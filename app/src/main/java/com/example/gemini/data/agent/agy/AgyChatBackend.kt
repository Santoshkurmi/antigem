package com.example.gemini.data.agent.agy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.ui.chat.PendingToolApproval
import kotlinx.coroutines.CoroutineScope

/**
 * Antigravity (agy --hub) chat backend: conversation sync, live trajectory stream, prompts, approvals,
 * auth, models and quotas. Moved out of ChatViewModel unchanged; it writes the on-screen chat state into [store].
 */
class AgyChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val authPrefs: AuthPreferences,
    private val store: ChatSessionStore,
    private val isNetworkConnectedProvider: () -> Boolean,
    private val onHubOnline: () -> Unit
) : AgentChatBackend {

    override val kind = AgentKind.AGY

    private val _conversations = store.conversations
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = store.currentConversation
    override val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = store.messages
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversationError = store.conversationError
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _userEmail = store.userEmail
    private val _attachments = store.attachments
    private val _terminatedToolDialog = store.terminatedToolDialog

    private fun isNetworkConnected(): Boolean = isNetworkConnectedProvider()

    val agyBridgeService = com.example.gemini.data.remote.AgyBridgeService.instance
    val systemConnectionState: StateFlow<com.example.gemini.data.remote.SystemConnectionState> = agyBridgeService.systemConnectionState
    val hubStatus: StateFlow<com.example.gemini.data.remote.AgyHubStatus> = agyBridgeService.hubStatus
    val agyHubClient = com.example.gemini.data.remote.AgyHubClient()
    val trajectoryEngine = com.example.gemini.domain.chat.TrajectoryEngine()
    val speechManager = com.example.gemini.data.audio.AgyAudioTranscriptionManager(agyHubClient) {
        AuthPreferences.currentHubUrl
    }

    private val _hasReceivedInitialSync = MutableStateFlow(false)
    val hasReceivedInitialSync: StateFlow<Boolean> = _hasReceivedInitialSync.asStateFlow()

    private val _isConversationsLoading = MutableStateFlow(true)
    val isConversationsLoading: StateFlow<Boolean> = _isConversationsLoading.asStateFlow()

    val artifacts: StateFlow<List<com.example.gemini.domain.model.ArtifactSnapshot>> = trajectoryEngine.artifacts

    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _messages.map { msgs ->
        msgs.filter { it.role == com.example.gemini.domain.model.MessageRole.ASSISTANT }
            .flatMap { msg ->
                msg.toolCalls.filter { it.status == "PENDING_APPROVAL" && it.toolType != com.example.gemini.domain.model.ToolType.ASK_CHOICE }
                    .distinctBy { it.stepIndex }
                    .map { PendingToolApproval(it, msg.id) }
            }
    }.stateIn(backendScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

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
        .stateIn(backendScope, SharingStarted.Eagerly, "")

    private val _quotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    val quotas: StateFlow<List<ModelQuota>> = _quotas.asStateFlow()

    private val _quotaSummary = MutableStateFlow<com.example.gemini.domain.model.QuotaSummaryResponse?>(null)
    val quotaSummary: StateFlow<com.example.gemini.domain.model.QuotaSummaryResponse?> = _quotaSummary.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(true)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    fun setModelEnabled(modelId: String, isEnabled: Boolean) {
        val currentIds = _enabledModelIds.value?.toMutableSet() ?: _availableModels.value.map { it.id }.toMutableSet()
        if (isEnabled) {
            currentIds.add(modelId)
        } else {
            if (currentIds.size > 1) { // Don't allow disabling all models
                currentIds.remove(modelId)
            }
        }
        backendScope.launch {
            authPrefs.saveEnabledModelIds(currentIds)
        }
    }

    fun enableAllModels() {
        val allIds = _availableModels.value.map { it.id }.toSet()
        backendScope.launch {
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

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _isBridgeOnline = MutableStateFlow<Boolean?>(null)
    val isBridgeOnline: StateFlow<Boolean?> = _isBridgeOnline.asStateFlow()

    fun checkBridgeHealth() {
        backendScope.launch(Dispatchers.IO) {
            val bridgeUrl = authPrefs.agyBridgeHttpUrl.firstOrNull() ?: AuthPreferences.currentBridgeHttpUrl
            val reachable = com.example.gemini.data.remote.AgyBridgeService.instance.checkServerHealth(bridgeUrl)
            _isBridgeOnline.value = reachable
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
    private var lastAuthCheckTimeMs = 0L

    fun onDrawerOpened() {
        val isLoggedIn = _agyAuthInfo.value.isLoggedIn
        val now = System.currentTimeMillis()
        if (!isLoggedIn) {
            // Not authenticated: check every single time user opens sidebar
            checkAgyAuthStatus(userInitiated = false)
        } else {
            // Authenticated: only check after 2 minutes (120,000ms) have passed
            if (now - lastAuthCheckTimeMs >= 120_000L) {
                checkAgyAuthStatus(userInitiated = false)
            }
        }
    }

    fun cancelAgyLogin() {
        loginPollJob?.cancel()
        loginPollJob = null
        _isAuthBusy.value = false
        _pendingLoginUrl.value = null
        checkAgyAuthStatus(userInitiated = false)
    }

    fun checkAgyAuthStatus(userInitiated: Boolean = false) {
        backendScope.launch {
            agyBridgeService.updateAuthChecking(true)
            if (!isNetworkConnected()) {
                android.util.Log.d("ChatViewModel", "Skipping auth status check: device is offline.")
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
                    isOffline = true
                )
                _isAuthBusy.value = false
                agyBridgeService.updateAuthChecking(false)
                if (userInitiated) {
                    _authFeedbackMessage.tryEmit("Cannot check status: device is offline.")
                }
                return@launch
            }
            val hubUrl = AuthPreferences.currentHubUrl
            var res = agyHubClient.fetchDetailedAuthInfo(hubUrl)

            // If failed on non-manual check during startup/connection phase, retry shortly
            if (res.isFailure && !userInitiated) {
                delay(800)
                if (isNetworkConnected()) {
                    res = agyHubClient.fetchDetailedAuthInfo(hubUrl)
                }
                if (res.isFailure) {
                    delay(1500)
                    if (isNetworkConnected()) {
                        res = agyHubClient.fetchDetailedAuthInfo(hubUrl)
                    }
                }
            }

            if (res.isSuccess) {
                val info = res.getOrThrow()
                _agyAuthInfo.value = info
                agyBridgeService.updateAuthState(isAuth = info.isLoggedIn, isChecking = false)
                if (info.isLoggedIn) {
                    lastAuthCheckTimeMs = System.currentTimeMillis()
                    _isAuthBusy.value = false
                    _pendingLoginUrl.value = null
                    loginPollJob?.cancel()
                    if (userInitiated) {
                        _authFeedbackMessage.tryEmit("Signed in as ${info.displayName.ifBlank { info.email }}")
                    }
                    refreshQuotas(force = false)
                } else if (userInitiated) {
                    _authFeedbackMessage.tryEmit("Not signed in.")
                }
            } else {
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
                    isOffline = true
                )
                // Retain current auth state; only clear the isChecking flag on network/RPC failure
                agyBridgeService.updateAuthChecking(false)
                if (userInitiated) {
                    _authFeedbackMessage.tryEmit("Unable to reach server.")
                }
            }
        }
    }

    fun loginToAgyHub(force: Boolean = false) {
        if (_isAuthBusy.value && !force) return
        if (!isNetworkConnected()) {
            _authFeedbackMessage.tryEmit("Cannot sign in: no internet connection.")
            return
        }
        _isAuthBusy.value = true
        agyBridgeService.updateAuthChecking(true)
        loginPollJob?.cancel()

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl

            // Pre-check: if user is already authenticated, finish immediately!
            val preCheck = agyHubClient.fetchDetailedAuthInfo(hubUrl)
            if (preCheck.isSuccess && preCheck.getOrThrow().isLoggedIn) {
                val authed = preCheck.getOrThrow()
                _agyAuthInfo.value = authed
                agyBridgeService.updateAuthState(isAuth = true, isChecking = false)
                lastAuthCheckTimeMs = System.currentTimeMillis()
                _isAuthBusy.value = false
                _authFeedbackMessage.tryEmit("Already signed in as ${authed.displayName}!")
                refreshQuotas(force = true)
                return@launch
            }

            _authFeedbackMessage.tryEmit("Initiating sign-in with Antigravity...")

            // 1. Poll for successful auth completion concurrently every 800ms
            loginPollJob = launch {
                val startTime = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - startTime < 120_000) {
                    delay(800)

                    if (isNetworkConnected()) {
                        val res = agyHubClient.fetchDetailedAuthInfo(hubUrl)
                        if (res.isSuccess && res.getOrThrow().isLoggedIn) {
                            val authed = res.getOrThrow()
                            _agyAuthInfo.value = authed
                            agyBridgeService.updateAuthState(isAuth = true, isChecking = false)
                            lastAuthCheckTimeMs = System.currentTimeMillis()
                            _isAuthBusy.value = false
                            _pendingLoginUrl.value = null
                            _authFeedbackMessage.tryEmit("Signed in successfully!")
                            refreshQuotas(force = true)
                            break
                        }
                    }
                }
                _isAuthBusy.value = false
                agyBridgeService.updateAuthChecking(false)
            }

            // 2. Kick off login RPC on daemon (Login RPC opens browser / triggers auth flow)
            launch {
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

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            try {
                agyHubClient.authLogout(hubUrl)
                _agyAuthInfo.value = com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo(
                    status = com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.UNAUTHENTICATED,
                    isLoggedIn = false
                )
                agyBridgeService.updateAuthState(isAuth = false, isChecking = false)
                _authFeedbackMessage.tryEmit("Logged out successfully.")
                refreshQuotas(force = true)
            } catch (e: Exception) {
                _authFeedbackMessage.tryEmit("Logout error: ${e.message}")
            } finally {
                _isAuthBusy.value = false
            }
        }
    }

    private val _activeInstances = MutableStateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>>(emptyList())
    val activeInstances: StateFlow<List<com.example.gemini.data.remote.AgyActiveInstance>> = _activeInstances.asStateFlow()

    val isAnyGenerationOrTaskActive: StateFlow<Boolean> = combine(
        _activeInstances,
        _conversations
    ) { instances, convs ->
        instances.isNotEmpty() || convs.any { it.isRunning || it.notFullyIdle || it.hasActivity }
    }.stateIn(backendScope, SharingStarted.WhileSubscribed(5000), false)

    // Authoritative conversation running & background states derived from the sidebar summary stream (_conversations).
    // Note: We use the daemon's sidebar summaries (JetboxSubscribeToSummaries) as the single source of truth rather than
    // per-frame stream updates from StreamAgentStateUpdates because the fine-grained gRPC stream in AGY can be buggier
    // (e.g. continuing to report RUNNING state even when the turn has ended, particularly in long chats with multiple subagents/tools).
    val isCurrentChatActivelyRunning: StateFlow<Boolean> = combine(
        _conversations,
        _currentConversation,
        systemConnectionState
    ) { convList, current, conn ->
        val curId = current?.id ?: return@combine false
        val conv = convList.find { it.id == curId } ?: current
        (conv.isRunning) && conn.isHubOnline
    }.distinctUntilChanged()
    .stateIn(backendScope, SharingStarted.Eagerly, false)

    val isCurrentChatBackgroundActive: StateFlow<Boolean> = combine(
        _conversations,
        _currentConversation,
        systemConnectionState,
        _activeInstances
    ) { convList, current, conn, instances ->
        val curId = current?.id ?: return@combine false
        val conv = convList.find { it.id == curId } ?: current
        val isActivelyRunning = (conv.isRunning) && conn.isHubOnline
        val activeInst = instances.find { it.conversationId == curId }
        conn.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)
    }.distinctUntilChanged()
    .stateIn(backendScope, SharingStarted.Eagerly, false)

    val connectionState: StateFlow<com.example.gemini.data.remote.BridgeConnectionState> = combine(_isServerOnline, isCurrentChatActivelyRunning) { online, streaming ->
        when {
            online == false -> com.example.gemini.data.remote.BridgeConnectionState.OFFLINE_ERROR
            streaming -> com.example.gemini.data.remote.BridgeConnectionState.STREAMING
            online == true -> com.example.gemini.data.remote.BridgeConnectionState.CONNECTED_READY
            else -> com.example.gemini.data.remote.BridgeConnectionState.CONNECTING
        }
    }.stateIn(backendScope, SharingStarted.Eagerly, com.example.gemini.data.remote.BridgeConnectionState.CONNECTING)

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

    /** Starts hub monitoring, conversation sync and auth checks (formerly ChatViewModel.init). */
    fun start() {
        // 1. Hub status live monitoring via WebSocket
        backendScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    val wsUrl = AuthPreferences.currentBridgeWsUrl
                    agyBridgeService.monitorHubStatus(wsUrl).collect { status ->
                        when (status.status) {
                            "online" -> {
                                _isServerOnline.value = true
                                _isBridgeOnline.value = true
                                _conversationError.value = null
                                syncAgyConversations(force = true)
                                val curConv = _currentConversation.value
                                val curId = curConv?.id
                                if (!curId.isNullOrBlank() && curId != "new" && curConv.title != "New Chat") {
                                    _conversationError.value = null
                                    _isLoadingConversation.value = true
                                    startPersistentStream(curId)
                                }
                                checkAgyAuthStatus(userInitiated = false)
                                refreshQuotas()
                                onHubOnline()
                            }
                            "starting" -> {
                                _isBridgeOnline.value = true
                                _conversationError.value = null
                                _isConversationsLoading.value = true
                            }
                            "error" -> {
                                _isServerOnline.value = false
                                _isConversationsLoading.value = false
                                _conversationError.value = "Antigravity Hub failed to start: ${status.error ?: "Check server logs"}"
                            }
                            "stopped" -> {
                                _isServerOnline.value = false
                                _isBridgeOnline.value = true
                                if (_conversations.value.any { it.isRunning }) {
                                    _conversations.value = _conversations.value.map { if (it.isRunning) it.copy(isRunning = false) else it }
                                }
                                if (_currentConversation.value?.isRunning == true) {
                                    _currentConversation.value = _currentConversation.value?.copy(isRunning = false)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    _isServerOnline.value = false
                    _isBridgeOnline.value = false
                    agyBridgeService.resetState()
                    delay(1000)
                }
            }
        }

        backendScope.launch {
            agyBridgeService.loginUrlEvents.collect { url ->
                if (url.isNotBlank()) {
                    _pendingLoginUrl.value = url
                }
            }
        }

        backendScope.launch {
            val savedId = authPrefs.preferredModelId.firstOrNull()
            val savedKey = authPrefs.preferredModelKey.firstOrNull()
            val initial = savedId ?: savedKey ?: ""
            if (initial.isNotBlank() && _selectedModelId.value.isBlank()) {
                _selectedModelId.value = initial
            }
            startNewChat()
        }

        backendScope.launch {
            authPrefs.enabledModelIds.collect { ids ->
                _enabledModelIds.value = ids
                recomputeEnabledModels()
            }
        }

        // Keep active conversation title and summary live-synced when conversation list updates
        backendScope.launch {
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

        // Periodic auto-reconnect monitor for Hub RPC streams & Bridge (every 20 seconds)
        backendScope.launch {
            delay(20_000)
            while (currentCoroutineContext().isActive) {
                checkBridgeHealth()
                if (agyBridgeService.hubStatus.value.status != "starting") {
                    if (_isServerOnline.value == true && syncJob?.isActive != true) {
                        android.util.Log.d("ChatViewModel", "Periodic check: reconnecting hub streams...")
                        syncAgyConversations(force = false)
                    }
                }
                delay(20_000)
            }
        }

        // Initial auth status check
        checkAgyAuthStatus(userInitiated = false)
    }

    fun refreshActiveInstances() {
        backendScope.launch {
            try {
                val httpUrl = AuthPreferences.currentBridgeHttpUrl
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
        backendScope.launch {
            val httpUrl = AuthPreferences.currentBridgeHttpUrl
            val ok = agyBridgeService.terminateInstance(conversationId, httpUrl)
            if (ok) {
                _activeInstances.value = _activeInstances.value.filter { it.conversationId != conversationId }
            }
        }
    }

    private var syncJob: Job? = null

    fun syncAgyConversations(force: Boolean = false) {
        if (!force && syncJob?.isActive == true) return
        syncJob?.cancel()
        syncJob = backendScope.launch {
            _isConversationsLoading.value = true
            while (currentCoroutineContext().isActive) {
                try {
                    val hubUrl = AuthPreferences.currentHubUrl
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

                        val sortedConvs = currentMap.values.sortedByDescending { it.updatedAt }
                        _conversations.value = sortedConvs
                        com.example.gemini.data.daemon.TermuxDaemonManager.loadProjects(sortedConvs)

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
                                    curr.notFullyIdle != activeInMap.notFullyIdle ||
                                    curr.hasActivity != activeInMap.hasActivity ||
                                    (activeInMap.stepCount > curr.stepCount)) {
                                    _currentConversation.value = curr.copy(
                                        title = newTitle,
                                        summary = activeInMap.summary ?: curr.summary,
                                        workspaceUri = newWorkspaceUri,
                                        stepCount = if (activeInMap.stepCount > 0) activeInMap.stepCount else curr.stepCount,
                                        isRunning = activeInMap.isRunning,
                                        notFullyIdle = activeInMap.notFullyIdle,
                                        hasActivity = activeInMap.hasActivity
                                    )
                                }
                            }
                        }
                        _hasReceivedInitialSync.value = true
                        if (systemConnectionState.value.isBridgeOnline) {
                            _isServerOnline.value = true
                            _isBridgeOnline.value = true
                        }
                        _conversationError.value = null
                        _isConversationsLoading.value = false
                        if (!_agyAuthInfo.value.isLoggedIn || _agyAuthInfo.value.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE || _agyAuthInfo.value.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.CHECKING) {
                            checkAgyAuthStatus(userInitiated = false)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        break
                    }
                    android.util.Log.e("ChatViewModel", "subscribeToSummaries failed: ${e.message}")
                    _isServerOnline.value = false
                    if (_conversations.value.any { it.isRunning }) {
                        _conversations.value = _conversations.value.map { if (it.isRunning) it.copy(isRunning = false) else it }
                    }
                    if (_currentConversation.value?.isRunning == true) {
                        _currentConversation.value = _currentConversation.value?.copy(isRunning = false)
                    }
                    val rawErr = e.message ?: "Connection failed"
                    val helpfulMsg = if (rawErr.contains("Connect", ignoreCase = true) || rawErr.contains("Failed to connect", ignoreCase = true)) {
                        "Cannot connect to Antigravity Hub (${AuthPreferences.currentHubUrl}). Make sure 'agy --hub' is running."
                    } else {
                        "Antigravity Hub unreachable: $rawErr"
                    }
                    val isStarting = agyBridgeService.hubStatus.value.status == "starting"
                    if (_conversations.value.isEmpty() && !isStarting) {
                        _conversationError.value = helpfulMsg
                        _isConversationsLoading.value = false
                    } else if (isStarting || _conversations.value.isEmpty()) {
                        _isConversationsLoading.value = true
                    } else {
                        _isConversationsLoading.value = false
                    }
                    delay(20_000) // Auto-retry conversation sync every 20 seconds while offline
                }
            }
        }
    }

    /**
     * Retries all connections: both sidebar conversation list and active chat stream
     */
    override fun retryConnections() {
        _conversationError.value = null
        val conv = _currentConversation.value
        val isExistingConv = conv != null && conv.title != "New Chat" && _conversations.value.any { it.id == conv.id }
        if (_messages.value.isEmpty() && isExistingConv) {
            _isLoadingConversation.value = true
        }
        _isReconnecting.value = true
        syncAgyConversations(force = true)
        val convId = conv?.id
        if (!convId.isNullOrBlank() && isExistingConv) {
            startPersistentStream(convId)
        }
        refreshQuotas()
        checkBridgeHealth()
        checkAgyAuthStatus()
        backendScope.launch {
            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = false)
        }
    }

    /**
     * Called when the app comes into focus / foreground.
     * Checks both RPC streams and reconnects if dropped.
     */
    override fun onAppForegrounded() {
        android.util.Log.d("ChatViewModel", "App foregrounded: inspecting RPC streams...")
        backendScope.launch {
            val conv = _currentConversation.value
            val isExistingConv = conv != null && conv.title != "New Chat" && _conversations.value.any { it.id == conv.id }
            val isSyncActive = syncJob?.isActive == true
            val isStreamActive = persistentStreamJob?.isActive == true

            if (!isSyncActive || _isServerOnline.value != true) {
                syncAgyConversations(force = true)
            }
            if (conv != null && isExistingConv && (!isStreamActive || _isServerOnline.value != true)) {
                startPersistentStream(conv.id)
            }
            refreshQuotas()
            if (!_agyAuthInfo.value.isLoggedIn || _agyAuthInfo.value.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.CHECKING) {
                checkAgyAuthStatus(userInitiated = false)
            }
            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
        }
    }

    fun onProjectChanged(projectPath: String) {
        _bridgeStatusMessage.value = null
        val conv = _currentConversation.value
        if (conv != null) {
            val uri = if (projectPath.isNotBlank()) {
                if (projectPath.startsWith("file://")) projectPath else "file://$projectPath"
            } else ""
            _currentConversation.value = conv.copy(workspaceUri = uri)
        }
        backendScope.launch {
            val httpUrl = AuthPreferences.currentBridgeHttpUrl
            val model = _selectedModelId.value.ifBlank { _availableModels.value.firstOrNull()?.id ?: "" }
            agyBridgeService.prewarm(
                conversationId = _currentConversation.value?.id,
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

    override fun startNewChat() {
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
        val defaultProjPath = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path ?: ""
        val defaultWorkspaceUri = if (defaultProjPath.isNotBlank()) {
            if (defaultProjPath.startsWith("file://")) defaultProjPath else "file://$defaultProjPath"
        } else ""
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelToUse,
            sessionId = UUID.randomUUID().toString(),
            workspaceUri = defaultWorkspaceUri
        )
        _currentConversation.value = newConv
        trajectoryEngine.reset(newConv.id, force = true)
        com.example.gemini.ui.components.ToolCallExpansionCache.setChat(newConv.id)
        com.example.gemini.ui.components.CodeBlockExpansionCache.setChat(newConv.id)
        _messages.value = emptyList()
        _isLoadingConversation.value = false
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        Log.d("CHAT_OPEN_DEBUG", "👉 [ChatViewModel.selectConversation] id=$id, activeStreamId=$activeStreamConversationId, isLoading=${_isLoadingConversation.value}, msgCount=${_messages.value.size}")
        if (id == activeStreamConversationId && !_isLoadingConversation.value && _messages.value.isNotEmpty()) {
            Log.d("CHAT_OPEN_DEBUG", "👉 [ChatViewModel.selectConversation] Skipped: already active conversation with messages")
            return
        }

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
        _isLoadingConversation.value = true

        backendScope.launch {
            _conversationError.value = null

            val conv = _conversations.value.find { it.id == id }
                ?: Conversation(id = id, title = "Antigravity Chat", sessionId = id)
            Log.d("CHAT_OPEN_DEBUG", "👉 [ChatViewModel.selectConversation] Launching stream for conv: title='${conv.title}', id=${conv.id}")
            _currentConversation.value = conv
            knownDaemonCascadeIds.add(id)

            // Connect persistent stream for real-time live streaming.
            // StreamAgentStateUpdates delivers the complete history in Chunk 0!
            startPersistentStream(id)
        }
    }

    fun startPersistentStream(conversationId: String) {
        Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel.startPersistentStream] convId=$conversationId, current activeId=$activeStreamConversationId, jobActive=${persistentStreamJob?.isActive}")
        if (conversationId.isBlank()) return

        // Another agent's chat is on screen: never stream it from the AGY hub
        if (_currentConversation.value?.let { it.id == conversationId && it.agent != AgentKind.AGY } == true) return

        // Brand new unsaved conversation: do not fetch trajectory from backend
        val isNewUnsaved = (_currentConversation.value?.id == conversationId && _currentConversation.value?.title == "New Chat" && _messages.value.isEmpty()) && !_conversations.value.any { it.id == conversationId }
        if (isNewUnsaved) {
            Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel.startPersistentStream] Skipped: $conversationId is a brand new unsaved chat")
            _isLoadingConversation.value = false
            _conversationError.value = null
            return
        }

        if (activeStreamConversationId == conversationId && persistentStreamJob?.isActive == true) {
            Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel.startPersistentStream] Already actively running for $conversationId")
            return
        }

        persistentStreamJob?.cancel()
        activeStreamConversationId = conversationId
        currentTurnStartStep = 0
        totalStepsCount = 0

        if (_messages.value.isEmpty()) {
            _isLoadingConversation.value = true
            _conversationError.value = null
        }

        persistentStreamJob = backendScope.launch(Dispatchers.IO) {
            val hubUrl = AuthPreferences.currentHubUrl
            var isFirstChunk = true

            if (trajectoryEngine.conversationId != conversationId || _messages.value.isEmpty()) {
                trajectoryEngine.reset(conversationId)
            }

            Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel.startPersistentStream] Starting loop for $conversationId on $hubUrl")

            while (activeStreamConversationId == conversationId) {
                try {
                    agyHubClient.streamAgentStateUpdates(conversationId, hubUrl).collect { resp ->
                        if (activeStreamConversationId != conversationId) {
                            Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel Stream] Dropping frame for inactive conversation (active=$activeStreamConversationId vs frame=$conversationId)")
                            return@collect
                        }

                        val update = resp.update
                        val statusStr = update?.status?.name ?: ""
                        val stepsCount = update?.main_trajectory_update?.steps_update?.steps?.size ?: 0
                        Log.d("CHAT_OPEN_DEBUG", "📥 [ChatViewModel Stream Frame] convId=$conversationId, isFirstChunk=$isFirstChunk, status=$statusStr, stepsCount=$stepsCount")
                        isFirstChunk = false
                        val turns = if (update != null) trajectoryEngine.ingestAgentStateUpdate(update) else emptyList()
                        val msgs = trajectoryEngine.toChatMessages(conversationId)


                        withContext(Dispatchers.Main) {
                            if (activeStreamConversationId != conversationId) return@withContext
                            _isServerOnline.value = true
                            _conversationError.value = null
                            _isLoadingConversation.value = false
                            _isReconnecting.value = false

                            val isRunning = trajectoryEngine.isRunning
                            val isWaiting = trajectoryEngine.isWaitingInteraction
                            _isStreaming.value = isRunning && !isWaiting

                            Log.d("CHAT_OPEN_DEBUG", "✨ [ChatViewModel State Update] convId=$conversationId, turnsCount=${turns.size}, msgsCount=${msgs.size}, isRunning=$isRunning, isWaiting=$isWaiting")

                            _messages.value = msgs
                            if (msgs.isNotEmpty()) {
                                com.example.gemini.ui.chat.ChatFeedCache.prewarm(msgs)

                                msgs.flatMap { it.toolCalls }.filter {
                                    it.toolType == com.example.gemini.domain.model.ToolType.GENERATE_IMAGE && it.output.isNotBlank()
                                }.forEach { tc ->
                                    com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(application, tc.output, agyHubClient, hubUrl)
                                }
                                msgs.flatMap { it.attachments }.filter { it.isImage && it.path.isNotBlank() }.forEach { att ->
                                    val rawUri = if (att.path.startsWith("file://") || att.path.startsWith("http")) att.path else "file://${att.path}"
                                    com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(application, rawUri, agyHubClient, hubUrl)
                                }
                            }
                        }
                    }

                    if (isFirstChunk) {
                        if (activeStreamConversationId != conversationId) break
                        val isHubOnline = agyBridgeService.hubStatus.value.status == "online"
                        Log.w("CHAT_OPEN_DEBUG", "⚠️ [ChatViewModel Stream Loop] Stream finished without delivering frames: isFirstChunk=true, isHubOnline=$isHubOnline")
                        withContext(Dispatchers.Main) {
                            _isLoadingConversation.value = false
                            _isReconnecting.value = false
                            val isKnownExisting = _conversations.value.any { it.id == conversationId && it.title != "New Chat" }
                            if (activeStreamConversationId == conversationId) {
                                if (isKnownExisting && _messages.value.isEmpty()) {
                                    _conversationError.value = "No messages received from Antigravity. Tap Retry."
                                    _isServerOnline.value = isHubOnline
                                } else {
                                    _conversationError.value = null
                                }
                            }
                        }
                        break
                    } else {
                        // Normal disconnection after receiving data; clean up in-flight states and stop loop so banner stays stable
                        withContext(Dispatchers.Main) {
                            _isServerOnline.value = false
                            _isReconnecting.value = false
                            if (isPromptInFlight || _isStreaming.value) {
                                isPromptInFlight = false
                                _isStreaming.value = false
                                trajectoryEngine.cancelRunning()
                                markLastAssistantMessageDisconnected()
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        break
                    }
                    Log.e("CHAT_OPEN_DEBUG", "❌ [ChatViewModel Stream Exception] convId=$conversationId: ${e.message}", e)
                    Log.w("ChatViewModel", "Persistent stream error ($conversationId): ${e.message}")
                    val rawErr = e.localizedMessage ?: e.message ?: e.toString()
                    val helpfulMsg = if (rawErr.contains("Connect", ignoreCase = true) || rawErr.contains("Failed to connect", ignoreCase = true)) {
                        "Cannot connect to Antigravity. Make sure Antigravity is running and tap Retry."
                    } else {
                        "Error loading conversation: $rawErr"
                    }

                    withContext(Dispatchers.Main) {
                        _isServerOnline.value = false
                        _isReconnecting.value = false
                        _isLoadingConversation.value = false
                        if (_messages.value.isEmpty()) {
                            _conversationError.value = helpfulMsg
                        }
                        if (isPromptInFlight || _isStreaming.value) {
                            isPromptInFlight = false
                            _isStreaming.value = false
                            trajectoryEngine.cancelRunning()
                            markLastAssistantMessageDisconnected()
                        }
                    }
                    break
                }
            }
        }
    }

    private fun markLastAssistantMessageDisconnected() {
        val list = _messages.value.toMutableList()
        var updated = false
        for (i in list.indices.reversed()) {
            val msg = list[i]
            if (msg.role == MessageRole.ASSISTANT && msg.isStreaming) {
                val newContent = if (msg.content.isBlank() && msg.toolCalls.isEmpty()) {
                    "⚠️ Connection to Antigravity daemon was interrupted."
                } else {
                    msg.content
                }
                list[i] = msg.copy(content = newContent, isStreaming = false)
                updated = true
            }
        }
        if (updated) {
            _messages.value = list
            com.example.gemini.ui.chat.ChatFeedCache.prewarm(list)
        }
    }

    override fun deleteConversation(id: String) {
        backendScope.launch {
            _conversations.value = _conversations.value.filter { it.id != id }
            knownDaemonCascadeIds.remove(id)
            val hubUrl = AuthPreferences.currentHubUrl
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

    override fun forkConversation(id: String) {
        backendScope.launch {
            _isLoadingConversation.value = true
            _conversationError.value = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl
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
        if (conv != null && conv.agent == AgentKind.AGY) {
            val updated = conv.copy(modelId = modelId)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
        val modelObj = _availableModels.value.find { it.id == modelId || it.key == modelId }
        val modelKey = modelObj?.key ?: ""
        val modelName = modelObj?.displayName ?: ""
        backendScope.launch {
            authPrefs.savePreferredModel(modelId = modelId, modelKey = modelKey, displayName = modelName)
        }
    }


    override fun sendMessage(content: String) {
        if ((content.isBlank() && _attachments.value.isEmpty()) || isCurrentChatActivelyRunning.value) return

        val state = systemConnectionState.value
        if (!state.canSend) {
            val reason = when (state.status) {
                com.example.gemini.data.remote.SystemStatus.OFFLINE -> "Server is offline. Please start the server."
                com.example.gemini.data.remote.SystemStatus.STARTING -> "Server is starting up. Please wait..."
                com.example.gemini.data.remote.SystemStatus.ACQUIRING_CSRF -> "Acquiring security token. Please wait..."
                com.example.gemini.data.remote.SystemStatus.CHECKING_AUTH -> "Verifying authentication. Please wait..."
                com.example.gemini.data.remote.SystemStatus.UNAUTHENTICATED -> "Please sign in to send messages."
                com.example.gemini.data.remote.SystemStatus.ERROR -> "Server error. Please check server logs."
                else -> "Cannot send message right now."
            }
            android.widget.Toast.makeText(application, reason, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val currentAtts = _attachments.value
        val finalPrompt = content.trim().ifBlank {
            if (currentAtts.any { it.isAudio }) "Voice note" else if (currentAtts.any { it.isImage }) "Image" else "Attachment"
        }
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

        updatedConv = updatedConv.copy(updatedAt = System.currentTimeMillis())
        _currentConversation.value = updatedConv

        trajectoryEngine.submitUserPrompt(finalPrompt, currentAtts, conv.id)
        val updatedList = trajectoryEngine.toChatMessages(conv.id)
        _messages.value = updatedList
        com.example.gemini.ui.chat.ChatFeedCache.prewarm(updatedList)

        backendScope.launch {
            val exists = _conversations.value.any { it.id == updatedConv.id }
            val newConversations = if (exists) {
                _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
            } else {
                listOf(updatedConv) + _conversations.value
            }
            _conversations.value = newConversations.sortedByDescending { it.updatedAt }

            // Prepare media payload for all attachments (images, audio, files)
            val mediaList = mutableListOf<com.example.gemini.data.remote.AgyHubClient.AgyMediaItem>()
            val hubUrl = AuthPreferences.currentHubUrl

            for (att in currentAtts) {
                var hostPath = if (att.path.isNotBlank() && !att.path.startsWith("/data/") && !att.path.startsWith("content://")) att.path else ""
                val b64 = when {
                    !att.base64.isNullOrBlank() -> att.base64
                    att.path.isNotBlank() && java.io.File(att.path).exists() -> {
                        android.util.Base64.encodeToString(java.io.File(att.path).readBytes(), android.util.Base64.NO_WRAP)
                    }
                    !att.localUri.isNullOrBlank() && java.io.File(android.net.Uri.parse(att.localUri).path ?: "").exists() -> {
                        android.util.Base64.encodeToString(java.io.File(android.net.Uri.parse(att.localUri).path ?: "").readBytes(), android.util.Base64.NO_WRAP)
                    }
                    else -> null
                }

                val ext = (att.name.ifBlank { hostPath }).substringAfterLast('.', "").lowercase()
                val resolvedMime = when {
                    !att.mimeType.isNullOrBlank() -> att.mimeType
                    att.isAudio -> "audio/mp4"
                    att.isImage -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
                    else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }

                if (hostPath.isBlank() && !b64.isNullOrBlank()) {
                    try {
                        val saveRes = agyHubClient.saveMediaAsArtifact(
                            mimeType = resolvedMime,
                            base64Data = b64,
                            description = att.name,
                            thumbnailBase64 = if (att.isImage) b64 else "",
                            hubUrl = hubUrl
                        )
                        if (saveRes.isSuccess) {
                            hostPath = saveRes.getOrThrow()
                        }
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "SaveMediaAsArtifact failed: ${e.message}")
                    }
                }

                val isImageOrAudio = resolvedMime.startsWith("image/") || resolvedMime.startsWith("audio/")
                if (!b64.isNullOrBlank() || hostPath.isNotBlank()) {
                    mediaList.add(
                        com.example.gemini.data.remote.AgyHubClient.AgyMediaItem(
                            mimeType = resolvedMime,
                            base64 = if (isImageOrAudio) (b64 ?: "") else "",
                            durationSeconds = att.durationSeconds,
                            description = att.name.ifBlank { if (att.isAudio) "Voice note" else if (att.isImage) "Image" else "Attachment" },
                            uri = if (hostPath.isNotBlank()) hostPath else null,
                            thumbnail = if (att.isImage) b64 else null
                        )
                    )
                }
            }

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
        backendScope.launch {
            isTranscribingAudio.value = true
            try {
                val bytes = file.readBytes()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val hub = AuthPreferences.currentHubUrl
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
            // Truncate following assistant responses and re-stream
            val truncated = current.take(index + 1)
            _messages.value = truncated
            backendScope.launch {
                executeStream(conv, truncated)
            }
        } else {
            // Assistant response retry: truncate this response and re-execute
            val truncated = current.take(index)
            _messages.value = truncated
            backendScope.launch {
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
        val truncated = current.take(index)
        _messages.value = truncated
        return targetMsg.content
    }

    /**
     * Reverts a user message: restores prompt text and attachments to the input box,
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
            val defaultProjPath = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path ?: ""
            val defaultWorkspaceUri = if (defaultProjPath.isNotBlank()) {
                if (defaultProjPath.startsWith("file://")) defaultProjPath else "file://$defaultProjPath"
            } else ""
            _currentConversation.value = Conversation(
                id = UUID.randomUUID().toString(),
                title = "New Chat",
                modelId = modelToUse,
                sessionId = UUID.randomUUID().toString(),
                workspaceUri = defaultWorkspaceUri
            )
        }

        backendScope.launch(Dispatchers.IO) {
            val hubUrl = AuthPreferences.currentHubUrl
            val modelEnum = com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
            val res = agyHubClient.revertUserMessage(conv.id, modelEnum, targetMsg.stepIndex, hubUrl)
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
                android.util.Log.w("ChatViewModel", "revertUserMessage failed on hub: ${res.exceptionOrNull()?.message}")
                if (!isFirstUserMsg) {
                    withContext(Dispatchers.Main) {
                        startPersistentStream(conv.id)
                    }
                }
            }
        }
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

        val hubUrl = AuthPreferences.currentHubUrl
        val userPrompt = currentHistory.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val modelEnum = com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
        val selectedModel = _enabledModels.value.find { it.id == modelEnum }
        val supportsThinking = selectedModel?.supportsThinking ?: (modelEnum.contains("thinking", ignoreCase = true) || modelEnum.contains("flash", ignoreCase = true) || modelEnum.contains("pro", ignoreCase = true))
        val thinkingBudget = if (supportsThinking) 8192 else 0

        val convWorkspace = conv.workspaceUri
        val workspaceUri = if (convWorkspace.isNotBlank()) {
            if (convWorkspace.startsWith("file://")) convWorkspace else "file://$convWorkspace"
        } else ""

        val isKnownOnDaemon = knownDaemonCascadeIds.contains(conv.id) ||
                (totalStepsCount > 0 && conv.title != "New Chat" && conv.title != "Antigravity Chat" && conv.title.isNotBlank())

        backendScope.launch(Dispatchers.IO) {
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
                        val formattedErr = formatStructuredErrorMessage(
                            title = if (err.contains("auth", ignoreCase = true)) "Authentication Required" else "Failed to Start Conversation",
                            userMessage = err,
                            shortError = err
                        )
                        withContext(Dispatchers.Main) {
                            isPromptInFlight = false
                            _isStreaming.value = false
                            hasSeenTurnActivity = false
                            trajectoryEngine.cancelRunning()
                            updateAssistantMessage(
                                msgId = assistantMsgId,
                                content = formattedErr,
                                isStreaming = false,
                                forceImmediate = true
                            )
                        }
                        return@launch
                    }
                    knownDaemonCascadeIds.add(conv.id)
                    if (workspaceUri.isNotBlank()) {
                        val projName = java.io.File(workspaceUri.removePrefix("file://")).name
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
                    val formattedErr = formatStructuredErrorMessage(
                        title = when {
                            err.contains("auth", ignoreCase = true) -> "Authentication Required"
                            err.contains("quota", ignoreCase = true) || err.contains("credit", ignoreCase = true) || err.contains("429") -> "Quota Limit Exceeded"
                            err.contains("model not found", ignoreCase = true) -> "Model Not Found"
                            err.contains("connect", ignoreCase = true) || err.contains("network", ignoreCase = true) -> "Server Connection Error"
                            else -> "Message Delivery Error"
                        },
                        userMessage = err,
                        shortError = err
                    )
                    withContext(Dispatchers.Main) {
                        _isServerOnline.value = false
                        isPromptInFlight = false
                        _isStreaming.value = false
                        hasSeenTurnActivity = false
                        trajectoryEngine.cancelRunning()
                        updateAssistantMessage(
                            msgId = assistantMsgId,
                            content = formattedErr,
                            isStreaming = false,
                            forceImmediate = true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "sendUserPrompt exception: ${e.message}", e)
                val formattedErr = formatStructuredErrorMessage(
                    title = "Execution Failure",
                    userMessage = e.message ?: "Unknown error",
                    shortError = e.message ?: "",
                    fullError = e.stackTraceToString()
                )
                withContext(Dispatchers.Main) {
                    _isServerOnline.value = false
                    isPromptInFlight = false
                    _isStreaming.value = false
                    hasSeenTurnActivity = false
                    trajectoryEngine.cancelRunning()
                    updateAssistantMessage(
                        msgId = assistantMsgId,
                        content = formattedErr,
                        isStreaming = false,
                        forceImmediate = true
                    )
                }
            }
        }
    }

    private fun formatStructuredErrorMessage(
        title: String,
        userMessage: String,
        shortError: String = "",
        fullError: String = "",
        errorCode: Int? = null,
        errorId: String = ""
    ): String {
        return try {
            val json = org.json.JSONObject().apply {
                put("title", title)
                put("userMessage", userMessage)
                put("shortError", shortError.ifBlank { userMessage })
                put("fullError", fullError)
                if (errorCode != null) put("errorCode", errorCode)
                if (errorId.isNotBlank()) put("errorId", errorId)
            }.toString()
            "<!-- error -->\n$json\n<!-- /error -->"
        } catch (_: Exception) {
            "⚠️ $title: $userMessage"
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
        } else {
            val conv = _currentConversation.value
            if (conv != null) {
                val newMsg = ChatMessage(
                    id = msgId,
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    toolCalls = toolCalls,
                    isStreaming = isStreaming
                )
                list.add(newMsg)
                _messages.value = list
            }
        }
        if (!isStreaming) {
            com.example.gemini.ui.chat.ChatFeedCache.prewarm(_messages.value)
        }
    }

    override fun stopStreaming() {
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
            backendScope.launch {
                val hubUrl = AuthPreferences.currentHubUrl
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

    fun refreshQuotas(force: Boolean = true, showToastFeedback: Boolean = false) {
        backendScope.launch {
            _isRefreshingModels.value = true
            var failureError: String? = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl
                val modelsDeferred = async { agyHubClient.getAvailableModels(forceRefresh = force, hubUrl = hubUrl) }
                val quotasDeferred = async { agyHubClient.retrieveUserQuotaSummary(forceRefresh = force, hubUrl = hubUrl) }

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
                } else {
                    failureError = modelsRes.exceptionOrNull()?.message
                }

                if (quotasRes.isSuccess) {
                    applyQuotaSummary(quotasRes.getOrThrow())
                } else if (failureError == null) {
                    failureError = quotasRes.exceptionOrNull()?.message
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
                failureError = e.message
            } finally {
                _isRefreshingModels.value = false
                if (showToastFeedback) {
                    withContext(Dispatchers.Main) {
                        if (failureError != null) {
                            val msg = if (failureError.contains("Connect", ignoreCase = true) || failureError.contains("failed to connect", ignoreCase = true)) {
                                "Failed to refresh: Antigravity Hub (${AuthPreferences.currentHubUrl}) unreachable"
                            } else {
                                "Failed to refresh: $failureError"
                            }
                            android.widget.Toast.makeText(application, msg, android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            android.widget.Toast.makeText(application, "Quotas & models refreshed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
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

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
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

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
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

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
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

        backendScope.launch {
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

    fun submitUserChoices(
        toolCall: com.example.gemini.domain.model.ToolCall,
        messageId: String,
        responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>,
        summaryDisplay: String
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = com.example.gemini.data.remote.dto.CortexStepStatuses.DONE,
                output = summaryDisplay
            )
            _messages.value = trajectoryEngine.toChatMessages(conv.id)
        }

        val msg = _messages.value.find { it.id == messageId }
        val completedToolCall = toolCall.copy(
            status = "SUCCESS",
            output = summaryDisplay
        )
        if (msg != null) {
            val updatedToolCalls = msg.toolCalls.map { if (it.stepIndex != null && it.stepIndex == toolCall.stepIndex) completedToolCall else it }
            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = updatedToolCalls,
                isStreaming = true
            )
        }

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            if (stepIndex != null) {
                val res = agyHubClient.handleAskQuestionInteraction(
                    cascadeId = conv.id,
                    stepIndex = stepIndex,
                    trajectoryId = toolCall.trajectoryId ?: "",
                    responses = responses,
                    hubUrl = hubUrl
                )
                if (res.isFailure) {
                    Log.e("ChatViewModel", "handleAskQuestionInteraction failed: ${res.exceptionOrNull()}")
                }
            }
        }
    }

    fun skipUserChoices(
        toolCall: com.example.gemini.domain.model.ToolCall,
        messageId: String,
        responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto> = emptyList()
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex
        val skipSummary = "Skipped by user"

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = com.example.gemini.data.remote.dto.CortexStepStatuses.DONE,
                output = skipSummary
            )
            _messages.value = trajectoryEngine.toChatMessages(conv.id)
        }

        val msg = _messages.value.find { it.id == messageId }
        val completedToolCall = toolCall.copy(
            status = "SUCCESS",
            output = skipSummary
        )
        if (msg != null) {
            val updatedToolCalls = msg.toolCalls.map { if (it.stepIndex != null && it.stepIndex == toolCall.stepIndex) completedToolCall else it }
            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = updatedToolCalls,
                isStreaming = true
            )
        }

        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            if (stepIndex != null) {
                val actualResponses = if (responses.isNotEmpty()) {
                    responses
                } else {
                    val questionnaire = toolCall.questionnaire
                    questionnaire?.questions?.map { q ->
                        com.example.gemini.data.remote.dto.AskQuestionResponseItemDto(
                            question = q.prompt,
                            options = q.options.map { com.example.gemini.data.remote.dto.AskQuestionOptionDto(id = it.id, text = it.label) },
                            isMultiSelect = if (q.isMultiSelect) true else null,
                            skipped = true
                        )
                    } ?: emptyList()
                }

                val res = agyHubClient.handleAskQuestionInteraction(
                    cascadeId = conv.id,
                    stepIndex = stepIndex,
                    trajectoryId = toolCall.trajectoryId ?: "",
                    responses = actualResponses,
                    hubUrl = hubUrl
                )
                if (res.isFailure) {
                    Log.e("ChatViewModel", "handleAskQuestionInteraction (skip) failed: ${res.exceptionOrNull()}")
                }
            }
        }
    }

    fun cancelUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex
        val hubUrl = AuthPreferences.currentHubUrl

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = com.example.gemini.data.remote.dto.CortexStepStatuses.CANCELED,
                output = "Questionnaire cancelled by user."
            )
            _messages.value = trajectoryEngine.toChatMessages(conv.id)
        }

        val msg = _messages.value.find { it.id == messageId }
        val canceledToolCall = toolCall.copy(
            status = "CANCELED",
            output = "Cancelled by user."
        )
        if (msg != null) {
            val updatedToolCalls = msg.toolCalls.map { if (it.stepIndex != null && it.stepIndex == toolCall.stepIndex) canceledToolCall else it }
            updateAssistantMessage(
                msgId = messageId,
                content = msg.content,
                thought = msg.thoughtText ?: "",
                thoughtDuration = msg.thoughtDurationMs,
                toolCalls = updatedToolCalls,
                isStreaming = false
            )
        }

        backendScope.launch {
            val res = agyHubClient.cancelCascadeInvocation(conv.id, hubUrl)
            if (res.isFailure) {
                Log.e("ChatViewModel", "cancelCascadeInvocation failed: ${res.exceptionOrNull()}")
            }
        }
    }

    fun resetQuotaState() {
        _quotas.value = emptyList()
        _quotaSummary.value = null
    }

    /** Stops streaming the current AGY chat because another agent's chat is being opened. */
    fun detach() {
        persistentStreamJob?.cancel()
        persistentStreamJob = null
        activeStreamConversationId = null
        currentAssistantMsgId = null
        isPromptInFlight = false
        hasStartedRunning = false
        hasSeenTurnActivity = false
        _isStreaming.value = false
        _isLoadingConversation.value = false
        _bridgeStatusMessage.value = null
    }

    fun dispose() {
        persistentStreamJob?.cancel()
        streamingJob?.cancel()
    }
}
