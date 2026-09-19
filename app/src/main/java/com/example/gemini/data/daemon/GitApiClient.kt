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
    val isGitRepo: Boolean = true,
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

data class GitCommitFileChange(
    val path: String,
    val status: String
)

data class GitCommitDetails(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val subject: String,
    val body: String,
    val changedFiles: List<GitCommitFileChange> = emptyList()
)

data class GitConfig(
    val userName: String = "",
    val userEmail: String = "",
    val pullRebase: String = "",
    val remoteUrl: String = ""
)

data class GitActionResult(
    val success: Boolean,
    val output: String = "",
    val error: String = ""
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
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
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

                val isGitRepo = json.optBoolean("isGitRepo", true)
                val staged = parseFileList(json.optJSONArray("stagedFiles"), staged = true)
                val unstaged = parseFileList(json.optJSONArray("unstagedFiles"), staged = false)
                val untracked = parseFileList(json.optJSONArray("untrackedFiles"), staged = false)

                GitStatusResponse(
                    isGitRepo = isGitRepo,
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

    suspend fun initRepo(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/init")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", "Initialized Git repository"),
                    error = json.optString("error", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "initRepo failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Init failed")
        }
    }

    suspend fun getConfig(projectPath: String): GitConfig? = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/config?project=$enc")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                GitConfig(
                    userName = json.optString("userName", ""),
                    userEmail = json.optString("userEmail", ""),
                    pullRebase = json.optString("pullRebase", ""),
                    remoteUrl = json.optString("remoteUrl", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getConfig failed: ${e.message}")
            null
        }
    }

    suspend fun setConfig(
        projectPath: String,
        userName: String? = null,
        userEmail: String? = null,
        pullRebase: String? = null,
        remoteUrl: String? = null,
        isGlobal: Boolean = false
    ): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
                if (userName != null) put("userName", userName)
                if (userEmail != null) put("userEmail", userEmail)
                if (pullRebase != null) put("pullRebase", pullRebase)
                if (remoteUrl != null) put("remoteUrl", remoteUrl)
                put("isGlobal", isGlobal)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/config")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", "Configuration updated"),
                    error = json.optString("error", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "setConfig failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Failed to set config")
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
                    status = obj.getString("status"),
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

    suspend fun push(projectPath: String): Boolean {
        return pushDetailed(projectPath).success
    }

    suspend fun pushDetailed(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/push")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", ""),
                    error = json.optString("error", if (!response.isSuccessful) "Push failed with code ${response.code}" else "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "push failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Push network error")
        }
    }

    suspend fun pull(projectPath: String): Boolean {
        return pullDetailed(projectPath).success
    }

    suspend fun pullDetailed(projectPath: String): GitActionResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("project", projectPath)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/api/git/pull")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                GitActionResult(
                    success = response.isSuccessful && json.optBoolean("success", true),
                    output = json.optString("output", ""),
                    error = json.optString("error", if (!response.isSuccessful) "Pull failed with code ${response.code}" else "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "pull failed: ${e.message}")
            GitActionResult(success = false, error = e.message ?: "Pull network error")
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

    suspend fun getLog(projectPath: String, limit: Int = 20, skip: Int = 0): List<GitCommitLog> = withContext(Dispatchers.IO) {
        try {
            val enc = URLEncoder.encode(projectPath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/log?project=$enc&limit=$limit&skip=$skip")
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

    suspend fun getCommitDetails(projectPath: String, hash: String): GitCommitDetails? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/details?project=$encProj&hash=$encHash")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val filesArr = json.optJSONArray("changedFiles") ?: JSONArray()
                val changedFiles = mutableListOf<GitCommitFileChange>()
                for (i in 0 until filesArr.length()) {
                    val fo = filesArr.getJSONObject(i)
                    changedFiles.add(
                        GitCommitFileChange(
                            path = fo.getString("path"),
                            status = fo.optString("status", "M")
                        )
                    )
                }
                GitCommitDetails(
                    hash = json.getString("hash"),
                    shortHash = json.optString("shortHash", hash.take(7)),
                    author = json.optString("author", ""),
                    date = json.optString("date", ""),
                    subject = json.optString("subject", ""),
                    body = json.optString("body", ""),
                    changedFiles = changedFiles
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitDetails failed: ${e.message}")
            null
        }
    }

    suspend fun getCommitFileDiff(projectPath: String, hash: String, filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/diff?project=$encProj&hash=$encHash&file=$encFile")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                json.optString("diff", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitFileDiff failed: ${e.message}")
            null
        }
    }

    suspend fun getCommitFileContent(projectPath: String, hash: String, filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val encProj = URLEncoder.encode(projectPath, "UTF-8")
            val encHash = URLEncoder.encode(hash, "UTF-8")
            val encFile = URLEncoder.encode(filePath, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/api/git/commit/content?project=$encProj&hash=$encHash&file=$encFile")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                json.optString("content", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommitFileContent failed: ${e.message}")
            null
        }
    }
}

