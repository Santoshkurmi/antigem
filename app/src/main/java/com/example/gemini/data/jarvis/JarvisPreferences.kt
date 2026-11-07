package com.example.gemini.data.jarvis

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings and bookkeeping of Jarvis (the hands-free voice mode). Everything is off until the user turns Jarvis on in
 * Settings → Jarvis. Values are read from any thread; every one is a StateFlow so the settings screen stays live.
 */
object JarvisPreferences {
    private var sp: SharedPreferences? = null

    /** One persisted value with a live flow. */
    class Pref<T : Any>(val key: String, val default: T) {
        val flow = MutableStateFlow(default)
        val state: StateFlow<T> get() = flow
        val value: T get() = flow.value

        fun set(v: T) {
            flow.value = v
            val e = sp?.edit() ?: return
            when (v) {
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                is Long -> e.putLong(key, v)
                is Float -> e.putFloat(key, v)
                is String -> e.putString(key, v)
                else -> {}
            }
            e.apply()
        }

        @Suppress("UNCHECKED_CAST")
        internal fun load(p: SharedPreferences) {
            val d = default
            flow.value = when (d) {
                is Boolean -> p.getBoolean(key, d)
                is Int -> p.getInt(key, d)
                is Long -> p.getLong(key, d)
                is Float -> p.getFloat(key, d)
                is String -> p.getString(key, d) ?: d
                else -> d
            } as T
        }
    }

    private val all = mutableListOf<Pref<*>>()
    private fun <T : Any> pref(key: String, default: T): Pref<T> = Pref(key, default).also { all += it }

    // ---- master switch and agent
    /** Jarvis is available (the Jarvis chip, the MCP server and the voice controls are active). */
    val enabled = pref("enabled", false)
    /** "CLAUDE", "AGY" or "OPENCODE": which agent runs the hidden Jarvis chat; blank until the user picks one. */
    val agent = pref("agent", "")
    /** Model of the Jarvis chat; blank = the agent's default. */
    val modelId = pref("model_id", "")

    // ---- chat rotation (all manual-first: 0 / off means never)
    /** Start a fresh Jarvis chat after this many idle minutes (0 = never). */
    val rotateIdleMinutes = pref("rotate_idle_min", 0)
    /** Start a fresh Jarvis chat after this many messages (0 = never). */
    val rotateMaxMessages = pref("rotate_max_msgs", 0)
    /** A new Jarvis chat starts with the Jarvis memory note and a short recap. */
    val carryOver = pref("carry_over", true)
    /** The note Jarvis keeps across chats (jarvis_memory_write). */
    val memory = pref("memory", "")

    // ---- voice output
    /** "android" (device TTS), "google_cloud" or "openai" (any OpenAI-compatible /audio/speech API). */
    val ttsEngine = pref("tts_engine", "android")
    val ttsVoice = pref("tts_voice", "")
    val ttsLanguage = pref("tts_language", "")
    val ttsRate = pref("tts_rate", 1.0f)
    val ttsPitch = pref("tts_pitch", 1.0f)
    val ttsApiKey = pref("tts_api_key", "")
    val ttsBaseUrl = pref("tts_base_url", "")
    val ttsModel = pref("tts_model", "")
    /** Jarvis says short progress updates while it works. */
    val speakProgress = pref("speak_progress", false)
    /** Quiet mode: Jarvis shows its answers on the card instead of saying them (meetings, night). */
    val quietMode = pref("quiet_mode", false)

    // ---- personality and the user's own notes (sent with the first message of every Jarvis chat)
    /**
     * Whom Jarvis speaks as: "female", "male", "neutral" or "" (not set). It decides the grammatical gender Jarvis uses
     * for itself, which matters in languages like Hindi ("kar rahi hoon" / "kar raha hoon").
     */
    val persona = pref("persona", "")
    /** What the user wants Jarvis to know: what to call them, language, context, standing instructions. */
    val aboutUser = pref("about_user", "")
    /** "<chat id>|<hash>": the chat that already knows the current persona and notes. */
    val profileSentFor = pref("profile_sent_for", "")

