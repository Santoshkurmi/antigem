package com.example.gemini.data.agent.claude

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.gemini.data.audio.SpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Claude Code dictation: 16 kHz mono PCM from the microphone is streamed to the bridge, which relays it to
 * Claude's speech-to-text service with the CLI's sign-in; transcript frames come back over the same socket.
 */
class ClaudeVoiceTranscriber(
    private val client: ClaudeBridgeClient,
    private val language: () -> String
) : SpeechTranscriber {

    private val _latestAmplitude = MutableStateFlow(0.08f)
    override val latestAmplitude: StateFlow<Float> = _latestAmplitude.asStateFlow()

    private var socket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    // Claude's transcript is cumulative for the whole stream: TranscriptText carries everything said so far, and
    // TranscriptEndpoint (normally only after CloseStream) ends it. Text up to an endpoint is reported once.
    @Volatile private var pendingText = ""
    @Volatile private var committedText = ""
    @Volatile private var stopping = false
    @Volatile private var closed = CompletableSignal()
    private var callbacks: Callbacks? = null

    private class Callbacks(
        val scope: CoroutineScope,
        val onPartial: (String) -> Unit,
        val onFinal: (String) -> Unit,
        val onError: (String) -> Unit
    )

    @SuppressLint("MissingPermission")
    override fun startTranscriptionSession(
        scope: CoroutineScope,
        cascadeId: String,
        preCursorText: String,
        postCursorText: String,
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (recording.get()) cancelTranscriptionSession()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
        } catch (e: Exception) {
            onError("Microphone unavailable: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("Microphone unavailable")
            return
        }
        audioRecord = record
        pendingText = ""
        committedText = ""
        stopping = false
        closed = CompletableSignal()
        callbacks = Callbacks(scope, onPartialText, onFinalText, onError)
        recording.set(true)
        paused.set(false)

        socket = client.openVoice(language(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete()
                if (recording.get()) report { it.onError("Dictation connection failed: ${t.message}") }
                stopAudio()
            }
        })

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                record.startRecording()
                val buffer = ByteArray(CHUNK)
                while (isActive && recording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (paused.get()) {
                        _latestAmplitude.value = 0.08f
                        continue
                    }
                    _latestAmplitude.value = amplitude(buffer, read)
                    socket?.send(buffer.copyOf(read).toByteString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "recording stopped: ${e.message}")
            }
        }
    }

    private fun handleFrame(text: String) {
        val obj = runCatching { ClaudeJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "TranscriptInterim", "TranscriptText" -> {
                val data = obj["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // drop what an earlier endpoint already reported, keep only this utterance
                val segment = (if (committedText.isNotEmpty() && data.startsWith(committedText)) data.removePrefix(committedText) else data).trim()
                if (segment.isNotBlank()) {
                    pendingText = segment
                    report { it.onPartial(segment) }
                }
            }
            "TranscriptEndpoint" -> {
                // while stopping, the final text goes to stopTranscriptionSession's callback instead
                if (stopping) return
                val done = pendingText
                pendingText = ""
                if (done.isNotBlank()) {
                    committedText = (obj["data"]?.jsonPrimitive?.contentOrNull ?: (committedText + " " + done)).trim()
                    report { it.onFinal(done) }
                }
            }
            "TranscriptError", "error" -> {
                val msg = obj["description"]?.jsonPrimitive?.contentOrNull ?: obj["message"]?.jsonPrimitive?.contentOrNull ?: "transcription error"
                report { it.onError(msg) }
                cancelTranscriptionSession()
            }
        }
    }

    private fun report(block: (Callbacks) -> Unit) {
        val cb = callbacks ?: return
        cb.scope.launch(Dispatchers.Main) { block(cb) }
    }

    override fun stopTranscriptionSession(onDone: ((String) -> Unit)?) {
        val cb = callbacks
        stopping = true
        stopAudio()
        val ws = socket
        if (ws == null) {
            onDone?.invoke(pendingText.also { pendingText = "" })
            return
        }
        ws.send("""{"type":"CloseStream"}""")
        val scope = cb?.scope ?: return
        scope.launch {
            // wait for the final transcript (the bridge closes the socket after it)
            withContext(Dispatchers.IO) { closed.await(3_000) }
            ws.close(1000, null)
            socket = null
            val rest = pendingText
            pendingText = ""
            callbacks = null
            onDone?.invoke(rest)
        }
    }

    override fun cancelTranscriptionSession() {
        stopAudio()
        socket?.cancel()
        socket = null
        pendingText = ""
        callbacks = null
    }

    override fun pauseTranscriptionSession() {
        paused.set(true)
    }

    override fun resumeTranscriptionSession() {
        paused.set(false)
    }

    private fun stopAudio() {
        recording.set(false)
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        _latestAmplitude.value = 0.08f
    }

    private fun amplitude(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        val samples = len / 2
        if (samples == 0) return 0.08f
        for (i in 0 until len - 1 step 2) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s * s
        }
        return (sqrt(sum / samples).toFloat() / 6500f).coerceIn(0.08f, 1f)
    }

    /** Minimal one-shot latch usable from any thread. */
    private class CompletableSignal {
        private val latch = java.util.concurrent.CountDownLatch(1)
        fun complete() = latch.countDown()
        fun await(ms: Long) {
            latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val TAG = "ClaudeVoice"
        const val SAMPLE_RATE = 16000
        const val CHUNK = 3200 // 100 ms
    }
}
