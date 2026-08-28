package com.example.gemini.data.remote

import android.util.Log
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class AgyStreamEvent {
    data class TextChunk(val text: String) : AgyStreamEvent()
    data class ThoughtChunk(val thought: String, val durationMs: Long? = null) : AgyStreamEvent()
    data class ToolChunk(val tool: ToolCall) : AgyStreamEvent()
    data class Completed(
        val tokenUsage: TokenUsage?,
        val conversationId: String? = null,
        val fullResponse: String? = null
    ) : AgyStreamEvent()
    data class Error(val message: String) : AgyStreamEvent()
}

class AgyBridgeService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val TAG = "AgyBridgeService"
        const val DEFAULT_WS_URL = "ws://127.0.0.1:8080"
        const val DEFAULT_HTTP_URL = "http://127.0.0.1:8080"
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
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
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
            Result.failure(e)
        }
    }

    suspend fun prewarm(
        conversationId: String?,
        model: String,
        httpBaseUrl: String = DEFAULT_HTTP_URL
    ) = withContext(Dispatchers.IO) {
        try {
            val targetId = conversationId ?: "new"
            val payload = JSONObject().apply {
                put("model", model)
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

    fun streamPrompt(
        prompt: String,
        model: String = "gemini-3.7-flash-high",
        conversationId: String? = null,
        wsUrl: String = DEFAULT_WS_URL
    ): Flow<AgyStreamEvent> = callbackFlow {
        val request = Request.Builder().url(wsUrl).build()

        var assignedConversationId = conversationId
        val activeToolsMap = mutableMapOf<Int, ToolCall>()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "[WS] Connected to AGY daemon bridge")
                activeWebSocket = webSocket

                // Send prompt payload
                val payload = JSONObject().apply {
                    put("type", "send_prompt")
                    put("prompt", prompt)
                    put("model", model)
                    if (!conversationId.isNullOrBlank()) {
                        put("conversationId", conversationId)
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
                            }

                            if (event == "step_update") {
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

                                    val toolCall = ToolCall(
                                        id = "tool_$stepIndex",
                                        name = name,
                                        command = params,
                                        status = if (state == "DONE") "SUCCESS" else "RUNNING",
                                        output = output,
                                        durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else null
                                    )
                                    activeToolsMap[stepIndex] = toolCall
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

                        "raw_chunk" -> {
                            val textChunk = root.optString("text")
                            if (textChunk.isNotBlank()) {
                                trySend(AgyStreamEvent.TextChunk(textChunk))
                            }
                        }

                        "done" -> {
                            channel.close()
                        }

                        "error" -> {
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
                trySend(AgyStreamEvent.Error("Daemon connection failure: ${t.message}. Ensure Termux bridge is running."))
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[WS] Closed: $code $reason")
                channel.close()
            }
        }

        val ws = client.newWebSocket(request, wsListener)

        awaitClose {
            ws.cancel()
            activeWebSocket = null
        }
    }.flowOn(Dispatchers.IO)

    fun abort() {
        try {
            val abortMsg = JSONObject().apply { put("type", "abort") }.toString()
            activeWebSocket?.send(abortMsg)
            activeWebSocket?.close(1000, "Aborted by user")
        } catch (e: Exception) {
            Log.w(TAG, "Error sending abort: ${e.message}")
        }
    }
}
