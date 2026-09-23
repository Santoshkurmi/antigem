package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.AgyMediaItem
import com.example.gemini.data.remote.AgyHubClient.Companion.resolveModelEnum
import com.example.gemini.data.remote.core.AgyGrpcClient
import com.example.gemini.data.remote.dto.AgyStreamFrameDto
import com.example.gemini.data.remote.dto.AskQuestionInteractionDto
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.data.remote.dto.CancelCascadeStepsRequestDto
import com.example.gemini.data.remote.dto.CascadeInteractionPayloadDto
import com.example.gemini.data.remote.dto.HandleCascadeUserInteractionRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Dedicated RPC service for chat sessions: starting cascades, sending prompts, streaming state frames,
 * canceling invocations/steps, handling interactive approvals, and resolving blocking steps.
 */
class AgyChatService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
    companion object {
        private const val TAG = "AgyChatService"
        val instance by lazy { AgyChatService() }

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    /**
     * Starts a new conversation session on the daemon.
     */
    suspend fun startCascade(
        cascadeId: String = UUID.randomUUID().toString(),
        modelEnum: String = "",
        workspaceUri: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
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

        grpcClient.executeGrpcWebCall("StartCascade", payload, hubUrl).map { cid }
    }

    /**
     * Constructs and sends the full prompt message payload to SendUserCascadeMessage.
     */
    suspend fun sendUserPrompt(
        cascadeId: String,
        text: String,
        modelEnum: String = "",
        thinkingBudget: Int = 8192,
        autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
        media: List<AgyMediaItem> = emptyList(),
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val resolvedModel = resolveModelEnum(modelEnum)
        val promptText = if (text.isNotBlank()) text else if (media.isNotEmpty()) (media.firstOrNull()?.description ?: "Voice note") else ""
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("items", JSONArray().put(JSONObject().put("text", promptText)))
            if (media.isNotEmpty()) {
                val mediaArr = JSONArray()
                for (m in media) {
                    val isImageOrAudio = m.mimeType.startsWith("image/") || m.mimeType.startsWith("audio/")
                    mediaArr.put(JSONObject().apply {
                        put("mimeType", m.mimeType)
                        put("inlineData", if (isImageOrAudio) m.base64 else "")
                        if (m.durationSeconds > 0) {
                            put("durationSeconds", m.durationSeconds)
                        }
                        put("description", m.description)
                        if (!m.uri.isNullOrBlank()) {
                            put("uri", m.uri)
                        }
                        if (isImageOrAudio && !m.thumbnail.isNullOrBlank()) {
                            put("thumbnail", m.thumbnail)
                        }
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
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = grpcClient.executeGrpcWebCall("SendUserCascadeMessage", payloadJson, hubUrl).map { }

    /**
     * Streams real-time updates for an active conversation via StreamAgentStateUpdates
     */
    fun streamAgentStateUpdates(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("conversationId", cascadeId)
            put("cascadeId", cascadeId)
            put("subscriberId", UUID.randomUUID().toString())
            put("trajectoryVerbosity", 2)
        }.toString()
        grpcClient.callStream("StreamAgentStateUpdates", payload, hubUrl).collect { frame ->
            emit(frame)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams real-time updates parsed as AgyStreamFrameDto.
     */
    fun streamAgentStateFrames(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<AgyStreamFrameDto> = flow {
        val payload = JSONObject().apply {
            put("conversationId", cascadeId)
            put("cascadeId", cascadeId)
            put("subscriberId", UUID.randomUUID().toString())
            put("trajectoryVerbosity", 2)
        }.toString()
        val customHeaders = mapOf("x-conversation-id" to cascadeId)
        Log.d("CHAT_OPEN_DEBUG", "📡 [AgyChatService] Connecting to StreamAgentStateUpdates for cascadeId=$cascadeId, hubUrl=$hubUrl")

        grpcClient.callStream("StreamAgentStateUpdates", payload, hubUrl, customHeaders).collect { frameJson ->
            Log.d("CHAT_OPEN_DEBUG", "📦 [AgyChatService] Received raw stream frame (len=${frameJson.length}): ${frameJson.take(300)}")
            val frame = try {
                jsonParser.decodeFromString<AgyStreamFrameDto>(frameJson)
            } catch (e: Exception) {
                Log.e("CHAT_OPEN_DEBUG", "❌ [AgyChatService] JSON DECODE ERROR for frame (len=${frameJson.length}): ${frameJson.take(500)}", e)
                throw IllegalStateException("JSON decoding error: ${e.message}", e)
            }
            val statusStr = frame.status.ifBlank { frame.update?.status ?: "" }
            val stepsCount = frame.steps?.size ?: frame.update?.stepsUpdate?.steps?.size ?: frame.update?.mainTrajectoryUpdate?.stepsUpdate?.steps?.size ?: 0
            Log.d("CHAT_OPEN_DEBUG", "✅ [AgyChatService] Successfully decoded AgyStreamFrameDto: status=$statusStr, stepsCount=$stepsCount")
            emit(frame)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cancels / aborts running generation or commands
     */
    suspend fun cancelCascadeInvocation(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
            put("killBackgroundTasks", true)
        }.toString()
        return grpcClient.executeGrpcWebCall("CancelCascadeInvocation", payload, hubUrl).map { }
    }

    /**
     * Selectively cancels specific step indices without aborting the entire cascade session.
     */
    suspend fun cancelCascadeSteps(
        cascadeId: String,
        stepIndices: List<Int>,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val reqDto = CancelCascadeStepsRequestDto(
            cascadeId = cascadeId,
            stepIndices = stepIndices
        )
        val payload = jsonParser.encodeToString(CancelCascadeStepsRequestDto.serializer(), reqDto)
        return grpcClient.executeGrpcWebCall("CancelCascadeSteps", payload, hubUrl).map { }
    }

    /**
     * Handles interactive user approval or denial for cascade permission steps.
     */
    suspend fun handleCascadeUserInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        allow: Boolean = true,
        scope: String = "PERMISSION_SCOPE_ONCE",
        userDenyInstruction: String = "",
        interactionType: String = "permission",
        hubUrl: String = AuthPreferences.currentHubUrl
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
            val unaryRes = grpcClient.callUnary("HandleCascadeUserInteraction", payload, hubUrl)
            if (unaryRes.isSuccess) {
                Log.d(TAG, "handleCascadeUserInteraction succeeded via Connect-RPC (type=$pType)")
                return Result.success(Unit)
            } else {
                lastErr = unaryRes.exceptionOrNull()
            }

            // Strategy B: gRPC-Web application/grpc-web+json framed call
            val grpcRes = grpcClient.executeGrpcWebCall("HandleCascadeUserInteraction", payload, hubUrl)
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
     * Handles interactive ask_question tool submissions and skips with structured responses.
     */
    suspend fun handleAskQuestionInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        responses: List<AskQuestionResponseItemDto>,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val req = HandleCascadeUserInteractionRequestDto(
            cascadeId = cascadeId,
            interaction = CascadeInteractionPayloadDto(
                trajectoryId = trajectoryId,
                stepIndex = stepIndex,
                askQuestion = AskQuestionInteractionDto(
                    responses = responses
                )
            )
        )
        val payload = jsonParser.encodeToString(HandleCascadeUserInteractionRequestDto.serializer(), req)
        Log.d(TAG, "handleAskQuestionInteraction payload: $payload")

        // Strategy A: Connect-RPC unary call
        val unaryRes = grpcClient.callUnary("HandleCascadeUserInteraction", payload, hubUrl)
        if (unaryRes.isSuccess) {
            Log.d(TAG, "handleAskQuestionInteraction succeeded via Connect-RPC")
            return Result.success(Unit)
        }

        // Strategy B: gRPC-Web framed call
        val grpcRes = grpcClient.executeGrpcWebCall("HandleCascadeUserInteraction", payload, hubUrl)
        if (grpcRes.isSuccess) {
            Log.d(TAG, "handleAskQuestionInteraction succeeded via gRPC-Web")
            return Result.success(Unit)
        }

        val err = unaryRes.exceptionOrNull() ?: grpcRes.exceptionOrNull() ?: Exception("HandleCascadeUserInteraction failed")
        Log.e(TAG, "handleAskQuestionInteraction failed", err)
        return Result.failure(err)
    }

    /**
     * Resolves all outstanding or blocking steps in a cascade
     */
    suspend fun resolveOutstandingSteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
        }.toString()
        return grpcClient.executeGrpcWebCall("ResolveOutstandingSteps", payload, hubUrl).map { }
    }
}

