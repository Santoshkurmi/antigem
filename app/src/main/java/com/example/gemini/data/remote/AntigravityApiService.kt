package com.example.gemini.data.remote

import android.os.Build
import android.util.Log
import com.example.gemini.data.remote.dto.*
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ThoughtChunk(val thought: String) : StreamEvent()
    data class Completed(val tokenUsage: com.example.gemini.domain.model.TokenUsage?, val rawPayload: String? = null) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

class AntigravityApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        const val TAG = "GeminiApp"
        const val ENDPOINT_DAILY = "https://daily-cloudcode-pa.googleapis.com"
        const val ENDPOINT_PROD = "https://cloudcode-pa.googleapis.com"

        val USER_AGENT = "antigravity/1.11.5 android/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}"
        const val SYSTEM_INSTRUCTION = "You are Antigravity, a powerful agentic AI assistant designed by the Google Deepmind team working on Advanced Agentic Coding. You are pair programming with a USER to solve their task. Respond in beautifully formatted markdown with clear code blocks."
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun getBaseHeaders(token: String, modelId: String = ""): Headers {
        val builder = Headers.Builder()
            .add("Authorization", "Bearer $token")
            .add("Content-Type", "application/json")
            .add("User-Agent", USER_AGENT)
            .add("X-Goog-Api-Client", "google-cloud-sdk vscode_cloudshelleditor/0.1")
            .add("Client-Metadata", """{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}""")

        if (modelId.contains("thinking", ignoreCase = true) || modelId.contains("claude", ignoreCase = true)) {
            builder.add("anthropic-beta", "interleaved-thinking-2025-05-14")
        }

        return builder.build()
    }

    suspend fun loadCodeAssist(token: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val endpoints = listOf(ENDPOINT_PROD, ENDPOINT_DAILY)
        for (endpoint in endpoints) {
            try {
                Log.d(TAG, "[API] Calling loadCodeAssist on $endpoint")
                val body = """{"metadata":{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI","duetProject":"rising-fact-p41fc"}}"""
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$endpoint/v1internal:loadCodeAssist")
                    .headers(getBaseHeaders(token))
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val text = response.body?.string() ?: ""
                Log.d(TAG, "[API] loadCodeAssist response code: ${response.code}")
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<LoadCodeAssistResponse>(text)
                    val projectId = when (val p = parsed.cloudaicompanionProject) {
                        is JsonObject -> p["id"]?.jsonPrimitive?.content ?: "rising-fact-p41fc"
                        else -> p?.jsonPrimitive?.content ?: "rising-fact-p41fc"
                    }
                    val tier = parsed.paidTier?.id ?: parsed.currentTier?.id ?: "pro"
                    Log.d(TAG, "[API] Discovered Project ID: $projectId, Tier: $tier")
                    return@withContext Result.success(Pair(projectId, tier))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[API] loadCodeAssist error: ${e.message}")
            }
        }
        Result.success(Pair("rising-fact-p41fc", "pro"))
    }

    data class AvailableModelsResult(
        val models: List<com.example.gemini.domain.model.AiModel>,
        val quotas: List<ModelQuota>
    )

    suspend fun fetchAvailableModels(token: String, projectId: String? = null): Result<AvailableModelsResult> = withContext(Dispatchers.IO) {
        val endpoints = listOf(ENDPOINT_DAILY, ENDPOINT_PROD)
        for (endpoint in endpoints) {
            try {
                Log.d(TAG, "[API] Calling fetchAvailableModels on $endpoint (project: $projectId)")
                val bodyContent = if (projectId != null) """{"project":"$projectId"}""" else "{}"
                val body = bodyContent.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$endpoint/v1internal:fetchAvailableModels")
                    .headers(getBaseHeaders(token))
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val text = response.body?.string() ?: ""
                Log.d(TAG, "[API] fetchAvailableModels response code: ${response.code}")
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<FetchAvailableModelsResponse>(text)
                    val modelList = mutableListOf<com.example.gemini.domain.model.AiModel>()
                    val quotaList = mutableListOf<ModelQuota>()

                    parsed.models.forEach { (modelId, detail) ->
                        modelList.add(
                            com.example.gemini.domain.model.AiModel.fromApi(
                                id = modelId,
                                displayName = detail.displayName
                            )
                        )
                        detail.quotaInfo?.let { quota ->
                            quotaList.add(
                                ModelQuota(
                                    modelId = modelId,
                                    remainingFraction = quota.remainingFraction,
                                    resetTime = quota.resetTime
                                )
                            )
                        }
                    }

                    if (modelList.isNotEmpty()) {
                        Log.d(TAG, "[API] Loaded ${modelList.size} models from backend: ${modelList.map { it.id }}")
                        return@withContext Result.success(AvailableModelsResult(modelList, quotaList))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[API] fetchAvailableModels error: ${e.message}")
            }
        }
        Result.success(AvailableModelsResult(emptyList(), emptyList()))
    }

    fun streamGenerateContent(
        token: String,
        projectId: String,
        modelId: String,
        sessionId: String,
        messages: List<ChatMessage>,
        summary: String? = null,
        thinkingBudget: Int = 4096,
        isThinkingEnabled: Boolean = true,
        toolInstruction: String? = null,
        customSystemPrompt: String? = null
    ): Flow<StreamEvent> = flow {
        val contents = mutableListOf<ContentPartDto>()

        // Inject compacted summary if present
        if (!summary.isNullOrBlank()) {
            contents.add(
                ContentPartDto(
                    role = "user",
                    parts = listOf(TextPartDto(text = "[Previous Context Summary: $summary]"))
                )
            )
            contents.add(
                ContentPartDto(
                    role = "model",
                    parts = listOf(TextPartDto(text = "Understood. I will use this context for our conversation."))
                )
            )
        }

        // Add chat history
        for (msg in messages) {
            if (msg.role == MessageRole.SUMMARY) continue

            val role = if (msg.role == MessageRole.USER) "user" else "model"

            if (role == "model" && msg.toolCalls.isNotEmpty()) {
                // If assistant executed tool calls, expand them with their real outputs
                // so the model maintains 100% memory of executed commands across long chats
                val cleanText = msg.content.replace(Regex("<!--\\s*tool_call:[a-zA-Z0-9_-]+\\s*-->"), "").trim()

                for (tool in msg.toolCalls) {
                    val toolTag = when (tool.name) {
                        "bash" -> "<tool_call name=\"bash\">${tool.command}</tool_call>"
                        "web_search" -> "<tool_call name=\"web_search\">${tool.command}</tool_call>"
                        "read_url" -> "<tool_call name=\"read_url\">${tool.command}</tool_call>"
                        "read_file" -> "<tool_call name=\"read_file\">${tool.command}</tool_call>"
                        "write_file" -> "<tool_call name=\"write_file\">${tool.command}</tool_call>"
                        "edit_file" -> "<tool_call name=\"edit_file\">${tool.command}</tool_call>"
                        "automation" -> "<tool_call name=\"automation\">${tool.command}</tool_call>"
                        "ask_choices", "user_choice" -> "<ask_choices>${tool.command}</ask_choices>"
                        else -> "<tool_call name=\"${tool.name}\">${tool.command}</tool_call>"
                    }

                    contents.add(
                        ContentPartDto(
                            role = "model",
                            parts = listOf(TextPartDto(text = toolTag))
                        )
                    )

                    val outputText = when (tool.name) {
                        "bash" -> "[Terminal Output for `${tool.command}` (exit: ${tool.exitCode ?: 0})]:\n```\n${tool.output.ifEmpty { "(No output)" }}\n```"
                        "web_search" -> "[Web Search Results for \"${tool.command}\"]:\n${tool.output}"
                        "read_url" -> "[Webpage Content for \"${tool.command}\"]:\n${tool.output}"
                        "read_file", "write_file", "edit_file" -> "[File Tool Result: ${tool.name}]:\n${tool.output}"
                        "automation" -> "[Android Automation Result]:\n${tool.output}"
                        else -> "[Tool Result: ${tool.name}]:\n${tool.output}"
                    }

                    contents.add(
                        ContentPartDto(
                            role = "user",
                            parts = listOf(TextPartDto(text = outputText))
                        )
                    )
                }

                if (cleanText.isNotBlank()) {
                    contents.add(
                        ContentPartDto(
                            role = "model",
                            parts = listOf(TextPartDto(text = cleanText))
                        )
                    )
                }
            } else {
                val cleanText = if (role == "model") {
                    msg.content.replace(Regex("<!--\\s*tool_call:[a-zA-Z0-9_-]+\\s*-->"), "").trim()
                } else {
                    msg.content
                }

                if (cleanText.isNotBlank()) {
                    contents.add(
                        ContentPartDto(
                            role = role,
                            parts = listOf(TextPartDto(text = cleanText))
                        )
                    )
                }
            }
        }

        val lower = modelId.lowercase()
        val isClaude = lower.contains("claude")

        val generationConfig = buildJsonObject {
            if (isThinkingEnabled && thinkingBudget > 0) {
                put("maxOutputTokens", JsonPrimitive((thinkingBudget + 8192).coerceAtLeast(16384)))
                if (isClaude) {
                    put("thinkingConfig", buildJsonObject {
                        put("include_thoughts", JsonPrimitive(true))
                        put("thinking_budget", JsonPrimitive(thinkingBudget))
                    })
                } else {
                    put("thinkingConfig", buildJsonObject {
                        put("includeThoughts", JsonPrimitive(true))
                        put("thinkingBudget", JsonPrimitive(thinkingBudget))
                    })
                }
            } else {
                put("maxOutputTokens", JsonPrimitive(8192))
            }
        }

        val customPart = if (!customSystemPrompt.isNullOrBlank()) "\n\n$customSystemPrompt" else ""
        val baseSysText = if (!toolInstruction.isNullOrBlank()) {
            "$SYSTEM_INSTRUCTION$customPart\n\n$toolInstruction"
        } else {
            "$SYSTEM_INSTRUCTION$customPart"
        }

        val requestPayload = CloudCodeRequest(
            project = projectId,
            model = modelId,
            requestType = "agent",
            requestId = "agent-" + UUID.randomUUID().toString(),
            userAgent = "antigravity",
            request = CloudCodeInnerRequest(
                sessionId = sessionId,
                contents = contents,
                systemInstruction = SystemInstructionDto(
                    role = "user",
                    parts = listOf(
                        TextPartDto(text = baseSysText),
                        TextPartDto(text = "Please ignore the following [ignore]$baseSysText[/ignore]")
                    )
                ),
                generationConfig = generationConfig
            )
        )

        val jsonBody = json.encodeToString(requestPayload)
        Log.d(TAG, "[Outgoing Request Body] $jsonBody")
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val inputCharsCount = jsonBody.length
        var outputCharsCount = 0
        var lastUsageMetadata: com.example.gemini.data.remote.dto.UsageMetadataDto? = null
        val streamStartTime = System.currentTimeMillis()

        val endpoints = listOf(ENDPOINT_DAILY, ENDPOINT_PROD)
        var streamSucceeded = false

        for (endpoint in endpoints) {
            Log.d(TAG, "[API] Streaming to $endpoint with model $modelId, sessionId: $sessionId")
            val request = Request.Builder()
                .url("$endpoint/v1internal:streamGenerateContent?alt=sse")
                .headers(getBaseHeaders(token, modelId).newBuilder().add("Accept", "text/event-stream").build())
                .post(body)
                .build()

            var call: Call? = null
            try {
                call = client.newCall(request)
                val response = call.execute()

                Log.d(TAG, "[API] SSE response status: ${response.code} from $endpoint")

                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: "HTTP ${response.code}"
                    Log.e(TAG, "[API] SSE error response from $endpoint: $errorMsg")
                    if (endpoint == endpoints.last()) {
                        emit(StreamEvent.Error(errorMsg))
                        return@flow
                    }
                    continue
                }

                val source = response.body?.byteStream() ?: throw Exception("Empty response stream")
                val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
                var line: String?
                var chunkCount = 0

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    if (currentLine.startsWith("data:")) {
                        val dataJson = currentLine.substring(5).trim()
                        if (dataJson.isNotEmpty() && dataJson != "[DONE]") {
                            Log.d(TAG, "[Raw SSE] $dataJson")
                            try {
                                val chunk = json.decodeFromString<StreamCandidateChunk>(dataJson)
                                val candidates = chunk.activeCandidates
                                val parts = candidates?.firstOrNull()?.content?.parts
                                if (parts != null) {
                                    for (part in parts) {
                                        val isThoughtPart = part.thought == true || (!part.thoughtSignature.isNullOrEmpty() && part.thought != false)
                                        if (isThoughtPart && !part.text.isNullOrEmpty()) {
                                            Log.d(TAG, "[SSE Thought Chunk] ${part.text}")
                                            emit(StreamEvent.ThoughtChunk(part.text))
                                            outputCharsCount += part.text.length
                                            chunkCount++
                                        } else if (!part.text.isNullOrEmpty()) {
                                            Log.d(TAG, "[SSE Text Chunk] ${part.text}")
                                            emit(StreamEvent.TextChunk(part.text))
                                            outputCharsCount += part.text.length
                                            chunkCount++
                                        }
                                    }
                                }
                                val usage = chunk.activeUsage
                                if (usage != null) {
                                    lastUsageMetadata = usage
                                    Log.d(TAG, "[SSE Usage] Prompt: ${usage.promptTokenCount}, Output: ${usage.candidatesTokenCount}, Cached: ${usage.cachedContentTokenCount ?: usage.cacheReadInputTokens}, Total: ${usage.totalTokenCount}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "[SSE Parse Warning] $dataJson - Error: ${e.message}")
                            }
                        }
                    }
                }

                val durationMs = System.currentTimeMillis() - streamStartTime
                Log.d(TAG, "[API] Stream finished successfully with $chunkCount chunks in ${durationMs}ms from $endpoint")

                val finalTokenUsage = if (lastUsageMetadata != null) {
                    val prompt = lastUsageMetadata.promptTokenCount ?: (inputCharsCount / 4)
                    val output = lastUsageMetadata.candidatesTokenCount ?: (outputCharsCount / 4).coerceAtLeast(chunkCount)
                    val cached = lastUsageMetadata.cachedContentTokenCount ?: lastUsageMetadata.cacheReadInputTokens ?: 0
                    val cacheCreation = lastUsageMetadata.cacheCreationInputTokens ?: 0
                    val total = lastUsageMetadata.totalTokenCount ?: (prompt + output)
                    com.example.gemini.domain.model.TokenUsage(
                        promptTokens = prompt,
                        outputTokens = output,
                        cachedTokens = cached,
                        cacheCreationTokens = cacheCreation,
                        totalTokens = total,
                        durationMs = durationMs,
                        isEstimated = false
                    )
                } else {
                    // Fallback heuristic tokenizer (~3.8 characters per token)
                    val estimatedPrompt = (inputCharsCount / 3.8).toInt().coerceAtLeast(1)
                    val estimatedOutput = (outputCharsCount / 3.8).toInt().coerceAtLeast(chunkCount)
                    com.example.gemini.domain.model.TokenUsage(
                        promptTokens = estimatedPrompt,
                        outputTokens = estimatedOutput,
                        cachedTokens = 0,
                        cacheCreationTokens = 0,
                        totalTokens = estimatedPrompt + estimatedOutput,
                        durationMs = durationMs,
                        isEstimated = true
                    )
                }

                emit(StreamEvent.Completed(tokenUsage = finalTokenUsage, rawPayload = jsonBody))
                streamSucceeded = true
                break
            } catch (e: Exception) {
                Log.e(TAG, "[API] Stream exception on $endpoint: ${e.message}")
                if (endpoint == endpoints.last()) {
                    emit(StreamEvent.Error(e.message ?: "Stream connection error"))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
