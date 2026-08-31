package com.example.gemini.data.remote

import android.util.Log
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AgyConversationSummary(
    val id: String,
    val title: String,
    val createdAt: String,
    val stepsCount: Int = 0,
    val isRunning: Boolean = false,
    val lastActivity: String? = null
)

data class AgyProjectSummary(
    val name: String,
    val path: String
)

data class AgyActiveInstance(
    val conversationId: String,
    val model: String,
    val workspaceDir: String,
    val pid: Long = 0,
    val uptimeSeconds: Long = 0,
    val isBusy: Boolean = false
)

enum class BridgeConnectionState {
    CONNECTED_READY,
    STREAMING,
    SPAWNING_INSTANCE,
    CONNECTING,
    RECONNECTING,
    OFFLINE_ERROR
}

sealed class AgyStreamEvent {
    data class TextChunk(val text: String, val seq: Long? = null) : AgyStreamEvent()
    data class ThoughtChunk(val thought: String, val durationMs: Long? = null, val seq: Long? = null) : AgyStreamEvent()
    data class ToolChunk(val tool: ToolCall, val seq: Long? = null) : AgyStreamEvent()
    data class InstanceStatus(val status: String, val message: String? = null, val conversationId: String? = null) : AgyStreamEvent()
    data class SessionAttached(val conversationId: String, val isRunning: Boolean, val prompt: String? = null) : AgyStreamEvent()
    data class StreamSnapshot(
        val conversationId: String,
        val isRunning: Boolean,
        val status: String,
        val seq: Long,
        val prompt: String? = null,
        val thought: String = "",
        val content: String = "",
        val activeTools: List<ToolCall> = emptyList()
    ) : AgyStreamEvent()
    data class QuotaUpdate(val quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse) : AgyStreamEvent()
    data class Completed(
        val tokenUsage: TokenUsage?,
        val conversationId: String? = null,
        val fullResponse: String? = null,
        val seq: Long? = null
    ) : AgyStreamEvent()
    data class Error(val message: String) : AgyStreamEvent()
}

class AgyBridgeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite read timeout for persistent WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS) // Active heartbeat ping every 10 seconds
        .retryOnConnectionFailure(true)
        .build()
) {
    companion object {
        const val TAG = "AgyBridgeService"
        const val DEFAULT_WS_URL = "ws://127.0.0.1:8080"
        const val DEFAULT_HTTP_URL = "http://127.0.0.1:8080"
    }

    private val _connectionState = MutableStateFlow(BridgeConnectionState.CONNECTING)
    val connectionState: StateFlow<BridgeConnectionState> = _connectionState.asStateFlow()

    fun updateConnectionState(newState: BridgeConnectionState) {
        _connectionState.value = newState
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var activeWebSocket: WebSocket? = null

    suspend fun fetchModels(httpBaseUrl: String = DEFAULT_HTTP_URL): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/models")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: JSONArray()
                val models = mutableListOf<AiModel>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.optString("name", id)
                    models.add(AiModel.fromApi(id, name))
                }

                Result.success(models)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchModels failed: ${e.message}")
            _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
            Result.failure(e)
        }
    }

    suspend fun fetchQuotas(
        httpBaseUrl: String = DEFAULT_HTTP_URL,
        force: Boolean = false
    ): Result<com.example.gemini.domain.model.QuotaSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val url = if (force) "$httpBaseUrl/api/quotas?force=true" else "$httpBaseUrl/api/quotas"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val summary = parseQuotaSummary(json)
                Result.success(summary)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchQuotas failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun parseQuotaSummary(json: JSONObject): com.example.gemini.domain.model.QuotaSummaryResponse {
        val groupsArr = json.optJSONArray("groups") ?: JSONArray()
        val groupsList = mutableListOf<com.example.gemini.domain.model.ModelQuotaGroup>()

        for (i in 0 until groupsArr.length()) {
            val groupObj = groupsArr.getJSONObject(i)
            val groupId = groupObj.optString("groupId", "")
            val groupName = groupObj.optString("groupName", "")
            val desc = groupObj.optString("description", "")

            val fiveHourObj = groupObj.optJSONObject("fiveHour")
            val fiveHour = fiveHourObj?.let {
                com.example.gemini.domain.model.QuotaWindowInfo(
                    window = it.optString("window", "5h"),
                    displayName = it.optString("displayName", ""),
                    remainingFraction = it.optDouble("remainingFraction", 1.0).toFloat(),
                    remainingPct = it.optString("remainingPct", "100.0%"),
                    usedPct = it.optString("usedPct", "0.0%"),
                    resetTime = it.optString("resetTime", "").takeIf { t -> t.isNotBlank() },
                    countdown = it.optString("countdown", ""),
                    description = it.optString("description", "")
                )
            }

            val weeklyObj = groupObj.optJSONObject("weekly")
            val weekly = weeklyObj?.let {
                com.example.gemini.domain.model.QuotaWindowInfo(
                    window = it.optString("window", "weekly"),
                    displayName = it.optString("displayName", ""),
                    remainingFraction = it.optDouble("remainingFraction", 1.0).toFloat(),
                    remainingPct = it.optString("remainingPct", "100.0%"),
                    usedPct = it.optString("usedPct", "0.0%"),
                    resetTime = it.optString("resetTime", "").takeIf { t -> t.isNotBlank() },
                    countdown = it.optString("countdown", ""),
                    description = it.optString("description", "")
                )
            }

            groupsList.add(
                com.example.gemini.domain.model.ModelQuotaGroup(
                    groupId = groupId,
                    groupName = groupName,
                    description = desc,
                    fiveHour = fiveHour,
                    weekly = weekly
                )
            )
        }

        return com.example.gemini.domain.model.QuotaSummaryResponse(
            groups = groupsList,
            lastUpdated = json.optString("lastUpdated", "")
        )
    }

    suspend fun checkServerHealth(httpBaseUrl: String = DEFAULT_HTTP_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val online = response.isSuccessful
                _connectionState.value = if (online) BridgeConnectionState.CONNECTED_READY else BridgeConnectionState.OFFLINE_ERROR
                online
            }
        } catch (e: Exception) {
            _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
            false
        }
    }

    suspend fun fetchConversations(
        searchQuery: String? = null,
        page: Int = 1,
        limit: Int = 30,
        httpBaseUrl: String = DEFAULT_HTTP_URL
    ): Result<List<AgyConversationSummary>> = withContext(Dispatchers.IO) {
        try {
            val qParam = if (!searchQuery.isNullOrBlank()) "&q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}" else ""
            val request = Request.Builder()
                .url("$httpBaseUrl/api/conversations?page=$page&limit=$limit$qParam")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val arr = json.optJSONArray("conversations") ?: JSONArray()
                val list = mutableListOf<AgyConversationSummary>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AgyConversationSummary(
                            id = obj.getString("id"),
                            title = obj.optString("title", "Conversation"),
                            createdAt = obj.optString("created_at", ""),
                            stepsCount = obj.optInt("steps_count", 0),
                            isRunning = obj.optBoolean("isRunning", false),
                            lastActivity = obj.optString("lastActivity").takeIf { it.isNotBlank() }
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchConversations failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun uploadAttachment(
        filename: String,
        base64Data: String,
        projectPath: String? = null,
        httpBaseUrl: String = DEFAULT_HTTP_URL
    ): Result<com.example.gemini.domain.model.ChatAttachment> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("filename", filename)
                put("base64Data", base64Data)
                if (!projectPath.isNullOrBlank()) {
                    put("projectPath", projectPath)
                }
            }.toString()

            val request = Request.Builder()
                .url("$httpBaseUrl/api/upload")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Upload HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val att = com.example.gemini.domain.model.ChatAttachment(
                    id = obj.optString("id", "att_${System.currentTimeMillis()}"),
                    name = obj.optString("name", filename),
                    path = obj.optString("path", ""),
                    isImage = obj.optBoolean("isImage", false),
                    size = obj.optLong("size", 0L),
                    url = obj.optString("url", "")
                )
                Result.success(att)
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAttachment failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchConversationMessages(
        conversationId: String,
        httpBaseUrl: String = DEFAULT_HTTP_URL
    ): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/conversations/$conversationId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val arr = json.optJSONArray("messages") ?: JSONArray()
                val chatMessages = mutableListOf<ChatMessage>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val roleStr = obj.optString("role", "user")
                    val role = if (roleStr == "agent" || roleStr == "assistant") com.example.gemini.domain.model.MessageRole.ASSISTANT
                               else com.example.gemini.domain.model.MessageRole.USER
                    val content = obj.optString("content", "")
                    val thinking = obj.optString("thinking").takeIf { it.isNotBlank() }

                    val toolCallsList = mutableListOf<ToolCall>()
                    val toolsArr = obj.optJSONArray("tool_calls")
                    if (toolsArr != null) {
                        for (t in 0 until toolsArr.length()) {
                            val toolObj = toolsArr.optJSONObject(t) ?: continue
                            val toolId = toolObj.optString("id").ifBlank { "tool_${conversationId}_$t" }
                            val toolName = toolObj.optString("name").ifBlank { toolObj.optString("tool_name", "tool") }
                            val toolCmd = toolObj.optString("command").ifBlank {
                                toolObj.optJSONObject("args")?.toString() ?: toolObj.optJSONObject("parameters")?.toString() ?: ""
                            }
                            val toolOut = toolObj.optString("output", "")
                            val toolStatus = toolObj.optString("status", "SUCCESS")
                            toolCallsList.add(
                                ToolCall(
                                    id = toolId,
                                    name = toolName,
                                    command = toolCmd,
                                    status = toolStatus,
                                    output = toolOut
                                )
                            )
                        }
                    }

                    val rawContent = obj.optString("rawContent").takeIf { it.isNotBlank() && it != "null" }
                    val contextSummary = obj.optString("contextSummary").takeIf { it.isNotBlank() && it != "null" }
                    val stepIndex = if (obj.has("stepIndex")) obj.optInt("stepIndex") else null

                    var tokenUsage: com.example.gemini.domain.model.TokenUsage? = null
                    val usageObj = obj.optJSONObject("tokenUsage")
                    if (usageObj != null) {
                        val inTok = usageObj.optInt("inputTokens", 0)
                        val outTok = usageObj.optInt("outputTokens", 0)
                        val cacheTok = usageObj.optInt("cacheReadTokens", 0)
                        val totalTok = usageObj.optInt("totalTokens", inTok + outTok)
                        val dur = (obj.optDouble("durationSeconds", 0.0) * 1000).toLong()
                        tokenUsage = com.example.gemini.domain.model.TokenUsage(
                            promptTokens = inTok,
                            outputTokens = outTok,
                            cachedTokens = cacheTok,
                            totalTokens = totalTok,
                            durationMs = dur
                        )
                    }

                    chatMessages.add(
                        ChatMessage(
                            id = "msg_${conversationId}_$i",
                            conversationId = conversationId,
                            role = role,
                            content = content,
                            thoughtText = thinking,
                            toolCalls = toolCallsList,
                            isStreaming = false,
                            rawContent = rawContent,
                            contextSummary = contextSummary,
                            stepIndex = stepIndex,
                            tokenUsage = tokenUsage
                        )
                    )
                }

                Result.success(chatMessages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchConversationMessages failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchProjects(httpBaseUrl: String = DEFAULT_HTTP_URL): Result<List<AgyProjectSummary>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/projects")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val arr = json.optJSONArray("projects") ?: JSONArray()
                val list = mutableListOf<AgyProjectSummary>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AgyProjectSummary(
                            name = obj.getString("name"),
                            path = obj.getString("path")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchProjects failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun prewarm(
        conversationId: String?,
        model: String,
        workspaceDir: String? = null,
        httpBaseUrl: String = DEFAULT_HTTP_URL
    ) = withContext(Dispatchers.IO) {
        try {
            val targetId = conversationId ?: "new"
            val payload = JSONObject().apply {
                put("model", model)
                if (!workspaceDir.isNullOrBlank()) {
                    put("workspaceDir", workspaceDir)
                }
            }.toString()

            val request = Request.Builder()
                .url("$httpBaseUrl/api/conversations/$targetId/warm")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { }
        } catch (e: Exception) {
            Log.w(TAG, "prewarm failed (non-fatal): ${e.message}")
        }
    }

    suspend fun fetchActiveInstances(httpBaseUrl: String = DEFAULT_HTTP_URL): Result<List<AgyActiveInstance>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/instances")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val arr = json.optJSONArray("instances") ?: JSONArray()
                val list = mutableListOf<AgyActiveInstance>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        AgyActiveInstance(
                            conversationId = obj.getString("conversationId"),
                            model = obj.optString("model", "gemini-3.7-flash-high"),
                            workspaceDir = obj.optString("workspaceDir", ""),
                            pid = obj.optLong("pid", 0),
                            uptimeSeconds = obj.optLong("uptimeSeconds", 0),
                            isBusy = obj.optBoolean("isBusy", false)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchActiveInstances failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun terminateInstance(conversationId: String, httpBaseUrl: String = DEFAULT_HTTP_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/instances/$conversationId/terminate")
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "terminateInstance failed: ${e.message}")
            false
        }
    }

    fun streamPrompt(
        prompt: String,
        model: String = "gemini-3.7-flash-high",
        conversationId: String? = null,
        workspaceDir: String? = null,
        wsUrl: String = DEFAULT_WS_URL
    ): Flow<AgyStreamEvent> = callbackFlow {
        _connectionState.value = BridgeConnectionState.CONNECTING
        val request = Request.Builder().url(wsUrl).build()

        var assignedConversationId = conversationId
        val activeToolsMap = mutableMapOf<String, ToolCall>()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "[WS] Connected to AGY daemon bridge")
                activeWebSocket = webSocket
                _connectionState.value = BridgeConnectionState.CONNECTED_READY

                // Send prompt payload
                val payload = JSONObject().apply {
                    put("type", "send_prompt")
                    put("prompt", prompt)
                    put("model", model)
                    if (!conversationId.isNullOrBlank()) {
                        put("conversationId", conversationId)
                    }
                    if (!workspaceDir.isNullOrBlank()) {
                        put("workspaceDir", workspaceDir)
                    }
                }
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val type = root.optString("type")

                    when (type) {
                        "agy_event" -> {
                            val data = root.optJSONObject("data") ?: return
                            val event = data.optString("event")

                            if (event == "init") {
                                val convId = data.optString("conversation_id")
                                if (convId.isNotBlank()) assignedConversationId = convId
                                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                            }

                            if (event == "step_update") {
                                _connectionState.value = BridgeConnectionState.STREAMING
                                val step = data.optJSONObject("step_update") ?: return
                                val stepType = step.optString("step_type")

                                // 1. Reasoning / Thought tokens
                                if (step.has("thought") || step.has("thinking") || stepType == "thought") {
                                    val thought = step.optString("thought").ifBlank { step.optString("thinking") }
                                    if (thought.isNotBlank()) {
                                        trySend(AgyStreamEvent.ThoughtChunk(thought))
                                    }
                                }

                                // 2. Tool executions
                                if (stepType == "tool" || step.has("tool_info") || step.has("tool_name")) {
                                    val info = step.optJSONObject("tool_info")
                                    val name = info?.optString("name") ?: step.optString("tool_name", "tool")
                                    val params = info?.optJSONObject("parameters")?.toString()
                                        ?: step.optString("tool_args", "")
                                    val output = info?.optString("output") ?: step.optString("tool_output", "")
                                    val state = step.optString("state", "ACTIVE")
                                    val stepIndex = step.optInt("step_index", activeToolsMap.size)
                                    val durationSec = step.optDouble("duration_seconds", 0.0)

                                    val toolId = "tool_${assignedConversationId ?: "live"}_$stepIndex"
                                    val toolCall = ToolCall(
                                        id = toolId,
                                        name = name,
                                        command = params,
                                        status = if (state == "DONE") "SUCCESS" else "RUNNING",
                                        output = output,
                                        durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else null
                                    )
                                    activeToolsMap[toolId] = toolCall
                                    trySend(AgyStreamEvent.ToolChunk(toolCall))
                                }

                                // 3. Agent response deltas
                                if (stepType == "agent_response" && step.has("text_delta")) {
                                    val delta = step.optString("text_delta")
                                    if (delta.isNotEmpty()) {
                                        trySend(AgyStreamEvent.TextChunk(delta))
                                    }
                                }
                            }

                            if (event == "result") {
                                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                                val res = data.optJSONObject("result") ?: JSONObject()
                                val usage = res.optJSONObject("usage")
                                val durationSec = res.optDouble("duration_seconds", 0.0)

                                val tokenUsage = if (usage != null) {
                                    TokenUsage(
                                        promptTokens = usage.optInt("input_tokens", 0),
                                        outputTokens = usage.optInt("output_tokens", 0),
                                        cachedTokens = usage.optInt("cache_read_tokens", 0),
                                        totalTokens = usage.optInt("total_tokens", 0),
                                        durationMs = (durationSec * 1000).toLong()
                                    )
                                } else null

                                val fullResponse = res.optString("response")
                                val error = res.optString("error")

                                if (error.isNotBlank()) {
                                    trySend(AgyStreamEvent.Error(error))
                                } else {
                                    trySend(
                                        AgyStreamEvent.Completed(
                                            tokenUsage = tokenUsage,
                                            conversationId = assignedConversationId,
                                            fullResponse = fullResponse
                                        )
                                    )
                                }
                            }
                        }

                        "stream_snapshot" -> {
                            val convId = root.optString("conversationId")
                            val isRunning = root.optBoolean("isRunning", false)
                            val status = root.optString("status", "IDLE")
                            val seq = root.optLong("seq", 0L)
                            val prompt = root.optString("prompt").takeIf { it.isNotBlank() }
                            val thought = root.optString("thought", "")
                            val content = root.optString("content", "")
                            val toolsArr = root.optJSONArray("activeTools")
                            val tools = mutableListOf<ToolCall>()
                            if (toolsArr != null) {
                                for (i in 0 until toolsArr.length()) {
                                    val tObj = toolsArr.optJSONObject(i) ?: continue
                                    tools.add(
                                        ToolCall(
                                            id = tObj.optString("id"),
                                            name = tObj.optString("name"),
                                            command = tObj.optString("command"),
                                            status = tObj.optString("status", "RUNNING"),
                                            output = tObj.optString("output", "")
                                        )
                                    )
                                }
                            }
                            if (convId.isNotBlank()) assignedConversationId = convId
                            _connectionState.value = if (isRunning) BridgeConnectionState.STREAMING else BridgeConnectionState.CONNECTED_READY
                            trySend(
                                AgyStreamEvent.StreamSnapshot(
                                    conversationId = convId,
                                    isRunning = isRunning,
                                    status = status,
                                    seq = seq,
                                    prompt = prompt,
                                    thought = thought,
                                    content = content,
                                    activeTools = tools
                                )
                            )
                        }

                        "session_attached" -> {
                            val convId = root.optString("conversationId")
                            val isRunning = root.optBoolean("isRunning", false)
                            val prompt = root.optString("prompt").takeIf { it.isNotBlank() }
                            if (convId.isNotBlank()) assignedConversationId = convId
                            _connectionState.value = if (isRunning) BridgeConnectionState.STREAMING else BridgeConnectionState.CONNECTED_READY
                            trySend(AgyStreamEvent.SessionAttached(conversationId = convId, isRunning = isRunning, prompt = prompt))
                        }

                        "instance_status" -> {
                            val status = root.optString("status")
                            val msg = root.optString("message").takeIf { it.isNotBlank() }
                            val convId = root.optString("conversationId").takeIf { it.isNotBlank() }
                            if (convId != null) assignedConversationId = convId
                            _connectionState.value = if (status == "creating") BridgeConnectionState.SPAWNING_INSTANCE else BridgeConnectionState.CONNECTED_READY
                            trySend(AgyStreamEvent.InstanceStatus(status = status, message = msg, conversationId = convId))
                        }

                        "quota_update" -> {
                            val quotaObj = root.optJSONObject("data")
                            if (quotaObj != null) {
                                val summary = parseQuotaSummary(quotaObj)
                                trySend(AgyStreamEvent.QuotaUpdate(summary))
                            }
                        }

                        "raw_chunk" -> {
                            val textChunk = root.optString("text")
                            if (textChunk.isNotBlank()) {
                                trySend(AgyStreamEvent.TextChunk(textChunk))
                            }
                        }

                        "done" -> {
                            _connectionState.value = BridgeConnectionState.CONNECTED_READY
                            channel.close()
                        }

                        "error" -> {
                            _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
                            val errMsg = root.optString("message", "Unknown bridge error")
                            trySend(AgyStreamEvent.Error(errMsg))
                            channel.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message: ${e.message}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[WS] Failure: ${t.message}")
                _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
                trySend(AgyStreamEvent.Error("Daemon connection failure: ${t.message}. Ensure Termux bridge is running."))
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[WS] Closed: $code $reason")
                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                channel.close()
            }
        }

        val ws = client.newWebSocket(request, wsListener)

        awaitClose {
            ws.cancel()
            activeWebSocket = null
        }
    }.flowOn(Dispatchers.IO)

    fun attachToConversation(
        conversationId: String,
        wsUrl: String = DEFAULT_WS_URL
    ): Flow<AgyStreamEvent> = callbackFlow {
        _connectionState.value = BridgeConnectionState.CONNECTING
        val request = Request.Builder().url(wsUrl).build()
        val activeToolsMap = mutableMapOf<String, ToolCall>()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeWebSocket = webSocket
                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                val payload = JSONObject().apply {
                    put("type", "attach_session")
                    put("conversationId", conversationId)
                }
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val type = root.optString("type")

                    when (type) {
                        "stream_snapshot" -> {
                            val convId = root.optString("conversationId")
                            val isRunning = root.optBoolean("isRunning", false)
                            val status = root.optString("status", "IDLE")
                            val seq = root.optLong("seq", 0L)
                            val prompt = root.optString("prompt").takeIf { it.isNotBlank() }
                            val thought = root.optString("thought", "")
                            val content = root.optString("content", "")
                            val toolsArr = root.optJSONArray("activeTools")
                            val tools = mutableListOf<ToolCall>()
                            if (toolsArr != null) {
                                for (i in 0 until toolsArr.length()) {
                                    val tObj = toolsArr.optJSONObject(i) ?: continue
                                    tools.add(
                                        ToolCall(
                                            id = tObj.optString("id"),
                                            name = tObj.optString("name"),
                                            command = tObj.optString("command"),
                                            status = tObj.optString("status", "RUNNING"),
                                            output = tObj.optString("output", "")
                                        )
                                    )
                                }
                            }
                            _connectionState.value = if (isRunning) BridgeConnectionState.STREAMING else BridgeConnectionState.CONNECTED_READY
                            trySend(
                                AgyStreamEvent.StreamSnapshot(
                                    conversationId = convId,
                                    isRunning = isRunning,
                                    status = status,
                                    seq = seq,
                                    prompt = prompt,
                                    thought = thought,
                                    content = content,
                                    activeTools = tools
                                )
                            )
                        }
                        "session_attached" -> {
                            val convId = root.optString("conversationId")
                            val isRunning = root.optBoolean("isRunning", false)
                            val prompt = root.optString("prompt").takeIf { it.isNotBlank() }
                            _connectionState.value = if (isRunning) BridgeConnectionState.STREAMING else BridgeConnectionState.CONNECTED_READY
                            trySend(AgyStreamEvent.SessionAttached(conversationId = convId, isRunning = isRunning, prompt = prompt))
                        }
                        "agy_event" -> {
                            val data = root.optJSONObject("data") ?: return
                            val event = data.optString("event")

                            if (event == "step_update") {
                                _connectionState.value = BridgeConnectionState.STREAMING
                                val step = data.optJSONObject("step_update") ?: return
                                val stepType = step.optString("step_type")

                                if (step.has("thought") || step.has("thinking") || stepType == "thought") {
                                    val thought = step.optString("thought").ifBlank { step.optString("thinking") }
                                    if (thought.isNotBlank()) {
                                        trySend(AgyStreamEvent.ThoughtChunk(thought))
                                    }
                                }

                                if (stepType == "tool" || step.has("tool_info") || step.has("tool_name")) {
                                    val info = step.optJSONObject("tool_info")
                                    val name = info?.optString("name") ?: step.optString("tool_name", "tool")
                                    val params = info?.optJSONObject("parameters")?.toString()
                                        ?: step.optString("tool_args", "")
                                    val output = info?.optString("output") ?: step.optString("tool_output", "")
                                    val state = step.optString("state", "ACTIVE")
                                    val stepIndex = step.optInt("step_index", activeToolsMap.size)
                                    val durationSec = step.optDouble("duration_seconds", 0.0)

                                    val toolId = "tool_${conversationId}_$stepIndex"
                                    val toolCall = ToolCall(
                                        id = toolId,
                                        name = name,
                                        command = params,
                                        status = if (state == "DONE") "SUCCESS" else "RUNNING",
                                        output = output,
                                        durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else null
                                    )
                                    activeToolsMap[toolId] = toolCall
                                    trySend(AgyStreamEvent.ToolChunk(toolCall))
                                }

                                if (stepType == "agent_response" && step.has("text_delta")) {
                                    val delta = step.optString("text_delta")
                                    if (delta.isNotEmpty()) {
                                        trySend(AgyStreamEvent.TextChunk(delta))
                                    }
                                }
                            }

                            if (event == "result") {
                                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                                val res = data.optJSONObject("result") ?: JSONObject()
                                val usage = res.optJSONObject("usage")
                                val durationSec = res.optDouble("duration_seconds", 0.0)

                                val tokenUsage = if (usage != null) {
                                    TokenUsage(
                                        promptTokens = usage.optInt("input_tokens", 0),
                                        outputTokens = usage.optInt("output_tokens", 0),
                                        cachedTokens = usage.optInt("cache_read_tokens", 0),
                                        totalTokens = usage.optInt("total_tokens", 0),
                                        durationMs = (durationSec * 1000).toLong()
                                    )
                                } else null

                                val fullResponse = res.optString("response")
                                val error = res.optString("error")

                                if (error.isNotBlank()) {
                                    trySend(AgyStreamEvent.Error(error))
                                } else {
                                    trySend(
                                        AgyStreamEvent.Completed(
                                            tokenUsage = tokenUsage,
                                            conversationId = conversationId,
                                            fullResponse = fullResponse
                                        )
                                    )
                                }
                            }
                        }

                        "instance_status" -> {
                            val status = root.optString("status")
                            val msg = root.optString("message").takeIf { it.isNotBlank() }
                            val convId = root.optString("conversationId").takeIf { it.isNotBlank() }
                            _connectionState.value = if (status == "creating") BridgeConnectionState.SPAWNING_INSTANCE else BridgeConnectionState.CONNECTED_READY
                            trySend(AgyStreamEvent.InstanceStatus(status = status, message = msg, conversationId = convId))
                        }

                        "done" -> {
                            _connectionState.value = BridgeConnectionState.CONNECTED_READY
                            channel.close()
                        }

                        "error" -> {
                            _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
                            val errMsg = root.optString("message", "Unknown bridge error")
                            trySend(AgyStreamEvent.Error(errMsg))
                            channel.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WS message: ${e.message}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "[WS] Attach Failure: ${t.message}")
                _connectionState.value = BridgeConnectionState.OFFLINE_ERROR
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = BridgeConnectionState.CONNECTED_READY
                channel.close()
            }
        }

        val ws = client.newWebSocket(request, wsListener)

        awaitClose {
            ws.cancel()
            activeWebSocket = null
        }
    }.flowOn(Dispatchers.IO)

    suspend fun updateConversationTitle(conversationId: String, title: String, httpBaseUrl: String = DEFAULT_HTTP_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("title", title) }.toString()
            val request = Request.Builder()
                .url("$httpBaseUrl/api/conversations/$conversationId")
                .patch(payload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateConversationTitle failed: ${e.message}")
            false
        }
    }

    suspend fun deleteConversation(conversationId: String, httpBaseUrl: String = DEFAULT_HTTP_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$httpBaseUrl/api/conversations/$conversationId")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteConversation failed: ${e.message}")
            false
        }
    }

    fun abort(conversationId: String? = null, httpBaseUrl: String = DEFAULT_HTTP_URL) {
        try {
            activeWebSocket?.let { ws ->
                val payload = JSONObject().apply {
                    put("type", "abort")
                    if (!conversationId.isNullOrBlank()) {
                        put("conversationId", conversationId)
                    }
                }
                ws.send(payload.toString())
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val payload = JSONObject().apply {
                        if (!conversationId.isNullOrBlank()) {
                            put("conversationId", conversationId)
                        }
                    }.toString()
                    val request = Request.Builder()
                        .url("$httpBaseUrl/api/abort")
                        .post(payload.toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(request).execute().use { }
                } catch (e: Exception) {
                    Log.w(TAG, "HTTP abort fallback failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "abort failed: ${e.message}")
        }
    }
}
