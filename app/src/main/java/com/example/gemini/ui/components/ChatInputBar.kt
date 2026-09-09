package com.example.gemini.ui.components

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
 * Claude Android style unified input bar:
 * - Single rounded container enclosing text field, attachments, [+] button, model selector pill, and voice recorder / send button.
 * - Dynamic microphone icon with native MediaRecorder audio recording, stop & attach, or direct send.
 */
@Composable
fun ChatInputBar(
    selectedModel: AiModel,
    quota: ModelQuota?,
    thinkingPreference: ThinkingPreference,
    textFieldValue: TextFieldValue,
    onTextFieldValueChange: (TextFieldValue) -> Unit,
    onOpenModelSelector: () -> Unit,
    onOpenThinkingSelector: () -> Unit,
    isStreaming: Boolean,
    onSendMessage: (String) -> Unit,
    onStopStreaming: () -> Unit,
    attachments: List<ChatAttachment> = emptyList(),
    isUploadingAttachment: Boolean = false,
    onRemoveAttachment: (String) -> Unit = {},
    onAddAttachment: (ChatAttachment) -> Unit = {},
    onAttachClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    // Audio recording state
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentRecordingFile by remember { mutableStateOf<File?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {}
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
            recordingDurationSeconds = 0

            recordingJob = coroutineScope.launch {
                while (isRecordingAudio && isActive) {
                    delay(1000)
                    recordingDurationSeconds++
                }
            }
        } catch (e: Exception) {
            Log.e("ChatInputBar", "Failed to start recording: ${e.message}")
            Toast.makeText(context, "Could not start audio recording", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startAudioRecording()
        } else {
            Toast.makeText(context, "Microphone permission required for voice recording", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerMicClick() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startAudioRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopAudioRecording(andSend: Boolean) {
        val file = currentRecordingFile
        val dur = recordingDurationSeconds
        recordingJob?.cancel()

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w("ChatInputBar", "Error stopping recorder: ${e.message}")
        } finally {
            mediaRecorder = null
            isRecordingAudio = false
            currentRecordingFile = null
            recordingDurationSeconds = 0
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
                onAddAttachment(att)
                val trimmed = textFieldValue.text.trim()
                onSendMessage(trimmed)
                onTextFieldValueChange(TextFieldValue(""))
            } else {
                onAddAttachment(att)
            }
        }
    }

    fun cancelAudioRecording() {
        val file = currentRecordingFile
        recordingJob?.cancel()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        finally {
            mediaRecorder = null
            isRecordingAudio = false
            currentRecordingFile = null
            recordingDurationSeconds = 0
        }
        file?.delete()
    }

    val canSend = textFieldValue.text.trim().isNotEmpty() || attachments.isNotEmpty()
    val familyColor = if (selectedModel.family == ModelFamily.CLAUDE) ClaudeTerracotta else GeminiBlue

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // Claude-style unified rounded input card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = if (isDark) Color(0xFF21211E) else Color.White,
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
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pulsing red recording dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935).copy(alpha = pulseAlpha))
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "Recording... ${formatAudioDuration(recordingDurationSeconds)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        // Cancel / Discard
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

                        Spacer(modifier = Modifier.width(6.dp))

                        // Stop & Attach (lets user enter more text)
                        Surface(
                            onClick = { stopAudioRecording(andSend = false) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop & Attach",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Send directly
                        Surface(
                            onClick = { stopAudioRecording(andSend = true) },
                            shape = CircleShape,
                            color = ClaudeTerracotta,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send voice note",
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp)
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
                        // 1. Text Field Area on Top
                        TextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        placeholder = {
                            Text(
                                text = "Reply to ${selectedModel.displayName.split(" ").firstOrNull() ?: "Gemini"}...",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
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
                        maxLines = 6,
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
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.background,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (att.isImage) {
                                            val imgSource = att.url ?: att.localUri ?: "file://${att.path}"
                                            AsyncImage(
                                                model = imgSource,
                                                contentDescription = att.name,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else if (att.isAudio) {
                                            Icon(
                                                imageVector = Icons.Rounded.Mic,
                                                contentDescription = null,
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                                                contentDescription = null,
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = att.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onRemoveAttachment(att.id) },
                                            modifier = Modifier.size(20.dp)
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

                        Spacer(modifier = Modifier.width(8.dp))

                        // Model Selector Pill (Claude style)
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = familyColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = selectedModel.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 165.dp)
                                )

                                val pct = quota?.percentage
                                if (pct != null) {
                                    Spacer(modifier = Modifier.width(5.dp))
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

                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch Model",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Action Button (Send / Stop / Voice Recorder)
                        if (isStreaming) {
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
                            Surface(
                                onClick = {
                                    val trimmed = textFieldValue.text.trim()
                                    if (canSend) {
                                        onSendMessage(trimmed)
                                        onTextFieldValueChange(TextFieldValue(""))
                                    }
                                },
                                shape = CircleShape,
                                color = ClaudeTerracotta,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        } else {
                            // Claude-style Microphone Voice Recorder Button
                            val voiceBgColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1F1F1F)
                            val voiceIconColor = if (isDark) Color(0xFF1B1B1B) else Color.White

                            Surface(
                                onClick = { triggerMicClick() },
                                shape = CircleShape,
                                color = voiceBgColor,
                                shadowElevation = 1.dp,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Record voice note",
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
