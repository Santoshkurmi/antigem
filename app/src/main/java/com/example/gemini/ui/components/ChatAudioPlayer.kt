package com.example.gemini.ui.components

import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream

fun formatAudioDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", mins, secs)
}

fun formatAudioMs(ms: Int): String {
    return formatAudioDuration((ms / 1000).coerceAtLeast(0))
}

@Composable
fun ChatAudioPlayer(
    attachment: ChatAttachment,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf((attachment.durationSeconds * 1000).coerceAtLeast(1000)) }
    var isPrepared by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    val mediaPlayer = remember {
        MediaPlayer().apply {
            setOnCompletionListener {
                isPlaying = false
                currentPositionMs = 0
            }
            setOnErrorListener { _, what, extra ->
                Log.e("ChatAudioPlayer", "MediaPlayer error: what=$what, extra=$extra")
                hasError = true
                isPlaying = false
                true
            }
        }
    }

    DisposableEffect(attachment.id) {
        onDispose {
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
                mediaPlayer.release()
            } catch (e: Exception) {
                Log.w("ChatAudioPlayer", "Error releasing MediaPlayer: ${e.message}")
            }
        }
    }

    fun preparePlayer(): Boolean {
        if (isPrepared) return true
        try {
            mediaPlayer.reset()

            val fileToPlay: File? = when {
                attachment.path.isNotBlank() && File(attachment.path).exists() -> File(attachment.path)
                !attachment.localUri.isNullOrBlank() && File(Uri.parse(attachment.localUri).path ?: "").exists() -> {
                    File(Uri.parse(attachment.localUri).path ?: "")
                }
                !attachment.base64.isNullOrBlank() -> {
                    val tempFile = File(context.cacheDir, "audio_note_${attachment.id.take(10)}.m4a")
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
                        FileOutputStream(tempFile).use { it.write(bytes) }
                    }
                    tempFile
                }
                else -> null
            }

            if (fileToPlay != null && fileToPlay.exists()) {
                mediaPlayer.setDataSource(fileToPlay.absolutePath)
                mediaPlayer.prepare()
                isPrepared = true
                val d = mediaPlayer.duration
                if (d > 0) durationMs = d
                return true
            } else if (!attachment.url.isNullOrBlank()) {
                mediaPlayer.setDataSource(attachment.url)
                mediaPlayer.prepare()
                isPrepared = true
                val d = mediaPlayer.duration
                if (d > 0) durationMs = d
                return true
            }
        } catch (e: Exception) {
            Log.e("ChatAudioPlayer", "Failed to prepare audio player: ${e.message}")
            hasError = true
        }
        return false
    }

    // Playback ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            try {
                if (mediaPlayer.isPlaying) {
                    currentPositionMs = mediaPlayer.currentPosition
                }
            } catch (_: Exception) {
                break
            }
            delay(80)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause Circle Button
            Surface(
                onClick = {
                    if (hasError) return@Surface
                    if (isPlaying) {
                        try {
                            mediaPlayer.pause()
                            isPlaying = false
                        } catch (e: Exception) {
                            Log.w("ChatAudioPlayer", "Pause failed: ${e.message}")
                        }
                    } else {
                        if (preparePlayer()) {
                            try {
                                mediaPlayer.start()
                                isPlaying = true
                            } catch (e: Exception) {
                                Log.w("ChatAudioPlayer", "Start failed: ${e.message}")
                            }
                        }
                    }
                },
                shape = CircleShape,
                color = ClaudeTerracotta,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "playPauseIcon"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Progress & Track Meta
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (attachment.name.isNotBlank()) attachment.name else "Voice Note",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "${formatAudioMs(currentPositionMs)} / ${formatAudioMs(durationMs)}",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Progress Indicator
                val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = ClaudeTerracotta,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Audio Waveform Decorative Icon
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = if (isPlaying) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
