package com.example.gemini.ui.components

import android.Manifest
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.net.Uri
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.domain.model.ThinkingPreference
import com.example.gemini.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Elegant 4-bar audio waveform icon matching Claude's signature voice mode button.
 */
@Composable
fun ClaudeWaveformIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.Black
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val barWidth = 2.2.dp.toPx()
        val spacing = 1.8.dp.toPx()
        val heights = listOf(0.35f, 0.75f, 1.0f, 0.5f)
        val totalWidth = heights.size * barWidth + (heights.size - 1) * spacing
        val startX = (size.width - totalWidth) / 2f
        val centerY = size.height / 2f

        heights.forEachIndexed { index, fraction ->
            val barHeight = size.height * fraction
            val x = startX + index * (barWidth + spacing)
            val top = centerY - barHeight / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Live audio waveform visualizer that dynamically draws bars reflecting
 * real-time microphone input volume amplitude.
 */
@Composable
fun LiveAudioWaveform(
    amplitudes: List<Float>,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = com.example.gemini.theme.AppColors.danger
) {
    Canvas(modifier = modifier) {
        val count = amplitudes.size.coerceAtLeast(1)
        val spacing = 2.dp.toPx()
        val totalSpacing = spacing * (count - 1)
        val availableWidth = size.width - totalSpacing
        val barWidth = (availableWidth / count).coerceIn(2.dp.toPx(), 4.dp.toPx())
        val maxHeight = size.height
        val midY = maxHeight / 2f

        for (i in amplitudes.indices) {
            val amp = amplitudes[i]
            val barHeight = (maxHeight * amp).coerceAtLeast(3.dp.toPx())
            val x = i * (barWidth + spacing)
            val top = midY - (barHeight / 2f)

            drawRoundRect(
                color = if (isPaused) barColor.copy(alpha = 0.35f) else barColor,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Claude Android style unified input bar:
 * - Single rounded container enclosing text field, attachments, [+] button, model selector pill, and voice recorder / send button.
 * - Dynamic microphone icon with native MediaRecorder audio recording, stop & attach, or direct send.
 */
@Composable
fun ChatInputBar(
    selectedModel: AiModel,
    quota: ModelQuota?,
    thinkingPreference: ThinkingPreference,
    /** The typed text; the keyboard edits it directly, so typing never waits on the screen redrawing. */
    textFieldState: TextFieldState,
    onOpenModelSelector: () -> Unit,
    onOpenThinkingSelector: () -> Unit,
    isStreaming: Boolean,
    isBackgroundActive: Boolean = false,
    onSendMessage: (String) -> Unit,
    onStopStreaming: () -> Unit,
    attachments: List<ChatAttachment> = emptyList(),
    isUploadingAttachment: Boolean = false,
    onRemoveAttachment: (String) -> Unit = {},
    onAddAttachment: (ChatAttachment) -> Unit = {},
    onAttachClick: () -> Unit = {},
    onTranscribeAudioFile: ((File, onDone: (String) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    isTranscribingAudio: Boolean = false,
    speechManager: com.example.gemini.data.audio.SpeechTranscriber? = null,
    cascadeId: String = "",
    isOnline: Boolean = true,
    isAuth: Boolean = true,
    /** Shown when sending is blocked because [isOnline] is false. */
    offlineMessage: String = "Server is offline. Start the server to send messages.",
    /** The agent cannot read audio (Claude): the mic records and transcribes to text instead of attaching a file. */
    voiceNotesAsText: Boolean = false,
    /** The agent has no voice input (OpenCode): no dictation or voice-note buttons; Send stays in their place. */
    voiceEnabled: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** Messages sent while the agent was working, waiting to be delivered (card above the box). */
    queuedMessages: List<com.example.gemini.domain.model.QueuedMessage> = emptyList(),
    onSendQueuedNow: (String) -> Unit = {},
    onDeleteQueued: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = isAppInDarkTheme()

    // Audio recording & Speech-to-Text state
    var recordMode by remember { mutableStateOf(AudioRecordMode.VOICE_NOTE) }
    var isLiveDictating by remember { mutableStateOf(false) }
    var isDictationPaused by remember { mutableStateOf(false) }
    var baseTextBeforeDictation by remember { mutableStateOf("") }
    var pendingActionAfterPermission by remember { mutableStateOf<String?>(null) }

    var isRecordingAudio by remember { mutableStateOf(false) }
    var isRecordingPaused by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentRecordingFile by remember { mutableStateOf<File?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val waveformAmplitudes = remember { mutableStateListOf<Float>() }

    // Stream live waveform amplitudes from AGY speech manager when active
    LaunchedEffect(isRecordingAudio, isLiveDictating, recordMode, speechManager) {
        if ((isRecordingAudio || isLiveDictating) && speechManager != null) {
            speechManager.latestAmplitude.collect { amp ->
                if (waveformAmplitudes.size >= 24) {
                    waveformAmplitudes.removeAt(0)
                }
                waveformAmplitudes.add(amp)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            speechManager?.cancelTranscriptionSession()
        }
    }

    fun startAudioRecording() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return
        }

        try {
            val audioFile = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.m4a")
            currentRecordingFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(audioFile.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            isRecordingAudio = true
            isRecordingPaused = false
            recordingDurationSeconds = 0
            waveformAmplitudes.clear()
            repeat(24) { waveformAmplitudes.add(0.08f) }

            recordingJob = coroutineScope.launch {
                var msElapsed = 0L
                while (isRecordingAudio && isActive) {
                    delay(50)
                    if (!isRecordingPaused) {
                        msElapsed += 50
                        if (msElapsed >= 1000) {
                            msElapsed -= 1000
                            recordingDurationSeconds++
                        }

                        val maxAmp = try {
                            recorder.maxAmplitude
                        } catch (_: Exception) { 0 }

                        val normalized = (maxAmp.toFloat() / 22000f).coerceIn(0f, 1f)
                        val target = (normalized * 0.92f + 0.08f).coerceIn(0.08f, 1f)

                        if (waveformAmplitudes.size >= 24) {
                            waveformAmplitudes.removeAt(0)
                        }
                        waveformAmplitudes.add(target)
                    } else {
                        if (waveformAmplitudes.size >= 24) {
                            waveformAmplitudes.removeAt(0)
                        }
                        val last = waveformAmplitudes.lastOrNull() ?: 0.08f
                        waveformAmplitudes.add((last * 0.85f).coerceAtLeast(0.08f))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatInputBar", "Failed to start recording: ${e.message}")
            Toast.makeText(context, "Could not start audio recording", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePauseAudioRecording() {
        if (recordMode == AudioRecordMode.TRANSCRIBE) {
            if (isRecordingPaused) speechManager?.resumeTranscriptionSession() else speechManager?.pauseTranscriptionSession()
            isRecordingPaused = !isRecordingPaused
            return
        }
        val rec = mediaRecorder ?: return
        try {
            if (isRecordingPaused) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    rec.resume()
                }
                isRecordingPaused = false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    rec.pause()
                }
                isRecordingPaused = true
            }
        } catch (e: Exception) {
            Log.w("ChatInputBar", "Error toggling pause: ${e.message}")
        }
    }

    var startLiveDictationAction by remember { mutableStateOf<() -> Unit>({}) }
    var startAutoTranscribeAction by remember { mutableStateOf<() -> Unit>({}) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            when (pendingActionAfterPermission) {
                "LIVE_DICTATION" -> startLiveDictationAction()
                "AUTO_TRANSCRIBE" -> startAutoTranscribeAction()
                else -> startAudioRecording()
            }
        } else {
            Toast.makeText(context, "Microphone permission required for speech recognition", Toast.LENGTH_SHORT).show()
        }
        pendingActionAfterPermission = null
    }

    fun triggerMicClick() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startAudioRecording()
        } else {
            pendingActionAfterPermission = "AUDIO_RECORDING"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun cancelAudioRecording() {
        if (recordMode == AudioRecordMode.TRANSCRIBE) {
            speechManager?.cancelTranscriptionSession()
        }
        val file = currentRecordingFile
        recordingJob?.cancel()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        finally {
            mediaRecorder = null
            isRecordingAudio = false
            isRecordingPaused = false
            currentRecordingFile = null
            recordingDurationSeconds = 0
            waveformAmplitudes.clear()
            recordMode = AudioRecordMode.VOICE_NOTE
        }
        file?.delete()
    }

    fun startLiveDictation() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            pendingActionAfterPermission = "LIVE_DICTATION"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val sm = speechManager
        if (sm == null) {
            Toast.makeText(context, "Speech service unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        baseTextBeforeDictation = textFieldState.text.toString()
        isLiveDictating = true
        isDictationPaused = false
        recordMode = AudioRecordMode.LIVE_DICTATION

        val selStart = textFieldState.selection.start.coerceIn(0, textFieldState.text.length)
        val selEnd = textFieldState.selection.end.coerceIn(0, textFieldState.text.length)
        val pre = textFieldState.text.substring(0, selStart)
        val post = textFieldState.text.substring(selEnd)

        sm.startTranscriptionSession(
            scope = coroutineScope,
            cascadeId = cascadeId,
            preCursorText = pre,
            postCursorText = post,
            onPartialText = { partial ->
                val sep = if (baseTextBeforeDictation.isNotBlank() && !baseTextBeforeDictation.endsWith(" ")) " " else ""
                val combined = baseTextBeforeDictation + sep + partial
                textFieldState.setTextAndPlaceCursorAtEnd(combined)
            },
            onFinalText = { finalText ->
                val sep = if (baseTextBeforeDictation.isNotBlank() && !baseTextBeforeDictation.endsWith(" ")) " " else ""
                val combined = baseTextBeforeDictation + sep + finalText
                textFieldState.setTextAndPlaceCursorAtEnd(combined)
                baseTextBeforeDictation = combined
            },
            onError = { err ->
                Toast.makeText(context, "Dictation: $err", Toast.LENGTH_SHORT).show()
                isLiveDictating = false
                isDictationPaused = false
                recordMode = AudioRecordMode.VOICE_NOTE
            }
        )
    }
    startLiveDictationAction = { startLiveDictation() }

    fun stopLiveDictation() {
        speechManager?.stopTranscriptionSession { finalText ->
            if (finalText.isNotBlank()) {
                val sep = if (baseTextBeforeDictation.isNotBlank() && !baseTextBeforeDictation.endsWith(" ")) " " else ""
                val combined = baseTextBeforeDictation + sep + finalText
                textFieldState.setTextAndPlaceCursorAtEnd(combined)
            }
        }
        isLiveDictating = false
        isDictationPaused = false
        recordMode = AudioRecordMode.VOICE_NOTE
    }

    fun startAutoTranscribe() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            pendingActionAfterPermission = "AUTO_TRANSCRIBE"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val sm = speechManager
        if (sm == null) {
            Toast.makeText(context, "Speech service unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        baseTextBeforeDictation = textFieldState.text.toString()
        recordMode = AudioRecordMode.TRANSCRIBE
        isRecordingAudio = true
        isRecordingPaused = false
        recordingDurationSeconds = 0
        waveformAmplitudes.clear()
        repeat(24) { waveformAmplitudes.add(0.08f) }

        recordingJob = coroutineScope.launch {
            while (isRecordingAudio && isActive) {
                delay(1000)
                if (!isRecordingPaused) {
                    recordingDurationSeconds++
                }
            }
        }

        val selStart = textFieldState.selection.start.coerceIn(0, textFieldState.text.length)
        val selEnd = textFieldState.selection.end.coerceIn(0, textFieldState.text.length)
        val pre = textFieldState.text.substring(0, selStart)
        val post = textFieldState.text.substring(selEnd)

        sm.startTranscriptionSession(
            scope = coroutineScope,
            cascadeId = cascadeId,
            preCursorText = pre,
            postCursorText = post,
            onPartialText = { /* In auto transcribe mode, we finalize upon tapping stop */ },
            onFinalText = { finalText ->
                val sep = if (baseTextBeforeDictation.isNotBlank() && !baseTextBeforeDictation.endsWith(" ")) " " else ""
                val combined = baseTextBeforeDictation + sep + finalText
                textFieldState.setTextAndPlaceCursorAtEnd(combined)
            },
            onError = { err ->
                Toast.makeText(context, "Transcribe: $err", Toast.LENGTH_SHORT).show()
                cancelAudioRecording()
            }
        )
    }
    startAutoTranscribeAction = { startAutoTranscribe() }

    fun stopAudioRecording(andSend: Boolean) {
        val currentMode = recordMode
        val dur = recordingDurationSeconds
        recordingJob?.cancel()

        if (currentMode == AudioRecordMode.TRANSCRIBE) {
            speechManager?.stopTranscriptionSession { finalText ->
                if (finalText.isNotBlank()) {
                    val cur = baseTextBeforeDictation
                    val sep = if (cur.isNotBlank() && !cur.endsWith(" ")) " " else ""
                    val combined = cur + sep + finalText
                    textFieldState.setTextAndPlaceCursorAtEnd(combined)
                    if (andSend) {
                        if (!isAuth) {
                            Toast.makeText(context, "Please sign in first to send messages.", Toast.LENGTH_SHORT).show()
                        } else {
                            onSendMessage(combined.trim())
                            textFieldState.clearText()
                        }
                    }
                } else {
                    Toast.makeText(context, "No speech recognized", Toast.LENGTH_SHORT).show()
                }
            }
            isRecordingAudio = false
            isRecordingPaused = false
            recordingDurationSeconds = 0
            waveformAmplitudes.clear()
            recordMode = AudioRecordMode.VOICE_NOTE
            return
        }

        val file = currentRecordingFile
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w("ChatInputBar", "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
            isRecordingAudio = false
            isRecordingPaused = false
            currentRecordingFile = null
            recordingDurationSeconds = 0
            waveformAmplitudes.clear()
            recordMode = AudioRecordMode.VOICE_NOTE
        }

        if (file != null && file.exists() && file.length() > 0) {
            val att = ChatAttachment(
                id = UUID.randomUUID().toString(),
                name = "Voice Note (${formatAudioDuration(dur)})",
                path = file.absolutePath,
                isAudio = true,
                durationSeconds = dur,
                size = file.length(),
                mimeType = "audio/mp4"
            )

            if (andSend) {
                if (!isAuth) {
                    Toast.makeText(context, "Please sign in first to send messages.", Toast.LENGTH_SHORT).show()
                    onAddAttachment(att)
                } else {
                    onAddAttachment(att)
                    val trimmed = textFieldState.text.toString().trim()
                    onSendMessage(trimmed)
                    textFieldState.clearText()
                }
            } else {
                onAddAttachment(att)
            }
        }
    }

    // derived, so the bar redraws only when the box turns empty / non-empty, not on every key press
    val hasText by remember(textFieldState) { derivedStateOf { textFieldState.text.isNotBlank() } }
    val canSend = hasText || attachments.isNotEmpty()
    val familyColor = if (selectedModel.family == ModelFamily.CLAUDE) ClaudeTerracotta else GeminiBlue
    val fileLinkHandler = LocalFileLinkHandler.current
    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    if (previewImageUrl != null) {
        FullScreenImageDialog(
            imageUrl = previewImageUrl!!,
            title = "Attachment Preview",
            onDismiss = { previewImageUrl = null }
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            QueuedMessagesCard(
                messages = queuedMessages,
                onSendNow = onSendQueuedNow,
                onDelete = onDeleteQueued,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Claude-style unified rounded input card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = com.example.gemini.theme.AppColors.card,
                border = BorderStroke(
                    1.dp,
                    if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            ) {
                if (isRecordingAudio) {
                    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "recAlpha"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pulsing red recording dot (steady amber if paused)
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRecordingPaused) com.example.gemini.theme.AppColors.warning
                                    else com.example.gemini.theme.AppColors.danger.copy(alpha = pulseAlpha)
                                )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Duration timer
                        Text(
                            text = formatAudioDuration(recordingDurationSeconds),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isRecordingPaused) com.example.gemini.theme.AppColors.warning else MaterialTheme.colorScheme.onSurface
                        )

                        if (recordMode == AudioRecordMode.TRANSCRIBE) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = ClaudeTerracotta
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Transcribe",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ClaudeTerracotta
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Real-time live audio waveform visualizer based on speaking volume
                        LiveAudioWaveform(
                            amplitudes = waveformAmplitudes,
                            isPaused = isRecordingPaused,
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .padding(horizontal = 4.dp),
                            barColor = if (isRecordingPaused) com.example.gemini.theme.AppColors.warning else com.example.gemini.theme.AppColors.danger
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // 1. Pause / Resume button
                        Surface(
                            onClick = { togglePauseAudioRecording() },
                            shape = CircleShape,
                            color = if (isRecordingPaused) com.example.gemini.theme.AppColors.warning.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, if (isRecordingPaused) com.example.gemini.theme.AppColors.warning else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isRecordingPaused) "Resume" else "Pause",
                                    tint = if (isRecordingPaused) com.example.gemini.theme.AppColors.warning else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 2. Cancel / Discard
                        IconButton(
                            onClick = { cancelAudioRecording() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Cancel recording",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 3. Stop & Attach
                        Surface(
                            onClick = { stopAudioRecording(andSend = false) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop & Attach",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 4. Send directly
                        Surface(
                            onClick = { stopAudioRecording(andSend = true) },
                            shape = CircleShape,
                            color = ClaudeTerracotta,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send voice note",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        // Live Dictation Listening Banner with Pause / Resume and Stop
                        if (isLiveDictating) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isDictationPaused) com.example.gemini.theme.AppColors.warning.copy(alpha = 0.12f) else GeminiBlue.copy(alpha = 0.12f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isDictationPaused) com.example.gemini.theme.AppColors.warning.copy(alpha = 0.4f) else GeminiBlue.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isDictationPaused) com.example.gemini.theme.AppColors.warning else com.example.gemini.theme.AppColors.danger)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isDictationPaused) "Dictation paused (tap resume to speak)" else "Listening... Speak now",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Pause / Resume button
                                    Surface(
                                        onClick = {
                                            if (isDictationPaused) {
                                                speechManager?.resumeTranscriptionSession()
                                                isDictationPaused = false
                                            } else {
                                                speechManager?.pauseTranscriptionSession()
                                                isDictationPaused = true
                                            }
                                        },
                                        shape = CircleShape,
                                        color = if (isDictationPaused) com.example.gemini.theme.AppColors.warning.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isDictationPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                contentDescription = if (isDictationPaused) "Resume" else "Pause",
                                                tint = if (isDictationPaused) com.example.gemini.theme.AppColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Stop button
                                    Surface(
                                        onClick = { stopLiveDictation() },
                                        shape = CircleShape,
                                        color = com.example.gemini.theme.AppColors.danger.copy(alpha = 0.12f),
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Stop,
                                                contentDescription = "Stop",
                                                tint = com.example.gemini.theme.AppColors.danger,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Audio Transcribing Banner
                        if (isTranscribingAudio) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ClaudeTerracotta.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = ClaudeTerracotta
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Transcribing audio via AGY Hub...",
                                        fontSize = 12.sp,
                                        color = ClaudeTerracotta,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // 1. Text Field Area on Top
                        TextField(
                        state = textFieldState,
                        placeholder = {
                            Text(
                                text = "Reply to ${selectedModel.displayName.split(" ").firstOrNull() ?: "Gemini"}...",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = ClaudeTerracotta
                        ),
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        ),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                    )

                    // 2. Attachments Preview (if any)
                    if (attachments.isNotEmpty() || isUploadingAttachment) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isUploadingAttachment) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.background,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = ClaudeTerracotta
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Uploading attachment...",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            for (att in attachments) {
                                val isImg = att.isImage || att.name.endsWith(".jpg", true) || att.name.endsWith(".png", true) ||
                                        att.name.endsWith(".jpeg", true) || att.name.endsWith(".webp", true) ||
                                        att.name.endsWith(".gif", true) || att.mimeType?.startsWith("image/") == true

                                val imgSource = remember(att) {
                                    when {
                                        !att.localUri.isNullOrBlank() -> att.localUri
                                        !att.url.isNullOrBlank() -> att.url
                                        att.path.isNotBlank() -> if (att.path.startsWith("file://") || att.path.startsWith("http")) att.path else "file://${att.path}"
                                        else -> null
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.background,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Clickable preview + name container
                                        Row(
                                            modifier = Modifier
                                                .clickable {
                                                    val ext = (att.name.ifBlank { att.path }).substringAfterLast('.', "").lowercase()
                                                    val isText = att.mimeType?.startsWith("text/") == true ||
                                                            att.mimeType?.contains("json") == true ||
                                                            att.mimeType?.contains("javascript") == true ||
                                                            att.mimeType?.contains("xml") == true ||
                                                            att.mimeType?.contains("yaml") == true ||
                                                            att.mimeType?.contains("toml") == true ||
                                                            ext in listOf("txt", "md", "markdown", "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "html", "css", "json", "xml", "yaml", "yml", "toml", "sh", "bash", "c", "cpp", "h", "hpp", "rs", "go", "sql", "gradle", "properties", "conf", "ini", "log", "env")
                                                    if (isImg && imgSource != null) {
                                                        previewImageUrl = imgSource
                                                    } else if (isText) {
                                                        val targetPath = when {
                                                            att.path.isNotBlank() -> att.path
                                                            !att.localUri.isNullOrBlank() -> att.localUri
                                                            else -> att.name
                                                        }
                                                        fileLinkHandler.onOpenFile(targetPath)
                                                    } else {
                                                        try {
                                                            val uri = when {
                                                                !att.url.isNullOrBlank() && (att.url.startsWith("content://") || att.url.startsWith("file://")) -> Uri.parse(att.url)
                                                                att.path.isNotBlank() && File(att.path).exists() -> {
                                                                    val file = File(att.path)
                                                                    androidx.core.content.FileProvider.getUriForFile(
                                                                        context,
                                                                        "${context.packageName}.fileprovider",
                                                                        file
                                                                    )
                                                                }
                                                                !att.url.isNullOrBlank() -> Uri.parse(att.url)
                                                                else -> null
                                                            }
                                                            if (uri != null) {
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    setDataAndType(uri, att.mimeType ?: "*/*")
                                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Open ${att.name}"))
                                                            } else {
                                                                Toast.makeText(context, att.name, Toast.LENGTH_SHORT).show()
                                                            }
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                                .padding(start = 5.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isImg && imgSource != null) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                ) {
                                                    AsyncImage(
                                                        model = imgSource,
                                                        contentDescription = att.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            } else if (att.isAudio) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Mic,
                                                    contentDescription = null,
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(20.dp).padding(start = 4.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(20.dp).padding(start = 4.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = att.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 130.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(2.dp))
                                        IconButton(
                                            onClick = { onRemoveAttachment(att.id) },
                                            modifier = Modifier.size(24.dp).padding(end = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Bottom Action Row: [+] [Model Chip] ... [Voice / Send / Stop Button]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 2.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // [+] Attachment Button
                        Surface(
                            onClick = onAttachClick,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = "Add attachments",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        // Middle Area: Model Selector Pill (takes up to 100% of the available space between [+] and right actions)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Surface(
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    onOpenModelSelector()
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.background,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = familyColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = selectedModel.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    val pct = quota?.percentage
                                    if (pct != null) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(badgeColor.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "$pct%",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Switch Model",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Dedicated Speech-to-Text Live Dictation Button (AGY Hub)
                        if (voiceEnabled && !isStreaming && !isRecordingAudio) {
                            Surface(
                                onClick = {
                                    if (isLiveDictating) {
                                        stopLiveDictation()
                                    } else {
                                        startLiveDictation()
                                    }
                                },
                                shape = CircleShape,
                                color = if (isLiveDictating) GeminiBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.background,
                                border = BorderStroke(
                                    1.dp,
                                    if (isLiveDictating) GeminiBlue.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = if (isLiveDictating) "Stop Dictation" else "Live Dictation",
                                        tint = if (isLiveDictating) GeminiBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Action Button (Send / Stop / Voice Recorder). While the agent works, a typed message is
                        // sent (it is queued, or injected by Claude); Stop shows when the box is empty.
                        if (isStreaming && !canSend) {
                            Surface(
                                onClick = onStopStreaming,
                                shape = CircleShape,
                                color = ClaudeTerracotta,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else if (canSend) {
                            val sendBtnColor = if (!isAuth) com.example.gemini.theme.AppColors.textSecondary else if (!isOnline) com.example.gemini.theme.AppColors.textSecondary else if (isBackgroundActive) com.example.gemini.theme.AppColors.warning else ClaudeTerracotta
                            Surface(
                                onClick = {
                                    val trimmed = textFieldState.text.toString().trim()
                                        if (!isAuth) {
                                            Toast.makeText(context, "Please sign in first to send messages.", Toast.LENGTH_SHORT).show()
                                            return@Surface
                                        }
                                        if (!isOnline) {
                                            Toast.makeText(context, offlineMessage, Toast.LENGTH_SHORT).show()
                                            return@Surface
                                        }
                                        if (isLiveDictating) {
                                            stopLiveDictation()
                                        }
                                        onSendMessage(trimmed)
                                        textFieldState.clearText()
                                },
                                shape = CircleShape,
                                color = sendBtnColor,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = if (isAuth && isOnline) (if (isBackgroundActive) com.example.gemini.theme.AppColors.onBackground else Color.White) else com.example.gemini.theme.AppColors.textSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        } else if (!voiceEnabled) {
                            // no voice input for this agent: an inactive Send until something is typed
                            Surface(
                                onClick = {},
                                enabled = false,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = com.example.gemini.theme.AppColors.textSecondary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        } else {
                            // Claude-style Microphone Voice Recorder Button
                            val voiceBgColor = com.example.gemini.theme.AppColors.onBackground
                            val voiceIconColor = com.example.gemini.theme.AppColors.background

                            Surface(
                                onClick = {
                                    if (voiceNotesAsText) {
                                        startAutoTranscribe()
                                    } else {
                                        recordMode = AudioRecordMode.VOICE_NOTE
                                        triggerMicClick()
                                    }
                                },
                                shape = CircleShape,
                                color = voiceBgColor,
                                shadowElevation = 1.dp,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = if (voiceNotesAsText) "Record and transcribe" else "Record voice note",
                                        tint = voiceIconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

enum class AudioRecordMode { VOICE_NOTE, TRANSCRIBE, LIVE_DICTATION }

