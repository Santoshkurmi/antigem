package com.example.gemini.data.jarvis

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.ClaudeBridgeClient
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Jarvis as a manager: work the user hands over ("build the release and tell me when it's done") runs as a task in its
 * own chat. Every Claude task gets its own backend that stays attached to that chat, so its approvals, questions and
 * end are seen live even while other chats are on screen; Antigravity tasks are watched through the hub. Changes of a
 * task (finished, failed, stuck) are sent to [JarvisController.onTaskEvent], which tells the user or the Jarvis model.
 */
object JarvisTasks {
    private const val TAG = "JarvisTasks"
    private const val KEEP = 30
    /** A task that never showed as running within this time after a message is taken as finished. */
    private const val START_GRACE_MS = 25_000L

    enum class State(val label: String) {
        STARTING("starting"), RUNNING("running"), NEEDS_APPROVAL("waiting for approval"), NEEDS_ANSWER("asking a question"),
        STALLED("no progress"), DONE("done"), FAILED("failed"), STOPPED("stopped");

        val active: Boolean get() = this == STARTING || this == RUNNING || this == NEEDS_APPROVAL || this == NEEDS_ANSWER || this == STALLED
        val needsUser: Boolean get() = this == NEEDS_APPROVAL || this == NEEDS_ANSWER
    }

    data class Task(
        val id: String,
        val agent: AgentKind,
        val title: String,
        val goal: String,
        val project: String = "",
        val state: State = State.STARTING,
        val createdAt: Long = System.currentTimeMillis(),
        /** When the current message was sent (the start grace counts from here). */
        val turnAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        /** Last time the chat visibly moved (new text, a tool started or finished). */
        val progressAt: Long = System.currentTimeMillis(),
        val seenRunning: Boolean = false,
        val followUps: Int = 0,
        /** One line about the latest change, for the panel ("Finished", "Wants to run: npm test"). */
        val note: String = "",
        /** The task's last reply (cut), once it finished. */
        val result: String = "",
        /** The current state was already reported (no repeat). */
        val reported: Boolean = true,
        /** An existing chat the user named, not one Jarvis started. */
        val adopted: Boolean = false,
        /** The Jarvis manager that started it and gets its news: [JarvisManagers.VOICE] or a Jarvis text chat's id. */
        val owner: String = JarvisManagers.VOICE,
        /** The manager's team that works on it (empty: none). */
        val team: String = ""
    ) {
        val active: Boolean get() = state.active
    }

    private lateinit var app: Application
    private var started = false
    private var prefs: android.content.SharedPreferences? = null

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    /** Newest first. */
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _anyActive = MutableStateFlow(false)
    /** Some task is working (keeps the screen on, shows on the orb). */
    val anyActive: StateFlow<Boolean> = _anyActive.asStateFlow()

    /** Claude task chats → their own backend, attached to that chat. */
    private val claudeBackends = ConcurrentHashMap<String, ClaudeChatBackend>()
    /** OpenCode task chats → their own backend (follows that chat's events), like the Claude ones. */
    private val openCodeBackends = ConcurrentHashMap<String, com.example.gemini.data.agent.opencode.OpenCodeChatBackend>()
    private val attachedAt = ConcurrentHashMap<String, Long>()
    private val progressSig = ConcurrentHashMap<String, String>()
    /** Approvals waiting in Antigravity chats that are not on screen (tasks and the Jarvis chat), from the hub. */
    private val agyPending = ConcurrentHashMap<String, List<Pair<ToolCall, String>>>()
    private val agyCheckedAt = ConcurrentHashMap<String, Long>()
    private val hub by lazy { AgyHubClient() }
    private lateinit var scope: CoroutineScope

    private val vm get() = JarvisController.vm

