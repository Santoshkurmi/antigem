package com.example.gemini.data.daemon

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ProjectItem(
    val name: String,
    val path: String,
    val isCustom: Boolean = false
)

fun mergeProjects(
    conversations: List<com.example.gemini.domain.model.Conversation> = emptyList(),
    daemonProjects: List<ProjectItem> = emptyList(),
    activeProject: ProjectItem? = null
): List<ProjectItem> {
    val list = mutableListOf<ProjectItem>()
    val seenPaths = mutableSetOf<String>()

    // 1. Add conversations workspace paths (from AGY)
    conversations.forEach { conv ->
        if (conv.workspaceUri.isNotBlank()) {
            val path = conv.workspaceUri.removePrefix("file://").trimEnd('/')
            if (path.isNotBlank() && seenPaths.add(path)) {
                val name = java.io.File(path).name.ifBlank { "Workspace" }
                list.add(ProjectItem(name = name, path = path, isCustom = false))
            }
        }
    }

    // 2. Add daemon & saved projects
    daemonProjects.forEach { proj ->
        if (proj.path.isNotBlank() && seenPaths.add(proj.path)) {
            list.add(proj)
        }
    }

    // 3. Add active project if not already present
    activeProject?.let {
        if (it.path.isNotBlank() && seenPaths.add(it.path)) {
            list.add(it)
        }
    }

    return list
}

data class FsItemNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modTime: Long = 0,
    val ext: String = ""
)

data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val children: List<FileNode> = emptyList()
)

data class SearchMatch(
    val path: String,
    val lineNumber: Int,
    val lineText: String
)

data class FsBrowseResult(
    val currentPath: String = "",
    val parentPath: String = "",
    val homePath: String = "",
    val directories: List<ProjectItem> = emptyList(),
    val items: List<FsItemNode> = emptyList()
)

sealed class FileSaveResult {
    data class Success(val path: String, val hash: String) : FileSaveResult()
    data class Conflict(val diskHash: String, val diskContent: String, val message: String) : FileSaveResult()
    data class Error(val message: String) : FileSaveResult()
}

