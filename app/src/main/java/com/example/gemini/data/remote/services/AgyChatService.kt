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

import exa.language_server_pb.ApprovalInteraction
import exa.language_server_pb.AskQuestionEntry
import exa.language_server_pb.AskQuestionInteraction
import exa.language_server_pb.AskQuestionOption
import exa.language_server_pb.AutoCommandConfig
import exa.language_server_pb.BuiltinAgentConfig
import exa.language_server_pb.CancelCascadeInvocationRequest
import exa.language_server_pb.CancelCascadeStepsRequest
import exa.language_server_pb.CascadeBrowserActionInteraction
import exa.language_server_pb.CascadeCommandsAutoExecution
import exa.language_server_pb.CascadeConfig
import exa.language_server_pb.CascadeExecutorConfig
import exa.language_server_pb.CascadeMcpInteraction
import exa.language_server_pb.CascadePlannerConfig
import exa.language_server_pb.CascadeReadUrlContentInteraction
import exa.language_server_pb.CascadeToolConfig
import exa.language_server_pb.CascadeUserInteraction
import exa.language_server_pb.ConversationHistoryConfig
import exa.language_server_pb.CortexTrajectorySource
import exa.language_server_pb.CustomAgentSpec
import exa.language_server_pb.DefaultAgentConfig
import exa.language_server_pb.HandleCascadeUserInteractionRequest
import exa.language_server_pb.Media
import exa.language_server_pb.MessageDeliveryStrategy
import exa.language_server_pb.Model
import exa.language_server_pb.ModelOrAlias
import exa.language_server_pb.NotifyUserConfig
import exa.language_server_pb.PermissionInteraction
import exa.language_server_pb.PermissionScope
import exa.language_server_pb.ProjectEnvironmentConfig
import exa.language_server_pb.ResolveOutstandingStepsRequest
import exa.language_server_pb.RunCommandToolConfig
import exa.language_server_pb.SendUserCascadeMessageRequest
import exa.language_server_pb.StartCascadeRequest
import exa.language_server_pb.TextOrScopeItem
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

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

    private fun toModelProto(name: String?): Model? {
        if (name.isNullOrBlank()) return null
        return try {
            Model.valueOf(name)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Starts a new conversation session on the daemon via typed AgyLanguageService.
     */
    suspend fun startCascade(
        cascadeId: String = UUID.randomUUID().toString(),
        modelEnum: String = "",
        workspaceUri: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val cid = cascadeId
        val resolvedModelStr = resolveModelEnum(modelEnum)
        val modelProto = toModelProto(resolvedModelStr)
        val normalizedUri = if (workspaceUri.isNotBlank()) {
            if (workspaceUri.startsWith("file://")) workspaceUri else "file://$workspaceUri"
        } else ""

        val req = StartCascadeRequest(
            cascade_id = cid,
            source = CortexTrajectorySource.CORTEX_TRAJECTORY_SOURCE_CASCADE_CLIENT,
            requested_model = modelProto ?: Model.MODEL_UNSPECIFIED,
            workspace_uris = if (normalizedUri.isNotBlank()) listOf(normalizedUri) else emptyList(),
            override_workspace_uris = if (normalizedUri.isNotBlank()) listOf(normalizedUri) else emptyList(),
            project_env_config = if (normalizedUri.isBlank()) ProjectEnvironmentConfig(project_id = "outside-of-project") else null
        )

        AgyLanguageService.StartCascade().executeSafely(req).map { cid }
    }

    /**
     * Constructs and sends the full prompt message payload via typed AgyLanguageService.
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
        val resolvedModelStr = resolveModelEnum(modelEnum)
        val modelProto = toModelProto(resolvedModelStr)
        val promptText = if (text.isNotBlank()) text else if (media.isNotEmpty()) (media.firstOrNull()?.description ?: "Voice note") else ""

        val autoPolicyVal = when (autoExecutionPolicy) {
            "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER" -> CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_EAGER
            "CASCADE_COMMANDS_AUTO_EXECUTION_AUTO" -> CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_AUTO
            "CASCADE_COMMANDS_AUTO_EXECUTION_OFF" -> CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_OFF
            "CASCADE_COMMANDS_AUTO_EXECUTION_PROCEED_IN_SANDBOX" -> CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_PROCEED_IN_SANDBOX
            else -> CascadeCommandsAutoExecution.CASCADE_COMMANDS_AUTO_EXECUTION_EAGER
        }

        val mediaItems = media.map { m ->
            val isImageOrAudio = m.mimeType.startsWith("image/") || m.mimeType.startsWith("audio/")
            val rawBytes = if (isImageOrAudio && m.base64.isNotBlank()) {
                m.base64.decodeBase64() ?: ByteString.EMPTY
            } else ByteString.EMPTY

            Media(
                mime_type = m.mimeType,
                inline_data = rawBytes,
                duration_seconds = if (m.durationSeconds > 0) m.durationSeconds.toFloat() else 0f,
                description = m.description,
                uri = m.uri ?: ""
            )
        }

        val req = SendUserCascadeMessageRequest(
            cascade_id = cascadeId,
            items = listOf(TextOrScopeItem(text = promptText)),
            media = mediaItems,
            cascade_config = CascadeConfig(
                planner_config = CascadePlannerConfig(
                    tool_config = CascadeToolConfig(
                        run_command = RunCommandToolConfig(
                            auto_command_config = AutoCommandConfig(
                                auto_execution_policy = autoPolicyVal
                            )
                        ),
                        notify_user = NotifyUserConfig()
                    ),
                    requested_model = if (modelProto != null) ModelOrAlias(model = modelProto) else null,
                    use_ai_credits = false,
                    supports_latex_rendering = true
                ),
                executor_config = CascadeExecutorConfig(use_core_direct = true),
                conversation_history_config = ConversationHistoryConfig()
            ),
            custom_agent_spec = CustomAgentSpec(
                builtin_agent = BuiltinAgentConfig(
                    default_agent = DefaultAgentConfig(
                        is_google = false,
                        is_interactive = true
                    )
                )
            ),
            delivery_strategy = MessageDeliveryStrategy.MESSAGE_DELIVERY_STRATEGY_WHEN_IDLE
        )

        AgyLanguageService.SendUserCascadeMessage().executeSafely(req).map { }
    }

    /**
     * Direct typed passthrough for SendUserCascadeMessage
     */
    suspend fun sendUserCascadeMessage(
        request: SendUserCascadeMessageRequest,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.SendUserCascadeMessage().executeSafely(request).map { }
    }

    /**
     * Streams real-time updates for an active conversation via StreamAgentStateUpdates using typed AgyLanguageService.
     */
    fun streamAgentStateUpdates(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<exa.language_server_pb.StreamAgentStateUpdatesResponse> {
        val req = exa.language_server_pb.StreamAgentStateUpdatesRequest(
            conversation_id = cascadeId,
            subscriber_id = UUID.randomUUID().toString(),
            trajectory_verbosity = exa.language_server_pb.ClientTrajectoryVerbosity.CLIENT_TRAJECTORY_VERBOSITY_VAL_CLIENT_TRAJECTORY_VERBOSITY_PROD_UI
        )
        return AgyLanguageService.StreamAgentStateUpdates().asFlowSafely(req)
    }


    /**
     * Cancels / aborts running generation or commands via typed AgyLanguageService
     */
    suspend fun cancelCascadeInvocation(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.CancelCascadeInvocation().executeSafely(
            CancelCascadeInvocationRequest(
                cascade_id = cascadeId,
                kill_background_tasks = true
            )
        ).map { }
    }

    /**
     * Selectively cancels specific step indices without aborting the entire cascade session.
     */
    suspend fun cancelCascadeSteps(
        cascadeId: String,
        stepIndices: List<Int>,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.CancelCascadeSteps().executeSafely(
            CancelCascadeStepsRequest(
                cascade_id = cascadeId,
                step_indices = stepIndices
            )
        ).map { }
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val permScope = when (scope.uppercase()) {
            "PERMISSION_SCOPE_CONVERSATION", "CONVERSATION" -> PermissionScope.PERMISSION_SCOPE_CONVERSATION
            "PERMISSION_SCOPE_WORKSPACE", "WORKSPACE", "PERMISSION_SCOPE_PROJECT", "PROJECT" -> PermissionScope.PERMISSION_SCOPE_PROJECT
            "PERMISSION_SCOPE_GLOBAL", "GLOBAL", "PERMISSION_SCOPE_PERMANENT" -> PermissionScope.PERMISSION_SCOPE_GLOBAL
            else -> PermissionScope.PERMISSION_SCOPE_ONCE
        }

        val interaction = when (interactionType.lowercase()) {
            "mcp" -> CascadeUserInteraction(
                trajectory_id = trajectoryId,
                step_index = stepIndex,
                mcp = CascadeMcpInteraction(confirm = allow)
            )
            "approvalinteraction", "approval_interaction" -> CascadeUserInteraction(
                trajectory_id = trajectoryId,
                step_index = stepIndex,
                approval_interaction = ApprovalInteraction(confirm = allow)
            )
            "readurlcontent", "read_url_content" -> CascadeUserInteraction(
                trajectory_id = trajectoryId,
                step_index = stepIndex,
                read_url_content = CascadeReadUrlContentInteraction(confirm = allow)
            )
            "browseraction", "browser_action" -> CascadeUserInteraction(
                trajectory_id = trajectoryId,
                step_index = stepIndex,
                browser_action = CascadeBrowserActionInteraction(confirm = allow)
            )
            else -> CascadeUserInteraction(
                trajectory_id = trajectoryId,
                step_index = stepIndex,
                permission = PermissionInteraction(
                    allow = allow,
                    scope = if (allow) permScope else PermissionScope.PERMISSION_SCOPE_ONCE,
                    user_deny_instruction = if (!allow) userDenyInstruction.ifBlank { "User rejected this command." } else ""
                )
            )
        }

        val req = HandleCascadeUserInteractionRequest(
            cascade_id = cascadeId,
            interaction = interaction
        )

        val result = AgyLanguageService.HandleCascadeUserInteraction().executeSafely(req).map { }
        if (result.isSuccess) {
            Log.d(TAG, "handleCascadeUserInteraction succeeded via typed AgyLanguageService (type=$interactionType)")
            return@withContext result
        }

        // Recovery Strategy: ResolveOutstandingSteps if allow is true
        if (allow) {
            val resolveRes = resolveOutstandingSteps(cascadeId, hubUrl)
            if (resolveRes.isSuccess) return@withContext resolveRes
        }

        result
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val entries = responses.map { item ->
            AskQuestionEntry(
                question = item.question,
                options = item.options.map { opt ->
                    AskQuestionOption(
                        id = opt.id,
                        text = opt.text.ifBlank { opt.label }
                    )
                },
                is_multi_select = item.isMultiSelect ?: false,
                selected_option_ids = item.selectedOptionIds ?: emptyList(),
                write_in_response = item.writeInResponse ?: "",
                skipped = item.skipped ?: false
            )
        }

        val allSkipped = responses.isNotEmpty() && responses.all { it.skipped == true }
        val interaction = CascadeUserInteraction(
            trajectory_id = trajectoryId,
            step_index = stepIndex,
            ask_question = AskQuestionInteraction(
                responses = entries,
                cancelled = allSkipped
            )
        )

        val req = HandleCascadeUserInteractionRequest(
            cascade_id = cascadeId,
            interaction = interaction
        )

        AgyLanguageService.HandleCascadeUserInteraction().executeSafely(req).map { }
    }

    /**
     * Resolves all outstanding or blocking steps in a cascade via typed AgyLanguageService
     */
    suspend fun resolveOutstandingSteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.ResolveOutstandingSteps().executeSafely(
            ResolveOutstandingStepsRequest(cascade_id = cascadeId)
        ).map { }
    }
}