    // ---- voice input
    /** "auto" (the Jarvis agent's own dictation), "claude", "agy" or "android". */
    val sttEngine = pref("stt_engine", "auto")
    val sttLanguage = pref("stt_language", "en")
    /** How long a pause ends your sentence. */
    val silenceMs = pref("silence_ms", 1500)
    /** "push" (button only) or "phrase" (say the wake phrase while hands-free is on). */
    val hotword = pref("hotword", "push")
    /** One or more wake phrases, separated by commas ("hey jarvis, ok jarvis"). */
    val wakePhrase = pref("wake_phrase", "hey jarvis")
    /** "phrase" (the whole phrase must be heard) or "name" (the name alone, e.g. "Jarvis, …", wakes it). */
    val wakeMatch = pref("wake_match", "phrase")
    /** Old on/off switch of follow-up listening; replaced by [listenAfter], kept so old settings still load. */
    val conversationMode = pref("conversation_mode", true)
    /**
     * Opening the microphone after Jarvis spoke: "auto" (when Jarvis expects an answer: the model decides with
     * jarvis_speak listen_after, and a question always counts), "always" (after every answer while you are talking to
     * it) or "never" (only jarvis_ask still listens).
     */
    val listenAfter = pref("listen_after", "auto")
    /** How long the microphone waits for a reply after Jarvis spoke; no word at all in this time closes it (seconds). */
    val followUpSeconds = pref("follow_up_s", 8)
    /**
     * Send by itself when you pause. Off (the default): what you say is sent only by a send phrase, the Send button, the
     * headset button or the notification, so taking a break to think never sends half a sentence.
     */
    val autoSend = pref("auto_send", false)
    /** How the pause is judged when [autoSend] is on: "smart" (longer when the sentence sounds unfinished) or "pause" (fixed). */
    val sendMode = pref("send_mode", "smart")
    /** Short answers to Jarvis' own questions ("yes", "the second one") send after a pause even with [autoSend] off. */
    val autoSendAnswers = pref("auto_send_answers", true)
    /** Saying one of [sendPhrases] at the end sends at once (the phrase itself is not sent). */
    val sendPhrasesOn = pref("send_phrases_on", true)
    /** One phrase per line; see [JarvisPhrases] for when a phrase counts. */
    val sendPhrases = pref("send_phrases", JarvisPhrases.DEFAULT_SEND.joinToString("\n"))
    /** Saying one of [cancelPhrases] at the end throws the capture away. */
    val cancelPhrasesOn = pref("cancel_phrases_on", true)
    val cancelPhrases = pref("cancel_phrases", JarvisPhrases.DEFAULT_CANCEL.joinToString("\n"))
    /** Longest single capture (seconds); what was heard is sent then. */
    val maxListenSeconds = pref("max_listen_s", 90)
    /**
     * Talking to other people near the phone: "off" (everything is for Jarvis), "smart" (the model ignores follow-ups that
     * were clearly said to someone else), "strict" (follow-ups must name Jarvis unless it just asked you something).
     */
    val sideTalk = pref("side_talk", "smart")
    /** Short tones when the microphone opens and closes. */
    val earcons = pref("earcons", true)
    /** You speak while Jarvis is still working: "queue" (wait), "interrupt" (stop it and send), "smart" (corrections interrupt). */
    val busyBehavior = pref("busy_behavior", "smart")
    /** The headset / Bluetooth button talks (one press) and stops (two presses) while hands-free is on. */
    val headsetButton = pref("headset_button", true)
    /** Live context (screen, project, chats, tasks) goes with every message so Jarvis rarely has to look it up. */
    val contextInPrompt = pref("context_in_prompt", true)