fun computeSha256(content: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(content.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

object IdeApiClient {
    private const val TAG = "IdeApiClient"
    var baseUrl: String
        get() = AuthPreferences.currentBridgeHttpUrl
        set(value) { AuthPreferences.currentBridgeHttpUrl = value }

    val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled) {
                addInterceptor(com.example.gemini.data.remote.inspector.NetworkInspectorInterceptor("IDE Bridge (HTTP)"))
            }
        }
        .build()

    private val client: OkHttpClient get() = okHttpClient

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkHealth failed: ${e.message}")
            false
        }
    }

    suspend fun getProjects(): List<ProjectItem> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/projects")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val list = mutableListOf<ProjectItem>()
                if (bodyStr.trim().startsWith("[")) {
                    val jsonArr = JSONArray(bodyStr)
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        list.add(
                            ProjectItem(
                                name = obj.getString("name"),
                                path = obj.getString("path"),
                                isCustom = obj.optBoolean("isCustom", false)
                            )
                        )
                    }
                } else {
                    val jsonObj = JSONObject(bodyStr)
                    val jsonArr = jsonObj.optJSONArray("projects") ?: JSONArray()
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        list.add(
                            ProjectItem(
                                name = obj.getString("name"),
                                path = obj.getString("path"),
                                isCustom = obj.optBoolean("isCustom", false)
                            )
                        )
                    }
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching projects", e)
            emptyList()
        }
    }

    suspend fun addSavedProject(path: String, name: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("path", path)
                put("name", name)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/projects/add")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "addSavedProject failed: ${e.message}")
            false
        }
    }

    suspend fun removeSavedProject(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("path", path)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/projects/remove")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "removeSavedProject failed: ${e.message}")
            false
        }
    }

    suspend fun createProject(name: String, template: String, path: String? = null): ProjectItem? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("name", name)
                put("template", template)
                if (!path.isNullOrBlank()) {
                    put("path", path)
                }
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/projects/create")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                ProjectItem(name = obj.getString("name"), path = obj.getString("path"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating project", e)
            null
        }
    }

    suspend fun browseDirectory(dir: String? = null): FsBrowseResult? = withContext(Dispatchers.IO) {
        try {
            val url = if (!dir.isNullOrBlank()) {
                "$baseUrl/api/fs/browse?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
            } else {
                "$baseUrl/api/fs/browse"
            }
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                val current = obj.optString("currentPath", "")
                val parent = obj.optString("parentPath", "")
                val home = obj.optString("homePath", "")
                val dirsArr = obj.optJSONArray("directories") ?: JSONArray()
                val dirs = mutableListOf<ProjectItem>()
                for (i in 0 until dirsArr.length()) {
                    val d = dirsArr.getJSONObject(i)
                    dirs.add(ProjectItem(d.optString("name"), d.optString("path")))
                }
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<FsItemNode>()
                for (i in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.getJSONObject(i)
                    items.add(
                        FsItemNode(
                            name = itemObj.optString("name"),
                            path = itemObj.optString("path"),
                            isDir = itemObj.optBoolean("isDir"),
                            size = itemObj.optLong("size", 0),
                            modTime = itemObj.optLong("modTime", 0),
                            ext = itemObj.optString("ext", "")
                        )
                    )
                }
                FsBrowseResult(current, parent, home, dirs, items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "browseDirectory failed: ${e.message}")
            null
        }
    }

    suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("path", path)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/fs/mkdir")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "createDirectory failed: ${e.message}")
            false
        }
    }

    suspend fun getFileTree(dir: String? = null): List<FileNode> = withContext(Dispatchers.IO) {
        try {
            val url = if (!dir.isNullOrBlank()) "$baseUrl/api/tree?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}" else "$baseUrl/api/tree"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                parseFileNodes(JSONArray(bodyStr))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file tree", e)
            emptyList()
        }
    }

    private fun parseFileNodes(array: JSONArray): List<FileNode> {
        val list = mutableListOf<FileNode>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val childrenArr = obj.optJSONArray("children")
            val children = if (childrenArr != null) parseFileNodes(childrenArr) else emptyList()

            list.add(
                FileNode(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    isDir = obj.getBoolean("isDir"),
                    size = obj.optLong("size", 0),
                    children = children
                )
            )
        }
        return list
    }

    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/file/read?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: $path", e)
            null
        }
    }

    suspend fun saveFileDetailed(
        path: String,
        content: String,
        expectedHash: String? = null,
        force: Boolean = false
    ): FileSaveResult = withContext(Dispatchers.IO) {
        try {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.JsonPrimitive(path))
                put("content", kotlinx.serialization.json.JsonPrimitive(content))
                if (!expectedHash.isNullOrBlank()) {
                    put("expectedHash", kotlinx.serialization.json.JsonPrimitive(expectedHash))
                }
                if (force) {
                    put("force", kotlinx.serialization.json.JsonPrimitive(true))
                }
            }.toString()
            Log.d(TAG, "[saveFileDetailed] Sending save request: url=$baseUrl/api/file/save, path='$path', contentLength=${content.length}, force=$force, expectedHash=$expectedHash")
            val request = Request.Builder()
                .url("$baseUrl/api/file/save")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                Log.d(TAG, "[saveFileDetailed] Response code=${response.code}, body=$bodyStr")
                if (response.code == 409) {
                    val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                    val diskHash = json.optString("diskHash")
                    val diskContent = json.optString("diskContent")
                    val msg = json.optString("message", "File on disk has been modified externally")
                    FileSaveResult.Conflict(diskHash = diskHash, diskContent = diskContent, message = msg)
                } else if (response.isSuccessful) {
                    val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                    val hash = json.optString("hash").ifBlank { computeSha256(content) }
                    FileSaveResult.Success(path = path, hash = hash)
                } else {
                    FileSaveResult.Error("Save failed with HTTP ${response.code}: $bodyStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: $path", e)
            FileSaveResult.Error(e.message ?: "Network error saving file")
        }
    }

    suspend fun saveFile(
        path: String,
        content: String,
        expectedHash: String? = null,
        force: Boolean = false
    ): Boolean {
        return saveFileDetailed(path, content, expectedHash, force) is FileSaveResult.Success
    }

    suspend fun patchFile(path: String, startLine: Int, endLine: Int, replacement: String): Boolean = withContext(Dispatchers.IO) {
        com.example.gemini.data.remote.HubMediaResolver.invalidateDocument(path)
        try {
            val payload = JSONObject().apply {
                put("path", path)
                put("startLine", startLine)
                put("endLine", endLine)
                put("replacement", replacement)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/patch")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error patching file: $path", e)
            false
        }
    }

    suspend fun createFileOrDir(path: String, isDir: Boolean): Boolean = withContext(Dispatchers.IO) {
        com.example.gemini.data.remote.HubMediaResolver.invalidateDocument(path)
        try {
            val payload = JSONObject().apply {
                put("path", path)
                put("isDir", isDir)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/create")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteFileOrDir(path: String): Boolean = withContext(Dispatchers.IO) {
        com.example.gemini.data.remote.HubMediaResolver.invalidateDocument(path)
        try {
            val payload = JSONObject().apply {
                put("path", path)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/delete")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun renameFileOrDir(path: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        com.example.gemini.data.remote.HubMediaResolver.invalidateDocument(path)
        com.example.gemini.data.remote.HubMediaResolver.invalidateDocument(newPath)
        try {
            val payload = JSONObject().apply {
                put("path", path)
                put("newPath", newPath)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/rename")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun copyFileOrDir(sourcePath: String, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("sourcePath", sourcePath)
                put("targetPath", targetPath)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/copy")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "copyFileOrDir failed: ${e.message}")
            false
        }
    }

    suspend fun searchCode(query: String, dir: String? = null): List<SearchMatch> = withContext(Dispatchers.IO) {
        try {
            val encodedQ = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (!dir.isNullOrBlank()) {
                "$baseUrl/api/search?q=$encodedQ&dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
            } else {
                "$baseUrl/api/search?q=$encodedQ"
            }

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(bodyStr)
                val matches = mutableListOf<SearchMatch>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    matches.add(
                        SearchMatch(
                            path = obj.getString("path"),
                            lineNumber = obj.getInt("lineNumber"),
                            lineText = obj.getString("lineText")
                        )
                    )
                }
                matches
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getEffectiveConfigPath(): String {
        return "~/.gemini/config/mcp_config.json"
    }

    suspend fun getMcpConfig(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/api/mcp/config").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use null
                    if (body.trim().startsWith("{")) {
                        val obj = JSONObject(body)
                        if (obj.has("content")) return@withContext obj.getString("content")
                        return@withContext body
                    }
                    return@withContext body
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getMcpConfig via /api/mcp/config failed: ${e.message}")
        }
        // Fallback to relative file read via IDE daemon
        try {
            val content = readFile("~/.gemini/config/mcp_config.json")
            if (!content.isNullOrBlank()) return@withContext content
            val legacyContent = readFile("~/.gemini/antigravity/mcp_config.json")
            if (!legacyContent.isNullOrBlank()) return@withContext legacyContent
        } catch (e: Exception) {
            Log.w(TAG, "getMcpConfig file fallback failed: ${e.message}")
        }
        null
    }

    suspend fun saveMcpConfig(content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("content", content)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/api/mcp/config")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "saveMcpConfig via /api/mcp/config failed: ${e.message}")
        }
        // Fallback to relative file save via IDE daemon
        try {
            val savedPrimary = saveFile("~/.gemini/config/mcp_config.json", content)
            val savedLegacy = saveFile("~/.gemini/antigravity/mcp_config.json", content)
            return@withContext savedPrimary || savedLegacy
        } catch (e: Exception) {
            Log.w(TAG, "saveMcpConfig file fallback failed: ${e.message}")
        }
        false
    }
}
