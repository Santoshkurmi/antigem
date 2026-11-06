package com.example.gemini.data.jarvis

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.ClaudeBridgeClient
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.audio.SpeechTranscriber
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import com.example.gemini.data.remote.dto.AskQuestionOptionDto
import com.example.gemini.data.remote.dto.AskQuestionResponseItemDto
import com.example.gemini.data.agent.ChatCompletionMonitor
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.ui.chat.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * The brain of Jarvis inside the app.
 *
 * It listens (orb, notification, Quick Settings tile, headset button or wake phrase), decides when a sentence is finished
 * (smart pause, fixed pause, a send word or manual), filters out what was said to other people, handles the simple
 * phrases itself (stop, repeat, slower, thanks) and sends the rest to a hidden chat of the chosen agent. The first
 * message of that chat carries the whole guide and tool list ([JarvisSkill.bootPack]); every later one a short note with
 * live context. The model answers through jarvis_mcp ([JarvisMcp]); this class speaks, asks, answers approvals and
 * questions by voice according to the user's policy, and turns task news from [JarvisTasks] into events for the model.
 *
 * The Jarvis chat is a normal Claude Code or Antigravity chat: Claude's runs on a second [ClaudeChatBackend] with its
 * own state (so it never takes over the chat on screen), Antigravity's is driven through the hub client by id.
 */
object JarvisController {
    private const val TAG = "JarvisController"
    private const val NO_SPEECH_MS = 10_000L
    private const val ASK_TIMEOUT_MS = 45_000L
    /** Microphone level above which someone is talking (the recognizers report 0.08..1). */
    private const val LOUD = 0.42f

    enum class Phase { OFF, IDLE, LISTENING, THINKING, SPEAKING }
    enum class Risk { READ, NORMAL, RISKY }

    /** What opened the microphone; the model is told (follow-ups may be side talk). */
    enum class Origin(val label: String) {
        BUTTON("button"), WAKE("wake phrase"), HEADSET("headset button"), FOLLOWUP("follow-up (may not be meant for you)"),
        ASK("answer"), TYPED("typed")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var app: Application
        private set
    lateinit var vm: ChatViewModel
        private set
    private var attached = false
    val isAttached get() = attached

    // ---- observable state
    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _heard = MutableStateFlow("")
    /** What the microphone has understood so far (live). */
    val heard: StateFlow<String> = _heard.asStateFlow()
    private val _said = MutableStateFlow("")
    /** The last thing Jarvis said (or showed, in quiet mode). */
    val said: StateFlow<String> = _said.asStateFlow()
    /** The screen the user is on: chat, ide, terminal, browser, notes. */
    val screen = MutableStateFlow("chat")
    private val _log = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The last lines of the conversation: "You" / "Jarvis" to text. */
    val log: StateFlow<List<Pair<String, String>>> = _log.asStateFlow()

    /** A problem the user should see. [action]: "setup" (Settings → Jarvis), "mic" (allow the microphone) or null. */
    data class Notice(val text: String, val action: String? = null, val at: Long = System.currentTimeMillis())
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    private val _mcpProblem = MutableStateFlow(false)
    /** The model said it has no jarvis_mcp tools (cleared by the next tool call). */
    val mcpProblem: StateFlow<Boolean> = _mcpProblem.asStateFlow()
    private val _sendProgress = MutableStateFlow(0f)
    /** 0..1 while a pause is counting down to sending what was heard (auto send modes). */
    val sendProgress: StateFlow<Float> = _sendProgress.asStateFlow()
    private val _holding = MutableStateFlow(false)
    /** The user tapped Wait: this sentence is only sent on Send. */
    val holding: StateFlow<Boolean> = _holding.asStateFlow()
    private val _keepAwake = MutableStateFlow(false)
    /** Jarvis wants the screen kept on (Settings → Jarvis → keep screen on). */
    val keepScreenAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()
    /** The Jarvis panel (long-press the orb) is open. */
    val panelOpen = MutableStateFlow(false)
    private val _settingsRequest = MutableStateFlow<String?>(null)
    /** A settings page Jarvis wants shown (a SettingsSection name). */
    val settingsRequest: StateFlow<String?> = _settingsRequest.asStateFlow()
    private val listening = MutableStateFlow(false)

    /** One line of what Jarvis is doing ("Checking your projects"). */
    data class Step(val text: String, val at: Long = System.currentTimeMillis(), val done: Boolean = false)
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    /** The last few things Jarvis did, newest last; the last one is current. Cleared a few seconds after work ends. */
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    @Volatile private var lastExplicitStatusAt = 0L
    @Volatile private var lastProgressSpokenAt = 0L
    @Volatile private var lastDerived = ""

    class TimerItem(val id: Int, val label: String, val endsAt: Long)
    private val _timers = MutableStateFlow<List<TimerItem>>(emptyList())
    val timers: StateFlow<List<TimerItem>> = _timers.asStateFlow()
    private var timerSeq = 0
    private val busy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = busy.asStateFlow()

    // ---- the agents' chats
    /** Backend of the Jarvis chat (Claude). Its state is separate from the chat on screen. */
    val jarvisClaude: ClaudeChatBackend by lazy {
        ClaudeChatBackend(app, scope, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())
    }
    /** Backend for chats Jarvis touches that are not tasks (model changes, stopping). */
    val opsClaude: ClaudeChatBackend by lazy {
        ClaudeChatBackend(app, scope, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())
    }
    private val hub by lazy { AgyHubClient() }
    @Volatile private var agyBusyUntil = 0L
    @Volatile private var agyRefreshing = false

    // ---- listening state
    private val lock = Any()
    private val committed = ArrayList<String>()
    @Volatile private var partial = ""
    @Volatile private var lastChangeAt = 0L
    @Volatile private var lastLoudAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var listenOrigin = Origin.BUTTON
    private var activeStt: SpeechTranscriber? = null
    private var endpointJob: Job? = null
    @Volatile private var inConversation = false
    /** Jarvis just asked something (or asked to listen): the next words are an answer, not side talk. */
    @Volatile private var expectingReply = false
    @Volatile private var listenAfterRequested = false
    @Volatile private var pendingAsk: CompletableDeferred<String>? = null
    private val askMutex = Mutex()
    private val androidStt by lazy { AndroidSpeechTranscriber(app) { JarvisPreferences.sttLanguage.value } }
    private val wakeStt by lazy { AndroidSpeechTranscriber(app) { JarvisPreferences.sttLanguage.value } }
    /** The chosen dictation failed: the device recognizer is used until the next start of the app. */
    @Volatile private var sttFallback = false
    @Volatile private var wakeRunning = false
    @Volatile private var wakeHit = false
    @Volatile private var wakeText = ""
    @Volatile private var wakeChangedAt = 0L
    /** Quiet mode: the reply counts as "speaking" until then (the card shows it, follow-ups still work). */
    @Volatile private var quietUntil = 0L

    private data class Outgoing(val text: String, val origin: Origin? = null, val event: Boolean = false)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    @Volatile private var carryText = ""
    private val handledPermissions: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val permQueue = Channel<Perm>(Channel.UNLIMITED)
    private val handledQuestions: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val questionQueue = Channel<PendingQuestion>(Channel.UNLIMITED)
    /** The Jarvis chat is working on a message, and the turn's end has not been handled yet. */
    @Volatile private var turnOpen = false
    @Volatile private var turnSpoke = false
    @Volatile private var turnToolCalls = 0
    @Volatile private var firstTurnOfChat = false
    /** The reply text handled at the end of the previous turn (a turn without new text is not read out again). */
    @Volatile private var lastReplySeen = ""

    // ------------------------------------------------------------------ lifecycle

    fun attach(application: Application, viewModel: ChatViewModel) {
        if (attached) return
        attached = true
        app = application
        vm = viewModel
        JarvisPreferences.init(app)
        JarvisVoice.init(app)
        // the microphone is never open after a restart until the user starts it again
        JarvisPreferences.handsFree.set(false)
        JarvisTasks.start(application, scope)
        scope.launch { pollLoop() }
        scope.launch { sendLoop() }
        scope.launch { permissionLoop() }
        scope.launch { questionLoop() }
        scope.launch { ChatCompletionMonitor.events.collect { c -> runCatching { onChatDone(c) } } }
    }

    /** Turns Jarvis on or off (Settings). */
    fun setEnabled(on: Boolean) {
        JarvisPreferences.enabled.set(on)
        if (!on) {
            setHandsFree(false)
            stopEverything()
            panelOpen.value = false
        }
    }

    /** Hands-free keeps the microphone service alive so the wake phrase and the headset button work with the screen off. */
    fun setHandsFree(on: Boolean) {
        val target = on && JarvisPreferences.enabled.value
        JarvisPreferences.handsFree.set(target)
        if (target) {
            if (!hasMic()) { showNotice("Allow the microphone first.", "mic"); JarvisPreferences.handsFree.set(false); return }
            JarvisService.start(app)
        } else {
            stopWake()
            JarvisService.stop(app)
        }
    }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun clearNotice() { _notice.value = null }

    private fun showNotice(text: String, action: String? = null) { _notice.value = Notice(text, action) }

    /** Shows a settings page (a SettingsSection name, e.g. "JARVIS"). */
    fun requestSettings(section: String) {
        panelOpen.value = false
        if (attached) vm.requestViewMode("CHAT")
        _settingsRequest.value = section
    }

