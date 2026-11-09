package com.example.gemini.data.voice

import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One line of a saved voice call: what the user said, what the voice said, or the voice handing work to the agent. */
@Serializable
data class VoiceChatLine(val role: String = "", val text: String = "", val time: String? = null) {
    val isUser: Boolean get() = role == "ROLE_USER"
    val isVoice: Boolean get() = role == "ROLE_VOICE"
    val isHandoff: Boolean get() = role == "ROLE_HANDOFF"
    val timeMs: Long? get() = parseIsoMillis(time)
}

/** A voice call the hub saved for a chat. */
@Serializable
data class VoiceChatRecord(val startedAt: String? = null, val stoppedAt: String? = null, val lines: List<VoiceChatLine> = emptyList()) {
    val startedAtMs: Long? get() = parseIsoMillis(startedAt)
    val stoppedAtMs: Long? get() = parseIsoMillis(stoppedAt)
    val durationMs: Long? get() = startedAtMs?.let { s -> stoppedAtMs?.let { e -> (e - s).coerceAtLeast(0) } }
    val hasTalk: Boolean get() = lines.any { (it.isUser || it.isVoice) && it.text.isNotBlank() }
}

@Serializable
private data class VoiceChatsResponse(val chats: List<VoiceChatRecord> = emptyList())

/**
 * The voice calls of each AGY chat, as the hub saved them (`GET /voice/chats?conversation_id=…`, agy 1.5+): the chat
 * shows a marker per call that opens its transcript.
 */
object AgyVoiceChats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }

    private val _chats = MutableStateFlow<Map<String, List<VoiceChatRecord>>>(emptyMap())
    /** Chat id → its saved voice calls, oldest first. */
    val chats: StateFlow<Map<String, List<VoiceChatRecord>>> = _chats.asStateFlow()

    /** Fetches the voice calls of [conversationId] (quietly does nothing on a hub without them). */
    fun refresh(conversationId: String) {
        if (conversationId.isBlank()) return
        scope.launch {
            val base = AuthPreferences.currentHubUrl.trimEnd('/')
            val url = "$base/voice/chats".toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("conversation_id", conversationId)?.build() ?: return@launch
            val list = runCatching {
                client.newCall(Request.Builder().url(url).agyHubHeaders().get().build()).execute().use { r ->
                    if (!r.isSuccessful) return@launch
                    json.decodeFromString<VoiceChatsResponse>(r.body?.string().orEmpty()).chats
                }
            }.getOrNull() ?: return@launch
            val sorted = list.filter { it.hasTalk }.sortedBy { it.startedAtMs ?: 0L }
            _chats.update { if (it[conversationId] == sorted) it else it + (conversationId to sorted) }
        }
    }
}

/** RFC 3339 time as the hub writes it ("2026-11-09T14:11:44.019094858Z", or with a +hh:mm offset) → epoch ms. */
internal fun parseIsoMillis(s: String?): Long? {
    if (s.isNullOrBlank() || s.length < 19) return null
    return runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var ms = fmt.parse(s.substring(0, 19))!!.time
        var i = 19
        if (i < s.length && s[i] == '.') {
            i++
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            ms += s.substring(start, i).padEnd(3, '0').take(3).toLong()
        }
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val sign = if (s[i] == '+') 1 else -1
            val hh = s.substring(i + 1, i + 3).toInt()
            val mm = s.substring(i + 4, i + 6).toInt()
            ms -= sign * (hh * 3_600_000L + mm * 60_000L)
        }
        ms
    }.getOrNull()
}
