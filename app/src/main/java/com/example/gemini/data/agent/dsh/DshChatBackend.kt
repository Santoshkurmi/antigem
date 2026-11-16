package com.example.gemini.data.agent.dsh

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.isBridgeUnreachable
import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.bool
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Whether DeepSeek Harness can be used, as the chat screen and the settings show it. */
enum class DshState(val label: String) {
    CHECKING("Checking…"),
    READY("Ready"),
    NO_KEY("API key needed"),
    NOT_INSTALLED("Not installed"),
    DISABLED("Turned off on the server"),
    OUTDATED("Server update needed"),
    OFFLINE("Server offline"),
    ERROR("Error");

    /** Sending a message cannot work in this state. */
    val isBlocking get() = this == NOT_INSTALLED || this == DISABLED || this == OUTDATED || this == OFFLINE || this == NO_KEY
}

/** A request dsh is waiting on: an approval or a question (answered through the bridge by [key]). */
data class DshPending(val key: String, val method: String, val params: JsonObject, val sessionId: String?) {
    val isQuestion get() = method == "user-question/ask"
    /** The tool call an approval is about, when dsh named it. */
    val callId: String? get() = params.str("callId")
}

/** How full the open chat's context window is: the newest request's prompt size, and the route's window when known. */
data class DshContextUsage(val usedTokens: Long, val contextWindow: Long?)

