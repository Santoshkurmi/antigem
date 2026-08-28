package com.example.gemini.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.local.LocalChatStorage
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.context.ContextCompactor
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = LocalChatStorage(application)
    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val apiService = AntigravityApiService()
    private val agyBridgeService = com.example.gemini.data.remote.AgyBridgeService()
    private val oauthManager = GoogleOAuthManager()
    private val automationExecutor = com.example.gemini.data.automation.AutomationToolExecutor(application)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val conversations: StateFlow<List<Conversation>> = storage.conversations

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

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _quotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    val quotas: StateFlow<List<ModelQuota>> = _quotas.asStateFlow()

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

    private val _showSummaryModelPicker = MutableStateFlow(false)
    val showSummaryModelPicker: StateFlow<Boolean> = _showSummaryModelPicker.asStateFlow()

    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(true)
    val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _terminatedToolDialog = MutableStateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?>(null)
    val terminatedToolDialog: StateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?> = _terminatedToolDialog.asStateFlow()

    fun dismissTerminatedToolDialog() {
        _terminatedToolDialog.value = null
    }

    fun openManualSummaryPicker() {
        _summaryError.value = null
        _showSummaryModelPicker.value = true
    }

    fun dismissSummaryModelPicker() {
        _showSummaryModelPicker.value = false
        _summaryError.value = null
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
        viewModelScope.launch {
            storage.saveConversation(updated)
        }
    }

    val chatFontScale = authPrefs.chatFontScale

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
        viewModelScope.launch {
            storage.saveMessages(conv.id, updated)
            storage.saveConversation(updatedConv)
        }
    }

    fun deleteSummaryMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.filterNot { it.id == messageId }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _postponedThreshold.value = null
        viewModelScope.launch {
            storage.saveMessages(conv.id, updated)
            storage.saveConversation(updatedConv)
        }
    }

    fun updateSummary(newSummary: String) {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            updateSummaryMessage(latestSummaryMsg.id, newSummary)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = if (newSummary.isBlank()) null else newSummary)
            _currentConversation.value = updated
            viewModelScope.launch {
                storage.saveConversation(updated)
            }
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
            viewModelScope.launch {
                storage.saveConversation(updated)
            }
        }
    }

    fun requestSummarization(modelId: String, onFinished: (() -> Unit)? = null) {
        val conv = _currentConversation.value ?: return
        val currentMessages = _messages.value
        if (currentMessages.isEmpty()) {
            _summaryError.value = "No messages in chat to summarize yet."
            _showSummaryModelPicker.value = true
            return
        }

        // Dismiss picker immediately so user returns to chat and sees live progress banner
        _showSummaryModelPicker.value = false
        _isSummarizing.value = true
        _summaryError.value = null

        val modelObj = _availableModels.value.find { it.id == modelId }
        _summarizingModelName.value = modelObj?.displayName ?: modelId

        // Insert a live streaming summary message at the current end of the chat!
        val liveSummaryMessage = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.SUMMARY,
            content = "",
            isStreaming = true
        )
        _messages.value = _messages.value + liveSummaryMessage

        viewModelScope.launch {
            val token = getValidAccessToken()
            if (token.isNullOrBlank()) {
                _summaryError.value = "Not logged in. Please sign in via Settings."
                _isSummarizing.value = false
                _messages.value = _messages.value.filterNot { it.id == liveSummaryMessage.id }
                _showSummaryModelPicker.value = true
                return@launch
            }

            val turnsToFilter = currentMessages.filter { it.role != MessageRole.SUMMARY }
            val windowLimit = _contextWindowLimit.value
            val (olderMessages, _) = ContextCompactor.splitHistory(turnsToFilter, windowLimit)
            val messagesToSummarize = if (olderMessages.isNotEmpty()) olderMessages else turnsToFilter

            android.util.Log.d("GeminiApp", "[ViewModel] Calling ContextCompactor.executeSummarization for conv: ${conv.id}")
            val result = ContextCompactor.executeSummarization(
                apiService = apiService,
                token = token,
                projectId = _projectId.value,
                modelId = modelId,
                messagesToSummarize = messagesToSummarize,
                existingSummary = conv.summary
            )

            _isSummarizing.value = false

            if (result.isSuccess) {
                val summarizationResult = result.getOrThrow()
                val newSummary = summarizationResult.summary
                val newTitle = summarizationResult.title?.takeIf { it.isNotBlank() } ?: conv.title
                android.util.Log.d("GeminiApp", "[ViewModel] Summarization succeeded! New Title: '$newTitle', updating summary message ${liveSummaryMessage.id}")
                val updatedMessages = _messages.value.map {
                    if (it.id == liveSummaryMessage.id) {
                        it.copy(content = newSummary, isStreaming = false)
                    } else it
                }
                _messages.value = updatedMessages

                val updatedConv = conv.copy(summary = newSummary, title = newTitle)
                _currentConversation.value = updatedConv
                _postponedThreshold.value = null
                _summaryError.value = null
                storage.saveMessages(conv.id, updatedMessages)
                storage.saveConversation(updatedConv)

                // If a user message was queued during summarization, dispatch it now!
                val pending = _pendingQueuedUserMessage.value
                _pendingQueuedUserMessage.value = null
                if (!pending.isNullOrBlank()) {
                    executeStream(updatedConv, updatedMessages)
                }

                onFinished?.invoke()
            } else {
                val err = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                android.util.Log.e("GeminiApp", "[ViewModel] Summarization failed: $err")
                _messages.value = _messages.value.filterNot { it.id == liveSummaryMessage.id }
                _summaryError.value = err
                _showSummaryModelPicker.value = true
            }
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
            all.filter { it.id in enabledSet }
        }
        _enabledModels.value = if (filtered.isNotEmpty()) filtered else all

        // If current selected model is not in enabled list, switch to first enabled model
        val currentSelected = _selectedModelId.value
        if (_enabledModels.value.isNotEmpty() && (currentSelected.isBlank() || _enabledModels.value.none { it.id == currentSelected })) {
            _enabledModels.value.firstOrNull()?.let {
                selectModel(it.id)
            }
        }
    }

    private var streamingJob: Job? = null
    private var serverJob: Job? = null
    var pendingPkceVerifier: String? = null

    init {
        viewModelScope.launch {
            // Instantly restore cached models and quotas from local storage
            authPrefs.cachedModelsJson.firstOrNull()?.let { modelsJson ->
                if (!modelsJson.isNullOrBlank()) {
                    try {
                        val cachedModels = json.decodeFromString<List<AiModel>>(modelsJson)
                        if (cachedModels.isNotEmpty()) {
                            _availableModels.value = cachedModels
                            recomputeEnabledModels()
                        }
                    } catch (_: Exception) {}
                }
            }
            authPrefs.cachedQuotasJson.firstOrNull()?.let { quotasJson ->
                if (!quotasJson.isNullOrBlank()) {
                    try {
                        val cachedQuotas = json.decodeFromString<List<ModelQuota>>(quotasJson)
                        _quotas.value = cachedQuotas
                    } catch (_: Exception) {}
                }
            }
        }

        viewModelScope.launch {
            storage.init()
            startNewChat()
            _isLoadingConversation.value = false

            authPrefs.userEmail.collect { _userEmail.value = it }
        }

        viewModelScope.launch {
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

    private val _bridgeStatusMessage = MutableStateFlow<String?>(null)
    val bridgeStatusMessage: StateFlow<String?> = _bridgeStatusMessage.asStateFlow()

    private val _isServerOnline = MutableStateFlow<Boolean?>(null)
    val isServerOnline: StateFlow<Boolean?> = _isServerOnline.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _activeInstances = MutableStateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>>(emptyList())
    val activeInstances: StateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>> = _activeInstances.asStateFlow()

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

    fun syncAgyConversations() {
        viewModelScope.launch {
            try {
                val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val isOnline = agyBridgeService.checkServerHealth(httpUrl)
                _isServerOnline.value = isOnline

                if (isOnline) {
                    refreshActiveInstances()
                    val res = agyBridgeService.fetchConversations(httpUrl)
                    if (res.isSuccess) {
                        val agyList = res.getOrThrow()
                        if (agyList.isNotEmpty()) {
                            storage.mergeAgyConversations(agyList)

                            // Background pre-fetch top 10 most recent conversations (if not in RAM)
                            val topRecent = agyList.take(10)
                            topRecent.forEach { summary ->
                                if (!messagesMemoryCache.containsKey(summary.id)) {
                                    val msgRes = agyBridgeService.fetchConversationMessages(summary.id, httpUrl)
                                    if (msgRes.isSuccess) {
                                        val msgs = msgRes.getOrThrow()
                                        messagesMemoryCache[summary.id] = msgs
                                        storage.saveMessages(summary.id, msgs)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("GeminiApp", "Background conversation sync failed (non-fatal): ${e.message}")
            }
        }
    }

    fun onProjectChanged(projectPath: String) {
        val projName = java.io.File(projectPath).name
        _bridgeStatusMessage.value = "Switching workspace to $projName & warming instance..."
        viewModelScope.launch {
            val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            val conv = _currentConversation.value
            val model = _selectedModelId.value.ifBlank { "gemini-3.7-flash-high" }
            agyBridgeService.prewarm(
                conversationId = conv?.id,
                model = model,
                workspaceDir = projectPath,
                httpBaseUrl = httpUrl
            )
            delay(1500)
            if (_bridgeStatusMessage.value?.startsWith("Switching workspace") == true) {
                _bridgeStatusMessage.value = null
            }
        }
    }

    private val messagesMemoryCache = java.util.concurrent.ConcurrentHashMap<String, List<ChatMessage>>()
    private var lastPrewarmedConvId: String? = null
    private var lastPrewarmedModelId: String? = null
    private var lastPrewarmedProjectPath: String? = null

    fun onUserStartedTyping() {
        val conv = _currentConversation.value ?: return
        val model = _selectedModelId.value.ifBlank { "gemini-3.7-flash-high" }
        val currentProject = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value
        val projPath = currentProject?.path

        if (lastPrewarmedConvId == conv.id && lastPrewarmedModelId == model && lastPrewarmedProjectPath == projPath) {
            return
        }
        lastPrewarmedConvId = conv.id
        lastPrewarmedModelId = model
        lastPrewarmedProjectPath = projPath

        viewModelScope.launch {
            val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
            agyBridgeService.prewarm(
                conversationId = conv.id,
                model = model,
                workspaceDir = projPath,
                httpBaseUrl = httpUrl
            )
        }
    }

    fun startNewChat() {
        val modelToUse = if (_selectedModelId.value.isNotBlank()) _selectedModelId.value else (_enabledModels.value.firstOrNull()?.id ?: "")
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelToUse,
            sessionId = UUID.randomUUID().toString()
        )
        _currentConversation.value = newConv
        _messages.value = emptyList()
        _conversationError.value = null
        _isLoadingConversation.value = false
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            _isLoadingConversation.value = true
            _conversationError.value = null
            try {
                val conv = storage.conversations.value.find { it.id == id }
                if (conv != null) {
                    _currentConversation.value = conv
                    _selectedModelId.value = conv.modelId

                    // Tier 1: In-RAM Cache
                    val inRam = messagesMemoryCache[id]
                    if (inRam != null) {
                        _messages.value = inRam
                        _isLoadingConversation.value = false
                        return@launch
                    }

                    // Check server health
                    val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                    val isOnline = agyBridgeService.checkServerHealth(httpUrl)
                    _isServerOnline.value = isOnline

                    if (isOnline) {
                        // Tier 2: Server fetch
                        val res = agyBridgeService.fetchConversationMessages(id, httpUrl)
                        if (res.isSuccess) {
                            val fetched = res.getOrThrow()
                            messagesMemoryCache[id] = fetched
                            storage.saveMessages(id, fetched)
                            _messages.value = fetched
                        } else {
                            // Fallback to local storage
                            val local = storage.getMessages(id)
                            if (local.isNotEmpty()) {
                                messagesMemoryCache[id] = local
                                _messages.value = local
                            } else {
                                _messages.value = emptyList()
                                _conversationError.value = "Failed to load conversation from server."
                            }
                        }
                    } else {
                        // Tier 3: Local Storage (Offline Mode)
                        val local = storage.getMessages(id)
                        if (local.isNotEmpty()) {
                            messagesMemoryCache[id] = local
                            _messages.value = local
                        } else {
                            _messages.value = emptyList()
                            _conversationError.value = "Antigravity daemon is offline and this conversation is not available in local cache."
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "Error selecting conversation: ${e.message}")
                _conversationError.value = "Error loading conversation: ${e.message}"
            } finally {
                _isLoadingConversation.value = false
            }
        }
    }

    fun deleteConversation(id: String) {
        messagesMemoryCache.remove(id)
        viewModelScope.launch {
            storage.deleteConversation(id)
            if (_currentConversation.value?.id == id) {
                val remaining = storage.conversations.value
                if (remaining.isNotEmpty()) {
                    selectConversation(remaining.first().id)
                } else {
                    startNewChat()
                }
            }
        }
    }

    fun updateConversationTitle(id: String, newTitle: String) {
        val conv = storage.conversations.value.find { it.id == id } ?: _currentConversation.value ?: return
        val updated = conv.copy(title = newTitle.trim())
        if (_currentConversation.value?.id == id) {
            _currentConversation.value = updated
        }
        viewModelScope.launch {
            storage.saveConversation(updated)
        }
    }

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        val modelName = AiModel.findInList(_enabledModels.value, modelId).displayName
        _bridgeStatusMessage.value = "Preparing $modelName instance..."
        val conv = _currentConversation.value
        if (conv != null) {
            val updated = conv.copy(modelId = modelId)
            _currentConversation.value = updated
            val existsInStorage = storage.conversations.value.any { it.id == conv.id }
            if (existsInStorage) {
                viewModelScope.launch {
                    storage.saveConversation(updated)
                    val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                    val currentProject = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value
                    agyBridgeService.prewarm(
                        conversationId = conv.id,
                        model = modelId,
                        workspaceDir = currentProject?.path,
                        httpBaseUrl = httpUrl
                    )
                    delay(1500)
                    if (_bridgeStatusMessage.value?.startsWith("Preparing") == true) {
                        _bridgeStatusMessage.value = null
                    }
                }
            } else {
                viewModelScope.launch {
                    delay(1500)
                    if (_bridgeStatusMessage.value?.startsWith("Preparing") == true) {
                        _bridgeStatusMessage.value = null
                    }
                }
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isStreaming.value) return

        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = content
        )

        var updatedConv = conv
        val isFirstUserMsg = _messages.value.none { it.role == MessageRole.USER }
        if (updatedConv.title == "New Chat" || updatedConv.title.isBlank() || isFirstUserMsg) {
            val firstLine = content.trim().lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            val cleanTitle = firstLine.take(40) + if (firstLine.length > 40) "..." else ""
            updatedConv = updatedConv.copy(title = cleanTitle)
        }

        _currentConversation.value = updatedConv

        val updatedList = _messages.value + userMsg
        _messages.value = updatedList
        messagesMemoryCache[updatedConv.id] = updatedList

        viewModelScope.launch {
            storage.saveConversation(updatedConv)
            storage.saveMessages(updatedConv.id, updatedList)
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
                    storage.saveMessages(conv.id, truncated)
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
                storage.saveMessages(conv.id, truncated)
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
            viewModelScope.launch {
                storage.saveMessages(conv.id, truncated)
            }
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

        val assistantMsgId = existingAssistantMsgId ?: UUID.randomUUID().toString()
        if (existingAssistantMsgId == null) {
            val assistantMsg = ChatMessage(
                id = assistantMsgId,
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = "",
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
                isStreaming = true
            )
        }

        val startTime = System.currentTimeMillis()
        val contentBuilder = StringBuilder(priorTextPrefix)
        var inThought = false
        val activeToolsMap = mutableMapOf<String, com.example.gemini.domain.model.ToolCall>()
        existingToolCalls.forEach { activeToolsMap[it.id] = it }

        val wsUrl = authPrefs.agyBridgeWsUrl.firstOrNull() ?: "ws://127.0.0.1:8080"
        val userPrompt = currentHistory.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val currentProject = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value

        _bridgeStatusMessage.value = "Connecting to Antigravity CLI..."

        streamingJob = viewModelScope.launch {
            try {
                agyBridgeService.streamPrompt(
                    prompt = userPrompt,
                    model = _selectedModelId.value.ifBlank { "gemini-3.7-flash-high" },
                    conversationId = conv.id,
                    workspaceDir = currentProject?.path,
                    wsUrl = wsUrl
                ).collect { event ->
                    _bridgeStatusMessage.value = null
                    when (event) {
                        is com.example.gemini.data.remote.AgyStreamEvent.ThoughtChunk -> {
                            if (!inThought) {
                                inThought = true
                                contentBuilder.append("\n<!-- thought -->\n")
                            }
                            contentBuilder.append(event.thought)
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = contentBuilder.toString(),
                                thought = null,
                                thoughtDuration = null,
                                toolCalls = activeToolsMap.values.toList(),
                                isStreaming = true
                            )
                        }
                        is com.example.gemini.data.remote.AgyStreamEvent.TextChunk -> {
                            if (inThought) {
                                inThought = false
                                contentBuilder.append("\n<!-- /thought -->\n")
                            }
                            contentBuilder.append(event.text)
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = contentBuilder.toString(),
                                thought = null,
                                thoughtDuration = null,
                                toolCalls = activeToolsMap.values.toList(),
                                isStreaming = true
                            )
                        }
                        is com.example.gemini.data.remote.AgyStreamEvent.ToolChunk -> {
                            if (inThought) {
                                inThought = false
                                contentBuilder.append("\n<!-- /thought -->\n")
                            }
                            activeToolsMap[event.tool.id] = event.tool
                            val marker = "<!-- tool_call:${event.tool.id} -->"
                            if (!contentBuilder.contains(marker)) {
                                contentBuilder.append("\n$marker\n")
                            }
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = contentBuilder.toString(),
                                thought = null,
                                thoughtDuration = null,
                                toolCalls = activeToolsMap.values.toList(),
                                isStreaming = true
                            )
                        }
                        is com.example.gemini.data.remote.AgyStreamEvent.Completed -> {
                            if (inThought) {
                                inThought = false
                                contentBuilder.append("\n<!-- /thought -->\n")
                            }
                            _isStreaming.value = false
                            _bridgeStatusMessage.value = null
                            val finalContent = event.fullResponse?.takeIf { it.isNotBlank() } ?: contentBuilder.toString()
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = finalContent,
                                thought = null,
                                thoughtDuration = null,
                                toolCalls = activeToolsMap.values.toList(),
                                isStreaming = false,
                                tokenUsage = event.tokenUsage
                            )
                            storage.saveMessages(conv.id, _messages.value)
                        }
                        is com.example.gemini.data.remote.AgyStreamEvent.Error -> {
                            if (inThought) {
                                inThought = false
                                contentBuilder.append("\n<!-- /thought -->\n")
                            }
                            _isStreaming.value = false
                            _bridgeStatusMessage.value = null
                            val errText = if (contentBuilder.isNotEmpty()) {
                                "${contentBuilder}\n\n⚠️ ${event.message}"
                            } else {
                                "⚠️ ${event.message}"
                            }
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = errText,
                                thought = null,
                                thoughtDuration = null,
                                toolCalls = activeToolsMap.values.toList(),
                                isStreaming = false
                            )
                            storage.saveMessages(conv.id, _messages.value)
                        }
                    }
                }
            } catch (e: Exception) {
                if (inThought) {
                    inThought = false
                    contentBuilder.append("\n<!-- /thought -->\n")
                }
                _isStreaming.value = false
                _bridgeStatusMessage.value = null
                updateAssistantMessage(
                    msgId = assistantMsgId,
                    content = if (contentBuilder.isNotEmpty()) "$contentBuilder\n\n⚠️ ${e.message}" else "⚠️ Stream error: ${e.message}",
                    thought = null,
                    thoughtDuration = null,
                    toolCalls = activeToolsMap.values.toList(),
                    isStreaming = false
                )
                storage.saveMessages(conv.id, _messages.value)
            } finally {
                messagesMemoryCache[conv.id] = _messages.value
                refreshActiveInstances()
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
        if (!forceImmediate && isStreaming && (now - lastStreamUpdateTime < 200)) {
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
                    viewModelScope.launch {
                        storage.saveConversation(updated)
                    }
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
            _currentConversation.value?.let { messagesMemoryCache[it.id] = list }
            android.util.Log.d("PERF_TRACE", "🌊 [Streaming Emit] ID=${msgId.take(8)}, len=${content.length}, isStreaming=$isStreaming")
        }
    }

    fun stopStreaming() {
        agyBridgeService.abort()
        streamingJob?.cancel()
        _isStreaming.value = false
        val conv = _currentConversation.value ?: return
        val list = _messages.value.map {
            if (it.isStreaming) it.copy(isStreaming = false) else it
        }
        _messages.value = list
        viewModelScope.launch {
            storage.saveMessages(conv.id, list)
        }
    }

    fun refreshQuotas() {
        viewModelScope.launch {
            _isRefreshingModels.value = true
            try {
                val httpUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val res = agyBridgeService.fetchModels(httpUrl)
                if (res.isSuccess) {
                    val models = res.getOrThrow()
                    if (models.isNotEmpty()) {
                        _availableModels.value = models
                        recomputeEnabledModels()
                        if (_selectedModelId.value.isBlank() || !models.any { it.id == _selectedModelId.value }) {
                            _selectedModelId.value = models.firstOrNull { it.id.contains("flash-high", ignoreCase = true) }?.id
                                ?: models.first().id
                        }
                    }
                    try {
                        val modelsStr = json.encodeToString(models)
                        authPrefs.saveCachedModelsAndQuotas(modelsStr, "[]")
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "Error refreshing models from bridge: ${e.message}")
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

    fun logout() {
        stopOAuthServer()
        pendingPkceVerifier = null
        viewModelScope.launch {
            authPrefs.clearAuth()
            _userEmail.value = null
            _projectId.value = "rising-fact-p41fc"
            _tier.value = "pro"
            _quotas.value = emptyList()
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
        val msg = _messages.value.find { it.id == messageId } ?: return

        val runningToolCall = toolCall.copy(status = "RUNNING")
        val updatedToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) runningToolCall else it }

        updateAssistantMessage(
            msgId = messageId,
            content = msg.content,
            thought = msg.thoughtText ?: "",
            thoughtDuration = msg.thoughtDurationMs,
            toolCalls = updatedToolCalls,
            isStreaming = true
        )

        viewModelScope.launch {
            val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
            val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
            val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
            val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

            val resultSession = com.example.gemini.data.ssh.TermuxSshManager.executeCommand(
                command = toolCall.command,
                host = host,
                port = port,
                user = user,
                pass = pass,
                customCmdId = toolCall.id
            )

            val finalStatus = if (resultSession.status == com.example.gemini.data.ssh.CommandStatus.TERMINATED) {
                "TERMINATED"
            } else if (resultSession.exitCode == 0) {
                "SUCCESS"
            } else {
                "FAILED"
            }

            val completedToolCall = runningToolCall.copy(
                status = finalStatus,
                output = resultSession.output,
                exitCode = resultSession.exitCode,
                durationMs = resultSession.durationMs
            )
            val finalToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) completedToolCall else it }

            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = finalToolCalls,
                isStreaming = false
            )
            storage.saveMessages(conv.id, _messages.value)

            if (finalStatus != "TERMINATED") {
                val currentHistory = _messages.value.filter { it.id != messageId }
                val syntheticHistory = currentHistory + listOf(
                    ChatMessage(
                        conversationId = conv.id,
                        role = MessageRole.ASSISTANT,
                        content = "<execute_command>${toolCall.command}</execute_command>"
                    ),
                    ChatMessage(
                        conversationId = conv.id,
                        role = MessageRole.USER,
                        content = "[Terminal Output for `${toolCall.command}` (exit: ${resultSession.exitCode ?: 0})]:\n```\n${resultSession.output.ifEmpty { "(No output)" }}\n```"
                    )
                )

                executeStream(
                    conv = conv,
                    currentHistory = syntheticHistory,
                    existingAssistantMsgId = messageId,
                    existingToolCalls = finalToolCalls,
                    priorTextPrefix = msg.content
                )
            }
        }
    }

    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) {
        val conv = _currentConversation.value ?: return
        val msg = _messages.value.find { it.id == messageId } ?: return

        val rejectedToolCall = toolCall.copy(
            status = "REJECTED",
            output = reason ?: "[Command execution was rejected by user]"
        )
        val finalToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) rejectedToolCall else it }

        updateAssistantMessage(
            msgId = messageId,
            content = msg.content,
            thought = msg.thoughtText ?: "",
            thoughtDuration = msg.thoughtDurationMs,
            toolCalls = finalToolCalls,
            isStreaming = false
        )

        viewModelScope.launch {
            storage.saveMessages(conv.id, _messages.value)

            val currentHistory = _messages.value.filter { it.id != messageId }
            val syntheticHistory = currentHistory + listOf(
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = "<execute_command>${toolCall.command}</execute_command>"
                ),
                ChatMessage(
                    conversationId = conv.id,
                    role = MessageRole.USER,
                    content = "[Terminal Command `${toolCall.command}` was rejected by user${if (reason.isNullOrBlank()) "" else ": $reason"}]"
                )
            )

            executeStream(
                conv = conv,
                currentHistory = syntheticHistory,
                existingAssistantMsgId = messageId,
                existingToolCalls = finalToolCalls,
                priorTextPrefix = msg.content
            )
        }
    }

    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        com.example.gemini.data.ssh.TermuxSshManager.killCommand(toolCall.id)

        val conv = _currentConversation.value ?: return
        val msg = _messages.value.find { it.id == messageId } ?: return

        val terminatedToolCall = toolCall.copy(
            status = "TERMINATED",
            exitCode = 130,
            output = (toolCall.output + "\n\n[Process terminated by user]").trim()
        )
        val finalToolCalls = msg.toolCalls.map { if (it.id == toolCall.id) terminatedToolCall else it }

        updateAssistantMessage(
            msgId = messageId,
            content = msg.content,
            thought = msg.thoughtText ?: "",
            thoughtDuration = msg.thoughtDurationMs,
            toolCalls = finalToolCalls,
            isStreaming = false
        )

        viewModelScope.launch {
            storage.saveMessages(conv.id, _messages.value)
        }

        // Show interactive action dialog
        _terminatedToolDialog.value = Pair(terminatedToolCall, messageId)
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
            storage.saveMessages(conv.id, _messages.value)

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
            storage.saveMessages(conv.id, _messages.value)

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
}
