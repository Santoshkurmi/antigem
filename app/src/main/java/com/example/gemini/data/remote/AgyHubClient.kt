package com.example.gemini.data.remote

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyCsrfManager
import com.example.gemini.data.remote.dto.AgyStreamFrameDto
import com.example.gemini.data.remote.services.*
import com.example.gemini.domain.chat.TrajectoryParser
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.McpServerState
import com.example.gemini.domain.model.QuotaSummaryResponse
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Unified high-performance native facade for Antigravity (AGY) Hub.
 * Maintains full backward compatibility with existing ViewModels and UI components while
 * delegating transport, framing, parsing, and domain RPCs to modular services.
 */
class AgyHubClient {
    companion object {
        const val TAG = "AgyHubClient"

        val agyJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun normalizeToolName(name: String): String =
            TrajectoryParser.normalizeToolName(name)

        fun updateModelRegistry(models: List<AiModel>) =
            AgyModelService.updateModelRegistry(models)

        fun resolveModelEnum(rawInput: String?): String =
            AgyModelService.resolveModelEnum(rawInput)

        fun formatQuotaResetCountdown(isoString: String?): String =
            AgyModelService.formatQuotaResetCountdown(isoString)
    }

    // ==================== CORE DELEGATES ====================

    private val csrfManager = AgyCsrfManager.instance
    private val authService = AgyAuthService.instance
    private val conversationService = AgyConversationService.instance
    private val chatService = AgyChatService.instance
    private val modelService = AgyModelService.instance
    private val projectService = AgyProjectService.instance
    private val mcpService = AgyMcpService.instance
    private val audioService = AgyAudioService.instance
    private val pluginService = AgyPluginService.instance

    val csrfEvents: SharedFlow<String>
        get() = csrfManager.csrfEvents

    suspend fun getOrFetchCsrfToken(
        hubUrl: String = AuthPreferences.currentHubUrl,
        forceRefresh: Boolean = false
    ): String = csrfManager.getCsrfToken(hubUrl, forceRefresh)

    fun clearCsrfToken() {
        csrfManager.clearToken()
    }

    // ==================== AUTHENTICATION & USER PROFILE ====================

    enum class AgyAuthStatus {
        CHECKING,
        AUTHENTICATED,
        UNAUTHENTICATED,
        OFFLINE
    }

    data class AgyAuthInfo(
        val status: AgyAuthStatus = AgyAuthStatus.CHECKING,
        val isLoggedIn: Boolean = false,
        val fullName: String = "",
        val displayName: String = fullName,
        val email: String = "",
        val username: String = "",
        val homeDir: String = "",
        val userTier: String = "",
        val userTierId: String = "",
        val userTierDescription: String = "",
        val planName: String = "",
        val teamsTier: String = "",
        val availablePromptCredits: Long? = null,
        val availableFlowCredits: Long? = null,
        val monthlyPromptCredits: Long? = null,
        val monthlyFlowCredits: Long? = null,
        val upgradeSubscriptionUri: String = "",
        val upgradeSubscriptionText: String = "",
        val profilePictureUrl: String? = null,
        val grantedScopes: List<String> = emptyList(),
        val isOffline: Boolean = false
    )