/**
 * DeepSeek Harness chat backend. The bridge runs one `dsh --profile sdk` for every dsh chat (session); this class
 * lists sessions and reads their logs through the bridge (no dsh needed for that), follows its event stream (one
 * WebSocket for all chats, replayed after a reconnect), and sends prompts and commands, stops turns, switches a
 * chat's model, and answers approvals and questions. [DshTranscript] turns the open chat into the shared chat UI's
 * messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: DshPreferences,
    val client: DshBridgeClient = DshBridgeClient()
) : AgentChatBackend {

    override val kind = AgentKind.DSH

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

    private val _state = MutableStateFlow(DshState.CHECKING)
    val state: StateFlow<DshState> = _state.asStateFlow()
    private val _status = MutableStateFlow<DshStatus?>(null)
    /** The bridge's last status answer (version, Node, running, presets, error). */
    val status: StateFlow<DshStatus?> = _status.asStateFlow()
    private val _config = MutableStateFlow<DshConfig?>(null)
    /** Provider, default model, keys set, MCP servers, permission (Settings → DeepSeek Harness). */
    val config: StateFlow<DshConfig?> = _config.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    /** Models of every provider with a key; ids are "provider/model". */
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()
    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private val _selectedModelId = MutableStateFlow(prefs.model.value)
    /** "provider/model" of the chat on screen (blank: the bridge's default). */
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    private val _effort = MutableStateFlow(prefs.effort.value)
    /** Reasoning effort of the chat on screen ("" = the model's default). */
    val effort: StateFlow<String> = _effort.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<DshPending>>(emptyList())
    /** Approvals the chat on screen (or one of its subagents) waits on; questions are shown in the chat instead. */
    val pendingApprovals: StateFlow<List<DshPending>> = _pendingApprovals.asStateFlow()

    private val _planMode = MutableStateFlow(false)
    /** Plan mode of the chat on screen (dsh plans before changing anything). */
    val planMode: StateFlow<Boolean> = _planMode.asStateFlow()

    private val _contextUsage = MutableStateFlow<DshContextUsage?>(null)
    val contextUsage: StateFlow<DshContextUsage?> = _contextUsage.asStateFlow()

    private val _slashCommands = MutableStateFlow(BUILTIN_COMMANDS)
    /** dsh's commands and the skills in its skills folder, for the "/" menu of a dsh chat. */
    val slashCommands: StateFlow<List<com.example.gemini.data.remote.SlashCommandItem>> = _slashCommands.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Retry notice of the chat on screen. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _cliJob = MutableStateFlow<DshCliJob?>(null)
    /** The last install / update / remove of dsh started from the app. */
    val cliJob: StateFlow<DshCliJob?> = _cliJob.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    /** Called after each finished turn of the chat on screen. */
    var onTurnFinished: () -> Unit = {}

    // ---- below: only touched on [worker]
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private var transcript = DshTranscript()
    /** Live copies of chats that ran while not on screen (their log on disk can lag a little behind). */
    private val shadows = LinkedHashMap<String, DshTranscript>()
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
    /** Sessions that exist in dsh (a new chat exists only in the app until its first message). */
    private val created = HashSet<String>()
    private val running = HashSet<String>()
    private val localConversations = LinkedHashMap<String, Conversation>()
    private val pendingAll = LinkedHashMap<String, DshPending>()
    private var lastServerList: List<Conversation> = emptyList()
    /** The bridge's session list entries by id (context pressure, title source). */
    @Volatile private var sessionInfo: Map<String, JsonObject> = emptyMap()
    /** Last bridge event sequence number seen (-1: none yet). */
    private var lastSeq = -1L
    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var emitJob: Job? = null
    private var refreshJob: Job? = null
    private var skillsJob: Job? = null

    // ------------------------------------------------------------------ lifecycle

    /** DeepSeek Harness is enabled: check the server, load settings and chats, follow the event stream. */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = backendScope.launch {
            refreshStatus()
            refreshConfig()
            if (_status.value?.running == true) refreshModels()
            refreshSkills()
            while (isActive) {
                refreshSessions()
                delay(if (_state.value == DshState.READY) 60_000 else 15_000)
                if (_state.value != DshState.READY) refreshStatus()
            }
        }
        eventsJob = backendScope.launch(worker) {
            var backoff = 1_000L
            while (isActive) {
                var off = false
                client.events(if (lastSeq >= 0) lastSeq + 1 else -1).collect { ev ->
                    when (ev) {
                        is DshSocketEvent.Opened -> backoff = 1_000L
                        is DshSocketEvent.Frame -> handleFrame(ev.frame)
                        is DshSocketEvent.Closed -> {
                            // live partial text is never replayed: what was shown of it goes
                            dropLive()
                            if (ev.disabled) { _state.value = DshState.DISABLED; off = true }
                            if (ev.outdated) { _state.value = DshState.OUTDATED; off = true }
                            if (!off && _state.value == DshState.READY) {
                                // the stream dropped (bridge stopped?): READY is never re-checked otherwise
                                backendScope.launch(worker) { if (_state.value == DshState.READY) refreshStatus() }
                            }
                        }
                    }
                }
                delay(if (off) 60_000 else backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    /** DeepSeek Harness was turned off: stop everything and forget its chats. */
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
        _notice.value = null
        _contextUsage.value = null
        backendScope.launch(worker) {
            switchTo(null)
            _messages.value = emptyList()
        }
    }

    override fun retryConnections() {
        backendScope.launch {
            refreshStatus(showProgress = true)
            refreshConfig()
            if (_status.value?.running == true) refreshModels()
            refreshSessions()
        }
    }

    override fun onAppForegrounded() {
        backendScope.launch {
            if (_state.value != DshState.READY) refreshStatus()
            refreshSessions()
            backendScope.launch(worker) { attachedId?.takeIf { it in created }?.let { reload(it) } }
        }
    }

    /**
     * Asks the bridge whether dsh is installed and has its API key. An unreachable bridge right after the app starts
     * keeps "Checking…" and is asked again every 2 s (the local server is still coming up).
     */
    suspend fun refreshStatus(showProgress: Boolean = false) {
        if (showProgress && _state.value != DshState.READY) _state.value = DshState.CHECKING
        var result = client.status()
        while (result.exceptionOrNull()?.isBridgeUnreachable() == true &&
            com.example.gemini.data.agent.claude.BridgeStartup.isStartingUp()
        ) {
            if (_state.value != DshState.READY) _state.value = DshState.CHECKING
            delay(2_000)
            result = client.status()
        }
        if (result.isSuccess) com.example.gemini.data.agent.claude.BridgeStartup.markReached()
        result
            .onSuccess { st ->
                _status.value = st
                _state.value = when {
                    !st.installed -> DshState.NOT_INSTALLED
                    st.has_key == false -> DshState.NO_KEY
                    !st.error.isNullOrBlank() && !st.running && !st.starting -> DshState.ERROR
                    else -> DshState.READY
                }
                st.statuses?.let { s -> backendScope.launch(worker) { applyStatuses(s) } }
                st.job?.let { if (_cliJob.value == null || it.running) _cliJob.value = it }
            }
            .onFailure { e ->
                _state.value = when {
                    e is DshUnavailableException && e.disabled -> DshState.DISABLED
                    e is DshUnavailableException && e.outdated -> DshState.OUTDATED
                    e is java.io.IOException -> DshState.OFFLINE
                    else -> DshState.ERROR
                }
                _status.value = DshStatus(error = e.message)
            }
    }

    suspend fun refreshConfig() {
        client.config().onSuccess { _config.value = it }
    }

    /**
     * Saves settings on the bridge (any subset of provider, model, reasoning_effort, max_tokens, permission, keys,
     * mcp). The result says whether the running dsh needs a restart to use them.
     */
    suspend fun saveConfig(body: JsonObject): Result<DshConfigSaved> {
        val r = client.saveConfig(body)
        r.onSuccess { saved ->
            saved.config?.let { _config.value = it }
            refreshStatus()
        }
        return r
    }

    /** Installs ("install"), updates ("update") or removes ("remove") dsh on the server and follows the job's log. */
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
                            _toasts.tryEmit(
                                if (ok) when (action) { "install" -> "DeepSeek Harness installed"; "remove" -> "DeepSeek Harness removed"; else -> "DeepSeek Harness is up to date" }
                                else "DeepSeek Harness $action failed"
                            )
                            refreshStatus()
                            if (ok) {
                                refreshConfig()
                                refreshSessions()
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

    /** Starts ("start"), restarts ("restart") or stops ("stop") the dsh process; [onDone] gets the error, if any. */
    fun controlServer(action: String, onDone: (String?) -> Unit = {}) {
        backendScope.launch {
            val r = client.control(action)
            refreshStatus()
            if (action != "stop" && r.isSuccess) refreshModels()
            onDone(r.exceptionOrNull()?.message)
        }
    }

    // ------------------------------------------------------------------ models, sessions

    /** The bridge's default model as "provider/model" (blank until the status is known). */
    fun defaultModelId(): String {
        val st = _status.value
        val cfg = _config.value
        return DshPreferences.join(st?.provider ?: cfg?.provider, st?.model ?: cfg?.model)
    }

    /** The model of a new chat: the one remembered, else the bridge's default. */
    private fun newChatModel(): String = prefs.model.value.ifBlank { defaultModelId() }

    /** Loads the model list (it needs dsh running: [start] starts it first, which can take half a minute). */
    fun refreshModels(start: Boolean = false) {
        backendScope.launch {
            _isRefreshingModels.value = true
            try {
                client.models(start)
                    .onSuccess { raw ->
                        val def = defaultModelId()
                        val list = raw.mapNotNull { m ->
                            val id = m.str("id") ?: return@mapNotNull null
                            val provider = m.str("provider").orEmpty()
                            val full = DshPreferences.join(provider, id)
                            AiModel(
                                id = full,
                                displayName = m.str("name")?.ifBlank { null } ?: id,
                                family = ModelFamily.OTHER,
                                supportsThinking = true,
                                description = m.str("description").orEmpty(),
                                isDefault = full == def,
                                key = full,
                                baseName = m.str("providerName")?.ifBlank { null } ?: provider,
                                tier = m.str("badge")
                            )
                        }
                        val failed = raw.filter { it.str("id") == null && it.str("error") != null }
                            .map { "${it.str("providerName") ?: it.str("provider")}: ${it.str("error")}" }
                        _availableModels.value = list
                        _modelsError.value = if (list.isEmpty()) failed.firstOrNull() ?: "No models: add an API key in Settings → DeepSeek Harness." else null
                        if (_selectedModelId.value.isBlank()) _selectedModelId.value = newChatModel()
                    }
                    .onFailure { e ->
                        _modelsError.value = if (_status.value?.running != true && !start) "DeepSeek Harness is not running: start it to load its models." else e.message
                    }
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    private fun createdSnapshot(): Set<String> = synchronized(created) { created.toSet() }

    suspend fun refreshSessions() {
        val raw = client.sessions().getOrElse { return }
        val list = raw.mapNotNull { conversationOf(it) }
        val active = raw.filter { it.bool("running") == true }.mapNotNull { it.str("id") }.toSet()
        val listed = list.map { it.id }.toSet()
        sessionInfo = raw.mapNotNull { s -> s.str("id")?.let { it to s } }.toMap()
        backendScope.launch(worker) {
            synchronized(created) { created.addAll(listed) }
            running.removeAll { it in listed && it !in active }
            running.addAll(active)
            localConversations.keys.removeAll(listed)
            publishConversations(list)
            updateContextUsage()
        }
    }

    private fun publishConversations(serverList: List<Conversation>? = null) {
        if (serverList != null) lastServerList = serverList
        // a new chat is local until dsh lists it: it is running too while its first reply runs
        _conversations.value = (localConversations.values.map { c -> c.copy(isRunning = c.id in running) } + lastServerList.map { c -> c.copy(isRunning = c.id in running) })
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
    }

    private fun conversationOf(s: JsonObject): Conversation? {
        val id = s.str("id") ?: return null
        val cwd = s.str("cwd").orEmpty()
        val subagent = s.str("origin") == "subagent"
        val parent = s.str("parent_session")?.takeIf { it.isNotBlank() }
        val title = s.str("title")?.let { com.example.gemini.data.remote.PromptExpansion.cleanTitle(it) }?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            ?.let { if (it.length > 80) it.take(80) + "…" else it }
            ?: if (subagent) "Subagent" else "Untitled chat"
        val updated = s.long("updated_at") ?: 0L
        return Conversation(
            id = id,
            title = title,
            modelId = DshPreferences.join(s.str("provider"), s.str("model")),
            sessionId = id,
            createdAt = s.long("created_at")?.takeIf { it > 0 } ?: updated,
            updatedAt = updated,
            isRunning = s.bool("running") == true,
            workspaceUri = if (cwd.isBlank()) "" else "file://$cwd",
            parentConversationId = parent,
            subagentRole = if (subagent) "subagent" else null,
            nestingDepth = (s.long("delegation_depth") ?: 0L).toInt(),
            agent = AgentKind.DSH,
            archived = s.bool("archived") == true,
            // a subagent's chat is driven by its parent: read only here
            readOnly = subagent
        )
    }

    /** The open chat's context window use, from the bridge's session list (on [worker]). */
    private fun updateContextUsage() {
        val p = attachedId?.let { sessionInfo[it] }.o("context_pressure")
        val used = p.long("pressureTokens") ?: p.long("projectedTokens")
        _contextUsage.value = used?.let { DshContextUsage(it, p.long("contextWindow")) }
    }

    // ------------------------------------------------------------------ AgentChatBackend

    override fun startNewChat() {
        val id = "session-" + UUID.randomUUID()
        _selectedModelId.value = newChatModel()
        _effort.value = prefs.effort.value
        store.currentConversation.value = Conversation(
            id = id, title = "New Chat", modelId = _selectedModelId.value, sessionId = id,
            workspaceUri = defaultWorkspaceUri(), agent = AgentKind.DSH
        )
        _planMode.value = false
        wantedId = id
        _conversationError.value = null
        _isLoadingConversation.value = false
        _isStreaming.value = false
        _pendingApprovals.value = emptyList()
        _notice.value = null
        _contextUsage.value = null
        backendScope.launch(worker) {
            switchTo(id)
            emitNow()
        }
    }

    /** The folder of the fresh chat (nothing sent yet). */
    fun setWorkspaceOfNewChat(path: String) {
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.DSH || conv.id in createdSnapshot() || path.isBlank()) return
        store.currentConversation.value = conv.copy(workspaceUri = if (path.startsWith("file://")) path else "file://$path")
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id }
            ?: Conversation(id = id, title = "DeepSeek Harness chat", sessionId = id, agent = AgentKind.DSH)
        store.currentConversation.value = conv
        if (conv.modelId.isNotBlank()) _selectedModelId.value = conv.modelId
        _planMode.value = false
        wantedId = id
        _conversationError.value = null
        _isLoadingConversation.value = true
        _pendingApprovals.value = emptyList()
        _notice.value = null
        _messages.value = emptyList()
        backendScope.launch(worker) {
            // another chat was opened meanwhile: this one's load is no longer wanted
            if (wantedId != id) return@launch
            // a chat that ran while not on screen comes back with what was seen live meanwhile
            switchTo(id)
            updateContextUsage()
            if (id in createdSnapshot() || localConversations[id] == null) {
                synchronized(created) { created.add(id) }
                loadingHistory = true
                try {
                    reload(id, restoreChoices = true)
                } finally {
                    loadingHistory = false
                }
            }
            emitNow()
            if (wantedId == id) _isLoadingConversation.value = false
        }
    }

    /** Loads the chat's saved log (no dsh needed). On [worker]. */
    private suspend fun reload(id: String, restoreChoices: Boolean = false) {
        val events = client.transcript(id).getOrElse { e ->
            if (attachedId == id) _conversationError.value = "Could not load this chat: ${e.message}"
            return
        }
        if (attachedId != id || wantedId != id) return
        transcript.loadHistory(events, active = id in running)
        if (restoreChoices) {
            // the picker shows what the chat last ran on
            transcript.lastModel()?.let { _selectedModelId.value = it }
            _effort.value = transcript.lastEffort().orEmpty()
        }
        transcript.planMode()?.let { _planMode.value = it }
        publishConversations()
        emitNow()
    }

    override fun sendMessage(content: String) {
        val atts = store.attachments.value
        if (content.isBlank() && atts.isEmpty()) return
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.DSH || conv.readOnly) return
        if (_state.value.isBlocking) {
            _toasts.tryEmit("DeepSeek Harness is not available: ${_state.value.label}. Check Settings → DeepSeek Harness.")
            return
        }
        store.attachments.value = emptyList()
        // "/command …" and "/skill …" go as typed (without the app's hidden notes): dsh reads them only at the start
        // of the message's only text block
        val collapsed = com.example.gemini.data.remote.PromptExpansion.collapse(content).trim()
        val slash = collapsed.startsWith("/")
        val text = if (slash) collapsed else content.trim()
        val title = if (conv.title == "New Chat" || conv.title.isBlank()) {
            val first = collapsed.ifBlank { "Attachment" }.lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            first.take(40) + if (first.length > 40) "..." else ""
        } else conv.title
        val updated = conv.copy(title = title, updatedAt = System.currentTimeMillis(), modelId = _selectedModelId.value)
        store.currentConversation.value = updated
        _conversationError.value = null
        val clientId = "agm_" + UUID.randomUUID()
        val command = slash && isNativeCommand(collapsed)
        backendScope.launch(worker) {
            if (attachedId != conv.id) switchTo(conv.id)
            val busy = transcript.isTurnActive
            val notes = mutableListOf<String>()
            val blocks = mutableListOf<JsonObject>()
            for (a in atts) attachmentInput(a, workspacePath(updated).orEmpty(), blocks, notes)
            val promptText = (listOf(text) + notes).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "See the attached image." }
            // images show as previews, other files sent by the app by name; files by path come from the text's list
            val images = atts.filter { it.isImage && !it.base64.isNullOrBlank() }
            if (!command) {
                transcript.addLocalPrompt(clientId, promptText, atts.filter { !it.reference && it !in images }.map { it.name }, images)
                _isStreaming.value = true
                emitNow()
            }
            if (!prepareSession(updated)) {
                if (!command) fail(clientId, "Could not start the DeepSeek Harness chat", null)
                return@launch
            }
            sentTo.add(conv.id)
            // project checkpoint (when on): the files right before dsh gets the message (a queued one: marked mid-turn)
            val checkpoint = when {
                command -> null
                busy -> {
                    com.example.gemini.data.checkpoint.ProjectCheckpoints.beforeTurnInBackground(AgentKind.DSH, conv.id, updated.workspaceUri, clientId, text)
                    null
                }
                else -> com.example.gemini.data.checkpoint.ProjectCheckpoints.beforeTurn(AgentKind.DSH, conv.id, updated.workspaceUri, clientId, text)
            }
            client.rpc("session/query", buildJsonObject {
                put("sessionId", conv.id)
                put("contentBlocks", JsonArray(listOf(textBlock(promptText)) + blocks))
            }).onSuccess { res -> onQueryAnswer(conv.id, updated.workspaceUri, clientId, res.obj(), checkpoint) }
                .onFailure { fail(clientId, "DeepSeek Harness did not take the message", it) }
        }
    }

    /** dsh took a query: a prompt (its message id), or a command it ran. On [worker]. */
    private fun onQueryAnswer(
        sessionId: String, workspaceUri: String, clientId: String, res: JsonObject?,
        checkpoint: com.example.gemini.data.checkpoint.CheckpointSummary?
    ) {
        synchronized(created) { created.add(sessionId) }
        if (res.str("kind") == "command") {
            // a command (or a skill dsh ran as one) has no user message of its own
            transcript.removeLocalPrompt(clientId)
            val ex = res.o("execution")
            (ex.str("text") ?: ex.str("message"))?.takeIf { it.isNotBlank() }?.let { _toasts.tryEmit(it.take(200)) }
        } else {
            running.add(sessionId)
            res.str("messageId")?.let { mid ->
                transcript.bindLocalPrompt(clientId, mid)
                if (checkpoint != null) backendScope.launch { com.example.gemini.data.checkpoint.ProjectCheckpoints.bind(workspaceUri, checkpoint, mid) }
            }
        }
        publishConversations()
        emitNow()
        scheduleRefresh()
    }

    /**
     * A chat's first message: dsh creates the session with it, so the chat's folder and model are set right before
     * (both create the session too). False when that failed. On [worker].
     */
    private suspend fun prepareSession(conv: Conversation): Boolean {
        if (conv.id in createdSnapshot()) return true
        localConversations[conv.id] = conv
        publishConversations()
        workspacePath(conv)?.let { path ->
            val r = client.rpc("session/working-directory/set", buildJsonObject { put("sessionId", conv.id); put("path", path) })
            if (r.isFailure) {
                _toasts.tryEmit("Could not use the project folder: ${r.exceptionOrNull()?.message}")
                if ((r.exceptionOrNull() as? DshRpcException)?.unavailable == true) return false
            }
        }
        applyModelTo(conv.id, onlyIfChanged = true)
        return true
    }

    /** Sends a dsh command ("/plan", "/compact"…) as the chat's only text block, without a bubble of its own. */
    fun sendCommand(command: String) {
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.DSH && !it.readOnly } ?: return
        if (_state.value.isBlocking) {
            _toasts.tryEmit("DeepSeek Harness is not available: ${_state.value.label}.")
            return
        }
        backendScope.launch(worker) {
            if (attachedId != conv.id) switchTo(conv.id)
            if (!prepareSession(conv)) return@launch
            client.rpc("session/query", buildJsonObject {
                put("sessionId", conv.id)
                put("contentBlocks", JsonArray(listOf(textBlock(command))))
            }).onSuccess { res -> onQueryAnswer(conv.id, conv.workspaceUri, "", res.obj(), null) }
                .onFailure { _toasts.tryEmit("Could not run $command: ${it.message}") }
        }
    }

    /**
     * Makes [id] the chat on screen (null: none). The chat left keeps a live copy when its reply is still running; the
     * chat opened takes over its live copy, if it has one (on [worker]).
     */
    private fun switchTo(id: String?) {
        val left = attachedId
        if (left != null && left != id && transcript.isTurnActive && left in created) {
            shadows.remove(left)
            shadows[left] = transcript
            trimShadows()
        }
        transcript = id?.let { shadows.remove(it) } ?: DshTranscript(id.orEmpty())
        transcript.sessionId = id.orEmpty()
        transcript.running = id != null && id in running
        attachedId = id
    }

    private fun trimShadows() {
        while (shadows.size > MAX_SHADOWS) shadows.remove(shadows.keys.first())
    }

    private suspend fun attachmentInput(a: ChatAttachment, project: String, blocks: MutableList<JsonObject>, notes: MutableList<String>) {
        val b64 = a.base64
        // a file by path is already listed in the message's text; only an image's shrunk copy is sent itself
        if (a.reference && b64.isNullOrBlank()) return
        if (b64.isNullOrBlank()) {
            if (a.path.startsWith("/")) notes.add("Attached file: ${a.path}")
            return
        }
        val mime = a.mimeType.orEmpty().lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
        if ((a.isImage || mime.startsWith("image/")) && mime in IMAGE_TYPES) {
            blocks.add(buildJsonObject { put("type", "image"); put("data", b64); put("mimeType", mime); put("name", a.name) })
            return
        }
        // other files are saved on the server, where dsh can open them
        client.upload(a.name, b64, project)
            .onSuccess { path -> notes.add("Attached file `${a.name}` (saved at $path)") }
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

    override fun stopStreaming() {
        val id = store.currentConversation.value?.takeIf { it.agent == AgentKind.DSH }?.id ?: return
        backendScope.launch(worker) {
            if (id !in createdSnapshot()) return@launch
            client.cancel(id).onFailure { e ->
                _toasts.tryEmit("Could not stop: ${e.message}")
                // dsh is gone (not running): nothing can still be running
                if (e.message.orEmpty().contains("not running", ignoreCase = true)) {
                    running.remove(id)
                    if (attachedId == id) transcript.settleAll()
                    publishConversations()
                    emitNow()
                }
            }
        }
    }

    override fun deleteConversation(id: String) {
        backendScope.launch {
            if (id in createdSnapshot()) {
                val r = client.sessionAction(id, "delete")
                if (r.isFailure) {
                    _toasts.tryEmit("Could not delete the chat: ${r.exceptionOrNull()?.message}")
                    return@launch
                }
            }
            backendScope.launch(worker) {
                localConversations.remove(id)
                shadows.remove(id)
                lastServerList = lastServerList.filter { it.id != id }
                synchronized(created) { created.remove(id) }
                publishConversations()
            }
            if (store.currentConversation.value?.id == id) startNewChat()
        }
    }

    /** dsh has no fork through the app. */
    override fun forkConversation(id: String) {
        _toasts.tryEmit("Copying a chat is not available in DeepSeek Harness chats")
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
            client.sessionAction(id, "rename", buildJsonObject { put("title", title) })
                .onSuccess {
                    backendScope.launch(worker) { applyTitle(id, title) }
                    scheduleRefresh()
                }
                .onFailure { _toasts.tryEmit("Could not rename: ${it.message}") }
        }
    }

    /** Archives / unarchives a chat. */
    fun setArchived(id: String, archived: Boolean) {
        backendScope.launch {
            if (id !in createdSnapshot()) return@launch
            client.sessionAction(id, "archive", buildJsonObject { put("archived", archived) })
                .onSuccess { refreshSessions() }
                .onFailure { _toasts.tryEmit("Could not ${if (archived) "archive" else "unarchive"} the chat: ${it.message}") }
        }
    }

    /** Messages of a chat that is not open (export / share from the sidebar). */
    suspend fun loadMessages(id: String): List<ChatMessage> {
        val events = client.transcript(id).getOrNull() ?: return emptyList()
        val t = DshTranscript(id)
        t.loadHistory(events, active = false)
        return t.toChatMessages(id, emptySet(), null)
    }

    // ------------------------------------------------------------------ chat controls

    /** Picks the model of the chat on screen (and of new chats); a chat dsh already has switches for its next message. */
    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        prefs.setModel(modelId)
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.DSH } ?: return
        store.currentConversation.value = conv.copy(modelId = modelId)
        if (conv.id in createdSnapshot() && !conv.readOnly) backendScope.launch(worker) { applyModelTo(conv.id) }
    }

    fun selectEffort(effort: String) {
        _effort.value = effort
        prefs.setEffort(effort)
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.DSH } ?: return
        if (conv.id in createdSnapshot() && !conv.readOnly) backendScope.launch(worker) { applyModelTo(conv.id) }
    }

    /**
     * Switches [sessionId] to the selected model and effort. [onlyIfChanged]: a new chat on the bridge's default model
     * (and default effort) needs no switch.
     */
    private suspend fun applyModelTo(sessionId: String, onlyIfChanged: Boolean = false) {
        val model = _selectedModelId.value.ifBlank { return }
        if (onlyIfChanged && model == defaultModelId() && _effort.value.isBlank()) return
        val (provider, id) = DshPreferences.split(model) ?: return
        client.setModel(sessionId, provider, id, _effort.value)
            .onFailure { _toasts.tryEmit("Could not switch the model: ${it.message}") }
    }

    /** Plan mode on / off for the chat on screen (dsh's /plan command). */
    fun setPlanMode(on: Boolean) {
        _planMode.value = on
        sendCommand(if (on) "/plan" else "/plan off")
    }

    /** What a "/" command opens in the app instead of being sent (the chat screen handles it). */
    enum class AppCommand { MODEL_PICKER, SETTINGS, MCP, NEW_CHAT }

    /** The app action of [text] ("/model", "/settings"…), or null when it is not one. */
    fun appCommand(text: String): AppCommand? = when (text.trim().lowercase()) {
        "/model", "/models" -> AppCommand.MODEL_PICKER
        "/settings", "/status", "/provider", "/key" -> AppCommand.SETTINGS
        "/mcp" -> AppCommand.MCP
        "/new", "/clear" -> AppCommand.NEW_CHAT
        else -> null
    }

    /** dsh's skills for the "/" menu (its skills folder, read through the bridge); dsh recognizes "/name" itself. */
    fun refreshSkills() {
        skillsJob?.cancel()
        skillsJob = backendScope.launch {
            val res = com.example.gemini.data.skills.AgentSkillsClient.list(AgentKind.DSH).getOrNull() ?: return@launch
            _slashCommands.value = BUILTIN_COMMANDS + res.skills.filter { it.kind == "skill" }.mapNotNull { sk ->
                val n = sk.name.ifBlank { sk.id }.ifBlank { return@mapNotNull null }
                com.example.gemini.data.remote.SlashCommandItem(
                    name = n, command = "/$n", description = sk.description, type = "skill", argumentHint = "what to do"
                )
            }.distinctBy { it.command }
        }
    }

    // ------------------------------------------------------------------ approvals & questions

    /** Allows a request once (dsh grants never persist beyond the operation). */
    fun approve(key: String) {
        backendScope.launch(worker) {
            val p = pendingAll[key] ?: return@launch
            answer(p, buildJsonObject { put("outcome", "allowed-once") })
        }
    }

    /** Declines a request; [feedback] (optional) goes to dsh as the chat's next message. */
    fun deny(key: String, feedback: String? = null) {
        backendScope.launch(worker) {
            val p = pendingAll[key] ?: return@launch
            if (!answer(p, buildJsonObject { put("outcome", "rejected") })) return@launch
            val note = feedback?.trim().orEmpty()
            val sid = p.sessionId ?: return@launch
            if (note.isNotBlank()) {
                client.rpc("session/query", buildJsonObject {
                    put("sessionId", sid)
                    put("contentBlocks", JsonArray(listOf(textBlock(note))))
                }).onFailure { _toasts.tryEmit("DeepSeek Harness did not get your note: ${it.message}") }
            }
        }
    }

    private suspend fun answer(p: DshPending, result: JsonElement): Boolean {
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
            pendingAll.values.firstOrNull { !it.isQuestion && it.callId != null && DshTranscript.safeId(it.callId!!) == toolCallId }?.let { action(it.key) }
        }
    }

    fun submitChoices(toolCall: ToolCall, responses: List<AskQuestionResponseItemDto>) {
        backendScope.launch(worker) {
            val p = pendingAll.values.firstOrNull { it.isQuestion && DshTranscript.questionToolId(it.key) == toolCall.id } ?: return@launch
            val questions = p.params.a("questions").orEmpty().mapNotNull { it.obj() }
            val answers = responses.mapIndexedNotNull { i, r ->
                if (r.skipped == true) return@mapIndexedNotNull null
                val q = questions.firstOrNull { (it.str("question") ?: it.str("header")) == r.question } ?: questions.getOrNull(i) ?: return@mapIndexedNotNull null
                val qid = q.str("id") ?: return@mapIndexedNotNull null
                buildJsonObject {
                    put("id", qid)
                    put("selected", JsonArray(r.selectedOptionIds.orEmpty().map { JsonPrimitive(it) }))
                    r.writeInResponse?.trim()?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                }
            }
            answer(p, buildJsonObject { put("answers", JsonArray(answers)) })
        }
    }

    fun cancelChoices(toolCall: ToolCall) {
        backendScope.launch(worker) {
            val p = pendingAll.values.firstOrNull { it.isQuestion && DshTranscript.questionToolId(it.key) == toolCall.id } ?: return@launch
            answer(p, buildJsonObject { put("cancelled", true) })
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
                    pendingAll[key] = pendingOf(key, r.str("method").orEmpty(), r.o("params") ?: JsonObject(emptyMap()), r.str("session_id"))
                }
                // the statuses of every session this dsh process knows (none after it exited)
                applyStatuses(st.o("statuses")?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty(), replace = true)
                val next = st.long("next_seq") ?: 0L
                val bufStart = st.long("buffer_start") ?: 0L
                when (f.str("event")) {
                    "attached" -> {
                        dropLive()
                        val since = lastSeq + 1
                        val missed = lastSeq >= 0 && (bufStart > since || next < since)
                        lastSeq = next - 1
                        if (missed) resync()
                        if (_state.value != DshState.READY) backendScope.launch { refreshStatus() }
                    }
                    "exited" -> {
                        // every running turn ended with the process
                        transcript.settleAll()
                        shadows.values.forEach { it.settleAll() }
                        attachedId?.takeIf { it in created }?.let { reload(it) }
                        f.str("error")?.takeIf { it.isNotBlank() }?.let { _toasts.tryEmit("DeepSeek Harness stopped: ${it.take(160)}") }
                        backendScope.launch { refreshStatus() }
                    }
                    "spawned" -> backendScope.launch {
                        refreshStatus()
                        refreshModels()
                    }
                    "installed", "starting" -> backendScope.launch { refreshStatus() }
                }
                publishConversations()
                emitNow()
            }
            // live-only typing of the step being written (no seq, never replayed): `antigem/stream` batches
            "bridge_live" -> {
                val d = f.o("data") ?: return
                if (d.str("method") != "antigem/stream") return
                var changed = false
                for (item in d.o("params").a("items").orEmpty().mapNotNull { it.obj() }) {
                    val sid = item.str("sessionId") ?: continue
                    if (sid == attachedId) { if (transcript.applyLive(item)) changed = true }
                    else shadows[sid]?.applyLive(item)
                }
                if (changed) emitSoon()
            }
            "bridge_line" -> {
                f.long("seq")?.let { if (it > lastSeq) lastSeq = it }
                val d = f.o("data") ?: return
                val method = d.str("method") ?: return
                val params = d.o("params") ?: JsonObject(emptyMap())
                val key = f.str("key")
                if (d.containsKey("id") && key != null) {
                    val p = pendingOf(key, method, params, null)
                    pendingAll[key] = p
                    if (p.sessionId == attachedId || p.sessionId in descendantsOf(attachedId)) emitNow()
                } else {
                    handleNotification(method, params, key)
                }
            }
        }
    }

    private fun pendingOf(key: String, method: String, params: JsonObject, sessionId: String?) =
        DshPending(key = key, method = method, params = params, sessionId = params.str("sessionId") ?: sessionId)

    /** Session statuses from the bridge ([replace]: the full set, sessions missing from it are idle). On [worker]. */
    private fun applyStatuses(statuses: Map<String, String>, replace: Boolean = false) {
        val on = statuses.filterValues { it == "running" }.keys
        if (replace) running.clear() else running.removeAll(statuses.filterValues { it != "running" }.keys)
        running.addAll(on)
        attachedId?.let { id ->
            if (id in running) transcript.running = true
            else if (replace || id in statuses) transcript.settleAll()
        }
        publishConversations()
    }

    private suspend fun handleNotification(method: String, p: JsonObject, key: String?) {
        when (method) {
            "session.event" -> {
                val sid = p.str("sessionId") ?: return
                val ev = p.o("event") ?: return
                val type = ev.str("type") ?: return
                val d = ev.o("data")
                if (sid != attachedId) {
                    // a reply running in a chat that is not on screen: keep its live copy current
                    if (type == "turn/start" && sid in sentTo) {
                        if (shadows[sid] == null) shadows[sid] = DshTranscript(sid)
                        trimShadows()
                    }
                    shadows[sid]?.apply(ev)
                    when (type) {
                        "session/title" -> d.str("title")?.let { if (!userTitled(sid)) applyTitle(sid, it) }
                        "turn/end" -> scheduleRefresh()
                    }
                    return
                }
                when (type) {
                    "turn/start" -> _notice.value = null
                    "llm/retry" -> {
                        val failure = d.o("failure").str("message").orEmpty()
                        val max = d.long("maxRetries")?.let { "/$it" }.orEmpty()
                        _notice.value = "Retrying (${d.long("retry") ?: 1}$max)… ${failure.take(140)}".trim()
                    }
                    "plan/mode" -> d.bool("active")?.let { _planMode.value = it }
                    "model/selection" -> DshPreferences.join(d.str("provider"), d.str("model")).ifBlank { null }?.let { m ->
                        _selectedModelId.value = m
                        d.str("reasoningEffort")?.let { _effort.value = it }
                    }
                    "session/title" -> d.str("title")?.let { if (!userTitled(sid)) applyTitle(sid, it) }
                }
                val changed = transcript.apply(ev)
                if (type == "turn/end") {
                    _notice.value = null
                    emitNow()
                    onTurnFinished()
                    // the list holds the chat's context use and order
                    scheduleRefresh()
                    return
                }
                if (changed) emitSoon()
            }
            "session.status" -> {
                val sid = p.str("sessionId") ?: return
                val on = p.str("status") == "running"
                if (if (on) running.add(sid) else running.remove(sid)) publishConversations()
                shadows[sid]?.let { if (on) it.running = true else it.settleAll() }
                if (sid == attachedId) {
                    if (on) transcript.running = true else transcript.settleAll()
                    emitSoon()
                }
                if (!on) scheduleRefresh()
            }
            "subagent.started", "subagent.finished" -> scheduleRefresh()
            "user-question.cancelled" -> key?.let { if (pendingAll.remove(it) != null) emitNow() }
            "antigem/request-resolved" -> (p.str("key") ?: key)?.let { if (pendingAll.remove(it) != null) emitNow() }
        }
    }

    /** Drops every partial step shown live (on [worker]). */
    private fun dropLive() {
        shadows.values.forEach { it.clearLive() }
        if (transcript.clearLive()) emitSoon()
    }

    /** The user renamed this chat in the app: dsh's own titles no longer apply. */
    private fun userTitled(id: String): Boolean = sessionInfo[id].str("title_source") == "user"

    private fun applyTitle(id: String, title: String) {
        val clean = com.example.gemini.data.remote.PromptExpansion.cleanTitle(title).lines().firstOrNull { it.isNotBlank() }?.trim() ?: return
        lastServerList = lastServerList.map { if (it.id == id) it.copy(title = clean) else it }
        localConversations[id]?.let { localConversations[id] = it.copy(title = clean) }
        publishConversations()
        store.currentConversation.value?.takeIf { it.id == id }?.let { store.currentConversation.value = it.copy(title = clean) }
    }

    /** The subagent sessions under [id] (their approvals and questions show in the parent chat). */
    private fun descendantsOf(id: String?): Set<String> {
        if (id == null) return emptySet()
        val byParent = lastServerList.filter { it.parentConversationId != null }.groupBy { it.parentConversationId }
        val out = HashSet<String>()
        fun collect(p: String) {
            byParent[p].orEmpty().forEach { c -> if (out.add(c.id)) collect(c.id) }
        }
        collect(id)
        return out
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
            refreshSessions()
        }
    }

    // a burst of events is shown at most every 80 ms
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
        val under = descendantsOf(id)
        val mine = pendingAll.values.filter { it.sessionId == id || it.sessionId in under }
        val awaiting = mine.filter { !it.isQuestion }.mapNotNull { it.callId }.toSet()
        val question = mine.firstOrNull { it.isQuestion }?.let { q ->
            DshTranscript.Question(q.key, q.params.a("questions").orEmpty().mapNotNull { it.obj() })
        }
        _messages.value = transcript.toChatMessages(id, awaiting, question)
        _pendingApprovals.value = mine.filter { !it.isQuestion }
        _isStreaming.value = transcript.isTurnActive
    }

    // ------------------------------------------------------------------ helpers

    private fun textBlock(text: String) = buildJsonObject { put("type", "text"); put("text", text) }

    private fun isNativeCommand(text: String): Boolean = text.substringBefore(' ').substringBefore('\n').lowercase() in NATIVE_COMMANDS

    private fun defaultWorkspaceUri(): String {
        val path = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path.orEmpty()
        return if (path.isBlank()) "" else if (path.startsWith("file://")) path else "file://$path"
    }

    private fun workspacePath(conv: Conversation): String? = conv.workspaceUri.removePrefix("file://").takeIf { it.startsWith("/") }

    companion object {
        private const val TAG = "DshBackend"
        /** Live copies kept of chats replying while not on screen. */
        private const val MAX_SHADOWS = 12

        /** Image types dsh takes inline (others go as files). */
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        /** dsh's own commands: sent as the only text block, they never become a user message. */
        private val NATIVE_COMMANDS = setOf("/compact", "/plan", "/goal", "/permission")

        /** dsh's own commands in the "/" menu of a dsh chat. */
        val BUILTIN_COMMANDS = listOf(
            com.example.gemini.data.remote.SlashCommandItem("compact", "/compact", "Summarize the chat so far to free up context", "command"),
            com.example.gemini.data.remote.SlashCommandItem("plan", "/plan", "Plan mode: plan before changing anything (or: /plan the task)", "command"),
            com.example.gemini.data.remote.SlashCommandItem("plan off", "/plan off", "Leave plan mode", "command"),
            com.example.gemini.data.remote.SlashCommandItem("goal", "/goal", "Work toward a goal over several rounds (clear, pause, resume, edit)", "command", argumentHint = "objective"),
            com.example.gemini.data.remote.SlashCommandItem("model", "/model", "Pick the model", "app"),
            com.example.gemini.data.remote.SlashCommandItem("settings", "/settings", "Provider, API key, permissions and server", "app"),
            com.example.gemini.data.remote.SlashCommandItem("mcp", "/mcp", "MCP servers DeepSeek Harness can use", "app"),
            com.example.gemini.data.remote.SlashCommandItem("new", "/new", "Start a new DeepSeek Harness chat", "app")
        )
    }
}
