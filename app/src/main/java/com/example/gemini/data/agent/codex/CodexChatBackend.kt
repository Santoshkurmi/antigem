package com.example.gemini.data.agent.codex

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.isBridgeUnreachable
import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.dbl
import com.example.gemini.data.agent.opencode.long
import com.example.gemini.data.agent.opencode.o
import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.QueuedMessage
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Whether Codex can be used, as the chat screen and the settings show it. */
enum class CodexState(val label: String) {
    CHECKING("Checking…"),
    READY("Ready"),
    SIGNED_OUT("Not signed in"),
    NOT_INSTALLED("Not installed"),
    DISABLED("Turned off on the server"),
    OUTDATED("Server update needed"),
    OFFLINE("Server offline"),
    ERROR("Error");

    /** Sending a message cannot work in this state. */
    val isBlocking get() = this == NOT_INSTALLED || this == DISABLED || this == OUTDATED || this == OFFLINE || this == SIGNED_OUT
}

/** The signed-in account (`account/read`). */
data class CodexAccount(val type: String, val email: String?, val plan: String?)

/** One usage-limit window (`account/rateLimits/read`): share used, its length and when it resets (epoch seconds). */
data class CodexLimitWindow(val usedPercent: Double, val windowMinutes: Long?, val resetsAt: Long?)

data class CodexRateLimits(val primary: CodexLimitWindow?, val secondary: CodexLimitWindow?, val plan: String?)

/** A sign-in in progress: open [url] (and type [code], for a device code sign-in). */
data class CodexLogin(val loginId: String, val url: String, val code: String?)

/** A request Codex is waiting on: an approval or a question (answered through the bridge by [key]). */
data class CodexPending(
    val key: String,
    val method: String,
    val params: JsonObject,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?
) {
    val isQuestion get() = method == "item/tool/requestUserInput"
}

