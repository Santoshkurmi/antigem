package com.example.gemini.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.example.gemini.data.remote.AgyHubClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * High-performance, device-independent audio transcription engine powered by AGY LanguageServerService.
 *
 * Uses native 16kHz 16-bit mono PCM capturing via Android's AudioRecord (bypassing all proprietary
 * Google Speech / OEM voice services), directly streaming chunks to AGY Hub via:
 * 1. StreamAudioTranscription (gRPC-Web server stream) -> yields AudioStreamReady(sessionId)
 * 2. SendAudioChunk (Connect-RPC unary) -> sends 100ms 16kHz PCM chunks
 * 3. EndAudioSession (Connect-RPC unary) -> terminates session and finalizes transcription
 */
class AgyAudioTranscriptionManager(
    private val agyHubClient: AgyHubClient,
    private val getHubUrl: suspend () -> String
) : SpeechTranscriber {
    companion object {
        private const val TAG = "AgyAudioTranscription"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_BYTES = 3200 // 100ms at 16kHz 16-bit mono (32kB/s)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var streamJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val currentSessionId = MutableStateFlow<String?>(null)

    private val _isLiveStreaming = MutableStateFlow(false)
    val isLiveStreaming = _isLiveStreaming.asStateFlow()

    private val _isLivePaused = MutableStateFlow(false)
    val isLivePaused = _isLivePaused.asStateFlow()

    private val _latestAmplitude = MutableStateFlow(0.08f)
    override val latestAmplitude = _latestAmplitude.asStateFlow()

    private var lastTranscribedText = ""

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
        if (isRecording.get()) {
            stopTranscriptionSession()
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, CHUNK_SIZE_BYTES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            onError("Microphone init failed: ${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            onError("Microphone not available")
            return
        }

        audioRecord = record
        isRecording.set(true)
        isPaused.set(false)
        _isLivePaused.value = false
        _isLiveStreaming.value = true
        currentSessionId.value = null
        lastTranscribedText = ""

        val pendingChunks = ConcurrentLinkedQueue<Pair<Long, ByteArray>>()
        val seqCounter = AtomicLong(0L)

        // 1. Launch AudioRecord worker to read 16kHz PCM chunks
        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                record.startRecording()
                val buffer = ByteArray(CHUNK_SIZE_BYTES)

                while (isRecording.get() && isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (isPaused.get()) {
                            _latestAmplitude.value = 0.08f
                            continue
                        }
                        val chunk = buffer.copyOf(read)

                        // Calculate RMS amplitude for live waveform animation
                        var sum = 0.0
                        val sampleCount = read / 2
                        if (sampleCount > 0) {
                            for (i in 0 until read step 2) {
                                val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                                val signedSample = if (sample >= 32768) sample - 65536 else sample
                                sum += (signedSample * signedSample)
                            }
                            val rms = sqrt(sum / sampleCount)
                            val normalized = (rms.toFloat() / 6500f).coerceIn(0.08f, 1f)
                            _latestAmplitude.value = normalized
                        }

                        val seq = seqCounter.getAndIncrement()
                        val sid = currentSessionId.value
                        val hubUrl = getHubUrl()

                        if (sid != null) {
                            // Flush any chunks that accumulated before session was ready
                            while (!pendingChunks.isEmpty()) {
                                val (pSeq, pChunk) = pendingChunks.poll() ?: break
                                val b64 = Base64.encodeToString(pChunk, Base64.NO_WRAP)
                                agyHubClient.sendAudioChunk(sid, b64, pSeq, hubUrl)
                            }
                            // Send current chunk
                            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                            agyHubClient.sendAudioChunk(sid, b64, seq, hubUrl)
                        } else {
                            pendingChunks.add(Pair(seq, chunk))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop exception: ${e.message}")
            }
        }

        // 2. Launch stream subscriber to AGY LanguageServerService/StreamAudioTranscription
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val hubUrl = getHubUrl()
                agyHubClient.streamAudioTranscription(
                    cascadeId = cascadeId,
                    preCursorText = preCursorText,
                    postCursorText = postCursorText,
                    hubUrl = hubUrl
                ).collect { response ->
                    try {
                        response.ready?.session_id?.takeIf { it.isNotBlank() }?.let { sid ->
                            Log.d(TAG, "Audio stream session ready: $sid")
                            currentSessionId.value = sid
                        }
                        response.transcription?.let { trans ->
                            val text = trans.text
                            val isFinal = trans.is_final
                            if (text.isNotBlank()) {
                                lastTranscribedText = text
                                if (isFinal) {
                                    withContext(Dispatchers.Main) { onFinalText(text) }
                                } else {
                                    withContext(Dispatchers.Main) { onPartialText(text) }
                                }
                            }
                        }
                        if (response.complete != null) {
                            Log.d(TAG, "Audio stream session complete")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed handling stream response: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                if (isRecording.get()) {
                    withContext(Dispatchers.Main) { onError(e.message ?: "Transcription stream failed") }
                }
            }
        }
    }

    override fun stopTranscriptionSession(onDone: ((String) -> Unit)?) {
        val wasRecording = isRecording.getAndSet(false)
        _isLiveStreaming.value = false
        val sid = currentSessionId.value

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        recordingJob?.cancel()

        if (sid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val hubUrl = getHubUrl()
                    agyHubClient.endAudioSession(sid, hubUrl)
                    delay(500) // Grace period for server to send final transcription
                } catch (e: Exception) {
                    Log.w(TAG, "Error ending audio session: ${e.message}")
                } finally {
                    streamJob?.cancel()
                    if (onDone != null) {
                        withContext(Dispatchers.Main) {
                            onDone(lastTranscribedText)
                        }
                    }
                }
            }
        } else {
            streamJob?.cancel()
            onDone?.invoke(lastTranscribedText)
        }
    }

    override fun cancelTranscriptionSession() {
        isRecording.set(false)
        _isLiveStreaming.value = false
        val sid = currentSessionId.value

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordingJob?.cancel()

        if (sid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val hubUrl = getHubUrl()
                    agyHubClient.endAudioSession(sid, hubUrl)
                } catch (_: Exception) {}
                streamJob?.cancel()
            }
        } else {
            streamJob?.cancel()
        }
    }

    override fun pauseTranscriptionSession() {
        if (isRecording.get()) {
            isPaused.set(true)
            _isLivePaused.value = true
            _latestAmplitude.value = 0.08f
        }
    }

    override fun resumeTranscriptionSession() {
        if (isRecording.get()) {
            isPaused.set(false)
            _isLivePaused.value = false
        }
    }

    fun togglePause() {
        if (isPaused.get()) {
            resumeTranscriptionSession()
        } else {
            pauseTranscriptionSession()
        }
    }
}
