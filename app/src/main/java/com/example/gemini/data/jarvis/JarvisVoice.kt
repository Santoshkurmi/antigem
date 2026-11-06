package com.example.gemini.data.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin

/**
 * Jarvis' voice: queues what is to be said and plays it with the device's text-to-speech or an online voice
 * (Google Cloud Text-to-Speech, or any OpenAI-compatible speech API). A failing online voice falls back to the device.
 */
object JarvisVoice {
    private const val TAG = "JarvisVoice"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context
    private var inited = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** 0..1, a lively pulse while speaking (the speech engines do not expose the real signal). */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _lastSpoken = MutableStateFlow("")
    val lastSpoken: StateFlow<String> = _lastSpoken.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private class Utterance(val text: String, val done: CompletableDeferred<Unit>)

    private val queue = Channel<Utterance>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private var pulse: Job? = null
    @Volatile private var current: Job? = null

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---- Android TTS
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    private val ttsWaiters = HashMap<String, CompletableDeferred<Unit>>()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        if (inited) return
        inited = true
        app = context.applicationContext
        consumer = scope.launch {
            for (u in queue) {
                try {
                    _speaking.value = true
                    startPulse()
                    val job = launch { playOne(u.text) }
                    current = job
                    job.join()
                } catch (e: Exception) {
                    Log.w(TAG, "speak failed: ${e.message}")
                } finally {
                    current = null
                    u.done.complete(Unit)
                    if (pending.decrementAndGet() <= 0) {
                        pending.set(0)
                        _speaking.value = false
                        stopPulse()
                        abandonFocus()
                    }
                }
            }
        }
    }

    /** Queues [text]; with [wait] the call returns when it has been said. */
    suspend fun speak(text: String, wait: Boolean = true) {
        val clean = clean(text)
        if (clean.isBlank()) return
        _lastSpoken.value = clean
        val done = CompletableDeferred<Unit>()
        pending.incrementAndGet()
        queue.trySend(Utterance(clean, done))
        if (wait) done.await()
    }

    /** Stops speaking at once and drops what is queued (the user spoke over Jarvis, or said stop). */
    fun stop() {
        while (true) {
            val u = queue.tryReceive().getOrNull() ?: break
            pending.decrementAndGet()
            u.done.complete(Unit)
        }
        current?.cancel()
        runCatching { tts?.stop() }
        runCatching { player?.stop() }
        _speaking.value = false
        stopPulse()
    }

    private fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " code omitted ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("https?://\\S+"), " link ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun startPulse() {
        if (pulse?.isActive == true) return
        pulse = scope.launch {
            var t = 0.0
            while (isActive) {
                t += 0.35
                _level.value = (0.35 + 0.45 * abs(sin(t)) + 0.2 * abs(sin(t * 2.7))).toFloat().coerceIn(0f, 1f)
                delay(60)
            }
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        _level.value = 0f
    }

    // ------------------------------------------------------------------ playback

    private suspend fun playOne(text: String) {
        requestFocus()
        for (chunk in chunks(text)) {
            val engine = JarvisPreferences.ttsEngine.value
            val ok = when (engine) {
                "google_cloud", "openai" -> playOnline(engine, chunk)
                else -> false
            }
            if (!ok) speakAndroid(chunk)
        }
    }

    private fun chunks(text: String): List<String> {
        if (text.length <= 3000) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (sb.length + s.length > 3000 && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(s).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }

    // ---- device voice

    private suspend fun ensureTts(): TextToSpeech? {
        tts?.let { if (ttsReady?.isCompleted == true) return it }
        val ready = ttsReady ?: CompletableDeferred<Boolean>().also { d ->
            ttsReady = d
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(app) { status -> d.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        val ok = ready.await()
        val engine = tts
        if (!ok || engine == null) {
            _lastError.value = "The device's text-to-speech is not available."
            ttsReady = null
            tts = null
            return null
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finishUtterance(utteranceId) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { finishUtterance(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { finishUtterance(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finishUtterance(utteranceId) }
        })
        return engine
    }

    private fun finishUtterance(id: String?) {
        val d = synchronized(ttsWaiters) { ttsWaiters.remove(id) }
        d?.complete(Unit)
    }

    private suspend fun speakAndroid(text: String) {
        val engine = ensureTts() ?: return
        withContext(Dispatchers.Main) {
            engine.setSpeechRate(JarvisPreferences.ttsRate.value.coerceIn(0.4f, 2.5f))
            engine.setPitch(JarvisPreferences.ttsPitch.value.coerceIn(0.4f, 2f))
            val voiceName = JarvisPreferences.ttsVoice.value
            val lang = JarvisPreferences.ttsLanguage.value
            val chosen = if (voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == voiceName } else null
            if (chosen != null) engine.voice = chosen
            else if (lang.isNotBlank()) engine.language = Locale.forLanguageTag(lang)
        }
        val id = "jarvis-" + System.nanoTime()
        val done = CompletableDeferred<Unit>()
        synchronized(ttsWaiters) { ttsWaiters[id] = done }
        withContext(Dispatchers.Main) { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }
        try {
            done.await()
        } finally {
            synchronized(ttsWaiters) { ttsWaiters.remove(id) }
        }
    }

    /** Voices installed on the device, for the settings screen. */
    suspend fun deviceVoices(): List<Pair<String, String>> {
        val engine = ensureTts() ?: return emptyList()
        // off the main thread: asking the engine for its voices is a slow call into the TTS service (Google's engine
        // has hundreds), and on the main thread it froze whatever was on screen (the Jarvis settings page) meanwhile
        return withContext(Dispatchers.Default) {
            runCatching { engine.voices.orEmpty().map { it.name to "${it.locale.displayName} · ${it.name}" } }
                .getOrDefault(emptyList())
                .sortedBy { it.second }
        }
    }

    // ---- online voices

    private suspend fun playOnline(engine: String, text: String): Boolean {
        val key = JarvisPreferences.ttsApiKey.value.trim()
        if (key.isBlank()) {
            _lastError.value = "No API key set for the online voice; using the device voice."
            return false
        }
        return try {
            val bytes = withContext(Dispatchers.IO) {
                if (engine == "google_cloud") googleCloud(text, key) else openAiSpeech(text, key)
            }
            if (bytes == null || bytes.isEmpty()) return false
            _lastError.value = null
            playBytes(bytes)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "online voice failed: ${e.message}")
            _lastError.value = "Online voice failed (${e.message}); used the device voice."
            false
        }
    }

    private fun googleCloud(text: String, key: String): ByteArray? {
        val lang = JarvisPreferences.ttsLanguage.value.ifBlank { "en-US" }
        val voice = JarvisPreferences.ttsVoice.value
        val body = JSONObject().apply {
            put("input", JSONObject().put("text", text))
            put("voice", JSONObject().apply {
                put("languageCode", lang)
                if (voice.isNotBlank()) put("name", voice)
            })
            put("audioConfig", JSONObject().apply {
                put("audioEncoding", "MP3")
                put("speakingRate", JarvisPreferences.ttsRate.value.toDouble().coerceIn(0.25, 4.0))
                put("pitch", ((JarvisPreferences.ttsPitch.value - 1f) * 10.0).coerceIn(-20.0, 20.0))
            })
        }.toString()
        val req = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${JSONObject(s.ifBlank { "{}" }).optJSONObject("error")?.optString("message").orEmpty()}")
            val audio = JSONObject(s).optString("audioContent")
            return if (audio.isBlank()) null else Base64.decode(audio, Base64.DEFAULT)
        }
    }

    private fun openAiSpeech(text: String, key: String): ByteArray? {
        val base = JarvisPreferences.ttsBaseUrl.value.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        val body = JSONObject().apply {
            put("model", JarvisPreferences.ttsModel.value.ifBlank { "tts-1" })
            put("input", text)
            put("voice", JarvisPreferences.ttsVoice.value.ifBlank { "alloy" })
            put("speed", JarvisPreferences.ttsRate.value.toDouble().coerceIn(0.25, 4.0))
            put("response_format", "mp3")
        }.toString()
        val req = Request.Builder()
            .url("$base/audio/speech")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${resp.body?.string().orEmpty().take(160)}")
            return resp.body?.bytes()
        }
    }

    private suspend fun playBytes(bytes: ByteArray) {
        val file = File(app.cacheDir, "jarvis_tts_${System.nanoTime()}.mp3")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setOnCompletionListener { runCatching { mp.release() }; if (player === mp) player = null; if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> runCatching { mp.release() }; if (player === mp) player = null; if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() }; if (player === mp) player = null }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { it.start() }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    runCatching { mp.release() }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } finally {
            file.delete()
        }
    }

    // ---- audio focus (other audio ducks while Jarvis talks)

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
