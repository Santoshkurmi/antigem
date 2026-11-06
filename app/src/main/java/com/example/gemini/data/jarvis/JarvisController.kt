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
 * The brain of Jarvis inside the app: listens (button or wake phrase), sends what was heard to a hidden chat of the
 * chosen agent, speaks what the model says through jarvis_mcp, answers permission prompts by voice according to the
 * user's policy, and starts fresh chats when the user asks (or when their own limits say so).
 *
 * The Jarvis chat is a normal Claude Code or Antigravity chat: Claude's runs on a second [ClaudeChatBackend] with its
 * own state (so it never takes over the chat on screen), Antigravity's is driven through the hub client by id.
 */
object JarvisController {
    private const val TAG = "JarvisController"
    private const val NO_SPEECH_MS = 9_000L
    private const val ASK_TIMEOUT_MS = 45_000L

    enum class Phase { OFF, IDLE, LISTENING, THINKING, SPEAKING }
    enum class Risk { READ, NORMAL, RISKY }

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
    /** The screen the user is on: chat, ide, terminal, browser, notes. */
    val screen = MutableStateFlow("chat")
    private val _log = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The last lines of the conversation: "You" / "Jarvis" to text. */
    val log: StateFlow<List<Pair<String, String>>> = _log.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** A problem the user should see (missing permission, voice error). */
    val notice: StateFlow<String?> = _notice.asStateFlow()
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
    /** Backend for chats Jarvis drives on the user's behalf (send_to_chat, stop_chat, permissions). */
    val opsClaude: ClaudeChatBackend by lazy {
        ClaudeChatBackend(app, scope, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())
    }
    private val hub by lazy { AgyHubClient() }
    @Volatile private var agyBusyUntil = 0L

    // ---- listening state
    private val lock = Any()
    private val committed = ArrayList<String>()
    @Volatile private var partial = ""
    @Volatile private var lastChangeAt = 0L
    @Volatile private var startedAt = 0L
    private var activeStt: SpeechTranscriber? = null
    private var endpointJob: Job? = null
    @Volatile private var inConversation = false
    @Volatile private var pendingAsk: CompletableDeferred<String>? = null
    private val askMutex = Mutex()
    private val androidStt by lazy { AndroidSpeechTranscriber(app) { JarvisPreferences.sttLanguage.value } }
    private val wakeStt by lazy { AndroidSpeechTranscriber(app) { JarvisPreferences.sttLanguage.value } }
    @Volatile private var wakeRunning = false
    @Volatile private var wakeHit = false
    @Volatile private var wakeText = ""
    @Volatile private var wakeChangedAt = 0L