    fun consumeSettingsRequest() { _settingsRequest.value = null }

    /** 0..1 live level of whatever is making sound right now (microphone while listening, voice while speaking). */
    fun level(): Float = when {
        listening.value -> activeStt?.latestAmplitude?.value ?: 0.1f
        JarvisVoice.speaking.value -> JarvisVoice.level.value
        isSpeaking() -> 0.35f
        else -> 0f
    }

    private fun isSpeaking(): Boolean = JarvisVoice.speaking.value || System.currentTimeMillis() < quietUntil

    /** A jarvis_mcp tool was called (the tools are connected). */
    fun onToolCall(name: String) {
        if (turnOpen) turnToolCalls++
        if (_mcpProblem.value) { _mcpProblem.value = false; if (_notice.value?.action == "setup") _notice.value = null }
    }

    // ------------------------------------------------------------------ polling loop (state edges)

    private suspend fun pollLoop() {
        var wasSpeaking = false
        var speakEndedAt = 0L
        var wasBusy = false
        var tick = 0
        while (scope.isActive) {
            try {
                val enabled = JarvisPreferences.enabled.value
                val speaking = isSpeaking()
                val isBusy = enabled && computeBusy()
                busy.value = isBusy
                val p = when {
                    !enabled -> Phase.OFF
                    listening.value -> Phase.LISTENING
                    speaking -> Phase.SPEAKING
                    isBusy -> Phase.THINKING
                    else -> Phase.IDLE
                }
                if (p != _phase.value) _phase.value = p
                if (enabled) {
                    if (wasSpeaking && !speaking) speakEndedAt = System.currentTimeMillis()
                    wasSpeaking = speaking
                    // follow-up: listen again after Jarvis finished talking
                    val now = System.currentTimeMillis()
                    if (speakEndedAt > 0 && !speaking && !isBusy && !listening.value && pendingAsk == null && now - speakEndedAt > 650) {
                        speakEndedAt = 0
                        val wanted = inConversation && (JarvisPreferences.conversationMode.value || listenAfterRequested)
                        listenAfterRequested = false
                        if (wanted) startListening(origin = Origin.FOLLOWUP)
                    }
                    if (wasBusy && !isBusy) { touch(); onJarvisTurnEnded() }
                    if (isBusy && now - lastExplicitStatusAt > 3000) {
                        deriveToolStatus()?.let { if (it != lastDerived) { lastDerived = it; postStatus(it, explicit = false) } }
                    }
                    val last = _steps.value.lastOrNull()
                    if (last != null && !isBusy && !speaking && !listening.value && now - last.at > 5000) _steps.value = emptyList()
                    wasBusy = isBusy
                    // the Antigravity Jarvis chat is never on screen: read its approvals from the hub while it works
                    if (isBusy && JarvisPreferences.currentChatAgent.value == AgentKind.AGY.name && !agyRefreshing) {
                        val id = JarvisPreferences.currentChatId.value
                        if (id.isNotBlank()) {
                            agyRefreshing = true
                            scope.launch { try { JarvisTasks.refreshAgyPending(id, 2500) } finally { agyRefreshing = false } }
                        }
                    }
                    collectPermissions()
                    collectQuestions()
                    pumpWake(speaking)
                    _notice.value?.let { n -> if (now - n.at > (if (n.action != null) 30_000 else 9_000)) _notice.value = null }
                    if (++tick % 200 == 0) checkUsageLow()
                } else {
                    stopWake()
                }
                _keepAwake.value = enabled && when (JarvisPreferences.keepScreenOn.value) {
                    "off" -> false
                    "active" -> p != Phase.IDLE || JarvisTasks.anyActive.value
                    else -> true
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll: ${e.message}")
            }
            // nothing to watch while Jarvis is off: check rarely
            delay(if (JarvisPreferences.enabled.value) 300 else 2000)
        }
    }

    private fun computeBusy(): Boolean {
        val id = JarvisPreferences.currentChatId.value
        if (id.isBlank()) return false
        return if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
            jarvisClaude.isCurrentChatActivelyRunning.value
        } else {
            System.currentTimeMillis() < agyBusyUntil ||
                vm.agyBackend.conversations.value.any { it.id == id && it.isRunning }
        }
    }

    private fun touch() {
        JarvisPreferences.lastActivityAt.set(System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ activity (what Jarvis is doing)

    /**
     * Shows [text] as the current step on the activity card. [explicit] steps come from the model (jarvis_status) and keep
     * the card steady for a moment; the rest come from the app (a tool being used). With [speak] and the "speak progress"
     * setting on, it is also said out loud, at most every few seconds.
     */
    fun postStatus(text: String, speak: Boolean = false, explicit: Boolean = true) {
        val t = text.trim().replace(Regex("\\s+"), " ").take(70)
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        if (explicit) lastExplicitStatusAt = now
        val cur = _steps.value
        if (cur.lastOrNull()?.text == t) return
        _steps.value = (cur.map { it.copy(done = true) } + Step(t)).takeLast(4)
        if (speak && JarvisPreferences.speakProgress.value && now - lastProgressSpokenAt > 8000 && !listening.value) {
            lastProgressSpokenAt = now
            scope.launch { speakOut(t) }
        }
    }

    /** What the Jarvis chat's agent is running right now, in plain words (Claude chats; null when unknown). */
    private fun deriveToolStatus(): String? {
        if (JarvisPreferences.currentChatAgent.value != AgentKind.CLAUDE.name) return null
        val call = jarvisClaude.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toolCalls?.lastOrNull { it.status == "RUNNING" } ?: return null
        val n = call.name.lowercase()
        return when {
            n.contains("jarvis") -> null
            n == "toolsearch" -> "Loading my tools"
            n.startsWith("mcp__") -> "Using ${n.removePrefix("mcp__").substringBefore("__").replace('_', ' ')}"
            n == "bash" || n.contains("terminal") || n.contains("command") -> "Running ${call.command.trim().take(32).ifBlank { "a command" }}"
            n.contains("read") || n.contains("view") -> "Reading a file"
            n.contains("write") || n.contains("edit") -> "Editing a file"
            n.contains("grep") || n.contains("glob") || n.contains("find") || n.contains("search") && !n.contains("web") -> "Searching the code"
            n.contains("web") -> "Looking it up online"
            else -> "Working on it"
        }
    }

    // ------------------------------------------------------------------ the turn ended

    private fun limitSpoken(text: String, max: Int = 340): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val sb = StringBuilder()
        for (sentence in flat.split(Regex("(?<=[.!?])\\s+"))) {
            if (sb.isNotEmpty() && sb.length + sentence.length > max) break
            sb.append(sentence).append(' ')
            if (sb.length >= max) break
        }
        return sb.toString().trim().take(max + 80)
    }

    private suspend fun lastJarvisReply(): String? {
        val id = JarvisPreferences.currentChatId.value
        val raw = if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
            jarvisClaude.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
        } else {
            runCatching { readChat(id).lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content }.getOrNull()
        }
        return raw?.let { PromptExpansion.collapse(it).trim() }
    }

    /**
     * The Jarvis chat finished a turn. A reply of [JarvisSkill.MCP_MISSING] means the agent has no jarvis_mcp tools: the
     * user is told how to fix that. If the model never called jarvis_speak (it answered in plain text), the start of that
     * answer is read out so the user is never left in silence.
     */
    private fun onJarvisTurnEnded() {
        if (!turnOpen) return
        turnOpen = false
        val first = firstTurnOfChat
        firstTurnOfChat = false
        val calls = turnToolCalls
        scope.launch {
            delay(700) // a late jarvis_speak may still be on its way
            val text = lastJarvisReply().orEmpty()
            val fresh = text.isNotBlank() && text != lastReplySeen
            if (fresh) lastReplySeen = text
            if (fresh && text.contains(JarvisSkill.MCP_MISSING)) { onMcpMissing(); return@launch }
            if (first && calls == 0 && !turnSpoke) {
                showNotice("Jarvis answered without its app tools. If it can't control the app, add the Jarvis MCP server to ${agentLabel()} (Settings → Jarvis → Setup).", "setup")
            }
            if (!fresh || turnSpoke || isSpeaking() || pendingAsk != null || pendingQuestions().isNotEmpty() || listening.value) return@launch
            val spoken = limitSpoken(text)
            if (spoken.isNotBlank()) {
                addLog("Jarvis", spoken)
                speakOut(spoken)
                if (JarvisPreferences.conversationMode.value) inConversation = true
            }
        }
    }

    private fun agentLabel() = if (JarvisPreferences.agentKind == AgentKind.CLAUDE) "Claude Code" else "Antigravity"

    private suspend fun onMcpMissing() {
        _mcpProblem.value = true
        showNotice("Jarvis' app tools (jarvis_mcp) are not connected to ${agentLabel()}. Tap to open the setup and add the Jarvis MCP server.", "setup")
        addLog("Jarvis", "My app tools aren't connected.")
        speakOut("My app tools aren't connected yet, so I can't do that. Open Settings, then Jarvis, and add the Jarvis MCP server.")
    }

    // ------------------------------------------------------------------ questions the agents ask (AskUserQuestion)

    class PendingQuestion(val key: String, val backend: ClaudeChatBackend, val call: ToolCall, val source: String, val chatId: String)

