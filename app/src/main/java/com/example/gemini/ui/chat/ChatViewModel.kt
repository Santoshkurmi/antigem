package com.example.gemini.ui.chat

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID

data class PendingToolApproval(
    val toolCall: com.example.gemini.domain.model.ToolCall,
    val messageId: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val apiService = AntigravityApiService()
    private val agyBridgeService = com.example.gemini.data.remote.AgyBridgeService()
    private val agyHubClient = com.example.gemini.data.remote.AgyHubClient()
    val trajectoryEngine = com.example.gemini.domain.chat.TrajectoryEngine()
    val speechManager = com.example.gemini.data.audio.AgyAudioTranscriptionManager(agyHubClient) {
        authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
    }
    private val oauthManager = GoogleOAuthManager()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _messages.map { msgs ->
        msgs.filter { it.role == com.example.gemini.domain.model.MessageRole.ASSISTANT }
            .flatMap { msg ->
                msg.toolCalls.filter { it.status == "PENDING_APPROVAL" }
                    .distinctBy { it.stepIndex }
                    .map { PendingToolApproval(it, msg.id) }
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(AiModel.DEFAULT_MODELS)
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _enabledModelIds = MutableStateFlow<Set<String>?>(null)
    val enabledModelIds: StateFlow<Set<String>?> = _enabledModelIds.asStateFlow()

    private val _enabledModels = MutableStateFlow<List<AiModel>>(AiModel.DEFAULT_MODELS)
    val enabledModels: StateFlow<List<AiModel>> = _enabledModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow("")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    val preferredModelName: StateFlow<String> = authPrefs.preferredModelName
        .map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _quotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    val quotas: StateFlow<List<ModelQuota>> = _quotas.asStateFlow()

    private val _quotaSummary = MutableStateFlow<com.example.gemini.domain.model.QuotaSummaryResponse?>(null)
    val quotaSummary: StateFlow<com.example.gemini.domain.model.QuotaSummaryResponse?> = _quotaSummary.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val isOAuthServerListening: StateFlow<Boolean> = oauthManager.isServerListening.asStateFlow()
    private val _isOAuthServerLoading = MutableStateFlow(false)
    val isOAuthServerLoading: StateFlow<Boolean> = _isOAuthServerLoading.asStateFlow()

    private val _projectId = MutableStateFlow("rising-fact-p41fc")
    val projectId: StateFlow<String> = _projectId.asStateFlow()

    private val _tier = MutableStateFlow("pro")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private val _contextWindowLimit = MutableStateFlow(10)
    val contextWindowLimit: StateFlow<Int> = _contextWindowLimit.asStateFlow()

    private val _summaryModelIdPref = MutableStateFlow("always_ask")
    val summaryModelIdPref: StateFlow<String> = _summaryModelIdPref.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summarizingModelName = MutableStateFlow("")
    val summarizingModelName: StateFlow<String> = _summarizingModelName.asStateFlow()

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError.asStateFlow()

    private val _pendingQueuedUserMessage = MutableStateFlow<String?>(null)
    val pendingQueuedUserMessage: StateFlow<String?> = _pendingQueuedUserMessage.asStateFlow()

    private val _postponedThreshold = MutableStateFlow<Int?>(null)
    val postponedThreshold: StateFlow<Int?> = _postponedThreshold.asStateFlow()

    private val _attachments = MutableStateFlow<List<com.example.gemini.domain.model.ChatAttachment>>(emptyList())
    val attachments: StateFlow<List<com.example.gemini.domain.model.ChatAttachment>> = _attachments.asStateFlow()

    private val _isUploadingAttachment = MutableStateFlow(false)
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<com.example.gemini.domain.model.McpServerState>>(emptyList())
    val mcpServers: StateFlow<List<com.example.gemini.domain.model.McpServerState>> = _mcpServers.asStateFlow()

    private val _isMcpLoading = MutableStateFlow(false)
    val isMcpLoading: StateFlow<Boolean> = _isMcpLoading.asStateFlow()

    private val _isMcpRefreshing = MutableStateFlow(false)
    val isMcpRefreshing: StateFlow<Boolean> = _isMcpRefreshing.asStateFlow()

    private val _refreshingMcpServer = MutableStateFlow<String?>(null)
    val refreshingMcpServer: StateFlow<String?> = _refreshingMcpServer.asStateFlow()

    private val _mcpErrorMessage = MutableStateFlow<String?>(null)
    val mcpErrorMessage: StateFlow<String?> = _mcpErrorMessage.asStateFlow()

    private val _mcpStatusMessage = MutableStateFlow<String?>(null)
    val mcpStatusMessage: StateFlow<String?> = _mcpStatusMessage.asStateFlow()

    fun clearMcpStatus() {
        _mcpStatusMessage.value = null
        _mcpErrorMessage.value = null
    }

    fun addAttachmentsFromUris(uris: List<android.net.Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (uri in uris) {
                    processAndUploadUri(uri, context)
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        addAttachmentsFromUris(listOf(uri), context)
    }

    private suspend fun processAndUploadUri(uri: android.net.Uri, context: android.content.Context) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "attachment_${System.currentTimeMillis()}"
            var fileSize = 0L

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            val mimeType = contentResolver.getType(uri)
            val isImg = (mimeType != null && mimeType.startsWith("image/")) ||
                    fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) ||
                    fileName.endsWith(".webp", true) || fileName.endsWith(".jpeg", true) ||
                    fileName.endsWith(".gif", true) || fileName.endsWith(".bmp", true)

            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val currentProjPath = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path
                val res = agyBridgeService.uploadAttachment(
                    filename = fileName,
                    base64Data = base64,
                    projectPath = currentProjPath,
                    httpBaseUrl = httpUrl
                )
                if (res.isSuccess) {
                    val att = res.getOrThrow().let {
                        it.copy(
                            localUri = uri.toString(),
                            isImage = isImg || it.isImage,
                            mimeType = mimeType ?: it.mimeType,
                            size = if (it.size > 0) it.size else fileSize
                        )
                    }
                    _attachments.value = _attachments.value + att
                } else {
                    val fallback = com.example.gemini.domain.model.ChatAttachment(
                        name = fileName,
                        path = uri.toString(),
                        isImage = isImg,
                        localUri = uri.toString(),
                        size = fileSize,
                        mimeType = mimeType
                    )
                    _attachments.value = _attachments.value + fallback
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to add attachment from uri $uri: ${e.message}")
        }
    }

    fun addProjectFileAttachment(filePath: String, fileName: String) {
        val isImg = fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".webp", true) || fileName.endsWith(".jpeg", true)
        val att = com.example.gemini.domain.model.ChatAttachment(
            name = fileName,
            path = filePath,
            isImage = isImg,
            size = java.io.File(filePath).length()
        )
        _attachments.value = _attachments.value + att
    }

    fun removeAttachment(attachmentId: String) {
        _attachments.value = _attachments.value.filter { it.id != attachmentId }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(true)
    val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _terminatedToolDialog = MutableStateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?>(null)
    val terminatedToolDialog: StateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?> = _terminatedToolDialog.asStateFlow()

    fun dismissTerminatedToolDialog() {
        _terminatedToolDialog.value = null
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setDevModeEnabled(enabled)
        }
    }

    fun updateCustomSystemPrompt(prompt: String?) {
        val conv = _currentConversation.value ?: return
        val updated = conv.copy(customSystemPrompt = if (prompt.isNullOrBlank()) null else prompt.trim())
        _currentConversation.value = updated
        _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
    }

    val chatFontScale = authPrefs.chatFontScale
    val themeMode: StateFlow<String> = authPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, authPreferences.getThemeModeSync())
    val agyHubUrl = authPrefs.agyHubUrl
    val agyBridgeHttpUrl = authPrefs.agyBridgeHttpUrl
    val terminalFontSize = authPrefs.terminalFontSize
    val terminalCursorStyle = authPrefs.terminalCursorStyle
    val terminalBufferSize = authPrefs.terminalBufferSize
    val terminalTheme = authPrefs.terminalTheme

    val useSshTerminal = authPrefs.useSshTerminal
    val termuxSshHost = authPrefs.termuxSshHost
    val termuxSshPort = authPrefs.termuxSshPort
    val termuxSshUser = authPrefs.termuxSshUser
    val termuxSshPass = authPrefs.termuxSshPass
    val isLocalToolsEnabled = authPrefs.isLocalToolsEnabled
    val isLocalToolsInstalled = authPrefs.isLocalToolsInstalled

    val commandAutoExecutionPolicy = authPrefs.commandAutoExecutionPolicy
    val commandSandboxEnabled = authPrefs.commandSandboxEnabled
    val requireApprovalForFileEdits = authPrefs.requireApprovalForFileEdits
    val defaultApprovalScope = authPrefs.defaultApprovalScope
    val groupChatsByWorkspace: StateFlow<Boolean> = authPrefs.groupChatsByWorkspace
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGroupChatsByWorkspace(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveGroupChatsByWorkspace(enabled)
        }
    }

    private val _globalSecuritySettings = MutableStateFlow<AgyHubClient.GlobalUserSettings?>(null)
    val globalSecuritySettings: StateFlow<AgyHubClient.GlobalUserSettings?> = _globalSecuritySettings.asStateFlow()

    private val _globalSettingsError = MutableStateFlow<String?>(null)
    val globalSettingsError: StateFlow<String?> = _globalSettingsError.asStateFlow()

    private val _isGlobalSettingsLoading = MutableStateFlow(false)
    val isGlobalSettingsLoading: StateFlow<Boolean> = _isGlobalSettingsLoading.asStateFlow()

    private val _projectsList = MutableStateFlow<List<AgyHubClient.ProjectItem>>(emptyList())
    val projectsList: StateFlow<List<AgyHubClient.ProjectItem>> = _projectsList.asStateFlow()

    private val _isProjectsLoading = MutableStateFlow(false)
    val isProjectsLoading: StateFlow<Boolean> = _isProjectsLoading.asStateFlow()

    fun loadSecurityAndProjectSettings() {
        viewModelScope.launch {
            _isGlobalSettingsLoading.value = true
            _isProjectsLoading.value = true
            _globalSettingsError.value = null
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL

            val globalRes = agyHubClient.fetchGlobalUserSettings(hubUrl)
            if (globalRes.isSuccess) {
                _globalSecuritySettings.value = globalRes.getOrNull()
                _globalSettingsError.value = null
            } else {
                val err = globalRes.exceptionOrNull()?.message ?: "Failed to connect to AGY Hub"
                _globalSettingsError.value = err
                _globalSecuritySettings.value = null
            }
            _isGlobalSettingsLoading.value = false

            val projRes = agyHubClient.fetchAllProjects(hubUrl)
            if (projRes.isSuccess) {
                _projectsList.value = projRes.getOrNull() ?: emptyList()
            }
            _isProjectsLoading.value = false
        }
    }

    fun addGlobalPermissionGrant(action: String, pattern: String, decision: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            val curGrants = cur.globalPermissionGrants

            val cleanAction = action.trim().lowercase()
            val cleanPattern = pattern.trim()
            if (cleanPattern.isBlank()) return@launch
            val ruleStr = "${cleanAction}($cleanPattern)"

            val newAllow = curGrants.allow.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()
            val newDeny = curGrants.deny.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()
            val newAsk = curGrants.ask.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()

            when (decision.uppercase()) {
                "ALLOW" -> newAllow.add(ruleStr)
                "DENY" -> newDeny.add(ruleStr)
                "ASK" -> newAsk.add(ruleStr)
                else -> newAllow.add(ruleStr)
            }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to save permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun removeGlobalPermissionGrant(rawRule: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: return@launch
            val curGrants = cur.globalPermissionGrants

            val newAllow = curGrants.allow.filterNot { it.equals(rawRule, ignoreCase = true) }
            val newDeny = curGrants.deny.filterNot { it.equals(rawRule, ignoreCase = true) }
            val newAsk = curGrants.ask.filterNot { it.equals(rawRule, ignoreCase = true) }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to remove permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun changeGlobalPermissionGrantDecision(rawRule: String, newDecision: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: return@launch
            val curGrants = cur.globalPermissionGrants

            val newAllow = curGrants.allow.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()
            val newDeny = curGrants.deny.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()
            val newAsk = curGrants.ask.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()

            when (newDecision.uppercase()) {
                "ALLOW" -> newAllow.add(rawRule)
                "DENY" -> newDeny.add(rawRule)
                "ASK" -> newAsk.add(rawRule)
            }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to update permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun updateGlobalArtifactReviewMode(mode: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(artifactReviewMode = mode)
            agyHubClient.writeGlobalUserSettings(
                artifactReviewMode = mode,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalSecurityPreset(
        autoExec: String,
        fileAccess: String
    ) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(
                autoExecutionPolicy = autoExec,
                nonWorkspaceFileAccessPolicy = fileAccess
            )
            authPrefs.setCommandAutoExecutionPolicy(autoExec)
            agyHubClient.writeGlobalUserSettings(
                autoExecutionPolicy = autoExec,
                nonWorkspaceFileAccessPolicy = fileAccess,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalCustomTerminalPolicy(policy: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(autoExecutionPolicy = policy)
            authPrefs.setCommandAutoExecutionPolicy(policy)
            agyHubClient.writeGlobalUserSettings(
                autoExecutionPolicy = policy,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalCustomFileAccessPolicy(policy: String) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(nonWorkspaceFileAccessPolicy = policy)
            agyHubClient.writeGlobalUserSettings(
                nonWorkspaceFileAccessPolicy = policy,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalTerminalSandbox(enabled: Boolean) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(enableTerminalSandbox = enabled)
            authPrefs.setCommandSandboxEnabled(enabled)
            agyHubClient.writeGlobalUserSettings(
                enableTerminalSandbox = enabled,
                hubUrl = hubUrl
            )
        }
    }

    fun setProjectInheritGlobal(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val res = agyHubClient.updateProjectSettings(
                projectId = project.id,
                projectName = project.name,
                inheritGlobal = true,
                hubUrl = hubUrl
            )
            if (res.isSuccess) {
                val refreshed = agyHubClient.fetchAllProjects(hubUrl)
                if (refreshed.isSuccess) {
                    _projectsList.value = refreshed.getOrNull() ?: emptyList()
                }
            }
        }
    }

    fun updateProjectPreset(
        project: com.example.gemini.data.remote.AgyHubClient.ProjectItem,
        autoExec: String,
        fileAccess: String,
        artifactReview: String? = null
    ) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val res = agyHubClient.updateProjectSettings(
                projectId = project.id,
                projectName = project.name,
                autoExecutionPolicy = autoExec,
                fileAccessPolicy = fileAccess,
                artifactReviewMode = artifactReview ?: project.artifactReviewMode ?: "ARTIFACT_REVIEW_MODE_ALWAYS",
                sandboxMode = project.sandboxMode ?: false,
                inheritGlobal = false,
                hubUrl = hubUrl
            )
            if (res.isSuccess) {
                val refreshed = agyHubClient.fetchAllProjects(hubUrl)
                if (refreshed.isSuccess) {
                    _projectsList.value = refreshed.getOrNull() ?: emptyList()
                }
            }
        }
    }

    fun setCommandAutoExecutionPolicy(policy: String) {
        updateGlobalCustomTerminalPolicy(policy)
    }

    fun setCommandSandboxEnabled(enabled: Boolean) {
        updateGlobalTerminalSandbox(enabled)
    }

    fun setRequireApprovalForFileEdits(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setRequireApprovalForFileEdits(enabled)
        }
    }

    fun setDefaultApprovalScope(scope: String) {
        viewModelScope.launch {
            authPrefs.setDefaultApprovalScope(scope)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            authPrefs.saveThemeMode(mode)
        }
    }

    fun saveServerUrls(hubUrl: String, bridgeUrl: String) {
        viewModelScope.launch {
            if (hubUrl.isNotBlank()) {
                authPrefs.saveAgyHubUrl(hubUrl.trim())
            }
            if (bridgeUrl.isNotBlank()) {
                authPrefs.saveAgyBridgeHttpUrl(bridgeUrl.trim())
            }
            retryConnections()
        }
    }

    fun saveTerminalPreferences(fontSize: Int, cursorStyle: String, bufferSize: Int, theme: String) {
        viewModelScope.launch {
            authPrefs.saveTerminalPreferences(fontSize, cursorStyle, bufferSize, theme)
        }
    }

    fun setUseSshTerminal(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setUseSshTerminal(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun saveSshSettings(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch {
            authPrefs.saveSshSettings(host, port, user, pass)
        }
    }

    fun setLocalToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setLocalToolsEnabled(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            authPrefs.saveChatFontScale(scale)
        }
    }

    fun setContextWindowLimit(limit: Int) {
        viewModelScope.launch {
            authPrefs.setContextWindowLimit(limit)
        }
    }

    fun setSummaryModelId(modelId: String) {
        viewModelScope.launch {
            authPrefs.setSummaryModelId(modelId)
        }
    }

    fun postponeSummarization(extraCount: Int) {
        val currentCount = _messages.value.size
        _postponedThreshold.value = currentCount + extraCount
    }

    fun updateSummaryMessage(messageId: String, newSummary: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.map {
            if (it.id == messageId) it.copy(content = newSummary) else it
        }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun deleteSummaryMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.filterNot { it.id == messageId }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _postponedThreshold.value = null
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun updateSummary(newSummary: String) {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            updateSummaryMessage(latestSummaryMsg.id, newSummary)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = if (newSummary.isBlank()) null else newSummary)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun deleteSummary() {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            deleteSummaryMessage(latestSummaryMsg.id)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = null)
            _currentConversation.value = updated
            _postponedThreshold.value = null
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun setThinkingPreference(pref: com.example.gemini.domain.model.ThinkingPreference) {
        _thinkingPreference.value = pref
    }

    fun setModelEnabled(modelId: String, isEnabled: Boolean) {
        val currentIds = _enabledModelIds.value?.toMutableSet() ?: _availableModels.value.map { it.id }.toMutableSet()
        if (isEnabled) {
            currentIds.add(modelId)
        } else {
            if (currentIds.size > 1) { // Don't allow disabling all models
                currentIds.remove(modelId)
            }
        }
        viewModelScope.launch {
            authPrefs.saveEnabledModelIds(currentIds)
        }
    }

    fun enableAllModels() {
        val allIds = _availableModels.value.map { it.id }.toSet()
        viewModelScope.launch {
            authPrefs.saveEnabledModelIds(allIds)
        }
    }

    private fun recomputeEnabledModels() {
        val all = _availableModels.value
        val enabledSet = _enabledModelIds.value
        val filtered = if (enabledSet.isNullOrEmpty()) {
            all
        } else {
            all.filter { it.id in enabledSet || it.key in enabledSet }
        }
        _enabledModels.value = if (filtered.isNotEmpty()) filtered else all

        // Only switch if user has an explicit filter AND current selection is not in the filtered enabled list
        val currentSelected = _selectedModelId.value
        if (!enabledSet.isNullOrEmpty() && currentSelected.isNotBlank()) {
            val isCurrentEnabled = _enabledModels.value.any { it.id == currentSelected || it.key == currentSelected }
            if (!isCurrentEnabled) {
                _enabledModels.value.firstOrNull()?.let {
                    selectModel(it.id)
                }
            }
        }
    }

    private val _bridgeStatusMessage = MutableStateFlow<String?>(null)
    val bridgeStatusMessage: StateFlow<String?> = _bridgeStatusMessage.asStateFlow()

    private val knownDaemonCascadeIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    private var isPromptInFlight = false
    @Volatile
    private var hasSeenTurnActivity = false
    @Volatile
    private var hasStartedRunning = false

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

    private val _isBridgeOnline = MutableStateFlow<Boolean?>(null)
    val isBridgeOnline: StateFlow<Boolean?> = _isBridgeOnline.asStateFlow()

    fun checkBridgeHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            val bridgeUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyBridgeService.DEFAULT_HTTP_URL
            val base = bridgeUrl.trimEnd('/')
            val endpoints = listOf("$base/api/health", "$base/health", base)
            var reachable = false
            for (ep in endpoints) {
                try {
                    val conn = (java.net.URL(ep).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) {
                        reachable = true
                        break
                    }
                } catch (_: Exception) {}
            }
            _isBridgeOnline.value = reachable
        }
    }

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error checking network connectivity: ${e.message}", e)
            false
        }
    }

    private val _agyAuthInfo = MutableStateFlow(com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo(status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.CHECKING))
    val agyAuthInfo: StateFlow<com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo> = _agyAuthInfo.asStateFlow()

    private val _isAuthBusy = MutableStateFlow(false)
    val isAuthBusy: StateFlow<Boolean> = _isAuthBusy.asStateFlow()

    private val _authFeedbackMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val authFeedbackMessage: SharedFlow<String> = _authFeedbackMessage.asSharedFlow()

    private val _pendingLoginUrl = MutableStateFlow<String?>(null)
    val pendingLoginUrl: StateFlow<String?> = _pendingLoginUrl.asStateFlow()

    fun clearPendingLoginUrl() {
        _pendingLoginUrl.value = null
    }

    private var loginPollJob: Job? = null

    fun checkAgyAuthStatus() {
        viewModelScope.launch {
            if (!isNetworkConnected()) {
                android.util.Log.d("ChatViewModel", "Skipping auth status check: device is offline.")
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
                    isOffline = true
                )
                return@launch
            }
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val bridgeUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            val res = agyHubClient.fetchDetailedAuthInfo(hubUrl, bridgeUrl)
            if (res.isSuccess) {
                val info = res.getOrThrow()
                _agyAuthInfo.value = info
                if (info.isLoggedIn) {
                    refreshQuotas(force = false)
                }
            } else {
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
                    isOffline = true
                )
            }
        }
    }

    fun loginToAgyHub() {
        if (_isAuthBusy.value) return
        if (!isNetworkConnected()) {
            _authFeedbackMessage.tryEmit("Cannot sign in: no internet connection.")
            return
        }
        _isAuthBusy.value = true
        loginPollJob?.cancel()

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val bridgeUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            _authFeedbackMessage.tryEmit("Initiating sign-in with Antigravity...")

            // 1. Poll for login URL and poll for successful auth completion concurrently every 800ms
            loginPollJob = launch {
                val startTime = System.currentTimeMillis()
                var urlFound = false
                while (isActive && System.currentTimeMillis() - startTime < 180_000) {
                    delay(800)

                    // Check if bridge detected a login URL
                    if (!urlFound) {
                        val detectedUrl = agyHubClient.fetchLoginUrl(bridgeUrl)
                        if (!detectedUrl.isNullOrBlank()) {
                            urlFound = true
                            _pendingLoginUrl.value = detectedUrl
                        }
                    }

                    if (isNetworkConnected()) {
                        val res = agyHubClient.fetchDetailedAuthInfo(hubUrl, bridgeUrl)
                        if (res.isSuccess && res.getOrThrow().isLoggedIn) {
                            _agyAuthInfo.value = res.getOrThrow()
                            _isAuthBusy.value = false
                            _pendingLoginUrl.value = null
                            _authFeedbackMessage.tryEmit("Signed in successfully!")
                            refreshQuotas(force = true)
                            break
                        }
                    }
                }
                _isAuthBusy.value = false
            }

            // 2. Kick off login on hub & bridge in parallel (Login RPC is blocking on daemon)
            launch {
                try {
                    agyHubClient.startBridgeLogin(bridgeUrl)
                } catch (e: Exception) {
                    android.util.Log.d("ChatViewModel", "Bridge start-login: ${e.message}")
                }
                try {
                    agyHubClient.login(hubUrl)
                } catch (e: Exception) {
                    android.util.Log.d("ChatViewModel", "Hub login RPC finished/interrupted: ${e.message}")
                }
            }
        }
    }

    fun logoutFromAgyHub() {
        if (_isAuthBusy.value) return
        _isAuthBusy.value = true
        loginPollJob?.cancel()

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            try {
                agyHubClient.authLogout(hubUrl)
                _agyAuthInfo.value = com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.UNAUTHENTICATED,
                    isLoggedIn = false
                )
                _authFeedbackMessage.tryEmit("Logged out successfully.")
                refreshQuotas(force = true)
            } catch (e: Exception) {
                _authFeedbackMessage.tryEmit("Logout error: ${e.message}")
            } finally {
                _isAuthBusy.value = false
            }
        }
    }

    val connectionState: StateFlow<com.example.gemini.data.remote.BridgeConnectionState> = combine(_isServerOnline, _isStreaming) { online, streaming ->
        when {
            online == false -> com.example.gemini.data.remote.BridgeConnectionState.OFFLINE_ERROR
            streaming -> com.example.gemini.data.remote.BridgeConnectionState.STREAMING
            online == true -> com.example.gemini.data.remote.BridgeConnectionState.CONNECTED_READY
            else -> com.example.gemini.data.remote.BridgeConnectionState.CONNECTING
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.example.gemini.data.remote.BridgeConnectionState.CONNECTED_READY)

    private val _conversationError = MutableStateFlow<String?>(null)
    val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _activeInstances = MutableStateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>>(emptyList())
    val activeInstances: StateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>> = _activeInstances.asStateFlow()

    private val _conversationDrafts = mutableMapOf<String, androidx.compose.ui.text.input.TextFieldValue>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingConversations = MutableStateFlow(false)
    val isSearchingConversations: StateFlow<Boolean> = _isSearchingConversations.asStateFlow()

    private var currentConvPage = 1
    private var hasMoreConversations = false

    private var lastPrewarmedConvId: String? = null
    private var lastPrewarmedModelId: String? = null
    private var lastPrewarmedProjectPath: String? = null

    private var streamingJob: Job? = null
    private var persistentStreamJob: Job? = null
    @Volatile private var activeStreamConversationId: String? = null
    @Volatile private var currentTurnStartStep: Int = 0
    @Volatile private var totalStepsCount: Int = 0
    @Volatile private var currentAssistantMsgId: String? = null
    @Volatile private var currentTrajectoryId: String = ""
    private var serverJob: Job? = null
    var pendingPkceVerifier: String? = null

    init {
        viewModelScope.launch {
            agyBridgeService.loginUrlEvents.collect { url ->
                if (url.isNotBlank()) {
                    _pendingLoginUrl.value = url
                }
            }
        }

        viewModelScope.launch {
            authPrefs.userEmail.collect { _userEmail.value = it }
        }

        viewModelScope.launch {
            val savedId = authPrefs.preferredModelId.firstOrNull()
            val savedKey = authPrefs.preferredModelKey.firstOrNull()
            val initial = savedId ?: savedKey ?: ""
            if (initial.isNotBlank() && _selectedModelId.value.isBlank()) {
                _selectedModelId.value = initial
            }
            startNewChat()
            syncAgyConversations()
        }

        viewModelScope.launch {
            authPrefs.enabledModelIds.collect { ids ->
                _enabledModelIds.value = ids
                recomputeEnabledModels()
            }
        }

        viewModelScope.launch {
            authPrefs.projectId.collect { it?.let { p -> _projectId.value = p } }
        }

        viewModelScope.launch {
            authPrefs.subscriptionTier.collect { it?.let { t -> _tier.value = t } }
        }

        viewModelScope.launch {
            authPrefs.contextWindowLimit.collect { _contextWindowLimit.value = it }
        }

        viewModelScope.launch {
            authPrefs.summaryModelId.collect { _summaryModelIdPref.value = it }
        }

        viewModelScope.launch {
            authPrefs.isDevModeEnabled.collect { _isDevModeEnabled.value = it }
        }

        // Keep active conversation title and summary live-synced when conversation list updates
        viewModelScope.launch {
            _conversations.collect { convList ->
                val activeId = _currentConversation.value?.id ?: return@collect
                val activeInList = convList.find { it.id == activeId } ?: return@collect
                val curr = _currentConversation.value ?: return@collect
                val newTitle = if (activeInList.title.isNotBlank() &&
                    activeInList.title != "Conversation" &&
                    activeInList.title != "New Chat") {
                    activeInList.title
                } else {
                    curr.title
                }
                if (newTitle != curr.title) {
                    _currentConversation.value = curr.copy(
                        title = newTitle,
                        summary = activeInList.summary ?: curr.summary
                    )
                }
            }
        }

        viewModelScope.launch {
            refreshQuotas()
            loadMcpServers()
        }

        // Periodic auto-reconnect monitor for Hub RPC streams & Bridge (every 20 seconds)
        viewModelScope.launch {
            checkBridgeHealth()
            checkAgyAuthStatus()
            while (currentCoroutineContext().isActive) {
                delay(20_000)
                checkBridgeHealth()
                checkAgyAuthStatus()
                if (_isServerOnline.value != true || syncJob?.isActive != true) {
                    android.util.Log.d("ChatViewModel", "Periodic check: reconnecting hub streams...")
                    syncAgyConversations(force = false)
                    val convId = _currentConversation.value?.id
                    if (!convId.isNullOrBlank() && persistentStreamJob?.isActive != true) {
                        startPersistentStream(convId)
                    }
                }
            }
        }
    }

    fun getDraft(conversationId: String): androidx.compose.ui.text.input.TextFieldValue {
        return _conversationDrafts[conversationId] ?: androidx.compose.ui.text.input.TextFieldValue("")
    }

    fun setDraft(conversationId: String, value: androidx.compose.ui.text.input.TextFieldValue) {
        if (value.text.isEmpty()) {
            _conversationDrafts.remove(conversationId)
        } else {
            _conversationDrafts[conversationId] = value
        }
    }

    fun clearDraft(conversationId: String) {
        _conversationDrafts.remove(conversationId)
    }

    fun refreshActiveInstances() {
        viewModelScope.launch {
            try {
                val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val res = agyBridgeService.fetchActiveInstances(httpUrl)
                if (res.isSuccess) {
                    _activeInstances.value = res.getOrThrow()
                }
            } catch (e: Exception) {
                android.util.Log.w("GeminiApp", "refreshActiveInstances failed: ${e.message}")
            }
        }
    }

    fun terminateInstance(conversationId: String) {
        viewModelScope.launch {
            val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            val ok = agyBridgeService.terminateInstance(conversationId, httpUrl)
            if (ok) {
                _activeInstances.value = _activeInstances.value.filter { it.conversationId != conversationId }
            }
        }
    }

    fun searchConversations(query: String) {
        _searchQuery.value = query
    }

    private var syncJob: Job? = null

    fun syncAgyConversations(force: Boolean = false) {
        if (!force && syncJob?.isActive == true) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _isLoadingConversation.value = true
            while (currentCoroutineContext().isActive) {
                try {
                    val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                    agyHubClient.subscribeToSummaries(hubUrl).collect { update ->
                        val currentMap = _conversations.value.associateBy { it.id }.toMutableMap()
                        val activeId = _currentConversation.value?.id

                        // Remove empty, deleted, or abandoned sessions
                        for (delId in update.removedIds) {
                            if (delId != activeId || _messages.value.isEmpty()) {
                                currentMap.remove(delId)
                                knownDaemonCascadeIds.remove(delId)
                            }
                        }

                        // Add or update valid conversations
                        for (conv in update.updated) {
                            currentMap[conv.id] = conv
                            knownDaemonCascadeIds.add(conv.id)
                        }

                        _conversations.value = currentMap.values.sortedByDescending { it.updatedAt }

                        // Automatically synchronize active conversation title and metadata from daemon
                        if (activeId != null) {
                            val activeInMap = currentMap[activeId]
                            val curr = _currentConversation.value
                            if (activeInMap != null && curr != null && curr.id == activeId) {
                                val newTitle = if (activeInMap.title.isNotBlank() &&
                                    activeInMap.title != "Conversation" &&
                                    activeInMap.title != "New Chat") {
                                    activeInMap.title
                                } else {
                                    curr.title
                                }
                                val newWorkspaceUri = curr.workspaceUri.ifBlank { activeInMap.workspaceUri }
                                if (newTitle != curr.title ||
                                    newWorkspaceUri != curr.workspaceUri ||
                                    curr.isRunning != activeInMap.isRunning ||
                                    (activeInMap.stepCount > curr.stepCount)) {
                                    _currentConversation.value = curr.copy(
                                        title = newTitle,
                                        summary = activeInMap.summary ?: curr.summary,
                                        workspaceUri = newWorkspaceUri,
                                        stepCount = if (activeInMap.stepCount > 0) activeInMap.stepCount else curr.stepCount,
                                        isRunning = activeInMap.isRunning
                                    )
                                }
                            }
                        }
                        _isServerOnline.value = true
                        _conversationError.value = null
                        _isLoadingConversation.value = false
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        break
                    }
                    android.util.Log.e("ChatViewModel", "subscribeToSummaries failed: ${e.message}")
                    _isServerOnline.value = false
                    val rawErr = e.message ?: "Connection failed"
                    val helpfulMsg = if (rawErr.contains("Connect", ignoreCase = true) || rawErr.contains("8090") || rawErr.contains("Failed to connect", ignoreCase = true)) {
                        "Cannot connect to Antigravity Hub on port 8090. Make sure 'agy --hub' is running."
                    } else {
                        "Antigravity Hub unreachable: $rawErr"
                    }
                    if (_conversations.value.isEmpty()) {
                        _conversationError.value = helpfulMsg
                    }
                    _isLoadingConversation.value = false
                    delay(20_000) // Auto-retry conversation sync every 20 seconds while offline
                }
            }
        }
    }

    /**
     * Retries all connections: both sidebar conversation list and active chat stream
     */
    fun retryConnections() {
        _conversationError.value = null
        _isLoadingConversation.value = true
        syncAgyConversations(force = true)
        val convId = _currentConversation.value?.id
        if (!convId.isNullOrBlank()) {
            startPersistentStream(convId)
        }
        refreshQuotas()
        checkBridgeHealth()
        checkAgyAuthStatus()
        viewModelScope.launch {
            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = false)
        }
    }

    /**
     * Called when the app comes into focus / foreground.
     * Checks both RPC streams and reconnects if dropped.
     */
    fun onAppForegrounded() {
        android.util.Log.d("ChatViewModel", "App foregrounded: inspecting RPC streams...")
        viewModelScope.launch {
            val convId = _currentConversation.value?.id
            val isSyncActive = syncJob?.isActive == true
            val isStreamActive = persistentStreamJob?.isActive == true

            if (!isSyncActive || _isServerOnline.value != true) {
                syncAgyConversations(force = true)
            }
            if (!convId.isNullOrBlank() && (!isStreamActive || _isServerOnline.value != true)) {
                startPersistentStream(convId)
            }
            refreshQuotas()
            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
        }
    }

    fun onProjectChanged(projectPath: String) {
        _bridgeStatusMessage.value = null
        viewModelScope.launch {
            val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            val conv = _currentConversation.value
            val model = _selectedModelId.value.ifBlank { _availableModels.value.firstOrNull()?.id ?: "" }
            agyBridgeService.prewarm(
                conversationId = conv?.id,
                model = model,
                workspaceDir = projectPath,
                httpBaseUrl = httpUrl
            )
        }
    }

    fun onUserStartedTyping() {
        // No-op: agy --hub manages models dynamically without prewarm
    }

    private fun checkPrewarmCandidate(conv: Conversation) {
        // Official agy --hub manages models dynamically without prewarm
    }

    fun startNewChat() {
        persistentStreamJob?.cancel()
        persistentStreamJob = null
        activeStreamConversationId = null
        currentAssistantMsgId = null
        currentTurnStartStep = 0
        totalStepsCount = 0
        isPromptInFlight = false
        hasStartedRunning = false
        hasSeenTurnActivity = false
        _isStreaming.value = false
        _bridgeStatusMessage.value = null

        val modelToUse = if (_selectedModelId.value.isNotBlank()) {
            com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
        } else {
            (_enabledModels.value.firstOrNull()?.id ?: "")
        }
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelToUse,
            sessionId = UUID.randomUUID().toString()
        )
        _currentConversation.value = newConv
        trajectoryEngine.reset(newConv.id, force = true)
        com.example.gemini.ui.components.ToolCallExpansionCache.setChat(newConv.id)
        com.example.gemini.ui.components.CodeBlockExpansionCache.setChat(newConv.id)
        _messages.value = emptyList()
        _isLoadingConversation.value = false
        if (_isServerOnline.value == true) {
            _conversationError.value = null
        }
    }

    fun selectConversation(id: String) {
        if (id == activeStreamConversationId && !_isLoadingConversation.value && _messages.value.isNotEmpty()) return

        persistentStreamJob?.cancel()
        persistentStreamJob = null
        activeStreamConversationId = id
        currentAssistantMsgId = null
        currentTurnStartStep = 0
        totalStepsCount = 0
        isPromptInFlight = false
        hasStartedRunning = false
        hasSeenTurnActivity = false
        trajectoryEngine.reset(id, force = true)
        com.example.gemini.ui.components.ToolCallExpansionCache.setChat(id)
        com.example.gemini.ui.components.CodeBlockExpansionCache.setChat(id)
        currentTrajectoryId = ""
        _isStreaming.value = false
        _bridgeStatusMessage.value = null
        _messages.value = emptyList()

        viewModelScope.launch {
            _isLoadingConversation.value = true
            _conversationError.value = null

            val conv = _conversations.value.find { it.id == id }
                ?: Conversation(id = id, title = "Antigravity Chat", sessionId = id)
            _currentConversation.value = conv
            // Keep globally selected model intact across all chats
            knownDaemonCascadeIds.add(id)

            // Connect persistent stream for this conversation.
            // StreamAgentStateUpdates delivers the complete history in Chunk 0!
            startPersistentStream(id)
        }
    }

    fun startPersistentStream(conversationId: String) {
        if (conversationId.isBlank()) return
        if (activeStreamConversationId == conversationId && persistentStreamJob?.isActive == true) {
            return
        }

        persistentStreamJob?.cancel()
        activeStreamConversationId = conversationId
        currentTurnStartStep = 0
        totalStepsCount = 0

        persistentStreamJob = viewModelScope.launch(Dispatchers.IO) {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            com.example.gemini.data.remote.HubMediaResolver.activeHubUrl = hubUrl
            var isFirstChunk = true

            trajectoryEngine.reset(conversationId)

            while (activeStreamConversationId == conversationId) {
                try {
                    _isServerOnline.value = true
                    agyHubClient.streamAgentStateFrames(conversationId, hubUrl).collect { frame ->
                        if (activeStreamConversationId != conversationId) {
                            return@collect
                        }

                        isFirstChunk = false
                        val turns = trajectoryEngine.ingestFrame(frame)
                        val msgs = trajectoryEngine.toChatMessages(conversationId)

                        withContext(Dispatchers.Main) {
                            if (activeStreamConversationId != conversationId) return@withContext
                            _conversationError.value = null
                            _isLoadingConversation.value = false

                            val isRunning = trajectoryEngine.isRunning || isPromptInFlight
                            val isWaiting = trajectoryEngine.isWaitingInteraction
                            _isStreaming.value = isRunning && !isWaiting

                            if (msgs.isNotEmpty()) {
                                _messages.value = msgs
                                com.example.gemini.ui.chat.ChatFeedCache.prewarm(msgs)

                                msgs.flatMap { it.toolCalls }.filter {
                                    it.toolType == com.example.gemini.domain.model.ToolType.GENERATE_IMAGE && it.output.isNotBlank()
                                }.forEach { tc ->
                                    com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(getApplication(), tc.output, agyHubClient, hubUrl)
                                }
                                msgs.flatMap { it.attachments }.filter { it.isImage && it.path.isNotBlank() }.forEach { att ->
                                    val rawUri = if (att.path.startsWith("file://") || att.path.startsWith("http")) att.path else "file://${att.path}"
                                    com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(getApplication(), rawUri, agyHubClient, hubUrl)
                                }
                            }
                        }
                    }

                    if (isFirstChunk) {
                        // Stream closed immediately without emitting any frames (EOF).
                        // This trajectory does not exist on the daemon (it was deleted or emptied).
                        withContext(Dispatchers.Main) {
                            if (activeStreamConversationId == conversationId) {
                                _isLoadingConversation.value = false
                                _conversations.value = _conversations.value.filter { it.id != conversationId }
                                knownDaemonCascadeIds.remove(conversationId)
                                if (_currentConversation.value?.id == conversationId) {
                                    startNewChat()
                                }
                            }
                        }
                        break // Stop retrying non-existent conversation!
                    } else {
                        // Normal disconnection after receiving data; pause briefly before reconnecting
                        delay(1000)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        break
                    }
                    Log.w("ChatViewModel", "Persistent stream disconnected ($conversationId): ${e.message}. Reconnecting in 1.5s...")
                    withContext(Dispatchers.Main) {
                        _isServerOnline.value = false
                        if (isFirstChunk) {
                            _isLoadingConversation.value = false
                            _conversationError.value = "Cannot connect to Antigravity Hub on port 8090. Make sure 'agy --hub' is running."
                        }
                    }
                    delay(1500)
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            _conversations.value = _conversations.value.filter { it.id != id }
            knownDaemonCascadeIds.remove(id)
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            agyHubClient.deleteCascadeTrajectory(id, hubUrl)
            if (_currentConversation.value?.id == id) {
                val remaining = _conversations.value
                if (remaining.isNotEmpty()) {
                    selectConversation(remaining.first().id)
                } else {
                    startNewChat()
                }
            }
        }
    }

    fun forkConversation(id: String) {
        viewModelScope.launch {
            _isLoadingConversation.value = true
            _conversationError.value = null
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                val res = agyHubClient.forkConversation(sourceCascadeId = id, forkAtStepIndex = null, hubUrl = hubUrl)
                if (res.isSuccess) {
                    val newCascadeId = res.getOrThrow()
                    if (newCascadeId.isNotBlank()) {
                        selectConversation(newCascadeId)
                    }
                } else {
                    _conversationError.value = "Fork failed: ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Fork failed: ${e.message}")
                _conversationError.value = "Fork error: ${e.message}"
            } finally {
                _isLoadingConversation.value = false
            }
        }
    }

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        _bridgeStatusMessage.value = null
        val conv = _currentConversation.value
        if (conv != null) {
            val updated = conv.copy(modelId = modelId)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
        val modelObj = _availableModels.value.find { it.id == modelId || it.key == modelId }
        val modelKey = modelObj?.key ?: ""
        val modelName = modelObj?.displayName ?: ""
        viewModelScope.launch {
            authPrefs.savePreferredModel(modelId = modelId, modelKey = modelKey, displayName = modelName)
        }
    }

    fun addAttachment(attachment: ChatAttachment) {
        _attachments.value = _attachments.value + attachment
    }

    fun sendMessage(content: String) {
        if ((content.isBlank() && _attachments.value.isEmpty()) || _isStreaming.value) return

        val currentAtts = _attachments.value
        val audioAtts = currentAtts.filter { it.isAudio }
        val nonAudioAtts = currentAtts.filter { !it.isAudio }

        val attText = if (nonAudioAtts.isNotEmpty()) {
            val listStr = nonAudioAtts.joinToString("\n") { att ->
                if (att.isImage) {
                    "[Attached Image: ${att.name}](file://${att.path})"
                } else {
                    "[Attached File: ${att.name}](file://${att.path})"
                }
            }
            if (content.isNotBlank()) "\n\n$listStr" else listStr
        } else ""

        val rawPrompt = (content.trim() + attText).trim()
        val finalPrompt = if (rawPrompt.isNotBlank()) rawPrompt else if (audioAtts.isNotEmpty()) "Voice note" else ""
        _attachments.value = emptyList()

        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = finalPrompt,
            attachments = currentAtts
        )

        var updatedConv = conv
        val isFirstUserMsg = _messages.value.none { it.role == MessageRole.USER }
        val isGenericTitle = updatedConv.title == "New Chat" || updatedConv.title == "Antigravity Chat" || updatedConv.title.isBlank()
        if (isGenericTitle || isFirstUserMsg) {
            val firstLine = finalPrompt.trim().lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            val cleanTitle = firstLine.take(40) + if (firstLine.length > 40) "..." else ""
            updatedConv = updatedConv.copy(title = cleanTitle)
        }

        updatedConv = updatedConv.copy(updatedAt = System.currentTimeMillis(), isRunning = true)
        _currentConversation.value = updatedConv

        trajectoryEngine.submitUserPrompt(finalPrompt, currentAtts, conv.id)
        val updatedList = trajectoryEngine.toChatMessages(conv.id)
        _messages.value = updatedList
        com.example.gemini.ui.chat.ChatFeedCache.prewarm(updatedList)

        // Prepare media payload for voice notes
        val mediaList = mutableListOf<com.example.gemini.data.remote.AgyHubClient.AgyMediaItem>()
        for (aud in audioAtts) {
            val b64 = when {
                !aud.base64.isNullOrBlank() -> aud.base64
                aud.path.isNotBlank() && java.io.File(aud.path).exists() -> {
                    android.util.Base64.encodeToString(java.io.File(aud.path).readBytes(), android.util.Base64.NO_WRAP)
                }
                else -> null
            }
            if (!b64.isNullOrBlank()) {
                mediaList.add(
                    com.example.gemini.data.remote.AgyHubClient.AgyMediaItem(
                        mimeType = aud.mimeType ?: "audio/mp4",
                        base64 = b64,
                        durationSeconds = aud.durationSeconds,
                        description = aud.name.ifBlank { "Voice note" }
                    )
                )
            }
        }

        viewModelScope.launch {
            val exists = _conversations.value.any { it.id == updatedConv.id }
            val newConversations = if (exists) {
                _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
            } else {
                listOf(updatedConv) + _conversations.value
            }
            _conversations.value = newConversations.sortedByDescending { it.updatedAt }
            executeStream(updatedConv, updatedList, mediaItems = mediaList)
        }
    }

    val isTranscribingAudio = MutableStateFlow(false)

    fun transcribeAudioFile(
        file: java.io.File,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!file.exists() || file.length() == 0L) {
            onError("Audio file empty or not found")
            return
        }
        viewModelScope.launch {
            isTranscribingAudio.value = true
            try {
                val bytes = file.readBytes()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val hub = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                val res = agyHubClient.getTranscription(audioBase64 = b64, hubUrl = hub)
                if (res.isSuccess) {
                    val text = res.getOrThrow().trim()
                    if (text.isNotBlank()) {
                        onDone(text)
                    } else {
                        onError("No speech recognized in recording")
                    }
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "Transcription failed"
                    onError(msg)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "transcribeAudioFile error: ${e.message}", e)
                onError(e.message ?: "Transcription error")
            } finally {
                isTranscribingAudio.value = false
            }
        }
    }

    fun retryMessage(messageId: String) {
        if (_isStreaming.value) return
        val conv = _currentConversation.value ?: return
        val current = _messages.value
        val index = current.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val targetMsg = current[index]
        if (targetMsg.role == MessageRole.USER) {
            val isLastUserMsg = index >= current.indexOfLast { it.role == MessageRole.USER }
            if (isLastUserMsg) {
                // Truncate following assistant responses and re-stream
                val truncated = current.take(index + 1)
                _messages.value = truncated
                viewModelScope.launch {
                    executeStream(conv, truncated)
                }
            } else {
                // Resend previous prompt as a fresh new user message
                sendMessage(targetMsg.content)
            }
        } else {
            // Assistant response retry: truncate this response and re-execute
            val truncated = current.take(index)
            _messages.value = truncated
            viewModelScope.launch {
                executeStream(conv, truncated)
            }
        }
    }

    fun prepareEditMessage(messageId: String): String? {
        val conv = _currentConversation.value ?: return null
        val current = _messages.value
        val index = current.indexOfFirst { it.id == messageId }
        if (index < 0) return null

        val targetMsg = current[index]
        val isLastUserMsg = index >= current.indexOfLast { it.role == MessageRole.USER }

        if (isLastUserMsg) {
            val truncated = current.take(index)
            _messages.value = truncated
        }
        return targetMsg.content
    }

    /**
     * Reverts the last user message: restores prompt text and attachments to the input box,
     * immediately prunes it from the local UI, and sends an undo RPC to the AGY hub daemon.
     */
    fun revertAndEditLastUserMessage(targetMsg: ChatMessage, onRestored: (String) -> Unit) {
        val conv = _currentConversation.value ?: return
        val current = _messages.value
        val index = current.indexOfFirst { it.id == targetMsg.id }
        if (index < 0) return

        persistentStreamJob?.cancel()
        persistentStreamJob = null
        _isStreaming.value = false
        isPromptInFlight = false

        val truncated = current.take(index)
        _messages.value = truncated

        val imageRegex = Regex("""\[Attached Image:\s*([^\]]+)\]\([^\)]+\)""", RegexOption.IGNORE_CASE)
        val fileRegex = Regex("""\[Attached File:\s*([^\]]+)\]\([^\)]+\)""", RegexOption.IGNORE_CASE)
        var cleanText = targetMsg.content
            .replace(imageRegex, "")
            .replace(fileRegex, "")
            .trim()
        if (cleanText == "Voice note" || cleanText == "Voice message") {
            cleanText = ""
        }

        if (targetMsg.attachments.isNotEmpty()) {
            _attachments.value = targetMsg.attachments
        }

        onRestored(cleanText)

        val isFirstUserMsg = index == 0 || current.none { it.role == MessageRole.USER && it.id != targetMsg.id }
        if (isFirstUserMsg) {
            // Optimistically clean up sidebar & switch to fresh new chat right away
            _conversations.value = _conversations.value.filter { it.id != conv.id }
            knownDaemonCascadeIds.remove(conv.id)
            activeStreamConversationId = null
            val modelToUse = if (_selectedModelId.value.isNotBlank()) {
                com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
            } else {
                (_enabledModels.value.firstOrNull()?.id ?: "")
            }
            _currentConversation.value = Conversation(
                id = UUID.randomUUID().toString(),
                title = "New Chat",
                modelId = modelToUse,
                sessionId = UUID.randomUUID().toString()
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val modelEnum = com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
            val res = agyHubClient.revertLastUserMessage(conv.id, modelEnum, hubUrl)
            if (res.isSuccess) {
                val targetStep = res.getOrThrow()
                if (targetStep < 0) {
                    withContext(Dispatchers.Main) {
                        _conversations.value = _conversations.value.filter { it.id != conv.id }
                        knownDaemonCascadeIds.remove(conv.id)
                        if (_currentConversation.value?.id == conv.id) {
                            startNewChat()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        startPersistentStream(conv.id)
                    }
                }
            } else {
                android.util.Log.w("ChatViewModel", "revertLastUserMessage failed on hub: ${res.exceptionOrNull()?.message}")
                if (!isFirstUserMsg) {
                    withContext(Dispatchers.Main) {
                        startPersistentStream(conv.id)
                    }
                }
            }
        }
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        val currentAccess = authPrefs.accessToken.firstOrNull()
        val currentRefresh = authPrefs.refreshToken.firstOrNull()
        val expiresAt = authPrefs.expiresAt.firstOrNull() ?: 0L

        val isExpired = forceRefresh || (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000)

        if (!isExpired && !currentAccess.isNullOrBlank()) {
            return currentAccess
        }

        if (!currentRefresh.isNullOrBlank()) {
            android.util.Log.d("GeminiApp", "[Auth] Refreshing Google OAuth access token using refresh_token...")
            val result = oauthManager.refreshToken(currentRefresh)
            if (result.isSuccess) {
                val tokenData = result.getOrThrow()
                android.util.Log.d("GeminiApp", "[Auth] Token refreshed successfully! New access token acquired.")
                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token ?: currentRefresh,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                return tokenData.access_token
            } else {
                android.util.Log.e("GeminiApp", "[Auth] Failed to refresh token: ${result.exceptionOrNull()?.message}")
            }
        }

        return currentAccess
    }

    private suspend fun executeStream(
        conv: Conversation, 
        currentHistory: List<ChatMessage>,
        isRetryAfterRefresh: Boolean = false,
        existingAssistantMsgId: String? = null,
        existingToolCalls: List<com.example.gemini.domain.model.ToolCall> = emptyList(),
        priorTextPrefix: String = "",
        mediaItems: List<com.example.gemini.data.remote.AgyHubClient.AgyMediaItem> = emptyList()
    ) {
        _isStreaming.value = true
        isPromptInFlight = true
        _bridgeStatusMessage.value = null

        val assistantMsgId = existingAssistantMsgId ?: UUID.randomUUID().toString()
        currentAssistantMsgId = assistantMsgId

        if (existingAssistantMsgId == null) {
            val hasStreamingAssistant = _messages.value.any { it.role == MessageRole.ASSISTANT && it.isStreaming }
            if (!hasStreamingAssistant) {
                val assistantMsg = ChatMessage(
                    id = assistantMsgId,
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = priorTextPrefix,
                    toolCalls = existingToolCalls,
                    isStreaming = true
                )
                _messages.value = _messages.value + assistantMsg
            }
        } else {
            updateAssistantMessage(
                msgId = assistantMsgId,
                content = priorTextPrefix,
                thought = "",
                thoughtDuration = null,
                toolCalls = existingToolCalls,
                isStreaming = true,
                forceImmediate = true
            )
        }

        val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
        val userPrompt = currentHistory.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val modelEnum = com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
        val selectedModel = _enabledModels.value.find { it.id == modelEnum }
        val supportsThinking = selectedModel?.supportsThinking ?: (modelEnum.contains("thinking", ignoreCase = true) || modelEnum.contains("flash", ignoreCase = true) || modelEnum.contains("pro", ignoreCase = true))
        val thinkingBudget = if (supportsThinking) 8192 else 0

        val currentProjPath = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path ?: ""
        val workspaceUri = if (currentProjPath.isNotBlank()) {
            if (currentProjPath.startsWith("file://")) currentProjPath else "file://$currentProjPath"
        } else ""

        val isKnownOnDaemon = knownDaemonCascadeIds.contains(conv.id) ||
                (totalStepsCount > 0 && conv.title != "New Chat" && conv.title != "Antigravity Chat" && conv.title.isNotBlank())

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // If brand new conversation not yet on daemon, call startCascade FIRST and await it!
                if (!isKnownOnDaemon) {
                    val startRes = agyHubClient.startCascade(
                        cascadeId = conv.id,
                        modelEnum = modelEnum,
                        workspaceUri = workspaceUri,
                        hubUrl = hubUrl
                    )
                    if (startRes.isFailure) {
                        val err = startRes.exceptionOrNull()?.message ?: "Failed to start conversation on daemon"
                        Log.e("ChatViewModel", "startCascade error: $err")
                        withContext(Dispatchers.Main) {
                            isPromptInFlight = false
                            _isStreaming.value = false
                            hasSeenTurnActivity = false
                            trajectoryEngine.cancelRunning()
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = "⚠️ $err",
                                isStreaming = false,
                                forceImmediate = true
                            )
                        }
                        return@launch
                    }
                    knownDaemonCascadeIds.add(conv.id)
                    if (workspaceUri.isNotBlank()) {
                        val projName = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.name
                            ?: java.io.File(workspaceUri.removePrefix("file://")).name
                        agyHubClient.updateProjectSettings(
                            projectId = "default-cli-project",
                            projectName = projName,
                            folderUris = listOf(workspaceUri),
                            hubUrl = hubUrl
                        )
                    }
                    val updatedWithWs = conv.copy(workspaceUri = workspaceUri)
                    withContext(Dispatchers.Main) {
                        _currentConversation.value = updatedWithWs
                        _conversations.value = _conversations.value.map { if (it.id == updatedWithWs.id) updatedWithWs else it }
                    }
                }

                // Ensure persistent stream is active for this conversation
                if (activeStreamConversationId != conv.id || persistentStreamJob?.isActive != true) {
                    startPersistentStream(conv.id)
                    delay(50)
                }

                // Reset turn boundary and state for this active turn
                currentTurnStartStep = totalStepsCount
                hasStartedRunning = false
                hasSeenTurnActivity = false

                val currentAutoExecPolicy = authPrefs.commandAutoExecutionPolicy.firstOrNull() ?: "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"

                var sendRes = agyHubClient.sendUserPrompt(
                    cascadeId = conv.id,
                    text = userPrompt,
                    modelEnum = modelEnum,
                    thinkingBudget = thinkingBudget,
                    autoExecutionPolicy = currentAutoExecPolicy,
                    media = mediaItems,
                    hubUrl = hubUrl
                )

                if (sendRes.isFailure) {
                    val err = sendRes.exceptionOrNull()?.message ?: "Failed to send message"
                    Log.e("ChatViewModel", "sendUserPrompt error: $err")
                    // If trajectory was not found on daemon (e.g. daemon restarted or session expired),
                    // attempt to re-start cascade and retry sending the prompt once!
                    if (err.contains("trajectory not found", ignoreCase = true) || err.contains("not found", ignoreCase = true)) {
                        Log.i("ChatViewModel", "Trajectory not found on daemon. Attempting to restart cascade ${conv.id} and retry...")
                        val restartRes = agyHubClient.startCascade(
                            cascadeId = conv.id,
                            modelEnum = modelEnum,
                            workspaceUri = workspaceUri,
                            hubUrl = hubUrl
                        )
                        if (restartRes.isSuccess) {
                            knownDaemonCascadeIds.add(conv.id)
                            sendRes = agyHubClient.sendUserPrompt(
                                cascadeId = conv.id,
                                text = userPrompt,
                                modelEnum = modelEnum,
                                thinkingBudget = thinkingBudget,
                                autoExecutionPolicy = currentAutoExecPolicy,
                                media = mediaItems,
                                hubUrl = hubUrl
                            )
                        }
                    }
                }

                if (sendRes.isSuccess) {
                    isPromptInFlight = false
                } else {
                    val err = sendRes.exceptionOrNull()?.message ?: "Failed to send message"
                    Log.e("ChatViewModel", "sendUserPrompt final error: $err")
                    withContext(Dispatchers.Main) {
                        isPromptInFlight = false
                        _isStreaming.value = false
                        hasSeenTurnActivity = false
                        trajectoryEngine.cancelRunning()
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = "⚠️ $err",
                            isStreaming = false,
                            forceImmediate = true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "sendUserPrompt exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isPromptInFlight = false
                    _isStreaming.value = false
                    hasSeenTurnActivity = false
                    trajectoryEngine.cancelRunning()
                    updateAssistantMessage(
                        msgId = assistantMsgId,
                        content = "⚠️ ${e.message}",
                        isStreaming = false,
                        forceImmediate = true
                    )
                }
            }
        }
    }

    private var lastStreamUpdateTime = 0L

    private fun updateAssistantMessage(
        msgId: String,
        content: String,
        thought: String? = null,
        thoughtDuration: Long? = null,
        toolCalls: List<com.example.gemini.domain.model.ToolCall> = emptyList(),
        isStreaming: Boolean,
        tokenUsage: com.example.gemini.domain.model.TokenUsage? = null,
        rawPayload: String? = null,
        forceImmediate: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!forceImmediate && isStreaming && (now - lastStreamUpdateTime < 30)) {
            return
        }
        lastStreamUpdateTime = now
        // Auto-extract chat title if present
        if (content.contains("<chat_title>", ignoreCase = true)) {
            val titleMatch = Regex("<chat_title>([\\s\\S]*?)</chat_title>", RegexOption.IGNORE_CASE).find(content)
            if (titleMatch != null) {
                val extractedTitle = titleMatch.groupValues[1].trim().replace("\"", "").replace("'", "")
                val conv = _currentConversation.value
                if (conv != null && extractedTitle.isNotBlank() && conv.title != extractedTitle) {
                    val updated = conv.copy(title = extractedTitle)
                    _currentConversation.value = updated
                    _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
                }
            }
        }

        val list = _messages.value.toMutableList()
        val index = list.indexOfFirst { it.id == msgId }
        if (index >= 0) {
            val existing = list[index]
            val finalToolCalls = if (toolCalls.isNotEmpty()) toolCalls else existing.toolCalls
            list[index] = existing.copy(
                content = content,
                thoughtText = thought?.ifEmpty { null } ?: existing.thoughtText,
                thoughtDurationMs = thoughtDuration ?: existing.thoughtDurationMs,
                toolCalls = finalToolCalls,
                isStreaming = isStreaming,
                tokenUsage = tokenUsage ?: existing.tokenUsage,
                rawPayload = rawPayload ?: existing.rawPayload
            )
            _messages.value = list
            android.util.Log.d("PERF_TRACE", "🌊 [Streaming Emit] ID=${msgId.take(8)}, len=${content.length}, isStreaming=$isStreaming")
        }
    }

    fun stopStreaming() {
        val conv = _currentConversation.value
        streamingJob?.cancel()
        isPromptInFlight = false
        _isStreaming.value = false
        hasStartedRunning = false
        hasSeenTurnActivity = false
        currentAssistantMsgId = null
        _bridgeStatusMessage.value = null
        trajectoryEngine.cancelRunning()

        if (conv != null) {
            viewModelScope.launch {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                agyHubClient.cancelCascadeInvocation(conv.id, hubUrl)
            }

            val list = _messages.value.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            }
            _messages.value = list
        }
    }

    fun applyQuotaSummary(summary: com.example.gemini.domain.model.QuotaSummaryResponse) {
        _quotaSummary.value = summary
        val geminiGroup = summary.groups.find {
            it.groupId == "gemini" || it.groupName.contains("gemini", ignoreCase = true)
        }
        val claudeGroup = summary.groups.find {
            it.groupId == "claude_gpt" || it.groupName.contains("claude", ignoreCase = true) || it.groupName.contains("gpt", ignoreCase = true)
        }

        val updatedQuotas = _availableModels.value.map { model ->
            val isClaude = model.family == com.example.gemini.domain.model.ModelFamily.CLAUDE
            val group = if (isClaude) claudeGroup else geminiGroup
            com.example.gemini.domain.model.ModelQuota(
                modelId = model.id,
                remainingFraction = group?.fiveHour?.remainingFraction,
                resetTime = group?.fiveHour?.resetTime,
                usedPercentage = group?.fiveHour?.usedPct,
                resetCountdown = group?.fiveHour?.countdown,
                weeklyRemainingFraction = group?.weekly?.remainingFraction,
                weeklyUsedPercentage = group?.weekly?.usedPct,
                weeklyResetCountdown = group?.weekly?.countdown
            )
        }
        _quotas.value = updatedQuotas
    }

    fun refreshQuotas(force: Boolean = true) {
        viewModelScope.launch {
            _isRefreshingModels.value = true
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                val modelsDeferred = async { agyHubClient.getAvailableModels(forceRefresh = force, hubUrl = hubUrl) }
                val quotasDeferred = async { agyHubClient.retrieveUserQuotaSummary(hubUrl = hubUrl) }

                val modelsRes = modelsDeferred.await()
                val quotasRes = quotasDeferred.await()

                if (modelsRes.isSuccess) {
                    val models = modelsRes.getOrThrow()
                    if (models.isNotEmpty()) {
                        com.example.gemini.data.remote.AgyHubClient.updateModelRegistry(models)
                        _availableModels.value = models
                        val currentSelected = _selectedModelId.value
                        val existingValidModel = if (currentSelected.isNotBlank()) {
                            models.find { it.id == currentSelected || it.key == currentSelected }
                        } else null

                        if (existingValidModel != null) {
                            // Current selection is already valid! Keep it and ensure canonical id
                            if (_selectedModelId.value != existingValidModel.id) {
                                _selectedModelId.value = existingValidModel.id
                            }
                        } else {
                            // First run or need to restore saved preference
                            val savedId = authPrefs.preferredModelId.firstOrNull()
                            val savedKey = authPrefs.preferredModelKey.firstOrNull()
                            val savedName = authPrefs.preferredModelName.firstOrNull()

                            val matched = models.find {
                                (!savedKey.isNullOrBlank() && (it.key.equals(savedKey, ignoreCase = true) || it.id == savedKey)) ||
                                (!savedName.isNullOrBlank() && it.displayName.equals(savedName, ignoreCase = true)) ||
                                (!savedId.isNullOrBlank() && (it.id == savedId || it.key == savedId))
                            }

                            if (matched != null) {
                                _selectedModelId.value = matched.id
                                authPrefs.savePreferredModel(modelId = matched.id, modelKey = matched.key, displayName = matched.displayName)
                            } else {
                                val preferred = models.find { it.key.contains("3.8") || it.key.contains("flash") } ?: models.first()
                                _selectedModelId.value = preferred.id
                            }
                        }

                        recomputeEnabledModels()
                    }
                }

                if (quotasRes.isSuccess) {
                    applyQuotaSummary(quotasRes.getOrThrow())
                }

                val userRes = agyHubClient.getLocalUserInfo(hubUrl)
                if (userRes.isSuccess) {
                    val (username, _) = userRes.getOrThrow()
                    if (username.isNotBlank()) {
                        _userEmail.value = username
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "Error refreshing models & quotas from hub: ${e.message}")
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    fun applyManualInput(input: String) {
        val trimmed = input.trim()
        android.util.Log.d("GeminiApp", "[OAuth] applyManualInput received: ${trimmed.take(30)}...")
        if (trimmed.contains("code=")) {
            val match = Regex("[?&]code=([^&\\s]+)").find(trimmed)
            val code = match?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (code != null) {
                android.util.Log.d("GeminiApp", "[OAuth] Extracted auth code from manual input: ${code.take(15)}...")
                handleOAuthCode(code)
                return
            }
        }

        if (trimmed.startsWith("4/0")) {
            val decoded = try { java.net.URLDecoder.decode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
            handleOAuthCode(decoded)
            return
        }

        // Direct access token (e.g. ya29...)
        viewModelScope.launch {
            android.util.Log.d("GeminiApp", "[OAuth] Applying direct token...")
            authPrefs.saveTokens(trimmed, null, "Manual Token")
            val assistRes = apiService.loadCodeAssist(trimmed)
            if (assistRes.isSuccess) {
                val (proj, tier) = assistRes.getOrThrow()
                authPrefs.saveProjectInfo(proj, tier)
                _projectId.value = proj
                _tier.value = tier
            }
            val userRes = oauthManager.fetchUserInfo(trimmed)
            if (userRes.isSuccess) {
                userRes.getOrNull()?.email?.let { email ->
                    authPrefs.saveTokens(trimmed, null, email)
                    _userEmail.value = email
                }
            }
            refreshQuotas()
        }
    }

    fun startOAuthServer() {
        if (oauthManager.isServerListening.value && serverJob?.isActive == true) {
            android.util.Log.d("GeminiApp", "[OAuth] Server is already active, ignoring start request")
            return
        }
        serverJob?.cancel()
        serverJob = viewModelScope.launch {
            _isOAuthServerLoading.value = true
            try {
                if (pendingPkceVerifier == null) {
                    val pkce = oauthManager.generatePkce()
                    pendingPkceVerifier = pkce.codeVerifier
                    android.util.Log.d("GeminiApp", "[OAuth] Generated PKCE verifier for server session")
                }
                _isOAuthServerLoading.value = false
                oauthManager.startLocalCallbackServer { code ->
                    handleOAuthCode(code)
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "[OAuth] Server start failed: ${e.message}")
                _isOAuthServerLoading.value = false
            }
        }
    }

    fun stopOAuthServer() {
        serverJob?.cancel()
        serverJob = null
        _isOAuthServerLoading.value = false
        oauthManager.stopLocalCallbackServer()
    }

    fun toggleOAuthServer(enable: Boolean) {
        if (enable) {
            startOAuthServer()
        } else {
            stopOAuthServer()
        }
    }

    fun login() {
        loginToAgyHub()
    }

    fun logout() {
        stopOAuthServer()
        pendingPkceVerifier = null
        logoutFromAgyHub()
        viewModelScope.launch {
            authPrefs.clearAuth()
        }
        _userEmail.value = null
        _projectId.value = "rising-fact-p41fc"
        _tier.value = "pro"
        _quotas.value = emptyList()
        _quotaSummary.value = null
    }

    fun getGoogleOAuthUrl(): String {
        val pkce = oauthManager.generatePkce()
        pendingPkceVerifier = pkce.codeVerifier
        android.util.Log.d("GeminiApp", "[OAuth] getGoogleOAuthUrl generated verifier (len=${pkce.codeVerifier.length})")
        startOAuthServer()
        return pkce.authUrl
    }

    fun handleOAuthCode(code: String) {
        val trimmedCode = code.trim()
        android.util.Log.d("GeminiApp", "[OAuth] handleOAuthCode processing code: ${trimmedCode.take(20)}...")
        val verifier = pendingPkceVerifier
        if (verifier == null) {
            android.util.Log.e("GeminiApp", "[OAuth] ERROR: pendingPkceVerifier is null! Cannot exchange auth code.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Brief delay to let the server socket fully close and free the IO thread pool
            // before making outbound network calls. This prevents DNS resolution failures
            // that occur when the token exchange fires from within the server socket callback.
            delay(400L)

            var tokenRes: Result<GoogleOAuthManager.TokenResponse>? = null
            for (attempt in 1..3) {
                android.util.Log.d("GeminiApp", "[OAuth] exchangeCodeForToken attempt $attempt...")
                tokenRes = oauthManager.exchangeCodeForToken(trimmedCode, verifier)
                if (tokenRes.isSuccess) break
                android.util.Log.w("GeminiApp", "[OAuth] Attempt $attempt failed: ${tokenRes.exceptionOrNull()?.message}")
                if (attempt < 3) delay(1000L * attempt)
            }

            if (tokenRes?.isSuccess == true) {
                val tokenData = tokenRes.getOrThrow()
                android.util.Log.d("GeminiApp", "[OAuth] Token exchange SUCCESS! Fetching user profile...")
                val userRes = oauthManager.fetchUserInfo(tokenData.access_token)
                val email = userRes.getOrNull()?.email ?: "Google Account"
                android.util.Log.d("GeminiApp", "[OAuth] User email resolved: $email")

                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token,
                    email = email,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                withContext(Dispatchers.Main) { _userEmail.value = email }

                val assistRes = apiService.loadCodeAssist(tokenData.access_token)
                if (assistRes.isSuccess) {
                    val (proj, tier) = assistRes.getOrThrow()
                    android.util.Log.d("GeminiApp", "[OAuth] CodeAssist project: $proj, tier: $tier")
                    authPrefs.saveProjectInfo(proj, tier)
                    withContext(Dispatchers.Main) {
                        _projectId.value = proj
                        _tier.value = tier
                    }
                } else {
                    android.util.Log.w("GeminiApp", "[OAuth] CodeAssist loading warning: ${assistRes.exceptionOrNull()?.message}")
                }
                refreshQuotas()
            } else {
                android.util.Log.e("GeminiApp", "[OAuth] Token exchange FAILED after retries: ${tokenRes?.exceptionOrNull()?.message}")
            }
        }
    }

    fun approveAndExecuteTerminalTool(
        toolCall: com.example.gemini.domain.model.ToolCall,
        messageId: String,
        scope: String? = null
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return
        val trajectoryId = toolCall.trajectoryId?.takeIf { it.isNotBlank() }
            ?: trajectoryEngine.trajectoryId

        trajectoryEngine.markStepResponded(stepIndex)
        trajectoryEngine.optimisticUpdateStepStatus(stepIndex, com.example.gemini.data.remote.dto.CortexStepStatuses.RUNNING)
        _messages.value = trajectoryEngine.toChatMessages(conv.id)
        _isStreaming.value = true

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val resolvedScope = scope ?: authPrefs.defaultApprovalScope.firstOrNull() ?: "PERMISSION_SCOPE_ONCE"
            val resolvedInteractionType = toolCall.interactionType
                ?: if (toolCall.name.startsWith("mcp_") || toolCall.name == "call_mcp_tool" || toolCall.name == "read_resource" || toolCall.name == "list_resources") "mcp" else "permission"
            val res = agyHubClient.handleCascadeUserInteraction(
                cascadeId = conv.id,
                stepIndex = stepIndex,
                trajectoryId = trajectoryId,
                allow = true,
                scope = resolvedScope,
                interactionType = resolvedInteractionType,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to approve tool"
                Log.e("ChatViewModel", "handleCascadeUserInteraction (approve) error: $err")
                _conversationError.value = "Approval failed: $err"
                _isStreaming.value = false

                // Roll back optimistic state on failure so UI accurately reflects reality
                trajectoryEngine.unmarkStepResponded(stepIndex)
                trajectoryEngine.optimisticUpdateStepStatus(stepIndex, com.example.gemini.data.remote.dto.CortexStepStatuses.WAITING)
                _messages.value = trajectoryEngine.toChatMessages(conv.id)
            }
        }
    }

    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return
        val trajectoryId = toolCall.trajectoryId?.takeIf { it.isNotBlank() }
            ?: trajectoryEngine.trajectoryId

        val denyInstruction = reason ?: "User rejected this command."
        trajectoryEngine.markStepResponded(stepIndex)
        trajectoryEngine.optimisticUpdateStepStatus(stepIndex, com.example.gemini.data.remote.dto.CortexStepStatuses.CANCELED, output = denyInstruction)
        _messages.value = trajectoryEngine.toChatMessages(conv.id)
        _isStreaming.value = false

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val resolvedInteractionType = toolCall.interactionType
                ?: if (toolCall.name.startsWith("mcp_") || toolCall.name == "call_mcp_tool" || toolCall.name == "read_resource" || toolCall.name == "list_resources") "mcp" else "permission"
            val res = agyHubClient.handleCascadeUserInteraction(
                cascadeId = conv.id,
                stepIndex = stepIndex,
                trajectoryId = trajectoryId,
                allow = false,
                userDenyInstruction = denyInstruction,
                interactionType = resolvedInteractionType,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to reject tool"
                Log.e("ChatViewModel", "handleCascadeUserInteraction (deny) error: $err")
                _conversationError.value = "Rejection failed: $err"

                // Roll back optimistic state on failure so UI accurately reflects reality
                trajectoryEngine.unmarkStepResponded(stepIndex)
                trajectoryEngine.optimisticUpdateStepStatus(stepIndex, com.example.gemini.data.remote.dto.CortexStepStatuses.WAITING)
                _messages.value = trajectoryEngine.toChatMessages(conv.id)
            }
        }
    }

    fun approveAllPendingTools(list: List<PendingToolApproval>) {
        list.forEach { approval ->
            approveAndExecuteTerminalTool(approval.toolCall, approval.messageId)
        }
    }

    fun rejectAllPendingTools(list: List<PendingToolApproval>, reason: String? = null) {
        list.forEach { approval ->
            rejectTerminalTool(approval.toolCall, approval.messageId, reason)
        }
    }

    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return

        // Optimistically mark this specific step terminated so UI responds instantaneously
        trajectoryEngine.optimisticUpdateStepStatus(
            stepIndex = stepIndex,
            status = com.example.gemini.data.remote.dto.CortexStepStatuses.CANCELED,
            output = "Command terminated by user."
        )
        _messages.value = trajectoryEngine.toChatMessages(conv.id)

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val res = agyHubClient.cancelCascadeSteps(
                cascadeId = conv.id,
                stepIndices = listOf(stepIndex),
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to cancel step"
                Log.e("ChatViewModel", "cancelCascadeSteps error for step $stepIndex: $err")
            }
        }
    }

    fun proceedAfterTermination(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, userInstructions: String?) {
        _terminatedToolDialog.value = null
        val conv = _currentConversation.value ?: return
        val msg = _messages.value.find { it.id == messageId } ?: return

        viewModelScope.launch {
            val currentHistory = _messages.value.filter { it.id != messageId }
            val promptFeedback = if (!userInstructions.isNullOrBlank()) "\nUser Feedback: $userInstructions" else ""
            val syntheticHistory = currentHistory + listOf(
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = "<execute_command>${toolCall.command}</execute_command>"
                ),
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.USER,
                    content = "[Terminal Command `${toolCall.command}` was terminated by user]:\n```\n${toolCall.output.ifEmpty { "(No output before termination)" }}\n```$promptFeedback"
                )
            )

            executeStream(
                conv = conv,
                currentHistory = syntheticHistory,
                existingAssistantMsgId = messageId,
                existingToolCalls = msg.toolCalls,
                priorTextPrefix = msg.content
            )
        }
    }

    fun submitUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, summary: String) {
        val conv = _currentConversation.value ?: return
        val msg = _messages.value.find { it.id == messageId } ?: return

        val completedToolCall = toolCall.copy(
            status = "SUCCESS",
            output = summary
        )
        val updatedToolCalls = msg.toolCalls.map { if (it.stepIndex != null && it.stepIndex == toolCall.stepIndex) completedToolCall else it }

        updateAssistantMessage(
            msgId = messageId,
            content = msg.content,
            thought = msg.thoughtText ?: "",
            thoughtDuration = msg.thoughtDurationMs,
            toolCalls = updatedToolCalls,
            isStreaming = true
        )

        viewModelScope.launch {
            val currentHistory = _messages.value.filter { it.id != messageId }
            val syntheticHistory = currentHistory + listOf(
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = "<ask_choices>${toolCall.command}</ask_choices>"
                ),
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.USER,
                    content = summary
                )
            )

            executeStream(
                conv = conv,
                currentHistory = syntheticHistory,
                existingAssistantMsgId = messageId,
                existingToolCalls = updatedToolCalls,
                priorTextPrefix = msg.content
            )
        }
    }

    fun skipUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val msg = _messages.value.find { it.id == messageId } ?: return

        val skippedSummary = "[User skipped clarification choices. Please proceed using the most sensible standard defaults and best practices.]"
        val completedToolCall = toolCall.copy(
            status = "SUCCESS",
            output = skippedSummary
        )
        val updatedToolCalls = msg.toolCalls.map { if (it.stepIndex != null && it.stepIndex == toolCall.stepIndex) completedToolCall else it }

        updateAssistantMessage(
            msgId = messageId,
            content = msg.content,
            thought = msg.thoughtText ?: "",
            thoughtDuration = msg.thoughtDurationMs,
            toolCalls = updatedToolCalls,
            isStreaming = true
        )

        viewModelScope.launch {
            val currentHistory = _messages.value.filter { it.id != messageId }
            val syntheticHistory = currentHistory + listOf(
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = "<ask_choices>${toolCall.command}</ask_choices>"
                ),
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.USER,
                    content = skippedSummary
                )
            )

            executeStream(
                conv = conv,
                currentHistory = syntheticHistory,
                existingAssistantMsgId = messageId,
                existingToolCalls = updatedToolCalls,
                priorTextPrefix = msg.content
            )
        }
    }

    fun loadMcpServers() {
        viewModelScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL

                // 1. Fetch live states from AGY daemon
                val liveResult = agyHubClient.getMcpServerStates(hubUrl)
                var fetchError: String? = null
                if (liveResult.isFailure) {
                    val err = liveResult.exceptionOrNull()?.message ?: "Failed to connect to Antigravity Hub"
                    Log.w("ChatViewModel", "loadMcpServers liveResult failed: $err")
                    fetchError = err
                }
                val liveList = liveResult.getOrDefault(emptyList()).toMutableList()

                // 2. Read persistent config from mcp_config.json to ensure any unstarted / disabled servers are also represented
                val configRaw = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig()
                if (!configRaw.isNullOrBlank()) {
                    try {
                        val cfgJson = org.json.JSONObject(configRaw)
                        val serversObj = cfgJson.optJSONObject("mcpServers")
                        if (serversObj != null) {
                            val keys = serversObj.keys()
                            while (keys.hasNext()) {
                                val serverName = keys.next()
                                val sObj = serversObj.optJSONObject(serverName) ?: continue
                                val existing = liveList.find { it.name.equals(serverName, ignoreCase = true) }
                                val disabled = sObj.optBoolean("disabled", false)

                                val command = sObj.optString("command", "")
                                val argsList = mutableListOf<String>()
                                sObj.optJSONArray("args")?.let { arr ->
                                    for (i in 0 until arr.length()) argsList.add(arr.optString(i))
                                }
                                val envMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("env")?.let { envObj ->
                                    val envKeys = envObj.keys()
                                    while (envKeys.hasNext()) {
                                        val k = envKeys.next()
                                        envMap[k] = envObj.optString(k, "")
                                    }
                                }
                                val serverUrl = sObj.optString("serverUrl", "")
                                val headersMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("headers")?.let { hObj ->
                                    val hKeys = hObj.keys()
                                    while (hKeys.hasNext()) {
                                        val k = hKeys.next()
                                        headersMap[k] = hObj.optString(k, "")
                                    }
                                }
                                val cwd = sObj.optString("cwd", "")

                                val parsedSpec = com.example.gemini.domain.model.McpServerSpec(
                                    serverName = serverName,
                                    command = command,
                                    args = argsList,
                                    env = envMap,
                                    serverUrl = serverUrl,
                                    headers = headersMap,
                                    disabled = disabled,
                                    cwd = cwd
                                )

                                if (existing == null) {
                                    liveList.add(
                                        com.example.gemini.domain.model.McpServerState(
                                            name = serverName,
                                            spec = parsedSpec,
                                            status = if (disabled) "DISABLED" else "MCP_SERVER_STATUS_STOPPED",
                                            isEnabled = !disabled
                                        )
                                    )
                                } else {
                                    val updatedSpec = existing.spec ?: parsedSpec
                                    val idx = liveList.indexOf(existing)
                                    liveList[idx] = existing.copy(
                                        spec = updatedSpec,
                                        isEnabled = !updatedSpec.disabled
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error parsing mcp_config.json: ${e.message}")
                    }
                }

                if (fetchError != null) {
                    _mcpErrorMessage.value = "Cannot reach Antigravity daemon ($hubUrl): $fetchError"
                }

                // If daemon RPC failed and no servers found, preserve existing cached servers if available
                if (liveList.isEmpty() && fetchError != null && _mcpServers.value.isNotEmpty()) {
                    Log.w("ChatViewModel", "Retaining ${_mcpServers.value.size} cached MCP servers due to fetch failure")
                } else {
                    // Stable alphabetical sorting by name (case-insensitive) to prevent jumping
                    _mcpServers.value = liveList.sortedBy { it.name.lowercase() }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadMcpServers error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun refreshMcpServers(targetServer: String? = null) {
        viewModelScope.launch {
            _isMcpRefreshing.value = true
            _refreshingMcpServer.value = targetServer
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                val res = agyHubClient.refreshMcpServers(hubUrl)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()?.message ?: "Refresh failed"
                    _mcpErrorMessage.value = "Daemon refresh error ($hubUrl): $err"
                }
                delay(600)
                loadMcpServers()
                if (targetServer != null) {
                    val s = _mcpServers.value.find { it.name.equals(targetServer, ignoreCase = true) }
                    if (s != null && !s.error.isNullOrBlank()) {
                        _mcpErrorMessage.value = "'$targetServer': ${s.error}"
                    } else if (s != null && s.tools.isNotEmpty()) {
                        _mcpStatusMessage.value = "Refreshed '$targetServer': ${s.tools.size} tool(s) available"
                    } else {
                        _mcpStatusMessage.value = "Refreshed '$targetServer'"
                    }
                } else {
                    val count = _mcpServers.value.size
                    val toolsCount = _mcpServers.value.sumOf { it.tools.size }
                    _mcpStatusMessage.value = "Refreshed: $count server(s) configured, $toolsCount tool(s) discovered"
                }
            } catch (e: Exception) {
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpRefreshing.value = false
                _refreshingMcpServer.value = null
            }
        }
    }

    fun toggleMcpServer(serverName: String, enabled: Boolean) {
        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL

            // 1. Call daemon ToggleMcpServer
            agyHubClient.toggleMcpServer(serverName, enabled, hubUrl)

            // 2. Persist disabled state in mcp_config.json
            try {
                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val mcpServers = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }
                val targetServer = mcpServers.optJSONObject(serverName)
                if (targetServer != null) {
                    targetServer.put("disabled", !enabled)
                    com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to update disabled flag in mcp_config.json: ${e.message}")
            }

            // 3. Update local state with stable sort
            _mcpServers.value = _mcpServers.value.map {
                if (it.name.equals(serverName, ignoreCase = true)) {
                    val updatedSpec = it.spec?.copy(disabled = !enabled)
                    it.copy(
                        spec = updatedSpec,
                        isEnabled = enabled,
                        status = if (!enabled) "DISABLED" else it.status
                    )
                } else it
            }.sortedBy { it.name.lowercase() }
            _mcpStatusMessage.value = if (enabled) "Enabled '$serverName'" else "Disabled '$serverName'"
        }
    }

    fun saveMcpServer(spec: com.example.gemini.domain.model.McpServerSpec, rawJsonString: String? = null) {
        viewModelScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }

                val serverObj: org.json.JSONObject = if (!rawJsonString.isNullOrBlank()) {
                    org.json.JSONObject(rawJsonString)
                } else {
                    org.json.JSONObject().apply {
                        if (spec.serverUrl.isNotBlank()) {
                            put("serverUrl", spec.serverUrl)
                            if (spec.headers.isNotEmpty()) {
                                val hObj = org.json.JSONObject()
                                spec.headers.forEach { (k, v) -> hObj.put(k, v) }
                                put("headers", hObj)
                            }
                        } else {
                            put("command", spec.command)
                            if (spec.args.isNotEmpty()) {
                                val argsArr = org.json.JSONArray()
                                spec.args.forEach { argsArr.put(it) }
                                put("args", argsArr)
                            }
                            if (spec.cwd.isNotBlank()) {
                                put("cwd", spec.cwd)
                            }
                            val envMap = spec.env.toMutableMap()
                            // Ensure Termux stdio binaries (npx, python3, etc.) have proper loader and PATH on Android
                            if (!envMap.containsKey("LD_PRELOAD")) {
                                envMap["LD_PRELOAD"] = "/data/data/com.termux/files/usr/lib/libtermux-exec.so"
                            }
                            if (!envMap.containsKey("PATH")) {
                                envMap["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin"
                            }
                            if (envMap.isNotEmpty()) {
                                val envObj = org.json.JSONObject()
                                envMap.forEach { (k, v) -> envObj.put(k, v) }
                                put("env", envObj)
                            }
                        }
                        if (spec.disabled) {
                            put("disabled", true)
                        }
                    }
                }
                serversObj.put(spec.serverName, serverObj)

                val success = com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                if (!success) {
                    _mcpErrorMessage.value = "Failed to save configuration to mcp_config.json"
                } else {
                    val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                    agyHubClient.refreshMcpServers(hubUrl)
                    loadMcpServers()
                    _mcpStatusMessage.value = "Saved '${spec.serverName}'. Tap Refresh on the server to connect."
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "saveMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun deleteMcpServer(serverName: String) {
        viewModelScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                // Instantly remove from local list for snappy UI
                _mcpServers.value = _mcpServers.value.filterNot { it.name.equals(serverName, ignoreCase = true) }

                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers")
                if (serversObj != null && serversObj.has(serverName)) {
                    serversObj.remove(serverName)
                    com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }

                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                agyHubClient.refreshMcpServers(hubUrl)
                loadMcpServers()
                _mcpStatusMessage.value = "Removed '$serverName'."
            } catch (e: Exception) {
                Log.e("ChatViewModel", "deleteMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        persistentStreamJob?.cancel()
        streamingJob?.cancel()
    }
}

private data class ExecutedToolResult(
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val status: String
)

