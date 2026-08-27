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
                val newSummary = result.getOrThrow()
                android.util.Log.d("GeminiApp", "[ViewModel] Summarization succeeded! Updating summary message ${liveSummaryMessage.id}")
                val updatedMessages = _messages.value.map {
                    if (it.id == liveSummaryMessage.id) {
                        it.copy(content = newSummary, isStreaming = false)
                    } else it
                }
                _messages.value = updatedMessages

                val updatedConv = conv.copy(summary = newSummary)
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
        _isLoadingConversation.value = false
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            _isLoadingConversation.value = true
            _messages.value = emptyList()
            try {
                val conv = storage.conversations.value.find { it.id == id }
                if (conv != null) {
                    val msgs = storage.getMessages(id)
                    _currentConversation.value = conv
                    _selectedModelId.value = conv.modelId
                    _messages.value = msgs
                }
            } finally {
                _isLoadingConversation.value = false
            }
        }
    }

    fun deleteConversation(id: String) {
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
        val conv = _currentConversation.value
        if (conv != null) {
            val updated = conv.copy(modelId = modelId)
            _currentConversation.value = updated
            val existsInStorage = storage.conversations.value.any { it.id == conv.id }
            if (existsInStorage) {
                viewModelScope.launch {
                    storage.saveConversation(updated)
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

        _currentConversation.value = conv

        val updatedList = _messages.value + userMsg
        _messages.value = updatedList

        viewModelScope.launch {
            storage.saveConversation(conv)
            storage.saveMessages(conv.id, updatedList)

            // If summarization is currently in progress, mark message as pending and queue it
            if (_isSummarizing.value) {
                _pendingQueuedUserMessage.value = content
                return@launch
            }

            // Check if context window limit is exceeded and no summary exists yet
            val threshold = _postponedThreshold.value ?: _contextWindowLimit.value
            val needsSummary = updatedList.size > threshold && conv.summary.isNullOrBlank()

            if (needsSummary) {
                val prefModel = _summaryModelIdPref.value
                val modelToUse = if (prefModel != "always_ask" && _availableModels.value.any { it.id == prefModel }) {
                    prefModel
                } else {
                    _availableModels.value.firstOrNull { it.id.contains("flash", ignoreCase = true) }?.id ?: _selectedModelId.value
                }

                _pendingQueuedUserMessage.value = content
                requestSummarization(modelToUse)
            } else {
                executeStream(conv, updatedList)
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
        val token = getValidAccessToken(forceRefresh = isRetryAfterRefresh)
        android.util.Log.d("GeminiApp", "[ViewModel] executeStream called. Model: ${_selectedModelId.value}, History size: ${currentHistory.size}, Token present: ${!token.isNullOrBlank()}")
        if (token.isNullOrBlank()) {
            val errorMsg = ChatMessage(
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = "⚠️ Please connect your Google account in Settings to use Antigravity models."
            )
            _messages.value = _messages.value + errorMsg
            storage.saveMessages(conv.id, _messages.value)
            return
        }

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
        val thoughtBuilder = StringBuilder()
        val textBuilder = StringBuilder()
        var thoughtCompletedAt: Long? = null

        // Apply context compaction: keep recent messages verbatim, older ones represented by executive summary
        val windowLimit = _contextWindowLimit.value
        val latestSummaryMsg = currentHistory.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        val summaryIndex = if (latestSummaryMsg != null) currentHistory.indexOf(latestSummaryMsg) else -1

        val (summary, compactHistory) = if (latestSummaryMsg != null && summaryIndex >= 0) {
            val messagesAfterSummary = currentHistory.drop(summaryIndex + 1).filter { it.role != MessageRole.SUMMARY }
            Pair(latestSummaryMsg.content, messagesAfterSummary)
        } else if (!conv.summary.isNullOrBlank()) {
            val (_, recent) = ContextCompactor.splitHistory(currentHistory.filter { it.role != MessageRole.SUMMARY }, windowLimit)
            Pair(conv.summary, recent)
        } else {
            Pair(null, currentHistory.filter { it.role != MessageRole.SUMMARY })
        }

        val isTerminalEnabled = authPrefs.isTerminalToolEnabled.firstOrNull() ?: false
        val isAutoExecute = authPrefs.isAutoExecuteTerminal.firstOrNull() ?: true
        val isWebSearchEnabled = authPrefs.isWebSearchToolEnabled.firstOrNull() ?: true
        val isWebReaderEnabled = authPrefs.isWebReaderToolEnabled.firstOrNull() ?: true
        val isChoicesToolEnabled = authPrefs.isChoicesToolEnabled.firstOrNull() ?: true
        val isFileToolEnabled = authPrefs.isFileToolEnabled.firstOrNull() ?: false
        val isAutomationToolEnabled = authPrefs.isAutomationToolEnabled.firstOrNull() ?: false
        val isMathToolEnabled = authPrefs.isMathToolEnabled.firstOrNull() ?: true

        val toolInstructionsList = mutableListOf<String>()
        if (isChoicesToolEnabled) {
            toolInstructionsList.add(
                "• Clarification & Multiple Choice Tool: When a user query is open-ended, broad, or requires requirement choices (e.g. creating websites, designing architecture, choosing stacks), DO NOT guess. Ask clarifying questions with structured choices (and nested follow-up questions up to 4 levels) in one go:\n" +
                "<tool_call name=\"ask_choices\">\n" +
                "{\n" +
                "  \"title\": \"Topic Title\",\n" +
                "  \"questions\": [\n" +
                "    {\n" +
                "      \"prompt\": \"Question 1?\",\n" +
                "      \"isMultiSelect\": false,\n" +
                "      \"options\": [\n" +
                "        {\n" +
                "          \"label\": \"Option A\",\n" +
                "          \"description\": \"Short info\",\n" +
                "          \"nestedQuestions\": [\n" +
                "            {\n" +
                "              \"prompt\": \"Sub question?\",\n" +
                "              \"options\": [{ \"label\": \"Sub-option 1\" }, { \"label\": \"Sub-option 2\" }]\n" +
                "            }\n" +
                "          ]\n" +
                "        },\n" +
                "        { \"label\": \"Option B\" }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "</tool_call>"
            )
        }
        if (isWebSearchEnabled) {
            toolInstructionsList.add("• Web Search Tool: To search Google / DuckDuckGo in real-time, output <tool_call name=\"web_search\">search query</tool_call>. You will receive top ranked results and snippets.")
        }
        if (isWebReaderEnabled) {
            toolInstructionsList.add("• Webpage Content Reader: To read and extract clean markdown from any URL, output <tool_call name=\"read_url\">https://example.com</tool_call>. You will receive the extracted page content.")
        }
        if (isTerminalEnabled) {
            toolInstructionsList.add("• Linux Shell Tool: You have access to a local Termux Linux shell via SSH. To execute commands, output <tool_call name=\"bash\">command_here</tool_call>.")
        }
        if (isFileToolEnabled) {
            toolInstructionsList.add(
                "• File System Tools: Read, write, and edit files on the user's device. Always read a file before writing to get its hash.\n" +
                "  - READ:  <tool_call name=\"read_file\">{\"path\": \"/abs/path\", \"start_line\": 1, \"end_line\": 50}</tool_call>\n" +
                "    Returns: file content with line numbers + hash. start_line/end_line are optional.\n" +
                "  - WRITE: <tool_call name=\"write_file\">{\"path\": \"/abs/path\", \"content\": \"full file content\", \"expected_hash\": \"<hash from read>\"}</tool_call>\n" +
                "    Use expected_hash=\"NEW\" for brand new files. ALWAYS provide the full file content.\n" +
                "  - EDIT:  <tool_call name=\"edit_file\">{\"path\": \"/abs/path\", \"old_str\": \"exact text to find\", \"new_str\": \"replacement\", \"expected_hash\": \"<hash from read>\"}</tool_call>\n" +
                "    EDIT is preferred for partial changes. old_str must be an EXACT match (including whitespace). If the file changed, you'll be told to re-read it first."
            )
        }
        if (isAutomationToolEnabled) {
            toolInstructionsList.add(
                "• Android Native Automation Tool: Inspect and control the user's Android device screen and applications natively without terminal or SSH.\n" +
                "  Output: <tool_call name=\"automation\">{\"action\": \"...\", ...}</tool_call>\n\n" +
                "  Actions available:\n" +
                "  1. analyze_screen       → Returns active foreground app, interactive UI tree with text labels, descriptions, and coordinates.\n" +
                "     Example: {\"action\": \"analyze_screen\"}\n" +
                "  2. tap                  → Click any button/element by text, description, or exact screen coordinates (x, y).\n" +
                "     Example: {\"action\": \"tap\", \"text\": \"Search\"} or {\"action\": \"tap\", \"x\": 540, \"y\": 960}\n" +
                "  3. type_text            → Type text into active or target input field.\n" +
                "     Example: {\"action\": \"type_text\", \"text\": \"Hello World\", \"target\": \"Search YouTube\"}\n" +
                "  4. scroll               → Scroll screen in any direction (\"down\", \"up\", \"left\", \"right\").\n" +
                "     Example: {\"action\": \"scroll\", \"direction\": \"down\"}\n" +
                "  5. launch_app           → Launch any app by name or package (e.g. \"YouTube\", \"Settings\", \"Spotify\", \"WhatsApp\").\n" +
                "     Example: {\"action\": \"launch_app\", \"name\": \"YouTube\"}\n" +
                "  6. press_key            → Trigger system navigation actions (\"back\", \"home\", \"recents\", \"notifications\", \"quick_settings\").\n" +
                "     Example: {\"action\": \"press_key\", \"key\": \"back\"}\n" +
                "  7. media_control        → Control active music/video playback (\"play\", \"pause\", \"play_pause\", \"next\", \"previous\", \"vol_up\", \"vol_down\", \"mute\").\n" +
                "     Example: {\"action\": \"media_control\", \"command\": \"play_pause\"}\n" +
                "  8. get_media_info       → Check if music/video is currently playing and current media volume.\n" +
                "     Example: {\"action\": \"get_media_info\"}\n" +
                "  9. list_apps            → List installed launchable apps on the device.\n" +
                "     Example: {\"action\": \"list_apps\", \"query\": \"music\"}\n" +
                "  10. get_running_apps    → Check recently used foreground apps.\n" +
                "     Example: {\"action\": \"get_running_apps\"}\n" +
                "  11. take_screenshot     → Capture high-res screen snapshot.\n" +
                "     Example: {\"action\": \"take_screenshot\"}\n\n" +
                "  Tip: When interacting with an app UI, always call 'analyze_screen' first to see visible buttons, then 'tap' or 'type_text'."
            )
        }
        if (isMathToolEnabled) {
            toolInstructionsList.add(
                "• Symja Computer Algebra System (CAS) Math Engine: You have an embedded, ultra-powerful symbolic & numeric mathematical engine.\n" +
                "  Output: <tool_call name=\"math\">expression</tool_call>\n" +
                "  Capabilities:\n" +
                "  - Symbolic Calculus: D(Sin(x)*Exp(x), x), Integrate(x^2*Cos(x), x), Limit(Sin(x)/x, x->0), Series(Exp(x), {x, 0, 5})\n" +
                "  - Equation & System Solving: Solve(x^2 - 5*x + 6 == 0, x), Solve({x + y == 10, x - y == 2}, {x, y}), Roots(...)\n" +
                "  - Algebra & Factorization: Factor(x^4 - 16), Simplify((x^3 - 1)/(x - 1)), Expand((x + y)^6), Apart(1/((x-1)*(x+2)))\n" +
                "  - Linear Algebra: Det({{1, 2}, {3, 4}}), Inverse({{1, 2}, {3, 4}}), Eigenvalues({{1, 2}, {2, 1}})\n" +
                "  - Arbitrary Precision & Numeric: N(Pi, 100), 1/3 + 1/7, FactorInteger(123456789), PrimeQ(999983)\n" +
                "  You will receive the exact symbolic result, numeric approximation, and rendered LaTeX formula."
            )
        }

        val baseToolInstruction = if (toolInstructionsList.isNotEmpty()) {
            "You have access to the following real-time tools:\n" +
            toolInstructionsList.joinToString("\n\n") +
            "\nWhen using a tool, explain what you are doing first, then output the <tool_call name=\"...\">payload</tool_call> block. You can use tools sequentially. Once a tool executes, you will receive the real results."
        } else null

        val currentProject = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value
        val currentTabPath = com.example.gemini.data.daemon.TermuxDaemonManager.activeTabPath.value

        val activeProjectContext = if (currentProject != null) {
            "\n\nActive IDE Project Context:\n" +
            "- Selected Project Name: ${currentProject.name}\n" +
            "- Selected Project Path: ${currentProject.path}\n" +
            "- Termux Project Folder: /data/data/com.termux/files/home/projects/${currentProject.name}\n" +
            if (!currentTabPath.isNullOrBlank()) "- Currently Open Active File in IDE Editor: $currentTabPath\n" else ""
        } else ""

        val isFirstTurn = _messages.value.filter { it.role == MessageRole.USER }.size <= 1
        val titlePrompt = if (isFirstTurn) {
            "\n\nConversation Title Requirement:\nAt the very beginning of your response, output a concise, descriptive 3-6 word title for this conversation enclosed in <chat_title>...</chat_title> (e.g. <chat_title>Quantum Mechanics Overview</chat_title>). Do not include quotes or punctuation in the tag."
        } else ""

        val combinedInstruction = (baseToolInstruction ?: "") + activeProjectContext + titlePrompt
        val finalToolInstruction = if (combinedInstruction.isNotBlank()) combinedInstruction else null

        streamingJob = viewModelScope.launch {
            apiService.streamGenerateContent(
                token = token,
                projectId = _projectId.value,
                modelId = _selectedModelId.value,
                sessionId = conv.sessionId,
                messages = compactHistory,
                summary = summary,
                thinkingBudget = _thinkingPreference.value.activeTokens,
                isThinkingEnabled = _thinkingPreference.value.isEnabled,
                toolInstruction = finalToolInstruction,
                customSystemPrompt = conv.customSystemPrompt
            ).collect { event ->
                when (event) {
                    is StreamEvent.ThoughtChunk -> {
                        thoughtBuilder.append(event.thought)
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = priorTextPrefix + textBuilder.toString(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = System.currentTimeMillis() - startTime,
                            toolCalls = existingToolCalls,
                            isStreaming = true
                        )
                    }
                    is StreamEvent.TextChunk -> {
                        if (thoughtCompletedAt == null && thoughtBuilder.isNotEmpty()) {
                            thoughtCompletedAt = System.currentTimeMillis()
                        }
                        textBuilder.append(event.text)
                        val duration = (thoughtCompletedAt ?: System.currentTimeMillis()) - startTime
                        val liveSanitized = sanitizeStreamingText(textBuilder.toString())
                        val liveContent = if (priorTextPrefix.isNotBlank()) {
                            if (liveSanitized.isNotBlank()) "$priorTextPrefix\n\n$liveSanitized" else priorTextPrefix
                        } else {
                            liveSanitized
                        }
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = liveContent,
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                            toolCalls = existingToolCalls,
                            isStreaming = true
                        )
                    }
                    is StreamEvent.Completed -> {
                        android.util.Log.d("GeminiApp", "[ViewModel] Stream completed. Text length: ${textBuilder.length}, Thought length: ${thoughtBuilder.length}")
                        _isStreaming.value = false
                        val duration = (thoughtCompletedAt ?: System.currentTimeMillis()) - startTime
                        val streamGeneratedText = textBuilder.toString()

                        val extractedTool = extractToolCall(streamGeneratedText)
                        if (extractedTool != null && extractedTool.payload.isNotEmpty()) {
                            val toolName = extractedTool.name
                            val payload = extractedTool.payload
                            val cleanPreamble = extractedTool.cleanPreamble

                            // 1. Clarification & Choices Tool
                            if ((toolName == "ask_choices" || toolName == "user_choice") && isChoicesToolEnabled) {
                                val choicesToolCall = com.example.gemini.domain.model.ToolCall(
                                    id = UUID.randomUUID().toString(),
                                    name = "ask_choices",
                                    command = payload,
                                    status = "AWAITING_CHOICE"
                                )
                                val toolCallsWithChoices = existingToolCalls + choicesToolCall
                                val choicesToolMarker = "<!-- tool_call:${choicesToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$choicesToolMarker" else "$priorTextPrefix\n\n$choicesToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$choicesToolMarker" else choicesToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithChoices,
                                    isStreaming = false
                                )
                                storage.saveMessages(conv.id, _messages.value)
                                return@collect
                            }

                            // 2. Web Search Tool
                            if ((toolName == "web_search" || toolName == "search") && isWebSearchEnabled) {
                                val runningToolCall = com.example.gemini.domain.model.ToolCall(
                                    name = "web_search",
                                    command = payload,
                                    status = "RUNNING"
                                )
                                val toolCallsWithRunning = existingToolCalls + runningToolCall
                                val runningToolMarker = "<!-- tool_call:${runningToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithRunning,
                                    isStreaming = false
                                )

                                viewModelScope.launch {
                                    val searchStartTime = System.currentTimeMillis()
                                    val searchResult = com.example.gemini.data.web.WebSearchManager.search(payload)
                                    val searchDuration = System.currentTimeMillis() - searchStartTime

                                    val isSuccess = searchResult.isSuccess
                                    val resultsList = searchResult.getOrNull() ?: emptyList()
                                    val outputFormatted = if (isSuccess && resultsList.isNotEmpty()) {
                                        buildString {
                                            resultsList.forEachIndexed { idx, res ->
                                                append("${idx + 1}. **${res.title}**\n")
                                                append("   URL: ${res.url}\n")
                                                append("   Snippet: ${res.snippet}\n\n")
                                            }
                                        }.trim()
                                    } else {
                                        searchResult.exceptionOrNull()?.localizedMessage ?: "(No search results found for '$payload')"
                                    }

                                    val completedToolCall = runningToolCall.copy(
                                        status = if (isSuccess) "SUCCESS" else "FAILED",
                                        output = outputFormatted,
                                        exitCode = if (isSuccess) 0 else 1,
                                        durationMs = searchDuration
                                    )
                                    val updatedToolCalls = existingToolCalls + completedToolCall

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = updatedToolCalls,
                                        isStreaming = true
                                    )
                                    storage.saveMessages(conv.id, _messages.value)

                                    val syntheticHistory = currentHistory + listOf(
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.ASSISTANT,
                                            content = "$cleanPreamble\n<tool_call name=\"web_search\">$payload</tool_call>"
                                        ),
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.USER,
                                            content = "[Web Search Results for \"$payload\"]:\n$outputFormatted"
                                        )
                                    )

                                    executeStream(
                                        conv = conv,
                                        currentHistory = syntheticHistory,
                                        existingAssistantMsgId = assistantMsgId,
                                        existingToolCalls = updatedToolCalls,
                                        priorTextPrefix = currentTextAccumulated
                                    )
                                }
                                return@collect
                            }

                            // 3. Web Reader Tool
                            if ((toolName == "read_url" || toolName == "web_reader") && isWebReaderEnabled) {
                                val runningToolCall = com.example.gemini.domain.model.ToolCall(
                                    name = "read_url",
                                    command = payload,
                                    status = "RUNNING"
                                )
                                val toolCallsWithRunning = existingToolCalls + runningToolCall
                                val runningToolMarker = "<!-- tool_call:${runningToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithRunning,
                                    isStreaming = false
                                )

                                viewModelScope.launch {
                                    val readStartTime = System.currentTimeMillis()
                                    val readResult = com.example.gemini.data.web.WebSearchManager.readUrl(payload)
                                    val readDuration = System.currentTimeMillis() - readStartTime

                                    val isSuccess = readResult.isSuccess
                                    val pageContent = readResult.getOrNull()
                                    val outputFormatted = if (isSuccess && pageContent != null) {
                                        "# ${pageContent.title}\n\n${pageContent.text}"
                                    } else {
                                        readResult.exceptionOrNull()?.localizedMessage ?: "(Could not fetch content from $payload)"
                                    }

                                    val completedToolCall = runningToolCall.copy(
                                        status = if (isSuccess) "SUCCESS" else "FAILED",
                                        output = outputFormatted,
                                        exitCode = if (isSuccess) 0 else 1,
                                        durationMs = readDuration
                                    )
                                    val updatedToolCalls = existingToolCalls + completedToolCall

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = updatedToolCalls,
                                        isStreaming = true
                                    )
                                    storage.saveMessages(conv.id, _messages.value)

                                    val syntheticHistory = currentHistory + listOf(
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.ASSISTANT,
                                            content = "$cleanPreamble\n<tool_call name=\"read_url\">$payload</tool_call>"
                                        ),
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.USER,
                                            content = "[Webpage Content for \"$payload\"]:\n$outputFormatted"
                                        )
                                    )

                                    executeStream(
                                        conv = conv,
                                        currentHistory = syntheticHistory,
                                        existingAssistantMsgId = assistantMsgId,
                                        existingToolCalls = updatedToolCalls,
                                        priorTextPrefix = currentTextAccumulated
                                    )
                                }
                                return@collect
                            }

                            // 4. Linux Shell Bash Tool
                            if ((toolName == "bash" || toolName == "execute_command" || toolName == "terminal") && isTerminalEnabled) {
                                val toolCallId = UUID.randomUUID().toString()

                                if (isAutoExecute) {
                                    val runningToolCall = com.example.gemini.domain.model.ToolCall(
                                        id = toolCallId,
                                        name = "bash",
                                        command = payload,
                                        status = "RUNNING"
                                    )
                                    val toolCallsWithRunning = existingToolCalls + runningToolCall
                                    val runningToolMarker = "<!-- tool_call:${runningToolCall.id} -->"
                                    val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                        if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                    } else {
                                        if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                    }

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = toolCallsWithRunning,
                                        isStreaming = false
                                    )

                                    val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
                                    val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
                                    val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
                                    val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

                                    viewModelScope.launch {
                                        val resultSession = com.example.gemini.data.ssh.TermuxSshManager.executeCommand(
                                            command = payload,
                                            host = host,
                                            port = port,
                                            user = user,
                                            pass = pass,
                                            customCmdId = toolCallId
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
                                        val updatedToolCalls = existingToolCalls + completedToolCall

                                        updateAssistantMessage(
                                            msgId = assistantMsgId,
                                            content = currentTextAccumulated,
                                            thought = thoughtBuilder.toString(),
                                            thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                            toolCalls = updatedToolCalls,
                                            isStreaming = true
                                        )
                                        storage.saveMessages(conv.id, _messages.value)

                                        if (finalStatus != "TERMINATED") {
                                            val syntheticHistory = currentHistory + listOf(
                                                ChatMessage(
                                                    conversationId = conv.id,
                                                    role = MessageRole.ASSISTANT,
                                                    content = "$cleanPreamble\n<tool_call name=\"bash\">$payload</tool_call>"
                                                ),
                                                ChatMessage(
                                                    conversationId = conv.id,
                                                    role = MessageRole.USER,
                                                    content = "[Terminal Output for `$payload` (exit: ${resultSession.exitCode ?: 0})]:\n```\n${resultSession.output.ifEmpty { "(No output)" }}\n```"
                                                )
                                            )

                                            executeStream(
                                                conv = conv,
                                                currentHistory = syntheticHistory,
                                                existingAssistantMsgId = assistantMsgId,
                                                existingToolCalls = updatedToolCalls,
                                                priorTextPrefix = currentTextAccumulated
                                            )
                                        }
                                    }
                                    return@collect
                                } else {
                                    // Manual Confirmation Required (Auto-Execute Disabled)
                                    val pendingToolCall = com.example.gemini.domain.model.ToolCall(
                                        id = toolCallId,
                                        name = "bash",
                                        command = payload,
                                        status = "PENDING_APPROVAL"
                                    )
                                    val toolCallsWithPending = existingToolCalls + pendingToolCall
                                    val pendingToolMarker = "<!-- tool_call:${pendingToolCall.id} -->"
                                    val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                        if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$pendingToolMarker" else "$priorTextPrefix\n\n$pendingToolMarker"
                                    } else {
                                        if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$pendingToolMarker" else pendingToolMarker
                                    }

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = toolCallsWithPending,
                                        isStreaming = false
                                    )
                                    storage.saveMessages(conv.id, _messages.value)
                                    return@collect
                                }
                            }

                            // 5. File System Tools — read_file / write_file / edit_file
                            if ((toolName == "read_file" || toolName == "write_file" || toolName == "edit_file") && isFileToolEnabled) {
                                val runningToolCall = com.example.gemini.domain.model.ToolCall(
                                    name = toolName,
                                    command = payload,
                                    status = "RUNNING"
                                )
                                val toolCallsWithRunning = existingToolCalls + runningToolCall
                                val runningToolMarker = "<!-- tool_call:${runningToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithRunning,
                                    isStreaming = false
                                )

                                val fileHost = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
                                val filePort = authPrefs.termuxSshPort.firstOrNull() ?: 8022
                                val fileUser = authPrefs.termuxSshUser.firstOrNull() ?: "root"
                                val filePass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

                                viewModelScope.launch {
                                    val fileStartTime = System.currentTimeMillis()
                                    val (outputFormatted, isSuccess) = try {
                                        when (toolName) {
                                            "read_file" -> {
                                                val p = com.example.gemini.data.file.FileToolExecutor.parseReadPayload(payload)
                                                val res = com.example.gemini.data.file.FileToolExecutor.readFile(p.path, fileHost, filePort, fileUser, filePass, p.startLine, p.endLine)
                                                if (res.isSuccess) Pair(com.example.gemini.data.file.FileToolExecutor.formatReadOutput(res.getOrThrow()), true)
                                                else Pair("Error: ${res.exceptionOrNull()?.message}", false)
                                            }
                                            "write_file" -> {
                                                val p = com.example.gemini.data.file.FileToolExecutor.parseWritePayload(payload)
                                                val res = com.example.gemini.data.file.FileToolExecutor.writeFile(p.path, p.content, p.expectedHash, fileHost, filePort, fileUser, filePass)
                                                if (res.isSuccess) Pair(com.example.gemini.data.file.FileToolExecutor.formatWriteOutput(res.getOrThrow()), true)
                                                else Pair("Error: ${res.exceptionOrNull()?.message}", false)
                                            }
                                            "edit_file" -> {
                                                val p = com.example.gemini.data.file.FileToolExecutor.parseEditPayload(payload)
                                                val res = com.example.gemini.data.file.FileToolExecutor.editFile(p.path, p.oldStr, p.newStr, p.expectedHash, fileHost, filePort, fileUser, filePass, p.replaceAll)
                                                if (res.isSuccess) Pair(com.example.gemini.data.file.FileToolExecutor.formatEditOutput(res.getOrThrow()), res.getOrThrow().replaced)
                                                else Pair("Error: ${res.exceptionOrNull()?.message}", false)
                                            }
                                            else -> Pair("Unknown file tool: $toolName", false)
                                        }
                                    } catch (e: Exception) {
                                        Pair("Error: ${e.message}", false)
                                    }
                                    val fileDuration = System.currentTimeMillis() - fileStartTime

                                    val completedToolCall = runningToolCall.copy(
                                        status = if (isSuccess) "SUCCESS" else "FAILED",
                                        output = outputFormatted,
                                        exitCode = if (isSuccess) 0 else 1,
                                        durationMs = fileDuration
                                    )
                                    val updatedToolCalls = existingToolCalls + completedToolCall

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = updatedToolCalls,
                                        isStreaming = true
                                    )
                                    storage.saveMessages(conv.id, _messages.value)

                                    val syntheticHistory = currentHistory + listOf(
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.ASSISTANT,
                                            content = "$cleanPreamble\n<tool_call name=\"$toolName\">$payload</tool_call>"
                                        ),
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.USER,
                                            content = "[File Tool Result: $toolName]:\n$outputFormatted"
                                        )
                                    )

                                    executeStream(
                                        conv = conv,
                                        currentHistory = syntheticHistory,
                                        existingAssistantMsgId = assistantMsgId,
                                        existingToolCalls = updatedToolCalls,
                                        priorTextPrefix = currentTextAccumulated
                                    )
                                }
                                return@collect
                            }

                            // 6. Native Android Automation Tool
                            if ((toolName == "automation" || toolName == "android_automation" || toolName == "ui_automation") && isAutomationToolEnabled) {
                                val runningToolCall = com.example.gemini.domain.model.ToolCall(
                                    name = "automation",
                                    command = payload,
                                    status = "RUNNING"
                                )
                                val toolCallsWithRunning = existingToolCalls + runningToolCall
                                val runningToolMarker = "<!-- tool_call:${runningToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithRunning,
                                    isStreaming = false
                                )

                                viewModelScope.launch {
                                    val autoStartTime = System.currentTimeMillis()
                                    val (outputFormatted, isSuccess) = automationExecutor.execute(payload)
                                    val autoDuration = System.currentTimeMillis() - autoStartTime

                                    val completedToolCall = runningToolCall.copy(
                                        status = if (isSuccess) "SUCCESS" else "FAILED",
                                        output = outputFormatted,
                                        exitCode = if (isSuccess) 0 else 1,
                                        durationMs = autoDuration
                                    )
                                    val updatedToolCalls = existingToolCalls + completedToolCall

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = updatedToolCalls,
                                        isStreaming = true
                                    )
                                    storage.saveMessages(conv.id, _messages.value)

                                    val syntheticHistory = currentHistory + listOf(
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.ASSISTANT,
                                            content = "$cleanPreamble\n<tool_call name=\"automation\">$payload</tool_call>"
                                        ),
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.USER,
                                            content = "[Android Automation Result]:\n$outputFormatted"
                                        )
                                    )

                                    executeStream(
                                        conv = conv,
                                        currentHistory = syntheticHistory,
                                        existingAssistantMsgId = assistantMsgId,
                                        existingToolCalls = updatedToolCalls,
                                        priorTextPrefix = currentTextAccumulated
                                    )
                                }
                                return@collect
                            }

                            // 6. Symja Computer Algebra System (CAS) Math Tool
                            if ((toolName == "math" || toolName == "cas" || toolName == "math_eval") && isMathToolEnabled) {
                                val mathToolCall = com.example.gemini.domain.model.ToolCall(
                                    name = "math",
                                    command = payload,
                                    status = "RUNNING"
                                )
                                val toolCallsWithRunning = existingToolCalls + mathToolCall
                                val runningToolMarker = "<!-- tool_call:${mathToolCall.id} -->"
                                val currentTextAccumulated = if (priorTextPrefix.isNotBlank()) {
                                    if (cleanPreamble.isNotBlank()) "$priorTextPrefix\n\n$cleanPreamble\n\n$runningToolMarker" else "$priorTextPrefix\n\n$runningToolMarker"
                                } else {
                                    if (cleanPreamble.isNotBlank()) "$cleanPreamble\n\n$runningToolMarker" else runningToolMarker
                                }

                                updateAssistantMessage(
                                    msgId = assistantMsgId,
                                    content = currentTextAccumulated,
                                    thought = thoughtBuilder.toString(),
                                    thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                    toolCalls = toolCallsWithRunning,
                                    isStreaming = false
                                )

                                viewModelScope.launch {
                                    val mathResult = com.example.gemini.data.math.SymjaCasManager.evaluate(payload)
                                    val isSuccess = mathResult.isSuccess
                                    val casRes = mathResult.getOrNull()

                                    val outputFormatted = if (isSuccess && casRes != null) {
                                        buildString {
                                            append("Result: ${casRes.resultText}\n")
                                            if (!casRes.latex.isNullOrBlank()) {
                                                append("LaTeX: $$${casRes.latex}$$\n")
                                            }
                                            if (!casRes.numericDecimal.isNullOrBlank()) {
                                                append("Numeric Approximation: ${casRes.numericDecimal}\n")
                                            }
                                        }.trim()
                                    } else {
                                        mathResult.exceptionOrNull()?.localizedMessage ?: "Evaluation error in CAS engine"
                                    }

                                    val completedToolCall = mathToolCall.copy(
                                        status = if (isSuccess) "SUCCESS" else "FAILED",
                                        output = outputFormatted,
                                        exitCode = if (isSuccess) 0 else 1,
                                        durationMs = casRes?.durationMs ?: 0L
                                    )
                                    val updatedToolCalls = existingToolCalls + completedToolCall

                                    updateAssistantMessage(
                                        msgId = assistantMsgId,
                                        content = currentTextAccumulated,
                                        thought = thoughtBuilder.toString(),
                                        thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                                        toolCalls = updatedToolCalls,
                                        isStreaming = true
                                    )
                                    storage.saveMessages(conv.id, _messages.value)

                                    val syntheticHistory = currentHistory + listOf(
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.ASSISTANT,
                                            content = "$cleanPreamble\n<tool_call name=\"math\">$payload</tool_call>"
                                        ),
                                        ChatMessage(
                                            conversationId = conv.id,
                                            role = MessageRole.USER,
                                            content = "[Symja CAS Math Engine Result]:\n$outputFormatted"
                                        )
                                    )

                                    executeStream(
                                        conv = conv,
                                        currentHistory = syntheticHistory,
                                        existingAssistantMsgId = assistantMsgId,
                                        existingToolCalls = updatedToolCalls,
                                        priorTextPrefix = currentTextAccumulated
                                    )
                                }
                                return@collect
                            }
                        }

                        val finalDisplayContent = priorTextPrefix + (if (priorTextPrefix.isNotBlank() && streamGeneratedText.isNotBlank()) "\n\n$streamGeneratedText" else streamGeneratedText)
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = finalDisplayContent.trim(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                            toolCalls = existingToolCalls,
                            isStreaming = false,
                            tokenUsage = event.tokenUsage,
                            rawPayload = event.rawPayload
                        )
                        storage.saveMessages(conv.id, _messages.value)
                        refreshQuotas()
                    }
                    is StreamEvent.Error -> {
                        android.util.Log.e("GeminiApp", "[ViewModel] Stream error: ${event.message}")
                        
                        // Check if 401 Unauthorized / Token Expired and can be refreshed automatically
                        val isAuthError = event.message.contains("401") || 
                                          event.message.contains("UNAUTHENTICATED", ignoreCase = true) || 
                                          event.message.contains("Unauthorized", ignoreCase = true) ||
                                          event.message.contains("token", ignoreCase = true)
                        
                        if (isAuthError && !isRetryAfterRefresh && !authPrefs.refreshToken.firstOrNull().isNullOrBlank()) {
                            android.util.Log.d("GeminiApp", "[ViewModel] 401 encountered, automatically refreshing token and retrying...")
                            _isStreaming.value = false
                            // Remove empty placeholder and re-run with fresh token
                            val currentList = _messages.value.filter { it.id != assistantMsgId }
                            _messages.value = currentList
                            executeStream(conv, currentHistory, isRetryAfterRefresh = true)
                            return@collect
                        }

                        _isStreaming.value = false
                        val errorContent = if (textBuilder.isEmpty()) "⚠️ Error: ${event.message}" else (priorTextPrefix + "\n\n" + textBuilder.toString() + "\n\n⚠️ Error: ${event.message}")
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = errorContent.trim(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = null,
                            toolCalls = existingToolCalls,
                            isStreaming = false
                        )
                        storage.saveMessages(conv.id, _messages.value)
                    }
                }
            }
        }
    }

    private data class ExtractedTool(
        val name: String,
        val payload: String,
        val cleanPreamble: String
    )

    private fun extractToolCall(text: String): ExtractedTool? {
        // 1. Unified <tool_call name="...">...</tool_call>
        val unifiedMatch = Regex("<tool_call\\s+name=[\"']?([a-zA-Z0-9_-]+)[\"']?\\s*>([\\s\\S]*?)</tool_call>", RegexOption.IGNORE_CASE).find(text)
        if (unifiedMatch != null) {
            val name = unifiedMatch.groupValues[1].trim().lowercase()
            val payload = unifiedMatch.groupValues[2].trim()
            val preamble = text.replace(Regex("<tool_call\\s+name=[\"']?[a-zA-Z0-9_-]+[\"']?\\s*>[\\s\\S]*?</tool_call>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool(name, payload, preamble)
        }

        // 2. Ask Choices / Question tags
        val choicesMatch = Regex("<(ask_choices|user_choice)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE).find(text)
        if (choicesMatch != null) {
            val payload = choicesMatch.groupValues[2].trim()
            val preamble = text.replace(Regex("<(ask_choices|user_choice)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("ask_choices", payload, preamble)
        }

        // 3. Web Search tag
        val searchMatch = Regex("<web_search>([\\s\\S]*?)</web_search>", RegexOption.IGNORE_CASE).find(text)
        if (searchMatch != null) {
            val payload = searchMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<web_search>[\\s\\S]*?</web_search>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("web_search", payload, preamble)
        }

        // 4. Web Reader tag
        val readMatch = Regex("<read_url>([\\s\\S]*?)</read_url>", RegexOption.IGNORE_CASE).find(text)
        if (readMatch != null) {
            val payload = readMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<read_url>[\\s\\S]*?</read_url>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("read_url", payload, preamble)
        }

        // 5. Terminal Bash tags (<execute_command>, <bash>, <terminal>, <sh>)
        val execMatch = Regex("<(execute_command|bash|terminal|sh)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE).find(text)
        if (execMatch != null) {
            val payload = execMatch.groupValues[2].trim()
            val preamble = text.replace(Regex("<(execute_command|bash|terminal|sh)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("bash", payload, preamble)
        }

        // 6. File tool tags (unified tool_call handles these, but keep as fallback)
        val fileReadMatch = Regex("<read_file>([\\s\\S]*?)</read_file>", RegexOption.IGNORE_CASE).find(text)
        if (fileReadMatch != null) {
            val payload = fileReadMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<read_file>[\\s\\S]*?</read_file>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("read_file", payload, preamble)
        }
        val fileWriteMatch = Regex("<write_file>([\\s\\S]*?)</write_file>", RegexOption.IGNORE_CASE).find(text)
        if (fileWriteMatch != null) {
            val payload = fileWriteMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<write_file>[\\s\\S]*?</write_file>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("write_file", payload, preamble)
        }
        val fileEditMatch = Regex("<edit_file>([\\s\\S]*?)</edit_file>", RegexOption.IGNORE_CASE).find(text)
        if (fileEditMatch != null) {
            val payload = fileEditMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<edit_file>[\\s\\S]*?</edit_file>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("edit_file", payload, preamble)
        }

        // 7. Automation tag
        val autoMatch = Regex("<automation>([\\s\\S]*?)</automation>", RegexOption.IGNORE_CASE).find(text)
        if (autoMatch != null) {
            val payload = autoMatch.groupValues[1].trim()
            val preamble = text.replace(Regex("<automation>[\\s\\S]*?</automation>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("automation", payload, preamble)
        }

        // 8. Math CAS tag
        val mathMatch = Regex("<(math|cas|math_eval)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE).find(text)
        if (mathMatch != null) {
            val payload = mathMatch.groupValues[2].trim()
            val preamble = text.replace(Regex("<(math|cas|math_eval)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "").trim()
            return ExtractedTool("math", payload, preamble)
        }

        return null
    }

    private fun sanitizeStreamingText(rawText: String): String {
        if (!rawText.contains('<')) return rawText
        // Find index where any tool call tag starts (complete, unclosed, or in-flight)
        val toolTagPattern = Regex("<\\s*(tool_call|execute_command|web_search|read_url|ask_choices|user_choice|read_file|write_file|edit_file|automation|math|cas|tool_|execute_|web_|read_|ask_|user_|auto_|math_)", RegexOption.IGNORE_CASE)
        val match = toolTagPattern.find(rawText)
        return if (match != null) {
            rawText.substring(0, match.range.first).trimEnd()
        } else {
            // Also clean up any orphan closed tags
            rawText.replace(Regex("<\\s*(tool_call|execute_command|web_search|read_url|ask_choices|user_choice|read_file|write_file|edit_file|automation|math|cas|math_eval)[^>]*>[\\s\\S]*?<\\/\\s*\\1\\s*>", RegexOption.IGNORE_CASE), "").trimEnd()
        }
    }

    private var lastStreamUpdateTime = 0L

    private fun updateAssistantMessage(
        msgId: String,
        content: String,
        thought: String,
        thoughtDuration: Long?,
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
                thoughtText = thought.ifEmpty { null },
                thoughtDurationMs = thoughtDuration,
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
            var token = getValidAccessToken() ?: return@launch
            _isRefreshingModels.value = true
            try {
                var res = apiService.fetchAvailableModels(token, _projectId.value)
                if (res.isFailure || res.getOrNull()?.models.isNullOrEmpty()) {
                    val refreshedToken = getValidAccessToken(forceRefresh = true)
                    if (!refreshedToken.isNullOrBlank() && refreshedToken != token) {
                        token = refreshedToken
                        res = apiService.fetchAvailableModels(token, _projectId.value)
                    }
                }

                if (res.isSuccess) {
                    val result = res.getOrThrow()
                    if (result.models.isNotEmpty()) {
                        _availableModels.value = result.models
                        recomputeEnabledModels()
                    }
                    _quotas.value = result.quotas

                    // Persist cached models and quotas to local storage for instant launch next time
                    try {
                        val modelsStr = json.encodeToString(result.models)
                        val quotasStr = json.encodeToString(result.quotas)
                        authPrefs.saveCachedModelsAndQuotas(modelsStr, quotasStr)
                    } catch (_: Exception) {}
                }
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