    /** Choice questions waiting for an answer in the chats Jarvis can reach. */
    fun pendingQuestions(): List<PendingQuestion> = buildList {
        fun scan(b: ClaudeChatBackend, source: String, chatId: String) {
            b.messages.value.asSequence()
                .filter { it.role == MessageRole.ASSISTANT }
                .flatMap { it.toolCalls.asSequence() }
                .filter { it.toolType == ToolType.ASK_CHOICE && it.status == "AWAITING_CHOICE" && it.questionnaire != null }
                .forEach { add(PendingQuestion(it.id, b, it, source, chatId)) }
        }
        JarvisTasks.claudeTaskBackends().forEach { (t, b) -> if (t.active) scan(b, "task", t.id) }
        if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) scan(jarvisClaude, "jarvis", JarvisPreferences.currentChatId.value)
        scan(opsClaude, "ops", opsClaude.currentConversation.value?.id.orEmpty())
        if (vm.currentConversation.value?.agent == AgentKind.CLAUDE) scan(vm.claudeBackend, "screen", vm.currentConversation.value?.id.orEmpty())
    }.distinctBy { it.key }

    private fun collectQuestions() {
        val live = pendingQuestions()
        handledQuestions.retainAll(live.map { it.key }.toSet())
        for (q in live) {
            if (q.key in handledQuestions) continue
            if (q.source == "screen" && !JarvisPreferences.handsFree.value) continue
            handledQuestions += q.key
            questionQueue.trySend(q)
        }
    }

    private fun describeQuestion(q: ChoiceQuestion): String = buildString {
        append(q.prompt.trim().trimEnd('.', '?')).append('?')
        q.description?.takeIf { it.isNotBlank() }?.let { append(' ').append(it.trim().take(120)) }
        val opts = q.options.filter { !it.isCustomInput }
        if (opts.isNotEmpty()) {
            append(" Options: ")
            opts.take(5).forEachIndexed { i, o -> append(i + 1).append(", ").append(o.label.trim()).append(". ") }
            if (opts.size > 5) append("And ${opts.size - 5} more. ")
            append(if (q.isMultiSelect) "You can pick several." else "Say a number or the name.")
        }
    }

    private val numberWords = mapOf(
        "one" to 1, "first" to 1, "1" to 1, "two" to 2, "second" to 2, "2" to 2, "three" to 3, "third" to 3, "3" to 3,
        "four" to 4, "fourth" to 4, "4" to 4, "five" to 5, "fifth" to 5, "5" to 5, "six" to 6, "sixth" to 6, "6" to 6
    )

    private fun pickOptions(answer: String, q: ChoiceQuestion): Pair<List<ChoiceOption>, String?> {
        val a = answer.lowercase()
        val words = Regex("[a-z0-9']+").findAll(a).map { it.value }.toList()
        val opts = q.options
        val picked = LinkedHashSet<ChoiceOption>()
        words.forEach { w -> numberWords[w]?.let { i -> opts.getOrNull(i - 1)?.let { picked += it } } }
        opts.forEach { o ->
            val label = o.label.lowercase()
            val sig = Regex("[a-z0-9']+").findAll(label).map { it.value }.filter { it.length > 3 }.toList()
            if (label.isNotBlank() && (a.contains(label) || (sig.isNotEmpty() && sig.count { it in words } * 2 >= sig.size))) picked += o
        }
        if (q.isMultiSelect && ("all" in words || "everything" in words)) picked.addAll(opts)
        val selected = if (q.isMultiSelect) picked.toList() else picked.take(1)
        val write = if (selected.isEmpty() && (q.allowCustomAnswer || opts.isEmpty())) answer.trim() else null
        return selected to write
    }

    private val skipWords = Regex("\\b(skip|cancel|never ?mind|you decide|up to you|whatever|don'?t care|your call)\\b", RegexOption.IGNORE_CASE)

    private fun responseOf(question: ChoiceQuestion, answer: String): Pair<AskQuestionResponseItemDto, String> {
        val (selected, write) = pickOptions(answer, question)
        return AskQuestionResponseItemDto(
            question = question.prompt,
            options = question.options.map { AskQuestionOptionDto(id = it.id, text = it.label, label = it.label, description = it.description.orEmpty()) },
            selectedOptionIds = selected.map { it.id },
            isMultiSelect = question.isMultiSelect,
            writeInResponse = write
        ) to (selected.map { it.label } + listOfNotNull(write)).joinToString(", ")
    }

    private fun questionEventText(task: JarvisTasks.Task?, chatId: String, qn: ChoiceQuestionnaire): String = buildString {
        append("[Phone event, not from the user] The task \"${task?.title ?: "a chat"}\" (task id $chatId) is asking a question:\n")
        qn.questions.forEachIndexed { i, q ->
            append("${i + 1}. ${q.prompt.trim()}")
            val opts = q.options.filter { !it.isCustomInput }
            if (opts.isNotEmpty()) append(" Options: " + opts.mapIndexed { j, o -> "${j + 1}) ${o.label.trim()}" }.joinToString("; "))
            if (q.isMultiSelect) append(" (several allowed)")
            append('\n')
        }
        task?.goal?.let { append("Task goal: ${it.take(300)}\n") }
        append("If the user already told you what they want, answer now with jarvis_task_answer (for several questions separate the answers with ';'). ")
        append("Otherwise ask the user with jarvis_ask (read the options briefly), then pass the answer with jarvis_task_answer.")
    }

