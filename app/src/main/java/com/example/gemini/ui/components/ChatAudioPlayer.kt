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
import kotlinx.coroutines.launch
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@RequiresApi(Build.VERSION_CODES.M)
private class InMemoryMediaDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val remaining = data.size - position
        val bytesToRead = minOf(size.toLong(), remaining).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, bytesToRead)
        return bytesToRead
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

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
    val coroutineScope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
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
                isBuffering = false
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

    fun playOrPauseAudio() {
        if (hasError) return
        if (isPlaying) {
            try {
                mediaPlayer.pause()
                isPlaying = false
            } catch (e: Exception) {
                Log.w("ChatAudioPlayer", "Pause failed: ${e.message}")
            }
            return
        }

        if (isPrepared) {
            try {
                mediaPlayer.start()
                isPlaying = true
            } catch (e: Exception) {
                Log.w("ChatAudioPlayer", "Start failed: ${e.message}")
            }
            return
        }

        coroutineScope.launch {
            isBuffering = true
            hasError = false
            try {
                withContext(Dispatchers.IO) {
                    mediaPlayer.reset()

                    // 1. Direct local file if already available (e.g. freshly recorded)
                    val directLocalFile = when {
                        attachment.path.isNotBlank() && File(attachment.path).exists() -> File(attachment.path)
                        !attachment.localUri.isNullOrBlank() && File(Uri.parse(attachment.localUri).path ?: "").exists() -> {
                            File(Uri.parse(attachment.localUri).path ?: "")
                        }
                        else -> null
                    }

                    if (directLocalFile != null && directLocalFile.exists()) {
                        mediaPlayer.setDataSource(directLocalFile.absolutePath)
                        mediaPlayer.prepare()
                    } else if (!attachment.base64.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // 2. In-memory playback for base64 without writing files to disk
                        val bytes = Base64.decode(attachment.base64, Base64.DEFAULT)
                        if (bytes.isNotEmpty()) {
                            mediaPlayer.setDataSource(InMemoryMediaDataSource(bytes))
                            mediaPlayer.prepare()
                        } else {
                            throw IllegalStateException("Empty base64 audio data")
                        }
                    } else {
                        // 3. Direct HTTP streaming from host without caching to local disk
                        val streamUrl = when {
                            !attachment.url.isNullOrBlank() && (attachment.url.startsWith("http://") || attachment.url.startsWith("https://")) -> {
                                attachment.url
                            }
                            attachment.path.isNotBlank() && (attachment.path.startsWith("http://") || attachment.path.startsWith("https://")) -> {
                                attachment.path
                            }
                            attachment.path.isNotBlank() -> {
                                val bridgeBase = com.example.gemini.data.remote.HubMediaResolver.activeBridgeUrl.removeSuffix("/")
                                "$bridgeBase/api/file/read?path=" + java.net.URLEncoder.encode(attachment.path, "UTF-8")
                            }
                            else -> null
                        }

                        if (!streamUrl.isNullOrBlank()) {
                            mediaPlayer.setDataSource(streamUrl)
                            mediaPlayer.prepare()
                        } else {
                            throw IllegalStateException("No audio source available for ${attachment.name}")
                        }
                    }
                }

                isPrepared = true
                isBuffering = false
                val d = mediaPlayer.duration
                if (d > 0) durationMs = d
                mediaPlayer.start()
                isPlaying = true
            } catch (e: Exception) {
                Log.e("ChatAudioPlayer", "Failed to prepare/play audio player: ${e.message}", e)
                isBuffering = false
                hasError = true
                isPlaying = false
            }
        }
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
                onClick = { playOrPauseAudio() },
                shape = CircleShape,
                color = ClaudeTerracotta,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
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
