package com.example.gemini.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val apiService = AntigravityApiService()
    private val agyBridgeService = com.example.gemini.data.remote.AgyBridgeService()
    private val agyHubClient = com.example.gemini.data.remote.AgyHubClient()
    private val oauthManager = GoogleOAuthManager()
    private val automationExecutor = com.example.gemini.data.automation.AutomationToolExecutor(application)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

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

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            _isUploadingAttachment.value = true
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
                        val att = res.getOrThrow().copy(localUri = uri.toString())
                        _attachments.value = _attachments.value + att
                    } else {
                        val isImg = fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".webp", true) || fileName.endsWith(".jpeg", true)
                        val fallback = com.example.gemini.domain.model.ChatAttachment(
                            name = fileName,
                            path = uri.toString(),
                            isImage = isImg,
                            localUri = uri.toString(),
                            size = fileSize
                        )
                        _attachments.value = _attachments.value + fallback
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to add attachment from uri: ${e.message}")
            } finally {
                _isUploadingAttachment.value = false
            }
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
    val useSshTerminal = authPrefs.useSshTerminal
    val termuxSshHost = authPrefs.termuxSshHost
    val termuxSshPort = authPrefs.termuxSshPort
    val termuxSshUser = authPrefs.termuxSshUser
    val termuxSshPass = authPrefs.termuxSshPass
    val isLocalToolsEnabled = authPrefs.isLocalToolsEnabled
    val isLocalToolsInstalled = authPrefs.isLocalToolsInstalled

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
    private val currentPlannerThoughts = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val currentPlannerResponses = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val currentActiveToolsMap = java.util.concurrent.ConcurrentHashMap<String, com.example.gemini.domain.model.ToolCall>()
    private val currentTurnToolMarkers = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun registerOrUpdateTurnTool(
        tool: com.example.gemini.domain.model.ToolCall,
        stepIndex: Int
    ) {
        val normName = com.example.gemini.data.remote.AgyHubClient.normalizeToolName(tool.name)

        val existingEntry = currentActiveToolsMap.entries.find { (k, v) ->
            k == tool.id || (
                com.example.gemini.data.remote.AgyHubClient.normalizeToolName(v.name) == normName &&
                (v.command == tool.command || tool.command.isBlank() || v.command.isBlank() || tool.name == v.name) &&
                (v.status == "RUNNING" || v.status == "PENDING_APPROVAL" || tool.id == k)
            )
        }

        val targetId = existingEntry?.key ?: tool.id
        val unifiedTool = tool.copy(id = targetId, name = normName)

        if (existingEntry != null && existingEntry.key != tool.id) {
            currentActiveToolsMap.remove(tool.id)
        }
        currentActiveToolsMap[targetId] = unifiedTool

        val oldMarker = if (existingEntry != null && existingEntry.key != tool.id) "<!-- tool_call:${tool.id} -->" else null
        if (oldMarker != null) {
            val oldStep = currentTurnToolMarkers.entries.find { it.value == oldMarker }?.key
            if (oldStep != null) {
                currentTurnToolMarkers.remove(oldStep)
            }
        }
        val prevStepWithMarker = currentTurnToolMarkers.entries.find { it.value == "<!-- tool_call:$targetId -->" }?.key
        if (prevStepWithMarker == null) {
            currentTurnToolMarkers[stepIndex] = "<!-- tool_call:$targetId -->"
        }
    }

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

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

        viewModelScope.launch {
            refreshQuotas()
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

    fun syncAgyConversations() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _isLoadingConversation.value = true
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                agyHubClient.subscribeToSummaries(hubUrl).collect { summaries ->
                    if (summaries.isNotEmpty()) {
                        val currentMap = _conversations.value.associateBy { it.id }.toMutableMap()
                        for (conv in summaries) {
                            currentMap[conv.id] = conv
                            knownDaemonCascadeIds.add(conv.id)
                        }
                        _conversations.value = currentMap.values.sortedByDescending { it.updatedAt }
                        _isServerOnline.value = true
                        _conversationError.value = null
                        _isLoadingConversation.value = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "subscribeToSummaries failed: ${e.message}")
                _isServerOnline.value = false
                val rawErr = e.message ?: "Connection failed"
                val helpfulMsg = if (rawErr.contains("Connect", ignoreCase = true) || rawErr.contains("8090") || rawErr.contains("Failed to connect", ignoreCase = true)) {
                    "Cannot connect to Antigravity Hub on port 8090. Make sure 'agy --hub' is running."
                } else {
                    "Antigravity Hub unreachable: $rawErr"
                }
                _conversationError.value = helpfulMsg
                _isLoadingConversation.value = false
            }
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
        currentPlannerThoughts.clear()
        currentPlannerResponses.clear()
        currentActiveToolsMap.clear()
        currentTurnToolMarkers.clear()
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
        currentPlannerThoughts.clear()
        currentPlannerResponses.clear()
        currentActiveToolsMap.clear()
        currentTurnToolMarkers.clear()
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
            var isFirstChunk = true
            val seenToolStepKeys = mutableSetOf<String>()

            while (activeStreamConversationId == conversationId) {
                try {
                    _isServerOnline.value = true
                    agyHubClient.streamAgentStateUpdates(conversationId, hubUrl).collect { frameJson ->
                        if (activeStreamConversationId != conversationId) {
                            return@collect
                        }

                        val root = JSONObject(frameJson)
                        val update = root.optJSONObject("update") ?: return@collect
                        val status = update.optString("status", update.optString("executableStatus", ""))
                        val trajId = update.optString("trajectoryId", "")
                        if (trajId.isNotBlank()) {
                            currentTrajectoryId = trajId
                        }

                        val stepsUpdate = update.optJSONObject("mainTrajectoryUpdate")?.optJSONObject("stepsUpdate")
                        val stepsArr = stepsUpdate?.optJSONArray("steps")
                        val indices = stepsUpdate?.optJSONArray("indices")
                        val totalLength = stepsUpdate?.optInt("totalLength", stepsArr?.length() ?: totalStepsCount) ?: totalStepsCount
                        if (totalLength > totalStepsCount) {
                            totalStepsCount = totalLength
                        }

                        // Chunk 0: Initial full conversation sync
                        if (isFirstChunk) {
                            isFirstChunk = false

                            val (parsedMessages, isRunning, isWaiting) = withContext(Dispatchers.Default) {
                                if (stepsArr != null && stepsArr.length() > 0) {
                                    val parsed = agyHubClient.parseStepsArrayToChatMessages(stepsArr, conversationId)
                                    val lastStep = stepsArr.optJSONObject(stepsArr.length() - 1)
                                    val lastStatus = lastStep?.optString("status", "") ?: ""
                                    val running = status.contains("RUNNING", ignoreCase = true) ||
                                            lastStatus.contains("RUNNING", ignoreCase = true)
                                    val waiting = status.contains("WAITING", ignoreCase = true) ||
                                            lastStatus.contains("WAITING", ignoreCase = true)

                                    if (running || waiting) {
                                        var lastUserStepIdx = -1
                                        for (k in 0 until stepsArr.length()) {
                                            val st = stepsArr.optJSONObject(k) ?: continue
                                            val stType = st.optString("type", "")
                                            if (stType == "CORTEX_STEP_TYPE_USER_INPUT" || st.has("userInput")) {
                                                lastUserStepIdx = k
                                            }
                                        }
                                        val startTurnIdx = (lastUserStepIdx + 1).coerceAtLeast(0)
                                        for (k in startTurnIdx until stepsArr.length()) {
                                            val st = stepsArr.optJSONObject(k) ?: continue
                                            val stepInfo = st.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                                            val stepIndex = when {
                                                stepInfo?.has("stepIndex") == true -> stepInfo.getInt("stepIndex")
                                                st.has("stepIndex") -> st.getInt("stepIndex")
                                                else -> k
                                            }
                                            val tool = agyHubClient.extractToolCallFromStep(st, stepIndex, conversationId)
                                            if (tool != null) {
                                                registerOrUpdateTurnTool(tool, stepIndex)
                                            } else {
                                                val stepErr = agyHubClient.extractStepError(st)
                                                if (stepErr != null) {
                                                    val existing = currentPlannerResponses[stepIndex]
                                                    currentPlannerResponses[stepIndex] = if (existing != null) "$existing\n\n⚠️ $stepErr" else "⚠️ $stepErr"
                                                }
                                            }
                                            if (st.has("plannerResponse")) {
                                                val pr = st.getJSONObject("plannerResponse")
                                                val th = pr.optString("thinking", "")
                                                val resp = pr.optString("response", "")
                                                if (th.isNotBlank()) currentPlannerThoughts[stepIndex] = th
                                                if (resp.isNotBlank()) currentPlannerResponses[stepIndex] = resp
                                            }
                                        }
                                    }
                                    com.example.gemini.ui.chat.ChatFeedCache.prewarm(parsed)
                                    Triple(parsed, running, waiting)
                                } else {
                                    Triple(emptyList<ChatMessage>(), false, false)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (activeStreamConversationId != conversationId) return@withContext
                                _conversationError.value = null

                                // NEVER overwrite messages or cancel streaming if user is actively generating/sending a message!
                                if (!_isStreaming.value) {
                                    _messages.value = parsedMessages
                                    _isStreaming.value = isRunning && !isWaiting
                                    if (isRunning || isWaiting) {
                                        hasSeenTurnActivity = true
                                        val lastAssistant = parsedMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                        currentAssistantMsgId = lastAssistant?.id
                                    }
                                }
                                _isLoadingConversation.value = false
                            }
                            return@collect
                        }

                        // Check for execution errors in executorMetadatasUpdate
                        val executorMetas = update.optJSONObject("mainTrajectoryUpdate")
                            ?.optJSONObject("executorMetadatasUpdate")
                            ?.optJSONArray("executorMetadatas")
                        if (executorMetas != null) {
                            for (m in 0 until executorMetas.length()) {
                                val meta = executorMetas.optJSONObject(m) ?: continue
                                val err = meta.optString("executionError", "")
                                val execId = meta.optString("executionId", "")
                                if (err.isNotBlank() && !seenToolStepKeys.contains("exec-err-$execId")) {
                                    seenToolStepKeys.add("exec-err-$execId")
                                    hasSeenTurnActivity = true
                                    withContext(Dispatchers.Main) {
                                        if (activeStreamConversationId != conversationId) return@withContext
                                        val cur = _messages.value.find { it.id == currentAssistantMsgId }
                                            ?: _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
                                        if (cur != null) {
                                            val newContent = if (cur.content.isNotBlank()) "${cur.content}\n\n⚠️ $err" else "⚠️ $err"
                                            updateAssistantMessage(
                                                msgId = cur.id,
                                                content = newContent,
                                                thought = cur.thoughtText,
                                                toolCalls = cur.toolCalls,
                                                isStreaming = false,
                                                forceImmediate = true
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Process steps for active turn
                        var hasNewTurnSteps = false
                        if (stepsArr != null) {
                            for (i in 0 until stepsArr.length()) {
                                val s = stepsArr.optJSONObject(i) ?: continue
                                val stepType = s.optString("type", "")

                                // User input is NOT model activity — skip it!
                                if (stepType == "CORTEX_STEP_TYPE_USER_INPUT" || s.has("userInput")) {
                                    continue
                                }

                                val stepInfo = s.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                                val stepIndex = when {
                                    stepInfo?.has("stepIndex") == true -> stepInfo.getInt("stepIndex")
                                    indices != null && i < indices.length() -> indices.getInt(i)
                                    s.has("stepIndex") -> s.getInt("stepIndex")
                                    else -> i
                                }

                                // Skip steps belonging to previous turns
                                if (stepIndex < currentTurnStartStep) {
                                    continue
                                }

                                if (stepIndex >= totalStepsCount) {
                                    totalStepsCount = stepIndex + 1
                                }

                                hasNewTurnSteps = true

                                if (s.has("plannerResponse")) {
                                    val pr = s.getJSONObject("plannerResponse")
                                    val th = pr.optString("thinking", "")
                                    val resp = pr.optString("response", "")
                                    if (th.isNotBlank()) {
                                        currentPlannerThoughts[stepIndex] = th
                                        hasSeenTurnActivity = true
                                        hasStartedRunning = true
                                    }
                                    if (resp.isNotBlank()) {
                                        currentPlannerResponses[stepIndex] = resp
                                        hasSeenTurnActivity = true
                                        hasStartedRunning = true
                                    }
                                }

                                val tool = agyHubClient.extractToolCallFromStep(s, stepIndex, conversationId)
                                if (tool != null) {
                                    registerOrUpdateTurnTool(tool, stepIndex)
                                    hasSeenTurnActivity = true
                                    hasStartedRunning = true
                                } else {
                                    val stepErr = agyHubClient.extractStepError(s)
                                    if (stepErr != null) {
                                        val existing = currentPlannerResponses[stepIndex]
                                        currentPlannerResponses[stepIndex] = if (existing != null) "$existing\n\n⚠️ $stepErr" else "⚠️ $stepErr"
                                        hasSeenTurnActivity = true
                                        hasStartedRunning = true
                                    }
                                }
                            }
                        }

                        if (!isPromptInFlight) {
                            if (status.contains("RUNNING", ignoreCase = true)) {
                                hasStartedRunning = true
                                hasSeenTurnActivity = true
                                _isStreaming.value = true
                            } else if (status.contains("WAITING", ignoreCase = true)) {
                                hasStartedRunning = true
                                hasSeenTurnActivity = true
                            } else if (hasSeenTurnActivity) {
                                hasStartedRunning = true
                            }
                        }

                        val allIndices = (currentTurnToolMarkers.keys + currentPlannerResponses.keys).toSortedSet()
                        val textChunks = mutableListOf<String>()
                        for (idx in allIndices) {
                            val marker = currentTurnToolMarkers[idx]
                            val resp = currentPlannerResponses[idx]
                            if (marker != null && !resp.isNullOrBlank()) {
                                textChunks.add("$marker\n\n$resp")
                            } else if (marker != null) {
                                textChunks.add(marker)
                            } else if (!resp.isNullOrBlank()) {
                                textChunks.add(resp)
                            }
                        }
                        val combinedText = textChunks.joinToString("\n\n").trim()

                        val combinedThought = if (currentPlannerThoughts.isNotEmpty()) {
                            currentPlannerThoughts.toSortedMap().values.joinToString("\n\n").trim().takeIf { it.isNotBlank() }
                        } else null

                        val hasPendingApprovalTool = currentActiveToolsMap.values.any { it.status == "PENDING_APPROVAL" }
                        val isWaitingInteraction = status.contains("WAITING", ignoreCase = true) || hasPendingApprovalTool
                        val fullyIdle = update.optBoolean("fullyIdle", false)
                        val isStatusIdle = status == "CASCADE_RUN_STATUS_IDLE" ||
                                fullyIdle ||
                                status.contains("IDLE", ignoreCase = true) ||
                                status.contains("COMPLETED", ignoreCase = true)

                        val isTurnDone = isStatusIdle && !isWaitingInteraction && hasStartedRunning && !isPromptInFlight

                        withContext(Dispatchers.Main) {
                            if (activeStreamConversationId != conversationId) return@withContext

                            val targetId = currentAssistantMsgId
                                ?: _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.id

                            val isMsgStreaming = !isTurnDone && !isWaitingInteraction && _isStreaming.value

                            if (targetId != null) {
                                currentAssistantMsgId = targetId
                                val curMsg = _messages.value.find { it.id == targetId }
                                if (curMsg != null) {
                                    val newContent = if (combinedText.isNotBlank()) combinedText else curMsg.content
                                    val newThought = combinedThought ?: curMsg.thoughtText

                                    val mergedTools = LinkedHashMap<String, com.example.gemini.domain.model.ToolCall>()
                                    for (t in curMsg.toolCalls) {
                                        mergedTools[t.id] = t
                                    }
                                    for ((id, t) in currentActiveToolsMap) {
                                        val normTName = com.example.gemini.data.remote.AgyHubClient.normalizeToolName(t.name)
                                        if (t.status != "RUNNING" && t.status != "PENDING_APPROVAL") {
                                            mergedTools.entries.removeIf { (oldId, oldT) ->
                                                oldId != id && com.example.gemini.data.remote.AgyHubClient.normalizeToolName(oldT.name) == normTName &&
                                                    oldT.status == "RUNNING" &&
                                                    (oldT.command == t.command || oldT.command.isBlank() || t.command.isBlank())
                                            }
                                        }
                                        mergedTools[id] = t
                                    }
                                    val newTools = mergedTools.values.toList()

                                    updateAssistantMessage(
                                        msgId = targetId,
                                        content = newContent,
                                        thought = newThought,
                                        toolCalls = newTools,
                                        isStreaming = isMsgStreaming,
                                        forceImmediate = isTurnDone || isWaitingInteraction
                                    )
                                }
                            } else if (hasNewTurnSteps || combinedText.isNotBlank() || combinedThought != null || currentActiveToolsMap.isNotEmpty()) {
                                val newAssistantId = "assistant_${conversationId}_${System.currentTimeMillis()}"
                                currentAssistantMsgId = newAssistantId
                                val newAssistantMsg = ChatMessage(
                                    id = newAssistantId,
                                    conversationId = conversationId,
                                    role = MessageRole.ASSISTANT,
                                    content = combinedText,
                                    thoughtText = combinedThought,
                                    toolCalls = currentActiveToolsMap.values.toList(),
                                    isStreaming = isMsgStreaming
                                )
                                _messages.value = _messages.value + newAssistantMsg
                            }

                            if (isWaitingInteraction) {
                                _isStreaming.value = false
                            } else if (isTurnDone) {
                                if (targetId != null) {
                                    val finalMsg = _messages.value.find { it.id == targetId }
                                    if (finalMsg != null && finalMsg.content.isBlank() && finalMsg.toolCalls.isEmpty() && finalMsg.thoughtText.isNullOrBlank()) {
                                        updateAssistantMessage(
                                            msgId = targetId,
                                            content = "⚠️ The model stopped without returning a response. Please try sending your message again.",
                                            isStreaming = false,
                                            forceImmediate = true
                                        )
                                    }
                                }
                                _isStreaming.value = false
                                hasStartedRunning = false
                                hasSeenTurnActivity = false
                                isPromptInFlight = false
                                currentPlannerThoughts.clear()
                                currentPlannerResponses.clear()
                                currentActiveToolsMap.clear()
                                currentTurnToolMarkers.clear()
                                currentAssistantMsgId = null
                                refreshQuotas()
                                syncAgyConversations()
                            }
                        }
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

    fun sendMessage(content: String) {
        if ((content.isBlank() && _attachments.value.isEmpty()) || _isStreaming.value) return

        val currentAtts = _attachments.value
        val attText = if (currentAtts.isNotEmpty()) {
            val listStr = currentAtts.joinToString("\n") { att ->
                if (att.isImage) {
                    "[Attached Image: ${att.name}](file://${att.path})"
                } else {
                    "[Attached File: ${att.name}](file://${att.path})"
                }
            }
            if (content.isNotBlank()) "\n\n$listStr" else listStr
        } else ""

        val finalPrompt = (content.trim() + attText).trim()
        _attachments.value = emptyList()

        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = finalPrompt
        )

        var updatedConv = conv
        val isFirstUserMsg = _messages.value.none { it.role == MessageRole.USER }
        val isGenericTitle = updatedConv.title == "New Chat" || updatedConv.title == "Antigravity Chat" || updatedConv.title.isBlank()
        if (isGenericTitle || isFirstUserMsg) {
            val firstLine = finalPrompt.trim().lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            val cleanTitle = firstLine.take(40) + if (firstLine.length > 40) "..." else ""
            updatedConv = updatedConv.copy(title = cleanTitle)
        }

        _currentConversation.value = updatedConv

        val updatedList = _messages.value + userMsg
        _messages.value = updatedList

        viewModelScope.launch {
            _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
            executeStream(updatedConv, updatedList)
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
            // Remove this message and any subsequent messages so user can edit and send fresh
            val truncated = current.take(index)
            _messages.value = truncated
        }
        return targetMsg.content
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
        priorTextPrefix: String = ""
    ) {
        _isStreaming.value = true
        isPromptInFlight = true
        _bridgeStatusMessage.value = null

        val assistantMsgId = existingAssistantMsgId ?: UUID.randomUUID().toString()
        currentAssistantMsgId = assistantMsgId

        if (existingAssistantMsgId == null) {
            val assistantMsg = ChatMessage(
                id = assistantMsgId,
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = priorTextPrefix,
                toolCalls = existingToolCalls,
                isStreaming = true
            )
            _messages.value = _messages.value + assistantMsg
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
                currentPlannerThoughts.clear()
                currentPlannerResponses.clear()
                currentActiveToolsMap.clear()
                currentTurnToolMarkers.clear()

                var sendRes = agyHubClient.sendUserPrompt(
                    cascadeId = conv.id,
                    text = userPrompt,
                    modelEnum = modelEnum,
                    thinkingBudget = thinkingBudget,
                    autoExecutionPolicy = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
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
                                autoExecutionPolicy = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
                                hubUrl = hubUrl
                            )
                        }
                    }
                }

                if (sendRes.isSuccess) {
                    currentTurnStartStep = totalStepsCount
                    isPromptInFlight = false
                } else {
                    val err = sendRes.exceptionOrNull()?.message ?: "Failed to send message"
                    Log.e("ChatViewModel", "sendUserPrompt final error: $err")
                    withContext(Dispatchers.Main) {
                        isPromptInFlight = false
                        _isStreaming.value = false
                        hasSeenTurnActivity = false
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
        _isStreaming.value = false
        hasStartedRunning = false
        hasSeenTurnActivity = false
        currentPlannerThoughts.clear()
        currentPlannerResponses.clear()
        currentActiveToolsMap.clear()
        currentTurnToolMarkers.clear()
        currentAssistantMsgId = null
        _bridgeStatusMessage.value = null

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
        viewModelScope.launch {
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                agyHubClient.login(hubUrl)
                delay(1500)
                refreshQuotas(force = true)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "login failed: ${e.message}")
            }
        }
    }

    fun logout() {
        stopOAuthServer()
        pendingPkceVerifier = null
        viewModelScope.launch {
            try {
                val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
                agyHubClient.authLogout(hubUrl)
            } catch (_: Exception) {}
            authPrefs.clearAuth()
            _userEmail.value = null
            _projectId.value = "rising-fact-p41fc"
            _tier.value = "pro"
            _quotas.value = emptyList()
            _quotaSummary.value = null
            android.util.Log.d("GeminiApp", "[OAuth] Logged out successfully")
        }
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

    fun approveAndExecuteTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.id.substringAfterLast("_").toIntOrNull() ?: 0

        val runningToolCall = toolCall.copy(status = "RUNNING")
        val msg = _messages.value.find { it.id == messageId }
        if (msg != null) {
            val updatedToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) runningToolCall else it }
            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = updatedToolCalls,
                isStreaming = true,
                forceImmediate = true
            )
        }
        _isStreaming.value = true

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val res = agyHubClient.handleCascadeUserInteraction(
                cascadeId = conv.id,
                stepIndex = stepIndex,
                trajectoryId = currentTrajectoryId,
                allow = true,
                scope = "PERMISSION_SCOPE_ONCE",
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to approve tool"
                Log.e("ChatViewModel", "handleCascadeUserInteraction (approve) error: $err")
            }
        }
    }

    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.id.substringAfterLast("_").toIntOrNull() ?: 0

        val rejectedToolCall = toolCall.copy(
            status = "REJECTED",
            output = reason ?: "[Command execution was rejected by user]"
        )
        val msg = _messages.value.find { it.id == messageId }
        if (msg != null) {
            val updatedToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) rejectedToolCall else it }
            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = updatedToolCalls,
                isStreaming = false,
                forceImmediate = true
            )
        }

        viewModelScope.launch {
            val hubUrl = authPrefs.agyHubUrl.firstOrNull() ?: com.example.gemini.data.remote.AgyHubClient.DEFAULT_HUB_URL
            val res = agyHubClient.handleCascadeUserInteraction(
                cascadeId = conv.id,
                stepIndex = stepIndex,
                trajectoryId = currentTrajectoryId,
                allow = false,
                userDenyInstruction = reason ?: "User rejected this command.",
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Failed to reject tool"
                Log.e("ChatViewModel", "handleCascadeUserInteraction (deny) error: $err")
            }
        }
    }

    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        stopStreaming()
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
        val updatedToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) completedToolCall else it }

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
        val updatedToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) completedToolCall else it }

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