    private val outbox = Channel<String>(Channel.UNLIMITED)
    /** Chats Jarvis started or wrote to (id → the task), so the phone can tell Jarvis when they finish. */
    private val watched: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    private val EVENT = "\u0001"
    @Volatile private var carryText = ""
    private val handledPermissions: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val permQueue = Channel<Perm>(Channel.UNLIMITED)
    private val handledQuestions: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val questionQueue = Channel<PendingQuestion>(Channel.UNLIMITED)
    /** The Jarvis chat is working on a message the user spoke, and has not said anything yet. */
    @Volatile private var turnOpen = false
    @Volatile private var turnSpoke = false

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
        }
    }

    /** Hands-free keeps the microphone service alive so the wake phrase works with the screen off. */
    fun setHandsFree(on: Boolean) {
        val target = on && JarvisPreferences.enabled.value
        JarvisPreferences.handsFree.set(target)
        if (target) {
            if (!hasMic()) { _notice.value = "Allow the microphone first (Settings → Jarvis)."; JarvisPreferences.handsFree.set(false); return }
            JarvisService.start(app)
        } else {
            stopWake()
            JarvisService.stop(app)
        }
    }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun clearNotice() { _notice.value = null }

    /** 0..1 live level of whatever is making sound right now (microphone while listening, voice while speaking). */
    fun level(): Float = when {
        listening.value -> activeStt?.latestAmplitude?.value ?: 0.1f
        JarvisVoice.speaking.value -> JarvisVoice.level.value
        else -> 0f
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
                val speaking = JarvisVoice.speaking.value
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
                    // follow-up question: listen again after Jarvis finished talking
                    val now = System.currentTimeMillis()
                    if (speakEndedAt > 0 && !speaking && !isBusy && !listening.value && pendingAsk == null &&
                        now - speakEndedAt > 700
                    ) {
                        speakEndedAt = 0
                        if (inConversation && JarvisPreferences.conversationMode.value) startListening()
                    }
                    if (wasBusy && !isBusy) { touch(); onJarvisTurnEnded() }
                    if (isBusy && now - lastExplicitStatusAt > 3000) {
                        deriveToolStatus()?.let { if (it != lastDerived) { lastDerived = it; postStatus(it, explicit = false) } }
                    }
                    val last = _steps.value.lastOrNull()
                    if (last != null && !isBusy && !speaking && !listening.value && now - last.at > 5000) _steps.value = emptyList()
                    wasBusy = isBusy
                    collectPermissions()
                    collectQuestions()
                    pumpWake(speaking)
                    if (++tick % 200 == 0) checkUsageLow()
                } else {
                    stopWake()
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
            scope.launch { JarvisVoice.speak(t, wait = false) }
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
            n.startsWith("mcp__") -> "Using ${n.removePrefix("mcp__").substringBefore("__").replace('_', ' ')}"
            n == "bash" || n.contains("terminal") || n.contains("command") -> "Running ${call.command.trim().take(32).ifBlank { "a command" }}"
            n.contains("read") || n.contains("view") -> "Reading a file"
            n.contains("write") || n.contains("edit") -> "Editing a file"
            n.contains("grep") || n.contains("glob") || n.contains("find") || n.contains("search") && !n.contains("web") -> "Searching the code"
            n.contains("web") -> "Looking it up online"
            else -> "Working on it"
        }
    }

    // ------------------------------------------------------------------ the turn ended without a spoken answer

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

    /**
     * The Jarvis chat finished a turn. If the model never called jarvis_speak (it answered in plain text), read the start of
     * that answer out loud so the user is never left in silence.
     */
    private fun onJarvisTurnEnded() {
        if (!turnOpen) return
        turnOpen = false
        if (turnSpoke || pendingAsk != null) return
        scope.launch {
            delay(700) // a late jarvis_speak may still be on its way
            if (turnSpoke || JarvisVoice.speaking.value || pendingQuestions().isNotEmpty() || listening.value) return@launch
            val id = JarvisPreferences.currentChatId.value
            val raw = if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
                jarvisClaude.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
            } else {
                runCatching { readChat(id).lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content }.getOrNull()
            }
            val text = raw?.let { limitSpoken(PromptExpansion.collapse(it)) }
            if (!text.isNullOrBlank()) {
                addLog("Jarvis", text)
                JarvisVoice.speak(text, wait = false)
                if (JarvisPreferences.conversationMode.value) inConversation = true
            }
        }
    }

    // ------------------------------------------------------------------ questions the agent asks (AskUserQuestion)

    class PendingQuestion(val key: String, val backend: ClaudeChatBackend, val call: ToolCall, val source: String)

    /** Choice questions waiting for an answer in the chats Jarvis can reach. */
    fun pendingQuestions(): List<PendingQuestion> = buildList {
        fun scan(b: ClaudeChatBackend, source: String) {
            b.messages.value.asSequence()
                .filter { it.role == MessageRole.ASSISTANT }
                .flatMap { it.toolCalls.asSequence() }
                .filter { it.toolType == ToolType.ASK_CHOICE && it.status == "AWAITING_CHOICE" && it.questionnaire != null }
                .forEach { add(PendingQuestion(it.id, b, it, source)) }
        }
        if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) scan(jarvisClaude, "jarvis")
        scan(opsClaude, "ops")
        if (vm.currentConversation.value?.agent == AgentKind.CLAUDE) scan(vm.claudeBackend, "screen")
    }

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

    private suspend fun questionLoop() {
        for (q in questionQueue) {
            try {
                if (pendingQuestions().none { it.key == q.key }) continue
                val qn = q.call.questionnaire ?: continue
                val responses = ArrayList<AskQuestionResponseItemDto>()
                val summary = ArrayList<String>()
                var skipped = false
                for (question in qn.questions) {
                    val answer = ask(describeQuestion(question), 60_000)
                    if (answer == null || skipWords.containsMatchIn(answer)) { skipped = true; break }
                    val (selected, write) = pickOptions(answer, question)
                    responses += AskQuestionResponseItemDto(
                        question = question.prompt,
                        options = question.options.map { AskQuestionOptionDto(id = it.id, text = it.label, label = it.label, description = it.description.orEmpty()) },
                        selectedOptionIds = selected.map { it.id },
                        isMultiSelect = question.isMultiSelect,
                        writeInResponse = write
                    )
                    summary += (selected.map { it.label } + listOfNotNull(write)).joinToString(", ")
                }
                if (skipped) {
                    q.backend.skipChoices(q.call)
                    JarvisVoice.speak("Okay, I told it to use its best judgement.", wait = false)
                } else {
                    q.backend.submitChoices(q.call, responses, summary.joinToString(" · "))
                    JarvisVoice.speak("Got it.", wait = false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "question: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ announcements

    private fun firstSentence(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val end = Regex("[.!?](\\s|$)").find(flat)?.range?.first?.plus(1) ?: flat.length
        return flat.take(minOf(end, max)).trim()
    }

    /** A chat finished working: tell the user (when they turned announcements on). */
    private suspend fun onChatDone(c: Conversation) {
        if (!JarvisPreferences.enabled.value) return
        val task = watched.remove(c.id)
        if (task != null) {
            // Jarvis started or wrote to this chat: the phone tells Jarvis, who tells the user (and can follow up)
            val last = runCatching {
                readChat(c.id).lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
            }.getOrNull()?.let { PromptExpansion.collapse(it).trim() }.orEmpty()
            val title = c.title.trim().take(60).ifBlank { "untitled" }
            postStatus("Done: $title", explicit = false)
            outbox.trySend(EVENT + buildString {
                append("[Phone event, not from the user] The chat \"$title\" (id ${c.id}, ${c.agent.name.lowercase()}) that you started or wrote to has finished working.\n")
                append("Its task was: ${task.take(200)}\n")
                append(if (last.isNotBlank()) "Its last reply (may be cut):\n${last.takeLast(900)}\n" else "It left no text reply; use jarvis_read_chat if you need details.\n")
                append("Tell the user briefly with jarvis_speak what happened. If the chat is asking the user something, ask the user and pass the answer on with jarvis_send_to_chat (reason continues_that_chats_work). Do not open the chat or start other work unless the user asks.")
            })
            return
        }
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
        JarvisVoice.speak(msg, wait = false)
    }

    private var usageWarnedFor = 0L

    private fun checkUsageLow() {
        if (!JarvisPreferences.enabled.value || !JarvisPreferences.announceUsageLow.value) return
        val w = vm.claudeBackend.rateLimits.value["five_hour"] ?: return
        val left = 1.0 - w.utilization
        val window = w.resetsAt ?: 0L
        if (left <= 0.15 && window != usageWarnedFor && !listening.value) {
            usageWarnedFor = window
            scope.launch { JarvisVoice.speak("Heads up, only about ${Math.round(left * 100).coerceAtLeast(0)} percent of your five hour usage is left.", wait = false) }
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
                JarvisVoice.speak("Your ${item.label.ifBlank { "timer" }} is done.", wait = false)
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
    fun onMicPressed() {
        if (!JarvisPreferences.enabled.value) return
        when {
            listening.value -> finishListening()
            JarvisVoice.speaking.value -> { JarvisVoice.stop(); startListening() }
            else -> startListening()
        }
    }

    fun startListening(initial: String = ""): Boolean {
        if (!JarvisPreferences.enabled.value) return false
        if (listening.value) return true
        if (!hasMic()) { _notice.value = "Allow the microphone first (Settings → Jarvis)."; return false }
        JarvisVoice.stop()
        stopWake()
        synchronized(lock) {
            committed.clear()
            partial = initial
        }
        _heard.value = initial
        val now = System.currentTimeMillis()
        lastChangeAt = now
        startedAt = now
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
        endpointJob = scope.launch {
            while (isActive && listening.value) {
                delay(150)
                val text = joinText(snapshotCommitted(), partial)
                val n = System.currentTimeMillis()
                when {
                    text.isNotBlank() && n - lastChangeAt > JarvisPreferences.silenceMs.value -> { finishListening(); return@launch }
                    text.isBlank() && n - startedAt > NO_SPEECH_MS -> { cancelListening(); inConversation = false; return@launch }
                    n - startedAt > 120_000 -> { finishListening(); return@launch }
                }
            }
        }
        return true
    }

    private fun snapshotCommitted(): List<String> = synchronized(lock) { committed.toList() }

    private fun joinText(parts: List<String>, tail: String): String =
        (parts + tail).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun transcriber(): SpeechTranscriber {
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
        cancelListening()
        if (!wasAndroid) {
            // the agent's dictation is unavailable: say so and fall back to the device recognizer for this try
            _notice.value = "Dictation unavailable ($err). Using the device recognizer."
            scope.launch {
                delay(300)
                val saved = JarvisPreferences.sttEngine.value
                JarvisPreferences.sttEngine.set("android")
                startListening()
                // keep the user's choice for the next time
                delay(500)
                JarvisPreferences.sttEngine.set(saved)
            }
        } else {
            _notice.value = err
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
        runCatching { t?.cancelTranscriptionSession() }
        _heard.value = ""
    }

    /** Throws away what was heard and stops listening, without sending anything and without touching the chat. */
    fun cancelCapture() {
        inConversation = false
        pendingAsk?.complete("")
        cancelListening()
        _heard.value = ""
    }

    /** Silences Jarvis (and leaves its chat alone). */
    fun silence() {
        inConversation = false
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
        val snapCommitted = snapshotCommitted()
        val snapPartial = partial
        scope.launch {
            val done = CompletableDeferred<String>()
            runCatching { t?.stopTranscriptionSession { rest -> done.complete(rest) } }.onFailure { done.complete("") }
            val rest = withTimeoutOrNull(3000) { done.await() }.orEmpty()
            val nowCommitted = snapshotCommitted()
            var tail = rest.ifBlank { if (nowCommitted.size == snapCommitted.size) snapPartial else "" }
            if (nowCommitted.lastOrNull()?.trim() == tail.trim()) tail = ""
            deliver(joinText(nowCommitted, tail))
        }
    }

    private suspend fun deliver(text: String) {
        val said = text.trim()
        _heard.value = said
        cue(2)
        if (said.isBlank()) { inConversation = false; return }
        addLog("You", said)
        pendingAsk?.let { it.complete(said); return }
        if (handleLocalCommand(said)) return
        outbox.trySend(said)
    }

    // ------------------------------------------------------------------ phrases handled without the model

    private val stopWords = Regex("^(hey )?(jarvis[, ]*)?(stop|cancel|quiet|be quiet|shut up|never ?mind|enough|abort)[.! ]*$", RegexOption.IGNORE_CASE)
    private val byeWords = Regex("^(hey )?(jarvis[, ]*)?(that'?s all|that is all|thanks|thank you|goodbye|bye|go to sleep|stop listening|i'?m done)[.! ]*$", RegexOption.IGNORE_CASE)
    private val newWords = Regex("^(hey )?(jarvis[, ]*)?(let'?s )?(start (again|over|fresh)|new (chat|session)|fresh (start|chat|session)|forget (that|everything)|reset)( .*)?[.! ]*$", RegexOption.IGNORE_CASE)

    private suspend fun handleLocalCommand(text: String): Boolean {
        when {
            stopWords.matches(text) -> {
                stopEverything()
                JarvisVoice.speak("Stopped.", wait = false)
                return true
            }
            byeWords.matches(text) -> {
                inConversation = false
                JarvisVoice.speak("Okay.", wait = false)
                return true
            }
            newWords.matches(text) -> {
                val forget = text.contains("forget", ignoreCase = true)
                newSession(carry = !forget)
                JarvisVoice.speak("Fresh start. What's next?", wait = false)
                return true
            }
        }
        return false
    }

    /** Stops speaking, listening and the Jarvis chat's current turn. */
    fun stopEverything() {
        JarvisVoice.stop()
        cancelListening()
        inConversation = false
        turnOpen = false
        pendingAsk?.complete("")
        while (outbox.tryReceive().isSuccess) { /* drop queued sentences */ }
        scope.launch { stopChat(JarvisPreferences.currentChatId.value) }
    }

    // ------------------------------------------------------------------ the Jarvis chat

    private suspend fun sendLoop() {
        for (raw in outbox) {
            val isEvent = raw.startsWith(EVENT)
            val text = if (isEvent) raw.removePrefix(EVENT) else raw
            try {
                if (isEvent) {
                    // a phone event never interrupts: wait until the user is not talking and Jarvis is free
                    var w = 0
                    while ((listening.value || pendingAsk != null || computeBusy()) && w < 1200) { delay(500); w++ }
                    if (!JarvisPreferences.enabled.value) continue
                    sendToJarvis(text)
                    continue
                }
                if (computeBusy()) {
                    // never talk over a running task: wait for it (the user can say stop)
                    JarvisVoice.speak("Okay, I'll do that when I'm done with this.", wait = false)
                    var waited = 0
                    while (computeBusy() && waited < 1200) { delay(500); waited++ }
                }
                sendToJarvis(text)
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
                _notice.value = "Jarvis could not send: ${e.message}"
                JarvisVoice.speak("Sorry, I couldn't reach the agent.", wait = false)
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
        _log.value = emptyList()
    }

    private fun buildCarry(): String {
        val mem = JarvisPreferences.memory.value.trim()
        val recent = _log.value.takeLast(10).joinToString("\n") { "${it.first}: ${it.second}" }
        return buildString {
            append("This is a new Jarvis chat; the previous one was closed. ")
            if (mem.isNotBlank()) append("Jarvis memory note:\n$mem\n")
            if (recent.isNotBlank()) append("Last lines of the previous conversation:\n$recent\n")
            append("Continue from here without repeating this.")
        }
    }

    private suspend fun sendToJarvis(text: String) {
        val reason = rotationReason()
        if (reason != null) {
            Log.i(TAG, "new Jarvis chat ($reason)")
            newSession(carry = true)
        }
        val carry = carryText
        carryText = ""
        turnSpoke = false
        turnOpen = true
        val prompt = JarvisSkill.preamble(screen.value, JarvisPreferences.permissionPolicy.value, JarvisPreferences.handsFree.value) +
            (if (carry.isNotBlank()) PromptExpansion.hidden(carry) else "") + text
        when (JarvisPreferences.agentKind) {
            AgentKind.CLAUDE -> sendClaude(prompt)
            AgentKind.AGY -> sendAgy(prompt)
        }
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
        vm.allConversations.value.find { it.id == id }?.agent
            ?: if (JarvisPreferences.isJarvisChat(id) && JarvisPreferences.currentChatId.value == id) JarvisPreferences.agentKind else AgentKind.AGY

    /** Sends [text] to any chat by id. Does not change the chat on screen. */
    suspend fun sendToChat(id: String, text: String): String {
        val result = sendToChatNow(id, text)
        if (result == "Sent.") watched[id] = text.trim().replace(Regex("\\s+"), " ").take(200)
        return result
    }

    private suspend fun sendToChatNow(id: String, text: String): String {
        if (vm.allConversations.value.find { it.id == id }?.isRunning == true) {
            return "That chat is busy working, so nothing was sent. Do not interrupt it: handle the request yourself, or ask the user."
        }
        return when (agentOfChat(id)) {
            AgentKind.CLAUDE -> {
                val onScreen = vm.currentConversation.value?.id == id
                if (onScreen) {
                    if (vm.claudeBackend.isCurrentChatActivelyRunning.value) return "That chat is still working. Stop it first or wait."
                    vm.claudeBackend.sendMessage(PromptExpansion.expand(text))
                } else {
                    if (id == JarvisPreferences.currentChatId.value) return "That is the Jarvis chat itself."
                    val b = opsClaude
                    if (b.currentConversation.value?.id != id) {
                        b.selectConversation(id)
                        delay(900)
                    }
                    if (b.isCurrentChatActivelyRunning.value) return "That chat is still working. Stop it first or wait."
                    b.sendMessage(PromptExpansion.expand(text))
                }
                "Sent."
            }
            AgentKind.AGY -> {
                val modelEnum = AgyHubClient.resolveModelEnum(vm.agyBackend.modelOfChat(id))
                hub.sendUserPrompt(id, PromptExpansion.expand(text), modelEnum).fold({ "Sent." }, { "Could not send: ${it.message}" })
            }
        }
    }

    /** Sets the model of a chat that is not on screen. Applies to its next message. */
    suspend fun setChatModel(id: String, kind: AgentKind, modelId: String) {
        when (kind) {
            AgentKind.AGY -> vm.agyBackend.rememberChatModel(id, modelId)
            AgentKind.CLAUDE -> {
                if (id == JarvisPreferences.currentChatId.value && JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
                    jarvisClaude.selectModel(modelId)
                } else {
                    val b = opsClaude
                    if (b.currentConversation.value?.id != id) { b.selectConversation(id); delay(900) }
                    b.selectModel(modelId)
                }
            }
        }
    }

    suspend fun stopChat(id: String): String {
        if (id.isBlank()) return "No chat."
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

    /** Creates a chat for a task and sends the first message. Returns the chat id. */
    suspend fun newChatWithMessage(agent: AgentKind, text: String, modelId: String?, projectPath: String?): String {
        if (projectPath != null) {
            val proj = com.example.gemini.data.daemon.TermuxDaemonManager.projects.value.firstOrNull { it.path == projectPath }
                ?: com.example.gemini.data.daemon.ProjectItem(java.io.File(projectPath).name, projectPath)
            com.example.gemini.data.daemon.TermuxDaemonManager.setActiveProject(proj)
        }
        return when (agent) {
            AgentKind.CLAUDE -> {
                val b = opsClaude
                b.startNewChat()
                delay(150)
                val id = b.currentConversation.value?.id ?: error("Could not create the chat")
                modelId?.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
                b.sendMessage(PromptExpansion.expand(text))
                watched[id] = text.trim().replace(Regex("\\s+"), " ").take(200)
                scope.launch { delay(4000); runCatching { vm.claudeBackend.refreshSessions() } }
                id
            }
            AgentKind.AGY -> {
                val id = UUID.randomUUID().toString()
                val chosen = modelId?.takeIf { it.isNotBlank() } ?: vm.agyBackend.selectedModelId.value
                val enum = AgyHubClient.resolveModelEnum(chosen)
                val ws = projectPath ?: defaultWorkspace().removePrefix("file://")
                hub.startCascade(id, enum, if (ws.startsWith("file://")) ws else "file://$ws").getOrThrow()
                vm.agyBackend.rememberChatModel(id, chosen)
                hub.sendUserPrompt(id, PromptExpansion.expand(text), enum).getOrThrow()
                watched[id] = text.trim().replace(Regex("\\s+"), " ").take(200)
                scope.launch { delay(2500); runCatching { vm.agyBackend.syncAgyConversations(force = true) } }
                id
            }
        }
    }

    // ------------------------------------------------------------------ speaking and asking

    /** Jarvis says [text] (called by the jarvis_speak tool). With [listenAfter] the microphone opens afterwards. */
    suspend fun say(text: String, listenAfter: Boolean = false, wait: Boolean = false) {
        addLog("Jarvis", text)
        turnSpoke = true
        if (listenAfter) inConversation = true
        JarvisVoice.speak(text, wait = wait)
    }

    /** Speaks [question] and returns what the user answers (null when nothing was heard in time). */
    suspend fun ask(question: String, timeoutMs: Long = ASK_TIMEOUT_MS): String? = askMutex.withLock {
        val d = CompletableDeferred<String>()
        pendingAsk = d
        try {
            addLog("Jarvis", question)
            turnSpoke = true
            JarvisVoice.speak(question, wait = true)
            if (!startListening()) return null
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

    private fun pumpWake(speaking: Boolean) {
        val want = JarvisPreferences.handsFree.value && JarvisPreferences.hotword.value == "phrase" && !listening.value && !speaking && hasMic()
        if (want && !wakeRunning) startWake()
        else if (!want && wakeRunning) stopWake()
        // a heard wake phrase fires once the sentence after it has ended
        if (wakeHit && System.currentTimeMillis() - wakeChangedAt >= 1300) {
            val phrase = JarvisPreferences.wakePhrase.value.lowercase().trim()
            val text = wakeText
            wakeHit = false
            wakeText = ""
            stopWake()
            val i = text.lowercase().indexOf(phrase)
            val rest = if (i >= 0) text.substring(i + phrase.length).trim(' ', ',', '.', '!', '?') else ""
            scope.launch {
                if (rest.length > 2) { inConversation = true; cue(1); deliver(rest) } else startListening()
            }
        }
    }

    private fun startWake() {
        wakeRunning = true
        wakeHit = false
        wakeText = ""
        val phrase = JarvisPreferences.wakePhrase.value.lowercase().trim()
        val onText = { t: String ->
            if (wakeHit || t.lowercase().contains(phrase)) {
                wakeHit = true
                wakeText = t
                wakeChangedAt = System.currentTimeMillis()
            }
        }
        wakeStt.startTranscriptionSession(
            scope = scope, cascadeId = "", preCursorText = "", postCursorText = "",
            onPartialText = { onText(it) },
            onFinalText = { onText(it) },
            onError = { err -> _notice.value = "Wake phrase: $err"; wakeRunning = false }
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
        /** "jarvis" (the Jarvis chat), "ops" (a chat Jarvis drives) or "screen" (the chat on screen). */
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

    private fun jstr(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    private fun claudePerms(b: ClaudeChatBackend, source: String): List<Perm> =
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
                p.toolName.startsWith("mcp__jarvis_mcp") || p.toolName in readTools -> Risk.READ
                p.toolName == "Bash" -> bashRisk(cmd)
                else -> Risk.NORMAL
            }
            Perm(
                key = p.toolUseId, source = source, chatId = b.currentConversation.value?.id.orEmpty(), tool = p.toolName,
                summary = summary, risk = risk,
                approve = {
                    if (p.toolName == "ExitPlanMode") b.approvePlan(p.toolUseId, "default")
                    else b.approveTool(ToolCall(id = p.toolUseId, command = ""))
                },
                reject = { why -> b.rejectToolById(p.toolUseId, why) }
            )
        }

    private fun agyPerms(): List<Perm> = vm.pendingApprovals.value.map { a ->
        val t = a.toolCall
        val risk = when (t.toolType) {
            ToolType.VIEW_FILE, ToolType.LIST_DIR, ToolType.GREP_SEARCH, ToolType.FIND, ToolType.SEARCH_WEB, ToolType.READ_URL -> Risk.READ
            ToolType.BASH -> bashRisk(t.command)
            ToolType.MCP -> if (t.name.contains("jarvis", true)) Risk.READ else Risk.NORMAL
            else -> Risk.NORMAL
        }
        Perm(
            key = "agy:${t.stepIndex}:${t.id}", source = "screen", chatId = vm.currentConversation.value?.id.orEmpty(), tool = t.name,
            summary = when (t.toolType) {
                ToolType.BASH -> "run the command: ${t.command.take(140)}"
                ToolType.EDIT_FILE -> "edit ${t.command.substringAfterLast('/').take(60)}"
                else -> "use ${t.name} ${t.command.take(80)}".trim()
            },
            risk = risk,
            approve = { vm.approveAndExecuteTerminalTool(t, a.messageId) },
            reject = { why -> vm.rejectTerminalTool(t, a.messageId, why) }
        )
    }

    /** Everything waiting for approval in the chats Jarvis can reach. */
    fun allPending(): List<Perm> = buildList {
        if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name || JarvisPreferences.currentChatId.value.isBlank()) {
            addAll(claudePerms(jarvisClaude, "jarvis"))
        }
        addAll(claudePerms(opsClaude, "ops"))
        if (vm.currentConversation.value?.agent == AgentKind.CLAUDE) addAll(claudePerms(vm.claudeBackend, "screen")) else addAll(agyPerms())
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

    private suspend fun permissionLoop() {
        for (p in permQueue) {
            try {
                if (allPending().none { it.key == p.key }) continue
                val policy = JarvisPreferences.permissionPolicy.value
                val auto = p.tool.startsWith("mcp__jarvis_mcp") ||
                    policy == "auto_all" ||
                    (policy == "auto_except_risky" && p.risk != Risk.RISKY) ||
                    (policy == "auto_readonly" && p.risk == Risk.READ)
                if (auto) { p.approve(); continue }
                val prefix = if (p.risk == Risk.RISKY) "Careful, this looks risky. " else ""
                val answer = ask("${prefix}It wants to ${p.summary}. Approve?")
                when (parseYesNo(answer)) {
                    Decision.YES_ALL -> {
                        p.approve()
                        allPending().filter { it.key != p.key && it.risk != Risk.RISKY }.forEach { it.approve() }
                    }
                    Decision.YES -> p.approve()
                    Decision.NO -> { p.reject("The user said no by voice."); JarvisVoice.speak("Rejected.", wait = false) }
                    Decision.UNCLEAR -> {
                        if (answer != null) { p.reject("The user's answer was unclear, so nothing was approved."); JarvisVoice.speak("I didn't get that, so I rejected it.", wait = false) }
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
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(if (kind == 1) 25 else 15, VibrationEffect.DEFAULT_AMPLITUDE))
                else v?.vibrate(25)
            }
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
            tg.startTone(if (kind == 1) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 110)
            scope.launch { delay(300); tg.release() }
        }
    }
}
