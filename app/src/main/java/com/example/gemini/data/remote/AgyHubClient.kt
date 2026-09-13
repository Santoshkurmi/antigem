package com.example.gemini.data.remote

import android.util.Log
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuotaGroup
import com.example.gemini.domain.model.QuotaSummaryResponse
import com.example.gemini.domain.model.QuotaWindowInfo
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.example.gemini.data.remote.dto.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * High-performance native Android client for the Antigravity (AGY) CLI Hub Daemon.
 * Communicates directly with http://127.0.0.1:8090 using Connect-RPC (JSON) and
 * binary 5-byte framed gRPC-Web streaming.
 */
class AgyHubClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        const val TAG = "AgyHubClient"
        const val DEFAULT_HUB_URL = "http://127.0.0.1:8090"
        val agyJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val GRPC_WEB_MEDIA_TYPE = "application/grpc-web+json".toMediaType()
        private val CSRF_PATTERN = Pattern.compile(""""csrfToken":\s*"([^"]+)"""")
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Dynamic model resolution registry - NO hardcoded model mapping enums!
        private val keyToModelEnum = ConcurrentHashMap<String, String>()
        private val nameToModelEnum = ConcurrentHashMap<String, String>()
        private val allValidEnums = CopyOnWriteArraySet<String>()
        @Volatile
        private var defaultModelEnum: String = ""

        fun normalizeToolName(name: String): String = when (name.lowercase().trim()) {
            "web_search", "search_web", "search" -> "web_search"
            "read_url", "read_url_content", "web_reader" -> "read_url"
            "run_command", "terminal", "bash" -> "bash"
            "view_file", "viewfile" -> "view_file"
            "edit_file", "modifyfile", "write_to_file", "replace_file_content", "codeaction", "filechange" -> "edit_file"
            "list_dir", "listdirectory" -> "list_dir"
            "find", "find_by_name" -> "find"
            "grep_search", "code_search" -> "grep_search"
            "generate_image", "generateimage" -> "generate_image"
            else -> name.lowercase().trim()
        }

        fun updateModelRegistry(models: List<AiModel>) {
            if (models.isEmpty()) return
            models.forEach { m ->
                allValidEnums.add(m.id)
                if (m.key.isNotBlank()) {
                    keyToModelEnum[m.key.lowercase()] = m.id
                }
                keyToModelEnum[m.displayName.lowercase()] = m.id
                if (m.baseName.isNotBlank()) {
                    nameToModelEnum[m.baseName.lowercase()] = m.id
                }
            }
            if (defaultModelEnum.isBlank() || !allValidEnums.contains(defaultModelEnum)) {
                defaultModelEnum = models.firstOrNull {
                    it.displayName.contains("flash", ignoreCase = true) || it.key.contains("flash", ignoreCase = true)
                }?.id ?: models.first().id
            }
        }

        fun resolveModelEnum(rawInput: String?): String {
            if (rawInput.isNullOrBlank()) return defaultModelEnum.ifBlank { "MODEL_PLACEHOLDER_M319" }

            // 1. If it's already a valid modelEnum known from daemon
            if (allValidEnums.contains(rawInput)) return rawInput

            val lower = rawInput.lowercase().trim()
            keyToModelEnum[lower]?.let { return it }
            nameToModelEnum[lower]?.let { return it }

            // 2. Substring search in registered keys
            for ((k, v) in keyToModelEnum) {
                if (lower.contains(k) || k.contains(lower)) return v
            }

            // 3. If it looks like a direct model enum (starts with MODEL_)
            if (rawInput.startsWith("MODEL_")) return rawInput

            return defaultModelEnum.ifBlank { rawInput }
        }

        fun formatQuotaResetCountdown(isoString: String?): String {
            if (isoString.isNullOrBlank()) return ""
            return try {
                val clean = isoString.substringBefore('.').substringBefore('Z')
                val target = ISO_FORMAT.parse(clean)?.time ?: return ""
                val diffMs = target - System.currentTimeMillis()
                if (diffMs <= 0) return "Resetting now"

                val totalMins = diffMs / (1000 * 60)
                val days = totalMins / (60 * 24)
                val hours = (totalMins % (60 * 24)) / 60
                val mins = totalMins % 60

                val parts = mutableListOf<String>()
                if (days > 0) parts.add("${days}d")
                if (hours > 0 || days > 0) parts.add("${hours}h")
                parts.add("${mins}m")
                "Resets in " + parts.joinToString(" ")
            } catch (e: Exception) {
                ""
            }
        }
    }

    @Volatile
    private var cachedCsrfToken: String? = null

    suspend fun getOrFetchCsrfToken(hubUrl: String = DEFAULT_HUB_URL): String = withContext(Dispatchers.IO) {
        val existing = cachedCsrfToken
        if (!existing.isNullOrBlank()) {
            return@withContext existing
        }
        val fetched = fetchCsrfToken(hubUrl)
        cachedCsrfToken = fetched
        fetched
    }

    fun clearCsrfToken() {
        cachedCsrfToken = null
    }

    private fun fetchCsrfToken(hubUrl: String): String {
        try {
            val req = Request.Builder()
                .url(hubUrl.trimEnd('/') + "/")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val matcher = CSRF_PATTERN.matcher(body)
                if (matcher.find()) {
                    val token = matcher.group(1)
                    if (!token.isNullOrBlank()) {
                        Log.d(TAG, "Extracted CSRF token: $token")
                        return token
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract CSRF token from $hubUrl: ${e.message}")
        }
        return ""
    }

    /**
     * Encodes a JSON byte array into a 5-byte length-prefixed gRPC-Web frame:
     * Byte 0: 0x00 (Data Frame)
     * Bytes 1-4: 32-bit unsigned Big-Endian length
     * Bytes 5+: Payload
     */
    fun encodeFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val frame = ByteArray(5 + len)
        frame[0] = 0x00
        frame[1] = ((len ushr 24) and 0xFF).toByte()
        frame[2] = ((len ushr 16) and 0xFF).toByte()
        frame[3] = ((len ushr 8) and 0xFF).toByte()
        frame[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, len)
        return frame
    }

    fun encodeFrame(jsonStr: String): ByteArray =
        encodeFrame(jsonStr.toByteArray(Charsets.UTF_8))

    /**
     * Executes a unary Connect-RPC call using application/json
     */
    suspend fun callUnary(
        endpoint: String,
        jsonBody: String = "{}",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getOrFetchCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val url = "$base/exa.language_server_pb.LanguageServerService/$endpoint"
            val req = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    // Retry once if token was invalid or expired
                    if (resp.code == 403 || resp.code == 401) {
                        clearCsrfToken()
                        val newToken = getOrFetchCsrfToken(hubUrl)
                        val retryReq = req.newBuilder()
                            .header("x-codeium-csrf-token", newToken)
                            .build()
                        client.newCall(retryReq).execute().use { retryResp ->
                            val retryBody = retryResp.body?.string() ?: ""
                            return@withContext if (retryResp.isSuccessful) {
                                Result.success(retryBody)
                            } else {
                                Result.failure(Exception("HTTP ${retryResp.code}: $retryBody"))
                            }
                        }
                    }
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $body"))
                }
                Result.success(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callUnary $endpoint failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Executes a streaming gRPC-Web call and yields parsed JSON frames
     */
    fun callStream(
        endpoint: String,
        jsonPayload: String = "{}",
        hubUrl: String = DEFAULT_HUB_URL
    ): Flow<String> = flow {
        val token = getOrFetchCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/$endpoint"
        val frameBytes = encodeFrame(jsonPayload)

        val req = Request.Builder()
            .url(url)
            .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
            .header("Content-Type", "application/grpc-web+json")
            .header("X-Grpc-Web", "1")
            .apply {
                if (token.isNotBlank()) {
                    header("x-codeium-csrf-token", token)
                }
            }
            .build()

        val call = client.newCall(req)
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
            call.cancel()
        }
        val resp = call.execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: "HTTP ${resp.code}"
            resp.close()
            throw Exception("gRPC stream failed (${resp.code}): $err")
        }

        val stream = resp.body?.byteStream() ?: run {
            resp.close()
            return@flow
        }

        try {
            readStreamFrames(stream) { frameJson ->
                emit(frameJson)
            }
        } finally {
            resp.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads 5-byte header and parses frames from input stream
     */
    private suspend fun readStreamFrames(
        stream: InputStream,
        onFrame: suspend (String) -> Unit
    ) {
        val header = ByteArray(5)
        while (true) {
            var read = 0
            while (read < 5) {
                val r = stream.read(header, read, 5 - read)
                if (r == -1) return
                read += r
            }

            val flag = header[0].toInt() and 0xFF
            val len = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)

            if (len <= 0) continue

            val payload = ByteArray(len)
            var pRead = 0
            while (pRead < len) {
                val r = stream.read(payload, pRead, len - pRead)
                if (r == -1) return
                pRead += r
            }

            // flag 0x00 is Data Frame, flag 0x80 is Trailers Frame
            if (flag == 0x00) {
                val jsonStr = String(payload, Charsets.UTF_8)
                onFrame(jsonStr)
            } else if (flag == 0x80) {
                val trailerText = String(payload, Charsets.UTF_8)
                val statusMatch = Regex("grpc-status:\\s*(\\d+)").find(trailerText)
                val statusCode = statusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                if (statusCode != 0) {
                    val messageMatch = Regex("grpc-message:\\s*([^\r\n]+)").find(trailerText)
                    val msg = messageMatch?.groupValues?.getOrNull(1) ?: "gRPC status $statusCode"
                    Log.w(TAG, "gRPC stream finished with error ($statusCode): $msg")
                }
                break
            }
        }
    }

    data class GrpcResult(val frames: List<String>, val status: Int, val message: String?)

    private fun parseGrpcWebBody(bodyBytes: ByteArray): GrpcResult {
        val jsonFrames = mutableListOf<String>()
        var status = 0
        var message: String? = null
        var offset = 0
        while (offset + 5 <= bodyBytes.size) {
            val flag = bodyBytes[offset].toInt() and 0xFF
            val len = ((bodyBytes[offset + 1].toInt() and 0xFF) shl 24) or
                    ((bodyBytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bodyBytes[offset + 3].toInt() and 0xFF) shl 8) or
                    (bodyBytes[offset + 4].toInt() and 0xFF)
            offset += 5
            if (len <= 0) continue
            if (offset + len > bodyBytes.size) break

            val payload = bodyBytes.copyOfRange(offset, offset + len)
            offset += len

            if (flag == 0x00) {
                jsonFrames.add(String(payload, Charsets.UTF_8))
            } else if (flag == 0x80) {
                val trailerText = String(payload, Charsets.UTF_8)
                val statusMatch = Regex("grpc-status:\\s*(\\d+)").find(trailerText)
                val messageMatch = Regex("grpc-message:\\s*([^\r\n]+)").find(trailerText)
                status = statusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                message = messageMatch?.groupValues?.getOrNull(1)
            }
        }
        return GrpcResult(jsonFrames, status, message)
    }

    /**
     * Executes a unary gRPC-Web call with full status and trailer verification
     */
    suspend fun executeGrpcWebCall(
        endpoint: String,
        payloadJson: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<GrpcResult> = withContext(Dispatchers.IO) {
        val token = getOrFetchCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/$endpoint"
        val frameBytes = encodeFrame(payloadJson)

        try {
            val req = Request.Builder()
                .url(url)
                .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(Exception("$endpoint failed: $err"))
                }
                val headerStatus = resp.header("grpc-status")?.toIntOrNull()
                if (headerStatus != null && headerStatus != 0) {
                    val headerMsg = resp.header("grpc-message") ?: "gRPC status $headerStatus"
                    return@withContext Result.failure(Exception("$endpoint error ($headerStatus): $headerMsg"))
                }
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val res = parseGrpcWebBody(bodyBytes)
                if (res.status != 0) {
                    val errMsg = res.message ?: "gRPC error status ${res.status}"
                    return@withContext Result.failure(Exception("$endpoint error (${res.status}): $errMsg"))
                }
                Result.success(res)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeGrpcWebCall $endpoint failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== AUTHENTICATION METHODS ====================

    data class AgyAuthInfo(
        val isLoggedIn: Boolean = false,
        val username: String = "",
        val homeDir: String = "",
        val userTier: String = "",
        val profilePictureUrl: String? = null,
        val grantedScopes: List<String> = emptyList(),
        val isOffline: Boolean = false
    )

    suspend fun login(hubUrl: String = DEFAULT_HUB_URL): Result<Unit> =
        callUnary("Login", JSONObject().apply { put("isGcpTos", false) }.toString(), hubUrl).map { }

    suspend fun authLogout(hubUrl: String = DEFAULT_HUB_URL): Result<Unit> =
        callUnary("AuthLogout", "{}", hubUrl).map { }

    suspend fun getAuthStatus(hubUrl: String = DEFAULT_HUB_URL): Result<Boolean> = withContext(Dispatchers.IO) {
        callUnary("GetAuthStatus", "{}", hubUrl).map { body ->
            try {
                val json = JSONObject(body)
                val authResult = json.optJSONObject("authResult") ?: json
                authResult.optBoolean("hasValidAuth", false)
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun getLocalUserInfo(hubUrl: String = DEFAULT_HUB_URL): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        callUnary("GetLocalUserInfo", "{}", hubUrl).map { body ->
            val json = JSONObject(body)
            val username = json.optString("username", "")
            val homeDir = json.optString("homeDirUri", "")
            username to homeDir
        }
    }

    suspend fun fetchDetailedAuthInfo(hubUrl: String = DEFAULT_HUB_URL): Result<AgyAuthInfo> = withContext(Dispatchers.IO) {
        try {
            val authResultCall = callUnary("GetAuthStatus", "{}", hubUrl)
            if (authResultCall.isFailure) {
                return@withContext Result.failure(authResultCall.exceptionOrNull() ?: Exception("Failed to query GetAuthStatus"))
            }
            val authBody = authResultCall.getOrThrow()
            val authJson = JSONObject(authBody)
            val authResult = authJson.optJSONObject("authResult")
            val hasValidAuth = authResult?.optBoolean("hasValidAuth", false) ?: false

            if (!hasValidAuth) {
                return@withContext Result.success(AgyAuthInfo(isLoggedIn = false))
            }

            val scopesList = mutableListOf<String>()
            val scopesArr = authResult?.optJSONArray("grantedScopes")
            if (scopesArr != null) {
                for (i in 0 until scopesArr.length()) {
                    scopesList.add(scopesArr.getString(i))
                }
            }

            var username = ""
            var homeDir = ""
            try {
                val userBody = callUnary("GetLocalUserInfo", "{}", hubUrl).getOrNull() ?: "{}"
                val userJson = JSONObject(userBody)
                username = userJson.optString("username", "")
                homeDir = userJson.optString("homeDirUri", "")
            } catch (_: Exception) {}

            var userTier = ""
            var profilePic: String? = null
            try {
                val statusBody = callUnary("GetUserStatus", "{}", hubUrl).getOrNull() ?: "{}"
                val statusJson = JSONObject(statusBody)
                val userStatus = statusJson.optJSONObject("userStatus")
                val tierObj = userStatus?.optJSONObject("userTier")
                userTier = tierObj?.optString("name", "") ?: ""
                profilePic = userStatus?.optString("profilePictureUrl", "")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {}

            Result.success(
                AgyAuthInfo(
                    isLoggedIn = true,
                    username = username,
                    homeDir = homeDir,
                    userTier = userTier,
                    profilePictureUrl = profilePic,
                    grantedScopes = scopesList
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== CONVERSATION MANAGEMENT ====================

    data class SummariesUpdate(
        val updated: List<Conversation>,
        val removedIds: Set<String>
    )

    /**
     * Subscribes to live conversation summaries via JetboxSubscribeToSummaries.
     * Streams conversation updates directly from daemon without local caching.
     */
    fun subscribeToSummaries(hubUrl: String = DEFAULT_HUB_URL): Flow<SummariesUpdate> = flow {
        callStream("JetboxSubscribeToSummaries", "{}", hubUrl).collect { frameJson ->
            try {
                val root = JSONObject(frameJson)
                val updates = root.optJSONObject("updates")
                if (updates != null) {
                    val frameList = mutableListOf<Conversation>()
                    val removedIds = mutableSetOf<String>()
                    val keys = updates.keys()
                    while (keys.hasNext()) {
                        val cid = keys.next()
                        val obj = updates.getJSONObject(cid)
                        val annotations = obj.optJSONObject("annotations")
                        val annTitle = annotations?.optString("title")?.takeIf { it.isNotBlank() }
                        val rawSummary = obj.optString("summary", "").takeIf { it.isNotBlank() }
                        val stepCount = obj.optInt("stepCount", 0)
                        val status = obj.optString("status", "")
                        val isDeleted = status.contains("DELETED", ignoreCase = true)

                        val hasContent = (annTitle != null || rawSummary != null || stepCount > 0) && !isDeleted
                        if (!hasContent) {
                            removedIds.add(cid)
                            continue
                        }

                        val summary = annTitle ?: rawSummary ?: "Conversation"
                        val lastModStr = obj.optString("lastModifiedTime", "")
                        val isRunning = status.contains("RUNNING", ignoreCase = true)

                        var lastModEpoch = System.currentTimeMillis()
                        if (lastModStr.isNotBlank()) {
                            try {
                                val cleanIso = if (lastModStr.length > 19) lastModStr.substring(0, 19) else lastModStr
                                lastModEpoch = ISO_FORMAT.parse(cleanIso)?.time ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                // Fallback to current time
                            }
                        }

                        val wsUri = obj.optJSONObject("trajectoryMetadata")?.optJSONArray("workspaceUris")?.optString(0)
                            ?.takeIf { it.isNotBlank() }
                            ?: obj.optJSONArray("workspaces")?.optJSONObject(0)?.optString("workspaceFolderAbsoluteUri")
                            ?.takeIf { it.isNotBlank() }
                            ?: obj.optString("workspaceUri").takeIf { it.isNotBlank() }
                            ?: ""

                        frameList.add(
                            Conversation(
                                id = cid,
                                title = summary,
                                modelId = "",
                                sessionId = cid,
                                summary = summary,
                                createdAt = lastModEpoch,
                                updatedAt = lastModEpoch,
                                isRunning = isRunning,
                                stepCount = stepCount,
                                workspaceUri = wsUri
                            )
                        )
                    }
                    if (frameList.isNotEmpty() || removedIds.isNotEmpty()) {
                        emit(SummariesUpdate(frameList, removedIds))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing conversation updates: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Gets raw step count for a conversation
     */
    suspend fun getRawStepCount(cascadeId: String, hubUrl: String = DEFAULT_HUB_URL): Int = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cascade_id", cascadeId)
                put("trajectory_verbosity", 2)
            }.toString()

            var count = 0
            callStream("GetCascadeTrajectorySteps", payload, hubUrl).collect { frameJson ->
                val json = JSONObject(frameJson)
                val stepsArr = json.optJSONArray("steps")
                if (stepsArr != null) {
                    count = stepsArr.length()
                }
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "getRawStepCount failed: ${e.message}")
            0
        }
    }

    /**
     * Forks conversation up to the specified stepIndex (or latest step if null)
     */
    suspend fun forkConversation(
        sourceCascadeId: String,
        forkAtStepIndex: Int? = null,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rawCount = getRawStepCount(sourceCascadeId, hubUrl)
            val targetStep = when {
                forkAtStepIndex != null -> forkAtStepIndex.coerceIn(0, (rawCount - 1).coerceAtLeast(0))
                rawCount > 0 -> rawCount - 1
                else -> 0
            }

            val payload = JSONObject().apply {
                put("sourceCascadeId", sourceCascadeId)
                put("forkAtStepIndex", targetStep)
            }.toString()

            val res = callUnary("ForkConversation", payload, hubUrl)
            res.map { body ->
                val json = JSONObject(body)
                json.optString("newCascadeId", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "forkConversation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Deletes a conversation trajectory from daemon storage
     */
    suspend fun deleteCascadeTrajectory(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
        }.toString()

        callUnary("DeleteCascadeTrajectory", payload, hubUrl).map { true }
    }

    /**
     * Starts a new conversation session on the daemon
     */
    suspend fun startCascade(
        cascadeId: String = UUID.randomUUID().toString(),
        modelEnum: String = "",
        workspaceUri: String = "",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        val cid = cascadeId
        val resolvedModel = resolveModelEnum(modelEnum)
        val normalizedUri = if (workspaceUri.isNotBlank()) {
            if (workspaceUri.startsWith("file://")) workspaceUri else "file://$workspaceUri"
        } else ""
        val payload = JSONObject().apply {
            put("source", "CORTEX_TRAJECTORY_SOURCE_CASCADE_CLIENT")
            put("cascadeId", cid)
            put("requestedModel", resolvedModel)
            if (normalizedUri.isNotBlank()) {
                put("workspaceUris", JSONArray().put(normalizedUri))
                put("overrideWorkspaceUris", JSONArray().put(normalizedUri))
            } else {
                put("projectEnvConfig", JSONObject().apply {
                    put("projectId", "outside-of-project")
                    put("defaultProjectEnvironment", JSONObject())
                })
            }
        }.toString()

        executeGrpcWebCall("StartCascade", payload, hubUrl).map { cid }
    }

    /**
     * Loads raw trajectory steps for a conversation via Connect-RPC Unary
     */
    suspend fun getCascadeTrajectorySteps(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cascadeId", cascadeId)
                put("trajectoryVerbosity", 2)
            }.toString()
            callUnary("GetCascadeTrajectorySteps", payload, hubUrl)
        } catch (e: Exception) {
            Log.e(TAG, "getCascadeTrajectorySteps failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Reverts the entire last user message turn on the AGY hub daemon trajectory.
     */
    suspend fun revertLastUserMessage(
        cascadeId: String,
        modelEnum: String = "MODEL_PLACEHOLDER_M319",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val stepsRes = getCascadeTrajectorySteps(cascadeId, hubUrl)
            if (!stepsRes.isSuccess) {
                return@withContext Result.failure(stepsRes.exceptionOrNull() ?: Exception("Failed to get trajectory steps"))
            }
            val stepsJson = JSONObject(stepsRes.getOrThrow())
            val stepsArr = stepsJson.optJSONArray("steps") ?: JSONArray()
            var lastUserIdx = -1
            for (i in (stepsArr.length() - 1) downTo 0) {
                val st = stepsArr.optJSONObject(i) ?: continue
                if (st.has("userInput") || st.optString("type") == "CORTEX_STEP_TYPE_USER_INPUT") {
                    lastUserIdx = i
                    break
                }
            }

            if (lastUserIdx <= 0) {
                // First message or none: delete trajectory from server
                deleteCascadeTrajectory(cascadeId, hubUrl)
                return@withContext Result.success(-1)
            }

            val targetStep = (lastUserIdx - 1).coerceAtLeast(0)
            val revertPayload = JSONObject().apply {
                put("cascadeId", cascadeId)
                put("stepIndex", targetStep)
                put("overrideConfig", JSONObject().apply {
                    put("plannerConfig", JSONObject().apply {
                        put("toolConfig", JSONObject().apply {
                            put("runCommand", JSONObject().apply {
                                put("autoCommandConfig", JSONObject().apply {
                                    put("autoExecutionPolicy", "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER")
                                })
                            })
                            put("notifyUser", JSONObject())
                        })
                        put("requestedModel", JSONObject().apply {
                            put("model", modelEnum)
                        })
                        put("knowledgeConfig", JSONObject())
                        put("useAiCredits", false)
                        put("supportsLatexRendering", true)
                    })
                    put("conversationHistoryConfig", JSONObject())
                })
            }.toString()

            val revertRes = callUnary("RevertToCascadeStep", revertPayload, hubUrl)
            if (!revertRes.isSuccess) {
                return@withContext Result.failure(revertRes.exceptionOrNull() ?: Exception("RevertToCascadeStep failed"))
            }
            Result.success(targetStep)
        } catch (e: Exception) {
            Log.e(TAG, "revertLastUserMessage failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Reads a file via LanguageServerService/ReadFile RPC.
     * Returns base64 encoded content string.
     */
    suspend fun readFileAsBase64(
        uri: String,
        hubUrl: String = DEFAULT_HUB_URL
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
            val res = callUnary("ReadFile", payload, hubUrl)
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

    // ==================== MODELS & QUOTA TELEMETRY ====================

    /**
     * Fetches all available models from daemon via Unary Connect-RPC and maps them to AiModel
     */
    suspend fun getAvailableModels(
        forceRefresh: Boolean = false,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("forceRefresh", forceRefresh)
            }.toString()

            val res = callUnary("GetAvailableModels", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("Failed to fetch models"))
            }
            val modelsJson = res.getOrThrow()
            if (modelsJson.isBlank()) {
                return@withContext Result.failure(Exception("Empty models response"))
            }

            val root = JSONObject(modelsJson)
            val resp = root.optJSONObject("response") ?: root
            val rawModels = resp.optJSONObject("models") ?: JSONObject()
            val sorts = resp.optJSONArray("agentModelSorts") ?: JSONArray()

            val sortedIds = mutableListOf<String>()
            for (i in 0 until sorts.length()) {
                val sortObj = sorts.getJSONObject(i)
                val groups = sortObj.optJSONArray("groups") ?: JSONArray()
                for (j in 0 until groups.length()) {
                    val grp = groups.getJSONObject(j)
                    val modelIds = grp.optJSONArray("modelIds") ?: JSONArray()
                    for (k in 0 until modelIds.length()) {
                        val mid = modelIds.getString(k)
                        if (!sortedIds.contains(mid)) sortedIds.add(mid)
                    }
                }
            }

            val keysToProcess = if (sortedIds.isNotEmpty()) {
                sortedIds
            } else {
                val allKeys = mutableListOf<String>()
                val iter = rawModels.keys()
                while (iter.hasNext()) {
                    val k = iter.next()
                    val details = rawModels.optJSONObject(k)
                    if (details != null && !details.optBoolean("isInternal", false) && details.has("displayName")) {
                        allKeys.add(k)
                    }
                }
                allKeys
            }

            val resultList = mutableListOf<AiModel>()
            for (key in keysToProcess) {
                val details = rawModels.optJSONObject(key) ?: continue
                if (details.optBoolean("disabled", false)) continue

                val displayName = details.optString("displayName", key)
                val modelEnum = details.optString("model", key)
                val supportsThinking = details.optBoolean("supportsThinking", false)

                var baseName = displayName
                var tier: String? = null
                val tierMatch = Regex("^(.*?)\\s*\\((High|Medium|Low|Med|Thinking)\\)$", RegexOption.IGNORE_CASE).find(baseName)
                if (tierMatch != null) {
                    baseName = tierMatch.groupValues[1].trim()
                    tier = tierMatch.groupValues[2].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val family = when {
                    displayName.contains("claude", ignoreCase = true) || key.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                    else -> ModelFamily.GEMINI
                }

                resultList.add(
                    AiModel(
                        id = modelEnum,
                        displayName = displayName,
                        family = family,
                        supportsThinking = supportsThinking,
                        description = details.optString("description", ""),
                        key = key,
                        baseName = baseName,
                        tier = tier
                    )
                )
            }

            updateModelRegistry(resultList)
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableModels failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches user quota summary (5-hour and weekly buckets) from RetrieveUserQuotaSummary
     */
    suspend fun retrieveUserQuotaSummary(
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<QuotaSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val res = callUnary("RetrieveUserQuotaSummary", "{}", hubUrl)
            res.map { body ->
                val root = JSONObject(body)
                val resp = root.optJSONObject("response") ?: root
                val groupsArr = resp.optJSONArray("groups") ?: JSONArray()
                val groupsList = mutableListOf<ModelQuotaGroup>()

                for (i in 0 until groupsArr.length()) {
                    val grp = groupsArr.getJSONObject(i)
                    val dispName = grp.optString("displayName", "")
                    val desc = grp.optString("description", "")
                    val buckets = grp.optJSONArray("buckets") ?: JSONArray()

                    var fiveHourInfo: QuotaWindowInfo? = null
                    var weeklyInfo: QuotaWindowInfo? = null

                    for (j in 0 until buckets.length()) {
                        val b = buckets.getJSONObject(j)
                        val window = b.optString("window", "")
                        val remFraction = b.optDouble("remainingFraction", 1.0).toFloat()
                        val remPct = String.format(Locale.US, "%.1f%%", remFraction * 100f)
                        val usedPct = String.format(Locale.US, "%.1f%%", (1.0f - remFraction) * 100f)
                        val resetTime = b.optString("resetTime", "").takeIf { it.isNotBlank() }
                        val bDesc = b.optString("description", "")
                        val countdown = formatQuotaResetCountdown(resetTime)

                        val windowInfo = QuotaWindowInfo(
                            window = window,
                            displayName = b.optString("displayName", window),
                            remainingFraction = remFraction,
                            remainingPct = remPct,
                            usedPct = usedPct,
                            resetTime = resetTime,
                            countdown = countdown,
                            description = bDesc
                        )

                        if (window.contains("5h", ignoreCase = true)) {
                            fiveHourInfo = windowInfo
                        } else if (window.contains("week", ignoreCase = true) || window.contains("7d", ignoreCase = true)) {
                            weeklyInfo = windowInfo
                        }
                    }

                    val gId = when {
                        dispName.contains("gemini", ignoreCase = true) -> "gemini"
                        dispName.contains("claude", ignoreCase = true) || dispName.contains("gpt", ignoreCase = true) -> "claude_gpt"
                        else -> dispName.lowercase().replace(" ", "_")
                    }

                    groupsList.add(
                        ModelQuotaGroup(
                            groupId = gId,
                            groupName = dispName,
                            description = desc,
                            fiveHour = fiveHourInfo,
                            weekly = weeklyInfo
                        )
                    )
                }

                QuotaSummaryResponse(
                    groups = groupsList,
                    lastUpdated = ISO_FORMAT.format(Date())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "retrieveUserQuotaSummary failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== MESSAGING & EXECUTION ====================

data class AgyMediaItem(
    val mimeType: String,
    val base64: String,
    val durationSeconds: Int = 0,
    val description: String = "Voice note"
)

    /**
     * Sends a user prompt to SendUserCascadeMessage with structured options matching agyClient.js
     */
    suspend fun sendUserPrompt(
        cascadeId: String,
        text: String,
        modelEnum: String = "",
        thinkingBudget: Int = 8192,
        autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
        media: List<AgyMediaItem> = emptyList(),
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val resolvedModel = resolveModelEnum(modelEnum)
        val promptText = if (text.isNotBlank()) text else if (media.isNotEmpty()) (media.firstOrNull()?.description ?: "Voice note") else ""
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("items", JSONArray().put(JSONObject().put("text", promptText)))
            if (media.isNotEmpty()) {
                val mediaArr = JSONArray()
                for (m in media) {
                    mediaArr.put(JSONObject().apply {
                        put("mimeType", m.mimeType)
                        put("inlineData", m.base64)
                        if (m.durationSeconds > 0) {
                            put("durationSeconds", m.durationSeconds)
                        }
                        put("description", m.description)
                    })
                }
                put("media", mediaArr)
            }
            put("cascadeConfig", JSONObject().apply {
                put("plannerConfig", JSONObject().apply {
                    put("toolConfig", JSONObject().apply {
                        put("runCommand", JSONObject().apply {
                            put("autoCommandConfig", JSONObject().apply {
                                put("autoExecutionPolicy", autoExecutionPolicy)
                            })
                        })
                        put("notifyUser", JSONObject())
                    })
                    put("requestedModel", JSONObject().apply {
                        put("model", resolvedModel)
                    })
                    put("supportsThinking", thinkingBudget > 0)
                    put("thinkingBudget", thinkingBudget)
                    put("knowledgeConfig", JSONObject())
                    put("useAiCredits", false)
                    put("supportsLatexRendering", true)
                })
                put("executorConfig", JSONObject().apply {
                    put("useCoreDirect", true)
                })
                put("conversationHistoryConfig", JSONObject())
            })
            put("customAgentSpec", JSONObject().apply {
                put("builtinAgent", JSONObject().apply {
                    put("defaultAgent", JSONObject().apply {
                        put("isGoogle", false)
                        put("isInteractive", true)
                    })
                })
            })
            put("deliveryStrategy", "MESSAGE_DELIVERY_STRATEGY_WHEN_IDLE")
        }.toString()

        sendUserCascadeMessage(payload, hubUrl)
    }

    /**
     * Sends user prompt to SendUserCascadeMessage
     */
    suspend fun sendUserCascadeMessage(
        payloadJson: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> = executeGrpcWebCall("SendUserCascadeMessage", payloadJson, hubUrl).map { }

    /**
     * Streams real-time updates for an active conversation via StreamAgentStateUpdates
     */
    fun streamAgentStateUpdates(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("conversationId", cascadeId)
            put("subscriberId", "antigem-${System.currentTimeMillis()}")
            put("trajectoryVerbosity", 2)
            put("initialStepsPageBounds", JSONObject().put("startIndex", 0))
        }.toString()

        callStream("StreamAgentStateUpdates", payload, hubUrl).collect { frame ->
            emit(frame)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams real-time updates for an active conversation via StreamAgentStateUpdates,
     * decoded directly into typed [AgyStreamFrameDto] models.
     */
    fun streamAgentStateFrames(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Flow<AgyStreamFrameDto> = flow {
        val payload = JSONObject().apply {
            put("conversationId", cascadeId)
            put("subscriberId", "antigem-${System.currentTimeMillis()}")
            put("trajectoryVerbosity", 2)
            put("initialStepsPageBounds", JSONObject().put("startIndex", 0))
        }.toString()

        callStream("StreamAgentStateUpdates", payload, hubUrl).collect { frameJson ->
            try {
                val frame = agyJson.decodeFromString<AgyStreamFrameDto>(frameJson)
                emit(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode AgyStreamFrameDto: ${e.message}", e)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancels / aborts running generation or commands
     */
    suspend fun cancelCascadeInvocation(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("killBackgroundTasks", true)
        }.toString()
        return executeGrpcWebCall("CancelCascadeInvocation", payload, hubUrl).map { }
    }

    /**
     * Selectively cancels specific step indices (e.g. aborting an individual long-running
     * shell command or background task) without aborting the entire cascade session.
     */
    suspend fun cancelCascadeSteps(
        cascadeId: String,
        stepIndices: List<Int>,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val reqDto = com.example.gemini.data.remote.dto.CancelCascadeStepsRequestDto(
            cascadeId = cascadeId,
            stepIndices = stepIndices
        )
        val payload = agyJson.encodeToString(com.example.gemini.data.remote.dto.CancelCascadeStepsRequestDto.serializer(), reqDto)
        return executeGrpcWebCall("CancelCascadeSteps", payload, hubUrl).map { }
    }

    /**
     * Handles interactive user approval or denial for cascade permission steps
     */
    suspend fun handleCascadeUserInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        allow: Boolean = true,
        scope: String = "PERMISSION_SCOPE_ONCE",
        userDenyInstruction: String = "",
        interactionType: String = "permission",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val scopeStr = when (scope.uppercase()) {
            "PERMISSION_SCOPE_ONCE", "ONCE" -> "PERMISSION_SCOPE_ONCE"
            "PERMISSION_SCOPE_CONVERSATION", "CONVERSATION" -> "PERMISSION_SCOPE_CONVERSATION"
            "PERMISSION_SCOPE_WORKSPACE", "WORKSPACE", "PERMISSION_SCOPE_PROJECT", "PROJECT" -> "PERMISSION_SCOPE_PROJECT"
            "PERMISSION_SCOPE_GLOBAL", "GLOBAL", "PERMISSION_SCOPE_PERMANENT" -> "PERMISSION_SCOPE_PERMANENT"
            else -> if (scope.startsWith("PERMISSION_SCOPE_")) scope else "PERMISSION_SCOPE_ONCE"
        }

        fun makeNestedPayload(type: String): String {
            return JSONObject().apply {
                put("cascadeId", cascadeId)
                put("interaction", JSONObject().apply {
                    if (trajectoryId.isNotBlank()) {
                        put("trajectoryId", trajectoryId)
                    }
                    put("stepIndex", stepIndex)
                    when (type) {
                        "mcp" -> {
                            put("mcp", JSONObject().apply {
                                put("confirm", allow)
                            })
                        }
                        "approvalInteraction" -> {
                            put("approvalInteraction", JSONObject().apply {
                                put("confirm", allow)
                            })
                        }
                        "readUrlContent" -> {
                            put("readUrlContent", JSONObject().apply {
                                put("confirm", allow)
                            })
                        }
                        "browserAction" -> {
                            put("browserAction", JSONObject().apply {
                                put("confirm", allow)
                            })
                        }
                        else -> {
                            put("permission", JSONObject().apply {
                                put("allow", allow)
                                if (allow) {
                                    put("scope", scopeStr)
                                } else {
                                    put("userDenyInstruction", userDenyInstruction.ifBlank { "User rejected this command." })
                                }
                            })
                        }
                    }
                })
            }.toString()
        }

        val primaryTypes = listOf("permission", interactionType, "mcp", "approvalInteraction").distinct()
        var lastErr: Throwable? = null

        for (pType in primaryTypes) {
            val payload = makeNestedPayload(pType)
            
            // Strategy A: Connect-RPC application/json unary call
            val unaryRes = callUnary("HandleCascadeUserInteraction", payload, hubUrl)
            if (unaryRes.isSuccess) {
                Log.d(TAG, "handleCascadeUserInteraction succeeded via Connect-RPC (type=$pType)")
                return Result.success(Unit)
            } else {
                lastErr = unaryRes.exceptionOrNull()
            }

            // Strategy B: gRPC-Web application/grpc-web+json framed call
            val grpcRes = executeGrpcWebCall("HandleCascadeUserInteraction", payload, hubUrl)
            if (grpcRes.isSuccess) {
                Log.d(TAG, "handleCascadeUserInteraction succeeded via gRPC-Web (type=$pType)")
                return Result.success(Unit)
            } else {
                lastErr = grpcRes.exceptionOrNull()
            }
        }

        // Recovery Strategy: ResolveOutstandingSteps if allow is true
        if (allow) {
            val resolveRes = resolveOutstandingSteps(cascadeId, hubUrl)
            if (resolveRes.isSuccess) return resolveRes
        }

        return Result.failure(lastErr ?: Exception("HandleCascadeUserInteraction failed across all payload formats"))
    }

    /**
     * Resolves all outstanding or blocking steps in a cascade
     */
    suspend fun resolveOutstandingSteps(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
        }.toString()
        return executeGrpcWebCall("ResolveOutstandingSteps", payload, hubUrl).map { }
    }

    data class GlobalUserSettings(
        val autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_OFF",
        val nonWorkspaceFileAccessPolicy: String = "AGENT_SETTING_POLICY_ASK",
        val artifactReviewMode: String = "ARTIFACT_REVIEW_MODE_ALWAYS",
        val enableTerminalSandbox: Boolean = false
    )

    /**
     * Fetches live daemon user settings snapshot by subscribing to JetboxSubscribeToState
     * and reading the very first frame pushed by the daemon.
     */
    suspend fun fetchGlobalUserSettings(hubUrl: String = DEFAULT_HUB_URL): Result<GlobalUserSettings> = withContext(Dispatchers.IO) {
        try {
            val token = getOrFetchCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val url = "$base/exa.language_server_pb.LanguageServerService/JetboxSubscribeToState"
            val frameBytes = encodeFrame("{}")
            val req = Request.Builder()
                .url(url)
                .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            client.newCall(req).execute().use { resp ->
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

                Result.success(GlobalUserSettings(
                    autoExecutionPolicy = autoExec,
                    nonWorkspaceFileAccessPolicy = fileAccess,
                    artifactReviewMode = artifactReview,
                    enableTerminalSandbox = sandbox
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
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("userConfig", JSONObject().apply {
                put("userSettings", JSONObject().apply {
                    autoExecutionPolicy?.let { put("autoExecutionPolicy", it) }
                    nonWorkspaceFileAccessPolicy?.let { put("nonWorkspaceFileAccessPolicy", it) }
                    artifactReviewMode?.let { put("artifactReviewMode", it) }
                    enableTerminalSandbox?.let { put("enableTerminalSandbox", it) }
                })
            })
        }.toString()
        return executeGrpcWebCall("JetboxWriteState", payload, hubUrl).map { }
    }

    data class ProjectItem(
        val id: String,
        val name: String,
        val autoExecutionPolicy: String? = null,
        val fileAccessPolicy: String? = null,
        val artifactReviewMode: String? = null,
        val sandboxMode: Boolean? = null,
        val isInheritingGlobal: Boolean = true
    )

    /**
     * Fetches all projects and their settings by reading ProjectUpdatesStream and ReadProjects
     */
    suspend fun fetchAllProjects(hubUrl: String = DEFAULT_HUB_URL): Result<List<ProjectItem>> = withContext(Dispatchers.IO) {
        try {
            val token = getOrFetchCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val streamUrl = "$base/exa.language_server_pb.LanguageServerService/ProjectUpdatesStream"
            val frameBytes = encodeFrame("{}")
            val req = Request.Builder()
                .url(streamUrl)
                .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            val projectIds = mutableListOf<String>()
            client.newCall(req).execute().use { resp ->
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

            val readRes = executeGrpcWebCall("ReadProjects", readPayload, hubUrl)
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
                val autoExec = settings?.optString("autoExecutionPolicy", null)
                val fileAccess = settings?.optString("fileAccessPolicy", null)
                val artifactReview = settings?.optString("artifactReviewMode", null)
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
     * Passing inheritGlobal = true sets settings to empty object {} so the project inherits global settings.
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
        hubUrl: String = DEFAULT_HUB_URL
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
        return executeGrpcWebCall("UpdateProject", payload, hubUrl).map { }
    }

    /**
     * Legacy helper updating daemon user settings
     */
    suspend fun setUserSettings(
        autoExecutionPolicy: String,
        enableTerminalSandbox: Boolean = false,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        return writeGlobalUserSettings(
            autoExecutionPolicy = autoExecutionPolicy,
            enableTerminalSandbox = enableTerminalSandbox,
            hubUrl = hubUrl
        )
    }

    /**
     * Finds the index of the last user step in a steps array
     */
    fun findLastUserStepIndex(steps: JSONArray): Int {
        var lastIndex = 0
        for (i in (steps.length() - 1) downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            val stepType = step.optString("type", "")
            if (stepType == "CORTEX_STEP_TYPE_USER_INPUT" || step.has("userInput")) {
                val stepInfo = step.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                return stepInfo?.optInt("stepIndex", step.optInt("stepIndex", i)) ?: step.optInt("stepIndex", i)
            }
        }
        return lastIndex
    }

    /**
     * Parses raw trajectory steps JSON into a list of ChatMessage turns (User and Assistant)
     */
    fun parseStepsToChatMessages(stepsJson: String, conversationId: String): List<ChatMessage> {
        try {
            val root = JSONObject(stepsJson)
            val steps = root.optJSONArray("steps")
                ?: root.optJSONObject("update")?.optJSONObject("mainTrajectoryUpdate")?.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: root.optJSONObject("mainTrajectoryUpdate")?.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: root.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: return emptyList()

            val status = root.optJSONObject("update")?.optString("status", "") ?: root.optString("status", "")
            return parseStepsArrayToChatMessages(steps, conversationId, status)
        } catch (e: Exception) {
            Log.e(TAG, "parseStepsToChatMessages error: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Extracts a ToolCall from a trajectory step if the step represents a tool invocation.
     */
    fun extractToolCallFromStep(
        step: JSONObject,
        stepIndex: Int,
        conversationId: String,
        cascadeStatus: String = ""
    ): ToolCall? {
        val meta = step.optJSONObject("metadata")
        val stepInfo = meta?.optJSONObject("sourceTrajectoryStepInfo")
        val actualStepIndex = when {
            stepInfo?.has("stepIndex") == true -> stepInfo.getInt("stepIndex")
            step.has("stepIndex") -> step.getInt("stepIndex")
            else -> stepIndex
        }
        val trajId = stepInfo?.optString("trajectoryId", "")?.takeIf { it.isNotBlank() }
            ?: step.optString("trajectoryId", "").takeIf { it.isNotBlank() }

        val reqInteraction = step.optJSONObject("requestedInteraction")
        val perm = reqInteraction?.optJSONObject("permission")
            ?: reqInteraction?.optJSONObject("confirmation")
            ?: step.optJSONObject("permission")
            ?: meta?.optJSONObject("permission")

        val stepType = step.optString("type", "")
        val interactionType = when {
            reqInteraction?.has("mcp") == true || step.has("mcpTool") || step.has("callMcpTool") || stepType.contains("MCP") ||
                reqInteraction?.optString("type")?.contains("MCP", ignoreCase = true) == true -> "mcp"
            perm != null || reqInteraction?.has("permission") == true -> "permission"
            reqInteraction?.has("approvalInteraction") == true || (step.has("generic") && perm == null) -> "approvalInteraction"
            reqInteraction?.has("readUrlContent") == true || step.has("readUrlContent") -> "readUrlContent"
            reqInteraction?.has("browserAction") == true -> "browserAction"
            else -> "permission"
        }

        val rawCall = extractRawToolCallFromStep(step, actualStepIndex, conversationId, cascadeStatus) ?: return null
        return rawCall.copy(
            stepIndex = actualStepIndex,
            trajectoryId = trajId ?: rawCall.trajectoryId,
            interactionType = rawCall.interactionType ?: interactionType
        )
    }

    private fun extractRawToolCallFromStep(
        step: JSONObject,
        stepIndex: Int,
        conversationId: String,
        cascadeStatus: String = ""
    ): ToolCall? {
        val meta = step.optJSONObject("metadata")
        val toolSummary = meta?.optString("toolSummary", "")?.trim() ?: ""
        val toolAction = meta?.optString("toolAction", "")?.trim() ?: ""
        val stepStatus = step.optString("status", "")
        val stepType = step.optString("type", "")

        val reqInteraction = step.optJSONObject("requestedInteraction")
        val perm = reqInteraction?.optJSONObject("permission")
            ?: reqInteraction?.optJSONObject("confirmation")
            ?: step.optJSONObject("permission")
            ?: meta?.optJSONObject("permission")

        val rc = step.optJSONObject("runCommand")
        val generic = step.optJSONObject("generic")
        val genericArgs = generic?.optJSONObject("args")

        val metaTc = meta?.optJSONObject("toolCall")
        val plannerPr = step.optJSONObject("plannerResponse")
        val callsArr = step.optJSONArray("tool_calls")
            ?: step.optJSONArray("toolCalls")
            ?: plannerPr?.optJSONArray("toolCalls")
            ?: plannerPr?.optJSONArray("tool_calls")
        val firstTc = callsArr?.optJSONObject(0)

        val realToolId = metaTc?.optString("id", "")?.takeIf { it.isNotBlank() }
            ?: firstTc?.optString("id", "")?.takeIf { it.isNotBlank() }
            ?: step.optString("callId", "").takeIf { it.isNotBlank() }
            ?: step.optString("toolCallId", "").takeIf { it.isNotBlank() }

        fun makeToolId(prefix: String): String {
            return stepIndex.toString()
        }

        fun parseArgsJson(obj: JSONObject?): JSONObject? {
            if (obj == null) return null
            val direct = obj.optJSONObject("args") ?: obj.optJSONObject("argsJson") ?: obj.optJSONObject("arguments")
            if (direct != null) return direct
            val jsonStr = obj.optString("argumentsJson", "").takeIf { it.isNotBlank() }
                ?: obj.optString("args", "").takeIf { it.isNotBlank() }
            if (jsonStr != null) {
                try { return JSONObject(jsonStr) } catch (_: Exception) {}
            }
            return null
        }
        val tcArgs = parseArgsJson(firstTc) ?: parseArgsJson(metaTc) ?: genericArgs

        val isStepRunning = stepStatus == "CORTEX_STEP_STATUS_RUNNING" ||
                stepStatus == "CORTEX_STEP_STATUS_PENDING" ||
                stepStatus == "CORTEX_STEP_STATUS_GENERATING" ||
                stepStatus == "RUNNING"
        val isStepDone = stepStatus == "CORTEX_STEP_STATUS_DONE" ||
                stepStatus == "DONE" ||
                stepStatus == "SUCCESS"

        val isProposedRunCommand = rc != null && rc.has("proposedCommandLine") &&
                rc.optString("commandLine", "").isBlank() &&
                (rc.optJSONObject("combinedOutput")?.optString("full") ?: rc.optString("output", "")).isBlank() &&
                !rc.has("exitCode")

        val isProposedGenericCommand = genericArgs?.has("CommandLine") == true && !step.has("runCommand")

        val isWaitingPermission = !isStepRunning && !isStepDone && (
            perm != null ||
            (reqInteraction != null && reqInteraction.length() > 0) ||
            stepType == "CORTEX_STEP_TYPE_CONFIRM" ||
            stepStatus == "CORTEX_STEP_STATUS_WAITING" ||
            stepStatus == "WAITING" ||
            isProposedRunCommand ||
            isProposedGenericCommand
        )

        fun resolveStatus(hasOutput: Boolean, isPending: Boolean = false): String {
            return when {
                stepStatus == "CORTEX_STEP_STATUS_DONE" || stepStatus == "DONE" || stepStatus == "SUCCESS" -> "SUCCESS"
                stepStatus == "CORTEX_STEP_STATUS_ERROR" || stepStatus == "ERROR" || stepStatus == "FAILED" -> "FAILED"
                stepStatus == "CORTEX_STEP_STATUS_CANCELLED" || stepStatus == "CANCELLED" || stepStatus == "REJECTED" -> "REJECTED"
                stepStatus == "CORTEX_STEP_STATUS_WAITING" || stepStatus == "WAITING" || isPending -> "PENDING_APPROVAL"
                stepStatus == "CORTEX_STEP_STATUS_RUNNING" || stepStatus == "CORTEX_STEP_STATUS_PENDING" || stepStatus == "CORTEX_STEP_STATUS_GENERATING" || stepStatus == "RUNNING" -> "RUNNING"
                else -> if (isPending) "PENDING_APPROVAL" else if (hasOutput) "SUCCESS" else "RUNNING"
            }
        }

        // 0. Explicit Confirmation / Permission Request
        if (isWaitingPermission) {
            val isMcpWaiting = reqInteraction?.has("mcp") == true ||
                reqInteraction?.optString("type")?.contains("MCP", ignoreCase = true) == true ||
                step.has("mcpTool") || step.has("callMcpTool") || stepType.contains("MCP") ||
                genericArgs?.has("ServerName") == true || tcArgs?.has("ServerName") == true

            if (isMcpWaiting) {
                val mcp = step.optJSONObject("mcpTool")
                    ?: step.optJSONObject("callMcpTool")
                    ?: step.optJSONObject("mcp")
                    ?: reqInteraction?.optJSONObject("mcp")
                val sName = mcp?.optString("serverName", "")?.ifBlank {
                    tcArgs?.optString("ServerName", tcArgs.optString("serverName", genericArgs?.optString("ServerName", ""))) ?: ""
                } ?: ""
                val tName = mcp?.optJSONObject("toolCall")?.optString("name", "")?.ifBlank {
                    mcp?.optString("toolName", mcp?.optString("name", ""))
                }?.ifBlank {
                    tcArgs?.optString("ToolName", tcArgs.optString("toolName", genericArgs?.optString("ToolName", ""))) ?: ""
                } ?: ""
                val cmdDisplay = if (sName.isNotBlank() && tName.isNotBlank()) "$sName / $tName"
                    else if (tName.isNotBlank()) tName
                    else toolSummary.ifBlank { "MCP Tool" }
                val normName = if (sName.isNotBlank() && tName.isNotBlank()) "mcp_${sName}_$tName"
                    else if (tName.isNotBlank()) "mcp_$tName"
                    else "mcp_tool"
                return ToolCall(
                    id = makeToolId("tool_mcp_"),
                    name = normName,
                    command = cmdDisplay,
                    output = toolAction.ifBlank { "[Awaiting confirmation]" },
                    status = "PENDING_APPROVAL",
                    interactionType = "mcp"
                )
            }

            val rawToolName = perm?.optString("toolName", "")?.ifBlank {
                meta?.optJSONObject("toolCall")?.optString("name", "") ?: ""
            }?.ifBlank {
                step.optJSONObject("generic")?.optString("name", "") ?: ""
            } ?: ""

            val cmd = when {
                rc != null && rc.optString("commandLine", rc.optString("proposedCommandLine", rc.optString("CommandLine", rc.optString("command", "")))).isNotBlank() ->
                    rc.optString("commandLine", rc.optString("proposedCommandLine", rc.optString("CommandLine", rc.optString("command", ""))))
                perm != null && perm.has("command") && perm.optString("command", "").isNotBlank() ->
                    perm.optString("command")
                perm?.optJSONObject("resource")?.optString("target", "")?.isNotBlank() == true ->
                    perm.getJSONObject("resource").getString("target")
                tcArgs?.has("CommandLine") == true ->
                    tcArgs.getString("CommandLine")
                tcArgs?.has("commandLine") == true ->
                    tcArgs.getString("commandLine")
                tcArgs?.has("command") == true ->
                    tcArgs.getString("command")
                tcArgs?.has("TargetFile") == true ->
                    tcArgs.getString("TargetFile")
                tcArgs?.has("AbsolutePath") == true ->
                    tcArgs.getString("AbsolutePath")
                tcArgs?.has("query") == true ->
                    tcArgs.getString("query")
                generic?.optString("name", "")?.isNotBlank() == true ->
                    generic.getString("name")
                else -> toolSummary.ifBlank { toolAction }
            }
            val normName = normalizeToolName(rawToolName.ifBlank { "bash" })
            return ToolCall(
                id = makeToolId("tool_"),
                name = normName,
                command = cmd.ifBlank { "Command execution" },
                output = toolAction.ifBlank { "[Awaiting confirmation]" },
                status = "PENDING_APPROVAL"
            )
        }

        val parsedArgs = parseArgsJson(genericArgs ?: tcArgs)
        val genericResultPayload = generic?.optJSONObject("result")?.optJSONObject("payload")

        // 0.5. Modern Generic Step Pattern
        if (stepType == "CORTEX_STEP_TYPE_GENERIC" || genericArgs != null || parsedArgs != null || (stepType.isBlank() && metaTc != null)) {
            val name = generic?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: metaTc?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: parsedArgs?.optString("ToolName", parsedArgs.optString("toolName", ""))?.takeIf { it.isNotBlank() }
                ?: ""

            if (name.isNotBlank() || (parsedArgs != null && parsedArgs.length() > 0)) {
                val mName = if (name.isBlank() && parsedArgs?.has("CommandLine") == true) "run_command" else name

                val cmd = when {
                    parsedArgs?.has("CommandLine") == true -> parsedArgs.optString("CommandLine", "")
                    parsedArgs?.has("commandLine") == true -> parsedArgs.optString("commandLine", "")
                    parsedArgs?.has("query") == true -> parsedArgs.optString("query", "")
                    parsedArgs?.has("AbsolutePath") == true -> parsedArgs.optString("AbsolutePath", "")
                    parsedArgs?.has("TargetFile") == true -> parsedArgs.optString("TargetFile", "")
                    parsedArgs?.has("DirectoryPath") == true -> parsedArgs.optString("DirectoryPath", "")
                    parsedArgs?.has("Prompt") == true -> parsedArgs.optString("Prompt", "")
                    parsedArgs?.has("ServerName") == true -> {
                        val s = parsedArgs.optString("ServerName")
                        val t = parsedArgs.optString("ToolName")
                        if (s.isNotBlank() && t.isNotBlank()) "$s / $t" else t
                    }
                    else -> toolSummary.ifBlank { toolAction }
                }

                val out = when {
                    genericResultPayload?.has("runCommand") == true -> genericResultPayload.getJSONObject("runCommand").optJSONObject("combinedOutput")?.optString("full", "") ?: ""
                    genericResultPayload?.has("searchWeb") == true -> genericResultPayload.getJSONObject("searchWeb").optString("summary", "")
                    genericResultPayload?.has("viewFile") == true -> genericResultPayload.getJSONObject("viewFile").optString("content", "")
                    genericResultPayload?.has("codeAction") == true -> genericResultPayload.getJSONObject("codeAction").optString("diff", "")
                    genericResultPayload?.has("mcpTool") == true -> genericResultPayload.getJSONObject("mcpTool").optString("result", "")
                    else -> generic?.optJSONObject("result")?.optString("payload", "") ?: ""
                }

                val exitCode = if (genericResultPayload?.has("runCommand") == true) genericResultPayload.getJSONObject("runCommand").optInt("exitCode", 0) else null

                val toolStatus = resolveStatus(out.isNotBlank() || exitCode != null, isWaitingPermission)
                return ToolCall(
                    id = makeToolId("tool_"),
                    name = normalizeToolName(mName.ifBlank { "unknown" }),
                    command = cmd,
                    output = out,
                    status = toolStatus,
                    exitCode = exitCode
                )
            }
        }

        // 1. Terminal / Shell command
        if (step.has("runCommand")) {
            val cmd = rc?.optString("commandLine",
                rc.optString("proposedCommandLine",
                    rc.optString("CommandLine",
                        rc.optString("command", ""))))?.takeIf { it.isNotBlank() }
                ?: tcArgs?.optString("CommandLine", tcArgs.optString("commandLine", tcArgs.optString("command", "")))
                ?: ""
            val out = rc?.optJSONObject("combinedOutput")?.optString("full") ?: rc?.optString("output", "") ?: ""
            val exitCode = if (rc != null && rc.has("exitCode")) rc.optInt("exitCode", 0) else null
            val isWaiting = isProposedRunCommand || isWaitingPermission || stepStatus == "CORTEX_STEP_STATUS_WAITING"
            val toolStatus = resolveStatus(out.isNotBlank() || exitCode != null, isWaiting)
            return ToolCall(
                id = makeToolId("tool_"),
                name = "bash",
                command = cmd.ifBlank { toolSummary },
                output = if (toolStatus == "PENDING_APPROVAL" && out.isBlank()) toolAction.ifBlank { "[Awaiting confirmation]" } else out,
                status = toolStatus,
                exitCode = exitCode
            )
        }

        // 1.2 MCP Tool Call (e.g. CORTEX_STEP_TYPE_MCP_TOOL, mcpTool, callMcpTool)
        if (step.has("mcpTool") || step.has("callMcpTool") || step.has("mcp") || stepType.contains("MCP")) {
            val mcp = step.optJSONObject("mcpTool")
                ?: step.optJSONObject("callMcpTool")
                ?: step.optJSONObject("mcp")
                ?: JSONObject()

            val serverName = mcp.optString("serverName", mcp.optString("server", "")).trim()
            val tcObj = mcp.optJSONObject("toolCall")
                ?: mcp.optJSONObject("call")
                ?: mcp.optJSONObject("mcpToolCall")
            val rawToolName = tcObj?.optString("name", tcObj.optString("toolName", ""))
                ?.ifBlank { mcp.optString("name", mcp.optString("toolName", "")) }
                ?: ""
            val toolName = rawToolName.trim()

            // Arguments can be in argumentsJson (JSON string) or arguments/args (JSONObject)
            val argsObj = tcObj?.optJSONObject("arguments")
                ?: tcObj?.optJSONObject("args")
                ?: mcp.optJSONObject("arguments")
                ?: mcp.optJSONObject("args")
            val argsJsonStr = tcObj?.optString("argumentsJson", "")?.takeIf { it.isNotBlank() }
                ?: tcObj?.optString("argsJson", "")?.takeIf { it.isNotBlank() }
                ?: mcp.optString("argumentsJson", "")?.takeIf { it.isNotBlank() }

            val parsedArgsObj = argsObj ?: if (!argsJsonStr.isNullOrBlank()) {
                try { JSONObject(argsJsonStr) } catch (_: Exception) { null }
            } else null

            val argsSummary = when {
                parsedArgsObj != null -> {
                    val keys = parsedArgsObj.keys().asSequence().toList()
                    if (keys.isEmpty()) ""
                    else if (keys.size == 1) {
                        val k = keys[0]
                        val v = parsedArgsObj.opt(k)?.toString() ?: ""
                        if (v.length > 80) "$k: ${v.take(80)}..." else "$k: $v"
                    } else {
                        parsedArgsObj.toString()
                    }
                }
                !argsJsonStr.isNullOrBlank() -> argsJsonStr.trim()
                else -> ""
            }

            val commandDisplay = when {
                serverName.isNotBlank() && toolName.isNotBlank() -> {
                    if (argsSummary.isNotBlank()) "$serverName / $toolName($argsSummary)"
                    else "$serverName / $toolName"
                }
                toolName.isNotBlank() -> {
                    if (argsSummary.isNotBlank()) "$toolName($argsSummary)"
                    else toolName
                }
                serverName.isNotBlank() -> "MCP: $serverName"
                else -> toolSummary.ifBlank { "MCP Tool" }
            }

            // Extract Result/Output
            val resObj = mcp.opt("result") ?: mcp.opt("response") ?: mcp.opt("output")
            val resError = mcp.optString("error", "").takeIf { it.isNotBlank() }
            val outStr = when {
                resError != null -> "Error: $resError"
                resObj is JSONObject -> {
                    val contentArr = resObj.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val sb = StringBuilder()
                        for (ci in 0 until contentArr.length()) {
                            val cObj = contentArr.optJSONObject(ci)
                            val text = cObj?.optString("text", "") ?: ""
                            if (text.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(text)
                            }
                        }
                        if (sb.isNotEmpty()) sb.toString() else resObj.toString(2)
                    } else {
                        resObj.optString("value", resObj.optString("text", resObj.toString(2)))
                    }
                }
                resObj is JSONArray -> resObj.toString(2)
                resObj != null && resObj.toString().isNotBlank() -> resObj.toString()
                else -> ""
            }

            val hasOutput = outStr.isNotBlank() || resError != null
            val isWaiting = isWaitingPermission
            val toolStatus = when {
                resError != null -> "FAILED"
                isWaiting -> "PENDING_APPROVAL"
                isStepRunning || (!hasOutput && !isStepDone) -> "RUNNING"
                else -> resolveStatus(hasOutput, isWaiting)
            }

            val normName = if (serverName.isNotBlank() && toolName.isNotBlank()) {
                "mcp_${serverName}_$toolName"
            } else if (toolName.isNotBlank()) {
                "mcp_$toolName"
            } else {
                "mcp_tool"
            }

            return ToolCall(
                id = makeToolId("tool_mcp_"),
                name = normName,
                command = commandDisplay,
                output = if (toolStatus == "PENDING_APPROVAL" && outStr.isBlank()) {
                    toolAction.ifBlank { "[Awaiting confirmation]" }
                } else if (toolStatus == "RUNNING" && outStr.isBlank()) {
                    toolAction.ifBlank { "Executing MCP tool..." }
                } else {
                    outStr
                },
                status = toolStatus,
                interactionType = "mcp"
            )
        }

        // 1.3 MCP Read Resource
        if (step.has("readResource") || stepType.contains("READ_RESOURCE")) {
            val rr = step.optJSONObject("readResource") ?: JSONObject()
            val serverName = rr.optString("serverName", "")
            val uri = rr.optString("uri", "")
            val contents = rr.optString("contents", rr.optString("content", ""))
            val cmd = if (uri.isNotBlank()) "$serverName: $uri" else toolSummary.ifBlank { "Read Resource" }
            return ToolCall(
                id = makeToolId("tool_mcp_res_"),
                name = "read_resource",
                command = cmd,
                output = contents.ifBlank { toolAction },
                status = resolveStatus(contents.isNotBlank()),
                interactionType = "mcp"
            )
        }

        // 1.4 MCP List Resources
        if (step.has("listResources") || stepType.contains("LIST_RESOURCES")) {
            val lr = step.optJSONObject("listResources") ?: JSONObject()
            val serverName = lr.optString("serverName", "")
            val cmd = if (serverName.isNotBlank()) "Resources on $serverName" else toolSummary.ifBlank { "List Resources" }
            val resArr = lr.optJSONArray("resources")
            val out = if (resArr != null && resArr.length() > 0) {
                (0 until resArr.length()).mapNotNull { resArr.optJSONObject(it)?.optString("name", "") }.joinToString("\n")
            } else lr.optString("output", toolAction)
            return ToolCall(
                id = makeToolId("tool_mcp_res_"),
                name = "list_resources",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank()),
                interactionType = "mcp"
            )
        }

        // 1.5 Planner Response Tool Calls / Step Tool Calls (e.g. model-initiated commands or tools)
        if (callsArr != null && callsArr.length() > 0) {
            val tc = callsArr.optJSONObject(0)
            if (tc != null) {
                val tcName = tc.optString("name", "").ifBlank { tc.optString("toolName", "") }
                val parsedArgs = parseArgsJson(tc) ?: tcArgs
                val tcSummary = parsedArgs?.optString("toolSummary", "")?.trim()?.removeSurrounding("\"")?.ifBlank { toolSummary } ?: toolSummary
                val tcAction = parsedArgs?.optString("toolAction", "")?.trim()?.removeSurrounding("\"")?.ifBlank { toolAction } ?: toolAction

                val isMcpCall = tcName == "call_mcp_tool" || tcName.startsWith("mcp_") ||
                        parsedArgs?.has("ServerName") == true || parsedArgs?.has("serverName") == true ||
                        parsedArgs?.has("ToolName") == true || parsedArgs?.has("toolName") == true

                val normName = when {
                    isMcpCall -> {
                        val sName = parsedArgs?.optString("ServerName", parsedArgs.optString("serverName", "")) ?: ""
                        val tName = parsedArgs?.optString("ToolName", parsedArgs.optString("toolName", "")) ?: ""
                        if (sName.isNotBlank() && tName.isNotBlank()) "mcp_${sName}_$tName"
                        else if (tName.isNotBlank()) "mcp_$tName"
                        else normalizeToolName(tcName.ifBlank { "mcp_tool" })
                    }
                    else -> normalizeToolName(tcName.ifBlank { "bash" })
                }

                val cmd = when {
                    isMcpCall -> {
                        val sName = parsedArgs?.optString("ServerName", parsedArgs.optString("serverName", "")) ?: ""
                        val tName = parsedArgs?.optString("ToolName", parsedArgs.optString("toolName", "")) ?: ""
                        val mcpArgs = parsedArgs?.opt("Arguments") ?: parsedArgs?.opt("arguments") ?: parsedArgs?.opt("args")
                        val mcpArgsSummary = when (mcpArgs) {
                            is JSONObject -> {
                                val keys = mcpArgs.keys().asSequence().toList()
                                if (keys.size == 1) {
                                    val k = keys[0]
                                    val v = mcpArgs.opt(k)?.toString() ?: ""
                                    if (v.length > 80) "$k: ${v.take(80)}..." else "$k: $v"
                                } else if (keys.isNotEmpty()) mcpArgs.toString() else ""
                            }
                            is String -> mcpArgs
                            else -> ""
                        }
                        if (sName.isNotBlank() && tName.isNotBlank()) {
                            if (mcpArgsSummary.isNotBlank()) "$sName / $tName($mcpArgsSummary)" else "$sName / $tName"
                        } else if (tName.isNotBlank()) {
                            if (mcpArgsSummary.isNotBlank()) "$tName($mcpArgsSummary)" else tName
                        } else {
                            tcSummary.ifBlank { tcName }
                        }
                    }
                    parsedArgs?.has("CommandLine") == true -> parsedArgs.optString("CommandLine", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("commandLine") == true -> parsedArgs.optString("commandLine", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("command") == true -> parsedArgs.optString("command", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("AbsolutePath") == true -> parsedArgs.optString("AbsolutePath", "").trim().removeSurrounding("\"").removePrefix("file://")
                    parsedArgs?.has("TargetFile") == true -> parsedArgs.optString("TargetFile", "").trim().removeSurrounding("\"").removePrefix("file://")
                    parsedArgs?.has("DirectoryPath") == true -> parsedArgs.optString("DirectoryPath", "").trim().removeSurrounding("\"").removePrefix("file://")
                    parsedArgs?.has("query") == true -> parsedArgs.optString("query", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("Query") == true -> parsedArgs.optString("Query", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("Prompt") == true -> parsedArgs.optString("Prompt", "").trim().removeSurrounding("\"")
                    parsedArgs?.has("Url") == true -> parsedArgs.optString("Url", "").trim().removeSurrounding("\"")
                    else -> tcSummary.ifBlank { tcAction }
                }

                val out = tc.optString("output", tc.optString("result", tcAction.ifBlank { tcSummary }))
                val isWaiting = isWaitingPermission
                val toolStatus = resolveStatus(out.isNotBlank() && out != tcAction, isWaiting)

                val idPrefix = when {
                    isMcpCall || normName.startsWith("mcp_") -> "tool_mcp_"
                    normName == "bash" -> "tool_"
                    normName == "view_file" -> "tool_view_"
                    normName == "edit_file" -> "tool_edit_"
                    normName == "list_dir" -> "tool_list_"
                    normName == "grep_search" -> "tool_grep_"
                    normName == "find" -> "tool_find_"
                    normName == "web_search" -> "tool_web_"
                    normName == "read_url" -> "tool_read_"
                    normName == "generate_image" -> "tool_genimg_"
                    else -> "tool_step_"
                }

                return ToolCall(
                    id = makeToolId(idPrefix),
                    name = normName,
                    command = cmd.ifBlank { tcSummary.ifBlank { tcName } },
                    output = if (toolStatus == "PENDING_APPROVAL" && out.isBlank()) tcAction.ifBlank { "[Awaiting confirmation]" } else out,
                    status = toolStatus,
                    interactionType = if (isMcpCall || normName.startsWith("mcp_")) "mcp" else null
                )
            }
        }

        // 1.8 Background Task / Running Task from Step Content
        val rawContentStr = step.optString("content", "")
        if (rawContentStr.contains("Tool is running as a background task", ignoreCase = true) ||
            (stepStatus.contains("RUN", ignoreCase = true) && rawContentStr.contains("Task Description:", ignoreCase = true))) {
            val taskDesc = Regex("""Task Description:\s*(.*)""").find(rawContentStr)?.groupValues?.get(1)?.trim() ?: ""
            val taskId = Regex("""task id:\s*([^\s\n]+)""").find(rawContentStr)?.groupValues?.get(1)?.trim() ?: ""
            val isWait = isWaitingPermission
            return ToolCall(
                id = makeToolId("tool_"),
                name = "bash",
                command = taskDesc.ifBlank { toolSummary.ifBlank { "Background task" } },
                output = if (taskId.isNotBlank()) "Running background task ($taskId)..." else rawContentStr,
                status = if (isWait) "PENDING_APPROVAL" else "RUNNING"
            )
        }

        // 2. View File
        if (step.has("viewFile") || stepType.contains("VIEW_FILE")) {
            val vf = step.optJSONObject("viewFile") ?: JSONObject()
            val rawPath = vf.optString("absolutePathUri", vf.optString("absolutePath", "")).removePrefix("file://")
            val startLine = vf.optInt("startLine", -1)
            val endLine = vf.optInt("endLine", -1)
            val lineRange = if (startLine > 0 && endLine > 0) " (lines $startLine-$endLine)"
                else if (endLine > 0) " (lines 1-$endLine)"
                else ""
            val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
            val cmd = if (fileName.isNotBlank()) "$fileName$lineRange" else toolSummary.ifBlank { "View File" }

            val mediaUri = vf.optJSONObject("mediaData")?.optString("uri", "") ?: ""
            val numLines = vf.optInt("numLines", 0)
            val numBytes = vf.optInt("numBytes", 0)
            val rawContent = vf.optString("content", "")

            val out = when {
                rawContent.isNotBlank() -> rawContent
                mediaUri.isNotBlank() -> "[Image: $mediaUri]"
                numLines > 0 || numBytes > 0 -> "$rawPath\n$numLines lines, $numBytes bytes"
                rawPath.isNotBlank() -> rawPath
                else -> toolAction.ifBlank { toolSummary }
            }

            return ToolCall(
                id = makeToolId("tool_view_"),
                name = "view_file",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // 3. List Directory
        if (step.has("listDirectory") || stepType.contains("LIST_DIRECTORY")) {
            val ld = step.optJSONObject("listDirectory") ?: JSONObject()
            val rawDir = ld.optString("directoryPathUri", ld.optString("directoryPath", "")).removePrefix("file://")
            val dirName = rawDir.substringAfterLast('/').ifBlank { rawDir }
            val cmd = if (dirName.isNotBlank()) dirName else toolSummary.ifBlank { "Directory" }

            val resultsArr = ld.optJSONArray("results")
            val out = if (resultsArr != null && resultsArr.length() > 0) {
                val items = mutableListOf<String>()
                for (idx in 0 until resultsArr.length()) {
                    val item = resultsArr.getJSONObject(idx)
                    val name = item.optString("name", "")
                    val isDir = item.optBoolean("isDir", false)
                    val size = item.optString("sizeBytes", "")
                    val prefix = if (isDir) "📁" else "📄"
                    val suffix = if (size.isNotBlank()) " ($size bytes)" else ""
                    items.add("$prefix $name$suffix")
                }
                items.joinToString("\n")
            } else {
                ld.optString("output", rawDir.ifBlank { toolAction.ifBlank { toolSummary } })
            }

            return ToolCall(
                id = makeToolId("tool_list_"),
                name = "list_dir",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // 4. Grep Search
        if (step.has("grepSearch") || stepType.contains("GREP")) {
            val gs = step.optJSONObject("grepSearch") ?: JSONObject()
            val query = gs.optString("query", "")
            val rawPath = gs.optString("searchPathUri", gs.optString("searchPath", "")).removePrefix("file://")
            val pathDisplay = rawPath.substringAfterLast('/').ifBlank { rawPath }
            val cmd = if (query.isNotBlank()) "\"$query\" in $pathDisplay" else toolSummary.ifBlank { "Search Code" }
            val total = gs.optInt("totalResults", -1)
            val commandRun = gs.optString("commandRun", "")
            val out = when {
                total >= 0 -> "$total matches found for \"$query\" in $rawPath\n$commandRun"
                commandRun.isNotBlank() -> commandRun
                else -> toolAction.ifBlank { toolSummary }
            }
            return ToolCall(
                id = makeToolId("tool_grep_"),
                name = "grep_search",
                command = cmd,
                output = out,
                status = resolveStatus(true)
            )
        }

        // 5. Find Files
        if ((step.has("find") || stepType.contains("FIND")) && !stepType.contains("FINDINGS")) {
            val f = step.optJSONObject("find") ?: JSONObject()
            val pat = f.optString("pattern", "*")
            val rawDir = f.optString("searchDirectory", "").removePrefix("file://")
            val dir = rawDir.substringAfterLast('/')
            val cmd = if (dir.isNotBlank()) "$pat in $dir" else toolSummary.ifBlank { "Find $pat" }
            val out = f.optString("truncatedOutput", f.optString("output", rawDir.ifBlank { toolAction.ifBlank { toolSummary } }))
            return ToolCall(
                id = makeToolId("tool_find_"),
                name = "find",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // 6. Modify / Edit File
        if (step.has("modifyFile") || step.has("codeAction") || step.has("fileChange") ||
            stepType.contains("FILE_CHANGE") || stepType.contains("CODE_ACTION")) {
            val ca = step.optJSONObject("codeAction")
                ?: step.optJSONObject("modifyFile")
                ?: step.optJSONObject("fileChange")
                ?: JSONObject()
            val uri = ca.optString("uri", ca.optString("absolutePathUri", ca.optString("path", "")))
            val path = uri.removePrefix("file://")
            val fileName = path.substringAfterLast('/').ifBlank { path }
            val diff = ca.optString("diff", ca.optString("patch", ca.optString("content", "")))
            val cmd = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "Edit File" }
            val out = diff.ifBlank { toolAction.ifBlank { toolSummary.ifBlank { "File modified" } } }
            return ToolCall(
                id = makeToolId("tool_edit_"),
                name = "edit_file",
                command = cmd,
                output = out,
                status = resolveStatus(true)
            )
        }

        // 6.5. Check for search results in content (trajectory history)
        val rawContent = step.optString("content", "")
        if (rawContent.contains("The search for") && rawContent.contains("returned the following summary")) {
            val query = Regex("""The search for "(.*?)" returned""").find(rawContent)?.groupValues?.get(1) ?: ""
            val summary = rawContent.substringAfter("returned the following summary:").trim()
            return ToolCall(
                id = makeToolId("tool_web_"),
                name = "web_search",
                command = query.ifBlank { toolSummary.ifBlank { "Web Search" } },
                output = summary.ifBlank { rawContent },
                status = resolveStatus(true)
            )
        }

        // 7. Search Web
        if (step.has("searchWeb") || stepType.contains("SEARCH_WEB")) {
            val sw = step.optJSONObject("searchWeb") ?: JSONObject()
            val query = sw.optString("query", "")
            val summary = sw.optString("summary", sw.optString("output", step.optString("content", "")))
            return ToolCall(
                id = makeToolId("tool_web_"),
                name = "web_search",
                command = query.ifBlank { toolSummary.ifBlank { "Web Search" } },
                output = summary.ifBlank { toolAction },
                status = resolveStatus(summary.isNotBlank())
            )
        }

        // 8. Read URL Content
        if (step.has("readUrlContent") || stepType.contains("READ_URL")) {
            val ru = step.optJSONObject("readUrlContent") ?: JSONObject()
            val url = ru.optString("url", "")
            val content = ru.optString("markdown", ru.optString("content", ""))
            return ToolCall(
                id = makeToolId("tool_read_"),
                name = "read_url",
                command = url.ifBlank { toolSummary.ifBlank { "Read URL" } },
                output = content,
                status = resolveStatus(content.isNotBlank())
            )
        }

        // 9. Generate Image
        if (step.has("generateImage") || stepType.contains("GENERATE_IMAGE")) {
            val gi = step.optJSONObject("generateImage") ?: JSONObject()
            val prompt = gi.optString("prompt", "")
            val gm = gi.optJSONObject("generatedMedia")
            val mimeType = gm?.optString("mimeType", "image/jpeg")?.ifBlank { "image/jpeg" } ?: "image/jpeg"
            val inlineData = gm?.optString("inlineData", gm.optString("inline_data", "")) ?: ""
            val rawUri = gm?.optString("uri", "")?.ifBlank { gi.optString("uri", "") } ?: ""
            val output = if (inlineData.isNotBlank()) "data:$mimeType;base64,$inlineData" else rawUri
            return ToolCall(
                id = makeToolId("tool_genimg_"),
                name = "generate_image",
                command = prompt.ifBlank { toolSummary.ifBlank { "Generate Image" } },
                output = output,
                status = resolveStatus(output.isNotBlank())
            )
        }

        // 10. Generic Tool Call
        if (step.has("generic")) {
            val generic = step.getJSONObject("generic")
            val args = generic.optJSONObject("args")
            val resultObj = generic.optJSONObject("result")
            val payload = resultObj?.optJSONObject("payload")
            val fullOutputUri = resultObj?.optString("fullOutputUri", "")?.takeIf { it.isNotBlank() }
            val toolCallMeta = meta?.optJSONObject("toolCall")
            val metaName = toolCallMeta?.optString("name", "")?.lowercase() ?: ""
            val isGenericWaiting = isWaitingPermission

            when {
                args?.has("CommandLine") == true || metaName == "run_command" -> {
                    val payloadRc = payload?.optJSONObject("runCommand") ?: rc
                    val cmd = payloadRc?.optString("commandLine")?.takeIf { it.isNotBlank() }
                        ?: args?.optString("CommandLine", "") ?: ""
                    val out = payloadRc?.optJSONObject("combinedOutput")?.optString("full")
                        ?: payloadRc?.optString("output")
                        ?: rc?.optJSONObject("combinedOutput")?.optString("full")
                        ?: rc?.optString("output")
                        ?: fullOutputUri?.let { "[Output stored at $it]" }
                        ?: if (isGenericWaiting) toolAction.ifBlank { "[Awaiting confirmation]" } else toolAction
                    val exitCode = if (payloadRc?.has("exitCode") == true) payloadRc.optInt("exitCode", 0) else if (rc?.has("exitCode") == true) rc.optInt("exitCode", 0) else null

                    return ToolCall(
                        id = makeToolId("tool_"),
                        name = "bash",
                        command = cmd.ifBlank { toolSummary },
                        output = out,
                        status = resolveStatus(out.isNotBlank() && out != toolAction, isGenericWaiting),
                        exitCode = exitCode
                    )
                }
                args?.has("AbsolutePath") == true || metaName == "view_file" -> {
                    val payloadVf = payload?.optJSONObject("viewFile") ?: step.optJSONObject("viewFile")
                    val rawPath = (args?.optString("AbsolutePath", "") ?: payloadVf?.optString("absolutePathUri", payloadVf?.optString("absolutePath", "")) ?: "").removePrefix("file://")
                    val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                    val content = payloadVf?.optString("content")?.takeIf { it.isNotBlank() }
                        ?: fullOutputUri?.let { "[File content at $it]" }
                        ?: rawPath.ifBlank { toolAction }

                    return ToolCall(
                        id = makeToolId("tool_view_"),
                        name = "view_file",
                        command = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "View File" },
                        output = content,
                        status = resolveStatus(content.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("TargetFile") == true || metaName == "edit_file" || metaName == "write_to_file" || metaName == "replace_file_content" -> {
                    val payloadCa = payload?.optJSONObject("codeAction")
                        ?: payload?.optJSONObject("modifyFile")
                        ?: step.optJSONObject("codeAction")
                        ?: step.optJSONObject("modifyFile")
                    val path = (args?.optString("TargetFile", "") ?: payloadCa?.optString("uri", payloadCa?.optString("path", "")) ?: "").removePrefix("file://")
                    val fileName = path.substringAfterLast('/').ifBlank { path }
                    val diff = payloadCa?.optString("diff")?.takeIf { it.isNotBlank() }
                        ?: payloadCa?.optString("patch")?.takeIf { it.isNotBlank() }
                    val diffStats = payloadCa?.optJSONObject("diffStats")
                    val diffStatsStr = if (diffStats != null) "+${diffStats.optInt("additions", 0)}, -${diffStats.optInt("deletions", 0)}" else ""

                    val desc = args?.optString("Instruction", args.optString("Description", "")) ?: ""
                    val out = when {
                        !diff.isNullOrBlank() -> diff
                        diffStatsStr.isNotBlank() -> "$path ($diffStatsStr)"
                        desc.isNotBlank() -> "$path\n$desc"
                        else -> path.ifBlank { toolAction }
                    }

                    return ToolCall(
                        id = makeToolId("tool_edit_"),
                        name = "edit_file",
                        command = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "Edit File" },
                        output = out,
                        status = resolveStatus(out.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("DirectoryPath") == true || metaName == "list_dir" -> {
                    val payloadLd = payload?.optJSONObject("listDirectory") ?: step.optJSONObject("listDirectory")
                    val dir = (args?.optString("DirectoryPath", "") ?: payloadLd?.optString("directoryPath", "") ?: "").removePrefix("file://")
                    val dirName = dir.substringAfterLast('/').ifBlank { dir }
                    val resultsArr = payloadLd?.optJSONArray("results")
                    val out = if (resultsArr != null && resultsArr.length() > 0) {
                        val items = mutableListOf<String>()
                        for (idx in 0 until resultsArr.length()) {
                            val item = resultsArr.optJSONObject(idx) ?: continue
                            val name = item.optString("name", "")
                            val isDir = item.optBoolean("isDir", false)
                            val size = item.optString("sizeBytes", "")
                            val prefix = if (isDir) "📁" else "📄"
                            val suffix = if (size.isNotBlank()) " ($size bytes)" else ""
                            items.add("$prefix $name$suffix")
                        }
                        items.joinToString("\n")
                    } else {
                        payloadLd?.optString("output") ?: dir.ifBlank { toolAction }
                    }

                    return ToolCall(
                        id = makeToolId("tool_list_"),
                        name = "list_dir",
                        command = if (dirName.isNotBlank()) dirName else toolSummary.ifBlank { "Directory" },
                        output = out,
                        status = resolveStatus(out.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("Query") == true && args.has("SearchPath") -> {
                    val payloadGs = payload?.optJSONObject("grepSearch") ?: step.optJSONObject("grepSearch")
                    val q = args?.optString("Query", "") ?: payloadGs?.optString("query", "") ?: ""
                    val sp = (args?.optString("SearchPath", "") ?: payloadGs?.optString("searchPath", "") ?: "").removePrefix("file://")
                    val resultsArr = payloadGs?.optJSONArray("results")
                    val out = if (resultsArr != null && resultsArr.length() > 0) {
                        val items = mutableListOf<String>()
                        for (idx in 0 until resultsArr.length().coerceAtMost(50)) {
                            val item = resultsArr.optJSONObject(idx) ?: continue
                            val file = item.optString("fileName", "").substringAfterLast('/')
                            val line = item.optInt("lineNumber", 0)
                            val content = item.optString("lineContent", "")
                            items.add("$file:$line: $content")
                        }
                        items.joinToString("\n")
                    } else {
                        payloadGs?.optString("commandRun") ?: toolAction
                    }

                    return ToolCall(
                        id = makeToolId("tool_grep_"),
                        name = "grep_search",
                        command = "\"$q\" in ${sp.substringAfterLast('/')}",
                        output = out,
                        status = resolveStatus(out.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("Url") == true || metaName == "read_url_content" -> {
                    val payloadRu = payload?.optJSONObject("readUrlContent") ?: step.optJSONObject("readUrlContent")
                    val url = args?.optString("Url", "") ?: payloadRu?.optString("url", "") ?: ""
                    val content = payloadRu?.optString("content", payloadRu?.optString("markdown", "")) ?: toolAction

                    return ToolCall(
                        id = makeToolId("tool_read_"),
                        name = "read_url",
                        command = url.ifBlank { toolSummary },
                        output = content,
                        status = resolveStatus(content.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("query") == true || args?.has("Query") == true || metaName == "search_web" -> {
                    val payloadSw = payload?.optJSONObject("searchWeb") ?: step.optJSONObject("searchWeb")
                    val query = (args?.optString("query", args.optString("Query", "")) ?: payloadSw?.optString("query", "") ?: "").trim().removeSurrounding("\"")
                    val title = query.ifBlank { toolSummary.ifBlank { "Web Search" } }
                    val summary = payloadSw?.optString("summary")?.takeIf { it.isNotBlank() }
                        ?: payloadSw?.optJSONArray("results")?.let { resArr ->
                            (0 until resArr.length()).mapNotNull { resArr.optJSONObject(it)?.optString("title") }.joinToString("\n")
                        }
                        ?: toolAction.ifBlank { title }

                    return ToolCall(
                        id = makeToolId("tool_web_"),
                        name = "web_search",
                        command = title,
                        output = summary,
                        status = resolveStatus(summary.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("Pattern") == true || metaName == "find_by_name" -> {
                    val payloadFind = payload?.optJSONObject("find") ?: step.optJSONObject("find")
                    val pattern = args?.optString("Pattern", "") ?: payloadFind?.optString("pattern", "") ?: ""
                    val out = payloadFind?.optString("truncatedOutput", payloadFind?.optString("output", toolAction)) ?: toolAction

                    return ToolCall(
                        id = makeToolId("tool_find_"),
                        name = "find",
                        command = pattern.ifBlank { toolSummary.ifBlank { "Find Files" } },
                        output = out,
                        status = resolveStatus(out.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("Prompt") == true || metaName == "generate_image" -> {
                    val payloadGi = payload?.optJSONObject("generateImage") ?: step.optJSONObject("generateImage")
                    val prompt = args?.optString("Prompt", "") ?: payloadGi?.optString("prompt", "") ?: ""
                    val gm = payloadGi?.optJSONObject("generatedMedia")
                    val rawUri = gm?.optString("uri", "") ?: payloadGi?.optString("uri", "") ?: ""
                    val inlineData = gm?.optString("inlineData", "") ?: ""
                    val mimeType = gm?.optString("mimeType", "image/jpeg") ?: "image/jpeg"
                    val output = if (inlineData.isNotBlank()) "data:$mimeType;base64,$inlineData" else if (rawUri.isNotBlank()) rawUri else toolAction

                    return ToolCall(
                        id = makeToolId("tool_genimg_"),
                        name = "generate_image",
                        command = prompt.ifBlank { toolSummary.ifBlank { "Generate Image" } },
                        output = output,
                        status = resolveStatus(output.isNotBlank(), isGenericWaiting)
                    )
                }
                args?.has("ServerName") == true || args?.has("ToolName") == true || metaName == "call_mcp_tool" || metaName.startsWith("mcp_") -> {
                    val payloadMcp = payload?.optJSONObject("mcpTool") ?: step.optJSONObject("mcpTool")
                    val sName = args?.optString("ServerName", args.optString("serverName", payloadMcp?.optString("serverName", ""))) ?: ""
                    val tName = args?.optString("ToolName", args.optString("toolName", payloadMcp?.optString("toolName", ""))) ?: ""
                    val mcpArgs = args?.opt("Arguments") ?: args?.opt("arguments") ?: args?.opt("args")
                    val mcpArgsSummary = when (mcpArgs) {
                        is JSONObject -> {
                            val keys = mcpArgs.keys().asSequence().toList()
                            if (keys.size == 1) {
                                val k = keys[0]
                                val v = mcpArgs.opt(k)?.toString() ?: ""
                                if (v.length > 80) "$k: ${v.take(80)}..." else "$k: $v"
                            } else if (keys.isNotEmpty()) mcpArgs.toString() else ""
                        }
                        is String -> mcpArgs
                        else -> ""
                    }
                    val cmd = if (sName.isNotBlank() && tName.isNotBlank()) {
                        if (mcpArgsSummary.isNotBlank()) "$sName / $tName($mcpArgsSummary)" else "$sName / $tName"
                    } else if (tName.isNotBlank()) {
                        if (mcpArgsSummary.isNotBlank()) "$tName($mcpArgsSummary)" else tName
                    } else toolSummary.ifBlank { metaName.ifBlank { "MCP Tool" } }

                    val normName = if (sName.isNotBlank() && tName.isNotBlank()) "mcp_${sName}_$tName"
                        else if (tName.isNotBlank()) "mcp_$tName"
                        else if (metaName.startsWith("mcp_")) metaName
                        else "mcp_tool"

                    val mcpRes = payloadMcp?.opt("result") ?: payloadMcp?.opt("response") ?: payloadMcp?.opt("output")
                    val out = when {
                        mcpRes is JSONObject -> {
                            val contentArr = mcpRes.optJSONArray("content")
                            if (contentArr != null && contentArr.length() > 0) {
                                val sb = StringBuilder()
                                for (ci in 0 until contentArr.length()) {
                                    val cObj = contentArr.optJSONObject(ci)
                                    val text = cObj?.optString("text", "") ?: ""
                                    if (text.isNotBlank()) {
                                        if (sb.isNotEmpty()) sb.append("\n")
                                        sb.append(text)
                                    }
                                }
                                if (sb.isNotEmpty()) sb.toString() else mcpRes.toString(2)
                            } else mcpRes.optString("value", mcpRes.toString(2))
                        }
                        mcpRes is JSONArray -> mcpRes.toString(2)
                        mcpRes != null && mcpRes.toString().isNotBlank() -> mcpRes.toString()
                        else -> if (isGenericWaiting) toolAction.ifBlank { "[Awaiting confirmation]" } else toolAction
                    }

                    return ToolCall(
                        id = makeToolId("tool_mcp_"),
                        name = normName,
                        command = cmd,
                        output = out,
                        status = resolveStatus(out.isNotBlank() && out != toolAction, isGenericWaiting),
                        interactionType = "mcp"
                    )
                }
                else -> {
                    val fallbackTitle = toolSummary.ifBlank { metaName.ifBlank { "Tool" } }
                    val unifiedName = normalizeToolName(metaName.ifBlank { "tool" })
                    val idPrefix = when (unifiedName) {
                        "web_search" -> "tool_web_"
                        "read_url" -> "tool_read_"
                        "bash" -> "tool_"
                        "view_file" -> "tool_view_"
                        "edit_file" -> "tool_edit_"
                        "list_dir" -> "tool_list_"
                        "find" -> "tool_find_"
                        "grep_search" -> "tool_grep_"
                        "generate_image" -> "tool_genimg_"
                        else -> if (unifiedName.startsWith("mcp_")) "tool_mcp_" else "tool_gen_"
                    }
                    return ToolCall(
                        id = makeToolId(idPrefix),
                        name = unifiedName,
                        command = fallbackTitle,
                        output = toolAction,
                        status = resolveStatus(false, isGenericWaiting),
                        interactionType = if (unifiedName.startsWith("mcp_")) "mcp" else null
                    )
                }
            }
        }

        // 11. Final fallback when toolSummary is available
        if (toolSummary.isNotBlank()) {
            val name = when {
                stepType.contains("MCP") -> "mcp_tool"
                stepType.contains("VIEW") -> "view_file"
                stepType.contains("LIST") -> "list_dir"
                stepType.contains("FIND") -> "find"
                stepType.contains("GREP") -> "grep_search"
                stepType.contains("FILE") || stepType.contains("CODE") -> "edit_file"
                stepType.contains("COMMAND") -> "bash"
                stepType.contains("SEARCH") -> "web_search"
                stepType.contains("READ") || stepType.contains("URL") -> "read_url"
                stepType.contains("IMAGE") -> "generate_image"
                else -> "tool"
            }
            val idPrefix = when (name) {
                "mcp_tool" -> "tool_mcp_"
                "view_file" -> "tool_view_"
                "list_dir" -> "tool_list_"
                "find" -> "tool_find_"
                "grep_search" -> "tool_grep_"
                "edit_file" -> "tool_edit_"
                "bash" -> "tool_"
                "web_search" -> "tool_web_"
                "read_url" -> "tool_read_"
                "generate_image" -> "tool_genimg_"
                else -> "tool_step_"
            }
            return ToolCall(
                id = makeToolId(idPrefix),
                name = name,
                command = toolSummary,
                output = toolAction.ifBlank { toolSummary },
                status = resolveStatus(true),
                interactionType = if (name == "mcp_tool") "mcp" else null
            )
        }

        return null
    }

    /**
     * Extracts an error message from a trajectory step if present.
     */
    fun extractStepError(step: JSONObject): String? {
        // 1. Check errorMessage object (standard daemon CORTEX_STEP_TYPE_ERROR_MESSAGE)
        val errMsgObj = step.optJSONObject("errorMessage")
        if (errMsgObj != null) {
            val err = errMsgObj.optJSONObject("error")
            val shortError = err?.optString("shortError", "")?.takeIf { it.isNotBlank() }
            val userError = err?.optString("userErrorMessage", "")?.takeIf { it.isNotBlank() }
            val directMsg = err?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: errMsgObj.optString("message", "").takeIf { it.isNotBlank() }

            if (!userError.isNullOrBlank() && !shortError.isNullOrBlank() && userError != shortError) {
                return "$userError: $shortError"
            }
            if (!shortError.isNullOrBlank()) return shortError
            if (!userError.isNullOrBlank()) return userError
            if (!directMsg.isNullOrBlank()) return directMsg
        }

        // 2. Direct error object
        val errObj = step.optJSONObject("error")
        if (errObj != null) {
            val shortError = errObj.optString("shortError", "").takeIf { it.isNotBlank() }
            val userError = errObj.optString("userErrorMessage", "").takeIf { it.isNotBlank() }
            val message = errObj.optString("message", "").takeIf { it.isNotBlank() }
            if (!userError.isNullOrBlank() && !shortError.isNullOrBlank() && userError != shortError) {
                return "$userError: $shortError"
            }
            if (!shortError.isNullOrBlank()) return shortError
            if (!userError.isNullOrBlank()) return userError
            if (!message.isNullOrBlank()) return message
        }

        val directErr = step.optString("error", "").takeIf { it.isNotBlank() }
            ?: step.optString("executionError", "").takeIf { it.isNotBlank() }
        if (directErr != null) {
            return directErr
        }

        // 3. Check inside plannerResponse error
        val plannerErr = step.optJSONObject("plannerResponse")?.optJSONObject("error")
        if (plannerErr != null) {
            val shortError = plannerErr.optString("shortError", "").takeIf { it.isNotBlank() }
            val msg = plannerErr.optString("message", "").takeIf { it.isNotBlank() }
            if (!shortError.isNullOrBlank()) return shortError
            if (!msg.isNullOrBlank()) return msg
        }

        // 4. If step type indicates error
        val stepType = step.optString("type", "")
        if (stepType.contains("ERROR", ignoreCase = true)) {
            val desc = step.optString("description", "").takeIf { it.isNotBlank() }
            return desc ?: "Agent execution terminated due to error."
        }

        // 5. If status indicates error or failure
        val status = step.optString("status", "")
        if (status.contains("ERROR", ignoreCase = true) || status.contains("FAIL", ignoreCase = true)) {
            val shortStatus = status.removePrefix("CORTEX_STEP_STATUS_").lowercase().replace('_', ' ')
            return "Model step error ($shortStatus)"
        }
        return null
    }

    /**
     * Parses steps array into chat messages with chronological tool ordering and live status
     */
    fun parseStepsArrayToChatMessages(
        steps: JSONArray,
        conversationId: String,
        cascadeStatus: String = ""
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        try {
            val turnTools = linkedMapOf<String, ToolCall>()
            val turnStepTexts = sortedMapOf<Int, String>()
            val turnStepThoughts = sortedMapOf<Int, String>()
            var turnAssistantId: String? = null

            fun flushAssistant() {
                if (turnAssistantId != null || turnStepThoughts.isNotEmpty() || turnStepTexts.isNotEmpty() || turnTools.isNotEmpty()) {
                    val content = turnStepTexts.values.joinToString("\n\n").trim()
                    val thoughtText = turnStepThoughts.values.joinToString("\n\n").trim().takeIf { it.isNotBlank() }
                    messages.add(
                        ChatMessage(
                            id = turnAssistantId ?: UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = content,
                            thoughtText = thoughtText,
                            toolCalls = turnTools.values.toList(),
                            isStreaming = false
                        )
                    )
                    turnStepThoughts.clear()
                    turnStepTexts.clear()
                    turnTools.clear()
                    turnAssistantId = null
                }
            }

            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val stepType = step.optString("type", "")
                val stepInfo = step.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                val stepIndex = stepInfo?.optInt("stepIndex", step.optInt("stepIndex", i)) ?: step.optInt("stepIndex", i)

                if (stepType == "CORTEX_STEP_TYPE_USER_INPUT" || step.has("userInput")) {
                    flushAssistant()
                    val userInput = step.optJSONObject("userInput")
                    var userText = ""
                    val userAttachments = mutableListOf<com.example.gemini.domain.model.ChatAttachment>()
                    if (userInput != null) {
                        val items = userInput.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            userText = items.getJSONObject(0).optString("text", "")
                        } else {
                            userText = userInput.optString("content", "")
                        }

                        val mediaArr = userInput.optJSONArray("media")
                        if (mediaArr != null) {
                            for (mIdx in 0 until mediaArr.length()) {
                                val mObj = mediaArr.optJSONObject(mIdx) ?: continue
                                val mime = mObj.optString("mimeType", "")
                                val inline = mObj.optString("inlineData", "")
                                val desc = mObj.optString("description", "Voice note")
                                val dur = mObj.optInt("durationSeconds", 0)
                                val isAud = mime.startsWith("audio/")
                                val isImg = mime.startsWith("image/")
                                userAttachments.add(
                                    com.example.gemini.domain.model.ChatAttachment(
                                        id = "att_${conversationId}_${stepIndex}_$mIdx",
                                        name = desc,
                                        path = "",
                                        isImage = isImg,
                                        isAudio = isAud,
                                        durationSeconds = dur,
                                        mimeType = mime,
                                        base64 = inline
                                    )
                                )
                            }
                        }

                        // Extract image and document attachments from prompt markdown links:
                        // e.g. [Attached Image: filename](file:///path) or [Attached File: filename](file:///path)
                        val imageRegex = Regex("""\[Attached Image:\s*([^\]]+)\]\(([^)]+)\)""", RegexOption.IGNORE_CASE)
                        val fileRegex = Regex("""\[Attached File:\s*([^\]]+)\]\(([^)]+)\)""", RegexOption.IGNORE_CASE)

                        imageRegex.findAll(userText).forEachIndexed { idx, match ->
                            val attName = match.groupValues[1].trim()
                            val rawPath = match.groupValues[2].trim()
                            val cleanPath = if (rawPath.startsWith("file://")) rawPath.removePrefix("file://") else rawPath
                            userAttachments.add(
                                com.example.gemini.domain.model.ChatAttachment(
                                    id = "att_${conversationId}_${stepIndex}_img_$idx",
                                    name = attName,
                                    path = cleanPath,
                                    isImage = true,
                                    isAudio = false
                                )
                            )
                        }

                        fileRegex.findAll(userText).forEachIndexed { idx, match ->
                            val attName = match.groupValues[1].trim()
                            val rawPath = match.groupValues[2].trim()
                            val cleanPath = if (rawPath.startsWith("file://")) rawPath.removePrefix("file://") else rawPath
                            userAttachments.add(
                                com.example.gemini.domain.model.ChatAttachment(
                                    id = "att_${conversationId}_${stepIndex}_file_$idx",
                                    name = attName,
                                    path = cleanPath,
                                    isImage = false,
                                    isAudio = false
                                )
                            )
                        }
                    }
                    if (userText.isNotBlank() || userAttachments.isNotEmpty()) {
                        messages.add(
                            ChatMessage(
                                id = "user_${conversationId}_$stepIndex",
                                conversationId = conversationId,
                                role = MessageRole.USER,
                                content = userText,
                                attachments = userAttachments,
                                stepIndex = stepIndex
                            )
                        )
                    }
                } else {
                    if (turnAssistantId == null) {
                        turnAssistantId = "assistant_${conversationId}_$stepIndex"
                    }

                    if (step.has("plannerResponse")) {
                        val pr = step.getJSONObject("plannerResponse")
                        val th = pr.optString("thinking", "")
                        val resp = pr.optString("response", "")
                        if (th.isNotBlank()) {
                            turnStepThoughts[stepIndex] = th
                        }
                        if (resp.isNotBlank()) {
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n$resp" else resp
                        }
                    }

                    val tool = extractToolCallFromStep(step, stepIndex, conversationId, cascadeStatus)
                    if (tool != null) {
                        val normName = normalizeToolName(tool.name)
                        val targetId = tool.id
                        val unifiedTool = tool.copy(id = targetId, name = normName)
                        turnTools[targetId] = unifiedTool

                        val marker = "<!-- tool_call:$targetId -->"
                        val alreadyHasMarker = turnStepTexts.values.any { it.contains(marker) }
                        if (!alreadyHasMarker) {
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n$marker" else marker
                        }
                    } else {
                        val stepErr = extractStepError(step)
                        if (stepErr != null) {
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n⚠️ $stepErr" else "⚠️ $stepErr"
                        }
                    }
                }
            }
            flushAssistant()
        } catch (e: Exception) {
            Log.e(TAG, "parseStepsArrayToChatMessages error: ${e.message}")
        }
        return messages
    }

    suspend fun getMcpServerStates(hubUrl: String = DEFAULT_HUB_URL): Result<List<com.example.gemini.domain.model.McpServerState>> = withContext(Dispatchers.IO) {
        try {
            val res = callUnary("GetMcpServerStates", "{}", hubUrl)
            if (res.isFailure) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetMcpServerStates failed"))
            }
            val body = res.getOrNull() ?: "{}"
            val json = JSONObject(body)
            val result = mutableListOf<com.example.gemini.domain.model.McpServerState>()

            if (json.has("states")) {
                val statesObj = json.get("states")
                if (statesObj is JSONArray) {
                    for (i in 0 until statesObj.length()) {
                        val item = statesObj.optJSONObject(i) ?: continue
                        parseMcpServerState(item)?.let { result.add(it) }
                    }
                } else if (statesObj is JSONObject) {
                    val keys = statesObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = statesObj.optJSONObject(key) ?: continue
                        parseMcpServerState(item, fallbackName = key)?.let { result.add(it) }
                    }
                }
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "getMcpServerStates error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseMcpServerState(item: JSONObject, fallbackName: String = ""): com.example.gemini.domain.model.McpServerState? {
        val specObj = item.optJSONObject("spec")
        val name = specObj?.optString("serverName")?.ifBlank { null }
            ?: item.optString("serverName").ifBlank { null }
            ?: fallbackName.ifBlank { "mcp_server" }

        val command = specObj?.optString("command", "") ?: ""
        val argsList = mutableListOf<String>()
        specObj?.optJSONArray("args")?.let { arr ->
            for (j in 0 until arr.length()) {
                argsList.add(arr.optString(j))
            }
        }
        val envMap = mutableMapOf<String, String>()
        specObj?.optJSONObject("env")?.let { envObj ->
            val envKeys = envObj.keys()
            while (envKeys.hasNext()) {
                val k = envKeys.next()
                envMap[k] = envObj.optString(k, "")
            }
        }
        val serverUrl = specObj?.optString("serverUrl", "") ?: ""
        val headersMap = mutableMapOf<String, String>()
        specObj?.optJSONObject("headers")?.let { hObj ->
            val hKeys = hObj.keys()
            while (hKeys.hasNext()) {
                val k = hKeys.next()
                headersMap[k] = hObj.optString(k, "")
            }
        }
        val disabled = specObj?.optBoolean("disabled", false) ?: false
        val cwd = specObj?.optString("cwd", "") ?: ""

        val spec = com.example.gemini.domain.model.McpServerSpec(
            serverName = name,
            command = command,
            args = argsList,
            env = envMap,
            serverUrl = serverUrl,
            headers = headersMap,
            disabled = disabled,
            cwd = cwd
        )

        val status = item.optString("status", "MCP_SERVER_STATUS_UNKNOWN")
        val error = item.optString("error", "").ifBlank { null }
        val instructions = item.optString("instructions", "").ifBlank { null }

        val toolsList = mutableListOf<com.example.gemini.domain.model.McpToolInfo>()
        item.optJSONArray("tools")?.let { toolsArr ->
            for (t in 0 until toolsArr.length()) {
                val tObj = toolsArr.optJSONObject(t) ?: continue
                val tName = tObj.optString("name", "")
                val tDesc = tObj.optString("description", "")
                val tSchema = tObj.optString("jsonSchemaString", "").ifBlank {
                    tObj.opt("inputSchema")?.toString() ?: ""
                }
                if (tName.isNotBlank()) {
                    toolsList.add(com.example.gemini.domain.model.McpToolInfo(name = tName, description = tDesc, inputSchema = tSchema))
                }
            }
        }

        return com.example.gemini.domain.model.McpServerState(
            name = name,
            spec = spec,
            status = status,
            error = error,
            tools = toolsList,
            instructions = instructions,
            isEnabled = !disabled
        )
    }

    suspend fun refreshMcpServers(hubUrl: String = DEFAULT_HUB_URL): Result<Unit> = withContext(Dispatchers.IO) {
        val res = callUnary("RefreshMcpServers", "{}", hubUrl)
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(res.exceptionOrNull() ?: Exception("RefreshMcpServers failed"))
    }

    suspend fun toggleMcpServer(serverName: String, enabled: Boolean, hubUrl: String = DEFAULT_HUB_URL): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("serverName", serverName)
            put("enabled", enabled)
        }.toString()
        val res = callUnary("ToggleMcpServer", payload, hubUrl)
        if (res.isSuccess) Result.success(Unit)
        else Result.failure(res.exceptionOrNull() ?: Exception("ToggleMcpServer failed"))
    }
}