    private suspend fun questionLoop() {
        for (q in questionQueue) {
            try {
                if (pendingQuestions().none { it.key == q.key }) continue
                val qn = q.call.questionnaire ?: continue
                if (q.source == "task" && JarvisPreferences.eventRouting.value == "model") {
                    outbox.trySend(Outgoing(questionEventText(JarvisTasks.find(q.chatId), q.chatId, qn), event = true))
                    continue
                }
                val responses = ArrayList<AskQuestionResponseItemDto>()
                val summary = ArrayList<String>()
                var skipped = false
                val who = if (q.source == "task") JarvisTasks.find(q.chatId)?.title?.let { "The task $it asks: " }.orEmpty() else ""
                for ((i, question) in qn.questions.withIndex()) {
                    val answer = ask((if (i == 0) who else "") + describeQuestion(question), 60_000)
                    if (answer == null || skipWords.containsMatchIn(answer)) { skipped = true; break }
                    val (r, s) = responseOf(question, answer)
                    responses += r
                    summary += s
                }
                if (skipped) {
                    q.backend.skipChoices(q.call)
                    speakOut("Okay, I told it to use its best judgement.")
                } else {
                    q.backend.submitChoices(q.call, responses, summary.joinToString(" · "))
                    speakOut("Got it.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "question: ${e.message}")
            }
        }
    }

    /** Answers the question a task is asking (jarvis_task_answer). */
    fun answerTaskQuestion(id: String, answer: String): String {
        val (b, call) = JarvisTasks.pendingQuestion(id) ?: return "That task is not asking anything right now."
        val qn = call.questionnaire ?: return "That task is not asking anything right now."
        val a = answer.trim()
        if (a.isEmpty()) return "The answer is empty."
        if (skipWords.containsMatchIn(a)) { b.skipChoices(call); return "Told it to use its best judgement." }
        val parts = if (qn.questions.size > 1) a.split(';', '|').map { it.trim() }.filter { it.isNotEmpty() } else listOf(a)
        val responses = ArrayList<AskQuestionResponseItemDto>()
        val summary = ArrayList<String>()
        qn.questions.forEachIndexed { i, q ->
            val (r, s) = responseOf(q, parts.getOrElse(i) { parts.last() })
            responses += r
            summary += s
        }
        b.submitChoices(call, responses, summary.joinToString(" · "))
        handledQuestions += call.id
        return "Answered: ${summary.joinToString("; ")}."
    }

    // ------------------------------------------------------------------ announcements and task events

    private fun firstSentence(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val end = Regex("[.!?](\\s|$)").find(flat)?.range?.first?.plus(1) ?: flat.length
        return flat.take(minOf(end, max)).trim()
    }

    /** Any chat finished working: tell the user (when they turned announcements on). Tasks report through [onTaskEvent]. */
    private suspend fun onChatDone(c: Conversation) {
        if (!JarvisPreferences.enabled.value) return
        if (JarvisTasks.isTask(c.id)) return
        if (!JarvisPreferences.announceDone.value) return
        if (JarvisPreferences.announceOffscreenOnly.value && vm.currentConversation.value?.id == c.id && screen.value == "chat") return
        val title = c.title.trim().take(40).ifBlank { "A chat" }
        var msg = "$title is done. Please check it."
        if (JarvisPreferences.announceSummary.value) {
            val last = runCatching {
                readChat(c.id).lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
            }.getOrNull()?.let { PromptExpansion.collapse(it) }
            if (!last.isNullOrBlank()) msg += " It says: " + firstSentence(last, 160)
        }
        postStatus("Done: $title", explicit = false)
        if (listening.value || pendingAsk != null) return // never talk over the user
        addLog("Jarvis", msg)
        speakOut(msg)
    }

    /**
     * News from a task: "done", "failed" or "stalled". With event routing "model" the Jarvis model gets a phone event and
     * decides what to say and do; with "speak" Jarvis just announces it.
     */
    fun onTaskEvent(t: JarvisTasks.Task, kind: String, detail: String) {
        if (!JarvisPreferences.enabled.value) return
        postStatus(when (kind) { "done" -> "Done: ${t.title}"; "failed" -> "Failed: ${t.title}"; else -> "Stuck: ${t.title}" }.take(60), explicit = false)
        if (JarvisPreferences.eventRouting.value == "speak") {
            val msg = when (kind) {
                "done" -> "${t.title} is done." + (if (JarvisPreferences.announceSummary.value && detail.isNotBlank()) " It says: " + firstSentence(detail.removePrefix("…"), 160) else "")
                "failed" -> "${t.title} ran into a problem." + (if (detail.isNotBlank()) " " + firstSentence(detail, 140) else "")
                else -> "${t.title} hasn't made progress for a while."
            }
            scope.launch {
                var w = 0
                while ((listening.value || pendingAsk != null) && w < 240) { delay(500); w++ }
                addLog("Jarvis", msg)
                speakOut(msg)
            }
            return
        }
        val text = buildString {
            append("[Phone event, not from the user] ")
            when (kind) {
                "done" -> append("The task \"${t.title}\" (task id ${t.id}, ${t.agent.name.lowercase()}) finished its work.\n")
                "failed" -> append("The task \"${t.title}\" (task id ${t.id}, ${t.agent.name.lowercase()}) failed: ${detail.take(400)}\n")
                else -> append("The task \"${t.title}\" (task id ${t.id}) has been working but shows no progress for ${JarvisPreferences.stallMinutes.value} minutes.\n")
            }
            append("Its goal: ${t.goal.take(300)}\n")
            if (kind == "done") append(if (detail.isNotBlank()) "Its last reply (may be cut):\n${detail.take(900)}\n" else "It left no text reply; use jarvis_task_get for details.\n")
            append(when (kind) {
                "done" -> "Tell the user in one or two spoken sentences what came out. If it asks the user something or needs a decision, ask with jarvis_ask and pass the answer with jarvis_task_reply. Do not start other work unless the user asks."
                "failed" -> "Tell the user briefly what went wrong in plain words and suggest the next step. Ask before retrying anything big."
                else -> "Check it with jarvis_task_get, then tell the user briefly. Do not stop it unless the user says so."
            })
        }
        outbox.trySend(Outgoing(text, event = true))
    }

    private var usageWarnedFor = 0L

    private fun checkUsageLow() {
        if (!JarvisPreferences.enabled.value || !JarvisPreferences.announceUsageLow.value) return
        val w = vm.claudeBackend.rateLimits.value["five_hour"] ?: return
        val left = 1.0 - w.utilization
        val window = w.resetsAt ?: 0L
        if (left <= 0.15 && window != usageWarnedFor && !listening.value) {
            usageWarnedFor = window
            scope.launch { speakOut("Heads up, only about ${Math.round(left * 100).coerceAtLeast(0)} percent of your five hour usage is left.") }
        }
    }

    // ------------------------------------------------------------------ timers (cooking, laundry…)

    fun addTimer(seconds: Int, label: String): TimerItem {
        val item = TimerItem(++timerSeq, label.trim(), System.currentTimeMillis() + seconds * 1000L)
        _timers.value = _timers.value + item
        scope.launch {
            delay(seconds * 1000L)
            if (_timers.value.any { it.id == item.id }) {
                _timers.value = _timers.value.filter { it.id != item.id }
                cue(1)
                postStatus("Timer done${if (item.label.isNotBlank()) ": ${item.label}" else ""}", explicit = false)
                speakOut("Your ${item.label.ifBlank { "timer" }} is done.")
            }
        }
        return item
    }

    fun cancelTimer(id: Int): Boolean {
        val had = _timers.value.any { it.id == id }
        _timers.value = _timers.value.filter { it.id != id }
        return had
    }

    // ------------------------------------------------------------------ listening

    /** The mic button: start listening, stop speaking, or send what has been heard so far. */
    fun onMicPressed(origin: Origin = Origin.BUTTON) {
        if (!JarvisPreferences.enabled.value) return
        when {
            listening.value -> finishListening()
            isSpeaking() -> { silence(); startListening(origin = origin) }
            else -> startListening(origin = origin)
        }
    }

    /** Headset / Bluetooth button: one press talks or sends, two or more stop everything. */
    fun onHeadsetClicks(count: Int) {
        if (!JarvisPreferences.enabled.value || !JarvisPreferences.headsetButton.value) return
        if (count <= 1) onMicPressed(Origin.HEADSET)
        else { stopEverything(); scope.launch { speakOut("Stopped.") } }
    }

    /** Keeps listening until Send (the user needs more time for this sentence). */
    fun holdListening() {
        if (!listening.value) return
        _holding.value = true
        _sendProgress.value = 0f
    }

    fun startListening(initial: String = "", origin: Origin = Origin.BUTTON): Boolean {
        if (!JarvisPreferences.enabled.value) return false
        if (listening.value) return true
        if (!hasMic()) { showNotice("Allow the microphone first.", "mic"); return false }
        JarvisVoice.stop()
        quietUntil = 0L
        stopWake()
        synchronized(lock) {
            committed.clear()
            partial = initial
        }
        _heard.value = initial
        val now = System.currentTimeMillis()
        lastChangeAt = now
        lastLoudAt = now
        startedAt = now
        listenOrigin = origin
        if (origin == Origin.BUTTON || origin == Origin.WAKE || origin == Origin.HEADSET) expectingReply = false
        _holding.value = false
        _sendProgress.value = 0f
        listening.value = true
        inConversation = true
        _phase.value = Phase.LISTENING
        cue(1)
        val t = transcriber()
        activeStt = t
        t.startTranscriptionSession(
            scope = scope,
            cascadeId = vm.currentConversation.value?.id.orEmpty(),
            preCursorText = "",
            postCursorText = "",
            onPartialText = { p ->
                synchronized(lock) { partial = p }
                _heard.value = joinText(snapshotCommitted(), p)
                lastChangeAt = System.currentTimeMillis()
            },
            onFinalText = { f ->
                synchronized(lock) { committed.add(f); partial = "" }
                _heard.value = joinText(snapshotCommitted(), "")
                lastChangeAt = System.currentTimeMillis()
            },
            onError = { err -> onSttError(t, err) }
        )
        endpointJob?.cancel()
        endpointJob = scope.launch { endpointLoop(origin) }
        return true
    }

    /** Decides when the sentence is over, by the chosen send mode. */
    private suspend fun endpointLoop(origin: Origin) {
        while (scope.isActive && listening.value) {
            delay(100)
            val n = System.currentTimeMillis()
            val amp = activeStt?.latestAmplitude?.value ?: 0f
            if (amp > LOUD) lastLoudAt = n
            val text = joinText(snapshotCommitted(), partial)
            val mode = if (_holding.value) "manual" else JarvisPreferences.sendMode.value
            if (text.isBlank()) {
                _sendProgress.value = 0f
                val limit = when (origin) {
                    Origin.FOLLOWUP -> JarvisPreferences.followUpSeconds.value.coerceIn(2, 30) * 1000L
                    Origin.ASK -> 90_000L
                    else -> NO_SPEECH_MS
                }
                if (n - startedAt > limit && n - lastLoudAt > 1200) {
                    cancelListening()
                    inConversation = false
                    expectingReply = false
                    return
                }
                continue
            }
            if (n - startedAt > JarvisPreferences.maxListenSeconds.value.coerceIn(15, 300) * 1000L) { finishListening(); return }
            if (mode == "manual") { _sendProgress.value = 0f; continue }
            if (mode == "word") {
                _sendProgress.value = 0f
                if (endsWithSendWord(text) != null && n - lastChangeAt > 350) { finishListening(); return }
                continue
            }
            val pause = effectivePause(text, origin, mode)
            val quietFor = n - lastChangeAt
            val voiceQuiet = n - lastLoudAt
            // the text stopped changing and the microphone went quiet (or the text has been still for long)
            val ready = quietFor >= pause && (voiceQuiet >= pause * 6 / 10 || quietFor >= pause * 2)
            _sendProgress.value = if (voiceQuiet < 250) 0f else (quietFor.toFloat() / pause).coerceIn(0f, 1f)
            if (ready) { finishListening(); return }
        }
    }

    private val continuation = setOf(
        "and", "but", "or", "so", "because", "the", "a", "an", "to", "of", "with", "for", "um", "uh", "like", "then", "if",
        "which", "that", "my", "your", "in", "on", "at", "is", "are", "about", "also", "plus", "into", "from", "when", "while",
        "than", "just", "please", "maybe", "can", "could", "should", "would", "will", "this", "these", "those", "its", "our"
    )

    /** The pause that ends the sentence. Smart mode waits longer when the sentence sounds unfinished. */
    private fun effectivePause(text: String, origin: Origin, mode: String): Long {
        val base = JarvisPreferences.silenceMs.value.toLong().coerceIn(600, 4000)
        if (mode != "smart") return base
        val words = Regex("[\\p{L}0-9']+").findAll(text.lowercase()).map { it.value }.toList()
        var f = 1.0
        if (words.lastOrNull() in continuation) f *= 1.8
        val end = text.trimEnd()
        if (end.endsWith("?") || end.endsWith(".") || end.endsWith("!")) f *= 0.85
        if (origin == Origin.ASK || expectingReply) { if (words.size <= 3) f *= 0.6 }
        else if (words.size <= 2) f *= 1.25
        return (base * f).toLong().coerceIn(500, 6000)
    }

    private fun sendWordList(): List<String> = JarvisPreferences.sendWords.value.split(',')
        .map { it.trim().lowercase().trim('.', '!', '?') }.filter { it.isNotEmpty() }.sortedByDescending { it.length }

    private fun endsWithSendWord(text: String): String? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ',', ' ')
        return sendWordList().firstOrNull { w -> t == w || t.endsWith(" $w") }
    }

