package com.example.gemini.data.agent.opencode

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.data.agent.claude.isBridgeUnreachable
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
import java.security.SecureRandom

/** Whether OpenCode can be used, as the chat screen and the settings show it. */
enum class OpenCodeState(val label: String) {
    CHECKING("Checking…"),
    READY("Ready"),
    NOT_INSTALLED("Not installed"),
    DISABLED("Turned off on the server"),
    OUTDATED("Server update needed"),
    OFFLINE("Server offline"),
    ERROR("Error");

    /** Sending a message cannot work in this state. */
    val isBlocking get() = this == NOT_INSTALLED || this == DISABLED || this == OUTDATED || this == OFFLINE
}

/** One agent of OpenCode (build, plan, or the user's own). */
data class OpenCodeAgent(val id: String, val name: String, val description: String)

/** A provider OpenCode knows (built-in integration or a custom one). */
data class OpenCodeProviderInfo(val id: String, val name: String)

/**
 * OpenCode chat backend. The bridge runs one `opencode serve` for every OpenCode chat; this class reads sessions and
 * their messages from its API, follows its live event stream (one connection for all chats), and sends prompts,
 * stops, approvals and answers. [OpenCodeTranscript] turns the open chat into the shared chat UI's messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: OpenCodePreferences,
    val client: OpenCodeBridgeClient = OpenCodeBridgeClient(),
    /** Model and agent picked in this backend become the defaults for new chats (false for Jarvis' own chat). */
    private val rememberChoices: Boolean = true,
    /** Extra permission rules for every chat of this backend (Jarvis' chat: no question forms nobody sees). */
    private val extraRules: List<JsonObject> = emptyList()
) : AgentChatBackend {

    override val kind = AgentKind.OPENCODE

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()
    // OpenCode keeps its own message state like Claude: the other agents never touch it
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

    private val _state = MutableStateFlow(OpenCodeState.CHECKING)
    val state: StateFlow<OpenCodeState> = _state.asStateFlow()
    private val _status = MutableStateFlow<OpenCodeStatus?>(null)
    /** The bridge's last status answer (version, binary, error). */
    val status: StateFlow<OpenCodeStatus?> = _status.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()
    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private val _selectedModelId = MutableStateFlow(prefs.model.value)
    /** "provider/model" of the chat on screen (blank until OpenCode's default is known). */
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    private val _providers = MutableStateFlow<List<OpenCodeProviderInfo>>(emptyList())
    val providers: StateFlow<List<OpenCodeProviderInfo>> = _providers.asStateFlow()

    private val _agents = MutableStateFlow<List<OpenCodeAgent>>(emptyList())
    val agents: StateFlow<List<OpenCodeAgent>> = _agents.asStateFlow()
    private val _agent = MutableStateFlow(prefs.agent.value)
    /** Agent of the chat on screen (build / plan / …). */
    val agent: StateFlow<String> = _agent.asStateFlow()
    val permissionMode: StateFlow<String> = prefs.permissionMode

    private val _pendingPermissions = MutableStateFlow<List<OpenCodeTranscript.Permission>>(emptyList())
    val pendingPermissions: StateFlow<List<OpenCodeTranscript.Permission>> = _pendingPermissions.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Retry / compaction notice of the chat on screen. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _cliJob = MutableStateFlow<OpenCodeCliJob?>(null)
    /** The last install / update of OpenCode started from the app. */
    val cliJob: StateFlow<OpenCodeCliJob?> = _cliJob.asStateFlow()

    private val _connectedProviders = MutableStateFlow<List<String>>(emptyList())
    /** Names of the built-in providers that have a saved key (OpenCode's own free models need none). */
    val connectedProviders: StateFlow<List<String>> = _connectedProviders.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    /** Called after each finished turn. */
    var onTurnFinished: () -> Unit = {}

    // ---- below: only touched on [worker]
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val transcript = OpenCodeTranscript()
    private var attachedId: String? = null
    /** Sessions that exist on the server (a new chat exists only in the app until its first message). */
    private val created = HashSet<String>()
    private val running = HashSet<String>()
    private val localConversations = LinkedHashMap<String, Conversation>()
    private var loopJob: Job? = null
    private var eventsJob: Job? = null
    private var emitJob: Job? = null
    private var refreshJob: Job? = null
    private var connected = false
    // when the open chat's turn last started (System.nanoTime), and when its last event came (System.currentTimeMillis)
    private var turnStartedAt = 0L
    private var lastActivityAt = 0L
    private var watchdogJob: Job? = null
    // retries counted for chats that are not open (the open one counts in its transcript)
    private val retriesBySession = HashMap<String, Int>()

    // ------------------------------------------------------------------ lifecycle

    /** OpenCode is enabled: check the server, load models and chats, follow the event stream. */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = backendScope.launch {
            refreshStatus()
            refreshModels()
            refreshAgents()
            while (isActive) {
                refreshSessions()
                delay(if (_state.value == OpenCodeState.READY) 60_000 else 15_000)
                if (_state.value != OpenCodeState.READY) refreshStatus()
            }
        }
        eventsJob = backendScope.launch(worker) {
            client.events(
                onConnected = {
                    backendScope.launch(worker) {
                        connected = true
                        if (_state.value != OpenCodeState.READY) refreshStatus()
                        resync()
                    }
                },
                onDisconnected = { e ->
                    connected = false
                    if (e is OpenCodeUnavailableException) {
                        _state.value = if (e.disabled) OpenCodeState.DISABLED else OpenCodeState.OUTDATED
                    }
                }
            ).collect { handleEvent(it) }
        }
    }

    /** OpenCode was turned off: stop everything and forget its chats. */
    fun stop() {
        loopJob?.cancel(); loopJob = null
        eventsJob?.cancel(); eventsJob = null
        detach()
        _conversations.value = emptyList()
        backendScope.launch(worker) { localConversations.clear() }
    }

    /** Leaves the chat on screen (another agent's chat opens). */
    fun detach() {
        _isStreaming.value = false
        _isLoadingConversation.value = false
        _pendingPermissions.value = emptyList()
        _notice.value = null
        backendScope.launch(worker) {
            attachedId = null
            transcript.reset("")
            _messages.value = emptyList()
        }
    }

    override fun retryConnections() {
        backendScope.launch {
            refreshStatus(showProgress = true)
            refreshModels()
            refreshSessions()
        }
    }

    override fun onAppForegrounded() {
        backendScope.launch {
            if (_state.value != OpenCodeState.READY) refreshStatus()
            refreshSessions()
            backendScope.launch(worker) { attachedId?.takeIf { it in created }?.let { reload(it) } }
        }
    }

    /**
     * Asks the bridge whether OpenCode is there. Right after the app starts the local server is still coming up: an
     * unreachable bridge then keeps "Checking…" and is asked again every 2 s (like Claude Code and Antigravity's
     * start-up grace) instead of showing OpenCode as unavailable. [showProgress]: a re-check the user asked for shows
     * "Checking…" meanwhile (the periodic one does not, so the state does not flicker).
     */
    suspend fun refreshStatus(showProgress: Boolean = false) {
        if (showProgress && _state.value != OpenCodeState.READY) _state.value = OpenCodeState.CHECKING
        var result = client.status()
        while (result.exceptionOrNull()?.isBridgeUnreachable() == true &&
            com.example.gemini.data.agent.claude.BridgeStartup.isStartingUp()
        ) {
            if (_state.value != OpenCodeState.READY) _state.value = OpenCodeState.CHECKING
            delay(2_000)
            result = client.status()
        }
        if (result.isSuccess) com.example.gemini.data.agent.claude.BridgeStartup.markReached()
        result
            .onSuccess { st ->
                _status.value = st
                _state.value = if (st.installed) OpenCodeState.READY else OpenCodeState.NOT_INSTALLED
            }
            .onFailure { e ->
                _state.value = when {
                    e is OpenCodeUnavailableException && e.disabled -> OpenCodeState.DISABLED
                    e is OpenCodeUnavailableException && e.outdated -> OpenCodeState.OUTDATED
                    e is java.io.IOException -> OpenCodeState.OFFLINE
                    else -> OpenCodeState.ERROR
                }
                _status.value = OpenCodeStatus(error = e.message)
            }
    }

    /** Installs ([action] "install") or updates ("update") OpenCode on the server and follows the job's log. */
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
                            _toasts.tryEmit(if (ok) "OpenCode ${if (action == "install") "installed" else "is up to date"}" else "OpenCode $action failed")
                            refreshStatus()
                            if (ok) {
                                refreshModels()
                                refreshAgents()
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

    fun restartServer(onDone: (String?) -> Unit = {}) {
        backendScope.launch {
            val r = client.restart()
            refreshStatus()
            refreshModels()
            onDone(r.exceptionOrNull()?.message)
        }
    }

    // ------------------------------------------------------------------ models, agents, sessions

    fun refreshModels() {
        backendScope.launch {
            _isRefreshingModels.value = true
            try {
                client.get("/integration").getOrNull().obj().a("data")?.let { list ->
                    _connectedProviders.value = list.mapNotNull { it.obj() }
                        .filter { it.a("connections").orEmpty().isNotEmpty() }
                        .map { it.str("name") ?: it.str("id").orEmpty() }
                }
                val providerNames = client.get("/provider").getOrNull().obj().a("data").orEmpty()
                    .mapNotNull { p -> p.obj()?.let { OpenCodeProviderInfo(it.str("id") ?: return@mapNotNull null, it.str("name") ?: it.str("id")!!) } }
                _providers.value = providerNames
                val names = providerNames.associate { it.id to it.name }
                val default = client.get("/model/default").getOrNull().obj().o("data")
                val defaultId = default?.let { "${it.str("providerID")}/${it.str("id")}" }
                client.get("/model")
                    .onSuccess { res ->
                        val list = res.obj().a("data").orEmpty().mapNotNull { it.obj() }
                            .filter { (it.str("type") ?: "chat") == "chat" && it.bool("enabled") != false && it.str("status") != "deprecated" }
                            .map { m ->
                                val provider = m.str("providerID").orEmpty()
                                val id = "$provider/${m.str("id")}"
                                val free = m.a("cost").orEmpty().firstOrNull().obj()?.let { c -> (c.dbl("input") ?: 1.0) == 0.0 && (c.dbl("output") ?: 1.0) == 0.0 } == true
                                AiModel(
                                    id = id,
                                    displayName = m.str("name") ?: m.str("id").orEmpty(),
                                    description = buildString {
                                        append(names[provider] ?: provider)
                                        m.o("limit").long("context")?.takeIf { it > 0 }?.let { append(" · ${it / 1000}K context") }
                                        if (free) append(" · free")
                                    },
                                    isDefault = id == defaultId,
                                    key = id,
                                    baseName = names[provider] ?: provider,
                                    supportsThinking = m.o("capabilities").bool("reasoning") == true || m.o("compatibility").str("reasoningField") != null
                                )
                            }
                            .sortedWith(compareBy({ !it.isDefault }, { it.baseName.lowercase() }, { it.displayName.lowercase() }))
                        _availableModels.value = list
                        _modelsError.value = if (list.isEmpty()) "No models: connect a provider in Settings → OpenCode." else null
                        // the chat's model: what was picked before (if it still exists), else OpenCode's default
                        val cur = _selectedModelId.value
                        if (cur.isBlank() || list.none { it.id == cur }) {
                            val pick = prefs.model.value.takeIf { p -> list.any { it.id == p } } ?: defaultId ?: list.firstOrNull()?.id.orEmpty()
                            if (store.currentConversation.value?.agent != AgentKind.OPENCODE || store.currentConversation.value?.id !in createdSnapshot()) {
                                _selectedModelId.value = pick
                            }
                        }
                    }
                    .onFailure { _modelsError.value = it.message }
            } finally {
                _isRefreshingModels.value = false
            }
        }
    }

    private fun createdSnapshot(): Set<String> = synchronized(created) { created.toSet() }

    fun refreshAgents() {
        backendScope.launch {
            client.get("/agent").onSuccess { res ->
                _agents.value = res.obj().a("data").orEmpty().mapNotNull { it.obj() }
                    .filter { it.bool("hidden") != true && it.str("mode") != "subagent" }
                    .map { OpenCodeAgent(it.str("id").orEmpty(), it.str("name") ?: it.str("id").orEmpty(), it.str("description").orEmpty()) }
            }
        }
    }

    suspend fun refreshSessions() {
        val res = client.get("/session?limit=200&parentID=null").getOrElse { return }
        val active = client.get("/session/active").getOrNull().obj().o("data")
        val list = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.mapNotNull { s -> conversationOf(s, active) }
        backendScope.launch(worker) {
            synchronized(created) { list.forEach { created.add(it.id) } }
            running.clear()
            active?.keys?.let { running.addAll(it) }
            val serverIds = list.map { it.id }.toSet()
            localConversations.keys.removeAll(serverIds)
            publishConversations(list)
        }
    }

    private var lastServerList: List<Conversation> = emptyList()

    private fun publishConversations(serverList: List<Conversation>? = null) {
        if (serverList != null) lastServerList = serverList
        val merged = (localConversations.values + lastServerList.map { c -> c.copy(isRunning = c.id in running) })
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
        _conversations.value = merged
    }

    private fun conversationOf(s: JsonObject, active: JsonObject?): Conversation? {
        val id = s.str("id") ?: return null
        if (s.o("time").long("archived") != null) return null
        val dir = s.o("location").str("directory").orEmpty()
        return Conversation(
            id = id,
            title = s.str("title")?.takeIf { it.isNotBlank() } ?: "New Chat",
            modelId = OpenCodeTranscript.modelRef(s.o("model")).orEmpty(),
            sessionId = id,
            createdAt = s.o("time").long("created") ?: 0L,
            updatedAt = s.o("time").long("updated") ?: 0L,
            isRunning = active?.containsKey(id) == true,
            workspaceUri = if (dir.isBlank()) "" else "file://$dir",
            parentConversationId = s.str("parentID"),
            agent = AgentKind.OPENCODE
        )
    }

    // ------------------------------------------------------------------ AgentChatBackend

    override fun startNewChat() {
        val id = newId("ses", descending = true)
        val conv = Conversation(
            id = id,
            title = "New Chat",
            modelId = _selectedModelId.value,
            sessionId = id,
            workspaceUri = defaultWorkspaceUri(),
            agent = AgentKind.OPENCODE
        )
        _selectedModelId.value = prefs.model.value.takeIf { p -> _availableModels.value.any { it.id == p } } ?: _selectedModelId.value
        _agent.value = prefs.agent.value
        store.currentConversation.value = conv.copy(modelId = _selectedModelId.value)
        _conversationError.value = null
        _isLoadingConversation.value = false
        _isStreaming.value = false
        _pendingPermissions.value = emptyList()
        _notice.value = null
        backendScope.launch(worker) {
            attachedId = id
            transcript.reset(id)
            emitNow()
        }
    }

    /** The folder of the fresh chat (nothing sent yet). */
    fun setWorkspaceOfNewChat(path: String) {
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.OPENCODE || conv.id in createdSnapshot() || path.isBlank()) return
        store.currentConversation.value = conv.copy(workspaceUri = if (path.startsWith("file://")) path else "file://$path")
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id }
            ?: Conversation(id = id, title = "OpenCode Chat", sessionId = id, agent = AgentKind.OPENCODE)
        store.currentConversation.value = conv
        if (conv.modelId.isNotBlank()) _selectedModelId.value = conv.modelId
        _conversationError.value = null
        _isLoadingConversation.value = true
        _pendingPermissions.value = emptyList()
        _notice.value = null
        _messages.value = emptyList()
        backendScope.launch(worker) {
            attachedId = id
            transcript.reset(id)
            synchronized(created) { created.add(id) }
            reload(id)
            _isLoadingConversation.value = false
        }
    }

    /** Loads the chat's messages, pending approvals and questions from OpenCode (on [worker]). */
    private suspend fun reload(id: String) {
        val reloadStartedAt = System.nanoTime()
        val items = mutableListOf<JsonObject>()
        var res = client.get("/session/$id/message?limit=200&order=desc")
        var pages = 0
        while (true) {
            val body = res.getOrElse { e ->
                if (attachedId == id) _conversationError.value = "Could not load this chat: ${e.message}"
                return
            }.obj()
            val page = body.a("data").orEmpty().mapNotNull { it.obj() }
            items.addAll(page)
            val next = body.o("cursor").str("next")
            if (page.isEmpty() || next.isNullOrBlank() || ++pages >= 10) break
            res = client.get("/session/$id/message?limit=200&cursor=${java.net.URLEncoder.encode(next, "UTF-8")}")
        }
        val active = client.get("/session/active").getOrNull().obj().o("data")?.containsKey(id) == true
        val sessionInfo = client.get("/session/$id").getOrNull().obj().o("data")
        val perms = client.get("/session/$id/permission").getOrNull().obj().a("data").orEmpty().mapNotNull { it.obj() }
        val forms = client.get("/session/$id/form").getOrNull().obj().a("data").orEmpty().mapNotNull { it.obj() }
        if (attachedId != id) return
        // a turn started while this was loading (a message sent right after the last reply): the snapshot is older than
        // what is on screen, and the turn's end loads it again
        if (!active && transcript.isTurnActive && turnStartedAt > reloadStartedAt) return
        transcript.loadHistory(items, active)
        transcript.pendingPermissions.clear()
        transcript.pendingForms.clear()
        for (p in perms) permissionOf(p)?.let { transcript.addPermission(it) }
        for (f in forms) {
            if (f.o("state").str("status")?.let { it != "pending" } == true) continue
            val fid = f.str("id") ?: continue
            transcript.addForm(OpenCodeTranscript.Form(fid, id, f.str("title").orEmpty(), f.a("fields").orEmpty().mapNotNull { it.obj() }))
        }
        sessionInfo?.let { s ->
            OpenCodeTranscript.modelRef(s.o("model"))?.let { _selectedModelId.value = it }
            s.str("agent")?.let { _agent.value = it }
        }
        if (active) {
            running.add(id)
            lastActivityAt = System.currentTimeMillis()
            startWatchdog()
        } else running.remove(id)
        emitNow()
    }

    private fun permissionOf(p: JsonObject): OpenCodeTranscript.Permission? {
        val id = p.str("id") ?: return null
        return OpenCodeTranscript.Permission(
            id = id,
            sessionId = p.str("sessionID").orEmpty(),
            action = p.str("action").orEmpty(),
            resources = p.a("resources").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            save = p.a("save").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            toolCallId = p.o("source").str("id"),
            messageId = p.o("source").str("messageID"),
            metadata = p.o("metadata") ?: JsonObject(emptyMap())
        )
    }

    /** Everything that may have been missed while the event stream was down. */
    private suspend fun resync() {
        refreshSessions()
        attachedId?.takeIf { it in createdSnapshot() }?.let { reload(it) }
    }

    override fun sendMessage(content: String) {
        val atts = store.attachments.value
        if (content.isBlank() && atts.isEmpty()) return
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.OPENCODE) return
        if (_state.value.isBlocking) {
            _toasts.tryEmit("OpenCode is not available: ${_state.value.label}. Check Settings → OpenCode.")
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
        _isStreaming.value = true
        _conversationError.value = null
        val msgId = newId("msg", descending = false)
        backendScope.launch(worker) {
            if (attachedId != conv.id) {
                attachedId = conv.id
                transcript.reset(conv.id)
            }
            transcript.addLocalPrompt(msgId, text, atts.map { it.name })
            turnStartedAt = System.nanoTime()
            lastActivityAt = System.currentTimeMillis()
            startWatchdog()
            emitNow()
            if (conv.id !in createdSnapshot()) {
                localConversations[conv.id] = updated
                running.add(conv.id)
                publishConversations()
                val body = buildJsonObject {
                    put("id", conv.id)
                    workspacePath(conv)?.let { dir -> put("location", buildJsonObject { put("directory", dir) }) }
                    modelRefJson(_selectedModelId.value)?.let { put("model", it) }
                    if (_agent.value.isNotBlank() && _agent.value != "build") put("agent", _agent.value)
                    sessionRules(prefs.permissionMode.value, _agent.value)?.let { put("permissions", it) }
                }
                val r = client.post("/session", body)
                if (r.isFailure) {
                    fail(msgId, "Could not start the OpenCode chat", r.exceptionOrNull())
                    return@launch
                }
                synchronized(created) { created.add(conv.id) }
            } else {
                running.add(conv.id)
                publishConversations()
            }
            val files = mutableListOf<JsonObject>()
            val notes = mutableListOf<String>()
            for (a in atts) attachmentPart(a, workspacePath(conv).orEmpty(), files, notes)
            val promptText = (listOf(text) + notes).filter { it.isNotBlank() }.joinToString("\n\n")
            val r = client.post("/session/${conv.id}/prompt", buildJsonObject {
                put("id", msgId)
                put("text", promptText.ifBlank { "See the attached file." })
                if (files.isNotEmpty()) put("files", JsonArray(files))
            })
            if (r.isFailure) fail(msgId, "OpenCode did not take the message", r.exceptionOrNull())
        }
    }

    private suspend fun attachmentPart(a: ChatAttachment, project: String, files: MutableList<JsonObject>, notes: MutableList<String>) {
        val b64 = a.base64
        val mime = a.mimeType ?: if (a.isImage) "image/png" else "application/octet-stream"
        if (b64.isNullOrBlank()) {
            if (a.path.startsWith("/")) notes.add("Attached file: ${a.path}")
            return
        }
        if (a.isImage || mime.startsWith("image/")) {
            files.add(buildJsonObject {
                put("uri", "data:$mime;base64,$b64")
                put("name", a.name)
                put("source", buildJsonObject { put("type", "inline") })
            })
            return
        }
        val textLike = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("yaml") ||
            a.name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
        if (textLike && bytes != null && bytes.size <= 200_000) {
            notes.add("Attached file `${a.name}`:\n```\n${String(bytes, Charsets.UTF_8)}\n```")
            return
        }
        // anything else is saved on the server so OpenCode can open it with its tools
        client.upload(a.name, b64, project)
            .onSuccess { path -> notes.add("Attached file `${a.name}` (saved at $path)") }
            .onFailure { notes.add("(The attachment ${a.name} could not be saved on the server: ${it.message})") }
    }

    private fun fail(msgId: String, title: String, e: Throwable?) {
        Log.w(TAG, "$title: ${e?.message}")
        transcript.removeLocalPrompt(msgId)
        transcript.addError(title, e?.message ?: "Unknown error")
        attachedId?.let { running.remove(it) }
        publishConversations()
        emitNow()
        _isStreaming.value = false
    }

    /** Stops a chat's turn, open or not. */
    suspend fun interrupt(id: String): Result<Unit> = client.post("/session/$id/interrupt").map { }

    override fun stopStreaming() {
        val id = store.currentConversation.value?.takeIf { it.agent == AgentKind.OPENCODE }?.id ?: return
        backendScope.launch {
            client.post("/session/$id/interrupt").onFailure { _toasts.tryEmit("Could not stop: ${it.message}") }
        }
    }

    override fun deleteConversation(id: String) {
        backendScope.launch {
            val existed = id in createdSnapshot()
            val r = if (existed) client.api("DELETE", "/session/$id") else Result.success(JsonNull)
            if (r.isFailure) {
                _toasts.tryEmit("Could not delete the chat: ${r.exceptionOrNull()?.message}")
                return@launch
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
            client.post("/session/$id/fork", buildJsonObject { })
                .onSuccess { res ->
                    val newId = res.obj().o("data").str("id") ?: res.obj().str("id")
                    refreshSessions()
                    if (newId != null) selectConversation(newId)
                }
                .onFailure { _toasts.tryEmit("Could not copy the chat: ${it.message}") }
        }
    }

    fun renameConversation(id: String, title: String) {
        backendScope.launch {
            client.api("PATCH", "/session/$id", buildJsonObject { put("title", title) })
                .onSuccess { refreshSessions() }
                .onFailure { _toasts.tryEmit("Could not rename: ${it.message}") }
        }
    }

    /** Messages of a chat that is not open (export / share from the sidebar). */
    suspend fun loadMessages(id: String): List<ChatMessage> {
        val res = client.get("/session/$id/message?limit=500&order=desc").getOrNull() ?: return emptyList()
        val t = OpenCodeTranscript(id)
        t.loadHistory(res.obj().a("data").orEmpty().mapNotNull { it.obj() }, active = false)
        return t.toChatMessages(id)
    }

    // ------------------------------------------------------------------ chat controls

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        if (rememberChoices) prefs.setModel(modelId)
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.OPENCODE } ?: return
        store.currentConversation.value = conv.copy(modelId = modelId)
        if (conv.id !in createdSnapshot()) return
        val ref = modelRefJson(modelId) ?: return
        backendScope.launch {
            client.post("/session/${conv.id}/model", buildJsonObject { put("model", ref) })
                .onFailure { _toasts.tryEmit("Could not switch the model: ${it.message}") }
        }
    }

    /** Cancels a provider sign-in that was left open (the settings sheet closed). */
    fun cancelSignIn(integrationId: String, attemptId: String) {
        backendScope.launch { client.cancelOAuth(integrationId, attemptId) }
    }

    /** Switches the model of any chat (open or not); applies to its next message. */
    suspend fun switchModelOf(sessionId: String, modelId: String): Result<Unit> {
        val ref = modelRefJson(modelId) ?: return Result.failure(IllegalArgumentException("Not an OpenCode model id: $modelId"))
        return client.post("/session/$sessionId/model", buildJsonObject { put("model", ref) }).map { }
    }

    fun selectAgent(agentId: String) {
        _agent.value = agentId
        if (rememberChoices) prefs.setAgent(agentId)
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.OPENCODE } ?: return
        if (conv.id !in createdSnapshot()) return
        backendScope.launch {
            client.post("/session/${conv.id}/agent", buildJsonObject { put("agent", agentId) })
                .onFailure { _toasts.tryEmit("Could not switch to $agentId: ${it.message}") }
            client.api("PATCH", "/session/${conv.id}", buildJsonObject { put("permissions", sessionRules(prefs.permissionMode.value, agentId) ?: JsonArray(emptyList())) })
        }
    }

    fun setPermissionMode(mode: String) {
        prefs.setPermissionMode(mode)
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.OPENCODE } ?: return
        if (conv.id !in createdSnapshot()) return
        backendScope.launch {
            client.api("PATCH", "/session/${conv.id}", buildJsonObject { put("permissions", sessionRules(mode, _agent.value) ?: JsonArray(emptyList())) })
                .onFailure { _toasts.tryEmit("Could not change approvals: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ approvals & questions

    /** [decision]: "once", "always" or "reject". */
    fun replyPermission(requestId: String, decision: String, message: String? = null) {
        backendScope.launch(worker) {
            val perm = transcript.pendingPermissions[requestId]
            val sid = perm?.sessionId?.ifBlank { null } ?: attachedId ?: return@launch
            transcript.removePermission(requestId, rejected = decision == "reject")
            emitNow()
            client.post("/session/$sid/permission/$requestId/reply", buildJsonObject {
                put("decision", decision)
                if (!message.isNullOrBlank()) put("message", message)
            }).onFailure { e ->
                _toasts.tryEmit("Could not answer the request: ${e.message}")
                reload(sid)
            }
        }
    }

    fun approveTool(toolCall: ToolCall) = permissionForTool(toolCall.id) { replyPermission(it, "once") }
    fun rejectTool(toolCall: ToolCall, reason: String?) = permissionForTool(toolCall.id) { replyPermission(it, "reject", reason) }

    private fun permissionForTool(toolCallId: String, action: (String) -> Unit) {
        backendScope.launch(worker) {
            transcript.pendingPermissions.values
                .firstOrNull { it.toolCallId != null && OpenCodeTranscript.safeId(it.toolCallId) == toolCallId }
                ?.let { action(it.id) }
        }
    }

    fun submitChoices(toolCall: ToolCall, responses: List<AskQuestionResponseItemDto>) {
        backendScope.launch(worker) {
            val form = transcript.pendingForms.values.firstOrNull { OpenCodeTranscript.formToolId(it.id) == toolCall.id } ?: return@launch
            val answer = buildJsonObject {
                for (r in responses) {
                    if (r.skipped == true) continue
                    val key = form.keyByPrompt[r.question] ?: continue
                    val field = form.fields.firstOrNull { it.str("key") == key } ?: continue
                    val picked = r.selectedOptionIds.orEmpty()
                    val write = r.writeInResponse?.trim().orEmpty()
                    when (field.str("type")) {
                        "multiselect" -> put(key, buildJsonArray {
                            picked.forEach { add(JsonPrimitive(it)) }
                            if (write.isNotBlank()) add(JsonPrimitive(write))
                        })
                        "boolean" -> picked.firstOrNull()?.let { put(key, it == "true") }
                        "number", "integer" -> write.toDoubleOrNull()?.let { n ->
                            if (field.str("type") == "integer") put(key, n.toLong()) else put(key, n)
                        }
                        else -> {
                            val v = write.ifBlank { picked.joinToString(", ") }
                            if (v.isNotBlank()) put(key, v)
                        }
                    }
                }
            }
            transcript.removeForm(form.id)
            emitNow()
            client.post("/session/${form.sessionId}/form/${form.id}/reply", buildJsonObject { put("answer", answer) })
                .onFailure { e ->
                    _toasts.tryEmit("Could not send the answer: ${e.message}")
                    reload(form.sessionId)
                }
        }
    }

    fun cancelChoices(toolCall: ToolCall) {
        backendScope.launch(worker) {
            val form = transcript.pendingForms.values.firstOrNull { OpenCodeTranscript.formToolId(it.id) == toolCall.id } ?: return@launch
            transcript.removeForm(form.id)
            emitNow()
            client.api("DELETE", "/session/${form.sessionId}/form/${form.id}")
        }
    }

    // ------------------------------------------------------------------ stuck turns

    /**
     * Watches the open chat's turn while it waits for the model: some providers (e.g. a busy NVIDIA NIM model) never
     * answer and OpenCode waits forever. After [STALL_NOTICE_MS] of silence the notice says so; after [STALL_MS] the
     * turn is stopped with an error card. Running commands and requests waiting for the user do not count.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = backendScope.launch(worker) {
            var noticeShown = false
            while (isActive && transcript.isTurnActive) {
                delay(5_000)
                val id = attachedId ?: break
                if (!transcript.isTurnActive) break
                val quiet = System.currentTimeMillis() - lastActivityAt
                if (!transcript.isWaitingOnModel) {
                    if (noticeShown) { transcript.setNotice(null); noticeShown = false; emitNow() }
                    continue
                }
                when {
                    quiet >= STALL_MS -> {
                        val model = _selectedModelId.value.substringAfter('/').ifBlank { "the model" }
                        transcript.addError(
                            "No answer from the model",
                            "$model did not answer for ${STALL_MS / 1000} s. It may be overloaded or not available on the provider right now: try again, or pick another model."
                        )
                        transcript.setNotice(null)
                        emitNow()
                        interrupt(id)
                        break
                    }
                    quiet >= STALL_NOTICE_MS -> {
                        transcript.setNotice("Waiting for the model to answer… ${quiet / 1000} s")
                        noticeShown = true
                        emitNow()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ retry & edit (OpenCode's revert)

    /** Asks again: the chat goes back to right before the prompt of reply [assistantMessageId] and sends it again. */
    fun regenerate(assistantMessageId: String) {
        backendScope.launch(worker) {
            val sid = attachedId ?: return@launch
            if (transcript.isTurnActive) { _toasts.tryEmit("Stop the reply first"); return@launch }
            val prompt = transcript.promptBefore(assistantMessageId)
            if (prompt == null || prompt.local) { _toasts.tryEmit("Nothing to retry here"); return@launch }
            if (!revertTo(sid, prompt.id, restoreFiles = false)) return@launch
            reload(sid)
            withContextMain { sendMessage(prompt.text) }
        }
    }

    /** Edits a sent prompt: the chat goes back to right before it (optionally restoring the files changed since) and its text returns to the input. */
    fun editMessage(userMessageId: String, restoreFiles: Boolean, onRestored: (String) -> Unit) {
        backendScope.launch(worker) {
            val sid = attachedId ?: return@launch
            if (transcript.isTurnActive) { _toasts.tryEmit("Stop the reply first"); return@launch }
            val prompt = transcript.userMessage(userMessageId)
            if (prompt == null || prompt.local) { _toasts.tryEmit("This message can't be edited yet"); return@launch }
            if (!revertTo(sid, prompt.id, restoreFiles)) return@launch
            reload(sid)
            withContextMain { onRestored(prompt.text) }
        }
    }

    private suspend fun revertTo(sid: String, messageId: String, restoreFiles: Boolean): Boolean {
        val staged = client.post("/session/$sid/revert/stage", buildJsonObject {
            put("messageID", messageId)
            put("files", restoreFiles)
        })
        val committed = if (staged.isSuccess) client.post("/session/$sid/revert/commit") else staged
        committed.onFailure {
            client.api("DELETE", "/session/$sid/revert")
            _toasts.tryEmit("Could not go back in the chat: ${it.message}")
        }
        return committed.isSuccess
    }

    private suspend fun withContextMain(block: () -> Unit) = kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    // ------------------------------------------------------------------ events

    private fun handleEvent(e: JsonObject) {
        val type = e.str("type") ?: return
        val d = e.o("data") ?: JsonObject(emptyMap())
        val sid = d.str("sessionID") ?: d.o("form").str("sessionID")
        when (type) {
            "server.connected" -> return
            "session.created", "session.deleted", "session.forked", "session.archived", "session.unarchived", "session.moved" -> scheduleRefresh()
            "session.execution.started" -> sid?.let {
                retriesBySession.remove(it)
                if (running.add(it)) publishConversations()
            }
            "session.retry.scheduled" -> if (sid != null && sid != attachedId) {
                val n = d.long("attempt")?.let { (it - 1).toInt() } ?: ((retriesBySession[sid] ?: 0) + 1)
                retriesBySession[sid] = n
                if (n > OpenCodeTranscript.MAX_RETRIES) backendScope.launch { interrupt(sid) }
            }
            "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted", "session.idle" ->
                sid?.let { if (running.remove(it)) publishConversations() }
            "session.renamed" -> {
                val title = d.str("title")
                if (sid != null && title != null) {
                    lastServerList = lastServerList.map { if (it.id == sid) it.copy(title = title) else it }
                    localConversations[sid]?.let { localConversations[sid] = it.copy(title = title) }
                    publishConversations()
                    store.currentConversation.value?.takeIf { it.id == sid }?.let { store.currentConversation.value = it.copy(title = title) }
                }
            }
        }
        if (sid == null || sid != attachedId) return
        lastActivityAt = System.currentTimeMillis()
        if (type == "session.execution.started") {
            turnStartedAt = System.nanoTime()
            startWatchdog()
        }
        val changed = transcript.apply(type, d)
        if (type == "session.retry.scheduled" && transcript.retries > OpenCodeTranscript.MAX_RETRIES) {
            // OpenCode would keep retrying a provider that keeps failing: stop after a few tries and say why
            val why = transcript.lastRetryError
            transcript.addError(
                "Gave up after ${OpenCodeTranscript.MAX_RETRIES} retries",
                "The provider kept failing" + (if (why.isNotBlank()) ": $why" else ".") + " Try again later or pick another model."
            )
            transcript.setNotice(null)
            emitNow()
            backendScope.launch { interrupt(sid) }
            return
        }
        when (type) {
            "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> {
                emitNow()
                onTurnFinished()
                // the stored chat is the reference (ids, final texts, usage): reload it once the turn is over
                val id = sid
                backendScope.launch(worker) {
                    delay(400)
                    if (attachedId == id && !transcript.isTurnActive) reload(id)
                    scheduleRefresh()
                }
                return
            }
            "session.model.selected" -> OpenCodeTranscript.modelRef(d.o("model"))?.let { _selectedModelId.value = it }
            "session.agent.selected" -> d.str("agent")?.let { _agent.value = it }
        }
        if (changed) emitSoon()
    }

    private fun scheduleRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = backendScope.launch {
            delay(700)
            refreshSessions()
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
        _messages.value = transcript.toChatMessages(id)
        _pendingPermissions.value = transcript.pendingPermissions.values.toList()
        _isStreaming.value = transcript.isTurnActive
        _notice.value = when {
            transcript.compacting -> "Compacting the conversation…"
            else -> transcript.retryNotice
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sessionRules(mode: String, agent: String): JsonArray? {
        val base = rules(mode, agent)
        if (extraRules.isEmpty()) return base
        return JsonArray(base.orEmpty() + extraRules)
    }

    private fun defaultWorkspaceUri(): String {
        val path = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path.orEmpty()
        return if (path.isBlank()) "" else if (path.startsWith("file://")) path else "file://$path"
    }

    private fun workspacePath(conv: Conversation): String? = conv.workspaceUri.removePrefix("file://").takeIf { it.startsWith("/") }

    private fun modelRefJson(modelId: String): JsonObject? {
        val provider = modelId.substringBefore('/', "")
        val model = modelId.substringAfter('/', "")
        if (provider.isBlank() || model.isBlank()) return null
        return buildJsonObject { put("id", model); put("providerID", provider) }
    }

    companion object {
        private const val TAG = "OpenCodeBackend"
        /** Silence while waiting for the model before the notice shows, and before the turn is stopped. */
        private const val STALL_NOTICE_MS = 20_000L
        private const val STALL_MS = 90_000L
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "log", "json", "yaml", "yml", "toml", "xml", "html", "css", "js", "ts", "tsx", "jsx",
            "kt", "kts", "java", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "rb", "php", "sh", "bash", "zsh", "sql", "ini", "conf", "gradle", "swift", "dart"
        )
        private val random = SecureRandom()
        private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private var lastMs = 0L
        private var counter = 0L

        /**
         * An id in OpenCode's own format ("ses_" / "msg_" + 12 hex digits of time + 14 random characters), so ids made
         * by the app sort like OpenCode's: sessions newest first (descending), messages oldest first.
         */
        @Synchronized
        fun newId(prefix: String, descending: Boolean): String {
            val now = System.currentTimeMillis()
            if (now != lastMs) { lastMs = now; counter = 0 }
            counter++
            var value = now * 0x1000 + counter
            if (descending) value = value.inv()
            val hex = StringBuilder()
            for (i in 5 downTo 0) hex.append(String.format("%02x", (value shr (i * 8)) and 0xff))
            val rnd = StringBuilder()
            repeat(14) { rnd.append(BASE62[random.nextInt(BASE62.length)]) }
            return "${prefix}_$hex$rnd"
        }

        /**
         * Session permission rules for an approval mode. Session rules are checked after the agent's, so they win;
         * the plan agent keeps its "no edits" rule.
         */
        fun rules(mode: String, agent: String): JsonArray? {
            fun rule(action: String, effect: String, resource: String = "*") = buildJsonObject {
                put("action", action); put("resource", resource); put("effect", effect)
            }
            val plan = agent == "plan"
            return when (mode) {
                OpenCodePreferences.MODE_AUTO -> JsonArray(listOfNotNull(rule("*", "allow"), if (plan) rule("edit", "deny") else null))
                OpenCodePreferences.MODE_ASK -> JsonArray(listOfNotNull(
                    rule("shell", "ask"),
                    if (plan) null else rule("edit", "ask"),
                    rule("webfetch", "ask"),
                    rule("websearch", "ask"),
                    rule("external_directory", "ask")
                ))
                else -> null
            }
        }
    }
}