/**
 * Codex chat backend. The bridge runs one `codex app-server` for every Codex chat (thread); this class lists threads
 * and reads their turns through it, follows its event stream (one WebSocket for all chats, replayed after a
 * reconnect), and starts turns, stops them, and answers approvals and questions. [CodexTranscript] turns the open
 * chat into the shared chat UI's messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: CodexPreferences,
    val client: CodexBridgeClient = CodexBridgeClient(),
    /** Model and effort picked in this backend become the defaults for new chats (false for Jarvis' own chats). */
    private val rememberChoices: Boolean = true,
    /** Codex's questions are answered empty at once (a chat nobody looks at: Jarvis asks through jarvis_ask instead). */
    private val skipQuestions: Boolean = false
) : AgentChatBackend {

    override val kind = AgentKind.CODEX

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    /** A turn of the chat on screen is running (also while it waits for an approval). */
    val isCurrentChatActivelyRunning: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _state = MutableStateFlow(CodexState.CHECKING)
    val state: StateFlow<CodexState> = _state.asStateFlow()
    private val _status = MutableStateFlow<CodexStatus?>(null)
    /** The bridge's last status answer (version, binary, error). */
    val status: StateFlow<CodexStatus?> = _status.asStateFlow()
    private val _account = MutableStateFlow<CodexAccount?>(null)
    val account: StateFlow<CodexAccount?> = _account.asStateFlow()
    private val _rateLimits = MutableStateFlow<CodexRateLimits?>(null)
    val rateLimits: StateFlow<CodexRateLimits?> = _rateLimits.asStateFlow()
    private val _login = MutableStateFlow<CodexLogin?>(null)
    /** The sign-in in progress, if any. */
    val login: StateFlow<CodexLogin?> = _login.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()
    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private val _selectedModelId = MutableStateFlow(prefs.model.value)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    private val _efforts = MutableStateFlow<List<String>>(emptyList())
    /** Reasoning efforts the selected model supports. */
    val efforts: StateFlow<List<String>> = _efforts.asStateFlow()
    private val _effort = MutableStateFlow(prefs.effort.value)
    /** Reasoning effort of the chat on screen. */
    val effort: StateFlow<String> = _effort.asStateFlow()
    val permissionMode: StateFlow<String> = prefs.permissionMode

    private val _pendingApprovals = MutableStateFlow<List<CodexPending>>(emptyList())
    /** Approvals the chat on screen waits on (questions are shown in the chat instead). */
    val pendingApprovals: StateFlow<List<CodexPending>> = _pendingApprovals.asStateFlow()
    private val _queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    /** Messages queued in Codex for the chat on screen (they run after the current turn). */
    val queuedMessages: StateFlow<List<QueuedMessage>> = _queuedMessages.asStateFlow()

    private val _planMode = MutableStateFlow(false)
    /** Plan mode: Codex explores and proposes a plan (it may ask questions) instead of changing anything. */
    val planMode: StateFlow<Boolean> = _planMode.asStateFlow()
    private val _lastPlan = MutableStateFlow<String?>(null)
    /** The plan of the chat's newest finished turn (plan mode), offered for implementing. */
    val lastPlan: StateFlow<String?> = _lastPlan.asStateFlow()

    private val _slashCommands = MutableStateFlow(BUILTIN_COMMANDS)
    /** Codex's commands and the skills it has, for the "/" menu of a Codex chat. */
    val slashCommands: StateFlow<List<com.example.gemini.data.remote.SlashCommandItem>> = _slashCommands.asStateFlow()
    // skill name → SKILL.md path (a "/name" message is sent as that skill)
    @Volatile private var skillPaths: Map<String, String> = emptyMap()

    private val _notice = MutableStateFlow<String?>(null)
    /** Retry / reroute notice of the chat on screen. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _cliJob = MutableStateFlow<CodexCliJob?>(null)
    /** The last install / update of Codex started from the app. */
    val cliJob: StateFlow<CodexCliJob?> = _cliJob.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    /** Called after each finished turn of the chat on screen. */
    var onTurnFinished: () -> Unit = {}

    // ---- below: only touched on [worker]
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private var transcript = CodexTranscript()
    /**
     * Live copies of chats that are not on screen but in the middle of a reply (left while it ran, or a reply that
     * started after leaving): Codex saves the answer only when it is finished, so coming back merges this in.
     */
    private val shadows = HashMap<String, CodexTranscript>()
    /** Chats this backend sent a message to (their replies get a live copy when they run while not on screen). */
    private val sentTo = HashSet<String>()
    /** The chat's history is loading: nothing half loaded goes on screen meanwhile. */
    private var loadingHistory = false
    private var attachedId: String? = null
    /**
     * The chat the screen asked for last, set at once on the caller's thread. The worker catches up later: a slower load
     * of a chat opened before must not show its result (or end the loading state) for the chat opened after it.
     */
    @Volatile private var wantedId: String? = null
    /** Threads that exist in Codex (a new chat exists only in the app until its first message). */
    private val created = HashSet<String>()
    private val running = HashSet<String>()
    private val localConversations = LinkedHashMap<String, Conversation>()
    private val pendingAll = LinkedHashMap<String, CodexPending>()
    private var lastServerList: List<Conversation> = emptyList()
    /** Last bridge event sequence number seen (-1: none yet). */
    private var lastSeq = -1L
    private var turnStartedAt = 0L
    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var emitJob: Job? = null
    private var refreshJob: Job? = null
    // reasoning efforts per model id, and each model's default
    @Volatile private var modelEfforts: Map<String, List<String>> = emptyMap()
    @Volatile private var modelDefaultEffort: Map<String, String> = emptyMap()

    // ------------------------------------------------------------------ lifecycle

    /** Codex is enabled: check the server, load account, models and chats, follow the event stream. */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = backendScope.launch {
            refreshStatus()
            if (_state.value == CodexState.READY || _state.value == CodexState.SIGNED_OUT) refreshModels()
            if (_state.value == CodexState.READY) refreshSkills()
            while (isActive) {
                refreshThreads()
                delay(if (_state.value == CodexState.READY) 60_000 else 15_000)
                if (_state.value != CodexState.READY) refreshStatus()
            }
        }
        eventsJob = backendScope.launch(worker) {
            var backoff = 1_000L
            while (isActive) {
                var off = false
                client.events(if (lastSeq >= 0) lastSeq + 1 else -1).collect { ev ->
                    when (ev) {
                        is CodexSocketEvent.Opened -> backoff = 1_000L
                        is CodexSocketEvent.Frame -> handleFrame(ev.frame)
                        is CodexSocketEvent.Closed -> {
                            if (ev.disabled) { _state.value = CodexState.DISABLED; off = true }
                            if (ev.outdated) { _state.value = CodexState.OUTDATED; off = true }
                        }
                    }
                }
                delay(if (off) 60_000 else backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    /** Codex was turned off: stop everything and forget its chats. */
    fun stop() {
        loopJob?.cancel(); loopJob = null
        eventsJob?.cancel(); eventsJob = null
        detach()
        _conversations.value = emptyList()
        backendScope.launch(worker) { localConversations.clear() }
    }

    /** Leaves the chat on screen (another agent's chat opens). */
    fun detach() {
        wantedId = null
        _isStreaming.value = false
        _isLoadingConversation.value = false
        _pendingApprovals.value = emptyList()
        _queuedMessages.value = emptyList()
        _notice.value = null
        backendScope.launch(worker) {
            switchTo(null)
            _messages.value = emptyList()
        }
    }

    override fun retryConnections() {
        backendScope.launch {
            refreshStatus(showProgress = true)
            refreshModels()
            refreshThreads()
        }
    }

    override fun onAppForegrounded() {
        backendScope.launch {
            if (_state.value != CodexState.READY) refreshStatus()
            refreshThreads()
            backendScope.launch(worker) { attachedId?.takeIf { it in created }?.let { reload(it) } }
        }
    }

    /**
     * Asks the bridge whether Codex is there and signed in. An unreachable bridge right after the app starts keeps
     * "Checking…" and is asked again every 2 s (the local server is still coming up).
     */
    suspend fun refreshStatus(showProgress: Boolean = false) {
        if (showProgress && _state.value != CodexState.READY) _state.value = CodexState.CHECKING
        var result = client.status()
        while (result.exceptionOrNull()?.isBridgeUnreachable() == true &&
            com.example.gemini.data.agent.claude.BridgeStartup.isStartingUp()
        ) {
            if (_state.value != CodexState.READY) _state.value = CodexState.CHECKING
            delay(2_000)
            result = client.status()
        }
        if (result.isSuccess) com.example.gemini.data.agent.claude.BridgeStartup.markReached()
        result
            .onSuccess { st ->
                _status.value = st
                if (!st.installed) {
                    _state.value = CodexState.NOT_INSTALLED
                    return
                }
                refreshAccount()
            }
            .onFailure { e ->
                _state.value = when {
                    e is CodexUnavailableException && e.disabled -> CodexState.DISABLED
                    e is CodexUnavailableException && e.outdated -> CodexState.OUTDATED
                    e is java.io.IOException -> CodexState.OFFLINE
                    else -> CodexState.ERROR
                }
                _status.value = CodexStatus(error = e.message)
            }
    }

    /** Reads the account (starts Codex on the server if needed); READY or SIGNED_OUT. */
    suspend fun refreshAccount() {
        client.rpc("account/read", buildJsonObject { put("refreshToken", false) })
            .onSuccess { res ->
                val o = res.obj()
                val acc = o.o("account")
                _account.value = acc?.let { CodexAccount(it.str("type").orEmpty(), it.str("email"), it.str("planType")) }
                val needsAuth = o?.get("requiresOpenaiAuth")?.toString() == "true"
                _state.value = if (acc == null && needsAuth) CodexState.SIGNED_OUT else CodexState.READY
                if (acc != null) refreshRateLimits()
            }
            .onFailure { e ->
                // Codex did not start (or answer): the status shows why
                _state.value = CodexState.ERROR
                _status.value = (_status.value ?: CodexStatus()).copy(error = e.message)
            }
    }

    private var limitsJob: Job? = null

    /** Reads the usage limits again shortly (a burst of finished turns asks once). */
    private fun refreshRateLimitsSoon() {
        if (limitsJob?.isActive == true) return
        limitsJob = backendScope.launch {
            delay(1_500)
            refreshRateLimits()
        }
    }

    suspend fun refreshRateLimits() {
        client.rpc("account/rateLimits/read").onSuccess { res -> _rateLimits.value = limitsOf(res.obj().o("rateLimits")) }
    }

    private fun limitsOf(o: JsonObject?): CodexRateLimits? {
        if (o == null) return null
        fun window(w: JsonObject?) = w?.let { CodexLimitWindow(it.dbl("usedPercent") ?: 0.0, it.long("windowDurationMins"), it.long("resetsAt")) }
        return CodexRateLimits(window(o.o("primary")), window(o.o("secondary")), o.str("planType"))
    }

    /** Installs ([action] "install") or updates ("update") Codex on the server and follows the job's log. */
    fun startCliJob(action: String) {
        backendScope.launch {
            client.startCliJob(action)
                .onSuccess { job ->
                    _cliJob.value = job
                    while (isActive) {
                        delay(1_500)
                        val j = client.cliJob().getOrNull() ?: continue
                        _cliJob.value = j
                        if (!j.running) {
                            val ok = j.exit_code == 0
                            _toasts.tryEmit(if (ok) "Codex ${if (action == "install") "installed" else "is up to date"}" else "Codex $action failed")
                            refreshStatus()
                            if (ok) {
                                refreshModels()
                                refreshThreads()
                            }
                            break
                        }
                    }
                }
                .onFailure { _toasts.tryEmit("Could not start the $action: ${it.message}") }
        }
    }

    /** Picks up a job that is still running (the settings page was reopened). */
    fun refreshCliJob() {
        backendScope.launch { client.cliJob().getOrNull()?.let { _cliJob.value = it } }
    }

    fun restartServer(onDone: (String?) -> Unit = {}) {
        backendScope.launch {
            val r = client.restart()
            refreshStatus()
            refreshModels()
            onDone(r.exceptionOrNull()?.message)
        }
    }

    // ------------------------------------------------------------------ account

    /**
     * Starts a ChatGPT sign-in. [deviceCode]: a code to type on any device (works with a remote server too);
     * otherwise a browser sign-in that comes back to Codex on this device (local server only).
     */
    fun startLogin(deviceCode: Boolean) {
        backendScope.launch {
            val params = buildJsonObject { put("type", if (deviceCode) "chatgptDeviceCode" else "chatgpt") }
            client.rpc("account/login/start", params)
                .onSuccess { res ->
                    val o = res.obj()
                    val id = o.str("loginId") ?: return@onSuccess
                    val url = o.str("verificationUrl") ?: o.str("authUrl") ?: return@onSuccess
                    _login.value = CodexLogin(id, url, o.str("userCode"))
                }
                .onFailure { _toasts.tryEmit("Could not start the sign-in: ${it.message}") }
        }
    }

    fun cancelLogin() {
        val l = _login.value ?: return
        _login.value = null
        backendScope.launch { client.rpc("account/login/cancel", buildJsonObject { put("loginId", l.loginId) }) }
    }

    fun logout() {
        backendScope.launch {
            client.rpc("account/logout")
                .onSuccess { refreshAccount() }
                .onFailure { _toasts.tryEmit("Could not sign out: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ models, threads

    fun refreshModels() {
        backendScope.launch {
            _isRefreshingModels.value = true
            try {
                client.rpc("model/list", buildJsonObject { put("limit", 100) })
                    .onSuccess { res ->
                        allModelIds = res.obj().a("data").orEmpty().mapNotNull { it.obj().str("id") }.toSet()
                        val raw = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.filter { it.str("hidden") != "true" }
                        modelEfforts = raw.associate { m ->
                            m.str("id").orEmpty() to m.a("supportedReasoningEfforts").orEmpty().mapNotNull { it.obj().str("reasoningEffort") }
                        }
                        modelDefaultEffort = raw.associate { m -> m.str("id").orEmpty() to m.str("defaultReasoningEffort").orEmpty() }
                        val list = raw.map { m ->
                            val id = m.str("id").orEmpty()
                            AiModel(
                                id = id,
                                displayName = m.str("displayName")?.ifBlank { null } ?: id,
                                family = ModelFamily.OTHER,
                                supportsThinking = true,
                                description = m.str("description").orEmpty(),
                                isDefault = m.str("isDefault") == "true",
                                key = id,
                                baseName = "OpenAI"
                            )
                        }
                        _availableModels.value = list
                        _modelsError.value = if (list.isEmpty()) "No models: sign in to Codex in Settings → Codex." else null
                        val cur = _selectedModelId.value
                        if (cur.isBlank() || list.none { it.id == cur }) {
                            val conv = store.currentConversation.value
                            if (conv?.agent != AgentKind.CODEX || conv.id !in createdSnapshot()) {
                                _selectedModelId.value = prefs.model.value.takeIf { p -> list.any { it.id == p } }
                                    ?: list.firstOrNull { it.isDefault }?.id ?: list.firstOrNull()?.id.orEmpty()
                            }
                        }
                        syncEfforts()
                    }
                    .onFailure { _modelsError.value = it.message }
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    /** The effort list of the selected model; an effort it does not support falls back to its default. */
    private fun syncEfforts() {
        val model = _selectedModelId.value
        val list = modelEfforts[model].orEmpty()
        _efforts.value = list
        if (list.isNotEmpty() && _effort.value !in list) {
            _effort.value = prefs.effort.value.takeIf { it in list } ?: modelDefaultEffort[model]?.takeIf { it.isNotBlank() } ?: list.first()
        }
    }

    private fun createdSnapshot(): Set<String> = synchronized(created) { created.toSet() }

    suspend fun refreshThreads() {
        val sources = buildJsonArray { listOf("cli", "vscode", "exec", "appServer").forEach { add(JsonPrimitive(it)) } }
        val res = client.rpc("thread/list", buildJsonObject {
            put("limit", 100); put("sortKey", "updated_at"); put("sourceKinds", sources)
        }).getOrElse { return }
        val archived = client.rpc("thread/list", buildJsonObject {
            put("limit", 50); put("sortKey", "updated_at"); put("archived", true); put("sourceKinds", sources)
        }).getOrNull()
        val list = res.obj().a("data").orEmpty().mapNotNull { conversationOf(it.obj(), false) } +
            archived.obj().a("data").orEmpty().mapNotNull { conversationOf(it.obj(), true) }
        val active = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.filter { it.o("status").str("type") == "active" }.mapNotNull { it.str("id") }
        backendScope.launch(worker) {
            synchronized(created) { list.forEach { created.add(it.id) } }
            running.removeAll { it !in active && list.any { c -> c.id == it } }
            running.addAll(active)
            localConversations.keys.removeAll(list.map { it.id }.toSet())
            publishConversations(list)
        }
    }

    private fun publishConversations(serverList: List<Conversation>? = null) {
        if (serverList != null) lastServerList = serverList
        // a new chat is local until Codex lists it: it is running too while its first reply runs
        _conversations.value = (localConversations.values.map { c -> c.copy(isRunning = c.id in running) } + lastServerList.map { c -> c.copy(isRunning = c.id in running) })
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
    }

    private fun conversationOf(t: JsonObject?, archived: Boolean): Conversation? {
        val id = t.str("id") ?: return null
        val cwd = t.str("cwd").orEmpty()
        // the preview is the first message as sent: its hidden blocks (even cut off) never become the title
        val title = t.str("name")?.let { com.example.gemini.data.remote.PromptExpansion.cleanTitle(it) }?.takeIf { it.isNotBlank() }
            ?: t.str("preview")?.let { com.example.gemini.data.remote.PromptExpansion.cleanTitle(it) }?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.let { if (it.length > 60) it.take(60) + "…" else it }
            ?: "New Chat"
        return Conversation(
            id = id,
            title = title,
            modelId = t.str("model").orEmpty(),
            sessionId = id,
            createdAt = (t.long("createdAt") ?: 0L) * 1000,
            updatedAt = (t.long("updatedAt") ?: t.long("createdAt") ?: 0L) * 1000,
            isRunning = t.o("status").str("type") == "active",
            workspaceUri = if (cwd.isBlank()) "" else "file://$cwd",
            agent = AgentKind.CODEX,
            archived = archived
        )
    }

    // ------------------------------------------------------------------ AgentChatBackend

    override fun startNewChat() {
        val id = "codex-new-" + UUID.randomUUID()
        _selectedModelId.value = prefs.model.value.takeIf { p -> _availableModels.value.any { it.id == p } }
            ?: _availableModels.value.firstOrNull { it.isDefault }?.id ?: _selectedModelId.value
        _effort.value = prefs.effort.value
        syncEfforts()
        store.currentConversation.value = Conversation(
            id = id, title = "New Chat", modelId = _selectedModelId.value, sessionId = id,
            workspaceUri = defaultWorkspaceUri(), agent = AgentKind.CODEX
        )
        _planMode.value = false
        wantedId = id
        _conversationError.value = null
        _isLoadingConversation.value = false
        _isStreaming.value = false
        _pendingApprovals.value = emptyList()
        _queuedMessages.value = emptyList()
        _notice.value = null
        backendScope.launch(worker) {
            switchTo(id)
            emitNow()
        }
    }

    /** The folder of the fresh chat (nothing sent yet). */
    fun setWorkspaceOfNewChat(path: String) {
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.CODEX || conv.id in createdSnapshot() || path.isBlank()) return
        store.currentConversation.value = conv.copy(workspaceUri = if (path.startsWith("file://")) path else "file://$path")
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id }
            ?: Conversation(id = id, title = "Codex Chat", sessionId = id, agent = AgentKind.CODEX)
        store.currentConversation.value = conv
        if (conv.modelId.isNotBlank()) { _selectedModelId.value = conv.modelId; syncEfforts() }
        _planMode.value = false
        wantedId = id
        _conversationError.value = null
        _isLoadingConversation.value = true
        _pendingApprovals.value = emptyList()
        _queuedMessages.value = emptyList()
        _notice.value = null
        _messages.value = emptyList()
        backendScope.launch(worker) {
            // another chat was opened meanwhile: this one's load is no longer wanted
            if (wantedId != id) return@launch
            // a chat left in the middle of a reply comes back with what was streamed meanwhile
            switchTo(id)
            if (id.startsWith("codex-new-")) {
                emitNow()
            } else {
                synchronized(created) { created.add(id) }
                loadingHistory = true
                try {
                    reload(id, restoreChoices = true)
                } finally {
                    loadingHistory = false
                }
                emitNow()
            }
            if (wantedId == id) _isLoadingConversation.value = false
        }
    }

    /** Loads the chat's turns (without loading the thread in Codex: reading needs no subscription). On [worker]. */
    private suspend fun reload(id: String, restoreChoices: Boolean = false) {
        val reloadStartedAt = System.nanoTime()
        val read = client.rpc("thread/read", buildJsonObject { put("threadId", id) }).getOrElse { e ->
            if (attachedId == id) _conversationError.value = "Could not load this chat: ${e.message}"
            return
        }.obj().o("thread")
        val turns = mutableListOf<JsonObject>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = client.rpc("thread/turns/list", buildJsonObject {
                put("threadId", id); put("limit", 30); put("itemsView", "full"); put("sortDirection", "desc")
                cursor?.let { put("cursor", it) }
            }).getOrElse { e ->
                if (attachedId == id) _conversationError.value = "Could not load this chat: ${e.message}"
                return
            }.obj()
            turns.addAll(page.a("data").orEmpty().mapNotNull { it.obj() })
            cursor = page.str("nextCursor")
        } while (cursor != null && ++pages < 4)
        if (attachedId != id) return
        val active = read.o("status").str("type") == "active"
        // a turn started while this was loading: the snapshot is older than what is on screen, its end reloads again
        if (!active && transcript.isTurnActive && turnStartedAt > reloadStartedAt) return
        // each turn's model and token use: Codex's record on disk (history has neither)
        val meta = client.turnsMeta(id).getOrNull()
        if (attachedId != id || wantedId != id) return
        transcript.model = read.str("model")
        transcript.loadHistory(turns.asReversed(), active)
        meta?.let { transcript.applyMeta(it) }
        if (restoreChoices) {
            // the picker shows what the chat last ran on: its newest turn's model and effort (the thread's own setting
            // can differ, e.g. changed elsewhere without a message)
            val last = turns.firstNotNullOfOrNull { t -> t.str("id")?.let { meta?.get(it) }?.takeIf { it.model != null } }
            (last?.model ?: read.str("model"))?.takeIf { it.isNotBlank() }?.let { _selectedModelId.value = it }
            (last?.effort ?: read.str("reasoningEffort"))?.takeIf { it.isNotBlank() }?.let { _effort.value = it }
            syncEfforts()
        }
        if (active) running.add(id) else running.remove(id)
        publishConversations()
        refreshQueue(id)
        emitNow()
    }

    override fun sendMessage(content: String) {
        val atts = store.attachments.value
        if (content.isBlank() && atts.isEmpty()) return
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.CODEX) return
        // commands are read without the app's hidden notes (its global context goes in front of messages)
        if (atts.isEmpty() && runCommand(conv, com.example.gemini.data.remote.PromptExpansion.collapse(content).trim())) return
        if (_state.value.isBlocking) {
            _toasts.tryEmit("Codex is not available: ${_state.value.label}. Check Settings → Codex.")
            return
        }
        store.attachments.value = emptyList()
        val text = content.trim()
        val title = if (conv.title == "New Chat" || conv.title.isBlank()) {
            val first = com.example.gemini.data.remote.PromptExpansion.collapse(text.ifBlank { "Attachment" }).lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            first.take(40) + if (first.length > 40) "..." else ""
        } else conv.title
        val updated = conv.copy(title = title, updatedAt = System.currentTimeMillis(), modelId = _selectedModelId.value)
        store.currentConversation.value = updated
        _conversationError.value = null
        val clientId = "agm_" + UUID.randomUUID()
        backendScope.launch(worker) {
            if (attachedId != conv.id) switchTo(conv.id)
            val isNew = conv.id !in createdSnapshot()
            val busy = !isNew && transcript.isTurnActive
            val notes = mutableListOf<String>()
            val inputs = mutableListOf<JsonObject>()
            for (a in atts) attachmentInput(a, workspacePath(updated).orEmpty(), inputs, notes)
            val promptText = (listOf(text) + notes).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "See the attached file." }
            // "/skill-name …": the skill goes along as Codex's own skill input
            val skill = Regex("^/([A-Za-z0-9_.:-]+)(\\s|$)").find(com.example.gemini.data.remote.PromptExpansion.collapse(text).trim())?.groupValues?.get(1)?.let { n -> skillPaths[n]?.let { n to it } }
            val skillInput = skill?.let { (n, path) -> buildJsonObject { put("type", "skill"); put("name", n); put("path", path) } }
            val input = JsonArray(listOf(textInput(promptText)) + listOfNotNull(skillInput) + inputs)
            if (busy) {
                // Codex is working on this chat: the message waits in Codex's queue and runs after the current turn
                sentTo.add(conv.id)
                client.rpc("thread/queue/add", buildJsonObject {
                    put("threadId", conv.id); put("input", input); put("clientUserMessageId", clientId)
                }).onSuccess {
                    if (!transcript.isTurnActive) client.rpc("thread/queue/start", buildJsonObject { put("threadId", conv.id) })
                    refreshQueue(conv.id)
                }.onFailure { _toasts.tryEmit("Could not queue the message: ${it.message}") }
                return@launch
            }
            // images show as previews, other files sent by the app by name; files by path come from the text's list
            val images = atts.filter { it.isImage && !it.base64.isNullOrBlank() }
            transcript.addLocalPrompt(
                clientId, text, atts.filter { !it.reference && it !in images }.map { it.name }, startsTurn = true, images = images
            )
            turnStartedAt = System.nanoTime()
            _isStreaming.value = true
            emitNow()
            var threadId = conv.id
            if (isNew) {
                localConversations[conv.id] = updated
                publishConversations()
                val mode = prefs.permissionMode.value
                val r = client.rpc("thread/start", buildJsonObject {
                    workspacePath(updated)?.let { put("cwd", it) }
                    _selectedModelId.value.takeIf { it.isNotBlank() }?.let { put("model", it) }
                    put("approvalPolicy", CodexPreferences.approvalPolicy(mode))
                    put("sandbox", CodexPreferences.sandboxMode(mode))
                    put("serviceName", "AntiGem")
                })
                val tid = r.getOrNull().obj().o("thread").str("id")
                if (tid == null) {
                    fail(clientId, "Could not start the Codex chat", r.exceptionOrNull())
                    return@launch
                }
                threadId = tid
                remap(conv.id, tid)
            }
            running.add(threadId)
            sentTo.add(threadId)
            publishConversations()
            // the turn runs on the model picked now (its card and the "Switched to" divider show it)
            _selectedModelId.value.takeIf { it.isNotBlank() }?.let { transcript.model = it }
            val mode = prefs.permissionMode.value
            val r = client.rpc("turn/start", buildJsonObject {
                put("threadId", threadId)
                put("input", input)
                put("clientUserMessageId", clientId)
                _selectedModelId.value.takeIf { it.isNotBlank() }?.let { put("model", it) }
                _effort.value.takeIf { it.isNotBlank() }?.let { put("effort", it) }
                // the model's thinking comes as a summary only when asked for: the chat shows it as the thinking block
                put("summary", "detailed")
                put("approvalPolicy", CodexPreferences.approvalPolicy(mode))
                put("sandboxPolicy", CodexPreferences.sandboxPolicy(mode))
                collaborationMode()?.let { put("collaborationMode", it) }
            })
            if (r.isFailure) fail(clientId, "Codex did not take the message", r.exceptionOrNull())
        }
    }

    /**
     * Makes [id] the chat on screen (null: none). The chat left keeps a live copy when its reply is still running; the
     * chat opened takes over its live copy, if it has one (on [worker]).
     */
    private fun switchTo(id: String?) {
        val left = attachedId
        if (left != null && left != id && transcript.isTurnActive && !left.startsWith("codex-new-")) shadows[left] = transcript
        transcript = id?.let { shadows.remove(it) } ?: CodexTranscript(id.orEmpty())
        transcript.threadId = id.orEmpty()
        attachedId = id
    }

    /** A new chat got its thread id: the app's temporary id is replaced everywhere (on [worker]). */
    private fun remap(tempId: String, threadId: String) {
        synchronized(created) { created.add(threadId) }
        val local = localConversations.remove(tempId)
        if (attachedId == tempId) {
            attachedId = threadId
            transcript.threadId = threadId
        }
        if (wantedId == tempId) wantedId = threadId
        val cur = store.currentConversation.value
        if (cur?.id == tempId) store.currentConversation.value = cur.copy(id = threadId, sessionId = threadId)
        localConversations[threadId] = (local ?: cur ?: Conversation(id = threadId, agent = AgentKind.CODEX)).copy(id = threadId, sessionId = threadId)
        // the app's context went with the first message, which the temporary id was marked for
        val gc = com.example.gemini.data.context.GlobalContext
        if (gc.firstMessageBody() != null) {
            gc.markSent(threadId, gc.fingerprint())
            gc.forget(tempId)
        }
        // files copied for the first message were kept under the temporary id
        com.example.gemini.data.files.AttachedFiles.moveCopies(application, tempId, threadId)
        publishConversations()
    }

    private suspend fun attachmentInput(a: ChatAttachment, project: String, inputs: MutableList<JsonObject>, notes: MutableList<String>) {
        val b64 = a.base64
        // a file by path is already listed in the message's text; only an image's shrunk copy is sent itself
        if (a.reference && b64.isNullOrBlank()) return
        if (b64.isNullOrBlank()) {
            if (a.path.startsWith("/")) {
                if (a.isImage) inputs.add(buildJsonObject { put("type", "localImage"); put("path", a.path) })
                else notes.add("Attached file: ${a.path}")
            }
            return
        }
        // the file is saved on the server, where Codex can open it
        client.upload(a.name, b64, project)
            .onSuccess { path ->
                val mime = a.mimeType.orEmpty()
                if (a.isImage || mime.startsWith("image/")) inputs.add(buildJsonObject { put("type", "localImage"); put("path", path) })
                else notes.add("Attached file `${a.name}` (saved at $path)")
            }
            .onFailure { notes.add("(The attachment ${a.name} could not be saved on the server: ${it.message})") }
    }

    private fun fail(clientId: String, title: String, e: Throwable?) {
        Log.w(TAG, "$title: ${e?.message}")
        transcript.removeLocalPrompt(clientId)
        transcript.addError(title, e?.message ?: "Unknown error")
        attachedId?.let { running.remove(it) }
        publishConversations()
        emitNow()
    }

    /** Stops a chat's turn, open or not. */
    suspend fun interrupt(threadId: String): Result<Unit> {
        val turnId = (if (threadId == attachedId) transcript.activeTurnId else null)
            ?: client.rpc("thread/turns/list", buildJsonObject { put("threadId", threadId); put("limit", 1); put("sortDirection", "desc") })
                .getOrNull().obj().a("data").orEmpty().firstOrNull().obj()?.takeIf { it.str("status") == "inProgress" }?.str("id")
        if (turnId == null) return Result.success(Unit)
        return client.rpc("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) }).map { }
    }

    override fun stopStreaming() {
        val id = store.currentConversation.value?.takeIf { it.agent == AgentKind.CODEX }?.id ?: return
        backendScope.launch(worker) {
            interrupt(id).onFailure { _toasts.tryEmit("Could not stop: ${it.message}") }
        }
    }

    override fun deleteConversation(id: String) {
        backendScope.launch {
            if (id in createdSnapshot()) {
                val r = client.rpc("thread/delete", buildJsonObject { put("threadId", id) })
                if (r.isFailure) {
                    _toasts.tryEmit("Could not delete the chat: ${r.exceptionOrNull()?.message}")
                    return@launch
                }
            }
            backendScope.launch(worker) {
                localConversations.remove(id)
                lastServerList = lastServerList.filter { it.id != id }
                synchronized(created) { created.remove(id) }
                publishConversations()
            }
            if (store.currentConversation.value?.id == id) startNewChat()
        }
    }

    override fun forkConversation(id: String) {
        backendScope.launch {
            if (id !in createdSnapshot()) return@launch
            client.rpc("thread/fork", buildJsonObject { put("threadId", id); put("excludeTurns", true) })
                .onSuccess { res ->
                    val newId = res.obj().o("thread").str("id")
                    refreshThreads()
                    if (newId != null) selectConversation(newId)
                }
                .onFailure { _toasts.tryEmit("Could not copy the chat: ${it.message}") }
        }
    }

    fun renameConversation(id: String, title: String) {
        backendScope.launch {
            if (id !in createdSnapshot()) {
                backendScope.launch(worker) {
                    localConversations[id]?.let { localConversations[id] = it.copy(title = title) }
                    publishConversations()
                }
                store.currentConversation.value?.takeIf { it.id == id }?.let { store.currentConversation.value = it.copy(title = title) }
                return@launch
            }
            client.rpc("thread/name/set", buildJsonObject { put("threadId", id); put("name", title) })
                .onSuccess { backendScope.launch(worker) { applyTitle(id, title) } }
                .onFailure { _toasts.tryEmit("Could not rename: ${it.message}") }
        }
    }

    /** Archives / unarchives a chat. */
    fun setArchived(id: String, archived: Boolean) {
        backendScope.launch {
            if (id !in createdSnapshot()) return@launch
            client.rpc(if (archived) "thread/archive" else "thread/unarchive", buildJsonObject { put("threadId", id) })
                .onSuccess { refreshThreads() }
                .onFailure { _toasts.tryEmit("Could not ${if (archived) "archive" else "unarchive"} the chat: ${it.message}") }
        }
    }

    /** Messages of a chat that is not open (export / share from the sidebar). */
    suspend fun loadMessages(id: String): List<ChatMessage> {
        val res = client.rpc("thread/turns/list", buildJsonObject {
            put("threadId", id); put("limit", 100); put("itemsView", "full"); put("sortDirection", "desc")
        }).getOrNull() ?: return emptyList()
        val t = CodexTranscript(id)
        t.loadHistory(res.obj().a("data").orEmpty().mapNotNull { it.obj() }.asReversed(), active = false)
        client.turnsMeta(id).getOrNull()?.let { t.applyMeta(it) }
        return t.toChatMessages(id, emptySet(), null)
    }

    // ------------------------------------------------------------------ chat controls

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        if (rememberChoices) prefs.setModel(modelId)
        syncEfforts()
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.CODEX } ?: return
        store.currentConversation.value = conv.copy(modelId = modelId)
    }

    /** Switches the model of any chat (open or not); applies to its next message. */
    suspend fun switchModelOf(threadId: String, modelId: String): Result<Unit> =
        client.rpc("thread/settings/update", buildJsonObject { put("threadId", threadId); put("model", modelId) }).map { }

    fun selectEffort(effort: String) {
        _effort.value = effort
        if (rememberChoices) prefs.setEffort(effort)
    }

    /** Plan mode on / off for the chat's next messages. */
    fun setPlanMode(on: Boolean) {
        _planMode.value = on
    }

    /** `collaborationMode` of a turn (plan or default), or null when the model is not known yet. */
    private fun collaborationMode(): JsonObject? {
        val model = _selectedModelId.value.ifBlank { return null }
        return buildJsonObject {
            put("mode", if (_planMode.value) "plan" else "default")
            put("settings", buildJsonObject {
                put("model", model)
                _effort.value.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) } ?: put("reasoning_effort", JsonNull)
                put("developer_instructions", JsonNull)
            })
        }
    }

    /** Applies to the chat's next message (every turn carries the mode) and to new chats. */
    fun setPermissionMode(mode: String) = prefs.setPermissionMode(mode)

    // ------------------------------------------------------------------ commands, skills

    /** What a "/" command opens in the app instead of being sent (the chat screen handles it). */
    enum class AppCommand { MODEL_PICKER, SETTINGS, MCP, NEW_CHAT }

    /** The app action of [text] ("/model", "/status"…), or null when it is not one. */
    fun appCommand(text: String): AppCommand? = when (text.trim().lowercase()) {
        "/model", "/models" -> AppCommand.MODEL_PICKER
        "/status", "/usage", "/limits", "/account", "/login", "/logout" -> AppCommand.SETTINGS
        "/mcp" -> AppCommand.MCP
        "/new", "/clear" -> AppCommand.NEW_CHAT
        else -> null
    }

    /** Codex's skills (`skills/list`) for the "/" menu. */
    fun refreshSkills() {
        backendScope.launch {
            val cwd = workspacePath(store.currentConversation.value ?: return@launch)
            val res = client.rpc("skills/list", buildJsonObject {
                cwd?.let { put("cwds", JsonArray(listOf(JsonPrimitive(it)))) }
            }).getOrNull() ?: return@launch
            val skills = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.flatMap { e -> e.a("skills").orEmpty().mapNotNull { it.obj() } }
                .filter { it.str("enabled") != "false" }
                .distinctBy { it.str("name") }
            skillPaths = skills.mapNotNull { sk -> sk.str("name")?.let { n -> sk.str("path")?.let { n to it } } }.toMap()
            _slashCommands.value = BUILTIN_COMMANDS + skills.mapNotNull { sk ->
                val n = sk.str("name") ?: return@mapNotNull null
                com.example.gemini.data.remote.SlashCommandItem(
                    name = n, command = "/$n",
                    description = sk.str("shortDescription")?.ifBlank { null } ?: sk.str("description").orEmpty(),
                    type = "skill", argumentHint = "what to do"
                )
            }
        }
    }

    /** Runs a Codex command typed as a message ("/compact", "/review …", "/plan …", "/fork"); false when it is not one. */
    private fun runCommand(conv: Conversation, text: String): Boolean {
        if (!text.startsWith("/")) return false
        val name = text.substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (name) {
            "/compact" -> compact()
            "/review" -> startReview(arg.ifBlank { null })
            "/plan" -> {
                setPlanMode(true)
                if (arg.isBlank()) _toasts.tryEmit("Plan mode is on: Codex plans before changing anything.")
                // the task itself goes out as a normal message, now in plan mode
                else backendScope.launch(Dispatchers.Main) { sendMessage(arg) }
            }
            "/fork" -> if (conv.id in createdSnapshot()) forkConversation(conv.id) else _toasts.tryEmit("Send a message first: there is nothing to copy yet.")
            "/stop" -> stopStreaming()
            else -> return false
        }
        return true
    }

    /** Summarizes the chat so far (Codex's /compact): frees the context window. */
    fun compact() {
        val id = store.currentConversation.value?.takeIf { it.agent == AgentKind.CODEX }?.id ?: return
        backendScope.launch(worker) {
            if (id !in createdSnapshot()) { _toasts.tryEmit("Nothing to compact yet."); return@launch }
            if (transcript.isTurnActive) { _toasts.tryEmit("Wait for the reply to finish first"); return@launch }
            _isStreaming.value = true
            client.rpc("thread/compact/start", buildJsonObject { put("threadId", id) })
                .onFailure { _isStreaming.value = false; _toasts.tryEmit("Could not compact: ${it.message}") }
        }
    }

    /**
     * Codex's code review (/review) in this chat: [instructions] what to review, or null for the uncommitted changes of
     * the project.
     */
    fun startReview(instructions: String?) {
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.CODEX } ?: return
        backendScope.launch(worker) {
            if (transcript.isTurnActive) { _toasts.tryEmit("Wait for the reply to finish first"); return@launch }
            val id = if (conv.id in createdSnapshot()) conv.id else createThread(conv) ?: return@launch
            _isStreaming.value = true
            client.rpc("review/start", buildJsonObject {
                put("threadId", id)
                put("target", if (instructions == null) buildJsonObject { put("type", "uncommittedChanges") }
                else buildJsonObject { put("type", "custom"); put("instructions", instructions) })
            }).onFailure { _isStreaming.value = false; _toasts.tryEmit("Could not start the review: ${it.message}") }
        }
    }

    /** Creates the thread of the fresh chat [conv] now (on [worker]); its id, or null after a toast. */
    private suspend fun createThread(conv: Conversation): String? {
        val mode = prefs.permissionMode.value
        val r = client.rpc("thread/start", buildJsonObject {
            workspacePath(conv)?.let { put("cwd", it) }
            _selectedModelId.value.takeIf { it.isNotBlank() }?.let { put("model", it) }
            put("approvalPolicy", CodexPreferences.approvalPolicy(mode))
            put("sandbox", CodexPreferences.sandboxMode(mode))
            put("serviceName", "AntiGem")
        })
        val tid = r.getOrNull().obj().o("thread").str("id")
        if (tid == null) {
            _toasts.tryEmit("Could not start the Codex chat: ${r.exceptionOrNull()?.message}")
            return null
        }
        localConversations[conv.id] = conv
        remap(conv.id, tid)
        return tid
    }

    /**
     * Starts a new chat and creates its thread right away (Jarvis and its tasks need the real id before the first
     * message). [projectPath] null = the active project.
     */
    suspend fun createChatNow(projectPath: String? = null, modelId: String? = null): String {
        startNewChat()
        projectPath?.takeIf { it.isNotBlank() }?.let { setWorkspaceOfNewChat(it) }
        modelId?.takeIf { it.isNotBlank() }?.let { selectModel(it) }
        val conv = store.currentConversation.value ?: error("Could not create the chat")
        return kotlinx.coroutines.withContext(worker) { createThread(conv) } ?: error("Could not create the Codex chat")
    }

    // ------------------------------------------------------------------ retry & edit (Codex's thread/revert)

    /** Asks again: the chat goes back to right before the turn of reply [assistantMessageId] and its prompt is sent again. */
    fun regenerate(assistantMessageId: String) {
        backendScope.launch(worker) {
            val sid = attachedId ?: return@launch
            if (transcript.isTurnActive) { _toasts.tryEmit("Stop the reply first"); return@launch }
            val turn = transcript.turnOfItem(assistantMessageId)
            val prompt = turn?.let { transcript.promptOf(it) }
            if (turn == null || prompt.isNullOrBlank()) { _toasts.tryEmit("Nothing to retry here"); return@launch }
            if (!revertTo(sid, turn.id)) return@launch
            reload(sid)
            kotlinx.coroutines.withContext(Dispatchers.Main) { sendMessage(prompt) }
        }
    }

    /**
     * Edits a sent prompt: the chat goes back to right before its turn and the text returns to the input. Codex does not
     * undo file changes (only the chat history goes back).
     */
    fun editMessage(userMessageId: String, onRestored: (String) -> Unit) {
        backendScope.launch(worker) {
            val sid = attachedId ?: return@launch
            if (transcript.isTurnActive) { _toasts.tryEmit("Stop the reply first"); return@launch }
            val turn = transcript.turnOfItem(userMessageId)
            if (turn == null) { _toasts.tryEmit("This message can't be edited yet"); return@launch }
            val text = turn.items[userMessageId]?.json?.a("content").orEmpty().mapNotNull { it.obj() }
                .filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
                .ifBlank { transcript.promptOf(turn).orEmpty() }
            if (!revertTo(sid, turn.id)) return@launch
            reload(sid)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onRestored(text) }
        }
    }

    private suspend fun revertTo(sid: String, turnId: String): Boolean {
        val r = client.rpc("thread/revert", buildJsonObject { put("threadId", sid); put("beforeTurnId", turnId) })
        r.onFailure { _toasts.tryEmit("Could not go back in the chat: ${it.message}") }
        return r.isSuccess
    }

    // ------------------------------------------------------------------ MCP servers (Codex's config.toml)

    /** One MCP server of Codex's config, with its live state (null until a chat connected it). */
    data class McpServer(val name: String, val config: JsonObject, val status: String?, val error: String?, val toolCount: Int)

    /** The MCP servers in Codex's config (`mcp_servers`), with their state. */
    suspend fun mcpList(): Result<List<McpServer>> = runCatching {
        val cfg = client.rpc("config/read", buildJsonObject { put("includeLayers", false) }).getOrThrow().obj().o("config").o("mcp_servers")
            ?: JsonObject(emptyMap())
        val live = client.rpc("mcpServerStatus/list", buildJsonObject { put("detail", "toolsAndAuthOnly") }, timeoutMs = 60_000).getOrNull()
            .obj().a("data").orEmpty().mapNotNull { it.obj() }.associateBy { it.str("name").orEmpty() }
        cfg.keys.sorted().map { name ->
            val st = live[name]
            McpServer(
                name = name,
                config = cfg[name].obj() ?: JsonObject(emptyMap()),
                status = st.str("runtimeStatus") ?: if (st != null && st.str("toolsError") == null) "connected" else if (st?.str("toolsError") != null) "failed" else null,
                error = st.str("toolsError"),
                toolCount = st.o("tools")?.size ?: 0
            )
        }
    }

    /** Names of the MCP servers in Codex's config. */
    suspend fun mcpServerNames(): Result<Set<String>> = runCatching {
        client.rpc("config/read", buildJsonObject { put("includeLayers", false) }).getOrThrow().obj().o("config").o("mcp_servers")?.keys ?: emptySet()
    }

    private suspend fun writeMcp(name: String, value: JsonElement): Result<Unit> = runCatching {
        client.rpc("config/batchWrite", buildJsonObject {
            put("edits", JsonArray(listOf(buildJsonObject { put("keyPath", "mcp_servers.$name"); put("value", value); put("mergeStrategy", "replace") })))
            put("reloadUserConfig", true)
        }).getOrThrow()
        client.rpc("config/mcpServer/reload")
    }

    /** Adds (or replaces) a local MCP server (a command Codex starts) for every Codex chat. */
    suspend fun addLocalMcp(name: String, command: List<String>, env: Map<String, String>): Result<Unit> {
        if (command.isEmpty()) return Result.failure(IllegalArgumentException("The command is empty"))
        return writeMcp(name, buildJsonObject {
            put("command", command.first())
            if (command.size > 1) put("args", JsonArray(command.drop(1).map { JsonPrimitive(it) }))
            if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        })
    }

    /** Adds (or replaces) a remote (HTTP) MCP server for every Codex chat. */
    suspend fun addRemoteMcp(name: String, url: String, headers: Map<String, String>): Result<Unit> =
        writeMcp(name, buildJsonObject {
            put("url", url)
            if (headers.isNotEmpty()) put("http_headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
        })

    suspend fun removeMcp(name: String): Result<Unit> = writeMcp(name, JsonNull)

    // ------------------------------------------------------------------ approvals & questions

    /** Allows a request: once, or for the rest of the session ([always]). */
    fun approve(key: String, always: Boolean = false) {
        backendScope.launch(worker) {
            val p = pendingAll[key] ?: return@launch
            val result: JsonElement = when (p.method) {
                "item/permissions/requestApproval" -> buildJsonObject {
                    put("permissions", p.params.o("permissions") ?: JsonObject(emptyMap()))
                    put("scope", if (always) "session" else "turn")
                }
                "mcpServer/elicitation/request" -> buildJsonObject { put("action", "accept"); put("content", JsonNull); put("_meta", JsonNull) }
                "applyPatchApproval", "execCommandApproval" -> buildJsonObject { put("decision", if (always) "approved_for_session" else "approved") }
                else -> buildJsonObject { put("decision", if (always) "acceptForSession" else "accept") }
            }
            answer(p, result)
        }
    }

    /** Allows a command and saves the rule Codex suggested (its command prefix), so it is never asked again. */
    fun approveRule(key: String) {
        backendScope.launch(worker) {
            val p = pendingAll[key] ?: return@launch
            val rule = p.params.a("proposedExecpolicyAmendment") ?: return@launch
            answer(p, buildJsonObject {
                put("decision", buildJsonObject { put("acceptWithExecpolicyAmendment", buildJsonObject { put("execpolicy_amendment", rule) }) })
            })
        }
    }

    /** Declines a request; [feedback] (optional) is sent to Codex right after, in the same turn. */
    fun deny(key: String, feedback: String? = null) {
        backendScope.launch(worker) {
            val p = pendingAll[key] ?: return@launch
            val result: JsonElement = when (p.method) {
                "item/permissions/requestApproval" -> buildJsonObject { put("permissions", JsonObject(emptyMap())); put("scope", "turn") }
                "mcpServer/elicitation/request" -> buildJsonObject { put("action", "decline"); put("content", JsonNull); put("_meta", JsonNull) }
                "applyPatchApproval", "execCommandApproval" -> buildJsonObject { put("decision", "denied") }
                else -> buildJsonObject { put("decision", "decline") }
            }
            if (!answer(p, result)) return@launch
            val note = feedback?.trim().orEmpty()
            val tid = p.threadId ?: return@launch
            if (note.isNotBlank() && p.turnId != null) {
                client.rpc("turn/steer", buildJsonObject {
                    put("threadId", tid); put("expectedTurnId", p.turnId); put("input", JsonArray(listOf(textInput(note))))
                }).onFailure { _toasts.tryEmit("Codex did not get your note: ${it.message}") }
            }
        }
    }

    private suspend fun answer(p: CodexPending, result: JsonElement): Boolean {
        pendingAll.remove(p.key)
        emitNow()
        val r = client.reply(p.key, result)
        r.onFailure { _toasts.tryEmit("Could not answer the request: ${it.message}") }
        return r.isSuccess
    }

    fun approveTool(toolCall: ToolCall) = pendingForTool(toolCall.id) { approve(it) }
    fun rejectTool(toolCall: ToolCall, reason: String?) = pendingForTool(toolCall.id) { deny(it, reason) }

    private fun pendingForTool(toolCallId: String, action: (String) -> Unit) {
        backendScope.launch(worker) {
            pendingAll.values.firstOrNull { it.itemId != null && CodexTranscript.safeId(it.itemId) == toolCallId }?.let { action(it.key) }
        }
    }

    fun submitChoices(toolCall: ToolCall, responses: List<AskQuestionResponseItemDto>) {
        backendScope.launch(worker) {
            val p = pendingAll.values.firstOrNull { it.isQuestion && CodexTranscript.questionToolId(it.key) == toolCall.id } ?: return@launch
            val questions = p.params.a("questions").orEmpty().mapNotNull { it.obj() }
            val answers = buildJsonObject {
                for (r in responses) {
                    if (r.skipped == true) continue
                    val q = questions.firstOrNull { (it.str("question") ?: it.str("header")) == r.question } ?: continue
                    val qid = q.str("id") ?: continue
                    val list = r.selectedOptionIds.orEmpty() + listOfNotNull(r.writeInResponse?.trim()?.takeIf { it.isNotBlank() })
                    put(qid, buildJsonObject { put("answers", JsonArray(list.map { JsonPrimitive(it) })) })
                }
            }
            answer(p, buildJsonObject { put("answers", answers) })
        }
    }

    fun cancelChoices(toolCall: ToolCall) {
        backendScope.launch(worker) {
            val p = pendingAll.values.firstOrNull { it.isQuestion && CodexTranscript.questionToolId(it.key) == toolCall.id } ?: return@launch
            answer(p, buildJsonObject { put("answers", JsonObject(emptyMap())) })
        }
    }

    // ------------------------------------------------------------------ queue

    private suspend fun refreshQueue(threadId: String) {
        val res = client.rpc("thread/queue/list", buildJsonObject { put("threadId", threadId) }).getOrNull() ?: return
        if (attachedId != threadId) return
        _queuedMessages.value = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.mapNotNull { q ->
            val id = q.str("id") ?: return@mapNotNull null
            val parts = q.a("input").orEmpty().mapNotNull { it.obj() }
            QueuedMessage(
                id = id,
                text = parts.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() },
                attachmentCount = parts.count { it.str("type") != "text" }
            )
        }
    }

    /** "Send now": the queued message joins the running turn (Codex reads it at its next step) instead of waiting. */
    fun sendQueuedNow(id: String) {
        backendScope.launch(worker) {
            val tid = attachedId ?: return@launch
            val turnId = transcript.activeTurnId
            if (turnId == null) {
                client.rpc("thread/queue/start", buildJsonObject { put("threadId", tid); put("queuedSubmissionId", id) })
                return@launch
            }
            val item = client.rpc("thread/queue/list", buildJsonObject { put("threadId", tid) }).getOrNull()
                .obj().a("data").orEmpty().mapNotNull { it.obj() }.firstOrNull { it.str("id") == id } ?: return@launch
            client.rpc("thread/queue/delete", buildJsonObject { put("threadId", tid); put("queuedSubmissionId", id) })
            client.rpc("turn/steer", buildJsonObject {
                put("threadId", tid); put("expectedTurnId", turnId); put("input", item.a("input") ?: JsonArray(emptyList()))
                item.str("clientUserMessageId")?.let { put("clientUserMessageId", it) }
            }).onFailure {
                // the turn ended meanwhile: queue it again so it runs next
                client.rpc("thread/queue/add", buildJsonObject {
                    put("threadId", tid); put("input", item.a("input") ?: JsonArray(emptyList()))
                    put("clientUserMessageId", item.str("clientUserMessageId") ?: ("agm_" + UUID.randomUUID()))
                })
            }
            refreshQueue(tid)
        }
    }

    fun deleteQueued(id: String) {
        backendScope.launch(worker) {
            val tid = attachedId ?: return@launch
            client.rpc("thread/queue/delete", buildJsonObject { put("threadId", tid); put("queuedSubmissionId", id) })
                .onFailure { _toasts.tryEmit("Could not remove the message: ${it.message}") }
            refreshQueue(tid)
        }
    }

    // ------------------------------------------------------------------ events

    private suspend fun handleFrame(f: JsonObject) {
        when (f.str("type")) {
            "bridge_state" -> {
                val st = f.o("state")
                pendingAll.clear()
                st.a("pending").orEmpty().mapNotNull { it.obj() }.forEach { r ->
                    val key = r.str("key") ?: return@forEach
                    pendingAll[key] = pendingOf(key, r.str("method").orEmpty(), r.o("params") ?: JsonObject(emptyMap()))
                }
                val next = st.long("next_seq") ?: 0L
                val bufStart = st.long("buffer_start") ?: 0L
                when (f.str("event")) {
                    "attached" -> {
                        val since = lastSeq + 1
                        val missed = lastSeq >= 0 && (bufStart > since || next < since)
                        lastSeq = next - 1
                        if (missed) resync()
                        if (_state.value != CodexState.READY) backendScope.launch { refreshStatus() }
                    }
                    "exited" -> {
                        // every running turn ended with the process
                        running.clear()
                        publishConversations()
                        attachedId?.takeIf { it in created && transcript.isTurnActive }?.let { reload(it) }
                        f.str("error")?.takeIf { it.isNotBlank() }?.let { _toasts.tryEmit("Codex stopped: ${it.take(160)}") }
                    }
                }
                emitNow()
            }
            "bridge_line" -> {
                f.long("seq")?.let { if (it > lastSeq) lastSeq = it }
                val d = f.o("data") ?: return
                val method = d.str("method") ?: return
                val params = d.o("params") ?: JsonObject(emptyMap())
                val key = f.str("key")
                if (d.containsKey("id") && key != null) {
                    val p = pendingOf(key, method, params)
                    if (skipQuestions && p.isQuestion && p.threadId == attachedId) {
                        client.reply(key, buildJsonObject { put("answers", JsonObject(emptyMap())) })
                        return
                    }
                    pendingAll[key] = p
                    if (p.threadId == attachedId) emitNow()
                } else {
                    handleNotification(method, params, key)
                }
            }
        }
    }

    private fun pendingOf(key: String, method: String, params: JsonObject) = CodexPending(
        key = key, method = method, params = params,
        threadId = params.str("threadId") ?: params.str("conversationId"),
        turnId = params.str("turnId"),
        itemId = params.str("itemId") ?: params.str("callId")
    )

    private suspend fun handleNotification(method: String, p: JsonObject, key: String?) {
        val tid = p.str("threadId") ?: p.o("thread").str("id")
        // a hidden thread that only makes a title: its reply is the title, nothing of it shows anywhere
        if (tid != null && titleRuns.containsKey(tid)) {
            if (method == "item/completed" && p.o("item").str("type") == "agentMessage") p.o("item").str("text")?.let { titleAnswers[tid] = it }
            if (method == "turn/completed") titleRuns[tid]?.complete(titleAnswers.remove(tid))
            return
        }
        when (method) {
            "serverRequest/resolved" -> if (key != null && pendingAll.remove(key) != null && tid == attachedId) emitNow()
            "thread/status/changed" -> if (tid != null) {
                val active = p.o("status").str("type") == "active"
                if (if (active) running.add(tid) else running.remove(tid)) publishConversations()
                if (!active && tid == attachedId && transcript.isTurnActive && p.o("status").str("type") == "idle") {
                    transcript.settleAll()
                    emitSoon()
                }
            }
            "thread/started", "thread/archived", "thread/unarchived", "thread/deleted" -> scheduleRefresh()
            "thread/name/updated" -> if (tid != null) p.str("threadName")?.let { applyTitle(tid, it) }
            "account/rateLimits/updated" -> limitsOf(p.o("rateLimits"))?.let { _rateLimits.value = it }
            "account/updated" -> backendScope.launch { refreshAccount() }
            "account/login/completed" -> {
                if (_login.value?.loginId == p.str("loginId")) _login.value = null
                if (p.str("success") == "true") {
                    _toasts.tryEmit("Signed in to Codex")
                    backendScope.launch { refreshAccount(); refreshModels(); refreshThreads() }
                } else p.str("error")?.takeIf { it != "Login was not completed" }?.let { _toasts.tryEmit("Codex sign-in failed: $it") }
            }
            "thread/queue/changed" -> if (tid != null && tid == attachedId) refreshQueue(tid)
            "model/rerouted" -> if (tid == attachedId) _toasts.tryEmit("Codex switched from ${p.str("fromModel")} to ${p.str("toModel")}")
        }
        if (tid != null && tid != attachedId) {
            // a reply running in a chat that is not on screen: keep its live copy current
            if (method == "turn/started" && tid in sentTo && shadows.size < MAX_SHADOWS) shadows.getOrPut(tid) { CodexTranscript(tid) }
            shadows[tid]?.let { sh ->
                sh.apply(method, p)
                val idle = method == "thread/status/changed" && p.o("status").str("type") != "active"
                if (method == "turn/completed" || idle) shadows.remove(tid)
            }
            return
        }
        if (tid == null) return
        when (method) {
            "turn/started" -> { turnStartedAt = System.nanoTime(); _notice.value = null }
            "error" -> if (p.str("willRetry") == "true") _notice.value = "Reconnecting… ${p.o("error").str("message").orEmpty().take(140)}"
        }
        val changed = transcript.apply(method, p)
        if (method == "turn/completed") {
            _notice.value = null
            emitNow()
            requestTitleOnce(tid)
            onTurnFinished()
            // the plan's usage after each reply (like Claude's and Antigravity's)
            refreshRateLimitsSoon()
            // the stored chat is the reference (final items): load it again once the turn is over
            val id = tid
            backendScope.launch(worker) {
                delay(500)
                if (attachedId == id && !transcript.isTurnActive) reload(id)
                scheduleRefresh()
            }
            return
        }
        if (changed) emitSoon()
    }

    /** Every model Codex offers, hidden ones included (the title model can be hidden). */
    @Volatile private var allModelIds: Set<String> = emptySet()
    /** Hidden title threads: their reply ends here. */
    private val titleRuns = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()
    private val titleAnswers = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Chats already asked for a title in this run of the app. */
    private val titleAsked = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Codex's app-server never names a chat: its own terminal app does, after the first message, by running one turn in
     * a hidden read-only thread (its small title model at low effort when the plan has it) and saving the answer with
     * `thread/name/set`. The same here, once, after the first reply of a new chat. The hidden thread gets no chat
     * history: only the title instructions and the first message (plus the tool text every Codex thread carries).
     */
    private fun requestTitleOnce(threadId: String) {
        if (threadId !in createdSnapshot()) return
        val prompts = transcript.prompts()
        if (prompts.size != 1 || !titleAsked.add(threadId)) return
        val raw = prompts.first()
        val typed = com.example.gemini.data.remote.PromptExpansion.cleanTitle(raw).trim()
        val files = com.example.gemini.data.files.AttachedFiles.parse(raw).map { it.path.substringAfterLast('/') }
        val subject = listOfNotNull(
            typed.takeIf { it.isNotBlank() },
            files.takeIf { it.isNotEmpty() }?.let { "Attached files: " + it.joinToString(", ") }
        ).joinToString("\n")
        if (subject.isBlank()) return
        val model = if (TITLE_MODEL in allModelIds) TITLE_MODEL else _selectedModelId.value
        backendScope.launch {
            // like Codex's own app: no MCP servers for the hidden thread (nothing to start, nothing it could call)
            val mcp = mcpServerNames().getOrDefault(emptySet())
            val temp = client.rpc("thread/start", buildJsonObject {
                if (model.isNotBlank()) put("model", model)
                put("sandbox", "read-only")
                put("approvalPolicy", "never")
                put("ephemeral", true)
                // as small as app-server allows (measured ~8.3k input tokens instead of ~12k): no environments, extra
                // tools or workspace roots (like Codex's own app), and a one-line base prompt instead of the coding one
                put("environments", JsonArray(emptyList()))
                put("dynamicTools", JsonArray(emptyList()))
                put("runtimeWorkspaceRoots", JsonArray(emptyList()))
                put("selectedCapabilityRoots", JsonArray(emptyList()))
                put("baseInstructions", "You write short titles for chats. Follow the user's instructions exactly.")
                if (mcp.isNotEmpty()) put("config", buildJsonObject {
                    put("mcp_servers", buildJsonObject { mcp.forEach { name -> put(name, buildJsonObject { put("enabled", false) }) } })
                })
            }).getOrNull().obj().o("thread").str("id") ?: return@launch
            val done = kotlinx.coroutines.CompletableDeferred<String?>()
            titleRuns[temp] = done
            try {
                val prompt = titlePrompt(subject)
                val started = client.rpc("turn/start", buildJsonObject {
                    put("threadId", temp)
                    put("input", JsonArray(listOf(textInput(prompt))))
                    put("outputSchema", TITLE_SCHEMA)
                    if (model == TITLE_MODEL) put("effort", "low")
                })
                if (started.isFailure) return@launch
                val answer = kotlinx.coroutines.withTimeoutOrNull(90_000) { done.await() } ?: return@launch
                val title = runCatching { CodexJson.parseToJsonElement(answer).obj().str("title") }.getOrNull()
                    ?.let { com.example.gemini.data.remote.PromptExpansion.cleanTitle(it).trim().take(60) }
                    ?.takeIf { it.isNotBlank() } ?: return@launch
                client.rpc("thread/name/set", buildJsonObject { put("threadId", threadId); put("name", title) })
                    .onSuccess { backendScope.launch(worker) { applyTitle(threadId, title) } }
            } finally {
                titleRuns.remove(temp)
                titleAnswers.remove(temp)
                client.rpc("thread/unsubscribe", buildJsonObject { put("threadId", temp) })
            }
        }
    }

    /** Codex's own title instructions (its terminal app), at most 960 bytes in all. */
    private fun titlePrompt(subject: String): String {
        val prefix = "Generate a concise, single-line task title of at most 36 characters and under five words where possible. " +
            "Start with an imperative verb. Capitalize only the first word unless the user's language, proper nouns, acronyms, " +
            "or code terms require otherwise. Preserve ticket references exactly. Write in the user's language. " +
            "Do not use quotes, markdown, or trailing punctuation. Do not answer the request.\n\nUser prompt:\n"
        val room = 960 - prefix.toByteArray().size
        val sb = StringBuilder()
        var used = 0
        for (ch in subject.trim()) {
            val n = ch.toString().toByteArray().size
            if (used + n > room) break
            sb.append(ch)
            used += n
        }
        return prefix + sb
    }

    private fun applyTitle(id: String, title: String) {
        lastServerList = lastServerList.map { if (it.id == id) it.copy(title = title) else it }
        localConversations[id]?.let { localConversations[id] = it.copy(title = title) }
        publishConversations()
        store.currentConversation.value?.takeIf { it.id == id }?.let { store.currentConversation.value = it.copy(title = title) }
    }

    /** Everything that may have been missed while the event stream was down (on [worker]). */
    private suspend fun resync() {
        scheduleRefresh()
        attachedId?.takeIf { it in createdSnapshot() }?.let { reload(it) }
    }

    private fun scheduleRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = backendScope.launch {
            delay(700)
            refreshThreads()
        }
    }

    // streaming deltas arrive many times a second: the UI is updated at most every 80 ms
    private fun emitSoon() {
        if (emitJob?.isActive == true) return
        emitJob = backendScope.launch(worker) {
            delay(80)
            emitNow()
        }
    }

    private fun emitNow() {
        val id = attachedId ?: return
        // a chat opened before the one now wanted: nothing of it goes on screen
        if (wantedId != null && wantedId != id) return
        if (loadingHistory) return
        val mine = pendingAll.values.filter { it.threadId == id }
        val awaiting = mine.filter { !it.isQuestion }.mapNotNull { it.itemId }.toSet()
        val question = mine.firstOrNull { it.isQuestion }?.let { q ->
            CodexTranscript.Question(q.key, q.turnId, q.params.a("questions").orEmpty().mapNotNull { it.obj() })
        }
        _messages.value = transcript.toChatMessages(id, awaiting, question)
        _pendingApprovals.value = mine.filter { !it.isQuestion }
        _isStreaming.value = transcript.isTurnActive
        _lastPlan.value = if (transcript.isTurnActive) null else transcript.lastPlan
    }

    /** Leaves plan mode and asks Codex to carry out the plan it proposed. */
    fun implementPlan() {
        setPlanMode(false)
        _lastPlan.value = null
        sendMessage("Implement the plan.")
    }

    // ------------------------------------------------------------------ helpers

    private fun textInput(text: String) = buildJsonObject {
        put("type", "text"); put("text", text); put("text_elements", JsonArray(emptyList()))
    }

    private fun defaultWorkspaceUri(): String {
        val path = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path.orEmpty()
        return if (path.isBlank()) "" else if (path.startsWith("file://")) path else "file://$path"
    }

    private fun workspacePath(conv: Conversation): String? = conv.workspaceUri.removePrefix("file://").takeIf { it.startsWith("/") }

    companion object {
        private const val TAG = "CodexBackend"
        /** Live copies kept of chats replying while not on screen. */
        private const val MAX_SHADOWS = 12

        /** The small model Codex's own app names chats with (when the plan offers it; else the chat's model). */
        private const val TITLE_MODEL = "gpt-5.6-luna"
        /** One title of at most 36 characters, as Codex's own app asks for it. */
        private val TITLE_SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 36) })
            })
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("title"))))
            put("additionalProperties", false)
        }

        /** Codex's own commands in the "/" menu of a Codex chat. */
        val BUILTIN_COMMANDS = listOf(
            com.example.gemini.data.remote.SlashCommandItem("compact", "/compact", "Summarize the chat so far to free up context", "command"),
            com.example.gemini.data.remote.SlashCommandItem("review", "/review", "Review the uncommitted changes (or: /review what to check)", "command"),
            com.example.gemini.data.remote.SlashCommandItem("plan", "/plan", "Plan mode: plan before changing anything (or: /plan the task)", "command"),
            com.example.gemini.data.remote.SlashCommandItem("fork", "/fork", "Copy this chat into a new one", "command"),
            com.example.gemini.data.remote.SlashCommandItem("model", "/model", "Pick the model", "app"),
            com.example.gemini.data.remote.SlashCommandItem("status", "/status", "Account, usage limits and settings", "app"),
            com.example.gemini.data.remote.SlashCommandItem("mcp", "/mcp", "MCP servers Codex can use", "app"),
            com.example.gemini.data.remote.SlashCommandItem("new", "/new", "Start a new Codex chat", "app")
        )
    }
}