    suspend fun login(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> =
        authService.login(hubUrl)

    suspend fun authLogout(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> =
        authService.authLogout(hubUrl)

    suspend fun getAuthStatus(hubUrl: String = AuthPreferences.currentHubUrl): Result<Boolean> =
        authService.getAuthStatus(hubUrl)

    suspend fun getLocalUserInfo(hubUrl: String = AuthPreferences.currentHubUrl): Result<Pair<String, String>> =
        authService.getLocalUserInfo(hubUrl)

    suspend fun fetchDetailedAuthInfo(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<AgyAuthInfo> =
        authService.fetchDetailedAuthInfo(hubUrl)

    // ==================== CONVERSATION MANAGEMENT ====================

    data class SummariesUpdate(
        val updated: List<Conversation>,
        val removedIds: Set<String>
    )

    fun subscribeToSummaries(hubUrl: String = AuthPreferences.currentHubUrl): Flow<SummariesUpdate> =
        conversationService.subscribeToSummaries(hubUrl)

    suspend fun getRawStepCount(cascadeId: String, hubUrl: String = AuthPreferences.currentHubUrl): Int =
        conversationService.getRawStepCount(cascadeId, hubUrl)

    suspend fun forkConversation(
        sourceCascadeId: String,
        forkAtStepIndex: Int? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        conversationService.forkConversation(sourceCascadeId, forkAtStepIndex, hubUrl)

    suspend fun deleteCascadeTrajectory(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Boolean> =
        conversationService.deleteCascadeTrajectory(cascadeId, hubUrl)

    suspend fun getCascadeTrajectorySteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<exa.language_server_pb.Step>> =
        conversationService.getCascadeTrajectorySteps(cascadeId, hubUrl)

    suspend fun revertUserMessage(
        cascadeId: String,
        modelEnum: String = "",
        targetStepIndex: Int? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Int> =
        conversationService.revertUserMessage(cascadeId, modelEnum, targetStepIndex, hubUrl)

    suspend fun revertLastUserMessage(
        cascadeId: String,
        modelEnum: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Int> =
        conversationService.revertLastUserMessage(cascadeId, modelEnum, hubUrl)

    // ==================== CHAT & EXECUTION ====================

    data class AgyMediaItem(
        val mimeType: String,
        val base64: String,
        val durationSeconds: Int = 0,
        val description: String = "Voice note",
        val uri: String? = null,
        val thumbnail: String? = null
    )

    suspend fun startCascade(
        cascadeId: String = UUID.randomUUID().toString(),
        modelEnum: String = "",
        workspaceUri: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        chatService.startCascade(cascadeId, modelEnum, workspaceUri, hubUrl)

    suspend fun sendUserPrompt(
        cascadeId: String,
        text: String,
        modelEnum: String = "",
        thinkingBudget: Int = 8192,
        autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
        media: List<AgyMediaItem> = emptyList(),
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.sendUserPrompt(cascadeId, text, modelEnum, thinkingBudget, autoExecutionPolicy, media, hubUrl)


    fun streamAgentStateUpdates(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<exa.language_server_pb.StreamAgentStateUpdatesResponse> =
        chatService.streamAgentStateUpdates(cascadeId, hubUrl)


    suspend fun cancelCascadeInvocation(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.cancelCascadeInvocation(cascadeId, hubUrl)

    suspend fun cancelCascadeSteps(
        cascadeId: String,
        stepIndices: List<Int>,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.cancelCascadeSteps(cascadeId, stepIndices, hubUrl)

    suspend fun handleCascadeUserInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        allow: Boolean = true,
        scope: String = "PERMISSION_SCOPE_ONCE",
        userDenyInstruction: String = "",
        interactionType: String = "permission",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.handleCascadeUserInteraction(cascadeId, stepIndex, trajectoryId, allow, scope, userDenyInstruction, interactionType, hubUrl)

    suspend fun handleAskQuestionInteraction(
        cascadeId: String,
        stepIndex: Int,
        trajectoryId: String = "",
        responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.handleAskQuestionInteraction(cascadeId, stepIndex, trajectoryId, responses, hubUrl)

    suspend fun resolveOutstandingSteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        chatService.resolveOutstandingSteps(cascadeId, hubUrl)

    // ==================== MODELS & QUOTA ====================

    suspend fun getAvailableModels(
        forceRefresh: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<AiModel>> =
        modelService.getAvailableModels(forceRefresh, hubUrl)

    suspend fun retrieveUserQuotaSummary(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<QuotaSummaryResponse> =
        modelService.retrieveUserQuotaSummary(hubUrl)

    // ==================== SETTINGS & PROJECTS ====================

    data class GlobalPermissionGrants(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList(),
        val ask: List<String> = emptyList()
    )

    data class GlobalUserSettings(
        val autoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_OFF",
        val nonWorkspaceFileAccessPolicy: String = "AGENT_SETTING_POLICY_ASK",
        val artifactReviewMode: String = "ARTIFACT_REVIEW_MODE_ALWAYS",
        val enableTerminalSandbox: Boolean = false,
        val globalPermissionGrants: GlobalPermissionGrants = GlobalPermissionGrants()
    )

    data class ProjectItem(
        val id: String,
        val name: String,
        val autoExecutionPolicy: String? = null,
        val fileAccessPolicy: String? = null,
        val artifactReviewMode: String? = null,
        val sandboxMode: Boolean? = null,
        val isInheritingGlobal: Boolean = true
    )

    suspend fun readFileAsBase64(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        projectService.readFileAsBase64(uri, hubUrl)

    suspend fun saveMediaAsArtifact(
        mimeType: String,
        base64Data: String,
        description: String,
        thumbnailBase64: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        projectService.saveMediaAsArtifact(mimeType, base64Data, description, thumbnailBase64, hubUrl)

    suspend fun deleteMediaArtifact(
        uri: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        projectService.deleteMediaArtifact(uri, hubUrl)

    suspend fun fetchGlobalUserSettings(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<GlobalUserSettings> =
        projectService.fetchGlobalUserSettings(hubUrl)

    suspend fun writeGlobalUserSettings(
        autoExecutionPolicy: String? = null,
        nonWorkspaceFileAccessPolicy: String? = null,
        artifactReviewMode: String? = null,
        enableTerminalSandbox: Boolean? = null,
        globalPermissionGrants: GlobalPermissionGrants? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        projectService.writeGlobalUserSettings(autoExecutionPolicy, nonWorkspaceFileAccessPolicy, artifactReviewMode, enableTerminalSandbox, globalPermissionGrants, hubUrl)

    suspend fun fetchAllProjects(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<ProjectItem>> =
        projectService.fetchAllProjects(hubUrl)

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
    ): Result<Unit> =
        projectService.updateProjectSettings(projectId, projectName, folderUris, autoExecutionPolicy, fileAccessPolicy, artifactReviewMode, sandboxMode, inheritGlobal, hubUrl)

    suspend fun setUserSettings(
        autoExecutionPolicy: String,
        enableTerminalSandbox: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        writeGlobalUserSettings(
            autoExecutionPolicy = autoExecutionPolicy,
            enableTerminalSandbox = enableTerminalSandbox,
            hubUrl = hubUrl
        )

    // ==================== DOMAIN PARSERS ====================

    fun findLastUserStepIndex(steps: JSONArray): Int =
        TrajectoryParser.findLastUserStepIndex(steps)

    fun parseStepsToChatMessages(stepsJson: String, conversationId: String): List<ChatMessage> =
        TrajectoryParser.parseStepsToChatMessages(stepsJson, conversationId)

    fun parseStepsArrayToChatMessages(
        steps: JSONArray,
        conversationId: String,
        cascadeStatus: String = ""
    ): List<ChatMessage> =
        TrajectoryParser.parseStepsArrayToChatMessages(steps, conversationId, cascadeStatus)

    fun extractToolCallFromStep(
        step: JSONObject,
        stepIndex: Int,
        conversationId: String,
        cascadeStatus: String = ""
    ): ToolCall? =
        TrajectoryParser.extractToolCallFromStep(step, stepIndex, conversationId, cascadeStatus)

    fun extractStepError(step: JSONObject): String? =
        TrajectoryParser.extractStepError(step)

    // ==================== MCP MANAGEMENT ====================

    suspend fun getMcpServerStates(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<McpServerState>> =
        mcpService.getMcpServerStates(hubUrl)

    suspend fun refreshMcpServers(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        mcpService.refreshMcpServers(hubUrl)

    suspend fun toggleMcpServer(
        serverName: String,
        enabled: Boolean,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        mcpService.toggleMcpServer(serverName, enabled, hubUrl)

    // ==================== AUDIO & TRANSCRIPTION ====================

    suspend fun getTranscription(
        audioBase64: String,
        prompt: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        audioService.getTranscription(audioBase64, prompt, hubUrl)

    fun streamAudioTranscription(
        cascadeId: String = "",
        preCursorText: String = "",
        postCursorText: String = "",
        mimeType: String = "audio/pcm;rate=16000",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<exa.language_server_pb.StreamAudioTranscriptionResponse> =
        audioService.streamAudioTranscription(cascadeId, preCursorText, postCursorText, mimeType, hubUrl)

    suspend fun sendAudioChunk(
        sessionId: String,
        dataBase64: String,
        sequenceNumber: Long,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        audioService.sendAudioChunk(sessionId, dataBase64, sequenceNumber, hubUrl)

    suspend fun endAudioSession(
        sessionId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        audioService.endAudioSession(sessionId, hubUrl)

    // ==================== PLUGINS & SKILLS & MARKETPLACE ====================

    suspend fun getAvailableCascadePlugins(
        os: String = "linux",
        searchQuery: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto>> =
        pluginService.getAvailableCascadePlugins(os, searchQuery, hubUrl)

    suspend fun installCascadePlugin(
        plugin: com.example.gemini.data.remote.dto.AvailableCascadePluginDto,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> =
        pluginService.installCascadePlugin(plugin, hubUrl)

    suspend fun getBuildWithGooglePlugins(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto>> =
        pluginService.getBuildWithGooglePlugins(hubUrl)

    suspend fun downloadBuildWithGooglePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        pluginService.downloadBuildWithGooglePlugin(pluginId, hubUrl)

    suspend fun deletePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> =
        pluginService.deletePlugin(pluginId, hubUrl)

    suspend fun getAllPlugins(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<com.example.gemini.data.remote.dto.InstalledPluginDto>> =
        pluginService.getAllPlugins(hubUrl)

    suspend fun getAllSkills(
        workspaceUris: List<String> = emptyList(),
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<com.example.gemini.data.remote.dto.SkillDefinitionDto>> =
        pluginService.getAllSkills(workspaceUris, hubUrl)
}
