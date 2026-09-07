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

        val resp = client.newCall(req).execute()
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
                // Trailers reached, stream finished
                break
            }
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

    private val conversationCache = java.util.concurrent.ConcurrentHashMap<String, Conversation>()

    fun removeCachedConversation(id: String) {
        conversationCache.remove(id)
    }

    fun getCachedConversations(): List<Conversation> {
        return conversationCache.values.toList().sortedByDescending { it.updatedAt }
    }

    /**
     * Subscribes to live conversation summaries via JetboxSubscribeToSummaries.
     * Accumulates all updates in conversationCache so conversations never disappear on incremental updates.
     */
    fun subscribeToSummaries(hubUrl: String = DEFAULT_HUB_URL): Flow<List<Conversation>> = flow {
        if (conversationCache.isNotEmpty()) {
            emit(conversationCache.values.toList().sortedByDescending { it.updatedAt })
        }

        callStream("JetboxSubscribeToSummaries", "{}", hubUrl).collect { frameJson ->
            try {
                val root = JSONObject(frameJson)
                val updates = root.optJSONObject("updates")
                if (updates != null) {
                    val keys = updates.keys()
                    while (keys.hasNext()) {
                        val cid = keys.next()
                        val obj = updates.getJSONObject(cid)
                        val summary = obj.optString("summary", "Conversation").ifBlank { "Conversation" }
                        val stepCount = obj.optInt("stepCount", 0)
                        val lastModStr = obj.optString("lastModifiedTime", "")
                        val status = obj.optString("status", "")
                        val isRunning = status.contains("RUNNING", ignoreCase = true)

                        var lastModEpoch = System.currentTimeMillis()
                        if (lastModStr.isNotBlank()) {
                            try {
                                val cleanIso = if (lastModStr.length > 19) lastModStr.substring(0, 19) else lastModStr
                                lastModEpoch = ISO_FORMAT.parse(cleanIso)?.time ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                // Fallback
                            }
                        }

                        val existing = conversationCache[cid]
                        val createdEpoch = existing?.createdAt ?: lastModEpoch

                        conversationCache[cid] = Conversation(
                            id = cid,
                            title = summary,
                            modelId = existing?.modelId?.takeIf { it.isNotBlank() } ?: "MODEL_PLACEHOLDER_M319",
                            sessionId = cid,
                            summary = summary,
                            createdAt = createdEpoch,
                            updatedAt = lastModEpoch
                        )
                    }

                    val sorted = conversationCache.values.toList().sortedByDescending { it.updatedAt }
                    emit(sorted)
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

        val res = callUnary("DeleteCascadeTrajectory", payload, hubUrl).map { true }
        if (res.isSuccess) {
            conversationCache.remove(cascadeId)
        }
        res
    }

    /**
     * Starts a new conversation session on the daemon
     */
    suspend fun startCascade(
        cascadeId: String = UUID.randomUUID().toString(),
        modelEnum: String = "MODEL_PLACEHOLDER_M319",
        workspaceUri: String = "",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        val cid = cascadeId
        val payload = JSONObject().apply {
            put("source", "CORTEX_TRAJECTORY_SOURCE_CASCADE_CLIENT")
            put("cascadeId", cid)
            put("requestedModel", modelEnum)
            if (workspaceUri.isNotBlank()) {
                put("workspaceUris", JSONArray().put(workspaceUri))
                put("overrideWorkspaceUris", JSONArray().put(workspaceUri))
            } else {
                put("projectEnvConfig", JSONObject().apply {
                    put("projectId", "default-cli-project")
                    put("defaultProjectEnvironment", JSONObject())
                })
            }
        }.toString()

        val frameBytes = encodeFrame(payload)
        val token = getOrFetchCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/StartCascade"

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
                    return@withContext Result.failure(Exception("StartCascade failed: $err"))
                }
                Result.success(cid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCascade failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Loads raw trajectory steps for a conversation
     */
    suspend fun getCascadeTrajectorySteps(
        cascadeId: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cascade_id", cascadeId)
                put("trajectory_verbosity", 2)
            }.toString()

            var latestJson = ""
            callStream("GetCascadeTrajectorySteps", payload, hubUrl).collect { frameJson ->
                latestJson = frameJson
            }
            if (latestJson.isNotBlank()) {
                Result.success(latestJson)
            } else {
                Result.failure(Exception("No trajectory steps returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCascadeTrajectorySteps failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ==================== MODELS & QUOTA TELEMETRY ====================

    /**
     * Fetches all available models from daemon and maps them to AiModel
     */
    suspend fun getAvailableModels(
        forceRefresh: Boolean = false,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("force_refresh", forceRefresh)
            }.toString()

            var modelsJson = ""
            callStream("GetAvailableModels", payload, hubUrl).collect { frameJson ->
                modelsJson = frameJson
            }

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

                val family = when {
                    displayName.contains("claude", ignoreCase = true) || key.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                    displayName.contains("gemini", ignoreCase = true) || key.contains("gemini", ignoreCase = true) -> ModelFamily.GEMINI
                    else -> ModelFamily.OTHER
                }

                resultList.add(
                    AiModel(
                        id = modelEnum,
                        displayName = displayName,
                        family = family,
                        supportsThinking = supportsThinking,
                        description = details.optString("description", "")
                    )
                )
            }

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

                        val windowInfo = QuotaWindowInfo(
                            window = window,
                            displayName = b.optString("displayName", window),
                            remainingFraction = remFraction,
                            remainingPct = remPct,
                            usedPct = usedPct,
                            resetTime = resetTime,
                            description = bDesc
                        )

                        if (window.contains("5h", ignoreCase = true)) {
                            fiveHourInfo = windowInfo
                        } else if (window.contains("week", ignoreCase = true)) {
                            weeklyInfo = windowInfo
                        }
                    }

                    groupsList.add(
                        ModelQuotaGroup(
                            groupId = dispName.lowercase().replace(" ", "_"),
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
     * Sends a user prompt to SendUserCascadeMessage with structured options
     */
    suspend fun sendUserPrompt(
        cascadeId: String,
        text: String,
        modelEnum: String = "MODEL_PLACEHOLDER_M319",
        thinkingBudget: Int = 8192,
        autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> = withContext(Dispatchers.IO) {
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
                        put("model", modelEnum)
                    })
                    put("supportsThinking", thinkingBudget > 0)
                    put("thinkingBudget", thinkingBudget)
                })
                put("executorConfig", JSONObject().apply {
                    put("useCoreDirect", true)
                })
            })
        }.toString()

        sendUserCascadeMessage(payload, hubUrl)
    }

    /**
     * Sends user prompt to SendUserCascadeMessage
     */
    suspend fun sendUserCascadeMessage(
        payloadJson: String,
        hubUrl: String = DEFAULT_HUB_URL
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getOrFetchCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage"
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
                    return@withContext Result.failure(Exception("SendUserCascadeMessage failed: $err"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendUserCascadeMessage failed: ${e.message}")
            Result.failure(e)
        }
    }

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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("killBackgroundTasks", true)
        }.toString()

        val token = getOrFetchCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/CancelCascadeInvocation"
        val frameBytes = encodeFrame(payload)

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
                    return@withContext Result.failure(Exception("CancelCascadeInvocation failed: $err"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelCascadeInvocation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Parses raw trajectory steps JSON into a list of ChatMessage turns (User and Assistant)
     */
    fun parseStepsToChatMessages(stepsJson: String, conversationId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        try {
            val root = JSONObject(stepsJson)
            val steps = root.optJSONArray("steps") ?: return emptyList()

            var currentAssistantMsg: ChatMessage? = null
            val currentAssistantTools = mutableListOf<ToolCall>()
            val currentAssistantThought = StringBuilder()
            val currentAssistantText = StringBuilder()

            fun flushAssistant() {
                if (currentAssistantMsg != null || currentAssistantThought.isNotEmpty() || currentAssistantText.isNotEmpty() || currentAssistantTools.isNotEmpty()) {
                    messages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = currentAssistantText.toString().trim(),
                            thoughtText = currentAssistantThought.toString().trim().takeIf { it.isNotBlank() },
                            toolCalls = currentAssistantTools.toList(),
                            isStreaming = false
                        )
                    )
                    currentAssistantThought.clear()
                    currentAssistantText.clear()
                    currentAssistantTools.clear()
                    currentAssistantMsg = null
                }
            }

            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val stepType = step.optString("type", "")
                val stepIndex = step.optInt("stepIndex", i)

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
                    currentAssistantMsg = ChatMessage(
                        id = "assistant_${conversationId}_$stepIndex",
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = ""
                    )

                    if (step.has("plannerResponse")) {
                        val pr = step.getJSONObject("plannerResponse")
                        val th = pr.optString("thinking", "")
                        val resp = pr.optString("response", "")
                        if (th.isNotBlank()) {
                            if (currentAssistantThought.isNotEmpty()) currentAssistantThought.append("\n\n")
                            currentAssistantThought.append(th)
                        }
                        if (resp.isNotBlank()) {
                            if (currentAssistantText.isNotEmpty()) currentAssistantText.append("\n\n")
                            currentAssistantText.append(resp)
                        }
                    }

                    if (step.has("runCommand")) {
                        val rc = step.getJSONObject("runCommand")
                        val cmd = rc.optString("commandLine", rc.optString("proposedCommandLine", ""))
                        val out = rc.optJSONObject("combinedOutput")?.optString("full") ?: rc.optString("output", "")
                        val status = step.optString("status", "SUCCESS")
                        val toolStatus = if (status.contains("WAIT", ignoreCase = true)) "WAITING"
                        else if (status.contains("ERROR", ignoreCase = true) || status.contains("FAIL", ignoreCase = true)) "FAILED"
                        else "SUCCESS"
                        currentAssistantTools.add(
                            ToolCall(
                                id = "tool_${conversationId}_$stepIndex",
                                name = "bash",
                                command = cmd,
                                output = out,
                                status = toolStatus
                            )
                        )
                    }

                    if (step.has("modifyFile") || step.has("codeAction")) {
                        val ca = step.optJSONObject("codeAction") ?: step.optJSONObject("modifyFile")
                        val uri = ca?.optString("uri", "") ?: ""
                        val path = uri.removePrefix("file://")
                        val diff = ca?.optString("diff", "") ?: ""
                        currentAssistantTools.add(
                            ToolCall(
                                id = "tool_edit_${conversationId}_$stepIndex",
                                name = "edit_file",
                                command = path,
                                output = diff,
                                status = "SUCCESS"
                            )
                        )
                    }

                    if (step.has("searchWeb")) {
                        val sw = step.getJSONObject("searchWeb")
                        val query = sw.optString("query", "")
                        val summary = sw.optString("summary", "")
                        currentAssistantTools.add(
                            ToolCall(
                                id = "tool_web_${conversationId}_$stepIndex",
                                name = "web_search",
                                command = query,
                                output = summary,
                                status = "SUCCESS"
                            )
                        )
                    }
                }
            }
            flushAssistant()
        } catch (e: Exception) {
            Log.e(TAG, "parseStepsToChatMessages error: ${e.message}")
        }
        return messages
    }
}
