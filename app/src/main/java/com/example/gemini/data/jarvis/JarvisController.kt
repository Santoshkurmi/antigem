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
    private const val LOG_KEEP = 500
    /** "Who" of a divider line in [log] (a new session began). */
    const val LOG_DIVIDER = "·"
    /** A voice counts as still talking this long after its last loud moment (bridges the gaps between syllables). */
    private const val VOICE_HANGOVER_MS = 400L

    enum class Phase { OFF, IDLE, LISTENING, THINKING, SPEAKING }
    enum class Risk { READ, NORMAL, RISKY }

    /** What opened the microphone; the model is told (follow-ups may be side talk). */
    enum class Origin(val label: String) {
        BUTTON("button"), WAKE("wake phrase"), HEADSET("headset button"), FOLLOWUP("follow-up (may not be meant for you)"),
        ASK("answer"), TYPED("typed")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Scope for the backends [JarvisDriver] keeps on Jarvis text chats. */
    val scopeForDrivers: CoroutineScope get() = scope
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
    /**
     * The voice conversation, kept across sessions and restarts (the last [LOG_KEEP] lines): "You" / "Jarvis" to text,
     * and [LOG_DIVIDER] lines where a new Jarvis chat began.
     */
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
    /** The quick settings sheet (tune icon on the card and in the panel) is open. */
    val quickSettingsOpen = MutableStateFlow(false)
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
    /** Backend of the Jarvis chat (OpenCode). It never changes the user's OpenCode defaults, and has no question forms. */
    val jarvisOpenCode: com.example.gemini.data.agent.opencode.OpenCodeChatBackend by lazy {
        com.example.gemini.data.agent.opencode.OpenCodeChatBackend(
            app, scope, ChatSessionStore(), vm.openCodePrefs, com.example.gemini.data.agent.opencode.OpenCodeBridgeClient(),
            rememberChoices = false,
            // Jarvis asks through jarvis_ask: OpenCode's own question form would wait in a chat nobody looks at
            extraRules = listOf(kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive("question"))
                put("resource", kotlinx.serialization.json.JsonPrimitive("*"))
                put("effect", kotlinx.serialization.json.JsonPrimitive("deny"))
            })
        ).also { it.start() }
    }

    /** Jarvis cannot work as set up (no agent picked, the agent is off, the model is gone): said and shown as is. */
    class JarvisSetupException(message: String) : Exception(message)

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

    /** [sendPhrase]: the send phrase that ended the sentence and was cut off (the model is told, in case it belonged to the request). */
    private data class Outgoing(val text: String, val origin: Origin? = null, val event: Boolean = false, val sendPhrase: String? = null)
    /** History of what was heard in this capture: the normalized words at each change and when (oldest first). */
    private val textHistory = ArrayList<Pair<String, Long>>()
    private val _autoSendNow = MutableStateFlow(false)
    /** The sentence being heard is sent after a pause (auto send on, or a short answer to Jarvis' question). */
    val autoSendNow: StateFlow<Boolean> = _autoSendNow.asStateFlow()
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
        JarvisManagers.init(app)
        loadLog()
        JarvisVoice.init(app)
        // the microphone is never open after a restart until the user starts it again
        JarvisPreferences.handsFree.set(false)
        JarvisTasks.start(application, scope)
        scope.launch { pollLoop() }
        scope.launch { sendLoop() }
        scope.launch { permissionLoop() }
        scope.launch { questionLoop() }
        scope.launch { ChatCompletionMonitor.events.collect { c -> runCatching { onChatDone(c) } } }
        JarvisEvents.start(scope)
    }

    /** Turns Jarvis on or off (Settings). */
    fun setEnabled(on: Boolean) {
        JarvisPreferences.enabled.set(on)
        if (on && JarvisPreferences.agentKind == null) {
            showNotice("Pick the agent Jarvis talks to (Claude Code, Antigravity or OpenCode): tap here.", "agent")
        }
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

    /** Shows [text] on the Jarvis notice chip. */
    fun showProblem(text: String) = showNotice(text)

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
                        // the model decides (jarvis_speak listen_after, or a question); "always" also listens after plain answers
                        val wanted = when (JarvisPreferences.listenAfter.value) {
                            "never" -> false
                            "always" -> listenAfterRequested || inConversation
                            else -> listenAfterRequested
                        }
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

    /** The voice Jarvis chat is working on a turn. */
    fun isVoiceChatBusy(): Boolean = computeBusy()

    /** The user is talking to Jarvis or answering it right now (news waits). */
    fun isUserTalking(): Boolean = listening.value || pendingAsk != null

    private fun computeBusy(): Boolean {
        val id = JarvisPreferences.currentChatId.value
        if (id.isBlank()) return false
        return if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
            jarvisClaude.isCurrentChatActivelyRunning.value
        } else if (JarvisPreferences.currentChatAgent.value == AgentKind.OPENCODE.name) {
            jarvisOpenCode.isCurrentChatActivelyRunning.value
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
        val msgs = when (JarvisPreferences.currentChatAgent.value) {
            AgentKind.CLAUDE.name -> jarvisClaude.messages.value
            AgentKind.OPENCODE.name -> jarvisOpenCode.messages.value
            else -> return null
        }
        val call = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toolCalls?.lastOrNull { it.status == "RUNNING" } ?: return null
        val n = call.name.lowercase()
        return when {
            n.contains("jarvis") -> null
            n == "toolsearch" -> "Loading my tools"
            n.startsWith("mcp__") -> "Using ${n.removePrefix("mcp__").substringBefore("__").replace('_', ' ')}"
            n == "bash" || n == "shell" || n.contains("terminal") || n.contains("command") -> "Running ${call.command.trim().take(32).ifBlank { "a command" }}"
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

    /** The Jarvis chat's last reply as words (its thinking, tool markers and error cards taken out, see [JarvisText]). */
    private suspend fun lastJarvisReply(): JarvisText.Reply? {
        val id = JarvisPreferences.currentChatId.value
        val raw = if (JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name) {
            jarvisClaude.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
        } else if (JarvisPreferences.currentChatAgent.value == AgentKind.OPENCODE.name) {
            jarvisOpenCode.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content
        } else {
            runCatching { readChat(id).lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content }.getOrNull()
        }
        return raw?.let { JarvisText.reply(it) }
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
            val reply = lastJarvisReply() ?: JarvisText.Reply("", null)
            val text = reply.text
            val seenKey = text + "|" + reply.error.orEmpty()
            val fresh = (text.isNotBlank() || reply.error != null) && seenKey != lastReplySeen
            if (fresh) lastReplySeen = seenKey
            if (fresh && text.contains(JarvisSkill.MCP_MISSING)) { onMcpMissing(); return@launch }
            if (first && calls == 0 && !turnSpoke && reply.error == null) {
                showNotice("Jarvis answered without its app tools. If it can't control the app, add the Jarvis MCP server to ${agentLabel()} (Settings → Jarvis → Setup).", "setup")
            }
            if (!fresh || turnSpoke || isSpeaking() || pendingAsk != null || pendingQuestions().isNotEmpty() || listening.value) return@launch
            if (text.isBlank()) {
                // the turn ended with an error and no words: one plain sentence, never the error card itself
                val err = reply.error ?: return@launch
                showNotice("Jarvis' agent stopped with an error: ${err.take(160)}")
                addLog("Jarvis", "Error: ${err.take(160)}")
                speakOut("Sorry, I ran into a problem. " + firstSentence(err, 120))
                return@launch
            }
            // only the words it wrote for the user: never its thinking, tool markers or markdown
            val spoken = spokenForm(text)
            if (spoken.isNotBlank()) {
                addLog("Jarvis", spoken)
                // a plain-text answer that asks something still opens the microphone for the reply
                if (spoken.trimEnd().endsWith("?")) { listenAfterRequested = true; expectingReply = true }
                speakOut(spoken)
            }
        }
    }

    private fun agentLabel() = when (JarvisPreferences.agentKind) {
        AgentKind.CLAUDE -> "Claude Code"
        AgentKind.AGY -> "Antigravity"
        AgentKind.OPENCODE -> "OpenCode"
        null -> "the agent"
    }

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

    /** A task's questions as plain lines (the body of a question event). */
    private fun questionBody(qn: ChoiceQuestionnaire): String = buildString {
        qn.questions.forEachIndexed { i, q ->
            append("${i + 1}. ${q.prompt.trim()}")
            val opts = q.options.filter { !it.isCustomInput }
            if (opts.isNotEmpty()) append(" Options: " + opts.mapIndexed { j, o -> "${j + 1}) ${o.label.trim()}" }.joinToString("; "))
            if (q.isMultiSelect) append(" (several allowed)")
            append('\n')
        }
        append("Answer with jarvis_task_answer (for several questions separate the answers with ';').")
    }

    private suspend fun questionLoop() {
        for (q in questionQueue) {
            try {
                if (pendingQuestions().none { it.key == q.key }) continue
                val qn = q.call.questionnaire ?: continue
                val task = if (q.source == "task") JarvisTasks.find(q.chatId) else null
                val owner = task?.let { JarvisManagers.ownerOrVoice(it.owner) }
                // the manager that runs the task answers it (a text chat always; the voice chat when Jarvis decides)
                if (task != null && owner != null && (JarvisPreferences.eventRouting.value == "model" || owner != JarvisManagers.VOICE)) {
                    JarvisEvents.post(owner, JarvisEvents.Event(
                        kind = "task_question", taskId = task.id, title = task.title, agent = task.agent.name.lowercase(),
                        team = task.team, goal = task.goal, body = questionBody(qn)
                    ))
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
            }.getOrNull()?.let { JarvisText.reply(it).text }
            if (!last.isNullOrBlank()) msg += " It says: " + firstSentence(last, 160)
        }
        postStatus("Done: $title", explicit = false)
        if (listening.value || pendingAsk != null) return // never talk over the user
        addLog("Jarvis", msg)
        speakOut(msg)
    }

    /**
     * News from a task: "done", "failed" or "stalled". The Jarvis manager that started the task gets it (batched with
     * whatever else came in, see [JarvisEvents]) and decides what to say and do; for the voice chat with event routing
     * "speak" Jarvis just announces it.
     */
    fun onTaskEvent(t: JarvisTasks.Task, kind: String, detail: String) {
        if (!JarvisPreferences.enabled.value) return
        val owner = JarvisManagers.ownerOrVoice(t.owner)
        val line = when (kind) { "done" -> "Done: ${t.title}"; "failed" -> "Failed: ${t.title}"; else -> "Stuck: ${t.title}" }.take(60)
        if (owner == JarvisManagers.VOICE) postStatus(line, explicit = false) else JarvisManagers.setStatus(owner, line)
        if (owner != JarvisManagers.VOICE || JarvisPreferences.eventRouting.value == "model") {
            // the manager that started it gets the news, batched with whatever else came in meanwhile
            JarvisEvents.post(owner, JarvisEvents.Event(
                kind = "task_$kind", taskId = t.id, title = t.title, agent = t.agent.name.lowercase(), team = t.team,
                goal = t.goal, body = detail
            ))
            return
        }
        // "Just tell me": announced without a model turn
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
    }

    /** Says [text] once the user is not talking (a Jarvis text chat's reply with voice replies on, an announcement). */
    fun announce(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !JarvisPreferences.enabled.value) return
        scope.launch {
            var w = 0
            while ((listening.value || pendingAsk != null) && w < 240) { delay(500); w++ }
            addLog("Jarvis", t)
            speakOut(t)
        }
    }

    private val mdCode = Regex("```[\\s\\S]*?```")
    private val mdLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val mdMarks = Regex("[*_`#>|]+")

    /** A written reply as something worth saying: no code, links or markdown, the first few sentences. */
    fun spokenForm(text: String): String {
        val plain = text.replace(mdCode, " (code) ").replace(mdLink, "$1").lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ") }.filter { it.isNotBlank() && !it.startsWith("|") }
            .joinToString(". ").replace(mdMarks, "").replace(Regex("\\.\\s*\\."), ".")
        return limitSpoken(plain)
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
            // words already heard (after the wake phrase) stay: the recognizer's first partial must not replace them
            if (initial.isNotBlank()) committed.add(initial.trim())
            partial = ""
        }
        _heard.value = initial
        val now = System.currentTimeMillis()
        lastChangeAt = now
        lastLoudAt = now
        startedAt = now
        synchronized(textHistory) {
            textHistory.clear()
            textHistory += JarvisPhrases.normalize(initial) to now
        }
        listenOrigin = origin
        if (origin == Origin.BUTTON || origin == Origin.WAKE || origin == Origin.HEADSET) expectingReply = false
        _holding.value = false
        _sendProgress.value = 0f
        captureAuto = autoSendFor(origin)
        _autoSendNow.value = captureAuto
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
                onHeardChanged(joinText(snapshotCommitted(), p))
            },
            onFinalText = { f ->
                synchronized(lock) { committed.add(f); partial = "" }
                onHeardChanged(joinText(snapshotCommitted(), ""))
            },
            onError = { err -> onSttError(t, err) }
        )
        endpointJob?.cancel()
        endpointJob = scope.launch { endpointLoop(origin) }
        return true
    }

    /**
     * The recognizer reported text. The agents' dictation repeats the same text many times a second while you are
     * quiet; only a real change of the words (not of case or punctuation) counts as you talking, otherwise the pause
     * that sends would never be reached.
     */
    private fun onHeardChanged(text: String) {
        _heard.value = text
        val norm = JarvisPhrases.normalize(text)
        val now = System.currentTimeMillis()
        synchronized(textHistory) {
            if (textHistory.lastOrNull()?.first == norm) return
            textHistory += norm to now
            if (textHistory.size > 400) textHistory.removeAt(0)
        }
        lastChangeAt = now
    }

    private fun historySnapshot(): List<Pair<String, Long>> = synchronized(textHistory) { textHistory.toList() }

    /**
     * Whether this capture is sent after a pause: auto send on, or (when the user chose so) an answer to Jarvis' own
     * question. Decided once when the microphone opens ([captureAuto]); nothing Jarvis says meanwhile can change it.
     */
    private fun autoSendFor(origin: Origin): Boolean =
        JarvisPreferences.autoSend.value ||
            (JarvisPreferences.answerSend.value == "auto" && (origin == Origin.ASK || expectingReply))

    /** The send mode of the capture in progress (see [autoSendFor]); Wait turns it off for the rest of the capture. */
    @Volatile private var captureAuto = false

    /**
     * Tells speech from background noise by the microphone level, whatever scale the recognizer reports (the agents'
     * dictation gives RMS/6500, so normal speech is only 0.1-0.45; the device recognizer maps dB to 0.08-1). It keeps
     * a noise floor (follows quieter readings quickly, louder ones only slowly and only while nobody talks), how much
     * that background wobbles, and a slowly fading peak; a level clearly above all of that is a voice.
     */
    private class VoiceDetector {
        private var floor = -1f
        private var noise = 0.02f
        private var peak = 0f

        fun isVoice(level: Float): Boolean {
            if (floor < 0f) { floor = level; peak = level; return false }
            peak = maxOf(level, peak * 0.985f)
            val threshold = floor + maxOf(0.05f, noise * 3f, (peak - floor) * 0.35f)
            val voice = level > threshold
            if (level < floor) floor += (level - floor) * 0.3f
            else if (!voice) {
                floor += (level - floor) * 0.02f
                noise = noise * 0.95f + kotlin.math.abs(level - floor) * 0.05f
            }
            return voice
        }
    }

    /**
     * Decides when the sentence is over: a send phrase sends at once and a cancel phrase throws it away (in every mode);
     * with auto send (or for a short answer to Jarvis' own question) a pause sends; otherwise it waits for Send.
     */
    private suspend fun endpointLoop(origin: Origin) {
        val detector = VoiceDetector()
        var checkedText = ""
        var phraseMatch: JarvisPhrases.Match? = null
        while (scope.isActive && listening.value) {
            delay(100)
            val n = System.currentTimeMillis()
            val amp = activeStt?.latestAmplitude?.value ?: 0f
            if (detector.isVoice(amp)) lastLoudAt = n
            val text = joinText(snapshotCommitted(), partial)
            val auto = captureAuto && !_holding.value
            _autoSendNow.value = auto
            if (text.isBlank()) {
                _sendProgress.value = 0f
                val limit = when (origin) {
                    Origin.FOLLOWUP -> JarvisPreferences.followUpSeconds.value.coerceIn(2, 30) * 1000L
                    Origin.ASK -> 90_000L
                    else -> NO_SPEECH_MS
                }
                if (n - startedAt > limit && n - lastLoudAt > 1200) {
                    // no word at all: the microphone closes (a soft tone tells the user it stopped waiting)
                    if (origin == Origin.FOLLOWUP) cue(4)
                    cancelListening()
                    inConversation = false
                    expectingReply = false
                    return
                }
                continue
            }
            if (n - startedAt > JarvisPreferences.maxListenSeconds.value.coerceIn(15, 300) * 1000L) { finishListening(); return }
            // a send or cancel phrase at the end, once the words stopped changing (so "send" is not the start of "sending")
            if (text != checkedText) {
                checkedText = text
                phraseMatch = JarvisPhrases.match(text, historySnapshot(), expectingReply || origin == Origin.ASK)
            }
            val pm = phraseMatch
            if (pm != null && n - lastChangeAt >= 450) {
                if (pm.kind == JarvisPhrases.Kind.CANCEL) cancelByPhrase() else finishListening()
                return
            }
            if (!auto) { _sendProgress.value = 0f; continue }
            val pause = effectivePause(text, origin, JarvisPreferences.sendMode.value)
            val quietFor = n - lastChangeAt
            val voiceQuiet = n - lastLoudAt
            // the text stopped changing and the microphone went quiet (or the text has been still for long: a noisy room)
            val ready = quietFor >= pause && (voiceQuiet >= pause * 6 / 10 || quietFor >= pause * 2)
            // the countdown only shows in a real pause: the gap between two partial transcripts while you talk is not one
            val micQuiet = voiceQuiet >= VOICE_HANGOVER_MS
            val showFrom = maxOf(500L, pause * 35 / 100)
            _sendProgress.value = when {
                micQuiet && quietFor >= showFrom -> ((quietFor - showFrom).toFloat() / (pause - showFrom).coerceAtLeast(1L)).coerceIn(0f, 1f)
                !micQuiet && quietFor >= pause -> ((quietFor - pause).toFloat() / pause).coerceIn(0f, 1f)
                else -> 0f
            }
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

    /** A cancel phrase ended the sentence: nothing is sent. */
    private fun cancelByPhrase() {
        val asking = pendingAsk
        cancelListening()
        inConversation = false
        expectingReply = false
        _heard.value = ""
        postStatus("Cancelled", explicit = false)
        if (asking != null) asking.complete("") else scope.launch { speakOut("Cancelled.") }
    }

    private fun snapshotCommitted(): List<String> = synchronized(lock) { committed.toList() }

    private fun joinText(parts: List<String>, tail: String): String =
        (parts + tail).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun transcriber(): SpeechTranscriber {
        if (sttFallback) return androidStt
        val chosen = JarvisPreferences.sttEngine.value.let {
            if (it == "auto") when (JarvisPreferences.agentKind) {
                AgentKind.CLAUDE -> "claude"
                AgentKind.AGY -> "agy"
                // OpenCode has no speech-to-text of its own
                else -> "android"
            } else it
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
        // a send phrase at the end is the command, not the message; a cancel phrase throws everything away
        var sendPhrase: String? = null
        val pm = JarvisPhrases.match(said, historySnapshot(), expectingReply || origin == Origin.ASK)
        when {
            pm?.kind == JarvisPhrases.Kind.CANCEL -> {
                _heard.value = ""
                cue(2)
                inConversation = false
                expectingReply = false
                postStatus("Cancelled", explicit = false)
                pendingAsk?.let { it.complete(""); return }
                speakOut("Cancelled.")
                return
            }
            pm != null && !pm.whole -> { said = pm.content; sendPhrase = pm.phrase }
        }
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
        outbox.trySend(Outgoing(said, if (wasExpecting && origin == Origin.FOLLOWUP) Origin.ASK else origin, sendPhrase = sendPhrase))
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

    /**
     * The user's own voice shortcuts (Settings → Jarvis → Listening): a whole utterance that is one of their phrases acts
     * at once, without a model turn. There are no built-in ones: every list starts empty, so a plain "okay" or "stop"
     * inside a conversation always goes to the model like anything else.
     */
    private suspend fun handleLocalCommand(text: String, @Suppress("UNUSED_PARAMETER") origin: Origin): Boolean {
        val P = JarvisPreferences
        fun said(pref: JarvisPreferences.Pref<String>) = JarvisPhrases.isShortcut(text, pref.value)
        when {
            said(P.cmdStop) -> {
                stopEverything()
                speakOut("Stopped.")
            }
            said(P.cmdBye) -> {
                inConversation = false
                speakOut("Okay.")
            }
            said(P.cmdRepeat) -> {
                val last = _said.value.ifBlank { _log.value.lastOrNull { it.first == "Jarvis" }?.second.orEmpty() }
                // repeating a question opens the microphone for its answer again
                if (last.trimEnd().endsWith("?")) { listenAfterRequested = true; expectingReply = true }
                speakOut(last.ifBlank { "I haven't said anything yet." })
            }
            said(P.cmdSlower) || said(P.cmdFaster) -> {
                val faster = said(P.cmdFaster)
                P.ttsRate.set((P.ttsRate.value + if (faster) 0.15f else -0.15f).coerceIn(0.5f, 2f))
                speakOut(if (faster) "Faster now." else "Slower now.")
            }
            said(P.cmdQuietOn) -> {
                P.quietMode.set(true)
                speakOut("Quiet mode on. I'll show my answers instead.")
            }
            said(P.cmdQuietOff) -> {
                P.quietMode.set(false)
                speakOut("I'm back.")
            }
            said(P.cmdNewSession) -> {
                newSession(carry = true)
                speakOut("Fresh start. What's next?")
            }
            else -> return false
        }
        return true
    }

    /** Text typed into the Jarvis panel (or into the voice Jarvis chat on screen): handled like spoken words. */
    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || !JarvisPreferences.enabled.value) return
        val shown = PromptExpansion.collapse(t).trim().ifBlank { t }
        scope.launch {
            pendingAsk?.let { addLog("You", shown); it.complete(shown); return@launch }
            addLog("You", shown)
            if (!handleLocalCommand(shown, Origin.TYPED)) outbox.trySend(Outgoing(t, Origin.TYPED))
        }
    }

    /**
     * A message typed into the chat on screen. Returns what to send: for a Jarvis text chat the message with its hidden
     * Jarvis note (the guide on the first message, then the rules, live context and mission brief), unchanged for any
     * other chat, or null when Jarvis took it (typed into the voice Jarvis chat: it goes there as a typed turn).
     */
    fun interceptScreenMessage(chatId: String?, text: String): String? {
        if (!attached || !JarvisPreferences.enabled.value || chatId.isNullOrBlank()) return text
        if (chatId == JarvisPreferences.currentChatId.value && !JarvisManagers.isTextManager(chatId)) {
            submitText(text)
            return null
        }
        // an older voice chat the user writes in now: it carries on as a Jarvis text chat
        if (JarvisPreferences.isJarvisChat(chatId) && !JarvisManagers.isTextManager(chatId)) {
            JarvisManagers.registerTextChat(chatId, agentOfChat(chatId), speak = false)
        }
        if (!JarvisManagers.isTextManager(chatId)) return text
        return wrapManagerMessage(chatId, text)
    }

    /** The user's message to a Jarvis text chat with its hidden note in front. */
    fun wrapManagerMessage(chatId: String, text: String): String {
        val m = JarvisManagers.of(chatId)
        val boot = m.bootFor != JarvisSkill.managerVersion
        val ctx = if (JarvisPreferences.contextInPrompt.value || boot) JarvisSkill.liveContext("typed in the chat", chatId) else ""
        val note = if (boot) JarvisSkill.managerBootPack(chatId, ctx) else JarvisSkill.managerPreamble(chatId, ctx)
        val guard = JarvisEvents.takeGuardNote(chatId)
        JarvisManagers.update(chatId) { it.copy(bootFor = JarvisSkill.managerVersion, lastSentAt = System.currentTimeMillis()) }
        JarvisManagers.resetNudges(chatId)
        return PromptExpansion.hidden(note + (guard?.let { "\n<app_note>\n$it\n</app_note>" } ?: "")) + text
    }

    /** Starts a new Jarvis text chat on screen with [agent] (default: Jarvis' own agent). Returns a problem to show, or null. */
    fun startTextChat(agent: AgentKind? = null): String? {
        if (!attached) return "Jarvis is still starting."
        if (!JarvisPreferences.enabled.value) return "Turn Jarvis on first (Settings → Jarvis)."
        val enabled = vm.enabledAgents.value
        // any agent works (unlike the voice chat, a text chat needs no Jarvis agent set): the last one used for a Jarvis chat first
        val remembered = JarvisPreferences.textChatAgent.value.takeIf { it.isNotBlank() }?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() }
        val a = agent ?: listOfNotNull(remembered, JarvisPreferences.agentKind, vm.newChatAgent.value).firstOrNull { it in enabled }
            ?: enabled.firstOrNull() ?: return "Turn on an agent first (Settings → Agents)."
        if (a !in enabled) return "That agent is turned off (Settings → Agents)."
        dropUnusedTextChats()
        vm.startNewChat(a)
        val id = vm.currentConversation.value?.id ?: return "Could not start the chat."
        JarvisManagers.registerTextChat(id, a)
        JarvisPreferences.textChatAgent.set(a.name)
        vm.requestViewMode("CHAT")
        return null
    }

    /** The user picked another agent in a fresh Jarvis text chat: it starts again with that agent, still a Jarvis chat. */
    fun switchTextChatAgent(chatId: String, agent: AgentKind) {
        if (vm.currentConversation.value?.agent == agent && JarvisManagers.isTextManager(chatId)) return
        // the empty chat being left is dropped
        if (vm.messages.value.isEmpty() && JarvisManagers.of(chatId).lastSentAt == 0L) {
            JarvisManagers.remove(chatId)
            JarvisPreferences.removeChatId(chatId)
        }
        startTextChat(agent)?.let { showNotice(it) }
    }

    /** Jarvis text chats that were opened but never written in (they are not chats anywhere): forgotten. */
    private fun dropUnusedTextChats() {
        val listed = vm.allConversations.value.map { it.id }.toSet()
        val screen = vm.currentConversation.value?.id
        JarvisManagers.textKeys().filter { it !in listed && it != screen && JarvisManagers.of(it).lastSentAt == 0L }.forEach {
            JarvisManagers.remove(it)
            JarvisPreferences.removeChatId(it)
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
        // queued news stays queued (it is about real work) but waits a moment after a "stop"
        JarvisEvents.holdVoiceUntil = System.currentTimeMillis() + 8000
        JarvisEvents.onVoiceFlushTaken()
        scope.launch { stopChat(JarvisPreferences.currentChatId.value) }
    }

    /** The news for the voice chat should go out (see [JarvisEvents]): the send loop drains it when Jarvis is free. */
    fun requestEventFlush() {
        outbox.trySend(Outgoing("", event = true))
    }

    /** The tool the voice Jarvis chat is running right now (for the wait guard). */
    suspend fun voiceRunningTool(): ToolCall? {
        val id = JarvisPreferences.currentChatId.value
        if (id.isBlank()) return null
        val msgs = when (JarvisPreferences.currentChatAgent.value) {
            AgentKind.CLAUDE.name -> jarvisClaude.messages.value
            AgentKind.OPENCODE.name -> jarvisOpenCode.messages.value
            else -> runCatching { readChat(id) }.getOrNull() ?: return null
        }
        return msgs.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.lastOrNull { it.status == "RUNNING" }
    }

    /** The voice Jarvis chat waits for its tasks inside a turn: stop that turn (its half-done words are not read out). */
    fun stopVoiceTurnForNews() {
        turnSpoke = true
        scope.launch { stopChat(JarvisPreferences.currentChatId.value) }
    }

    // ------------------------------------------------------------------ the Jarvis chat


    /** Failed tries in a row to hand the voice chat its news (the next try waits longer). */
    @Volatile private var eventFailures = 0

    private suspend fun sendLoop() {
        for (m in outbox) {
            try {
                if (m.event) {
                    // news never interrupts: wait until the user is not talking and Jarvis is free
                    var w = 0
                    while ((listening.value || pendingAsk != null || computeBusy() || System.currentTimeMillis() < JarvisEvents.holdVoiceUntil) && w < 1200) { delay(500); w++ }
                    JarvisEvents.onVoiceFlushTaken()
                    if (!JarvisPreferences.enabled.value) continue
                    if (m.text.isNotBlank()) { sendToJarvis(m); continue }
                    // everything that came in meanwhile goes out together, in one message
                    val batch = JarvisEvents.drain(JarvisManagers.VOICE)
                    if (batch.isEmpty()) continue
                    try {
                        sendToJarvis(m.copy(text = JarvisEvents.format(JarvisManagers.VOICE, batch, JarvisEvents.takeGuardNote(JarvisManagers.VOICE))))
                        eventFailures = 0
                    } catch (e: Exception) {
                        // keep the news and try again later, waiting longer each time; one notice, never spoken
                        batch.forEach { JarvisEvents.post(JarvisManagers.VOICE, it) }
                        eventFailures++
                        val wait = (15_000L shl (eventFailures - 1).coerceAtMost(5)).coerceAtMost(300_000L)
                        JarvisEvents.holdVoiceUntil = System.currentTimeMillis() + wait
                        Log.w(TAG, "news for the voice chat not sent (${e.message}); next try in ${wait / 1000}s")
                        if (eventFailures == 1) showNotice("Jarvis could not take its task news: ${e.message?.take(120)}. It tries again in a moment.", if (e is JarvisSetupException) "agent" else null)
                    }
                    continue
                }
                var interrupted = false
                if (computeBusy()) {
                    val mode = JarvisPreferences.busyBehavior.value
                    // "smart": only the user's own interrupt phrases ("wait…", "actually…") cut in
                    val interrupt = mode == "interrupt" || (mode == "smart" && JarvisPhrases.startsWithAny(m.text.trim(), JarvisPreferences.interruptPhrases.value))
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
            } catch (e: JarvisSetupException) {
                showNotice(e.message.orEmpty(), "agent")
                speakOut(e.message.orEmpty())
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

    /**
     * Next message goes to a brand new Jarvis chat (it gets the full guide). [carry] brings over the Jarvis memory and a
     * short recap; [clearLog] = false keeps the conversation lines on the card (the old chat vanished, the talk goes on).
     */
    fun newSession(carry: Boolean = true, clearLog: Boolean = true) {
        carryText = if (carry && JarvisPreferences.carryOver.value) buildCarry() else ""
        JarvisPreferences.currentChatId.set("")
        JarvisPreferences.currentChatMessages.set(0)
        JarvisPreferences.bootSentFor.set("")
        lastReplySeen = ""
        // the history stays (the panel shows all of it); a divider marks where the new chat begins
        if (clearLog && _log.value.isNotEmpty() && _log.value.last().first != LOG_DIVIDER) {
            addLog(LOG_DIVIDER, "New session · " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
        }
    }

    /**
     * A chat was deleted (chat list, a tool): Jarvis forgets it. When it was the Jarvis chat itself, the next message
     * starts a new one instead of being sent to a chat that no longer exists.
     */
    fun onChatDeleted(id: String) {
        if (id.isBlank()) return
        runCatching {
            if (id == JarvisPreferences.currentChatId.value) {
                turnOpen = false
                newSession(carry = true, clearLog = false)
            }
            if (JarvisPreferences.isJarvisChat(id)) JarvisPreferences.removeChatId(id)
            if (JarvisTasks.isTask(id)) JarvisTasks.close(id)
            JarvisManagers.remove(id)
            JarvisDriver.release(id)
        }
    }

    /** What the agents answer when a chat id no longer exists. */
    private val missingChat = Regex(
        "not.?found|does not exist|doesn'?t exist|no such|unknown (cascade|conversation|session|chat)|no conversation found|\\b404\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * The Jarvis chat [id] is gone (deleted elsewhere): the next message starts a new one. [resending] is the message
     * about to be sent there again; it is left out of the recap so the model does not take it as already handled.
     */
    private fun replaceMissingChat(id: String, resending: String?) {
        Log.i(TAG, "the Jarvis chat $id is gone: starting a new one")
        JarvisPreferences.removeChatId(id)
        newSession(carry = true, clearLog = false)
        if (resending != null && JarvisPreferences.carryOver.value) carryText = buildCarry(exclude = resending)
        showNotice("The Jarvis chat was gone, so I started a new one.")
    }

    private fun buildCarry(exclude: String? = null): String {
        val talk = _log.value.takeLastWhile { it.first != LOG_DIVIDER }
        val lines = talk.let { l -> if (exclude != null && l.lastOrNull()?.let { it.first == "You" && it.second == exclude } == true) l.dropLast(1) else l }
        val recent = lines.takeLast(10).joinToString("\n") { "${it.first}: ${it.second}" }
        if (recent.isBlank()) return ""
        // the memory note itself is part of the guide of the new chat
        return "The previous Jarvis chat was closed. Its last lines (continue from here without repeating them):\n$recent"
    }

    /** [retried]: this is the second try, in a new chat, after the old Jarvis chat turned out to be gone. */
    private suspend fun sendToJarvis(m: Outgoing, interrupted: Boolean = false, retried: Boolean = false) {
        val reason = rotationReason()
        if (reason != null) {
            Log.i(TAG, "new Jarvis chat ($reason)")
            newSession(carry = true)
        }
        val carry = carryText
        carryText = ""
        val agent = JarvisPreferences.agentKind
            ?: throw JarvisSetupException("I don't have an agent yet. Pick one for me in Settings, Jarvis, Agent.")
        if (agent !in vm.enabledAgents.value) {
            throw JarvisSetupException("${agentLabel()} is turned off in the app. Turn it on in Settings, Agents, or pick another agent for me in Settings, Jarvis, Agent.")
        }
        val chatId = JarvisPreferences.currentChatId.value
        val freshChat = chatId.isBlank() || JarvisPreferences.currentChatAgent.value != agent.name
        // the full guide goes into the first message of a chat (again when the guide changed with an app update)
        val needsBoot = freshChat || JarvisPreferences.bootSentFor.value != "$chatId|${JarvisSkill.version}"
        val heardVia = if (m.event) "phone event" else m.origin?.label
        val context = if (JarvisPreferences.contextInPrompt.value || needsBoot) JarvisSkill.liveContext(heardVia)
        else heardVia?.let { "heard_via: $it" }.orEmpty()
        val note = buildString {
            append(if (needsBoot) JarvisSkill.bootPack(context) else JarvisSkill.preamble(context))
            // the user changed the persona or their notes in Settings since this chat was told: tell it once
            if (!needsBoot && JarvisPreferences.profileSentFor.value != "$chatId|${JarvisSkill.profileHash()}") {
                append("\n\n[Updated by the user in Settings → Jarvis, follow from now on]\n")
                append(JarvisSkill.profileSection() ?: "The persona and the notes about the user were cleared: drop what they said.")
            }
            if (interrupted) append("\n(The user interrupted your previous turn with this message: switch to it.)")
            m.sendPhrase?.let { append("\n(The user ended with “$it”, which the app took as the send command and cut off. If it was meant as part of the request, take it into account.)") }
            if (carry.isNotBlank()) append("\n\n").append(carry)
        }
        turnSpoke = false
        turnToolCalls = 0
        turnOpen = true
        firstTurnOfChat = freshChat
        val prompt = PromptExpansion.hidden(note) + m.text
        val existingId = if (freshChat) "" else chatId
        val errorBefore = if (agent == AgentKind.CLAUDE) jarvisClaude.conversationError.value else null
        try {
            when (agent) {
                AgentKind.CLAUDE -> sendClaude(prompt)
                AgentKind.AGY -> sendAgy(prompt)
                AgentKind.OPENCODE -> sendOpenCode(prompt)
            }
        } catch (e: Exception) {
            // the saved Jarvis chat was deleted: start a new one and send this message there (once)
            if (!retried && existingId.isNotBlank() && missingChat.containsMatchIn(e.message.orEmpty())) {
                turnOpen = false
                replaceMissingChat(existingId, m.text)
                sendToJarvis(m, interrupted = false, retried = true)
                return
            }
            turnOpen = false
            throw e
        }
        if (needsBoot) JarvisPreferences.bootSentFor.set("${JarvisPreferences.currentChatId.value}|${JarvisSkill.version}")
        JarvisPreferences.profileSentFor.set("${JarvisPreferences.currentChatId.value}|${JarvisSkill.profileHash()}")
        JarvisPreferences.currentChatMessages.set(JarvisPreferences.currentChatMessages.value + 1)
        touch()
        // Claude reports a missing chat as an error of the chat, a moment after sending
        if (agent == AgentKind.CLAUDE && existingId.isNotBlank() && !retried) {
            scope.launch {
                val until = System.currentTimeMillis() + 6000
                while (System.currentTimeMillis() < until) {
                    delay(300)
                    if (JarvisPreferences.currentChatId.value != existingId) return@launch
                    val err = jarvisClaude.conversationError.value
                    if (err != null && err != errorBefore && missingChat.containsMatchIn(err)) {
                        turnOpen = false
                        replaceMissingChat(existingId, m.text)
                        outbox.trySend(m)
                        return@launch
                    }
                }
            }
        }
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

    private suspend fun sendOpenCode(prompt: String) {
        val b = jarvisOpenCode
        if (b.state.value.isBlocking) b.refreshStatus()
        if (b.state.value.isBlocking) throw JarvisSetupException("OpenCode is not available: ${b.state.value.label}. Check Settings, OpenCode.")
        // the model Jarvis uses must still exist (its provider may have been disconnected)
        if (b.availableModels.value.isEmpty()) { b.refreshModels(); var w = 0; while (b.availableModels.value.isEmpty() && w < 20) { delay(250); w++ } }
        val wanted = JarvisPreferences.modelId.value
        if (b.availableModels.value.isEmpty()) {
            throw JarvisSetupException("OpenCode has no models. Connect a provider in Settings, OpenCode.")
        }
        if (wanted.isNotBlank() && b.availableModels.value.none { it.id == wanted }) {
            throw JarvisSetupException("The model I'm set to, ${wanted.substringAfter('/')}, is not available in OpenCode anymore. Pick another one in Settings, Jarvis, Agent.")
        }
        var id = JarvisPreferences.currentChatId.value
        // a Jarvis chat deleted on the server is replaced by a fresh one
        val gone = id.isNotBlank() && JarvisPreferences.currentChatAgent.value == AgentKind.OPENCODE.name && b.client.get("/session/$id").isFailure
        if (id.isBlank() || JarvisPreferences.currentChatAgent.value != AgentKind.OPENCODE.name || gone) {
            b.startNewChat()
            delay(150)
            id = b.currentConversation.value?.id ?: error("Could not create the Jarvis chat")
            JarvisPreferences.currentChatId.set(id)
            JarvisPreferences.currentChatAgent.set(AgentKind.OPENCODE.name)
            JarvisPreferences.addChatId(id)
            if (wanted.isNotBlank()) b.selectModel(wanted)
            scope.launch {
                delay(6000)
                b.renameConversation(id, "Jarvis · " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                runCatching { vm.openCodeBackend.refreshSessions() }
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
            ?: if (JarvisPreferences.isJarvisChat(id) && JarvisPreferences.currentChatId.value == id) (JarvisPreferences.agentKind ?: AgentKind.AGY) else AgentKind.AGY

    /** Sends [text] to an existing chat by id; the chat becomes a watched task. Does not change the chat on screen. */
    suspend fun sendToChat(id: String, text: String, owner: String = JarvisManagers.VOICE): String {
        if (id == JarvisPreferences.currentChatId.value) return "That is the Jarvis chat itself."
        if (JarvisManagers.isTextManager(id)) return "That is a Jarvis chat, not a work chat."
        if (JarvisTasks.isTask(id)) return JarvisTasks.reply(id, text, interrupt = false)
        if (vm.allConversations.value.find { it.id == id }?.isRunning == true) {
            return "That chat is busy working, so nothing was sent. Do not interrupt it: handle the request yourself, or ask the user."
        }
        JarvisTasks.adopt(id, agentOfChat(id), text, owner)
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
            AgentKind.OPENCODE -> when {
                id == JarvisPreferences.currentChatId.value && JarvisPreferences.currentChatAgent.value == AgentKind.OPENCODE.name -> jarvisOpenCode.selectModel(modelId)
                vm.currentConversation.value?.id == id -> vm.openCodeBackend.selectModel(modelId)
                JarvisTasks.openCodeBackendOf(id) != null -> JarvisTasks.openCodeBackendOf(id)!!.selectModel(modelId)
                else -> vm.openCodeBackend.switchModelOf(id, modelId).getOrThrow()
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
            AgentKind.OPENCODE -> vm.openCodeBackend.interrupt(id).fold({ "Stopped." }, { "Could not stop: ${it.message}" })
        }
    }

    /** Messages of any chat (Claude: from the transcript; Antigravity: from its trajectory). */
    suspend fun readChat(id: String): List<ChatMessage> = when (agentOfChat(id)) {
        AgentKind.CLAUDE -> vm.claudeBackend.loadMessages(id)
        AgentKind.OPENCODE -> vm.openCodeBackend.loadMessages(id)
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
    /**
     * Jarvis says [text] (the jarvis_speak tool). [listenAfter]: the model expects an answer (true) or not (false); null
     * = it did not say, then a question opens the microphone. The last call of a turn decides.
     */
    suspend fun say(text: String, listenAfter: Boolean? = null, wait: Boolean = false) {
        addLog("Jarvis", text)
        turnSpoke = true
        val wants = listenAfter ?: text.trimEnd().endsWith("?")
        listenAfterRequested = wants
        expectingReply = wants
        if (wants) inConversation = true
        speakOut(text, wait = wait)
    }

    /** Speaks [question] and returns what the user answers (null when nothing was heard in time). */
    suspend fun ask(question: String, timeoutMs: Long = ASK_TIMEOUT_MS): String? = askMutex.withLock {
        val d = CompletableDeferred<String>()
        pendingAsk = d
        // the answer comes back to the model here: no extra listening afterwards for an earlier jarvis_speak of this turn
        listenAfterRequested = false
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
        _log.value = (_log.value + (who to text)).takeLast(LOG_KEEP)
        saveLogSoon()
    }

    /** Clears the conversation history (the panel's Clear). */
    fun clearHistory() {
        _log.value = emptyList()
        saveLogSoon()
    }

    private var logSaveJob: Job? = null

    private fun saveLogSoon() {
        logSaveJob?.cancel()
        logSaveJob = scope.launch {
            delay(1200)
            val arr = org.json.JSONArray()
            _log.value.forEach { (who, text) -> arr.put(org.json.JSONArray().put(who).put(text.take(4000))) }
            app.getSharedPreferences("jarvis_log", android.content.Context.MODE_PRIVATE).edit().putString("lines", arr.toString()).apply()
        }
    }

    private fun loadLog() {
        val raw = app.getSharedPreferences("jarvis_log", android.content.Context.MODE_PRIVATE).getString("lines", null) ?: return
        _log.value = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }.takeLast(LOG_KEEP)
        }.getOrDefault(emptyList())
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
                when {
                    // auto send: what followed the wake phrase goes out as it is
                    rest.length > 2 && JarvisPreferences.autoSend.value -> { inConversation = true; expectingReply = false; cue(1); deliver(rest, Origin.WAKE) }
                    // manual send: it opens the normal capture with those words in it, sent like anything else
                    rest.length > 2 -> startListening(initial = rest, origin = Origin.WAKE)
                    else -> startListening(origin = Origin.WAKE)
                }
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
    ) {
        fun withSource(source: String, chatId: String = this.chatId) = Perm(key, source, chatId, tool, summary, risk, approve, reject)
    }

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

    /** OpenCode's permission requests (shell, edit, web…) as voice approvals. */
    private fun openCodePerms(b: com.example.gemini.data.agent.opencode.OpenCodeChatBackend, source: String): List<Perm> =
        b.pendingPermissions.value.map { p ->
            val res = p.resources.firstOrNull().orEmpty()
            val summary = when (p.action) {
                "shell", "bash" -> "run the command: ${res.take(140)}"
                "edit" -> "edit ${res.substringAfterLast('/').ifBlank { "a file" }}"
                "read" -> "read ${res.substringAfterLast('/').ifBlank { "a file" }}"
                "webfetch" -> "open ${res.ifBlank { "a web page" }}"
                "websearch" -> "search the web for ${res.take(80)}"
                "external_directory" -> "work outside the project in ${res.take(80)}"
                else -> "use ${p.action}"
            }
            val risk = when (p.action) {
                "read", "list", "glob", "grep" -> Risk.READ
                "shell", "bash" -> bashRisk(res)
                else -> Risk.NORMAL
            }
            Perm(
                key = "oc:${p.id}", source = source, chatId = p.sessionId, tool = p.action,
                summary = summary, risk = risk,
                approve = { b.replyPermission(p.id, "once") },
                reject = { why -> b.replyPermission(p.id, "reject", why) }
            )
        }

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
        JarvisTasks.openCodeTaskBackends().forEach { (t, b) -> if (t.active && vm.currentConversation.value?.id != t.id) add(openCodePerms(b, "task")) }
        JarvisTasks.activeTasks().filter { it.agent == AgentKind.AGY && vm.currentConversation.value?.id != it.id }.forEach { add(agyOffscreenPerms(it.id, "task")) }
        val jarvisId = JarvisPreferences.currentChatId.value
        when {
            JarvisPreferences.currentChatAgent.value == AgentKind.OPENCODE.name && jarvisId.isNotBlank() -> add(openCodePerms(jarvisOpenCode, "jarvis"))
            JarvisPreferences.currentChatAgent.value == AgentKind.CLAUDE.name || jarvisId.isBlank() -> add(claudePerms(jarvisClaude, "jarvis"))
            else -> add(agyOffscreenPerms(jarvisId, "jarvis"))
        }
        add(claudePerms(opsClaude, "ops"))
        val onScreen = vm.currentConversation.value
        // the Jarvis text chats that are not on screen: their own tool calls (Jarvis' tools go through at once)
        for (id in JarvisManagers.textKeys()) {
            if (onScreen?.id == id) continue
            JarvisDriver.claudeOf(id)?.let { add(claudePerms(it, "manager", id)) }
            JarvisDriver.openCodeOf(id)?.let { add(openCodePerms(it, "manager")) }
            if (JarvisDriver.agentOf(id) == AgentKind.AGY) add(agyOffscreenPerms(id, "manager"))
        }
        val screenList = when (onScreen?.agent) {
            AgentKind.CLAUDE -> claudePerms(vm.claudeBackend, "screen")
            AgentKind.OPENCODE -> openCodePerms(vm.openCodeBackend, "screen")
            else -> agyPerms()
        }
        // the chat on screen counts as a task's (or a Jarvis text chat's) when it is one
        add(when {
            onScreen != null && JarvisTasks.isTask(onScreen.id) -> screenList.map { it.withSource("task") }
            onScreen != null && JarvisManagers.isTextManager(onScreen.id) -> screenList.map { it.withSource("manager", onScreen.id) }
            else -> screenList
        })
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

    private suspend fun permissionLoop() {
        for (p in permQueue) {
            try {
                if (allPending().none { it.key == p.key }) continue
                if (p.source == "manager") {
                    // a Jarvis text chat is a normal chat: Jarvis' own tools go through, everything else is the user's call
                    if (isJarvisTool(p.tool)) { p.approve(); continue }
                    if (vm.currentConversation.value?.id != p.chatId) {
                        val title = vm.allConversations.value.find { it.id == p.chatId }?.title?.take(40)?.ifBlank { null } ?: "A Jarvis chat"
                        showNotice("“$title” is waiting for your approval: ${p.summary.take(90)}", "chat:${p.chatId}")
                        if (JarvisManagers.of(p.chatId).speak) announce("$title needs your approval.")
                    }
                    continue
                }
                val policy = JarvisPreferences.permissionPolicy.value
                val auto = isJarvisTool(p.tool) ||
                    policy == "auto_all" ||
                    (policy == "auto_except_risky" && p.risk != Risk.RISKY) ||
                    (policy == "auto_readonly" && p.risk == Risk.READ)
                if (auto) { p.approve(); continue }
                val task = if (p.source == "task") JarvisTasks.find(p.chatId) else null
                val owner = task?.let { JarvisManagers.ownerOrVoice(it.owner) }
                // the manager decides: a Jarvis text chat always, the voice chat when "Jarvis decides" is on
                if (task != null && owner != null && (owner != JarvisManagers.VOICE ||
                        (JarvisPreferences.taskApprovals.value == "jarvis" && JarvisPreferences.eventRouting.value == "model"))) {
                    JarvisEvents.post(owner, JarvisEvents.Event(
                        kind = "task_approval", taskId = task.id, title = task.title, agent = task.agent.name.lowercase(),
                        team = task.team, goal = task.goal, body = p.summary, key = p.key, risk = p.risk.name.lowercase()
                    ))
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
    fun resolve(key: String?, approve: Boolean, all: Boolean, reason: String?, caller: String = JarvisManagers.VOICE): String {
        val every = allPending()
        // each Jarvis chat answers only for its own work
        val list = every.filter { canResolve(it, caller) }
        if (!all && !key.isNullOrBlank() && list.none { it.key == key } && every.any { it.key == key }) {
            return "That request belongs to another Jarvis chat's work: only that chat (or the user) can answer it."
        }
        val targets = if (all || key.isNullOrBlank()) list else list.filter { it.key == key }
        if (targets.isEmpty()) return "Nothing of yours is waiting for approval."
        targets.forEach { if (approve) it.approve() else it.reject(reason) }
        return "${if (approve) "Approved" else "Rejected"} ${targets.size} request${if (targets.size == 1) "" else "s"}."
    }

    /** What [caller] may answer of what is waiting (each Jarvis chat answers only for its own work). */
    fun pendingFor(caller: String): List<Perm> = allPending().filter { canResolve(it, caller) }

    /** [caller] may answer [p]: its own tasks; the voice chat also its own chat, the chats it drives and the one on screen. */
    private fun canResolve(p: Perm, caller: String): Boolean = when (p.source) {
        "task" -> JarvisTasks.find(p.chatId)?.let { JarvisManagers.ownerOrVoice(it.owner) == caller } ?: (caller == JarvisManagers.VOICE)
        "manager" -> false
        else -> caller == JarvisManagers.VOICE
    }

    // ------------------------------------------------------------------ small cues

    @Suppress("DEPRECATION")
    private fun cue(kind: Int) {
        runCatching {
            // 1 = mic opens, 2 = heard / sent, 3 = quiet-mode reply (buzz only), 4 = mic closed waiting for a reply (tone only)
            if (JarvisPreferences.haptics.value && kind != 4) {
                val v = app.getSystemService(Vibrator::class.java)
                val ms = when (kind) { 1 -> 25L; 3 -> 10L; else -> 15L }
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                else v?.vibrate(ms)
            }
            if (kind != 3 && JarvisPreferences.earcons.value) {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, if (kind == 4) 35 else 55)
                tg.startTone(when (kind) { 1 -> ToneGenerator.TONE_PROP_BEEP; 4 -> ToneGenerator.TONE_PROP_BEEP2; else -> ToneGenerator.TONE_PROP_ACK }, 110)
                scope.launch { delay(400); tg.release() }
            }
        }
    }
}
