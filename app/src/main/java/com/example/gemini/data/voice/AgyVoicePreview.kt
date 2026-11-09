package com.example.gemini.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyCsrfManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** The headers every request to the agy hub carries (security header with the hub token, proxy header). */
internal fun Request.Builder.agyHubHeaders(): Request.Builder {
    header("User-Agent", "antiGem-Android-Native")
    val token = AgyCsrfManager.instance.token
    if (token.isNotBlank()) header(AuthPreferences.currentFramedHeader, token)
    AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) }
    return this
}

/**
 * Plays a short sample of a voice-mode voice. agy (1.5+) ships three samples per voice and serves them at
 * `/voices/<name>_<1..3>.mp3`; each is fetched once from the hub and kept in the app's cache.
 */
object AgyVoicePreview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The voice whose sample is playing, or null. */
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    /** The voice whose sample is being fetched, or null. */
    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private var player: MediaPlayer? = null
    private var job: Job? = null
    private val nextSample = mutableMapOf<String, Int>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }

    /** Plays [voice] (the next of its samples each time). */
    fun play(context: Context, voice: String) {
        val app = context.applicationContext
        stop()
        val turn = nextSample.getOrPut(voice) { (0..2).random() }
        nextSample[voice] = turn + 1
        val n = turn % 3 + 1
        _loading.value = voice
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch(app, voice, n) } }
            if (_loading.value != voice) return@launch
            _loading.value = null
            val file = result.getOrElse {
                com.example.gemini.ui.components.AppToastHelper.showToast(
                    "Couldn't load $voice's sample: ${it.message}", com.example.gemini.ui.components.ChatToastType.ERROR
                )
                return@launch
            }
            val mp = MediaPlayer()
            runCatching {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    if (player === it) {
                        player = null
                        _playing.value = null
                    }
                    it.release()
                }
                mp.prepare()
                mp.start()
                player = mp
                _playing.value = voice
            }.onFailure {
                mp.release()
                file.delete()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _loading.value = null
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        _playing.value = null
    }

    private fun fetch(app: Context, voice: String, n: Int): File {
        val name = "${voice.lowercase()}_$n.mp3"
        val dir = File(app.cacheDir, "agy_voices").apply { mkdirs() }
        val file = File(dir, name)
        if (file.length() > 1024) return file
        val base = AuthPreferences.currentHubUrl.trimEnd('/')
        val req = Request.Builder().url("$base/voices/$name").agyHubHeaders().get().build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) error("this agy has no voice samples (agy 1.5 or newer has them)")
            if (!r.isSuccessful) error("the hub answered HTTP ${r.code}")
            val body = r.body ?: error("empty answer")
            val tmp = File(dir, "$name.part")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) error("could not save the sample")
        }
        return file
    }
}
