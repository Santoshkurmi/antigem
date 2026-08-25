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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = LocalChatStorage(application)
    private val authPrefs = AuthPreferences(application)
    private val apiService = AntigravityApiService()
    private val oauthManager = GoogleOAuthManager()

    val conversations: StateFlow<List<Conversation>> = storage.conversations

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(AiModel.DEFAULT_MODELS)
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow(AiModel.DEFAULT_MODELS.first().id)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _quotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    val quotas: StateFlow<List<ModelQuota>> = _quotas.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _projectId = MutableStateFlow("rising-fact-p41fc")
    val projectId: StateFlow<String> = _projectId.asStateFlow()

    private val _tier = MutableStateFlow("pro")
    val tier: StateFlow<String> = _tier.asStateFlow()

    fun setThinkingPreference(pref: com.example.gemini.domain.model.ThinkingPreference) {
        _thinkingPreference.value = pref
    }

    private var streamingJob: Job? = null
    var pendingPkceVerifier: String? = null

    init {
        viewModelScope.launch {
            storage.init()
            val list = storage.conversations.value
            if (list.isNotEmpty()) {
                selectConversation(list.first().id)
            } else {
                startNewChat()
            }

            authPrefs.userEmail.collect { _userEmail.value = it }
        }

        viewModelScope.launch {
            authPrefs.projectId.collect { it?.let { p -> _projectId.value = p } }
        }

        viewModelScope.launch {
            authPrefs.subscriptionTier.collect { it?.let { t -> _tier.value = t } }
        }

        viewModelScope.launch {
            refreshQuotas()
        }
    }

    fun startNewChat() {
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = _selectedModelId.value,
            sessionId = UUID.randomUUID().toString()
        )
        _currentConversation.value = newConv
        _messages.value = emptyList()
        viewModelScope.launch {
            storage.saveConversation(newConv)
        }
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            val conv = storage.conversations.value.find { it.id == id }
            if (conv != null) {
                _currentConversation.value = conv
                _selectedModelId.value = conv.modelId
                val msgs = storage.getMessages(id)
                _messages.value = msgs
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

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        val conv = _currentConversation.value
        if (conv != null) {
            val updated = conv.copy(modelId = modelId)
            _currentConversation.value = updated
            viewModelScope.launch {
                storage.saveConversation(updated)
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

        // Set title on first message
        val updatedConv = if (conv.title == "New Chat" && _messages.value.isEmpty()) {
            val shortTitle = if (content.length > 30) content.take(30) + "..." else content
            conv.copy(title = shortTitle)
        } else conv

        _currentConversation.value = updatedConv

        val updatedList = _messages.value + userMsg
        _messages.value = updatedList

        viewModelScope.launch {
            storage.saveConversation(updatedConv)
            storage.saveMessages(conv.id, updatedList)
            executeStream(updatedConv, updatedList)
        }
    }

    private suspend fun executeStream(conv: Conversation, currentHistory: List<ChatMessage>) {
        val token = authPrefs.accessToken.firstOrNull()
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

        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            conversationId = conv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )

        _messages.value = _messages.value + assistantMsg

        val startTime = System.currentTimeMillis()
        val thoughtBuilder = StringBuilder()
        val textBuilder = StringBuilder()
        var thoughtCompletedAt: Long? = null

        // Apply context compaction if history is very long
        val (summary, compactHistory) = if (ContextCompactor.needsCompaction(currentHistory)) {
            ContextCompactor.compactHistory(currentHistory)
        } else {
            Pair(conv.summary, currentHistory)
        }

        streamingJob = viewModelScope.launch {
            apiService.streamGenerateContent(
                token = token,
                projectId = _projectId.value,
                modelId = _selectedModelId.value,
                sessionId = conv.sessionId,
                messages = compactHistory,
                summary = summary,
                thinkingBudget = _thinkingPreference.value.activeTokens,
                isThinkingEnabled = _thinkingPreference.value.isEnabled
            ).collect { event ->
                when (event) {
                    is StreamEvent.ThoughtChunk -> {
                        thoughtBuilder.append(event.thought)
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = textBuilder.toString(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = System.currentTimeMillis() - startTime,
                            isStreaming = true
                        )
                    }
                    is StreamEvent.TextChunk -> {
                        if (thoughtCompletedAt == null && thoughtBuilder.isNotEmpty()) {
                            thoughtCompletedAt = System.currentTimeMillis()
                        }
                        textBuilder.append(event.text)
                        val duration = (thoughtCompletedAt ?: System.currentTimeMillis()) - startTime
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = textBuilder.toString(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                            isStreaming = true
                        )
                    }
                    is StreamEvent.Completed -> {
                        android.util.Log.d("GeminiApp", "[ViewModel] Stream completed. Text length: ${textBuilder.length}, Thought length: ${thoughtBuilder.length}")
                        _isStreaming.value = false
                        val duration = (thoughtCompletedAt ?: System.currentTimeMillis()) - startTime
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = textBuilder.toString(),
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = if (thoughtBuilder.isNotEmpty()) duration else null,
                            isStreaming = false
                        )
                        storage.saveMessages(conv.id, _messages.value)
                        refreshQuotas()
                    }
                    is StreamEvent.Error -> {
                        android.util.Log.e("GeminiApp", "[ViewModel] Stream error: ${event.message}")
                        _isStreaming.value = false
                        val errorContent = if (textBuilder.isEmpty()) "⚠️ Error: ${event.message}" else textBuilder.toString() + "\n\n⚠️ Error: ${event.message}"
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = errorContent,
                            thought = thoughtBuilder.toString(),
                            thoughtDuration = null,
                            isStreaming = false
                        )
                        storage.saveMessages(conv.id, _messages.value)
                    }
                }
            }
        }
    }

    private fun updateAssistantMessage(
        msgId: String,
        content: String,
        thought: String,
        thoughtDuration: Long?,
        isStreaming: Boolean
    ) {
        val list = _messages.value.toMutableList()
        val index = list.indexOfFirst { it.id == msgId }
        if (index >= 0) {
            list[index] = list[index].copy(
                content = content,
                thoughtText = thought.ifEmpty { null },
                thoughtDurationMs = thoughtDuration,
                isStreaming = isStreaming
            )
            _messages.value = list
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
            val token = authPrefs.accessToken.firstOrNull() ?: return@launch
            _isRefreshingModels.value = true
            try {
                val res = apiService.fetchAvailableModels(token, _projectId.value)
                if (res.isSuccess) {
                    val result = res.getOrThrow()
                    if (result.models.isNotEmpty()) {
                        _availableModels.value = result.models
                    }
                    _quotas.value = result.quotas
                }
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    fun applyManualInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.contains("code=")) {
            val match = Regex("[?&]code=([^&\\s]+)").find(trimmed)
            val code = match?.groupValues?.get(1)
            if (code != null) {
                handleOAuthCode(code)
                return
            }
        }

        if (trimmed.startsWith("4/0")) {
            handleOAuthCode(trimmed)
            return
        }

        // Direct access token
        viewModelScope.launch {
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

    fun getGoogleOAuthUrl(): String {
        val pkce = oauthManager.generatePkce()
        pendingPkceVerifier = pkce.codeVerifier

        // Start background local callback server on 51121
        viewModelScope.launch {
            oauthManager.startLocalCallbackServer { code ->
                handleOAuthCode(code)
            }
        }

        return pkce.authUrl
    }

    fun handleOAuthCode(code: String) {
        val verifier = pendingPkceVerifier ?: return
        viewModelScope.launch {
            val tokenRes = oauthManager.exchangeCodeForToken(code, verifier)
            if (tokenRes.isSuccess) {
                val tokenData = tokenRes.getOrThrow()
                val userRes = oauthManager.fetchUserInfo(tokenData.access_token)
                val email = userRes.getOrNull()?.email ?: "Google Account"

                authPrefs.saveTokens(tokenData.access_token, tokenData.refresh_token, email)
                _userEmail.value = email

                val assistRes = apiService.loadCodeAssist(tokenData.access_token)
                if (assistRes.isSuccess) {
                    val (proj, tier) = assistRes.getOrThrow()
                    authPrefs.saveProjectInfo(proj, tier)
                    _projectId.value = proj
                    _tier.value = tier
                }
                refreshQuotas()
            }
        }
    }
}
