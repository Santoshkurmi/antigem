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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitFileStatus(
    val path: String,
    val oldPath: String? = null,
    val status: String, // "M", "A", "D", "U", "R"
    val staged: Boolean
)

data class GitStatusResponse(
    val branch: String = "HEAD",
    val tracking: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val stagedFiles: List<GitFileStatus> = emptyList(),
    val unstagedFiles: List<GitFileStatus> = emptyList(),
    val untrackedFiles: List<GitFileStatus> = emptyList(),
    val hasStash: Boolean = false,
    val error: String? = null
) {
    val totalChangesCount: Int
        get() = stagedFiles.size + unstagedFiles.size + untrackedFiles.size

    val hasChanges: Boolean
        get() = totalChangesCount > 0
}

data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean
)

data class GitCommitLog(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String
)

data class GitDiffResponse(
    val path: String,
    val staged: Boolean,
    val diff: String,
    val additions: Int,
    val deletions: Int
)

object GitApiClient {
    private const val TAG = "GitApiClient"
    var baseUrl: String
        get() = AuthPreferences.currentBridgeHttpUrl
        set(value) { AuthPreferences.currentBridgeHttpUrl = value }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun getStatus(projectPath: String): GitStatusResponse? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/status?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)

                val staged = parseFileList(json.optJSONArray("stagedFiles"), staged = true)
                val unstaged = parseFileList(json.optJSONArray("unstagedFiles"), staged = false)
                val untracked = parseFileList(json.optJSONArray("untrackedFiles"), staged = false)

                GitStatusResponse(
                    branch = json.optString("branch", "HEAD"),
                    tracking = json.optString("tracking").takeIf { it.isNotBlank() },
                    ahead = json.optInt("ahead", 0),
                    behind = json.optInt("behind", 0),
                    stagedFiles = staged,
                    unstagedFiles = unstaged,
                    untrackedFiles = untracked,
                    hasStash = json.optBoolean("hasStash", false),
                    error = json.optString("error").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStatus failed: ${e.message}")
            null
        }
    }

    private fun parseFileList(arr: JSONArray?, staged: Boolean): List<GitFileStatus> {
        if (arr == null) return emptyList()
        val list = mutableListOf<GitFileStatus>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                GitFileStatus(
                    path = obj.getString("path"),
                    oldPath = obj.optString("oldPath").takeIf { it.isNotBlank() },
                    status = obj.optString("status", "M"),
                    staged = staged
                )
            )
        }
        return list
    }

    suspend fun getBranches(projectPath: String): List<GitBranchInfo> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/branches?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val arr = json.optJSONArray("branches") ?: return@withContext emptyList()
                val list = mutableListOf<GitBranchInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        GitBranchInfo(
                            name = obj.getString("name"),
                            isCurrent = obj.optBoolean("isCurrent", false),
                            isRemote = obj.optBoolean("isRemote", false)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBranches failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun checkoutBranch(projectPath: String, branch: String, create: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("branch", branch)
                put("create", create)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/checkout")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkoutBranch failed: ${e.message}")
            false
        }
    }

    suspend fun stage(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stage")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "stage failed: ${e.message}")
            false
        }
    }

    suspend fun unstage(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/unstage")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "unstage failed: ${e.message}")
            false
        }
    }

    suspend fun discard(projectPath: String, paths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("paths", JSONArray(paths))
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/discard")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "discard failed: ${e.message}")
            false
        }
    }

    suspend fun commit(projectPath: String, message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("message", message)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/commit")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "commit failed: ${e.message}")
            false
        }
    }

    suspend fun push(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/push")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "push failed: ${e.message}")
            false
        }
    }

    suspend fun pull(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/pull")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "pull failed: ${e.message}")
            false
        }
    }

    suspend fun stash(projectPath: String, message: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                put("message", message)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stash")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "stash failed: ${e.message}")
            false
        }
    }

    suspend fun stashPop(projectPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/stash/pop")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "stashPop failed: ${e.message}")
            false
        }
    }

    suspend fun getDiff(projectPath: String, filePath: String, staged: Boolean = false): GitDiffResponse? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val url = "$baseUrl/api/git/diff?project=$encProj&file=$encFile&staged=$staged"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)

                GitDiffResponse(
                    path = json.optString("path", filePath),
                    staged = json.optBoolean("staged", staged),
                    diff = json.optString("diff", ""),
                    additions = json.optInt("additions", 0),
                    deletions = json.optInt("deletions", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDiff failed: ${e.message}")
            null
        }
    }

    suspend fun getLog(projectPath: String, limit: Int = 20): List<GitCommitLog> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/log?project=$enc&limit=$limit")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val arr = json.optJSONArray("commits") ?: return@withContext emptyList()
                val list = mutableListOf<GitCommitLog>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        GitCommitLog(
                            hash = obj.getString("hash"),
                            shortHash = obj.getString("shortHash"),
                            author = obj.getString("author"),
                            date = obj.getString("date"),
                            message = obj.getString("message")
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLog failed: ${e.message}")
            emptyList()
        }
    }
}