    private fun stripSendWord(text: String): String {
        val w = endsWithSendWord(text) ?: return text
        val t = text.trim().trimEnd('.', '!', '?', ',', ' ')
        return t.dropLast(w.length).trim().trimEnd(',').trim()
    }

    private fun snapshotCommitted(): List<String> = synchronized(lock) { committed.toList() }

    private fun joinText(parts: List<String>, tail: String): String =
        (parts + tail).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun transcriber(): SpeechTranscriber {
        if (sttFallback) return androidStt
        val chosen = JarvisPreferences.sttEngine.value.let {
            if (it == "auto") (if (JarvisPreferences.agentKind == AgentKind.CLAUDE) "claude" else "agy") else it
        }
        return when (chosen) {
            "claude" -> vm.claudeVoice
            "agy" -> vm.speechManager
            else -> androidStt
        }
    }

    private fun onSttError(t: SpeechTranscriber, err: String) {
        Log.w(TAG, "stt error: $err")
        val wasAndroid = t === androidStt
        val origin = listenOrigin
        cancelListening()
        if (!wasAndroid) {
            // the agent's dictation is unavailable: say so and use the device recognizer from now on (this app session)
            showNotice("Dictation unavailable ($err). Using the device recognizer.")
            sttFallback = true
            scope.launch { delay(300); startListening(origin = origin) }
        } else {
            showNotice(err)
            inConversation = false
        }
    }

    private fun cancelListening() {
        val t = synchronized(lock) {
            if (!listening.value) return
            listening.value = false
            activeStt
        }
        endpointJob?.cancel()
        _sendProgress.value = 0f
        _holding.value = false
        runCatching { t?.cancelTranscriptionSession() }
        _heard.value = ""
    }

    /** Throws away what was heard and stops listening, without sending anything and without touching the chat. */
    fun cancelCapture() {
        inConversation = false
        expectingReply = false
        pendingAsk?.complete("")
        cancelListening()
        _heard.value = ""
    }

    /** Silences Jarvis (and leaves its chat alone). */
    fun silence() {
        inConversation = false
        listenAfterRequested = false
        quietUntil = 0L
        JarvisVoice.stop()
    }

    /** Ends the sentence now and sends what was heard. */
    fun finishListening() {
        val t = synchronized(lock) {
            if (!listening.value) return
            listening.value = false
            activeStt
        }
        endpointJob?.cancel()
        _sendProgress.value = 0f
        _holding.value = false
        val origin = listenOrigin
        val snapCommitted = snapshotCommitted()
        val snapPartial = partial
        scope.launch {
            val done = CompletableDeferred<String>()
            runCatching { t?.stopTranscriptionSession { rest -> done.complete(rest) } }.onFailure { done.complete("") }
            val rest = withTimeoutOrNull(3000) { done.await() }.orEmpty()
            val nowCommitted = snapshotCommitted()
            var tail = rest.ifBlank { if (nowCommitted.size == snapCommitted.size) snapPartial else "" }
            if (nowCommitted.lastOrNull()?.trim() == tail.trim()) tail = ""
            deliver(joinText(nowCommitted, tail), origin)
        }
    }

    private val fillerWords = Regex("^(u+h+|u+m+|h+m+|a+h+|e+h+|o+h+|e+r+|m+|huh|hm+)[.!?, ]*$", RegexOption.IGNORE_CASE)

    private suspend fun deliver(text: String, origin: Origin) {
        var said = text.trim()
        if (JarvisPreferences.sendMode.value == "word") said = stripSendWord(said)
        _heard.value = said
        cue(2)
        if (said.isBlank()) { inConversation = false; return }
        pendingAsk?.let { addLog("You", said); it.complete(said); return }
        val wasExpecting = expectingReply
        expectingReply = false
        if (origin == Origin.FOLLOWUP && JarvisPreferences.sideTalk.value != "off" && !wasExpecting) {
            if (fillerWords.matches(said)) { inConversation = false; _heard.value = ""; return }
            if (JarvisPreferences.sideTalk.value == "strict" && !mentionsName(said)) { markIgnored("not addressed to me"); return }
        }
        addLog("You", said)
        if (handleLocalCommand(said, origin)) return
        outbox.trySend(Outgoing(said, if (wasExpecting && origin == Origin.FOLLOWUP) Origin.ASK else origin))
    }

    /** The model (jarvis_ignore) or the side-talk filter decided the words were not for Jarvis. */
    fun onIgnored(reason: String?) {
        markIgnored(reason?.takeIf { it.isNotBlank() } ?: "not for me")
        // the turn produced no speech on purpose: nothing is read out at its end
        turnSpoke = true
    }

    private fun markIgnored(reason: String) {
        inConversation = false
        listenAfterRequested = false
        postStatus("Ignored: ${reason.take(40)}", explicit = false)
    }

    // ------------------------------------------------------------------ phrases handled without the model

    private val stopWords = Regex("^(hey )?(jarvis[, ]*)?(stop|cancel|quiet|be quiet|shut up|never ?mind|enough|abort|stop talking)[.! ]*$", RegexOption.IGNORE_CASE)
    private val byeWords = Regex("^(hey )?(jarvis[, ]*)?(that'?s all|that is all|thanks|thank you|thanks jarvis|thank you jarvis|goodbye|bye|go to sleep|stop listening|i'?m done)[.! ]*$", RegexOption.IGNORE_CASE)
    private val newWords = Regex("^(hey )?(jarvis[, ]*)?(let'?s )?(start (again|over|fresh)|new (chat|session)|fresh (start|chat|session)|forget (that|everything)|reset)( .*)?[.! ]*$", RegexOption.IGNORE_CASE)
    private val ackWords = Regex("^(ok(ay)?|cool|great|perfect|nice|good|got it|alright|all right|sounds good|awesome|fine|right)([, ]+(thanks|thank you|jarvis))*[.! ]*$", RegexOption.IGNORE_CASE)
    private val repeatWords = Regex("^(hey )?(jarvis[, ]*)?(repeat( that| it)?|say (that|it) again|what did you (just )?say|come again|pardon( me)?|sorry\\?)[.?! ]*$", RegexOption.IGNORE_CASE)
    private val slowerWords = Regex("^(jarvis[, ]*)?(please )?((speak|talk)( a (bit|little))? (slower|more slowly)|slow down)[.! ]*$", RegexOption.IGNORE_CASE)
    private val fasterWords = Regex("^(jarvis[, ]*)?(please )?((speak|talk)( a (bit|little))? (faster|quicker|more quickly)|speed up)[.! ]*$", RegexOption.IGNORE_CASE)
    private val quietOnWords = Regex("^(jarvis[, ]*)?(quiet mode( on)?|text only( mode)?|mute (yourself|your voice))[.! ]*$", RegexOption.IGNORE_CASE)
    private val quietOffWords = Regex("^(jarvis[, ]*)?(quiet mode off|unmute( yourself)?|voice on|speak again|use your voice)[.! ]*$", RegexOption.IGNORE_CASE)

    private suspend fun handleLocalCommand(text: String, origin: Origin): Boolean {
        when {
            stopWords.matches(text) -> {
                stopEverything()
                speakOut("Stopped.")
                return true
            }
            byeWords.matches(text) -> {
                inConversation = false
                speakOut(if (text.contains("thank", true)) "Anytime." else "Okay.")
                return true
            }
            ackWords.matches(text) && (origin == Origin.FOLLOWUP || origin == Origin.ASK || text.length < 14) -> {
                // a simple acknowledgement ends the conversation without a round trip
                inConversation = false
                return true
            }
            repeatWords.matches(text) -> {
                val last = _said.value.ifBlank { _log.value.lastOrNull { it.first == "Jarvis" }?.second.orEmpty() }
                speakOut(last.ifBlank { "I haven't said anything yet." })
                if (JarvisPreferences.conversationMode.value) inConversation = true
                return true
            }
            slowerWords.matches(text) || fasterWords.matches(text) -> {
                val faster = fasterWords.matches(text)
                JarvisPreferences.ttsRate.set((JarvisPreferences.ttsRate.value + if (faster) 0.15f else -0.15f).coerceIn(0.5f, 2f))
                speakOut(if (faster) "Is this better?" else "Is this easier to follow?")
                return true
            }
            quietOnWords.matches(text) -> {
                JarvisPreferences.quietMode.set(true)
                speakOut("Quiet mode on. I'll show my answers instead.")
                return true
            }
            quietOffWords.matches(text) -> {
                JarvisPreferences.quietMode.set(false)
                speakOut("I'm back.")
                return true
            }
            newWords.matches(text) -> {
                val forget = text.contains("forget", ignoreCase = true)
                newSession(carry = !forget)
                speakOut("Fresh start. What's next?")
                return true
            }
        }
        return false
    }

