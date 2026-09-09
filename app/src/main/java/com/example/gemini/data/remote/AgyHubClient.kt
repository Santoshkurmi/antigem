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

    suspend fun login(hubUrl: String = DEFAULT_HUB_URL): Result<Unit> =
        callUnary("Login", "{}", hubUrl).map { }

    suspend fun authLogout(hubUrl: String = DEFAULT_HUB_URL): Result<Unit> =
        callUnary("AuthLogout", "{}", hubUrl).map { }

    suspend fun getAuthStatus(hubUrl: String = DEFAULT_HUB_URL): Result<Boolean> = withContext(Dispatchers.IO) {
        callUnary("GetAuthStatus", "{}", hubUrl).map { body ->
            try {
                val json = JSONObject(body)
                json.optBoolean("hasValidAuth", false)
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

    // ==================== CONVERSATION MANAGEMENT ====================

    /**
     * Subscribes to live conversation summaries via JetboxSubscribeToSummaries.
     * Streams conversation updates directly from daemon without local caching.
     */
    fun subscribeToSummaries(hubUrl: String = DEFAULT_HUB_URL): Flow<List<Conversation>> = flow {
        callStream("JetboxSubscribeToSummaries", "{}", hubUrl).collect { frameJson ->
            try {
                val root = JSONObject(frameJson)
                val updates = root.optJSONObject("updates")
                if (updates != null) {
                    val frameList = mutableListOf<Conversation>()
                    val keys = updates.keys()
                    while (keys.hasNext()) {
                        val cid = keys.next()
                        val obj = updates.getJSONObject(cid)
                        val summary = obj.optString("summary", "Conversation").ifBlank { "Conversation" }
                        val lastModStr = obj.optString("lastModifiedTime", "")

                        var lastModEpoch = System.currentTimeMillis()
                        if (lastModStr.isNotBlank()) {
                            try {
                                val cleanIso = if (lastModStr.length > 19) lastModStr.substring(0, 19) else lastModStr
                                lastModEpoch = ISO_FORMAT.parse(cleanIso)?.time ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                // Fallback to current time
                            }
                        }

                        frameList.add(
                            Conversation(
                                id = cid,
                                title = summary,
                                modelId = "",
                                sessionId = cid,
                                summary = summary,
                                createdAt = lastModEpoch,
                                updatedAt = lastModEpoch
                            )
                        )
                    }
                    if (frameList.isNotEmpty()) {
                        emit(frameList)
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

    /**
     * Sends a user prompt to SendUserCascadeMessage with structured options matching agyClient.js
     */
    suspend fun sendUserPrompt(
        cascadeId: String,
        text: String,
        modelEnum: String = "",
        thinkingBudget: Int = 8192,
        autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val resolvedModel = resolveModelEnum(modelEnum)
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("items", JSONArray().put(JSONObject().put("text", text)))
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
     * Handles interactive user approval or denial for cascade permission steps
     */
    suspend fun handleCascadeUserInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        allow: Boolean = true,
        scope: String = "PERMISSION_SCOPE_ONCE",
        userDenyInstruction: String = "",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("interaction", JSONObject().apply {
                if (trajectoryId.isNotBlank()) {
                    put("trajectoryId", trajectoryId)
                }
                put("stepIndex", stepIndex)
                put("permission", JSONObject().apply {
                    put("allow", allow)
                    if (allow) {
                        put("scope", scope)
                    } else {
                        put("userDenyInstruction", userDenyInstruction.ifBlank { "User rejected this command." })
                    }
                })
            })
        }.toString()
        return executeGrpcWebCall("HandleCascadeUserInteraction", payload, hubUrl).map { }
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

            return parseStepsArrayToChatMessages(steps, conversationId)
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
        conversationId: String
    ): ToolCall? {
        val reqInteraction = step.optJSONObject("requestedInteraction")
        val isWaitingPermission = reqInteraction?.has("permission") == true
        val meta = step.optJSONObject("metadata")
        val toolSummary = meta?.optString("toolSummary", "")?.trim() ?: ""
        val toolAction = meta?.optString("toolAction", "")?.trim() ?: ""
        val stepStatus = step.optString("status", "")
        val stepType = step.optString("type", "")

        fun resolveStatus(hasOutput: Boolean, isPending: Boolean = false): String {
            return when {
                isPending || stepStatus.contains("WAIT", ignoreCase = true) -> "PENDING_APPROVAL"
                stepStatus.contains("RUN", ignoreCase = true) -> "RUNNING"
                stepStatus.contains("ERROR", ignoreCase = true) || stepStatus.contains("FAIL", ignoreCase = true) -> "FAILED"
                stepStatus.contains("CANCEL", ignoreCase = true) || stepStatus.contains("REJECT", ignoreCase = true) -> "REJECTED"
                !hasOutput && !stepStatus.contains("SUCCESS", ignoreCase = true) && !stepStatus.contains("DONE", ignoreCase = true) -> "RUNNING"
                else -> "SUCCESS"
            }
        }

        // 1. Terminal / Shell command
        if (step.has("runCommand") || (isWaitingPermission && step.optJSONObject("runCommand") != null)) {
            val rc = step.optJSONObject("runCommand")
            val cmd = when {
                rc != null -> rc.optString("commandLine", rc.optString("proposedCommandLine", ""))
                isWaitingPermission -> reqInteraction.optJSONObject("permission")?.optJSONObject("resource")?.optString("target", "") ?: ""
                else -> ""
            }
            val out = rc?.optJSONObject("combinedOutput")?.optString("full") ?: rc?.optString("output", "") ?: ""
            val exitCode = if (rc != null && rc.has("exitCode")) rc.optInt("exitCode", 0) else null
            val toolStatus = resolveStatus(out.isNotBlank() || exitCode != null, isWaitingPermission)
            return ToolCall(
                id = "tool_${conversationId}_$stepIndex",
                name = "bash",
                command = cmd.ifBlank { toolSummary },
                output = out,
                status = toolStatus,
                exitCode = exitCode
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
                id = "tool_view_${conversationId}_$stepIndex",
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
                id = "tool_list_${conversationId}_$stepIndex",
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
                id = "tool_grep_${conversationId}_$stepIndex",
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
                id = "tool_find_${conversationId}_$stepIndex",
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
                id = "tool_edit_${conversationId}_$stepIndex",
                name = "edit_file",
                command = cmd,
                output = out,
                status = resolveStatus(true)
            )
        }

        // 7. Search Web
        if (step.has("searchWeb") || stepType.contains("SEARCH_WEB")) {
            val sw = step.optJSONObject("searchWeb") ?: JSONObject()
            val query = sw.optString("query", "")
            val summary = sw.optString("summary", "")
            return ToolCall(
                id = "tool_web_${conversationId}_$stepIndex",
                name = "web_search",
                command = query.ifBlank { toolSummary.ifBlank { "Web Search" } },
                output = summary,
                status = resolveStatus(summary.isNotBlank())
            )
        }

        // 8. Read URL Content
        if (step.has("readUrlContent") || stepType.contains("READ_URL")) {
            val ru = step.optJSONObject("readUrlContent") ?: JSONObject()
            val url = ru.optString("url", "")
            val content = ru.optString("markdown", ru.optString("content", ""))
            return ToolCall(
                id = "tool_read_${conversationId}_$stepIndex",
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
            val uri = gi.optJSONObject("generatedMedia")?.optString("uri", "") ?: gi.optString("uri", "")
            return ToolCall(
                id = "tool_genimg_${conversationId}_$stepIndex",
                name = "generate_image",
                command = prompt.ifBlank { toolSummary.ifBlank { "Generate Image" } },
                output = uri,
                status = resolveStatus(uri.isNotBlank())
            )
        }

        // 10. Generic Tool Call
        if (step.has("generic")) {
            val generic = step.getJSONObject("generic")
            val args = generic.optJSONObject("args")
            val toolCallMeta = meta?.optJSONObject("toolCall")
            val metaName = toolCallMeta?.optString("name", "")?.lowercase() ?: ""

            when {
                args?.has("CommandLine") == true || metaName == "run_command" -> {
                    val cmd = args?.optString("CommandLine", "") ?: ""
                    return ToolCall(
                        id = "tool_${conversationId}_$stepIndex",
                        name = "bash",
                        command = cmd.ifBlank { toolSummary },
                        output = toolAction,
                        status = resolveStatus(false)
                    )
                }
                args?.has("AbsolutePath") == true || metaName == "view_file" -> {
                    val path = (args?.optString("AbsolutePath", "") ?: "").removePrefix("file://")
                    val fileName = path.substringAfterLast('/').ifBlank { path }
                    return ToolCall(
                        id = "tool_view_${conversationId}_$stepIndex",
                        name = "view_file",
                        command = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "View File" },
                        output = path.ifBlank { toolAction },
                        status = resolveStatus(false)
                    )
                }
                args?.has("TargetFile") == true || metaName == "edit_file" || metaName == "write_to_file" || metaName == "replace_file_content" -> {
                    val path = (args?.optString("TargetFile", "") ?: "").removePrefix("file://")
                    val fileName = path.substringAfterLast('/').ifBlank { path }
                    val desc = args?.optString("Instruction", args.optString("Description", "")) ?: ""
                    return ToolCall(
                        id = "tool_edit_${conversationId}_$stepIndex",
                        name = "edit_file",
                        command = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "Edit File" },
                        output = if (desc.isNotBlank()) "$path\n$desc" else path.ifBlank { toolAction },
                        status = resolveStatus(false)
                    )
                }
                args?.has("DirectoryPath") == true || metaName == "list_dir" -> {
                    val dir = (args?.optString("DirectoryPath", "") ?: "").removePrefix("file://")
                    val dirName = dir.substringAfterLast('/').ifBlank { dir }
                    return ToolCall(
                        id = "tool_list_${conversationId}_$stepIndex",
                        name = "list_dir",
                        command = if (dirName.isNotBlank()) dirName else toolSummary.ifBlank { "Directory" },
                        output = dir.ifBlank { toolAction },
                        status = resolveStatus(false)
                    )
                }
                args?.has("Query") == true && args.has("SearchPath") -> {
                    val q = args.optString("Query", "")
                    val sp = args.optString("SearchPath", "").removePrefix("file://")
                    return ToolCall(
                        id = "tool_grep_${conversationId}_$stepIndex",
                        name = "grep_search",
                        command = "\"$q\" in ${sp.substringAfterLast('/')}",
                        output = toolAction,
                        status = resolveStatus(false)
                    )
                }
                args?.has("Url") == true || metaName == "read_url_content" -> {
                    val url = args?.optString("Url", "") ?: ""
                    return ToolCall(
                        id = "tool_read_${conversationId}_$stepIndex",
                        name = "read_url",
                        command = url.ifBlank { toolSummary },
                        output = toolAction,
                        status = resolveStatus(false)
                    )
                }
                else -> {
                    val fallbackTitle = toolSummary.ifBlank { metaName.ifBlank { "Tool" } }
                    return ToolCall(
                        id = "tool_gen_${conversationId}_$stepIndex",
                        name = metaName.ifBlank { "tool" },
                        command = fallbackTitle,
                        output = toolAction,
                        status = resolveStatus(false)
                    )
                }
            }
        }

        // 11. Final fallback when toolSummary is available
        if (toolSummary.isNotBlank()) {
            val name = when {
                stepType.contains("VIEW") -> "view_file"
                stepType.contains("LIST") -> "list_dir"
                stepType.contains("FIND") -> "find"
                stepType.contains("GREP") -> "grep_search"
                stepType.contains("FILE") || stepType.contains("CODE") -> "edit_file"
                stepType.contains("COMMAND") -> "bash"
                else -> "tool"
            }
            return ToolCall(
                id = "tool_step_${conversationId}_$stepIndex",
                name = name,
                command = toolSummary,
                output = toolAction.ifBlank { toolSummary },
                status = resolveStatus(true)
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
    fun parseStepsArrayToChatMessages(steps: JSONArray, conversationId: String): List<ChatMessage> {
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
                    if (userInput != null) {
                        val items = userInput.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            userText = items.getJSONObject(0).optString("text", "")
                        } else {
                            userText = userInput.optString("content", "")
                        }
                    }
                    if (userText.isNotBlank()) {
                        messages.add(
                            ChatMessage(
                                id = "user_${conversationId}_$stepIndex",
                                conversationId = conversationId,
                                role = MessageRole.USER,
                                content = userText,
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

                    val tool = extractToolCallFromStep(step, stepIndex, conversationId)
                    if (tool != null) {
                        turnTools[tool.id] = tool
                        val marker = "<!-- tool_call:${tool.id} -->"
                        val existing = turnStepTexts[stepIndex]
                        turnStepTexts[stepIndex] = if (existing != null) "$marker\n\n$existing" else marker
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
}