    fun start(application: Application, s: CoroutineScope) {
        if (started) return
        started = true
        app = application
        scope = s
        prefs = application.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE)
        load()
        scope.launch { loop() }
    }

    /** The task with [id], or the one whose id starts with it (models often shorten ids; at least 4 characters). */
    fun find(id: String?): Task? {
        val i = id?.trim().orEmpty()
        if (i.isEmpty()) return null
        val list = _tasks.value
        return list.firstOrNull { it.id == i } ?: if (i.length >= 4) list.firstOrNull { it.id.startsWith(i) } else null
    }
    fun isTask(id: String?): Boolean = id != null && _tasks.value.any { it.id == id }
    fun activeTasks(): List<Task> = _tasks.value.filter { it.active }
    /** The tasks of one Jarvis manager (newest first); a task whose Jarvis chat is gone belongs to the voice chat. */
    fun ownedBy(owner: String): List<Task> = _tasks.value.filter { JarvisManagers.ownerOrVoice(it.owner) == owner }

    /** Task backends Jarvis can read approvals and questions from. */
    fun claudeTaskBackends(): List<Pair<Task, ClaudeChatBackend>> =
        _tasks.value.filter { it.agent == AgentKind.CLAUDE }.mapNotNull { t -> claudeBackends[t.id]?.let { t to it } }

    fun backendOf(id: String): ClaudeChatBackend? = claudeBackends[id]

    /** OpenCode task backends Jarvis reads approvals from. */
    fun openCodeTaskBackends(): List<Pair<Task, com.example.gemini.data.agent.opencode.OpenCodeChatBackend>> =
        _tasks.value.filter { it.agent == AgentKind.OPENCODE }.mapNotNull { t -> openCodeBackends[t.id]?.let { t to it } }

    fun openCodeBackendOf(id: String): com.example.gemini.data.agent.opencode.OpenCodeChatBackend? = openCodeBackends[id]

    // ------------------------------------------------------------------ starting and steering

    private fun newClaudeBackend() = ClaudeChatBackend(app, scope, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())

    /**
     * A backend for one OpenCode task chat. It never changes the user's OpenCode defaults, and OpenCode's question form
     * is denied: nobody sees a task's chat, so the agent asks in its reply instead (Jarvis reads that out).
     */
    private fun newOpenCodeBackend() = com.example.gemini.data.agent.opencode.OpenCodeChatBackend(
        app, scope, ChatSessionStore(), vm.openCodePrefs, com.example.gemini.data.agent.opencode.OpenCodeBridgeClient(),
        rememberChoices = false,
        extraRules = listOf(kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("question"))
            put("resource", kotlinx.serialization.json.JsonPrimitive("*"))
            put("effect", kotlinx.serialization.json.JsonPrimitive("deny"))
        })
    ).also { it.start() }

    /**
     * Starts [goal] in a new chat of [agent]. [projectPath] null = the active project. [owner] gets the task's news,
     * [brief] goes hidden in front of the goal (how a sub-agent of Jarvis works, the team's role). Returns the task.
     */
    suspend fun start(
        agent: AgentKind, goal: String, title: String?, modelId: String?, projectPath: String?,
        owner: String = JarvisManagers.VOICE, team: String = "", brief: String? = null
    ): Task {
        val text = goal.trim()
        require(text.isNotEmpty()) { "The task needs a goal." }
        val prompt = (brief?.takeIf { it.isNotBlank() }?.let { PromptExpansion.hidden(it) } ?: "") + PromptExpansion.expand(text)
        val id = when (agent) {
            AgentKind.OPENCODE -> {
                val b = newOpenCodeBackend()
                if (b.state.value.isBlocking) b.refreshStatus()
                if (b.state.value.isBlocking) { b.stop(); error("OpenCode is not available: ${b.state.value.label}.") }
                b.startNewChat()
                delay(150)
                val id = b.currentConversation.value?.id ?: run { b.stop(); error("Could not create the chat") }
                projectPath?.let { b.setWorkspaceOfNewChat(it) }
                modelId?.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
                b.sendMessage(prompt)
                openCodeBackends[id] = b
                attachedAt[id] = System.currentTimeMillis()
                scope.launch {
                    delay(4000)
                    runCatching { vm.openCodeBackend.refreshSessions() }
                    if (!title.isNullOrBlank()) {
                        delay(4000)
                        runCatching { b.renameConversation(id, title.trim().take(60)) }
                    }
                }
                id
            }
            AgentKind.CLAUDE -> {
                val b = newClaudeBackend()
                b.startNewChat()
                delay(150)
                val id = b.currentConversation.value?.id ?: error("Could not create the chat")
                projectPath?.let { b.setWorkspaceOfNewChat(it) }
                modelId?.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
                b.sendMessage(prompt)
                claudeBackends[id] = b
                attachedAt[id] = System.currentTimeMillis()
                scope.launch {
                    delay(4000)
                    runCatching { vm.claudeBackend.refreshSessions() }
                    if (!title.isNullOrBlank()) {
                        delay(4000)
                        runCatching { b.renameConversation(id, title.trim().take(60)) }
                    }
                }
                id
            }
            AgentKind.AGY -> {
                val id = UUID.randomUUID().toString()
                val chosen = modelId?.takeIf { it.isNotBlank() } ?: vm.agyBackend.selectedModelId.value
                val enum = AgyHubClient.resolveModelEnum(chosen)
                val ws = projectPath ?: (com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path
                    ?: com.example.gemini.data.local.LocalEnvironmentManager.getHomeDir(app).absolutePath)
                hub.startCascade(id, enum, if (ws.startsWith("file://")) ws else "file://$ws").getOrThrow()
                vm.agyBackend.rememberChatModel(id, chosen)
                hub.sendUserPrompt(id, prompt, enum).getOrThrow()
                scope.launch { delay(2500); runCatching { vm.agyBackend.syncAgyConversations(force = true) } }
                id
            }
        }
        val task = Task(
            id = id, agent = agent,
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: shortTitle(text),
            goal = text.take(600), project = projectPath.orEmpty(), note = "Started",
            owner = JarvisManagers.ownerOrVoice(owner), team = team.trim()
        )
        put(task)
        return task
    }

    /** Watches an existing chat the user named (a message was just sent to it). Its news goes to [owner]. */
    fun adopt(id: String, agent: AgentKind, message: String, owner: String = JarvisManagers.VOICE) {
        val now = System.currentTimeMillis()
        val existing = find(id)
        if (existing != null && existing.id == id) {
            put(existing.copy(state = State.STARTING, turnAt = now, updatedAt = now, progressAt = now, seenRunning = false,
                followUps = existing.followUps + 1, note = "Follow-up sent", reported = true))
            return
        }
        val conv = vm.allConversations.value.find { it.id == id }
        put(Task(
            id = id, agent = agent, title = conv?.title?.takeIf { it.isNotBlank() && it != "New Chat" }?.take(60) ?: shortTitle(message),
            goal = message.trim().take(600), project = conv?.workspaceUri?.removePrefix("file://").orEmpty(),
            note = "Message sent", adopted = true, owner = JarvisManagers.ownerOrVoice(owner)
        ))
    }

    /** Sends a follow-up, a correction or a new direction to the task. With [interrupt] a running task is stopped first. */
    suspend fun reply(id: String, message: String, interrupt: Boolean): String {
        val t = find(id) ?: return "No task with that id. Use jarvis_task_list."
        val text = message.trim()
        if (text.isEmpty()) return "The message is empty."
        if (isRunningNow(t)) {
            if (!interrupt) return "The task is still working. Pass interrupt=true to stop it and send this instead, or wait for it to finish."
            stop(t.id, quiet = true)
            var w = 0
            while (isRunningNow(t) && w < 24) { delay(500); w++ }
        }
        when (t.agent) {
            AgentKind.CLAUDE -> {
                val onScreen = vm.currentConversation.value?.id == t.id
                if (onScreen) {
                    vm.claudeBackend.sendMessage(PromptExpansion.expand(text))
                } else {
                    val b = ensureBackend(t.id)
                    if (b.currentConversation.value?.id != t.id) { b.selectConversation(t.id); delay(900) }
                    b.sendMessage(PromptExpansion.expand(text))
                }
            }
            AgentKind.AGY -> {
                val enum = AgyHubClient.resolveModelEnum(vm.agyBackend.modelOfChat(t.id))
                hub.sendUserPrompt(t.id, PromptExpansion.expand(text), enum).getOrElse { return "Could not send: ${it.message}" }
            }
            AgentKind.OPENCODE -> {
                if (vm.currentConversation.value?.id == t.id) {
                    vm.openCodeBackend.sendMessage(PromptExpansion.expand(text))
                } else {
                    val b = ensureOpenCodeBackend(t.id)
                    if (b.currentConversation.value?.id != t.id) { b.selectConversation(t.id); delay(900) }
                    b.sendMessage(PromptExpansion.expand(text))
                }
            }
        }
        val now = System.currentTimeMillis()
        put(t.copy(state = State.STARTING, turnAt = now, updatedAt = now, progressAt = now, seenRunning = false,
            followUps = t.followUps + 1, note = "Follow-up: ${text.take(60)}", result = "", reported = true))
        return "Sent to the task."
    }

    /** Stops what the task is doing. */
    suspend fun stop(id: String, quiet: Boolean = false): String {
        val t = find(id) ?: return "No task with that id."
        when (t.agent) {
            AgentKind.CLAUDE -> {
                if (vm.currentConversation.value?.id == t.id) vm.claudeBackend.stopStreaming()
                else ensureBackend(t.id).stopStreaming()
            }
            AgentKind.AGY -> hub.cancelCascadeInvocation(t.id).onFailure { return "Could not stop: ${it.message}" }
            AgentKind.OPENCODE -> (openCodeBackends[t.id] ?: vm.openCodeBackend).interrupt(t.id).onFailure { return "Could not stop: ${it.message}" }
        }
        if (!quiet) put(t.copy(state = State.STOPPED, updatedAt = System.currentTimeMillis(), note = "Stopped", reported = true))
        return "Stopped."
    }

    /** Forgets the task (the chat itself stays). */
    fun close(id: String): Boolean {
        val t = find(id) ?: return false
        release(t.id)
        _tasks.value = _tasks.value.filter { it.id != t.id }
        save()
        return true
    }

    /** Removes the finished tasks from the list. */
    fun clearFinished() {
        _tasks.value.filter { !it.active }.forEach { release(it.id) }
        _tasks.value = _tasks.value.filter { it.active }
        save()
    }

    /** The task's waiting question, if any (Claude tasks). */
    fun pendingQuestion(id: String): Pair<ClaudeChatBackend, ToolCall>? {
        val b = claudeBackends[id] ?: return null
        val call = b.messages.value.asSequence().filter { it.role == MessageRole.ASSISTANT }.flatMap { it.toolCalls.asSequence() }
            .lastOrNull { it.toolType == ToolType.ASK_CHOICE && it.status == "AWAITING_CHOICE" && it.questionnaire != null } ?: return null
        return b to call
    }

    /** The task's last reply text (cut to [max]). */
    suspend fun lastReply(t: Task, max: Int = 900): String {
        val msgs = when (t.agent) {
            AgentKind.CLAUDE -> claudeBackends[t.id]?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            AgentKind.OPENCODE -> openCodeBackends[t.id]?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            AgentKind.AGY -> runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
        }
        val text = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content.orEmpty()
        // the sub-agent's report as words: its thinking and tool markers out, an error as one closing line
        return JarvisText.report(text).let { if (it.length > max) "…" + it.takeLast(max) else it }
    }

    // ------------------------------------------------------------------ Antigravity approvals off screen

    /** Approvals waiting in an Antigravity chat that is not on screen: (tool call, message id). Refreshed by [refreshAgyPending]. */
    fun agyPendingOf(id: String): List<Pair<ToolCall, String>> = agyPending[id].orEmpty()

    /** Reads the chat's steps (at most every [minGapMs]) and remembers the approvals waiting in it. */
    suspend fun refreshAgyPending(id: String, minGapMs: Long = 4000) {
        val now = System.currentTimeMillis()
        if (now - (agyCheckedAt[id] ?: 0L) < minGapMs) return
        agyCheckedAt[id] = now
        val msgs = runCatching { JarvisController.readChat(id) }.getOrNull() ?: return
        val waiting = msgs.filter { it.role == MessageRole.ASSISTANT }.flatMap { m ->
            m.toolCalls.filter { it.status == "PENDING_APPROVAL" && it.toolType != ToolType.ASK_CHOICE && it.stepIndex != null }
                .distinctBy { it.stepIndex }.map { it to m.id }
        }
        if (waiting.isEmpty()) agyPending.remove(id) else agyPending[id] = waiting
    }

    fun clearAgyPending(id: String) {
        agyPending.remove(id)
        agyCheckedAt.remove(id)
    }

    /** Answers an Antigravity approval of a chat that is not on screen. */
    suspend fun resolveAgy(chatId: String, call: ToolCall, allow: Boolean, reason: String?): Result<Unit> {
        val type = call.interactionType
            ?: if (call.name.startsWith("mcp_") || call.name == "call_mcp_tool" || call.name == "read_resource" || call.name == "list_resources") "mcp" else "permission"
        val r = hub.handleCascadeUserInteraction(
            cascadeId = chatId, stepIndex = call.stepIndex ?: return Result.failure(IllegalStateException("no step")),
            trajectoryId = call.trajectoryId.orEmpty(), allow = allow, scope = "PERMISSION_SCOPE_ONCE",
            userDenyInstruction = if (allow) "" else (reason ?: "The user rejected this."), interactionType = type
        )
        agyPending[chatId] = agyPending[chatId].orEmpty().filter { it.first.stepIndex != call.stepIndex }
        return r
    }

    // ------------------------------------------------------------------ watching

    private fun ensureBackend(id: String): ClaudeChatBackend = claudeBackends.getOrPut(id) {
        attachedAt[id] = System.currentTimeMillis()
        newClaudeBackend().also { it.selectConversation(id) }
    }

    private fun ensureOpenCodeBackend(id: String): com.example.gemini.data.agent.opencode.OpenCodeChatBackend = openCodeBackends.getOrPut(id) {
        attachedAt[id] = System.currentTimeMillis()
        newOpenCodeBackend().also { it.selectConversation(id) }
    }

    private fun release(id: String) {
        claudeBackends.remove(id)?.let { runCatching { it.detach() } }
        openCodeBackends.remove(id)?.let { runCatching { it.stop() } }
        attachedAt.remove(id)
        progressSig.remove(id)
        clearAgyPending(id)
    }

    private fun isRunningNow(t: Task): Boolean = when (t.agent) {
        AgentKind.CLAUDE -> claudeBackends[t.id]?.isCurrentChatActivelyRunning?.value == true ||
            (vm.currentConversation.value?.id == t.id && vm.claudeBackend.isCurrentChatActivelyRunning.value)
        AgentKind.AGY -> agyBusy(t.id)
        AgentKind.OPENCODE -> openCodeBackends[t.id]?.isCurrentChatActivelyRunning?.value == true ||
            (vm.currentConversation.value?.id == t.id && vm.openCodeBackend.isCurrentChatActivelyRunning.value) ||
            vm.openCodeBackend.conversations.value.any { it.id == t.id && it.isRunning }
    }

    private fun agyBusy(id: String): Boolean {
        val hubOnline = vm.systemConnectionState.value.isHubOnline
        val c = vm.agyBackend.conversations.value.find { it.id == id } ?: return false
        return hubOnline && (c.isRunning || c.notFullyIdle)
    }

    private suspend fun loop() {
        var tick = 0
        while (scope.isActive) {
            delay(1500)
            tick++
            try {
                if (!JarvisController.isAttached || !JarvisPreferences.enabled.value) continue
                for (t in _tasks.value.filter { it.active }) runCatching { check(t, tick) }.onFailure { Log.w(TAG, "check ${t.id}: ${it.message}") }
                _anyActive.value = _tasks.value.any { it.active }
            } catch (e: Exception) {
                Log.w(TAG, "loop: ${e.message}")
            }
        }
    }

    private suspend fun check(t0: Task, tick: Int) {
        var t = t0
        val now = System.currentTimeMillis()
        val running: Boolean
        var needsApproval = false
        var asking = false
        var error: String? = null
        val sig: String
        when (t.agent) {
            AgentKind.CLAUDE -> {
                val b = ensureBackend(t.id)
                running = b.isCurrentChatActivelyRunning.value
                needsApproval = b.pendingPermissions.value.isNotEmpty()
                asking = pendingQuestion(t.id) != null
                error = b.conversationError.value
                val msgs = b.messages.value
                val last = msgs.lastOrNull()
                sig = "${msgs.size}:${last?.content?.length}:${last?.toolCalls?.size}:${last?.toolCalls?.lastOrNull()?.status}"
            }
            AgentKind.AGY -> {
                running = agyBusy(t.id)
                val c = vm.agyBackend.conversations.value.find { it.id == t.id }
                sig = "${c?.stepCount}:${c?.updatedAt}"
                // a step waiting for approval also stops the progress: only then read the steps from the hub
                if (running && tick % 3 == 0 && now - t.progressAt > 3000) refreshAgyPending(t.id, 4000)
                else if (!running) clearAgyPending(t.id)
                needsApproval = running && agyPendingOf(t.id).isNotEmpty() ||
                    (vm.currentConversation.value?.id == t.id && vm.pendingApprovals.value.isNotEmpty())
            }
            AgentKind.OPENCODE -> {
                val b = ensureOpenCodeBackend(t.id)
                running = b.isCurrentChatActivelyRunning.value
                needsApproval = b.pendingPermissions.value.isNotEmpty()
                val msgs = b.messages.value
                val last = msgs.lastOrNull()
                // OpenCode reports failures as an error card in the reply (provider error, gave up after retries, no answer)
                error = b.conversationError.value ?: last?.takeIf { !running && it.role == MessageRole.ASSISTANT && it.content.contains("<!-- error -->") }
                    ?.let { m -> Regex("\"userMessage\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(m.content)?.groupValues?.get(1) ?: "the provider failed" }
                sig = "${msgs.size}:${last?.content?.length}:${last?.toolCalls?.size}:${last?.toolCalls?.lastOrNull()?.status}"
            }
        }
        if (progressSig.put(t.id, sig) != sig) t = t.copy(progressAt = now)
        if (running && !t.seenRunning) t = t.copy(seenRunning = true)
        val graceFrom = maxOf(t.turnAt, attachedAt[t.id] ?: 0L)
        val stallMs = JarvisPreferences.stallMinutes.value * 60_000L
        val next = when {
            needsApproval -> State.NEEDS_APPROVAL
            asking -> State.NEEDS_ANSWER
            running && stallMs > 0 && now - t.progressAt > stallMs -> State.STALLED
            running -> State.RUNNING
            t.seenRunning || now - graceFrom > START_GRACE_MS -> if (error != null) State.FAILED else State.DONE
            else -> State.STARTING
        }
        if (next != t.state) {
            t = t.copy(state = next, updatedAt = now, reported = false, note = when (next) {
                State.NEEDS_APPROVAL -> "Waiting for an approval"
                State.NEEDS_ANSWER -> "Asking a question"
                State.STALLED -> "No progress for ${(now - t.progressAt) / 60_000} min"
                State.RUNNING -> "Working"
                State.DONE -> "Finished"
                State.FAILED -> "Failed: ${error.orEmpty().take(80)}"
                else -> t.note
            })
            // the manager gets the sub-agent's whole report (cut only when very long)
            if (next == State.DONE || next == State.FAILED) t = t.copy(result = lastReply(t, 3500))
        }
        put(t)
        if (!t.reported) {
            // approvals and questions are reported by the controller's own loops (they can be answered there)
            val kind = when (t.state) {
                State.DONE -> "done"
                State.FAILED -> "failed"
                State.STALLED -> "stalled"
                else -> null
            }
            put(t.copy(reported = true))
            if (kind != null) JarvisController.onTaskEvent(t, kind, if (kind == "failed") error.orEmpty() else t.result)
            if (!t.state.active) {
                // keep the chat readable, but stop listening to it
                scope.launch {
                    delay(90_000)
                    if (find(t.id)?.active == false) {
                        claudeBackends.remove(t.id)?.let { runCatching { it.detach() } }
                        openCodeBackends.remove(t.id)?.let { runCatching { it.stop() } }
                        attachedAt.remove(t.id)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ list and storage

    private fun put(task: Task) {
        val list = _tasks.value
        val changed = list.firstOrNull { it.id == task.id } != task
        _tasks.value = (listOf(task) + list.filter { it.id != task.id }).sortedByDescending { it.createdAt }.take(KEEP)
        _anyActive.value = _tasks.value.any { it.active }
        if (changed) save()
    }

    private fun shortTitle(text: String): String {
        val first = PromptExpansion.collapse(text).lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (first.length > 48) first.take(48).trimEnd() + "…" else first.ifBlank { "Task" }
    }

    private fun save() {
        val arr = JSONArray()
        _tasks.value.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("agent", t.agent.name); put("title", t.title); put("goal", t.goal); put("project", t.project)
                put("state", t.state.name); put("created", t.createdAt); put("turn", t.turnAt); put("updated", t.updatedAt)
                put("seen", t.seenRunning); put("follow", t.followUps); put("note", t.note); put("result", t.result.take(1200)); put("adopted", t.adopted)
                put("owner", t.owner); put("team", t.team)
            })
        }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }

    private fun load() {
        val raw = prefs?.getString("list", null) ?: return
        val now = System.currentTimeMillis()
        _tasks.value = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val agent = runCatching { AgentKind.valueOf(o.getString("agent")) }.getOrNull() ?: return@mapNotNull null
                val state = runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.DONE)
                Task(
                    id = o.getString("id"), agent = agent, title = o.optString("title"), goal = o.optString("goal"),
                    project = o.optString("project"),
                    // a task that was working when the app closed is checked again (the chat may still be running)
                    state = if (state.active) State.STARTING else state,
                    createdAt = o.optLong("created", now), turnAt = if (state.active) now else o.optLong("turn", now),
                    updatedAt = o.optLong("updated", now), progressAt = now, seenRunning = false,
                    followUps = o.optInt("follow"), note = o.optString("note"), result = o.optString("result"),
                    reported = true, adopted = o.optBoolean("adopted"),
                    owner = o.optString("owner").ifBlank { JarvisManagers.VOICE }, team = o.optString("team")
                )
            }
        }.getOrDefault(emptyList())
        _anyActive.value = _tasks.value.any { it.active }
    }
}