    /** Text typed into the Jarvis panel: handled like spoken words. */
    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !JarvisPreferences.enabled.value) return
        scope.launch {
            pendingAsk?.let { addLog("You", t); it.complete(t); return@launch }
            addLog("You", t)
            if (!handleLocalCommand(t, Origin.TYPED)) outbox.trySend(Outgoing(t, Origin.TYPED))
        }
    }

    /** Stops speaking, listening and the Jarvis chat's current turn (tasks keep running). */
    fun stopEverything() {
        JarvisVoice.stop()
        quietUntil = 0L
        cancelListening()
        inConversation = false
        expectingReply = false
        listenAfterRequested = false
        turnOpen = false
        pendingAsk?.complete("")
        while (outbox.tryReceive().isSuccess) { /* drop queued sentences */ }
        scope.launch { stopChat(JarvisPreferences.currentChatId.value) }
    }

    // ------------------------------------------------------------------ the Jarvis chat

    private val correctionWords = Regex("^(no|nope|wait|stop|actually|instead|cancel|hold on|hang on|not that|wrong|scratch that|change of plans?|forget it)\\b", RegexOption.IGNORE_CASE)

    private suspend fun sendLoop() {
        for (m in outbox) {
            try {
                if (m.event) {
                    // a phone event never interrupts: wait until the user is not talking and Jarvis is free
                    var w = 0
                    while ((listening.value || pendingAsk != null || computeBusy()) && w < 1200) { delay(500); w++ }
                    if (!JarvisPreferences.enabled.value) continue
                    sendToJarvis(m)
                    continue
                }
                var interrupted = false
                if (computeBusy()) {
                    val mode = JarvisPreferences.busyBehavior.value
                    val interrupt = mode == "interrupt" || (mode == "smart" && correctionWords.containsMatchIn(m.text.trim()))
                    if (interrupt) {
                        postStatus("Switching to your new request", explicit = false)
                        stopChat(JarvisPreferences.currentChatId.value)
                        var w = 0
                        while (computeBusy() && w < 20) { delay(400); w++ }
                        interrupted = true
                    } else {
                        // never talk over a running turn: wait for it (the user can say stop)
                        speakOut("Noted. Right after this.")
                        var waited = 0
                        while (computeBusy() && waited < 1200) { delay(500); waited++ }
                    }
                }
                sendToJarvis(m, interrupted)
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
                showNotice("Jarvis could not send: ${e.message}")
                speakOut("Sorry, I couldn't reach the agent.")
            }
        }
    }

    private fun rotationReason(): String? {
        if (JarvisPreferences.currentChatId.value.isBlank()) return null
        val idle = JarvisPreferences.rotateIdleMinutes.value
        val last = JarvisPreferences.lastActivityAt.value
        if (idle > 0 && last > 0 && System.currentTimeMillis() - last > idle * 60_000L) return "idle for $idle minutes"
        val max = JarvisPreferences.rotateMaxMessages.value
        if (max > 0 && JarvisPreferences.currentChatMessages.value >= max) return "$max messages"
        return null
    }

    /** Next message goes to a brand new Jarvis chat. [carry] brings over the Jarvis memory and a short recap. */
    fun newSession(carry: Boolean = true) {
        carryText = if (carry && JarvisPreferences.carryOver.value) buildCarry() else ""
        JarvisPreferences.currentChatId.set("")
        JarvisPreferences.currentChatMessages.set(0)
        JarvisPreferences.bootSentFor.set("")
        lastReplySeen = ""
        _log.value = emptyList()
    }

    private fun buildCarry(): String {
        val recent = _log.value.takeLast(10).joinToString("\n") { "${it.first}: ${it.second}" }
        if (recent.isBlank()) return ""
        // the memory note itself is part of the guide of the new chat
        return "The previous Jarvis chat was closed. Its last lines (continue from here without repeating them):\n$recent"
    }

    private suspend fun sendToJarvis(m: Outgoing, interrupted: Boolean = false) {
        val reason = rotationReason()
        if (reason != null) {
            Log.i(TAG, "new Jarvis chat ($reason)")
            newSession(carry = true)
        }
        val carry = carryText
        carryText = ""
        val agent = JarvisPreferences.agentKind
        val chatId = JarvisPreferences.currentChatId.value
        val freshChat = chatId.isBlank() || JarvisPreferences.currentChatAgent.value != agent.name
        // the full guide goes into the first message of a chat (again when the guide changed with an app update)
        val needsBoot = freshChat || JarvisPreferences.bootSentFor.value != "$chatId|${JarvisSkill.version}"
        val heardVia = if (m.event) "phone event" else m.origin?.label
        val context = if (JarvisPreferences.contextInPrompt.value || needsBoot) JarvisSkill.liveContext(heardVia)
        else heardVia?.let { "heard_via: $it" }.orEmpty()
        val note = buildString {
            append(if (needsBoot) JarvisSkill.bootPack(context) else JarvisSkill.preamble(context))
            if (interrupted) append("\n(The user interrupted your previous turn with this message: switch to it.)")
            if (carry.isNotBlank()) append("\n\n").append(carry)
        }
        turnSpoke = false
        turnToolCalls = 0
        turnOpen = true
        firstTurnOfChat = freshChat
        val prompt = PromptExpansion.hidden(note) + m.text
        when (agent) {
            AgentKind.CLAUDE -> sendClaude(prompt)
            AgentKind.AGY -> sendAgy(prompt)
        }
        if (needsBoot) JarvisPreferences.bootSentFor.set("${JarvisPreferences.currentChatId.value}|${JarvisSkill.version}")
        JarvisPreferences.currentChatMessages.set(JarvisPreferences.currentChatMessages.value + 1)
        touch()
    }

    private suspend fun sendClaude(prompt: String) {
        val b = jarvisClaude
        var id = JarvisPreferences.currentChatId.value
        if (id.isBlank() || JarvisPreferences.currentChatAgent.value != AgentKind.CLAUDE.name) {
            b.startNewChat()
            delay(150)
            id = b.currentConversation.value?.id ?: error("Could not create the Jarvis chat")
            JarvisPreferences.currentChatId.set(id)
            JarvisPreferences.currentChatAgent.set(AgentKind.CLAUDE.name)
            JarvisPreferences.addChatId(id)
            JarvisPreferences.modelId.value.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
            scope.launch {
                delay(6000)
                b.renameConversation(id, "Jarvis · " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                runCatching { vm.claudeBackend.refreshSessions() }
            }
        } else if (b.currentConversation.value?.id != id) {
            b.selectConversation(id)
            delay(700)
        }
        b.sendMessage(prompt)
    }

    private suspend fun sendAgy(prompt: String) {
        var id = JarvisPreferences.currentChatId.value
        val model = JarvisPreferences.modelId.value.ifBlank { vm.agyBackend.selectedModelId.value }
        val modelEnum = AgyHubClient.resolveModelEnum(model)
        if (id.isBlank() || JarvisPreferences.currentChatAgent.value != AgentKind.AGY.name) {
            id = UUID.randomUUID().toString()
            hub.startCascade(id, modelEnum, defaultWorkspace()).getOrThrow()
            JarvisPreferences.currentChatId.set(id)
            JarvisPreferences.currentChatAgent.set(AgentKind.AGY.name)
            JarvisPreferences.addChatId(id)
        }
        agyBusyUntil = System.currentTimeMillis() + 5000
        JarvisTasks.clearAgyPending(id)
        hub.sendUserPrompt(
            cascadeId = id,
            text = prompt,
            modelEnum = modelEnum,
            autoExecutionPolicy = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"
        ).getOrThrow()
        scope.launch { delay(2500); runCatching { vm.agyBackend.syncAgyConversations(force = true) } }
    }

    private fun defaultWorkspace(): String {
        val p = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path
            ?: com.example.gemini.data.local.LocalEnvironmentManager.getHomeDir(app).absolutePath
        return if (p.startsWith("file://")) p else "file://$p"
    }

    // ------------------------------------------------------------------ chats Jarvis drives for the user

    fun agentOfChat(id: String): AgentKind =
        JarvisTasks.find(id)?.takeIf { it.id == id }?.agent
            ?: vm.allConversations.value.find { it.id == id }?.agent
            ?: if (JarvisPreferences.isJarvisChat(id) && JarvisPreferences.currentChatId.value == id) JarvisPreferences.agentKind else AgentKind.AGY

    /** Sends [text] to an existing chat by id; the chat becomes a watched task. Does not change the chat on screen. */
    suspend fun sendToChat(id: String, text: String): String {
        if (id == JarvisPreferences.currentChatId.value) return "That is the Jarvis chat itself."
        if (JarvisTasks.isTask(id)) return JarvisTasks.reply(id, text, interrupt = false)
        if (vm.allConversations.value.find { it.id == id }?.isRunning == true) {
            return "That chat is busy working, so nothing was sent. Do not interrupt it: handle the request yourself, or ask the user."
        }
        JarvisTasks.adopt(id, agentOfChat(id), text)
        return JarvisTasks.reply(id, text, interrupt = false)
    }

    /** Sets the model of a chat that is not on screen. Applies to its next message. */
    suspend fun setChatModel(id: String, kind: AgentKind, modelId: String) {
        when (kind) {
            AgentKind.AGY -> vm.agyBackend.rememberChatModel(id, modelId)
            AgentKind.CLAUDE -> {
                val task = JarvisTasks.backendOf(id)
                when {
                    id == JarvisPreferences.currentChatId.value && JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name -> jarvisClaude.selectModel(modelId)
                    task != null -> task.selectModel(modelId)
                    else -> {
                        val b = opsClaude
                        if (b.currentConversation.value?.id != id) { b.selectConversation(id); delay(900) }
                        b.selectModel(modelId)
                    }
                }
            }
        }
    }

    suspend fun stopChat(id: String): String {
        if (id.isBlank()) return "No chat."
        if (JarvisTasks.isTask(id)) return JarvisTasks.stop(id)
        return when (agentOfChat(id)) {
            AgentKind.CLAUDE -> {
                when {
                    id == JarvisPreferences.currentChatId.value && JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name -> jarvisClaude.stopStreaming()
                    vm.currentConversation.value?.id == id -> vm.claudeBackend.stopStreaming()
                    else -> {
                        val b = opsClaude
                        if (b.currentConversation.value?.id != id) { b.selectConversation(id); delay(700) }
                        b.stopStreaming()
                    }
                }
                "Stopped."
            }
            AgentKind.AGY -> hub.cancelCascadeInvocation(id).fold({ "Stopped." }, { "Could not stop: ${it.message}" })
        }
    }

    /** Messages of any chat (Claude: from the transcript; Antigravity: from its trajectory). */
    suspend fun readChat(id: String): List<ChatMessage> = when (agentOfChat(id)) {
        AgentKind.CLAUDE -> vm.claudeBackend.loadMessages(id)
        AgentKind.AGY -> {
            val steps = hub.getCascadeTrajectorySteps(id).getOrNull().orEmpty()
            com.example.gemini.domain.chat.TrajectoryEngine().ingestStepsDirect(steps, id)
        }
    }

    // ------------------------------------------------------------------ speaking and asking

    /** Says [text], or shows it in quiet mode. With [wait] it returns after it was said. */
    private suspend fun speakOut(text: String, wait: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        _said.value = t
        if (JarvisPreferences.quietMode.value) {
            val dur = (1800L + t.length * 55L).coerceAtMost(9000L)
            quietUntil = System.currentTimeMillis() + dur
            cue(3)
            if (wait) delay(minOf(dur, 1200L))
            return
        }
        JarvisVoice.speak(t, wait = wait)
    }

    /** Jarvis says [text] (called by the jarvis_speak tool). With [listenAfter] the microphone opens afterwards. */
    suspend fun say(text: String, listenAfter: Boolean = false, wait: Boolean = false) {
        addLog("Jarvis", text)
        turnSpoke = true
        if (listenAfter) { inConversation = true; listenAfterRequested = true; expectingReply = true }
        else if (text.trimEnd().endsWith("?")) expectingReply = true
        speakOut(text, wait = wait)
    }

    /** Speaks [question] and returns what the user answers (null when nothing was heard in time). */
    suspend fun ask(question: String, timeoutMs: Long = ASK_TIMEOUT_MS): String? = askMutex.withLock {
        val d = CompletableDeferred<String>()
        pendingAsk = d
        try {
            addLog("Jarvis", question)
            turnSpoke = true
            speakOut(question, wait = true)
            if (!startListening(origin = Origin.ASK)) return null
            withTimeoutOrNull(timeoutMs) { d.await() }?.takeIf { it.isNotBlank() }
        } finally {
            pendingAsk = null
            cancelListening()
        }
    }

    private fun addLog(who: String, text: String) {
        _log.value = (_log.value + (who to text)).takeLast(40)
    }

    // ------------------------------------------------------------------ wake phrase

    private val nameAliases = mapOf(
        "jarvis" to listOf("jarvis", "jervis", "jarvas", "jarves", "jarvys", "javis", "jarviss", "jarvi")
    )
    @Volatile private var wakeKey = ""
    @Volatile private var wakeRegexCache: Regex? = null

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun wakePhrases(): List<String> =
        JarvisPreferences.wakePhrase.value.split(',', '|').map { normalize(it) }.filter { it.isNotBlank() }.ifEmpty { listOf("hey jarvis") }

    /** The phrase with the usual mishearings of its name ("hey jervis"). */
    private fun variants(phrase: String): List<String> {
        val out = ArrayList<String>()
        out += phrase
        nameAliases.forEach { (name, aliases) -> if (phrase.contains(name)) aliases.forEach { out += phrase.replace(name, it) } }
        return out.distinct()
    }

    private fun names(): List<String> = wakePhrases().map { it.substringAfterLast(' ') }.flatMap { variants(it) }.distinct()

    private fun wakeRegex(): Regex {
        val key = JarvisPreferences.wakePhrase.value + "|" + JarvisPreferences.wakeMatch.value
        wakeRegexCache?.let { if (key == wakeKey) return it }
        val cands = (wakePhrases().flatMap { variants(it) } + if (JarvisPreferences.wakeMatch.value == "name") names() else emptyList()).distinct()
        val alts = cands.sortedByDescending { it.length }.joinToString("|") { c -> c.split(' ').joinToString("[\\s,.!?]+") { Regex.escape(it) } }
        val r = Regex("(?<![\\p{L}])(?:$alts)(?![\\p{L}])", RegexOption.IGNORE_CASE)
        wakeKey = key
        wakeRegexCache = r
        return r
    }

    private fun mentionsName(text: String): Boolean {
        val t = normalize(text)
        return names().any { Regex("(^| )" + Regex.escape(it) + "( |$)").containsMatchIn(t) }
    }

    private fun pumpWake(speaking: Boolean) {
        val want = JarvisPreferences.handsFree.value && JarvisPreferences.hotword.value == "phrase" && !listening.value && !speaking && hasMic()
        if (want && !wakeRunning) startWake()
        else if (!want && wakeRunning) stopWake()
        // a heard wake phrase fires once the sentence after it has ended
        if (wakeHit && System.currentTimeMillis() - wakeChangedAt >= 1300) {
            val text = wakeText
            wakeHit = false
            wakeText = ""
            stopWake()
            val m = wakeRegex().find(text)
            val rest = if (m != null) text.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?') else ""
            scope.launch {
                if (rest.length > 2) { inConversation = true; expectingReply = false; cue(1); deliver(rest, Origin.WAKE) } else startListening(origin = Origin.WAKE)
            }
        }
    }

    private fun startWake() {
        wakeRunning = true
        wakeHit = false
        wakeText = ""
        val regex = wakeRegex()
        val onText = { t: String ->
            if (wakeHit || regex.containsMatchIn(t)) {
                wakeHit = true
                wakeText = t
                wakeChangedAt = System.currentTimeMillis()
            }
        }
        wakeStt.startTranscriptionSession(
            scope = scope, cascadeId = "", preCursorText = "", postCursorText = "",
            onPartialText = { onText(it) },
            onFinalText = { onText(it) },
            onError = { err -> showNotice("Wake phrase: $err"); wakeRunning = false }
        )
    }

    private fun stopWake() {
        if (!wakeRunning) return
        wakeRunning = false
        runCatching { wakeStt.cancelTranscriptionSession() }
    }

    // ------------------------------------------------------------------ permissions

    class Perm(
        val key: String,
        /** "jarvis" (the Jarvis chat), "task" (a task), "ops" (a chat Jarvis drives) or "screen" (the chat on screen). */
        val source: String,
        val chatId: String,
        val tool: String,
        val summary: String,
        val risk: Risk,
        val approve: () -> Unit,
        val reject: (String?) -> Unit
    )

    private val readTools = setOf("Read", "Glob", "Grep", "LS", "NotebookRead", "WebSearch", "WebFetch", "TodoWrite", "ToolSearch")
    private val riskyBash = Regex(
        "\\brm\\s|\\bsudo\\b|git\\s+push|git\\s+reset\\s+--hard|git\\s+clean|\\bmkfs|\\bdd\\s+if=|chmod\\s+-R|chown\\s|(curl|wget)[^|]*\\|\\s*(sh|bash)|:\\(\\)\\s*\\{|\\bpkill\\b|kill\\s+-9|drop\\s+table|npm\\s+publish|--force|\\bformat\\b|>\\s*/(dev|etc|usr)"
    )
    private val readBash = Regex("^\\s*(ls|cat|pwd|echo|head|tail|wc|grep|rg|find|which|date|whoami|df|du|ps|stat|file|tree|git\\s+(status|log|diff|branch|show|remote))\\b")
    private val unsafeInRead = Regex("[>;&]|\\b(rm|mv|cp|sh|bash|xargs|sudo|tee|dd)\\b|-delete|-exec")

    fun bashRisk(cmd: String?): Risk {
        val c = cmd.orEmpty()
        if (riskyBash.containsMatchIn(c)) return Risk.RISKY
        if (readBash.containsMatchIn(c) && !unsafeInRead.containsMatchIn(c)) return Risk.READ
        return Risk.NORMAL
    }

    /** Jarvis' own tools (any agent's naming) never need the user. */
    private fun isJarvisTool(name: String) =
        name.startsWith("mcp__jarvis_mcp") || name.startsWith("mcp_jarvis_mcp") || name.startsWith("jarvis_")

    private fun jstr(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    private fun claudePerms(b: ClaudeChatBackend, source: String, chatId: String = b.currentConversation.value?.id.orEmpty()): List<Perm> =
        b.pendingPermissions.value.map { p ->
            val cmd = jstr(p.input, "command")
            val file = jstr(p.input, "file_path") ?: jstr(p.input, "path")
            val summary = when {
                p.toolName == "Bash" -> "run the command: ${(cmd ?: "").take(140)}"
                p.toolName in setOf("Write", "Edit", "MultiEdit", "NotebookEdit") -> "edit ${file?.substringAfterLast('/') ?: "a file"}"
                p.toolName == "Read" -> "read ${file?.substringAfterLast('/') ?: "a file"}"
                p.toolName == "WebFetch" -> "open ${jstr(p.input, "url") ?: "a web page"}"
                p.toolName == "ExitPlanMode" -> "start working on its plan"
                p.toolName.startsWith("mcp__") -> "use the tool ${p.toolName.removePrefix("mcp__").replace("__", " ")}"
                else -> "use ${p.toolName}"
            }
            val risk = when {
                isJarvisTool(p.toolName) || p.toolName in readTools -> Risk.READ
                p.toolName == "Bash" -> bashRisk(cmd)
                else -> Risk.NORMAL
            }
            Perm(
                key = p.toolUseId, source = source, chatId = chatId, tool = p.toolName,
                summary = summary, risk = risk,
                approve = {
                    if (p.toolName == "ExitPlanMode") b.approvePlan(p.toolUseId, "default")
                    else b.approveTool(ToolCall(id = p.toolUseId, command = ""))
                },
                reject = { why -> b.rejectToolById(p.toolUseId, why) }
            )
        }

    private fun agyRisk(t: ToolCall): Risk = when (t.toolType) {
        ToolType.VIEW_FILE, ToolType.LIST_DIR, ToolType.GREP_SEARCH, ToolType.FIND, ToolType.SEARCH_WEB, ToolType.READ_URL -> Risk.READ
        ToolType.BASH -> bashRisk(t.command)
        ToolType.MCP -> if (t.name.contains("jarvis", true)) Risk.READ else Risk.NORMAL
        else -> Risk.NORMAL
    }

    private fun agySummary(t: ToolCall): String = when (t.toolType) {
        ToolType.BASH -> "run the command: ${t.command.take(140)}"
        ToolType.EDIT_FILE -> "edit ${t.command.substringAfterLast('/').take(60)}"
        else -> "use ${t.name} ${t.command.take(80)}".trim()
    }

    /** Approvals of the Antigravity chat on screen. */
    private fun agyPerms(): List<Perm> {
        val chat = vm.currentConversation.value?.id.orEmpty()
        return vm.pendingApprovals.value.map { a ->
            val t = a.toolCall
            Perm(
                key = "agy:$chat:${t.stepIndex}", source = "screen", chatId = chat, tool = t.name,
                summary = agySummary(t), risk = agyRisk(t),
                approve = { vm.approveAndExecuteTerminalTool(t, a.messageId) },
                reject = { why -> vm.rejectTerminalTool(t, a.messageId, why) }
            )
        }
    }

    /** Approvals of an Antigravity chat that is not on screen (a task or the Jarvis chat), answered through the hub. */
    private fun agyOffscreenPerms(chatId: String, source: String): List<Perm> =
        JarvisTasks.agyPendingOf(chatId).map { (t, _) ->
            Perm(
                key = "agy:$chatId:${t.stepIndex}", source = source, chatId = chatId, tool = t.name,
                summary = agySummary(t), risk = agyRisk(t),
                approve = { scope.launch { JarvisTasks.resolveAgy(chatId, t, true, null) } },
                reject = { why -> scope.launch { JarvisTasks.resolveAgy(chatId, t, false, why) } }
            )
        }

    /** Everything waiting for approval in the chats Jarvis can reach (one entry per request). */
    fun allPending(): List<Perm> {
        val out = LinkedHashMap<String, Perm>()
        fun add(list: List<Perm>) = list.forEach { out.putIfAbsent(it.key, it) }
        // tasks first: their entry wins when the same chat is also on screen
        JarvisTasks.claudeTaskBackends().forEach { (t, b) -> if (t.active) add(claudePerms(b, "task", t.id)) }
        JarvisTasks.activeTasks().filter { it.agent == AgentKind.AGY && vm.currentConversation.value?.id != it.id }.forEach { add(agyOffscreenPerms(it.id, "task")) }
        val jarvisId = JarvisPreferences.currentChatId.value
        if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name || jarvisId.isBlank()) add(claudePerms(jarvisClaude, "jarvis"))
        else add(agyOffscreenPerms(jarvisId, "jarvis"))
        add(claudePerms(opsClaude, "ops"))
        val onScreen = vm.currentConversation.value
        val screenList = if (onScreen?.agent == AgentKind.CLAUDE) claudePerms(vm.claudeBackend, "screen") else agyPerms()
        // the chat on screen counts as a task's when it is one
        add(if (onScreen != null && JarvisTasks.isTask(onScreen.id)) screenList.map { Perm(it.key, "task", it.chatId, it.tool, it.summary, it.risk, it.approve, it.reject) } else screenList)
        return out.values.toList()
    }

    private fun collectPermissions() {
        val live = allPending()
        val liveKeys = live.map { it.key }.toSet()
        handledPermissions.retainAll(liveKeys)
        for (p in live) {
            if (p.key in handledPermissions) continue
            // the chat on screen is only handled when hands-free is on; chats Jarvis runs always are
            if (p.source == "screen" && !JarvisPreferences.handsFree.value) continue
            handledPermissions += p.key
            permQueue.trySend(p)
        }
    }

    private fun approvalEventText(t: JarvisTasks.Task?, p: Perm): String =
        "[Phone event, not from the user] The task \"${t?.title ?: "a chat"}\" (task id ${p.chatId}) wants to ${p.summary} " +
            "(risk: ${p.risk.name.lowercase()}, approval key ${p.key}).\n" +
            (t?.goal?.let { "Task goal: ${it.take(300)}\n" } ?: "") +
            "Decide like a careful manager: if it clearly serves that goal and is not risky, approve it yourself with " +
            "jarvis_resolve_permission(key=\"${p.key}\", approve=true) without bothering the user. If it is risky or unclear, read the action " +
            "back to the user in plain words with jarvis_ask, then approve or reject accordingly."

    private suspend fun permissionLoop() {
        for (p in permQueue) {
            try {
                if (allPending().none { it.key == p.key }) continue
                val policy = JarvisPreferences.permissionPolicy.value
                val auto = isJarvisTool(p.tool) ||
                    policy == "auto_all" ||
                    (policy == "auto_except_risky" && p.risk != Risk.RISKY) ||
                    (policy == "auto_readonly" && p.risk == Risk.READ)
                if (auto) { p.approve(); continue }
                if (p.source == "task" && JarvisPreferences.taskApprovals.value == "jarvis" && JarvisPreferences.eventRouting.value == "model") {
                    outbox.trySend(Outgoing(approvalEventText(JarvisTasks.find(p.chatId), p), event = true))
                    continue
                }
                val who = if (p.source == "task") JarvisTasks.find(p.chatId)?.title?.let { "The task $it" } ?: "It" else "It"
                val prefix = if (p.risk == Risk.RISKY) "Careful, this looks risky. " else ""
                val answer = ask("$prefix$who wants to ${p.summary}. Approve?")
                when (parseYesNo(answer)) {
                    Decision.YES_ALL -> {
                        p.approve()
                        allPending().filter { it.key != p.key && it.risk != Risk.RISKY }.forEach { it.approve() }
                    }
                    Decision.YES -> p.approve()
                    Decision.NO -> { p.reject("The user said no by voice."); speakOut("Rejected.") }
                    Decision.UNCLEAR -> {
                        if (answer != null) { p.reject("The user's answer was unclear, so nothing was approved."); speakOut("I didn't get that, so I rejected it.") }
                        else { handledPermissions.remove(p.key) /* ask again on the next look */; delay(20_000) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "permission: ${e.message}")
            }
        }
    }

    enum class Decision { YES, YES_ALL, NO, UNCLEAR }

    private fun parseYesNo(text: String?): Decision {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return Decision.UNCLEAR
        val no = Regex("\\b(no|nope|reject|deny|don'?t|do not|stop|cancel|negative|decline)\\b")
        val yes = Regex("\\b(yes|yeah|yep|yup|approve|allow|go ahead|do it|ok|okay|sure|accept|proceed|confirm|affirmative)\\b")
        return when {
            no.containsMatchIn(t) -> Decision.NO
            yes.containsMatchIn(t) && Regex("\\b(all|everything|always)\\b").containsMatchIn(t) -> Decision.YES_ALL
            yes.containsMatchIn(t) -> Decision.YES
            else -> Decision.UNCLEAR
        }
    }

    /** Resolves a waiting permission from a tool call. */
    fun resolve(key: String?, approve: Boolean, all: Boolean, reason: String?): String {
        val list = allPending()
        val targets = if (all || key.isNullOrBlank()) list else list.filter { it.key == key }
        if (targets.isEmpty()) return "Nothing is waiting for approval."
        targets.forEach { if (approve) it.approve() else it.reject(reason) }
        return "${if (approve) "Approved" else "Rejected"} ${targets.size} request${if (targets.size == 1) "" else "s"}."
    }

    // ------------------------------------------------------------------ small cues

    @Suppress("DEPRECATION")
    private fun cue(kind: Int) {
        runCatching {
            if (JarvisPreferences.haptics.value) {
                val v = app.getSystemService(Vibrator::class.java)
                val ms = when (kind) { 1 -> 25L; 3 -> 10L; else -> 15L }
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                else v?.vibrate(ms)
            }
            if (kind != 3 && JarvisPreferences.earcons.value) {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
                tg.startTone(if (kind == 1) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 110)
                scope.launch { delay(300); tg.release() }
            }
        }
    }
}
