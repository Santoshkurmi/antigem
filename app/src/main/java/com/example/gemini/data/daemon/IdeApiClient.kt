package com.example.gemini.data.daemon

import android.util.Log
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
    val path: String
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

object IdeApiClient {
    private const val TAG = "IdeApiClient"
    var baseUrl: String = "http://127.0.0.1:8080"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

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
                                path = obj.getString("path")
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
                                path = obj.getString("path")
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

    suspend fun createProject(name: String, template: String): ProjectItem? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("name", name)
                put("template", template)
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
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: $path", e)
            null
        }
    }

    suspend fun saveFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("path", path)
                put("content", content)
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/api/file/save")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: $path", e)
            false
        }
    }

    suspend fun patchFile(path: String, startLine: Int, endLine: Int, replacement: String): Boolean = withContext(Dispatchers.IO) {
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

        // Fallback to reading file directly via /api/file/read
        val candidatePaths = listOf(
            "/home/cat/.gemini/antigravity/mcp_config.json",
            "${System.getProperty("user.home")}/.gemini/antigravity/mcp_config.json",
            "/home/cat/.gemini/config/mcp_config.json"
        )
        for (p in candidatePaths) {
            val content = readFile(p)
            if (!content.isNullOrBlank()) return@withContext content
            try {
                val f = java.io.File(p)
                if (f.exists()) return@withContext f.readText()
            } catch (_: Exception) {}
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

        val candidatePaths = listOf(
            "/home/cat/.gemini/antigravity/mcp_config.json",
            "${System.getProperty("user.home")}/.gemini/antigravity/mcp_config.json"
        )
        for (p in candidatePaths) {
            val saved = saveFile(p, content)
            if (saved) return@withContext true
            try {
                val f = java.io.File(p)
                f.parentFile?.mkdirs()
                f.writeText(content)
                return@withContext true
            } catch (_: Exception) {}
        }
        false
    }
}