    // ---- permissions
    /** "ask_each", "auto_readonly", "auto_except_risky" or "auto_all". */
    val permissionPolicy = pref("permission_policy", "ask_each")
    /** Jarvis may change app settings (jarvis_set_setting). Off keeps it read-only. */
    val allowSettingChanges = pref("allow_setting_changes", false)

    // ---- announcements (all off until the user asks)
    /** Say "<chat> is done, please check it" when any chat finishes working. */
    val announceDone = pref("announce_done", false)
    /** Skip the announcement for the chat the user is looking at. */
    val announceOffscreenOnly = pref("announce_offscreen_only", true)
    /** Also read the first sentence of the chat's last reply. */
    val announceSummary = pref("announce_summary", false)
    /** Warn once per window when the 5-hour usage is nearly used up. */
    val announceUsageLow = pref("announce_usage_low", false)

    // ---- manager (tasks Jarvis runs in their own chats)
    /** Task news (done, failed, stuck, questions): "model" (Jarvis reads it and decides what to say or do) or "speak" (just announce). */
    val eventRouting = pref("event_routing", "model")
    /** Approvals inside tasks: "policy" (the approval rules below, asking you by voice) or "jarvis" (Jarvis decides from the task's goal; risky ones still ask you). */
    val taskApprovals = pref("task_approvals", "policy")
    /** Tell Jarvis when a running task shows no progress for this many minutes (0 = never). */
    val stallMinutes = pref("stall_minutes", 10)

    // ---- look
    /** "floating" orb or "fullscreen" overlay. */
    val orbStyle = pref("orb_style", "floating")
    /** Size of the orb while Jarvis is idle: "tiny", "small", "normal" or "hidden" (only shown while active). */
    val orbSize = pref("orb_size", "small")
    /** Edge the orb rests on ("left" / "right") and its height (0 = top, 1 = bottom). */
    val orbSide = pref("orb_side", "right")
    val orbY = pref("orb_y", 0.78f)
    /** Keep the screen on: "off", "active" (while Jarvis listens, works, speaks or a task runs) or "always" (while Jarvis is on). */
    val keepScreenOn = pref("keep_screen_on", "always")
    val haptics = pref("haptics", true)
    val showTranscript = pref("show_transcript", true)
    /** A small card shows what Jarvis is doing while it works. */
    val showActivity = pref("show_activity", true)

    // ---- state kept between launches
    /** Hands-free mode (microphone service) was switched on. */
    val handsFree = pref("hands_free", false)
    val currentChatId = pref("chat_id", "")
    val currentChatAgent = pref("chat_agent", AgentKind.CLAUDE.name)
    val currentChatMessages = pref("chat_msgs", 0)
    val lastActivityAt = pref("last_activity", 0L)
    val setupSkillInstalled = pref("skill_installed", "")
    /** "<chat id>|<guide version>": the chat that already got the full Jarvis guide (boot message). */
    val bootSentFor = pref("boot_sent_for", "")

    private val _chatIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of every chat Jarvis ever opened (they are listed under the Jarvis filter, not in the normal lists). */
    val chatIds: StateFlow<Set<String>> get() = _chatIds

    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        sp = p
        all.forEach { it.load(p) }
        _chatIds.value = p.getStringSet("chat_ids", emptySet())?.toSet().orEmpty()
    }

    @Synchronized
    fun addChatId(id: String) {
        if (id.isBlank() || id in _chatIds.value) return
        val next = _chatIds.value + id
        _chatIds.value = next
        sp?.edit()?.putStringSet("chat_ids", next)?.apply()
    }

    @Synchronized
    fun removeChatId(id: String) {
        val next = _chatIds.value - id
        _chatIds.value = next
        sp?.edit()?.putStringSet("chat_ids", next)?.apply()
    }

    /** The agent the user picked for Jarvis, or null when none was picked yet. */
    val agentKind: AgentKind?
        get() = agent.value.takeIf { it.isNotBlank() }?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() }

    fun isJarvisChat(id: String?): Boolean = id != null && id in _chatIds.value
}
