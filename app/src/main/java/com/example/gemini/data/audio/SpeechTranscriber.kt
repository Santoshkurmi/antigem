package com.example.gemini.data.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Live dictation: microphone in, text out. Implemented per agent (Antigravity hub, Claude voice relay). */
interface SpeechTranscriber {
    /** 0.08..1 microphone level for the waveform. */
    val latestAmplitude: StateFlow<Float>

    /**
     * [onPartialText] receives the utterance being spoken, [onFinalText] the finished utterance; both carry only the
     * current utterance, not the whole dictation.
     */
    fun startTranscriptionSession(
        scope: CoroutineScope,
        cascadeId: String = "",
        preCursorText: String = "",
        postCursorText: String = "",
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (String) -> Unit
    )

    /** Stops listening; [onDone] receives text that was heard but not yet finalized. */
    fun stopTranscriptionSession(onDone: ((String) -> Unit)? = null)

    fun cancelTranscriptionSession()

    fun pauseTranscriptionSession()

    fun resumeTranscriptionSession()
}
