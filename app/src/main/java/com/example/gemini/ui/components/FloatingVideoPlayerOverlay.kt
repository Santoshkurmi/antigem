package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.example.gemini.data.media.YouTubeMediaSessionManager
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.isAppInDarkTheme
import kotlin.math.roundToInt

/**
 * Floating in-app controller for active YouTube playback across Chat, IDE, Browser, and Terminal.
 * Provides instant Play/Pause toggle and Stop/Kill button to terminate background playback and free CPU & RAM.
 */
@Composable
fun FloatingVideoPlayerOverlay(
    modifier: Modifier = Modifier
) {
    val playback by YouTubeMediaSessionManager.playbackState.collectAsState()
    val activeState = playback ?: return

    var isExpanded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableStateOf(screenWidthPx - with(density) { 68.dp.toPx() }) }
    var offsetY by remember { mutableStateOf(screenHeightPx * 0.25f) }

    val isDark = isAppInDarkTheme()
    val bgColor = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color(0xFF35373C) else Color(0xFFE2E4E9)
    val contentColor = if (isDark) Color(0xFFE4E4E7) else Color(0xFF18181B)

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(120f)
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = offsetX.roundToInt().coerceIn(16, (screenWidthPx - with(density) { if (isExpanded) 290.dp.toPx() else 56.dp.toPx() }).toInt().coerceAtLeast(16)),
                        y = offsetY.roundToInt().coerceIn(40, (screenHeightPx - with(density) { 120.dp.toPx() }).toInt().coerceAtLeast(40))
                    )
                }
                .pointerInput(isExpanded) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            if (!isExpanded) {
                // Compact Floating Circle Button
                Surface(
                    onClick = { isExpanded = true },
                    shape = CircleShape,
                    color = bgColor,
                    border = BorderStroke(1.5.dp, Color(0xFFFF0000).copy(alpha = 0.85f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (!activeState.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = activeState.thumbnailUrl,
                                contentDescription = activeState.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                            )
                            // Translucent dark veil
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f))
                            )
                        }

                        // Central Play/Pause badge
                        Icon(
                            imageVector = if (activeState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (activeState.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )

                        // Small YouTube Red Dot Indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000))
                        )
                    }
                }
            } else {
                // Expanded Glassmorphic Control Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = bgColor.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, borderColor),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .width(280.dp)
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Mini Video Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 28.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!activeState.thumbnailUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = activeState.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFFFF0000),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Title and Time info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeState.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val posFormatted = formatMs(activeState.positionMs)
                                val durFormatted = formatMs(activeState.durationMs)
                                Text(
                                    text = "$posFormatted / $durFormatted",
                                    fontSize = 11.sp,
                                    color = contentColor.copy(alpha = 0.65f)
                                )
                            }

                            // Collapse Button
                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ExpandLess,
                                    contentDescription = "Collapse",
                                    tint = contentColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Controls Row: Play/Pause, Seek Back/Forward, Stop & Kill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Play / Pause Toggle
                                FilledIconButton(
                                    onClick = {
                                        if (activeState.isPlaying) {
                                            YouTubeMediaSessionManager.pause()
                                        } else {
                                            YouTubeMediaSessionManager.play()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (activeState.isPlaying) Color(0xFFFF0000) else ClaudeTerracotta
                                    ),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (activeState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (activeState.isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Seek -10s
                                OutlinedButton(
                                    onClick = {
                                        val newPos = (activeState.positionMs - 10000L).coerceAtLeast(0L)
                                        YouTubeMediaSessionManager.seekTo(newPos)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("-10s", fontSize = 11.sp)
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Seek +10s
                                OutlinedButton(
                                    onClick = {
                                        val newPos = (activeState.positionMs + 10000L).coerceAtMost(activeState.durationMs.coerceAtLeast(0L))
                                        YouTubeMediaSessionManager.seekTo(newPos)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("+10s", fontSize = 11.sp)
                                }
                            }

                            // Terminate / Kill Playback (Frees CPU & RAM)
                            IconButton(
                                onClick = {
                                    isExpanded = false
                                    YouTubeMediaSessionManager.release()
                                },
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = 0.12f))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Stop & Kill Player",
                                    tint = Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format("%d:%02d", minutes, seconds)
}
