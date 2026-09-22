package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants
import com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings
import com.example.gemini.data.remote.AgyHubClient.ProjectItem
import com.example.gemini.data.remote.core.AgyGrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated RPC service for workspace projects, global user settings, file reading, and media artifacts.
 */
class AgyProjectService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
    companion object {
        private const val TAG = "AgyProjectService"
        val instance by lazy { AgyProjectService() }
    }

    /**
     * Reads a file via LanguageServerService/ReadFile RPC.
     * Returns base64 encoded content string.
     */
    suspend fun readFileAsBase64(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formattedUri = if (uri.startsWith("file://") || uri.startsWith("http://") || uri.startsWith("https://")) {
                uri
            } else {
                "file://$uri"
            }
            val payload = JSONObject().apply {
                put("uri", formattedUri)
            }.toString()
            val res = grpcClient.callUnary("ReadFile", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("ReadFile failed"))
            }
            val jsonStr = res.getOrThrow()
            val json = JSONObject(jsonStr)
            val content = json.optString("content", json.optString("data", ""))
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "readFileAsBase64 failed for $uri: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Saves a media file onto the daemon host as an artifact and returns its persistent uri.
     */
    suspend fun saveMediaAsArtifact(
        mimeType: String,
        base64Data: String,
        description: String,
        thumbnailBase64: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("media", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("inlineData", base64Data)
                    put("description", description)
                    if (thumbnailBase64.isNotBlank()) {
                        put("thumbnail", thumbnailBase64)
                    }
                })
            }.toString()

            val res = grpcClient.callUnary("SaveMediaAsArtifact", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("SaveMediaAsArtifact failed"))
            }
            val json = JSONObject(res.getOrThrow())
            val uri = json.optString("uri", json.optString("path", ""))
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "saveMediaAsArtifact failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Deletes a media artifact file on the host.
     */
    suspend fun deleteMediaArtifact(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("uri", uri)
            }.toString()
            grpcClient.callUnary("DeleteMediaArtifact", payload, hubUrl).map { }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches live daemon user settings snapshot by subscribing to JetboxSubscribeToState
     * and reading the very first frame pushed by the daemon.
     */
    suspend fun fetchGlobalUserSettings(hubUrl: String = AuthPreferences.currentHubUrl): Result<GlobalUserSettings> = withContext(Dispatchers.IO) {
        try {
            val token = grpcClient.csrfManager.getCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val url = "$base/exa.language_server_pb.LanguageServerService/JetboxSubscribeToState"
            val frameBytes = com.example.gemini.data.remote.core.GrpcWebFrameCodec.encodeDataFrame("{}")
            val req = Request.Builder()
                .url(url)
                .post(frameBytes.toRequestBody(AgyGrpcClient.GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            grpcClient.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("JetboxSubscribeToState failed: HTTP ${resp.code}"))
                }
                val stream = resp.body?.byteStream() ?: return@withContext Result.failure(Exception("Empty response body"))
                val header = ByteArray(5)
                var read = 0
                while (read < 5) {
                    val r = stream.read(header, read, 5 - read)
                    if (r == -1) break
                    read += r
                }
                if (read < 5) return@withContext Result.failure(Exception("Incomplete gRPC header"))
                val len = ((header[1].toInt() and 0xFF) shl 24) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 8) or
                        (header[4].toInt() and 0xFF)
                if (len <= 0) return@withContext Result.failure(Exception("Invalid payload length: $len"))
                val payloadBytes = ByteArray(len)
                var payloadRead = 0
                while (payloadRead < len) {
                    val r = stream.read(payloadBytes, payloadRead, len - payloadRead)
                    if (r == -1) break
                    payloadRead += r
                }
                val jsonStr = String(payloadBytes, Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                val userSettings = json.optJSONObject("userConfig")?.optJSONObject("userSettings")
                val autoExec = userSettings?.optString("autoExecutionPolicy", "CASCADE_COMMANDS_AUTO_EXECUTION_OFF") ?: "CASCADE_COMMANDS_AUTO_EXECUTION_OFF"
                val fileAccess = userSettings?.optString("nonWorkspaceFileAccessPolicy", "AGENT_SETTING_POLICY_ASK") ?: "AGENT_SETTING_POLICY_ASK"
                val artifactReview = userSettings?.optString("artifactReviewMode", "ARTIFACT_REVIEW_MODE_ALWAYS") ?: "ARTIFACT_REVIEW_MODE_ALWAYS"
                val sandbox = userSettings?.optBoolean("enableTerminalSandbox", false) ?: false

                val grantsObj = userSettings?.optJSONObject("globalPermissionGrants")
                val allowList = mutableListOf<String>()
                grantsObj?.optJSONArray("allow")?.let { arr ->
                    for (i in 0 until arr.length()) allowList.add(arr.getString(i))
                }
                val denyList = mutableListOf<String>()
                grantsObj?.optJSONArray("deny")?.let { arr ->
                    for (i in 0 until arr.length()) denyList.add(arr.getString(i))
                }
                val askList = mutableListOf<String>()
                grantsObj?.optJSONArray("ask")?.let { arr ->
                    for (i in 0 until arr.length()) askList.add(arr.getString(i))
                }

                Result.success(GlobalUserSettings(
                    autoExecutionPolicy = autoExec,
                    nonWorkspaceFileAccessPolicy = fileAccess,
                    artifactReviewMode = artifactReview,
                    enableTerminalSandbox = sandbox,
                    globalPermissionGrants = GlobalPermissionGrants(
                        allow = allowList,
                        deny = denyList,
                        ask = askList
                    )
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchGlobalUserSettings failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Updates daemon user settings via JetboxWriteState
     */
    suspend fun writeGlobalUserSettings(
        autoExecutionPolicy: String? = null,
        nonWorkspaceFileAccessPolicy: String? = null,
        artifactReviewMode: String? = null,
        enableTerminalSandbox: Boolean? = null,
        globalPermissionGrants: GlobalPermissionGrants? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("userConfig", JSONObject().apply {
                put("userSettings", JSONObject().apply {
                    autoExecutionPolicy?.let { put("autoExecutionPolicy", it) }
                    nonWorkspaceFileAccessPolicy?.let { put("nonWorkspaceFileAccessPolicy", it) }
                    artifactReviewMode?.let { put("artifactReviewMode", it) }
                    enableTerminalSandbox?.let { put("enableTerminalSandbox", it) }
                    globalPermissionGrants?.let { grants ->
                        put("globalPermissionGrants", JSONObject().apply {
                            put("allow", JSONArray(grants.allow))
                            put("deny", JSONArray(grants.deny))
                            put("ask", JSONArray(grants.ask))
                        })
                    }
                })
            })
        }.toString()
        return grpcClient.executeGrpcWebCall("JetboxWriteState", payload, hubUrl).map { }
    }

    /**
     * Discovers all projects registered in the daemon via ProjectUpdatesStream and ReadProjects.
     */
    suspend fun fetchAllProjects(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<ProjectItem>> = withContext(Dispatchers.IO) {
        try {
            val token = grpcClient.csrfManager.getCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val streamUrl = "$base/exa.language_server_pb.LanguageServerService/ProjectUpdatesStream"
            val frameBytes = com.example.gemini.data.remote.core.GrpcWebFrameCodec.encodeDataFrame("{}")
            val req = Request.Builder()
                .url(streamUrl)
                .post(frameBytes.toRequestBody(AgyGrpcClient.GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            val projectIds = mutableListOf<String>()
            grpcClient.okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val stream = resp.body?.byteStream()
                    if (stream != null) {
                        val header = ByteArray(5)
                        var read = 0
                        while (read < 5) {
                            val r = stream.read(header, read, 5 - read)
                            if (r == -1) break
                            read += r
                        }
                        if (read == 5) {
                            val len = ((header[1].toInt() and 0xFF) shl 24) or
                                    ((header[2].toInt() and 0xFF) shl 16) or
                                    ((header[3].toInt() and 0xFF) shl 8) or
                                    (header[4].toInt() and 0xFF)
                            if (len > 0) {
                                val payloadBytes = ByteArray(len)
                                var payloadRead = 0
                                while (payloadRead < len) {
                                    val r = stream.read(payloadBytes, payloadRead, len - payloadRead)
                                    if (r == -1) break
                                    payloadRead += r
                                }
                                val jsonStr = String(payloadBytes, Charsets.UTF_8)
                                val obj = JSONObject(jsonStr)
                                val list = obj.optJSONObject("projectList")?.optJSONArray("projectIds")
                                if (list != null) {
                                    for (i in 0 until list.length()) {
                                        projectIds.add(list.getString(i))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (projectIds.isEmpty()) {
                projectIds.addAll(listOf("default-cli-project", "outside-of-project"))
            }

            val readPayload = JSONObject().apply {
                put("ids", JSONArray(projectIds))
            }.toString()

            val readRes = grpcClient.executeGrpcWebCall("ReadProjects", readPayload, hubUrl)
            if (readRes.isFailure) {
                return@withContext Result.failure(readRes.exceptionOrNull() ?: Exception("ReadProjects failed"))
            }

            val resObj = readRes.getOrNull()?.frames?.firstOrNull()?.let { JSONObject(it) } ?: JSONObject()
            val projectsArr = resObj.optJSONArray("projects") ?: JSONArray()
            val items = mutableListOf<ProjectItem>()

            for (i in 0 until projectsArr.length()) {
                val p = projectsArr.getJSONObject(i)
                val pid = p.optString("id", "")
                val name = p.optString("name", pid)
                val settings = p.optJSONObject("settings")
                val autoExec = settings?.optString("autoExecutionPolicy", "")?.takeIf { it.isNotBlank() }
                val fileAccess = settings?.optString("fileAccessPolicy", "")?.takeIf { it.isNotBlank() }
                val artifactReview = settings?.optString("artifactReviewMode", "")?.takeIf { it.isNotBlank() }
                val sandbox = if (settings?.has("sandboxMode") == true) settings.optBoolean("sandboxMode") else null

                val isInheriting = settings == null || (
                    (autoExec == null || autoExec == "CASCADE_COMMANDS_AUTO_EXECUTION_UNSPECIFIED" || autoExec.isBlank()) &&
                    (fileAccess == null || fileAccess == "AGENT_SETTING_POLICY_UNSPECIFIED" || fileAccess.isBlank()) &&
                    (artifactReview == null || artifactReview == "ARTIFACT_REVIEW_MODE_UNSPECIFIED" || artifactReview.isBlank()) &&
                    sandbox == null
                )

                items.add(ProjectItem(
                    id = pid,
                    name = name,
                    autoExecutionPolicy = autoExec,
                    fileAccessPolicy = fileAccess,
                    artifactReviewMode = artifactReview,
                    sandboxMode = sandbox,
                    isInheritingGlobal = isInheriting
                ))
            }

            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllProjects error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Updates a specific project's settings via UpdateProject RPC.
     */
    suspend fun updateProjectSettings(
        projectId: String,
        projectName: String = "",
        folderUris: List<String> = emptyList(),
        autoExecutionPolicy: String? = null,
        fileAccessPolicy: String? = null,
        artifactReviewMode: String? = null,
        sandboxMode: Boolean? = null,
        inheritGlobal: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("project", JSONObject().apply {
                put("id", projectId)
                if (projectName.isNotBlank()) {
                    put("name", projectName)
                }
                if (folderUris.isNotEmpty()) {
                    put("projectResources", JSONObject().apply {
                        val resArr = JSONArray()
                        for (f in folderUris) {
                            val norm = if (f.startsWith("file://")) f else "file://$f"
                            resArr.put(JSONObject().put("folderUri", norm))
                        }
                        put("resources", resArr)
                    })
                } else {
                    put("projectResources", JSONObject())
                }
                put("permissionGrants", JSONObject().apply {
                    put("permissionGrants", JSONObject().apply {
                        put("allow", JSONArray().put("read_url(example.com)"))
                    })
                })
                if (inheritGlobal) {
                    put("settings", JSONObject())
                } else {
                    put("settings", JSONObject().apply {
                        autoExecutionPolicy?.let { put("autoExecutionPolicy", it) }
                        fileAccessPolicy?.let { put("fileAccessPolicy", it) }
                        artifactReviewMode?.let { put("artifactReviewMode", it) }
                        sandboxMode?.let { put("sandboxMode", it) }
                    })
                }
            })
        }.toString()
        return grpcClient.executeGrpcWebCall("UpdateProject", payload, hubUrl).map { }
    }
}
