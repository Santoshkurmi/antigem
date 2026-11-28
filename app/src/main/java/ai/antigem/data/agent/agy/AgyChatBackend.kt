package ai.antigem.data.agent.agy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.domain.model.AiModel
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.ChatAttachment
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.domain.model.ModelQuota
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
import ai.antigem.data.agent.AgentChatBackend
import ai.antigem.domain.model.AgentKind
import ai.antigem.data.agent.ChatSessionStore
import ai.antigem.ui.chat.PendingToolApproval
import kotlinx.coroutines.CoroutineScope
import ai.antigem.i18n.L

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

    val agyBridgeService = ai.antigem.data.remote.AgyBridgeService.instance
    val systemConnectionState: StateFlow<ai.antigem.data.remote.SystemConnectionState> = agyBridgeService.systemConnectionState
    val hubStatus: StateFlow<ai.antigem.data.remote.AgyHubStatus> = agyBridgeService.hubStatus
    val agyHubClient = ai.antigem.data.remote.AgyHubClient()
    val trajectoryEngine = ai.antigem.domain.chat.TrajectoryEngine()
    val speechManager = ai.antigem.data.audio.AgyAudioTranscriptionManager(agyHubClient) {
        AuthPreferences.currentHubUrl
    }

    private val _hasReceivedInitialSync = MutableStateFlow(false)
    val hasReceivedInitialSync: StateFlow<Boolean> = _hasReceivedInitialSync.asStateFlow()

    private val _isConversationsLoading = MutableStateFlow(true)
    val isConversationsLoading: StateFlow<Boolean> = _isConversationsLoading.asStateFlow()

    val artifacts: StateFlow<List<ai.antigem.domain.model.ArtifactSnapshot>> = trajectoryEngine.artifacts

    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _messages.map { msgs ->
        msgs.filter { it.role == ai.antigem.domain.model.MessageRole.ASSISTANT }
            .flatMap { msg ->
                msg.toolCalls.filter { it.status == "PENDING_APPROVAL" && it.toolType != ai.antigem.domain.model.ToolType.ASK_CHOICE }
                    .distinctBy { it.stepIndex }
                    .map { PendingToolApproval(it, msg.id) }
            }
    }.stateIn(backendScope, SharingStarted.Eagerly, emptyList())

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(AiModel.DEFAULT_MODELS)
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    init {
        // the usage pill under a reply names its model, as the "Switched to …" divider does
        trajectoryEngine.modelDisplayName = { id ->
            _availableModels.value.firstOrNull { it.id == id || it.key == id }?.displayName?.takeIf { it.isNotBlank() } ?: id
        }
    }

    private val _enabledModelIds = MutableStateFlow<Set<String>?>(null)
    val enabledModelIds: StateFlow<Set<String>?> = _enabledModelIds.asStateFlow()

    private val _enabledModels = MutableStateFlow<List<AiModel>>(AiModel.DEFAULT_MODELS)
    val enabledModels: StateFlow<List<AiModel>> = _enabledModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow("")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    /** The model the user last picked (the saved default); new chats start on it. */
    @Volatile private var pickedModelId = ""

    val preferredModelName: StateFlow<String> = authPrefs.preferredModelName
        .map { it ?: "" }
        .stateIn(backendScope, SharingStarted.Eagerly, "")

    private val _quotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    val quotas: StateFlow<List<ModelQuota>> = _quotas.asStateFlow()

    private val _quotaSummary = MutableStateFlow<ai.antigem.domain.model.QuotaSummaryResponse?>(null)
    val quotaSummary: StateFlow<ai.antigem.domain.model.QuotaSummaryResponse?> = _quotaSummary.asStateFlow()

    init {
        // moving a model to another quota group, or switching the pill between 5h and 7d, updates the pill right away
        ai.antigem.domain.model.ModelGroupPrefs.init(application)
        backendScope.launch {
            combine(
                ai.antigem.domain.model.ModelGroupPrefs.overrides(ai.antigem.domain.model.QuotaAgent.AGY),
                ai.antigem.domain.model.ModelGroupPrefs.pillWindow(ai.antigem.domain.model.QuotaAgent.AGY)
            ) { _, _ -> }.collect { recomputeQuotas() }
        }
    }

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
            val reachable = ai.antigem.data.remote.AgyBridgeService.instance.checkServerHealth(bridgeUrl)
            _isBridgeOnline.value = reachable
        }
    }

    private val _agyAuthInfo = MutableStateFlow(ai.antigem.data.remote.AgyHubClient.AgyAuthInfo(status = ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.CHECKING))
    val agyAuthInfo: StateFlow<ai.antigem.data.remote.AgyHubClient.AgyAuthInfo> = _agyAuthInfo.asStateFlow()

    private val _isAuthBusy = MutableStateFlow(false)
    val isAuthBusy: StateFlow<Boolean> = _isAuthBusy.asStateFlow()

    private val _authFeedbackMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val authFeedbackMessage: SharedFlow<String> = _authFeedbackMessage.asSharedFlow()

    private val _pendingLoginUrl = MutableStateFlow<String?>(null)
    val pendingLoginUrl: StateFlow<String?> = _pendingLoginUrl.asStateFlow()

    fun clearPendingLoginUrl() {
        _pendingLoginUrl.value = null
    }

    /** Why agy refused the account after the Google sign-in; [verificationUrl] only when the account can fix it there. */
    data class AgyLoginIssue(val message: String, val verificationUrl: String? = null)

    private val _agyLoginIssue = MutableStateFlow<AgyLoginIssue?>(null)
    val agyLoginIssue: StateFlow<AgyLoginIssue?> = _agyLoginIssue.asStateFlow()

    fun clearAgyLoginIssue() {
        _agyLoginIssue.value = null
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

    /** [accountChanged]: the agy login was swapped; quotas and models are reloaded for the new account. */
    fun checkAgyAuthStatus(userInitiated: Boolean = false, accountChanged: Boolean = false) {
        backendScope.launch {
            val emailBefore = _agyAuthInfo.value.email.takeIf { _agyAuthInfo.value.isLoggedIn }
            // "Checking sign-in…" only while the sign-in state is unknown (or the user asked). A background re-check of
            // a confirmed sign-in stays silent: the status only changes if the answer differs (signed out).
            val alreadySignedIn = _agyAuthInfo.value.isLoggedIn && !_agyAuthInfo.value.isOffline
            if (userInitiated || !alreadySignedIn) agyBridgeService.updateAuthChecking(true)
            if (!isNetworkConnected()) {
                android.util.Log.d("ChatViewModel", "Skipping auth status check: device is offline.")
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
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
                    } else if (accountChanged && info.email.isNotBlank() && !info.email.equals(emailBefore, ignoreCase = true)) {
                        _authFeedbackMessage.tryEmit("Antigravity now uses ${info.email}")
                    }
                    refreshQuotas(force = accountChanged)
                } else if (userInitiated) {
                    _authFeedbackMessage.tryEmit("Not signed in.")
                }
            } else {
                _agyAuthInfo.value = _agyAuthInfo.value.copy(
                    status = ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE,
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

    /** Waits for the hub to report a signed-in account; keeps the "Signing in…" state on until it does or time runs out. */
    private fun startLoginPoll(hubUrl: String) {
        _isAuthBusy.value = true
        agyBridgeService.updateAuthChecking(true)
        loginPollJob?.cancel()
        loginPollJob = backendScope.launch {
                val startTime = System.currentTimeMillis()
                // a remote hub needs time for the user to paste the redirect address
                val maxWaitMs = if (AuthPreferences.currentRemoteMode) 420_000L else 120_000L
                while (isActive && System.currentTimeMillis() - startTime < maxWaitMs) {
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
    }

    /** Remote sign-in: the pasted address reached the server, so the sign-in is finishing: show "Signing in…" until it is done. */
    fun onLoginCallbackForwarded() {
        startLoginPoll(AuthPreferences.currentHubUrl)
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
            startLoginPoll(hubUrl)

            // 2. Kick off login RPC on daemon (Login RPC opens browser / triggers auth flow)
            launch {
                try {
                    val authResult = agyHubClient.login(hubUrl).getOrNull()
                    if (authResult != null && !authResult.has_valid_auth) {
                        val verificationUrl = authResult.ineligible?.verification_url?.takeIf { it.isNotBlank() }
                            ?: authResult.verification_required?.verification_url?.takeIf { it.isNotBlank() }
                        // Google sign-in worked but agy refused the account (e.g. "not eligible: must be 18 or
                        // older", often with no link): say why instead of polling on until "not signed in".
                        val reason = listOfNotNull(
                            authResult.ui_message,
                            authResult.ineligible?.reason,
                            authResult.general_error?.reason,
                            authResult.tos_violation?.message
                        ).firstOrNull { it.isNotBlank() }
                        if (verificationUrl != null || reason != null) {
                            loginPollJob?.cancel()
                            _isAuthBusy.value = false
                            _pendingLoginUrl.value = null
                            agyBridgeService.updateAuthChecking(false)
                            _agyLoginIssue.value = AgyLoginIssue(
                                message = reason ?: L.agent.agyChatBackend.yourAccountNeedsVerificationBeforeYou,
                                verificationUrl = verificationUrl
                            )
                        }
                    }
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
                _agyAuthInfo.value = ai.antigem.data.remote.AgyHubClient.AgyAuthInfo(
                    status = ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.UNAUTHENTICATED,
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

    private val _activeInstances = MutableStateFlow<List<ai.antigem.data.remote.AgyActiveInstance>>(emptyList())
    val activeInstances: StateFlow<List<ai.antigem.data.remote.AgyActiveInstance>> = _activeInstances.asStateFlow()

    val isAnyGenerationOrTaskActive: StateFlow<Boolean> = combine(
        _activeInstances,
        _conversations
    ) { instances, convs ->
        instances.isNotEmpty() || convs.any { it.isRunning || it.notFullyIdle || it.hasActivity }
    }.stateIn(backendScope, SharingStarted.WhileSubscribed(5000), false)

    // Conversation running & background states derived from the sidebar summary stream (_conversations) and the
    // open chat's live stream (StreamAgentStateUpdates). Both use the hub rule (`status == RUNNING`) for running,
    // and `!fullyIdle` / `hasActivity` for background work. Without a stream frame yet, the summary decides.
    private data class StreamRunState(val conversationId: String, val loopRunning: Boolean, val fullyIdle: Boolean)
    private val _streamRun = MutableStateFlow<StreamRunState?>(null)

    private var quotaAfterRunJob: Job? = null

    init {
        // A finished run used quota: when the last running chat (the open one or one in the background) stops,
        // fetch the quota numbers again so the model picker and the chat pill show what is left now.
        backendScope.launch {
            combine(
                _conversations.map { list -> list.any { it.isRunning } },
                _streamRun.map { it?.loopRunning == true }
            ) { anyChat, openChat -> anyChat || openChat }
                .distinctUntilChanged()
                .drop(1)
                .collect { running -> if (!running) refreshQuotaAfterRun() }
        }
    }

    /** Only the quota summary (not the model list, so no spinner); runs ending close together refresh once. */
    private fun refreshQuotaAfterRun() {
        quotaAfterRunJob?.cancel()
        quotaAfterRunJob = backendScope.launch {
            delay(3_000) // the server books a run's usage a moment after it ends
            agyHubClient.retrieveUserQuotaSummary(forceRefresh = true, hubUrl = AuthPreferences.currentHubUrl)
                .onSuccess { applyQuotaSummary(it) }
                .onFailure { Log.w("AgyQuota", "quota refresh after run failed: ${it.message}") }
        }
    }

    // A just-sent (not queued) message shows the chat as running right away, until the stream confirms it
    // (the hub web UI does the same), so the Send button turns into Stop at once.
    private data class OptimisticSend(val conversationId: String, val tag: String)
    private val _optimisticSend = MutableStateFlow<OptimisticSend?>(null)
    private var optimisticTimeoutJob: Job? = null

    val isCurrentChatActivelyRunning: StateFlow<Boolean> = combine(
        _conversations,
        _currentConversation,
        systemConnectionState,
        _streamRun,
        _optimisticSend
    ) { convList, current, conn, stream, optimistic ->
        val curId = current?.id ?: return@combine false
        if (!conn.isHubOnline) return@combine false
        if (optimistic?.conversationId == curId) return@combine true
        stream?.takeIf { it.conversationId == curId }?.loopRunning
            ?: (convList.find { it.id == curId } ?: current).isRunning
    }.distinctUntilChanged()
    .stateIn(backendScope, SharingStarted.Eagerly, false)

    val isCurrentChatBackgroundActive: StateFlow<Boolean> = combine(
        combine(_conversations, _currentConversation, ::Pair),
        systemConnectionState,
        _activeInstances,
        _streamRun,
        isCurrentChatActivelyRunning
    ) { (convList, current), conn, instances, stream, activelyRunning ->
        val curId = current?.id ?: return@combine false
        val conv = convList.find { it.id == curId } ?: current
        val activeInst = instances.find { it.conversationId == curId }
        val streamBusy = stream?.takeIf { it.conversationId == curId }?.let { !it.fullyIdle } == true
        // a summary still RUNNING while the loop is idle is background work (subagents, background tasks)
        conn.isHubOnline && !activelyRunning &&
            (streamBusy || conv.isRunning || conv.notFullyIdle || conv.hasActivity || activeInst != null)
    }.distinctUntilChanged()
    .stateIn(backendScope, SharingStarted.Eagerly, false)

    // ---- queued messages (sent while the agent was working; the daemon delivers them when it is done)
    private val _queuedMessages = MutableStateFlow<List<ai.antigem.domain.model.QueuedMessage>>(emptyList())
    val queuedMessages: StateFlow<List<ai.antigem.domain.model.QueuedMessage>> = _queuedMessages.asStateFlow()
    // queued sends from this app the daemon's queue does not list yet: chat id → (client tag → entry)
    private val localQueued = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, ai.antigem.domain.model.QueuedMessage>>()
    // server queue entries removed here, hidden until the daemon's queue drops them
    private val hiddenQueuedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val connectionState: StateFlow<ai.antigem.data.remote.BridgeConnectionState> = combine(_isServerOnline, isCurrentChatActivelyRunning) { online, streaming ->
        when {
            online == false -> ai.antigem.data.remote.BridgeConnectionState.OFFLINE_ERROR
            streaming -> ai.antigem.data.remote.BridgeConnectionState.STREAMING
            online == true -> ai.antigem.data.remote.BridgeConnectionState.CONNECTED_READY
            else -> ai.antigem.data.remote.BridgeConnectionState.CONNECTING
        }
    }.stateIn(backendScope, SharingStarted.Eagerly, ai.antigem.data.remote.BridgeConnectionState.CONNECTING)

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
                        // the bridge answers: learn its version (the app warns when it differs from the app's)
                        ai.antigem.data.remote.BridgeInfo.refreshVersionIfUnknown()
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
                                _conversationError.value = L.agent.agyChatBackend.antigravityHubFailedToStartValue(status.error ?: L.agent.agyChatBackend.checkServerLogs)
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
                    ai.antigem.data.remote.BridgeInfo.reset()
                    delay(1000)
                }
            }
        }

        backendScope.launch {
            agyBridgeService.loginUrlEvents.collect { url ->
                if (url.isNotBlank()) {
                    _pendingLoginUrl.value = url
                    // a sign-in has started (even one the server started): show "Signing in…" until it finishes
                    if (!_isAuthBusy.value && !_agyAuthInfo.value.isLoggedIn) startLoginPoll(AuthPreferences.currentHubUrl)
                }
            }
        }

        // The agy account changed (switched in Settings, the Agy Accounts mini app or a terminal): agy already uses the
        // new login, so ask it now instead of waiting for the next check. collectLatest: a burst becomes one check.
        backendScope.launch {
            agyBridgeService.agyAccountEvents.collectLatest {
                delay(300)
                checkAgyAuthStatus(userInitiated = false, accountChanged = true)
            }
        }
        ai.antigem.data.remote.AgyAccounts.replyRunning = {
            _activeInstances.value.isNotEmpty() || _conversations.value.any { it.isRunning }
        }

        backendScope.launch {
            val savedId = authPrefs.preferredModelId.firstOrNull()
            val savedKey = authPrefs.preferredModelKey.firstOrNull()
            val initial = savedId ?: savedKey ?: ""
            if (initial.isNotBlank() && _selectedModelId.value.isBlank()) {
                _selectedModelId.value = initial
            }
            if (initial.isNotBlank() && pickedModelId.isBlank()) pickedModelId = initial
            // the start-up chat: only when nothing is open yet or it is still an empty Antigravity chat (a Claude
            // chat opened meanwhile must not be replaced)
            val cur = _currentConversation.value
            if (cur == null || (cur.agent == AgentKind.AGY && cur.title == "New Chat" && _messages.value.isEmpty())) {
                startNewChat()
            }
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
    private var cloudSyncJob: Job? = null
    private val summariesLock = Any()

    /** Cloud chat id → the newest updatedAt it was given (kept across a stream's reconnects). Under [summariesLock]. */
    private val cloudUpdatedAt = HashMap<String, Long>()

    /**
     * [c] with the newest time seen for it, for a cloud chat: both chat lists (the hub's own and the cloud one) carry
     * cloud chats, sometimes with an older time (the hub re-sends remembered summaries, reconnects resend all), so
     * taking each frame's time as it came moved the chat up and down the list. Other chats are returned as they are.
     * Call under [summariesLock].
     */
    private fun steadyCloudTime(c: Conversation): Conversation {
        if (!c.isCloud) return c
        val seen = cloudUpdatedAt[c.id] ?: 0L
        // (no time at all yet: now, once, then it stays)
        val time = maxOf(seen, c.updatedAt).takeIf { it > 0L } ?: System.currentTimeMillis()
        if (time != seen) cloudUpdatedAt[c.id] = time
        return if (time == c.updatedAt) c else c.copy(updatedAt = time)
    }

    /**
     * Keeps the Antigravity cloud chats (Remote Agents) in the chat list, next to the hub's own. Quietly does nothing
     * for an account or hub without cloud agents.
     */
    @Volatile private var cloudSyncHealthy = false

    private fun syncCloudConversations(force: Boolean) {
        // the cloud project list loaded (the hub is ready for cloud calls): restart a cloud list that never got going
        AgyCloud.onAvailable = { if (!cloudSyncHealthy) syncCloudConversations(force = true) }
        // Settings → Antigravity → Cloud agents switched on or off
        AgyCloud.onEnabledChanged = { syncCloudConversations(force = true) }
        if (!AgyCloud.enabled.value) {
            // off: no cloud calls to the hub, and no cloud chats in the list (the open one stays until left)
            cloudSyncJob?.cancel()
            cloudSyncJob = null
            cloudSyncHealthy = false
            val activeId = _currentConversation.value?.id
            synchronized(summariesLock) {
                val all = _conversations.value
                if (all.any { it.isCloud && it.id != activeId }) {
                    _conversations.value = all.filter { !it.isCloud || it.id == activeId }
                }
            }
            return
        }
        if (!force && cloudSyncJob?.isActive == true) return
        cloudSyncJob?.cancel()
        cloudSyncHealthy = false
        cloudSyncJob = backendScope.launch {
            var backoff = 15_000L
            var warmups = 0
            while (currentCoroutineContext().isActive) {
                try {
                    agyHubClient.subscribeToSummaries(AuthPreferences.currentHubUrl, cloud = true).collect { update ->
                        backoff = 15_000L
                        cloudSyncHealthy = true
                        val activeId = _currentConversation.value?.id
                        val byId = synchronized(summariesLock) {
                            val map = _conversations.value.associateBy { it.id }.toMutableMap()
                            for (id in update.removedIds) {
                                if (map[id]?.isCloud == true && (id != activeId || _messages.value.isEmpty())) {
                                    map.remove(id)
                                    knownDaemonCascadeIds.remove(id)
                                }
                            }
                            for (c in update.updated) {
                                map[c.id] = steadyCloudTime(c)
                                knownDaemonCascadeIds.add(c.id)
                            }
                            _conversations.value = map.values.sortedByDescending { it.updatedAt }
                            map
                        }
                        // the open cloud chat follows its title and running state
                        val curr = _currentConversation.value
                        val fresh = activeId?.let { byId[it] }
                        if (curr != null && fresh != null && fresh.isCloud && curr.id == fresh.id) {
                            val title = fresh.title.takeIf { it.isNotBlank() && it != "Conversation" && it != "New Chat" } ?: curr.title
                            if (title != curr.title || curr.isRunning != fresh.isRunning || curr.notFullyIdle != fresh.notFullyIdle) {
                                _currentConversation.value = curr.copy(
                                    title = title, isRunning = fresh.isRunning, notFullyIdle = fresh.notFullyIdle,
                                    hasActivity = fresh.hasActivity, cloudProjectId = curr.cloudProjectId ?: fresh.cloudProjectId
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) break
                    val msg = e.message.orEmpty()
                    android.util.Log.w("AgyCloud", "cloud chat list: $msg")
                    cloudSyncHealthy = false
                    // no cloud agents for this account / hub: stop asking
                    if (msg.contains("PERMISSION_DENIED", true) || msg.contains("unimplemented", true)) break
                    // the hub is still starting (account switches not loaded): ask again soon
                    if (AgyCloud.isWarmingUp(msg) && warmups < 60) {
                        warmups++
                        delay(3_000L)
                        continue
                    }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    fun syncAgyConversations(force: Boolean = false) {
        syncCloudConversations(force)
        if (!force && syncJob?.isActive == true) return
        syncJob?.cancel()
        syncJob = backendScope.launch {
            _isConversationsLoading.value = true
            while (currentCoroutineContext().isActive) {
                try {
                    val hubUrl = AuthPreferences.currentHubUrl
                    agyHubClient.subscribeToSummaries(hubUrl).collect { update ->
                        val activeId = _currentConversation.value?.id
                        // the cloud chats' list (syncCloudConversations) writes the same list: one at a time
                        val (currentMap, sortedConvs) = synchronized(summariesLock) {
                            val currentMap = _conversations.value.associateBy { it.id }.toMutableMap()

                            // Remove empty, deleted, or abandoned sessions
                            for (delId in update.removedIds) {
                                if (delId != activeId || _messages.value.isEmpty()) {
                                    currentMap.remove(delId)
                                    // the open chat stays known: one with only a voice call has no steps, so the hub lists
                                    // it as empty, but it exists there and starting it again fails ("already exists")
                                    if (delId != activeId) knownDaemonCascadeIds.remove(delId)
                                }
                            }

                            // Add or update valid conversations
                            for (conv in update.updated) {
                                // a cloud chat the hub lists itself, while cloud agents are off in Settings
                                if (conv.isCloud && !AgyCloud.enabled.value && conv.id != activeId) continue
                                currentMap[conv.id] = steadyCloudTime(conv)
                                knownDaemonCascadeIds.add(conv.id)
                            }

                            val sortedConvs = currentMap.values.sortedByDescending { it.updatedAt }
                            _conversations.value = sortedConvs
                            currentMap to sortedConvs
                        }
                        // cloud chats run in Google's VM: their folders are not projects on this machine
                        ai.antigem.data.daemon.IdeDaemonManager.loadProjects(sortedConvs.filterNot { it.isCloud })

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
                        if (!_agyAuthInfo.value.isLoggedIn || _agyAuthInfo.value.status == ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE || _agyAuthInfo.value.status == ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.CHECKING) {
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
                        L.agent.agyChatBackend.cannotConnectToAntigravityHubUrl(AuthPreferences.currentHubUrl)
                    } else {
                        L.agent.agyChatBackend.antigravityHubUnreachableRawerr(rawErr)
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
            ai.antigem.data.daemon.IdeDaemonManager.checkHealthAndReconnect(isSilent = false)
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
            if (!_agyAuthInfo.value.isLoggedIn || _agyAuthInfo.value.status == ai.antigem.data.remote.AgyHubClient.AgyAuthStatus.CHECKING) {
                checkAgyAuthStatus(userInitiated = false)
            }
            ai.antigem.data.daemon.IdeDaemonManager.checkHealthAndReconnect(isSilent = true)
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
        clearOptimisticSend()
        _streamRun.value = null
        _queuedMessages.value = emptyList()
        // a new chat starts on the model the user last picked, not on the one an opened chat showed
        _availableModels.value.firstOrNull { it.id == pickedModelId || it.key == pickedModelId }
            ?.let { _selectedModelId.value = it.id }

        val modelToUse = if (_selectedModelId.value.isNotBlank()) {
            ai.antigem.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
        } else {
            (_enabledModels.value.firstOrNull()?.id ?: "")
        }
        val defaultProjPath = ai.antigem.data.daemon.IdeDaemonManager.activeProject.value?.path ?: ""
        val defaultWorkspaceUri = if (defaultProjPath.isNotBlank()) {
            if (defaultProjPath.startsWith("file://")) defaultProjPath else "file://$defaultProjPath"
        } else ""
        // a cloud project picked in the project chooser: the chat runs in Google's cloud (Remote Agents)
        val cloudProject = AgyCloud.selectedProjectId.value?.takeIf { AgyCloud.enabled.value }
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelToUse,
            sessionId = UUID.randomUUID().toString(),
            workspaceUri = if (cloudProject != null) "" else defaultWorkspaceUri,
            cloudProjectId = cloudProject
        )
        _currentConversation.value = newConv
        trajectoryEngine.reset(newConv.id, force = true)
        ai.antigem.ui.components.ToolCallExpansionCache.setChat(newConv.id)
        ai.antigem.ui.components.CodeBlockExpansionCache.setChat(newConv.id)
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
        ai.antigem.ui.components.ToolCallExpansionCache.setChat(id)
        ai.antigem.ui.components.CodeBlockExpansionCache.setChat(id)
        currentTrajectoryId = ""
        _isStreaming.value = false
        _bridgeStatusMessage.value = null
        _messages.value = emptyList()
        _isLoadingConversation.value = true
        clearOptimisticSend()
        _streamRun.value = null
        _queuedMessages.value = emptyList()

        backendScope.launch {
            _conversationError.value = null

            val conv = _conversations.value.find { it.id == id }
                ?: Conversation(id = id, title = "Antigravity Chat", sessionId = id)
            Log.d("CHAT_OPEN_DEBUG", "👉 [ChatViewModel.selectConversation] Launching stream for conv: title='${conv.title}', id=${conv.id}")
            _currentConversation.value = conv
            // this app's pending queued sends for the chat (the daemon's queue arrives with the first frame)
            refreshQueuedMessages(id)
            chatModels[id]?.let { _selectedModelId.value = it }
            knownDaemonCascadeIds.add(id)

            // Connect persistent stream for real-time live streaming.
            // StreamAgentStateUpdates delivers the complete history in Chunk 0!
            startPersistentStream(id)
        }
    }

    /**
     * The project chooser picked a cloud project ([projectId]) or a local one (null). New chats start there, and the
     * chat on screen moves there too while nothing was sent in it yet.
     */
    fun setNewChatCloudProject(projectId: String?) {
        AgyCloud.select(projectId)
        val conv = _currentConversation.value ?: return
        if (conv.agent != AgentKind.AGY || _messages.value.isNotEmpty() || conv.cloudProjectId == projectId) return
        if (knownDaemonCascadeIds.contains(conv.id)) {
            // nothing sent yet, but the chat already exists on the hub (e.g. a voice call started it) and a hub chat
            // can't move between the cloud and the hub's machine: a fresh chat starts in the picked place instead
            startNewChat()
            return
        }
        _currentConversation.value = conv.copy(cloudProjectId = projectId, workspaceUri = if (projectId != null) "" else conv.workspaceUri)
    }

    /** startCascade failed only because the hub already has a chat with that id. */
    private fun isAlreadyOnHub(err: String?): Boolean = err?.contains("already exists", ignoreCase = true) == true

    /** The `Model` number of the model picked for the chat on screen (0 = not known), for voice calls. */
    fun planModelNumber(): Int = runCatching {
        exa.language_server_pb.Model.valueOf(AgyHubClient.resolveModelEnum(_selectedModelId.value)).value
    }.getOrDefault(0)

    /**
     * Voice call: makes sure the AGY chat on screen exists on the hub (a new chat is started there first) and is
     * streaming, so what the voice agent does in it shows up live. Returns the chat id and [planModelNumber].
     */
    suspend fun prepareVoiceCall(): Pair<String, Int> {
        val onScreen = _currentConversation.value?.takeIf { it.agent == AgentKind.AGY }
        val conv: Conversation = onScreen ?: run {
            withContext(Dispatchers.Main) { startNewChat() }
            _currentConversation.value ?: error("no chat")
        }
        if (conv.cloudProjectId != null) throw ai.antigem.data.voice.AgyVoiceCall.CallRefused(ai.antigem.data.voice.AgyVoiceCall.CLOUD_CHAT_NO_VOICE)
        val id = conv.id
        val onDaemon = knownDaemonCascadeIds.contains(id) || _conversations.value.any { it.id == id } || _messages.value.isNotEmpty()
        if (!onDaemon) {
            val modelEnum = AgyHubClient.resolveModelEnum(_selectedModelId.value)
            val ws = conv.workspaceUri.takeIf { it.isNotBlank() }?.let { if (it.startsWith("file://")) it else "file://$it" } ?: ""
            agyHubClient.startCascade(cascadeId = id, modelEnum = modelEnum, workspaceUri = ws, cloudProjectId = conv.cloudProjectId)
                .exceptionOrNull()?.let { if (!isAlreadyOnHub(it.message)) throw it }
            knownDaemonCascadeIds.add(id)
            // no longer an unsaved "New Chat": the live stream may start (the hub names the chat later)
            val named = conv.copy(title = if (conv.title == "New Chat") L.agent.agyChatBackend.voiceChat else conv.title, workspaceUri = ws)
            withContext(Dispatchers.Main) {
                if (_currentConversation.value?.id == id) _currentConversation.value = named
            }
        }
        withContext(Dispatchers.Main) { startPersistentStream(id) }
        return id to planModelNumber()
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
            val lastFrameAtMs = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime())
            var lastRehydrateAtMs = 0L

            if (trajectoryEngine.conversationId != conversationId || _messages.value.isEmpty()) {
                trajectoryEngine.reset(conversationId)
            }

            Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel.startPersistentStream] Starting loop for $conversationId on $hubUrl")

            while (activeStreamConversationId == conversationId) {
                var rehydrateRequested = false
                var streamFailure: Exception? = null
                try {
                    kotlinx.coroutines.coroutineScope {
                        val collectJob = launch {
                            try {
                                agyHubClient.streamAgentStateUpdates(conversationId, hubUrl).collect { resp ->
                                    if (activeStreamConversationId != conversationId) {
                                        Log.d("CHAT_OPEN_DEBUG", "🌊 [ChatViewModel Stream] Dropping frame for inactive conversation (active=$activeStreamConversationId vs frame=$conversationId)")
                                        return@collect
                                    }

                                    lastFrameAtMs.set(android.os.SystemClock.elapsedRealtime())

                                    val update = resp.update
                                    val historyChunk = isFirstChunk
                                    isFirstChunk = false
                                    if (update != null) trajectoryEngine.ingestAgentStateUpdate(update)
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
                                        _streamRun.value = StreamRunState(conversationId, isRunning, trajectoryEngine.fullyIdle)
                                        _optimisticSend.value?.let { o ->
                                            // the sent message started (loop running) or already finished (its step is there)
                                            if (o.conversationId == conversationId && (isRunning || trajectoryEngine.hasSeenClientTag(o.tag))) {
                                                clearOptimisticSend()
                                            }
                                        }
                                        refreshQueuedMessages(conversationId)

                                        _messages.value = msgs
                                        if (historyChunk) adoptChatModelFromHistory(conversationId, msgs)
                                    }
                                    if (msgs.isNotEmpty() && activeStreamConversationId == conversationId) {
                                        // off the main thread: parse the newest replies before the list draws them
                                        backendScope.launch(Dispatchers.Default) { ai.antigem.ui.chat.ChatFeedCache.prewarm(msgs) }
                                        prefetchChatImages(conversationId, msgs, hubUrl)
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                streamFailure = e
                            }
                        }

                        val watchdogJob = launch {
                            var mismatchSinceMs = 0L
                            while (isActive && collectJob.isActive && activeStreamConversationId == conversationId) {
                                delay(1_000L)
                                val now = android.os.SystemClock.elapsedRealtime()
                                val streamRunning = _streamRun.value?.let { it.conversationId == conversationId && it.loopRunning } == true
                                val optimisticActive = _optimisticSend.value?.conversationId == conversationId
                                val summaryConv = _conversations.value.find { it.id == conversationId }
                                val summaryIdle = summaryConv != null && !summaryConv.isRunning
                                val silentMs = now - lastFrameAtMs.get()
                                if (streamRunning && !optimisticActive && summaryIdle && silentMs >= 5_000L) {
                                    if (mismatchSinceMs == 0L) mismatchSinceMs = now
                                    if (now - mismatchSinceMs >= 4_000L && now - lastRehydrateAtMs >= 10_000L) {
                                        Log.w("CHAT_OPEN_DEBUG", "🔄 [ChatViewModel Stream Watchdog] Stream RUNNING while summary IDLE and silent for ${silentMs}ms (convId=$conversationId); rehydrating")
                                        lastRehydrateAtMs = now
                                        rehydrateRequested = true
                                        collectJob.cancel()
                                        break
                                    }
                                } else {
                                    mismatchSinceMs = 0L
                                }
                            }
                        }

                        collectJob.join()
                        watchdogJob.cancel()
                    }

                    if (rehydrateRequested && activeStreamConversationId == conversationId) {
                        isFirstChunk = true
                        continue
                    }
                    streamFailure?.let { throw it }

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
                                    _conversationError.value = L.agent.agyChatBackend.noMessagesReceivedFromAntigravityTap
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
                        L.agent.agyChatBackend.cannotConnectToAntigravityMakeSure
                    } else {
                        L.agent.agyChatBackend.errorLoadingConversationRawerr(rawErr)
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
            // no live stream for this chat any more: its sidebar summary decides again
            _streamRun.compareAndSet(_streamRun.value?.takeIf { it.conversationId == conversationId }, null)
        }
    }

    private fun clearOptimisticSend() {
        optimisticTimeoutJob?.cancel()
        optimisticTimeoutJob = null
        _optimisticSend.value = null
    }

    private fun startOptimisticSend(conversationId: String, tag: String) {
        optimisticTimeoutJob?.cancel()
        _optimisticSend.value = OptimisticSend(conversationId, tag)
        // safety net only: the stream normally confirms within a moment
        optimisticTimeoutJob = backendScope.launch {
            delay(30_000)
            if (_optimisticSend.value?.tag == tag) _optimisticSend.value = null
        }
    }

    /** The open chat's queue: the daemon's queued messages plus sends from this app it does not list yet. */
    private fun refreshQueuedMessages(conversationId: String) {
        if (_currentConversation.value?.id != conversationId) return
        val server = trajectoryEngine.queuedMessages()
        val serverIds = server.map { it.id }.toSet()
        synchronized(hiddenQueuedIds) { hiddenQueuedIds.retainAll(serverIds) }
        val serverTags = server.flatMap { it.tags }.toSet()
        val local = localQueued[conversationId]
        val pending = if (local == null) emptyList() else synchronized(local) {
            // listed by the daemon now (or already delivered): no longer pending here
            local.keys.removeAll { it in serverTags || trajectoryEngine.hasSeenClientTag(it) }
            local.values.toList()
        }
        _queuedMessages.value = server.filter { it.id !in hiddenQueuedIds }.map { e ->
            ai.antigem.domain.model.QueuedMessage(
                id = e.id,
                text = e.text,
                attachmentCount = e.media.size,
                // NEXT_INVOCATION ones are being delivered right now
                canSendNow = e.deliveryStrategy != exa.language_server_pb.MessageDeliveryStrategy.MESSAGE_DELIVERY_STRATEGY_NEXT_INVOCATION,
                canDelete = true
            )
        } + pending
    }

    private fun markLastAssistantMessageDisconnected() {
        val list = _messages.value.toMutableList()
        var updated = false
        for (i in list.indices.reversed()) {
            val msg = list[i]
            if (msg.role == MessageRole.ASSISTANT && msg.isStreaming) {
                val newContent = if (msg.content.isBlank() && msg.toolCalls.isEmpty()) {
                    L.agent.agyChatBackend.connectionToAntigravityDaemonWas
                } else {
                    msg.content
                }
                list[i] = msg.copy(content = newContent, isStreaming = false)
                updated = true
            }
        }
        if (updated) {
            _messages.value = list
            ai.antigem.ui.chat.ChatFeedCache.prewarm(list)
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

    /** Renames a chat: the daemon stores the title in its annotations and the summaries stream pushes it back. */
    fun renameConversation(id: String, title: String) {
        val clean = title.trim().ifBlank { return }
        val oldTitle = _conversations.value.find { it.id == id }?.title
        _conversations.value = _conversations.value.map { if (it.id == id) it.copy(title = clean) else it }
        _currentConversation.value?.let { if (it.id == id) _currentConversation.value = it.copy(title = clean) }
        backendScope.launch {
            agyHubClient.updateConversationTitle(id, clean, AuthPreferences.currentHubUrl).onFailure { e ->
                Log.w("AgyChatBackend", "rename failed: ${e.message}")
                if (oldTitle != null) {
                    _conversations.value = _conversations.value.map { if (it.id == id && it.title == clean) it.copy(title = oldTitle) else it }
                    _currentConversation.value?.let { if (it.id == id && it.title == clean) _currentConversation.value = it.copy(title = oldTitle) }
                }
                ai.antigem.ui.components.AppToastHelper.showToast(
                    L.agent.agyChatBackend.couldNotRenameMessage(e.message), ai.antigem.ui.components.ChatToastType.ERROR
                )
            }
        }
    }

    /** Archives / unarchives a chat: the daemon stores it in the chat's annotations and the summaries stream pushes it back. */
    fun setArchived(id: String, archived: Boolean) {
        val before = _conversations.value.find { it.id == id }?.archived ?: return
        if (before == archived) return
        _conversations.value = _conversations.value.map { if (it.id == id) it.copy(archived = archived) else it }
        _currentConversation.value?.let { if (it.id == id) _currentConversation.value = it.copy(archived = archived) }
        backendScope.launch {
            agyHubClient.setConversationArchived(id, archived, AuthPreferences.currentHubUrl).onFailure { e ->
                Log.w("AgyChatBackend", "archive failed: ${e.message}")
                _conversations.value = _conversations.value.map { if (it.id == id) it.copy(archived = before) else it }
                _currentConversation.value?.let { if (it.id == id) _currentConversation.value = it.copy(archived = before) }
                ai.antigem.ui.components.AppToastHelper.showToast(
                    if (archived) L.agent.agyChatBackend.couldNotArchiveError(e.message) else L.agent.agyChatBackend.couldNotUnarchiveError(e.message), ai.antigem.ui.components.ChatToastType.ERROR
                )
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
                    _conversationError.value = L.agent.agyChatBackend.forkFailedValue(res.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Fork failed: ${e.message}")
                _conversationError.value = L.agent.agyChatBackend.forkErrorMessage(e.message)
            } finally {
                _isLoadingConversation.value = false
            }
        }
    }

    /** The model each chat was last given (the picker shows it when the chat is opened). */
    /**
     * Fetches the newest images of the open chat ([PREFETCH_IMAGES] at most: generated images and attached pictures)
     * into the app's image cache ahead of time, so they show at once when the list reaches them. Each image is fetched
     * once per chat visit, in the background (never holding up the stream); one that failed is tried again after a
     * minute at the earliest. Older images are fetched when the list shows them (the cards resolve their own image).
     */
    private fun prefetchChatImages(conversationId: String, msgs: List<ChatMessage>, hubUrl: String) {
        val sources = ArrayList<String>()
        for (i in msgs.indices.reversed()) {
            val m = msgs[i]
            for (tc in m.toolCalls) {
                val out = tc.output
                if (tc.toolType == ai.antigem.domain.model.ToolType.GENERATE_IMAGE && out.isNotBlank() &&
                    !out.startsWith("data:") && !out.startsWith("http")) sources.add(out)
            }
            for (att in m.attachments) {
                if (!att.isImage || att.path.isBlank() || !att.localUri.isNullOrBlank()) continue
                sources.add(if (att.path.startsWith("file://") || att.path.startsWith("http")) att.path else "file://${att.path}")
            }
            if (sources.size >= PREFETCH_IMAGES) break
        }
        if (sources.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val todo = synchronized(prefetchLock) {
            if (prefetchConversationId != conversationId) {
                prefetchConversationId = conversationId
                prefetchedImages.clear()
                prefetchFailedAt.clear()
            }
            sources.take(PREFETCH_IMAGES).distinct().filter { src ->
                val failedAt = prefetchFailedAt[src]
                (failedAt == null || now - failedAt > PREFETCH_RETRY_MS) && prefetchedImages.add(src)
            }
        }
        if (todo.isEmpty()) return
        backendScope.launch(Dispatchers.IO) {
            for (src in todo) {
                if (activeStreamConversationId != conversationId) return@launch
                ai.antigem.data.remote.HubMediaResolver.resolveMediaUri(application, src, agyHubClient, hubUrl)
                if (!ai.antigem.data.remote.HubMediaResolver.isLocalOrCached(application, src)) {
                    synchronized(prefetchLock) {
                        if (prefetchConversationId == conversationId) {
                            prefetchedImages.remove(src)
                            prefetchFailedAt[src] = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
        }
    }

    private val prefetchLock = Any()
    private var prefetchConversationId: String? = null
    private val prefetchedImages = HashSet<String>()
    private val prefetchFailedAt = HashMap<String, Long>()

    private val chatModels = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberChatModel(chatId: String, modelId: String) {
        if (chatId.isBlank() || modelId.isBlank()) return
        chatModels[chatId] = modelId
        if (_currentConversation.value?.id == chatId) _selectedModelId.value = modelId
    }

    /**
     * On opening a chat whose model was not picked in this session, the picker shows the model of its last reply
     * (as Claude / OpenCode chats do). Only for this chat: the saved default model is left as it is.
     */
    private fun adoptChatModelFromHistory(chatId: String, msgs: List<ChatMessage>) {
        if (chatModels.containsKey(chatId)) return
        val lastModel = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && !it.modelId.isNullOrBlank() }?.modelId ?: return
        val model = _availableModels.value.firstOrNull { it.id == lastModel || it.key == lastModel } ?: return
        rememberChatModel(chatId, model.id)
    }

    /** The model a message to [chatId] should use: the one set for that chat, else the picker's. */
    fun modelOfChat(chatId: String): String = chatModels[chatId] ?: _selectedModelId.value

    fun selectModel(modelId: String) {
        _currentConversation.value?.takeIf { it.agent == AgentKind.AGY }?.let { chatModels[it.id] = modelId }
        _selectedModelId.value = modelId
        pickedModelId = modelId
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
        if (content.isBlank() && _attachments.value.isEmpty()) return

        val state = systemConnectionState.value
        if (!state.canSend) {
            val reason = when (state.status) {
                ai.antigem.data.remote.SystemStatus.OFFLINE -> L.agent.agyChatBackend.serverIsOfflinePleaseStartThe
                ai.antigem.data.remote.SystemStatus.STARTING -> L.agent.agyChatBackend.serverIsStartingUpPleaseWait
                ai.antigem.data.remote.SystemStatus.ACQUIRING_CSRF -> L.agent.agyChatBackend.acquiringSecurityTokenPleaseWait
                ai.antigem.data.remote.SystemStatus.CHECKING_AUTH -> L.agent.agyChatBackend.verifyingAuthenticationPleaseWait
                ai.antigem.data.remote.SystemStatus.UNAUTHENTICATED -> L.agent.agyChatBackend.pleaseSignInToSendMessages
                ai.antigem.data.remote.SystemStatus.ERROR -> L.agent.agyChatBackend.serverErrorPleaseCheckServerLogs
                else -> L.agent.agyChatBackend.cannotSendMessageRightNow
            }
            android.widget.Toast.makeText(application, reason, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val currentAtts = _attachments.value
        val finalPrompt = content.trim().ifBlank {
            if (currentAtts.any { it.isAudio }) "Voice note" else if (currentAtts.any { it.isImage }) "Image" else "Attachment"
        }
        _attachments.value = emptyList()
        // an edited message's attachments, maybe still being copied (see revertAndEditLastUserMessage)
        val keptEdit = keptEditAttachments
        keptEditAttachments = null

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
            val firstLine = ai.antigem.data.remote.PromptExpansion.collapse(finalPrompt).trim().lines().firstOrNull { it.isNotBlank() } ?: L.agent.agyChatBackend.chat
            val cleanTitle = firstLine.take(40) + if (firstLine.length > 40) "..." else ""
            updatedConv = updatedConv.copy(title = cleanTitle)
        }

        updatedConv = updatedConv.copy(updatedAt = System.currentTimeMillis())
        _currentConversation.value = updatedConv

        // Like the hub web UI: a fully idle chat runs the message now; otherwise it is queued and the daemon sends it
        // when the agent is done (it was always WHEN_IDLE before, so a message sent while the chat was "idle" with
        // background work vanished until then)
        val queued = isCurrentChatActivelyRunning.value || isCurrentChatBackgroundActive.value
        val clientMessageId = UUID.randomUUID().toString()
        val clientTag = ai.antigem.data.remote.services.AgyChatService.clientMsgTag(clientMessageId)
        val updatedList: List<ChatMessage>
        if (queued) {
            val entries = localQueued.getOrPut(conv.id) { LinkedHashMap() }
            synchronized(entries) {
                entries[clientTag] = ai.antigem.domain.model.QueuedMessage(
                    id = clientTag,
                    text = finalPrompt,
                    attachmentCount = currentAtts.size,
                    canSendNow = false,
                    canDelete = false,
                    isPending = true
                )
            }
            refreshQueuedMessages(conv.id)
            updatedList = _messages.value
        } else {
            startOptimisticSend(conv.id, clientTag)
            trajectoryEngine.submitUserPrompt(finalPrompt, currentAtts, conv.id)
            updatedList = trajectoryEngine.toChatMessages(conv.id)
            _messages.value = updatedList
            ai.antigem.ui.chat.ChatFeedCache.prewarm(updatedList)
        }

        backendScope.launch {
            // under the chat lists' lock: the hub's list can add this new chat between the check and the write
            synchronized(summariesLock) {
                val current = _conversations.value
                val exists = current.any { it.id == updatedConv.id }
                // (a cloud chat keeps this send time: an older frame from the hub doesn't move it back down)
                val row = steadyCloudTime(updatedConv)
                val newConversations = if (exists) {
                    current.map { if (it.id == row.id) row else it }
                } else {
                    listOf(row) + current
                }
                _conversations.value = newConversations.sortedByDescending { it.updatedAt }
            }

            // Prepare media payload for all attachments (images, audio, files)
            val mediaList = mutableListOf<ai.antigem.data.remote.AgyHubClient.AgyMediaItem>()
            val hubUrl = AuthPreferences.currentHubUrl
            val kept = keptEdit?.await().orEmpty()
            // files a cloud chat could not be sent (named to the agent in a hidden note)
            val cloudFailed = mutableListOf<String>()

            for (att in currentAtts.mapNotNull { if (kept.containsKey(it.id)) kept[it.id] else it }) {
                // A file by path is already listed in the message's text. Its own path is never sent as Media.uri:
                // the hub MOVES a file given that way, so only an image's shrunk copy goes (saved as a hub artifact)
                if (att.reference && att.base64.isNullOrBlank()) {
                    // a cloud chat runs in Google's cloud, where that path means nothing (its text has no file list):
                    // the file itself goes with the message
                    if (conv.isCloud && att.path.isNotBlank()) {
                        val item = cloudFileMedia(att)
                        if (item != null) mediaList.add(item) else cloudFailed.add(att.name.ifBlank { att.path.substringAfterLast('/') })
                    }
                    continue
                }
                var hostPath = if (!att.reference && att.path.isNotBlank() && !att.path.startsWith("/data/") && !att.path.startsWith("content://")) att.path else ""
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
                        ai.antigem.data.remote.AgyHubClient.AgyMediaItem(
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

            executeStream(
                updatedConv, updatedList,
                mediaItems = mediaList,
                promptText = if (cloudFailed.isEmpty()) finalPrompt else finalPrompt + "\n\n" + cloudFilesMissingNote(cloudFailed),
                queued = queued,
                clientMessageId = clientMessageId
            )
        }
    }

    /**
     * A file attached by path ([ChatAttachment.reference]) for a cloud chat. The file is on the hub's machine (a copy
     * in the uploads folder, or the user's own file picked in Files): the hub copies it into its temp media
     * (SaveMediaAsArtifact with the path, the original is not touched), and a message carrying that temp media's uri
     * goes to the cloud with the file's bytes (the hub's cloud proxy inlines it). Null, with a toast, when the hub
     * refused it (e.g. over [AgyCloud.MAX_FILE_BYTES]).
     */
    private suspend fun cloudFileMedia(att: ChatAttachment): ai.antigem.data.remote.AgyHubClient.AgyMediaItem? {
        val name = att.name.ifBlank { att.path.substringAfterLast('/') }
        val mime = att.mimeType?.takeIf { it.isNotBlank() } ?: ai.antigem.data.files.AttachedFiles.mimeOf(name)
        val res = agyHubClient.saveFileAsArtifact(path = att.path, mimeType = mime, description = name)
        val uri = res.getOrNull()?.takeIf { it.isNotBlank() }
        if (uri == null) {
            val err = res.exceptionOrNull()?.message ?: L.agent.agyChatBackend.unknownError
            Log.w("AgyChatBackend", "Cloud chat: could not send ${att.path}: $err")
            ai.antigem.ui.components.AppToastHelper.showToast(
                L.agent.agyChatBackend.couldntSendFilenameToTheCloud(name, err), ai.antigem.ui.components.ChatToastType.ERROR
            )
            return null
        }
        return ai.antigem.data.remote.AgyHubClient.AgyMediaItem(
            mimeType = mime,
            base64 = "",
            description = name,
            uri = uri,
            displayName = name
        )
    }

    /** Tells a cloud agent which attached files did not reach it (the user got a toast for each). */
    private fun cloudFilesMissingNote(names: List<String>): String = ai.antigem.data.remote.PromptExpansion.hidden(buildString {
        append("(Hidden note added by the AntiGem app: it is not part of the user's message. Never quote it.)\n")
        append("The user attached these files, but they could not be uploaded to you, so you do not have them: ")
        append(names.joinToString(", "))
        append(". If the task needs them, say so and ask the user to attach them again (up to 20 MB a file).")
    })

    /**
     * "Send now" on a queued message: it is sent again to run right away (interrupting the current work), then
     * removed from the queue (the hub web UI does the same).
     */
    fun sendQueuedNow(id: String) {
        val conv = _currentConversation.value ?: return
        val entry = trajectoryEngine.queuedMessages().find { it.id == id } ?: return
        val clientMessageId = UUID.randomUUID().toString()
        startOptimisticSend(conv.id, ai.antigem.data.remote.services.AgyChatService.clientMsgTag(clientMessageId))
        hiddenQueuedIds.add(id)
        refreshQueuedMessages(conv.id)
        backendScope.launch(Dispatchers.IO) {
            val (modelEnum, thinkingBudget, policy) = promptSettings()
            val res = agyHubClient.sendUserPrompt(
                cascadeId = conv.id,
                text = entry.text,
                modelEnum = modelEnum,
                thinkingBudget = thinkingBudget,
                autoExecutionPolicy = policy,
                deliveryStrategy = exa.language_server_pb.MessageDeliveryStrategy.MESSAGE_DELIVERY_STRATEGY_NEXT_INVOCATION,
                clientMessageId = clientMessageId,
                rawMedia = entry.media,
                cloud = conv.isCloud
            )
            if (res.isSuccess) {
                agyHubClient.deleteAgentMessage(conv.id, id).onFailure { Log.w("ChatViewModel", "deleteAgentMessage: ${it.message}") }
            } else {
                withContext(Dispatchers.Main) {
                    clearOptimisticSend()
                    hiddenQueuedIds.remove(id)
                    refreshQueuedMessages(conv.id)
                    android.widget.Toast.makeText(application, L.agent.agyChatBackend.couldntSendTheQueuedMessageValue(res.exceptionOrNull()?.message), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** "Delete" on a queued message: removes it from the daemon's queue. */
    fun deleteQueued(id: String) {
        val conv = _currentConversation.value ?: return
        hiddenQueuedIds.add(id)
        refreshQueuedMessages(conv.id)
        backendScope.launch(Dispatchers.IO) {
            agyHubClient.deleteAgentMessage(conv.id, id).onFailure { e ->
                withContext(Dispatchers.Main) {
                    hiddenQueuedIds.remove(id)
                    refreshQueuedMessages(conv.id)
                    android.widget.Toast.makeText(application, L.agent.agyChatBackend.couldntDeleteTheQueuedMessageMessage(e.message), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Model, thinking budget and command auto-execution policy a prompt is sent with. */
    private suspend fun promptSettings(): Triple<String, Int, String> {
        val modelEnum = ai.antigem.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
        val selectedModel = _enabledModels.value.find { it.id == modelEnum }
        val supportsThinking = selectedModel?.supportsThinking ?: (modelEnum.contains("thinking", ignoreCase = true) || modelEnum.contains("flash", ignoreCase = true) || modelEnum.contains("pro", ignoreCase = true))
        val policy = authPrefs.commandAutoExecutionPolicy.firstOrNull() ?: "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"
        return Triple(modelEnum, if (supportsThinking) 8192 else 0, policy)
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
                        onError(L.agent.agyChatBackend.noSpeechRecognizedInRecording)
                    }
                } else {
                    val msg = res.exceptionOrNull()?.message ?: L.agent.agyChatBackend.transcriptionFailed
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
     * Attachments of the message being edited, copied into the app's own storage (id → copy, null when the file could
     * not be found; see [keepRestoredAttachments]). A send right after the edit waits for them.
     */
    @Volatile private var keptEditAttachments: kotlinx.coroutines.Deferred<Map<String, ChatAttachment?>>? = null

    /**
     * Copies the attachments put back in the input box by an edit (images, voice notes, files) into the app's media
     * store, as a fresh pick would be: a sent attachment only points at the file the hub saved for the chat, and the
     * hub deletes that file when the edit takes back the chat's first message. Returns id → the copy (its old path
     * dropped, so it is sent as data again), or null when the file could not be found. A file attached by reference
     * (a file on the agent's machine, listed in the message's text) is left out: it keeps going by its path.
     */
    private suspend fun keepRestoredAttachments(atts: List<ChatAttachment>, hubUrl: String): Map<String, ChatAttachment?> {
        val kept = HashMap<String, ChatAttachment?>()
        for (att in atts) {
            if (att.reference || att.path.isBlank() || att.path.startsWith("content://") || att.path.startsWith("http")) continue
            try {
                // its data is already in the app (the step's inline data): only the old path has to go
                val ownData = !att.base64.isNullOrBlank() ||
                    att.localUri?.takeIf { it.startsWith("file://") }?.let { java.io.File(Uri.parse(it).path ?: "").isFile } == true
                if (ownData) {
                    kept[att.id] = att.copy(path = "")
                    continue
                }
                val source = java.io.File(att.path).takeIf { it.isFile && it.canRead() }
                    ?: ai.antigem.data.remote.HubMediaResolver.cachedFile(att.path)
                    ?: ai.antigem.data.remote.HubMediaResolver.resolveMediaUri(application, att.path, agyHubClient, hubUrl)
                        .takeIf { it.startsWith("file://") }?.let { java.io.File(it.removePrefix("file://")) }?.takeIf { it.isFile }
                val copy = source?.let { ai.antigem.data.media.InlineMediaStore.fileForBytes(it.readBytes(), att.mimeType) }
                if (copy == null) {
                    Log.w("ChatViewModel", "Edit: attachment not found: ${att.name} (${att.path})")
                    kept[att.id] = null
                    continue
                }
                kept[att.id] = att.copy(path = "", localUri = "file://${copy.absolutePath}", size = if (att.size > 0) att.size else copy.length())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Edit: could not read attachment ${att.name}: ${e.message}")
                kept[att.id] = null
            }
        }
        return kept
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
        // copied before the hub is asked to revert (taking back the first message deletes the files they point at)
        val hubUrlForCopies = AuthPreferences.currentHubUrl
        val keep = if (targetMsg.attachments.isEmpty()) null
            else backendScope.async(Dispatchers.IO) { keepRestoredAttachments(targetMsg.attachments, hubUrlForCopies) }
        keptEditAttachments = keep

        onRestored(cleanText)

        val isFirstUserMsg = index == 0 || current.none { it.role == MessageRole.USER && it.id != targetMsg.id }
        if (isFirstUserMsg) {
            // Optimistically clean up sidebar & switch to fresh new chat right away
            _conversations.value = _conversations.value.filter { it.id != conv.id }
            knownDaemonCascadeIds.remove(conv.id)
            activeStreamConversationId = null
            val modelToUse = if (_selectedModelId.value.isNotBlank()) {
                ai.antigem.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
            } else {
                (_enabledModels.value.firstOrNull()?.id ?: "")
            }
            val defaultProjPath = ai.antigem.data.daemon.IdeDaemonManager.activeProject.value?.path ?: ""
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
            // the deleted chat's turns (and its attachments' paths, gone with it) must not carry over into the new one
            trajectoryEngine.reset(newConv.id, force = true)
        }

        backendScope.launch(Dispatchers.IO) {
            val hubUrl = AuthPreferences.currentHubUrl
            keep?.await()?.takeIf { it.isNotEmpty() }?.let { kept ->
                val missing = kept.values.count { it == null }
                withContext(Dispatchers.Main) {
                    // an attachment that could not be found leaves the input box (there is nothing to send)
                    _attachments.value = _attachments.value.mapNotNull { if (kept.containsKey(it.id)) kept[it.id] else it }
                    if (missing > 0) {
                        android.widget.Toast.makeText(application, L.agent.agyChatBackend.editedMessageAttachmentsMissing(missing), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            val modelEnum = ai.antigem.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
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
        existingToolCalls: List<ai.antigem.domain.model.ToolCall> = emptyList(),
        priorTextPrefix: String = "",
        mediaItems: List<ai.antigem.data.remote.AgyHubClient.AgyMediaItem> = emptyList(),
        // the text to send; null = the last user message of [currentHistory]
        promptText: String? = null,
        // queued for after the current work: no reply placeholder, it shows in the queued-messages card
        queued: Boolean = false,
        clientMessageId: String? = null
    ) {
        _bridgeStatusMessage.value = null

        val assistantMsgId = if (queued) (currentAssistantMsgId ?: "") else existingAssistantMsgId ?: UUID.randomUUID().toString()
        if (!queued) currentAssistantMsgId = assistantMsgId
        val clientTag = clientMessageId?.let { ai.antigem.data.remote.services.AgyChatService.clientMsgTag(it) }

        // a queued message that could not be sent: out of the queue card, and say so
        suspend fun failQueued(err: String) = withContext(Dispatchers.Main) {
            localQueued[conv.id]?.let { synchronized(it) { it.remove(clientTag) } }
            refreshQueuedMessages(conv.id)
            android.widget.Toast.makeText(application, L.agent.agyChatBackend.couldntQueueTheMessageErr(err), android.widget.Toast.LENGTH_SHORT).show()
        }

        if (queued) {
            // nothing to show in the chat yet
        } else if (existingAssistantMsgId == null) {
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
        val userPrompt = promptText ?: currentHistory.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val deliveryStrategy = if (queued) exa.language_server_pb.MessageDeliveryStrategy.MESSAGE_DELIVERY_STRATEGY_WHEN_IDLE
            else exa.language_server_pb.MessageDeliveryStrategy.MESSAGE_DELIVERY_STRATEGY_NEXT_INVOCATION
        val modelEnum = ai.antigem.data.remote.AgyHubClient.resolveModelEnum(_selectedModelId.value)
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
                        hubUrl = hubUrl,
                        cloudProjectId = conv.cloudProjectId
                    )
                    // already on the hub (e.g. a voice call started it): nothing to start, carry on and send
                    if (startRes.isFailure && !isAlreadyOnHub(startRes.exceptionOrNull()?.message)) {
                        val rawErr = startRes.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToStartConversationOnDaemon
                        val err = if (conv.isCloud) AgyCloud.friendlyError(rawErr) else rawErr
                        Log.e("ChatViewModel", "startCascade error: $rawErr")
                        val formattedErr = formatStructuredErrorMessage(
                            title = if (conv.isCloud) L.agent.agyChatBackend.theCloudAgentCouldntStart
                                else if (err.contains("auth", ignoreCase = true)) L.agent.agyChatBackend.authenticationRequired else L.agent.agyChatBackend.failedToStartConversation,
                            userMessage = err,
                            shortError = err
                        )
                        if (queued) {
                            failQueued(err)
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            clearOptimisticSend()
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

                // Reset turn boundary and state for this active turn (a queued message does not start one now)
                if (!queued) {
                    currentTurnStartStep = totalStepsCount
                    hasStartedRunning = false
                    hasSeenTurnActivity = false
                }

                val currentAutoExecPolicy = authPrefs.commandAutoExecutionPolicy.firstOrNull() ?: "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"

                // project checkpoint (when on): the files right before Antigravity gets the message (local projects
                // only: a cloud chat's files are not on the bridge's machine). A queued one never holds anything up.
                if (clientMessageId != null && !conv.isCloud) {
                    val ws = _currentConversation.value?.takeIf { it.id == conv.id }?.workspaceUri?.takeIf { it.isNotBlank() } ?: conv.workspaceUri
                    val title = promptText ?: userPrompt
                    if (queued) ai.antigem.data.checkpoint.ProjectCheckpoints.beforeTurnInBackground(AgentKind.AGY, conv.id, ws, clientMessageId, title)
                    else ai.antigem.data.checkpoint.ProjectCheckpoints.beforeTurn(AgentKind.AGY, conv.id, ws, clientMessageId, title)
                }

                var sendRes = agyHubClient.sendUserPrompt(
                    cascadeId = conv.id,
                    text = userPrompt,
                    modelEnum = modelEnum,
                    thinkingBudget = thinkingBudget,
                    autoExecutionPolicy = currentAutoExecPolicy,
                    media = mediaItems,
                    hubUrl = hubUrl,
                    deliveryStrategy = deliveryStrategy,
                    clientMessageId = clientMessageId,
                    cloud = conv.isCloud
                )

                if (sendRes.isFailure) {
                    val err = sendRes.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToSendMessage
                    Log.e("ChatViewModel", "sendUserPrompt error: $err")
                    // If trajectory was not found on daemon (e.g. daemon restarted or session expired),
                    // attempt to re-start cascade and retry sending the prompt once!
                    if (err.contains("trajectory not found", ignoreCase = true) || err.contains("not found", ignoreCase = true)) {
                        Log.i("ChatViewModel", "Trajectory not found on daemon. Attempting to restart cascade ${conv.id} and retry...")
                        val restartRes = agyHubClient.startCascade(
                            cascadeId = conv.id,
                            modelEnum = modelEnum,
                            workspaceUri = workspaceUri,
                            hubUrl = hubUrl,
                            cloudProjectId = conv.cloudProjectId
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
                                hubUrl = hubUrl,
                                deliveryStrategy = deliveryStrategy,
                                clientMessageId = clientMessageId,
                                cloud = conv.isCloud
                            )
                        }
                    }
                }

                if (sendRes.isSuccess) {
                    isPromptInFlight = false
                } else if (queued) {
                    failQueued(sendRes.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToSendMessage)
                } else {
                    val rawErr = sendRes.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToSendMessage
                    // a cloud chat: Google's limits (busy, daily VM time, chat limits) in plain words
                    val err = if (conv.isCloud) AgyCloud.friendlyError(rawErr) else rawErr
                    Log.e("ChatViewModel", "sendUserPrompt final error: $rawErr")
                    val formattedErr = formatStructuredErrorMessage(
                        title = when {
                            conv.isCloud -> L.agent.agyChatBackend.cloudAgent
                            err.contains("auth", ignoreCase = true) -> L.agent.agyChatBackend.authenticationRequired
                            err.contains("quota", ignoreCase = true) || err.contains("credit", ignoreCase = true) || err.contains("429") -> L.agent.agyChatBackend.quotaLimitExceeded
                            err.contains("model not found", ignoreCase = true) -> L.agent.agyChatBackend.modelNotFound
                            err.contains("connect", ignoreCase = true) || err.contains("network", ignoreCase = true) -> L.agent.agyChatBackend.serverConnectionError
                            else -> L.agent.agyChatBackend.messageDeliveryError
                        },
                        userMessage = err,
                        shortError = err
                    )
                    withContext(Dispatchers.Main) {
                        clearOptimisticSend()
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
                if (queued) {
                    failQueued(e.message ?: L.agent.agyChatBackend.unknownError)
                    return@launch
                }
                val formattedErr = formatStructuredErrorMessage(
                    title = L.agent.agyChatBackend.executionFailure,
                    userMessage = e.message ?: L.agent.agyChatBackend.unknownError,
                    shortError = e.message ?: "",
                    fullError = e.stackTraceToString()
                )
                withContext(Dispatchers.Main) {
                    clearOptimisticSend()
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
        toolCalls: List<ai.antigem.domain.model.ToolCall> = emptyList(),
        isStreaming: Boolean,
        tokenUsage: ai.antigem.domain.model.TokenUsage? = null,
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
            ai.antigem.ui.chat.ChatFeedCache.prewarm(_messages.value)
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
        clearOptimisticSend()

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

    fun applyQuotaSummary(summary: ai.antigem.domain.model.QuotaSummaryResponse) {
        _quotaSummary.value = summary
        recomputeQuotas()
    }

    /**
     * Each model's quota from its group (the user's choice in the model picker, else the automatic one). A group
     * without a 5-hour limit (Argon) has none here: the pill falls back to its weekly limit, never a made-up 5h.
     * A used-up weekly limit (shown as 0%) wins over the 5h choice: the model can't run until it resets, while the
     * 5h window may still read 100%.
     */
    private fun recomputeQuotas() {
        val summary = _quotaSummary.value ?: return
        val groups = ai.antigem.domain.model.AgyQuotaGroups.groups(summary)
        val overrides = ai.antigem.domain.model.ModelGroupPrefs.overrides(ai.antigem.domain.model.QuotaAgent.AGY).value
        val pill = ai.antigem.domain.model.ModelGroupPrefs.pillWindow(ai.antigem.domain.model.QuotaAgent.AGY).value
        _quotas.value = _availableModels.value.mapNotNull { model ->
            val group = ai.antigem.domain.model.AgyQuotaGroups.groupOf(model, groups, overrides) ?: return@mapNotNull null
            // under 0.05% left reads as "0.0%" in the pill
            val weeklyEmpty = group.tightestWeekly?.let { it.remaining * 100f < 0.05f } == true
            val window = if (weeklyEmpty) ai.antigem.domain.model.QuotaPillWindow.WEEKLY else pill
            group.toModelQuota(model.id, window)
        }
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
                        ai.antigem.data.remote.AgyHubClient.updateModelRegistry(models)
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
                                L.agent.agyChatBackend.failedToRefreshAntigravityHubUrl(AuthPreferences.currentHubUrl)
                            } else {
                                L.agent.agyChatBackend.failedToRefreshFailureerror(failureError)
                            }
                            android.widget.Toast.makeText(application, msg, android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            android.widget.Toast.makeText(application, L.agent.agyChatBackend.quotasModelsRefreshed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    fun approveAndExecuteTerminalTool(
        toolCall: ai.antigem.domain.model.ToolCall,
        messageId: String,
        scope: String? = null
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return
        val trajectoryId = toolCall.trajectoryId?.takeIf { it.isNotBlank() }
            ?: trajectoryEngine.trajectoryId

        trajectoryEngine.markStepResponded(stepIndex)
        trajectoryEngine.optimisticUpdateStepStatus(stepIndex, ai.antigem.data.remote.dto.CortexStepStatuses.RUNNING)
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
                val err = res.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToApproveTool
                Log.e("ChatViewModel", "handleCascadeUserInteraction (approve) error: $err")
                _conversationError.value = L.agent.agyChatBackend.approvalFailedErr(err)
                _isStreaming.value = false

                // Roll back optimistic state on failure so UI accurately reflects reality
                trajectoryEngine.unmarkStepResponded(stepIndex)
                trajectoryEngine.optimisticUpdateStepStatus(stepIndex, ai.antigem.data.remote.dto.CortexStepStatuses.WAITING)
                _messages.value = trajectoryEngine.toChatMessages(conv.id)
            }
        }
    }

    fun rejectTerminalTool(toolCall: ai.antigem.domain.model.ToolCall, messageId: String, reason: String? = null) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return
        val trajectoryId = toolCall.trajectoryId?.takeIf { it.isNotBlank() }
            ?: trajectoryEngine.trajectoryId

        val denyInstruction = reason ?: "User rejected this command."
        trajectoryEngine.markStepResponded(stepIndex)
        trajectoryEngine.optimisticUpdateStepStatus(stepIndex, ai.antigem.data.remote.dto.CortexStepStatuses.CANCELED, output = denyInstruction)
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
                val err = res.exceptionOrNull()?.message ?: L.agent.agyChatBackend.failedToRejectTool
                Log.e("ChatViewModel", "handleCascadeUserInteraction (deny) error: $err")
                _conversationError.value = L.agent.agyChatBackend.rejectionFailedErr(err)

                // Roll back optimistic state on failure so UI accurately reflects reality
                trajectoryEngine.unmarkStepResponded(stepIndex)
                trajectoryEngine.optimisticUpdateStepStatus(stepIndex, ai.antigem.data.remote.dto.CortexStepStatuses.WAITING)
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

    fun terminateRunningTerminalTool(toolCall: ai.antigem.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex ?: return

        // Optimistically mark this specific step terminated so UI responds instantaneously
        trajectoryEngine.optimisticUpdateStepStatus(
            stepIndex = stepIndex,
            status = ai.antigem.data.remote.dto.CortexStepStatuses.CANCELED,
            output = L.agent.agyChatBackend.commandTerminatedByUser
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

    fun proceedAfterTermination(toolCall: ai.antigem.domain.model.ToolCall, messageId: String, userInstructions: String?) {
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
        toolCall: ai.antigem.domain.model.ToolCall,
        messageId: String,
        responses: List<ai.antigem.data.remote.dto.AskQuestionResponseItemDto>,
        summaryDisplay: String
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = ai.antigem.data.remote.dto.CortexStepStatuses.DONE,
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
        toolCall: ai.antigem.domain.model.ToolCall,
        messageId: String,
        responses: List<ai.antigem.data.remote.dto.AskQuestionResponseItemDto> = emptyList()
    ) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex
        val skipSummary = L.agent.agyChatBackend.skippedByUser

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = ai.antigem.data.remote.dto.CortexStepStatuses.DONE,
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
                        ai.antigem.data.remote.dto.AskQuestionResponseItemDto(
                            question = q.prompt,
                            options = q.options.map { ai.antigem.data.remote.dto.AskQuestionOptionDto(id = it.id, text = it.label) },
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

    fun cancelUserChoices(toolCall: ai.antigem.domain.model.ToolCall, messageId: String) {
        val conv = _currentConversation.value ?: return
        val stepIndex = toolCall.stepIndex
        val hubUrl = AuthPreferences.currentHubUrl

        if (stepIndex != null) {
            trajectoryEngine.markStepResponded(stepIndex)
            trajectoryEngine.optimisticUpdateStepStatus(
                stepIndex = stepIndex,
                status = ai.antigem.data.remote.dto.CortexStepStatuses.CANCELED,
                output = L.agent.agyChatBackend.questionnaireCancelledByUser
            )
            _messages.value = trajectoryEngine.toChatMessages(conv.id)
        }

        val msg = _messages.value.find { it.id == messageId }
        val canceledToolCall = toolCall.copy(
            status = "CANCELED",
            output = L.agent.agyChatBackend.cancelledByUser
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
        clearOptimisticSend()
        _streamRun.value = null
        _queuedMessages.value = emptyList()
    }

    fun dispose() {
        persistentStreamJob?.cancel()
        streamingJob?.cancel()
        // the singleton must not keep this backend (and its ViewModel) alive
        ai.antigem.data.remote.AgyAccounts.replyRunning = { false }
    }
}

/** How many of the newest images of an Antigravity chat are fetched ahead of time (see prefetchChatImages). */
private const val PREFETCH_IMAGES = 8
/** An image that could not be fetched ahead of time is not tried again sooner than this. */
private const val PREFETCH_RETRY_MS = 60_000L
