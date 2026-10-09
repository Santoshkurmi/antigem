package com.example.gemini.data.agent.claude

import android.app.Application
import android.util.Base64
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.remote.SlashCommandItem
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Claude Code chat backend. The bridge runs one `claude` process per conversation; this class attaches to the
 * conversation's WebSocket, sends prompts / control messages, and maps stream-json events into the shared chat state
 * ([store]) through [ClaudeTranscript]. It also owns the per-chat controls (model, effort, thinking, permission mode)
 * and the live indicators (prompt cache, context window, plan limits).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val prefs: ClaudePreferences,
    private val client: ClaudeBridgeClient = ClaudeBridgeClient()
) : AgentChatBackend {

    override val kind = AgentKind.CLAUDE

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()
    // Claude keeps its own message / error state: the Antigravity backend writes the shared store and must never
    // touch a Claude chat (ChatViewModel shows the state of the agent that owns the chat on screen)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _startupStatus = MutableStateFlow<String?>(null)
    /** Set while a sent message waits for the claude process to boot (seconds on a phone). */
    val startupStatus: StateFlow<String?> = _startupStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isTurnActive = MutableStateFlow(false)
    /** A turn is in progress (including while waiting for an approval). */
    val isCurrentChatActivelyRunning: StateFlow<Boolean> = _isTurnActive.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    // ---- models & chat controls
    private val _modelInfos = MutableStateFlow<List<ClaudeModelInfo>>(emptyList())
    /** Models from the CLI's `initialize` (with supported effort levels). */
    val modelInfos: StateFlow<List<ClaudeModelInfo>> = _modelInfos.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    /** The CLI's models; empty until they are loaded (no placeholder). */
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    /** Why the model list could not be loaded (null while loading or after success). */
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    private val _selectedModelId = MutableStateFlow("default")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _activeModel = MutableStateFlow<String?>(null)
    /** Resolved model id the session is actually using (from the CLI). */
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    private val _effort = MutableStateFlow<String?>(null)
    /** null = the model's default effort. */
    val effort: StateFlow<String?> = _effort.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private val _permissionMode = MutableStateFlow(prefs.cachedDefaultMode())
    val permissionMode: StateFlow<String> = _permissionMode.asStateFlow()

    private val _fastModeState = MutableStateFlow("off")
    val fastModeState: StateFlow<String> = _fastModeState.asStateFlow()

    private val _outputStyles = MutableStateFlow<List<String>>(emptyList())
    val outputStyles: StateFlow<List<String>> = _outputStyles.asStateFlow()

    private val _slashCommands = MutableStateFlow<List<SlashCommandItem>>(emptyList())
    val slashCommands: StateFlow<List<SlashCommandItem>> = _slashCommands.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    // ---- live indicators
    private val _cacheInfo = MutableStateFlow<ClaudeTranscript.CacheInfo?>(null)
    val cacheInfo: StateFlow<ClaudeTranscript.CacheInfo?> = _cacheInfo.asStateFlow()

    private val _contextUsage = MutableStateFlow<ClaudeContextUsage?>(null)
    val contextUsage: StateFlow<ClaudeContextUsage?> = _contextUsage.asStateFlow()

    private val _rateLimits = MutableStateFlow<Map<String, ClaudeUnifiedWindow>>(emptyMap())
    val rateLimits: StateFlow<Map<String, ClaudeUnifiedWindow>> = _rateLimits.asStateFlow()

    private val _sessionCost = MutableStateFlow(0.0)
    val sessionCost: StateFlow<Double> = _sessionCost.asStateFlow()

    private val _needsLogin = MutableStateFlow(false)
    /** The last turn failed because the CLI is not signed in. */
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<ClaudeTranscript.PendingPermission>>(emptyList())
    /** Tool approvals and plan approvals waiting for the user (questions are answered on their card). */
    val pendingPermissions: StateFlow<List<ClaudeTranscript.PendingPermission>> = _pendingPermissions.asStateFlow()

    /** Called after a turn so plan usage can refresh. */
    var onTurnFinished: () -> Unit = {}

    // ---- everything below is only touched on [worker]
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val transcript = ClaudeTranscript()
    private var attachedId: String? = null
    private var socket: ClaudeSocket? = null
    private var socketJob: Job? = null
    private var emitJob: Job? = null
    private var lastSeq = -1L
    private var processLive = false
    private var pendingForkFrom: String? = null
    private var pendingResumeAt: String? = null
    private val outbox = ArrayDeque<String>()
    private val replies = HashMap<String, CompletableDeferred<ClaudeControlResponseInner>>()
    // controls changed in this chat; untouched ones are left to the user's settings.json defaults
    @Volatile private var modeTouched = false
    @Volatile private var thinkingTouched = false
    // the mode new chats start in: the bridge's default (Auto unless the user set one in settings.json)
    @Volatile private var defaultPermissionMode = prefs.cachedDefaultMode()
    private val localConversations = LinkedHashMap<String, Conversation>()
    // a sent message is waiting for the process the bridge spawns for it
    private var awaitingProcess = false
    // opening a chat whose process is live: its newest output is replayed from the bridge buffer up to this seq;
    // the chat is shown once (complete) when it arrives
    private var replayUntilSeq: Long? = null
    private var replayDeadline: Job? = null
    private var loopJob: Job? = null
    private val titleRefreshes = HashMap<String, Job>()

    /** Loads the session list and model info and keeps the list fresh (Claude Code is enabled). */
    fun start() {
        if (loopJob?.isActive == true) return
        prefs.cachedInfo()?.let { applyInfo(it) }
        loopJob = backendScope.launch {
            refreshInfo(force = false)
            while (isActive) {
                refreshSessions()
                // poll faster while a Claude chat is working so the sidebar's running state stays current
                delay(if (_conversations.value.any { it.isRunning }) 5_000 else 30_000)
            }
        }
    }

    /** Claude Code was turned off: stop polling and forget its chats. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        detach()
        _conversations.value = emptyList()
        synchronized(localConversations) { localConversations.clear() }
    }

    // ------------------------------------------------------------------ AgentChatBackend

    override fun startNewChat() {
        val id = UUID.randomUUID().toString()
        val conv = Conversation(
            id = id,
            title = "New Chat",
            modelId = _selectedModelId.value,
            sessionId = id,
            workspaceUri = defaultWorkspaceUri(),
            agent = AgentKind.CLAUDE
        )
        showConversation(conv, loading = false)
        _cacheInfo.value = null
        _contextUsage.value = null
        backendScope.launch(worker) {
            detachLocked()
            transcript.reset(id)
            attachedId = id
        }
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id }
            ?: Conversation(id = id, title = "Claude Chat", sessionId = id, agent = AgentKind.CLAUDE)
        showConversation(conv, loading = true)
        backendScope.launch(worker) {
            detachLocked()
            attachedId = id
            loadAndAttach(id)
        }
    }

    override fun sendMessage(content: String) {
        val atts = store.attachments.value
        if ((content.isBlank() && atts.isEmpty()) || _isTurnActive.value) return
        val conv = store.currentConversation.value ?: return
        if (conv.agent != AgentKind.CLAUDE) return
        store.attachments.value = emptyList()
        sendPrompt(conv, content.trim(), atts)
    }

    private fun sendPrompt(conv: Conversation, text: String, atts: List<ChatAttachment>) {
        val displayText = text.ifBlank { if (atts.any { it.isImage }) "Image" else "Attachment" }
        val title = if (conv.title == "New Chat" || conv.title.isBlank()) {
            val first = displayText.lines().firstOrNull { it.isNotBlank() } ?: "Chat"
            first.take(40) + if (first.length > 40) "..." else ""
        } else conv.title
        val updated = conv.copy(title = title, updatedAt = System.currentTimeMillis(), modelId = _selectedModelId.value)
        store.currentConversation.value = updated
        upsertLocal(updated)
        _isTurnActive.value = true
        _isStreaming.value = true
        _needsLogin.value = false

        val uuid = UUID.randomUUID().toString()
        backendScope.launch(worker) {
            if (attachedId != conv.id) {
                detachLocked()
                transcript.reset(conv.id)
                attachedId = conv.id
            }
            transcript.addLocalUserMessage(uuid, displayText, atts)
            if (!processLive) {
                awaitingProcess = true
                _startupStatus.value = "Starting Claude Code…"
            }
            emitNow()
            val frame = ClaudeJson.encodeToString(ClaudeUserMessage(uuid = uuid, message = ClaudeOutgoingMessage(buildBlocks(conv.id, text, atts))))
            ensureConnected(conv.id)
            send(frame)
        }
    }

    override fun stopStreaming() {
        backendScope.launch(worker) {
            if (socket == null) {
                transcript.endTurn()
                emitNow()
                return@launch
            }
            sendControl(ClaudeControlRequestBody(subtype = "interrupt"))
        }
    }

    override fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        synchronized(localConversations) { localConversations.remove(id) }
        backendScope.launch(worker) {
            if (attachedId == id) detachLocked()
            client.delete(id).onFailure { Log.w(TAG, "delete $id failed: ${it.message}") }
        }
        if (store.currentConversation.value?.id == id) startNewChat()
    }

    override fun forkConversation(id: String) {
        val source = _conversations.value.find { it.id == id } ?: return
        forkInto(source, cutAtUserUuid = null) {}
    }

    override fun retryConnections() {
        backendScope.launch(worker) {
            val id = attachedId ?: return@launch
            if (store.currentConversation.value?.id != id || pendingForkFrom != null) return@launch
            // a chat not on disk yet has no history to reload: just reconnect (its output is in the bridge buffer)
            if (isOnDisk(id)) loadAndAttach(id) else ensureConnected(id)
        }
        backendScope.launch { refreshSessions() }
    }

    override fun onAppForegrounded() {
        backendScope.launch { refreshSessions() }
        backendScope.launch(worker) {
            val id = attachedId ?: return@launch
            if (socketJob?.isActive != true && pendingForkFrom == null && isOnDisk(id)) loadAndAttach(id)
        }
    }

    /** The bridge lists the chat (its transcript exists); a chat only known locally has not been written yet. */
    private fun isOnDisk(id: String) =
        _conversations.value.any { it.id == id } && synchronized(localConversations) { id !in localConversations }

    /** Stops listening to the current chat (another agent's chat is being opened). */
    fun detach() {
        _isStreaming.value = false
        _isTurnActive.value = false
        _isLoadingConversation.value = false
        _pendingPermissions.value = emptyList()
        backendScope.launch(worker) { detachLocked() }
    }

    // ------------------------------------------------------------------ approvals, plans & questions

    fun approveTool(toolCall: ToolCall) = respondTool(toolCall.id) { p ->
        ClaudePermissionResult(behavior = "allow", updatedInput = p.input, toolUseID = p.toolUseId) to "RUNNING"
    }

    /** Allow and remember: applies the CLI's [suggestionIndex]-th permission suggestion (a rule, a directory, a mode). */
    fun approveToolAlways(toolUseId: String, suggestionIndex: Int) = respondTool(toolUseId) { p ->
        val suggestion = p.suggestions?.getOrNull(suggestionIndex)
        ClaudePermissionResult(
            behavior = "allow",
            updatedInput = p.input,
            updatedPermissions = suggestion?.let { JsonArray(listOf(it)) },
            toolUseID = p.toolUseId
        ) to "RUNNING"
    }

    fun rejectTool(toolCall: ToolCall, reason: String? = null) = rejectToolById(toolCall.id, reason)

    fun rejectToolById(toolUseId: String, reason: String?, interrupt: Boolean = false) = respondTool(toolUseId) { p ->
        val message = reason?.takeIf { it.isNotBlank() } ?: "User rejected this tool call."
        ClaudePermissionResult(behavior = "deny", message = message, interrupt = interrupt.takeIf { it }, toolUseID = p.toolUseId) to "REJECTED"
    }

    /** Plan approval: allow ExitPlanMode, then switch to [nextMode] ("acceptEdits", "default", "auto"). */
    fun approvePlan(toolUseId: String, nextMode: String) {
        respondTool(toolUseId) { p ->
            ClaudePermissionResult(behavior = "allow", updatedInput = p.input, toolUseID = p.toolUseId) to "RUNNING"
        }
        setPermissionMode(nextMode)
    }

    /** Plan rejected: Claude keeps planning with the feedback. */
    fun keepPlanning(toolUseId: String, feedback: String) = rejectToolById(
        toolUseId,
        feedback.ifBlank { "The user wants you to keep planning. Revise the plan before calling ExitPlanMode again." }
    )

    fun submitChoices(toolCall: ToolCall, responses: List<AskQuestionResponseItemDto>, summaryDisplay: String) {
        backendScope.launch(worker) {
            val p = transcript.pendingFor(toolCall.id) ?: return@launch
            val answers = buildJsonObject {
                for (r in responses) {
                    if (r.skipped == true) continue
                    val picked = r.selectedOptionIds.orEmpty().map { id ->
                        r.options.find { it.id == id }?.let { it.text.ifBlank { it.label } } ?: id
                    }
                    val value = (picked + listOfNotNull(r.writeInResponse?.takeIf { it.isNotBlank() })).joinToString(", ")
                    if (value.isNotBlank()) put(r.question, JsonPrimitive(value))
                }
            }
            val input = JsonObject(p.input + ("answers" to answers))
            respond(p.requestId, ClaudePermissionResult(behavior = "allow", updatedInput = input, toolUseID = p.toolUseId))
            transcript.markPermissionAnswered(p.toolUseId, "RUNNING", summaryDisplay)
            emitNow()
        }
    }

    fun skipChoices(toolCall: ToolCall) = respondTool(toolCall.id) { p ->
        ClaudePermissionResult(behavior = "deny", message = "The user skipped the question. Continue with your best judgement.", toolUseID = p.toolUseId) to "RUNNING"
    }

    fun cancelChoices(toolCall: ToolCall) = respondTool(toolCall.id) { p ->
        ClaudePermissionResult(behavior = "deny", message = "The user cancelled the question.", interrupt = true, toolUseID = p.toolUseId) to "REJECTED"
    }

    private fun respondTool(toolUseId: String, build: (ClaudeTranscript.PendingPermission) -> Pair<ClaudePermissionResult, String>) {
        backendScope.launch(worker) {
            val p = transcript.pendingFor(toolUseId) ?: return@launch
            val (result, status) = build(p)
            respond(p.requestId, result)
            transcript.markPermissionAnswered(p.toolUseId, status, result.message?.takeIf { status == "REJECTED" })
            emitNow()
        }
    }

    // ------------------------------------------------------------------ chat controls

    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        val conv = store.currentConversation.value
        if (conv != null && conv.agent == AgentKind.CLAUDE) store.currentConversation.value = conv.copy(modelId = modelId)
        // keep the effort valid for the new model
        val levels = _modelInfos.value.find { it.value == modelId }?.supportedEffortLevels.orEmpty()
        if (_effort.value != null && levels.isNotEmpty() && _effort.value !in levels) _effort.value = null
        liveOrSpawn(
            live = ClaudeControlRequestBody(subtype = "set_model", model = modelId),
            spawn = BridgeConfigFrame(model = modelId.takeIf { it != "default" })
        )
    }

    /** level: low | medium | high | xhigh | max, or null for the model default. */
    fun setEffort(level: String?) {
        _effort.value = level
        liveOrSpawn(
            live = ClaudeControlRequestBody(
                subtype = "apply_flag_settings",
                settings = buildJsonObject { put("effortLevel", level?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }
            ),
            spawn = BridgeConfigFrame(effort = level)
        )
    }

    fun setThinking(enabled: Boolean) {
        thinkingTouched = true
        _thinkingEnabled.value = enabled
        liveOrSpawn(
            live = ClaudeControlRequestBody(
                subtype = "set_max_thinking_tokens",
                max_thinking_tokens = if (enabled) 31999 else 0,
                thinking_display = if (enabled) "summarized" else null
            ),
            spawn = BridgeConfigFrame(thinking = if (enabled) "on" else "off")
        )
    }

    /** default (Manual) | acceptEdits | plan | auto | dontAsk */
    fun setPermissionMode(mode: String) {
        modeTouched = true
        _permissionMode.value = mode
        liveOrSpawn(
            live = ClaudeControlRequestBody(subtype = "set_permission_mode", mode = mode),
            spawn = BridgeConfigFrame(permission_mode = mode)
        )
    }

    /** Applies a control live when the process runs, otherwise stores it as a spawn option (no process is started). */
    private fun liveOrSpawn(live: ClaudeControlRequestBody, spawn: BridgeConfigFrame) {
        backendScope.launch(worker) {
            if (socket == null) return@launch
            if (processLive) sendControl(live) else send(ClaudeJson.encodeToString(spawn))
        }
    }

    // ------------------------------------------------------------------ edit / regenerate / rewind / export

    /**
     * Edits the prompt with [userMessageId]: opens a fork of this chat that ends right before that prompt (the
     * original chat is kept), and returns the prompt text for the input box. With [restoreCode], files changed
     * since that prompt are restored first.
     */
    fun editMessage(userMessageId: String, restoreCode: Boolean, onRestored: (String) -> Unit) {
        val conv = store.currentConversation.value ?: return
        val userUuid = userMessageId.removePrefix("user_")
        backendScope.launch(worker) {
            val text = transcript.userText(userUuid).orEmpty()
            if (restoreCode) rewindFilesLocked(userUuid)
            withContext(Dispatchers.Main) {
                forkInto(conv, cutAtUserUuid = userUuid) { onRestored(text) }
            }
        }
    }

    /** Regenerates the reply [assistantMessageId] in a fork that ends before its prompt, re-sending that prompt. */
    fun regenerate(assistantMessageId: String) {
        val conv = store.currentConversation.value ?: return
        backendScope.launch(worker) {
            val userUuid = transcript.userBefore(assistantMessageId) ?: return@launch
            val text = transcript.userText(userUuid).orEmpty()
            withContext(Dispatchers.Main) {
                forkInto(conv, cutAtUserUuid = userUuid) { fork -> sendPrompt(fork, text, emptyList()) }
            }
        }
    }

    /**
     * Opens a new conversation forked from [source]. With [cutAtUserUuid], history ends right before that prompt
     * (`--resume-session-at`); the fork's process starts with the first message sent in it.
     */
    private fun forkInto(source: Conversation, cutAtUserUuid: String?, then: (Conversation) -> Unit) {
        val newId = UUID.randomUUID().toString()
        val fork = source.copy(
            id = newId,
            sessionId = newId,
            title = if (cutAtUserUuid == null) "${source.title} (fork)" else source.title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isRunning = false,
            agent = AgentKind.CLAUDE
        )
        showConversation(fork, loading = true)
        backendScope.launch(worker) {
            val parent = cutAtUserUuid?.let { transcript.parentOfUser(it) }
            detachLocked()
            attachedId = newId
            if (cutAtUserUuid != null && parent == null) {
                // editing the very first prompt: nothing to keep, start fresh in the same folder
                transcript.reset(newId)
                pendingForkFrom = null
                pendingResumeAt = null
            } else {
                pendingForkFrom = source.id
                pendingResumeAt = parent
                client.history(source.id)
                    .onSuccess { h ->
                        transcript.reset(newId)
                        for (entry in h.entries) {
                            if (cutAtUserUuid != null && entry["uuid"]?.jsonPrimitive?.contentOrNull == cutAtUserUuid) break
                            transcript.applyHistory(entry)
                        }
                        transcript.finishHistory()
                    }
                    .onFailure { _conversationError.value = "Cannot load the chat to fork: ${it.message}" }
            }
            emitNow()
            _isLoadingConversation.value = false
            withContext(Dispatchers.Main) { then(store.currentConversation.value ?: fork) }
        }
    }

    /** Restores files changed since [userUuid] (Claude's file checkpoints). Returns the CLI's summary. */
    fun rewindFiles(userMessageId: String, dryRun: Boolean, onResult: (ClaudeRewindResult?) -> Unit) {
        backendScope.launch(worker) {
            val r = rewindFilesLocked(userMessageId.removePrefix("user_"), dryRun)
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    private suspend fun rewindFilesLocked(userUuid: String, dryRun: Boolean = false): ClaudeRewindResult? {
        val id = attachedId ?: return null
        ensureConnected(id)
        val reply = request(ClaudeControlRequestBody(subtype = "rewind_files", user_message_id = userUuid, dry_run = dryRun)) ?: return null
        return reply.response?.let { runCatching { ClaudeJson.decodeFromJsonElement<ClaudeRewindResult>(it) }.getOrNull() }
            ?: ClaudeRewindResult(error = reply.error)
    }

    /** The conversation as plain text (the CLI's /export format). */
    fun exportConversation(onResult: (ClaudeExportResult?) -> Unit) {
        backendScope.launch(worker) {
            val id = attachedId
            val res = if (id == null) null else {
                ensureConnected(id)
                request(ClaudeControlRequestBody(subtype = "export_conversation"))?.response
                    ?.let { runCatching { ClaudeJson.decodeFromJsonElement<ClaudeExportResult>(it) }.getOrNull() }
            }
            withContext(Dispatchers.Main) { onResult(res) }
        }
    }

    /** Loads a Claude conversation's messages without opening it (sidebar export / share). */
    suspend fun loadMessages(conversationId: String): List<ChatMessage> {
        val h = client.history(conversationId).getOrNull() ?: return emptyList()
        val t = ClaudeTranscript().apply { reset(conversationId) }
        h.entries.forEach { t.applyHistory(it) }
        t.finishHistory()
        return t.toChatMessages()
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim().ifBlank { return }
        _conversations.value = _conversations.value.map { if (it.id == id) it.copy(title = clean) else it }
        store.currentConversation.value?.let { if (it.id == id) store.currentConversation.value = it.copy(title = clean) }
        backendScope.launch {
            client.rename(id, clean).onFailure { Log.w(TAG, "rename failed: ${it.message}") }
            refreshSessions()
        }
    }

    fun refreshContextUsage() {
        backendScope.launch(worker) {
            if (!processLive || socket == null) return@launch
            request(ClaudeControlRequestBody(subtype = "get_context_usage"))?.response?.let {
                _contextUsage.value = runCatching { ClaudeJson.decodeFromJsonElement<ClaudeContextUsage>(it) }.getOrNull()
            }
        }
    }

    // ------------------------------------------------------------------ models / sessions / info

    fun refreshInfo(force: Boolean = true) {
        backendScope.launch {
            _isRefreshingModels.value = true
            client.info(refresh = force)
                .onSuccess { resp ->
                    val info = resp.info
                    if (resp.success && info != null) {
                        applyInfo(info)
                        prefs.saveInfo(info)
                        _modelsError.value = null
                        resp.default_mode?.let { mode ->
                            defaultPermissionMode = mode
                            prefs.saveDefaultMode(mode)
                            if (!processLive && !modeTouched) _permissionMode.value = mode
                        }
                    } else {
                        _modelsError.value = resp.error ?: "Claude Code did not return its models"
                    }
                }
                .onFailure { _modelsError.value = it.message ?: "Cannot reach the bridge" }
            _isRefreshingModels.value = false
        }
    }

    /** Models, slash commands and defaults from the CLI's `initialize` (live or cached). */
    private fun applyInfo(info: ClaudeInitializeInfo) {
        if (info.models.isNotEmpty()) {
            _modelInfos.value = info.models
            _availableModels.value = info.models.map { m ->
                // "Default (recommended)" + "Opus 5.5 · Best for…" → "Default (Opus 5.5)"
                val resolved = m.description.substringBefore(" · ", "").takeIf { m.value == "default" && it.isNotBlank() }
                AiModel(
                    id = m.value,
                    displayName = resolved?.let { "Default ($it)" } ?: m.displayName.ifBlank { m.value },
                    family = com.example.gemini.domain.model.ModelFamily.CLAUDE,
                    description = m.description,
                    key = m.value,
                    baseName = m.displayName.ifBlank { m.value }
                )
            }
        }
        _slashCommands.value = info.commands
            .filter { it.name !in HIDDEN_COMMANDS && !it.description.startsWith("(removed)") }
            .map { c ->
                val appDescription = APP_COMMANDS[c.name]
                SlashCommandItem(
                    name = c.name,
                    command = "/${c.name}",
                    description = appDescription ?: (c.description + c.argumentHint.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()),
                    type = when {
                        appDescription != null -> "app"
                        c.builtin && !c.name.contains(':') -> "command"
                        else -> "skill"
                    }
                )
            }
        _outputStyles.value = info.available_output_styles
        info.fast_mode_state?.let { _fastModeState.value = it }
    }

    /**
     * A slash command the app answers itself, or null to send [text] to Claude. Commands with arguments that only
     * change a chat control (`/model opus`, `/effort high`, `/rename x`) are applied through the app's controls so
     * the chips stay in sync.
     */
    fun appCommand(text: String): ClaudeAppCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val name = trimmed.removePrefix("/").substringBefore(' ').lowercase()
        val arg = trimmed.substringAfter(' ', "").trim()
        return when (name) {
            "usage", "cost" -> ClaudeAppCommand.Usage
            "context" -> ClaudeAppCommand.Context
            "mcp" -> if (arg.isBlank()) ClaudeAppCommand.Mcp else null
            "config" -> if (arg.isBlank()) ClaudeAppCommand.Settings else null
            "clear", "new" -> ClaudeAppCommand.NewChat
            "model" -> if (arg.isBlank()) ClaudeAppCommand.ModelPicker else {
                selectModel(arg)
                ClaudeAppCommand.Done("Model set to $arg")
            }
            "effort" -> when {
                arg.isBlank() -> ClaudeAppCommand.ModelPicker
                arg.equals("auto", true) || arg.equals("default", true) -> {
                    setEffort(null)
                    ClaudeAppCommand.Done("Effort set to the model default")
                }
                else -> {
                    setEffort(arg.lowercase())
                    ClaudeAppCommand.Done("Effort set to ${effortName(arg)}")
                }
            }
            "rename" -> if (arg.isBlank()) null else {
                store.currentConversation.value?.id?.let { renameConversation(it, arg) }
                ClaudeAppCommand.Done("Chat renamed")
            }
            else -> null
        }
    }

    private fun effortName(level: String) = if (level.equals("xhigh", true)) "extra high" else level.lowercase()

    /** The CLI writes a chat's AI title shortly after the first reply: look again a few times. */
    private fun scheduleTitleRefresh() {
        val id = attachedId ?: return
        titleRefreshes[id]?.cancel()
        titleRefreshes[id] = backendScope.launch {
            for (wait in longArrayOf(1_500, 6_000, 15_000)) {
                delay(wait)
                refreshSessions()
            }
        }
    }

    suspend fun refreshSessions() {
        client.listSessions().onSuccess { resp ->
            val fromBridge = resp.sessions.map { s ->
                Conversation(
                    id = s.id,
                    title = s.title.ifBlank { "Claude Chat" },
                    sessionId = s.id,
                    createdAt = s.created_at,
                    updatedAt = s.updated_at,
                    isRunning = s.busy,
                    workspaceUri = if (s.cwd.isNotBlank()) "file://${s.cwd}" else "",
                    agent = AgentKind.CLAUDE
                )
            }
            val ids = fromBridge.map { it.id }.toSet()
            val locals = synchronized(localConversations) {
                localConversations.keys.removeAll(ids)
                localConversations.values.toList()
            }
            val openId = store.currentConversation.value?.id
            val openRunning = _isTurnActive.value
            _conversations.value = (locals + fromBridge)
                .map { if (it.id == openId) it.copy(isRunning = it.isRunning || openRunning) else it }
                .sortedByDescending { it.updatedAt }
            val cur = store.currentConversation.value
            if (cur != null && cur.agent == AgentKind.CLAUDE) {
                fromBridge.find { it.id == cur.id }?.let { fresh ->
                    if (fresh.title != cur.title && fresh.title.isNotBlank()) {
                        store.currentConversation.value = cur.copy(title = fresh.title)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "session list failed: ${it.message}") }
    }

    // ------------------------------------------------------------------ socket

    private fun showConversation(conv: Conversation, loading: Boolean) {
        modeTouched = false
        thinkingTouched = false
        _permissionMode.value = defaultPermissionMode
        _thinkingEnabled.value = true
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
        _isLoadingConversation.value = loading
        _isStreaming.value = false
        _isTurnActive.value = false
        _pendingPermissions.value = emptyList()
        _needsLogin.value = false
    }

    private suspend fun loadAndAttach(id: String) {
        _isLoadingConversation.value = true
        val res = client.history(id)
        if (attachedId != id) return
        res.onSuccess { h ->
            transcript.reset(id)
            h.entries.forEach { transcript.applyHistory(it) }
            transcript.finishHistory()
            lastSeq = h.state.buffer_start_seq - 1
            processLive = h.state.live
            val newest = h.state.next_seq - 1
            if (h.state.live && newest >= h.state.buffer_start_seq) {
                // the rest of the chat is in the bridge buffer: keep the loading state until it is replayed
                replayUntilSeq = newest
                replayDeadline?.cancel()
                // safety net only: the replay normally ends within a moment
                replayDeadline = backendScope.launch(worker) {
                    delay(3_000)
                    if (attachedId == id) finishReplay()
                }
            } else {
                finishReplay()
            }
            connect(id, h.state.buffer_start_seq)
        }.onFailure {
            if (it.isBridgeUnreachable() && BridgeStartup.isStartingUp()) {
                // the app just started and the local server is not up yet: keep loading and try again
                backendScope.launch(worker) {
                    delay(2_000)
                    if (attachedId == id) loadAndAttach(id)
                }
                return
            }
            _isLoadingConversation.value = false
            _conversationError.value = "Cannot load Claude chat: ${it.message}"
        }
    }

    private fun finishReplay() {
        replayUntilSeq = null
        replayDeadline?.cancel()
        replayDeadline = null
        emitNow()
        _isLoadingConversation.value = false
    }

    private fun ensureConnected(id: String) {
        if (socketJob?.isActive == true) return
        connect(id, if (lastSeq >= 0) lastSeq + 1 else -1)
    }

    private fun connect(id: String, since: Long) {
        socketJob?.cancel()
        socketJob = backendScope.launch(worker) {
            var nextSince = since
            while (isActive && attachedId == id) {
                client.connect(id, nextSince, spawnConfig()).collect { ev ->
                    when (ev) {
                        is ClaudeSocketEvent.Opened -> {
                            socket = ev.socket
                            while (outbox.isNotEmpty()) ev.socket.send(outbox.removeFirst())
                        }
                        is ClaudeSocketEvent.Frame -> if (attachedId == id) handleFrame(ev.frame)
                        is ClaudeSocketEvent.Closed -> {
                            socket = null
                            if (ev.error != null) Log.w(TAG, "socket closed: ${ev.error}")
                        }
                    }
                }
                socket = null
                if (attachedId != id) break
                delay(1500)
                nextSince = if (lastSeq >= 0) lastSeq + 1 else -1
            }
        }
    }

    private fun spawnConfig(): BridgeConfigFrame {
        val conv = store.currentConversation.value
        val cwd = conv?.workspaceUri?.removePrefix("file://")?.takeIf { it.isNotBlank() }
        return BridgeConfigFrame(
            cwd = cwd,
            model = _selectedModelId.value.takeIf { it.isNotBlank() && it != "default" },
            permission_mode = _permissionMode.value.takeIf { modeTouched },
            effort = _effort.value,
            thinking = if (!thinkingTouched) null else if (_thinkingEnabled.value) "on" else "off",
            fork_from = pendingForkFrom,
            resume_session_at = pendingResumeAt
        )
    }

    private fun handleFrame(frame: BridgeFrame) {
        when (frame.type) {
            "bridge_line" -> {
                val seq = frame.seq ?: return
                if (seq <= lastSeq) return
                lastSeq = seq
                val data = frame.data
                val isReply = data?.get("type")?.jsonPrimitive?.contentOrNull == "control_response"
                if (isReply) deliverReply(data!!)
                val type = if (data != null && !isReply) transcript.applyLive(data) else ""
                val replayTarget = replayUntilSeq
                if (replayTarget != null) {
                    // old output being replayed while the chat opens: no side effects, show it once at the end.
                    // Every buffered line counts, control replies included (often the newest line).
                    if (seq >= replayTarget) finishReplay()
                    return
                }
                if (data == null || isReply) return
                if (awaitingProcess && type != "stream_event" && type != "rate_limit_event" && type != "command_lifecycle") {
                    awaitingProcess = false
                    _startupStatus.value = null
                }
                when (type) {
                    "result" -> {
                        scheduleTitleRefresh()
                        onTurnFinished()
                        refreshContextUsage()
                    }
                    "system" -> when (data["subtype"]?.jsonPrimitive?.contentOrNull) {
                        "session_title_changed" -> {
                            val title = data["title"]?.jsonPrimitive?.contentOrNull
                            val cur = store.currentConversation.value
                            if (!title.isNullOrBlank() && cur != null && cur.id == attachedId) {
                                store.currentConversation.value = cur.copy(title = title)
                                _conversations.value = _conversations.value.map { if (it.id == cur.id) it.copy(title = title) else it }
                                synchronized(localConversations) { localConversations[cur.id]?.let { localConversations[cur.id] = it.copy(title = title) } }
                            }
                        }
                        "init" -> {
                            processLive = true
                            transcript.permissionMode?.let { _permissionMode.value = it }
                        }
                        "status" -> transcript.permissionMode?.let { _permissionMode.value = it }
                    }
                }
                if (type == "stream_event") scheduleEmit() else emitNow()
            }
            "bridge_state" -> {
                val st = frame.state ?: return
                processLive = st.live
                when (frame.event) {
                    "attached" -> {
                        if (lastSeq >= 0 && lastSeq + 1 < st.buffer_start_seq) {
                            // output was dropped from the buffer while we were away: reload from the transcript
                            // (a chat not written to disk yet keeps what it shows)
                            val id = attachedId
                            if (id != null && isOnDisk(id)) {
                                backendScope.launch(worker) { loadAndAttach(id) }
                                return
                            }
                        }
                        // no process and nothing sent on this connection: a turn shown as running is over (the
                        // process exited while we were away). A just-sent message starts the process itself.
                        if (!st.live && transcript.isTurnActive && !awaitingProcess) transcript.endTurn()
                        if (replayUntilSeq != null && st.next_seq - 1 <= lastSeq) finishReplay()
                    }
                    "spawned" -> {
                        pendingForkFrom = null
                        pendingResumeAt = null
                        if (awaitingProcess) _startupStatus.value = "Claude Code is starting…"
                    }
                    "exited" -> {
                        awaitingProcess = false
                        _startupStatus.value = null
                        if (replayUntilSeq != null) finishReplay()
                        failReplies("Claude process exited")
                        val err = frame.error.orEmpty()
                        if (transcript.isTurnActive || (frame.exit_code ?: 0) != 0) {
                            if ((frame.exit_code ?: 0) != 0) {
                                transcript.addError("Claude Code stopped", err.ifBlank { "The claude process exited with code ${frame.exit_code}." })
                            }
                            transcript.endTurn()
                        }
                    }
                }
                emitNow()
            }
            "bridge_error" -> {
                val err = frame.error.orEmpty()
                awaitingProcess = false
                _startupStatus.value = null
                if (replayUntilSeq != null) finishReplay()
                failReplies(err)
                transcript.addError(
                    if (err.contains("not found", ignoreCase = true)) "Claude Code is not installed" else "Claude Code unavailable",
                    if (err.contains("not found", ignoreCase = true)) "$err\n\nInstall it from Settings → Claude Code → Account & CLI." else err
                )
                transcript.endTurn()
                emitNow()
            }
        }
    }

    private fun deliverReply(data: JsonObject) {
        val ev = runCatching { ClaudeJson.decodeFromJsonElement<ClaudeControlResponseEvent>(data) }.getOrNull() ?: return
        replies.remove(ev.response.request_id)?.complete(ev.response)
    }

    private fun failReplies(error: String) {
        replies.values.forEach { it.complete(ClaudeControlResponseInner(subtype = "error", error = error)) }
        replies.clear()
    }

    /** Sends a control request and waits for its reply (null on timeout). Starts the process if needed. */
    private suspend fun request(body: ClaudeControlRequestBody, timeoutMs: Long = 30_000): ClaudeControlResponseInner? {
        val id = "app_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<ClaudeControlResponseInner>()
        replies[id] = deferred
        send(ClaudeJson.encodeToString(ClaudeControlRequest(request_id = id, request = body)))
        // the reply arrives on this same worker: wait without blocking it
        val result = withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        replies.remove(id)
        return result
    }

    private fun send(text: String) {
        val s = socket
        if (s == null || !s.send(text)) outbox.addLast(text)
    }

    private fun sendControl(body: ClaudeControlRequestBody) {
        send(ClaudeJson.encodeToString(ClaudeControlRequest(request_id = "app_${UUID.randomUUID()}", request = body)))
    }

    private fun respond(requestId: String, result: ClaudePermissionResult) {
        send(ClaudeJson.encodeToString(ClaudeControlResponse(ClaudeControlResponseBody(request_id = requestId, response = result))))
    }

    private fun detachLocked() {
        socketJob?.cancel()
        socketJob = null
        socket?.close()
        socket = null
        emitJob?.cancel()
        emitJob = null
        outbox.clear()
        failReplies("chat closed")
        attachedId = null
        lastSeq = -1
        processLive = false
        pendingForkFrom = null
        pendingResumeAt = null
        awaitingProcess = false
        _startupStatus.value = null
        replayUntilSeq = null
        replayDeadline?.cancel()
        replayDeadline = null
    }

    private fun scheduleEmit() {
        if (emitJob?.isActive == true) return
        emitJob = backendScope.launch(worker) {
            delay(40)
            emitNow()
        }
    }

    private fun emitNow() {
        emitJob?.cancel()
        emitJob = null
        if (replayUntilSeq != null) return
        val id = attachedId ?: return
        if (store.currentConversation.value?.id != id) return
        val msgs = transcript.toChatMessages()
        _messages.value = msgs
        val active = transcript.isTurnActive
        _isTurnActive.value = active
        if (_conversations.value.any { it.id == id && it.isRunning != active }) {
            _conversations.value = _conversations.value.map { if (it.id == id) it.copy(isRunning = active) else it }
        }
        _isStreaming.value = active && !transcript.hasPending()
        _pendingPermissions.value = transcript.pendingList().filter { it.toolName != "AskUserQuestion" }
        _cacheInfo.value = transcript.cacheInfo
        if (transcript.rateLimits.isNotEmpty()) _rateLimits.value = transcript.rateLimits
        if (transcript.sessionCostUsd > 0) _sessionCost.value = transcript.sessionCostUsd
        _needsLogin.value = transcript.needsLogin
        transcript.sessionModel?.let { _activeModel.value = it }
        com.example.gemini.ui.chat.ChatFeedCache.prewarm(msgs)
    }

    private fun upsertLocal(conv: Conversation) {
        val inBridge = _conversations.value.any { it.id == conv.id }
        if (!inBridge) synchronized(localConversations) { localConversations[conv.id] = conv }
        _conversations.value = (listOf(conv) + _conversations.value.filter { it.id != conv.id }).sortedByDescending { it.updatedAt }
    }

    private fun defaultWorkspaceUri(): String {
        val path = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path.orEmpty()
        return if (path.isBlank()) "" else if (path.startsWith("file://")) path else "file://$path"
    }

    /**
     * The prompt's content blocks. Images small enough go inline (Claude sees them directly); every other file (and
     * bigger images) is saved on the bridge for this chat and listed by path, so Claude reads it with its tools and
     * the chat can show it again later. If saving fails, PDFs and text files still go inline.
     */
    private suspend fun buildBlocks(sessionId: String, text: String, atts: List<ChatAttachment>): List<ClaudeContentBlock> {
        val blocks = mutableListOf<ClaudeContentBlock>()
        val notes = mutableListOf<String>()
        val saved = mutableListOf<ClaudeAttachments.SavedFile>()
        for (a in atts) {
            val b64 = a.base64
            val mime = a.mimeType?.lowercase().orEmpty()
            if (b64.isNullOrBlank()) {
                notes.add("[Attachment ${a.name} could not be read]")
                continue
            }
            if (mime in IMAGE_TYPES && b64.length * 3L / 4 <= INLINE_IMAGE_BYTES) {
                blocks.add(ClaudeContentBlock(type = "image", source = ClaudeBlockSource("base64", mime, b64)))
                continue
            }
            val result = client.saveAttachment(sessionId, a.name, mime.ifBlank { "application/octet-stream" }, b64).getOrNull()
            val path = result?.path
            if (result?.success == true && path != null) {
                saved.add(ClaudeAttachments.SavedFile(path, result.mime_type ?: mime, result.size))
                continue
            }
            Log.w(TAG, "attachment ${a.name} not saved: ${result?.error}")
            when {
                mime in IMAGE_TYPES -> blocks.add(ClaudeContentBlock(type = "image", source = ClaudeBlockSource("base64", mime, b64)))
                mime == "application/pdf" -> blocks.add(ClaudeContentBlock(type = "document", title = a.name, source = ClaudeBlockSource("base64", mime, b64)))
                isTextLike(mime, a.name) -> {
                    val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    if (decoded != null) {
                        blocks.add(ClaudeContentBlock(type = "document", title = a.name, source = ClaudeBlockSource("text", "text/plain", decoded)))
                    } else notes.add("[Attachment ${a.name} could not be decoded]")
                }
                else -> notes.add("[Attachment ${a.name} could not be saved for Claude: ${result?.error ?: "the bridge did not answer"}]")
            }
        }
        val fullText = (notes + listOf(text) + listOfNotNull(saved.takeIf { it.isNotEmpty() }?.let { ClaudeAttachments.block(it) }))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (fullText.isNotBlank() || blocks.isEmpty()) blocks.add(ClaudeContentBlock(type = "text", text = fullText.ifBlank { " " }))
        return blocks
    }

    private fun isTextLike(mime: String, name: String): Boolean {
        if (mime.startsWith("text/") || mime in TEXT_MIME_TYPES) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    companion object {
        private const val TAG = "ClaudeChatBackend"

        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        /** Larger images are saved as files (the API takes inline images up to about 5 MB). */
        private const val INLINE_IMAGE_BYTES = 3_500_000L
        private val TEXT_MIME_TYPES = setOf(
            "application/json", "application/xml", "application/javascript", "application/x-sh",
            "application/x-yaml", "application/toml", "application/sql"
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "toml", "csv", "log", "kt", "kts", "java", "py", "js", "ts",
            "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "rb", "php", "sh", "bash", "zsh",
            "html", "css", "scss", "sql", "gradle", "properties", "ini", "conf", "env", "dart", "lua", "proto"
        )

        /** Built-in commands whose terminal view the app replaces with its own screen (see [appCommand]). */
        val APP_COMMANDS = mapOf(
            "usage" to "Plan usage limits and this chat's cost",
            "context" to "Context window usage",
            "model" to "Choose the model",
            "effort" to "Set the effort level",
            "mcp" to "MCP servers",
            "config" to "Claude Code settings",
            "clear" to "Start a new chat"
        )

        /** Built-ins that do nothing useful in the app (terminal-only views, internal or removed commands). */
        private val HIDDEN_COMMANDS = setOf(
            "color", "focus", "heapdump", "__remote-workflow", "workflow-launch-exec", "extra-usage", "agents", "import"
        )

        /** Permission modes in the order the mode menu shows them. */
        val PERMISSION_MODES = listOf(
            "default" to "Manual",
            "acceptEdits" to "Accept edits",
            "plan" to "Plan",
            "auto" to "Auto",
            "dontAsk" to "Don't ask"
        )
    }
}
