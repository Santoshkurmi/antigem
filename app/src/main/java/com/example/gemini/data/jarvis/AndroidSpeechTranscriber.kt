package com.example.gemini.data.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.gemini.data.audio.SpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation with the device's own speech recognizer. It needs no agent sign-in, so it also serves the wake phrase.
 * The recognizer ends after each sentence; this restarts it until the session is stopped.
 */
class AndroidSpeechTranscriber(
    context: Context,
    private val language: () -> String
) : SpeechTranscriber {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _amp = MutableStateFlow(0.08f)
    override val latestAmplitude: StateFlow<Float> = _amp.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var lastPartial = ""
    private var onPartial: (String) -> Unit = {}
    private var onFinal: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    override fun startTranscriptionSession(
        scope: CoroutineScope,
        cascadeId: String,
        preCursorText: String,
        postCursorText: String,
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onPartial = onPartialText
        this.onFinal = onFinalText
        this.onError = onError
        active = true
        paused = false
        lastPartial = ""
        main.post { begin() }
    }

    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            active = false
            onError("This device has no speech recognizer. Install or enable Google speech services.")
            return
        }
        destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(app).also { it.setRecognitionListener(listener) }
        listen()
    }

    private fun listen() {
        if (!active || paused) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language().ifBlank { "en" })
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private fun destroy() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            _amp.value = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) {
                lastPartial = t
                onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { lastPartial }
            lastPartial = ""
            if (t.isNotBlank()) onFinal(t)
            // the recognizer stops after each sentence: go on
            main.postDelayed({ listen() }, 150)
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    val t = lastPartial
                    lastPartial = ""
                    if (t.isNotBlank()) onFinal(t)
                    main.postDelayed({ listen() }, 250)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ begin() }, 600)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    active = false
                    this@AndroidSpeechTranscriber.onError("Microphone permission is missing.")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    this@AndroidSpeechTranscriber.onError("Speech recognition needs a connection right now.")
                    main.postDelayed({ if (active) begin() }, 2000)
                }
                else -> main.postDelayed({ if (active) begin() }, 500)
            }
        }
    }

    override fun stopTranscriptionSession(onDone: ((String) -> Unit)?) {
        val rest = lastPartial
        lastPartial = ""
        active = false
        main.post { destroy() }
        onDone?.invoke(rest)
    }

    override fun cancelTranscriptionSession() {
        active = false
        lastPartial = ""
        main.post { destroy() }
    }

    override fun pauseTranscriptionSession() {
        paused = true
        main.post { runCatching { recognizer?.cancel() } }
    }

    override fun resumeTranscriptionSession() {
        paused = false
        main.post { listen() }
    }
}
