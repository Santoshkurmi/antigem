package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.gemini.data.remote.core.AntiGemLiveDiagnostics
import kotlin.math.roundToInt

@Composable
fun FloatingDiagnosticsOverlay(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val snapshot by AntiGemLiveDiagnostics.snapshot.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(true) }

    if (!isVisible) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableStateOf(16f) }
    var offsetY by remember { mutableStateOf(screenHeightPx * 0.12f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(9999f)
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 200f)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 200f)
                    }
                }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE121316),
                border = BorderStroke(1.dp, if (snapshot.isLagging || snapshot.agyQueued > 0) Color(0xFFFF5252) else Color(0x444CAF50)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { isExpanded = !isExpanded }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Header Bar (Compact View)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Status Indicator Dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (snapshot.isLagging || snapshot.agyQueued > 0) Color(0xFFFF5252) else Color(0xFF4CAF50))
                        )

                        Text(
                            text = "🧵 ${snapshot.activeJvmThreads}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = "⚡${snapshot.agyRunning + snapshot.ideRunning}r",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (snapshot.agyRunning > 0) Color(0xFF81D4FA) else Color(0xFF9E9E9E),
                            fontFamily = FontFamily.Monospace
                        )

                        if (snapshot.agyQueued > 0 || snapshot.ideQueued > 0) {
                            Text(
                                text = "⏳${snapshot.agyQueued + snapshot.ideQueued}q",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF5252),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = "🔌${snapshot.agyConns + snapshot.ideConns}c",
                            fontSize = 11.sp,
                            color = Color(0xFFB0BEC5),
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = if (snapshot.ioLagMs >= 0) "${snapshot.ioLagMs}ms" else "ERR",
                            fontSize = 10.sp,
                            color = if (snapshot.isLagging) Color(0xFFFF5252) else Color(0xFF81C784),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Expanded Details Panel
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .widthIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HorizontalDivider(color = Color(0x33FFFFFF), thickness = 0.5.dp)

                            // Groups
                            Text(
                                text = "Thread Groups: ${snapshot.threadGroupSummary}",
                                fontSize = 10.sp,
                                color = Color(0xFFB0BEC5),
                                fontFamily = FontFamily.Monospace
                            )

                            // AgyGrpc Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "AgyGrpc Hub:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF90CAF9),
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Run=${snapshot.agyRunning} | Q=${snapshot.agyQueued} | Pool=${snapshot.agyConns} (${snapshot.agyIdleConns} idle)",
                                    fontSize = 10.sp,
                                    color = Color(0xFFE0E0E0),
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // IdeHttp Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "IDE Bridge:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFA5D6A7),
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Run=${snapshot.ideRunning} | Q=${snapshot.ideQueued} | Pool=${snapshot.ideConns} (${snapshot.ideIdleConns} idle)",
                                    fontSize = 10.sp,
                                    color = Color(0xFFE0E0E0),
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Blocked Threads breakdown
                            if (snapshot.topBlockedThreads.isNotEmpty()) {
                                Text(
                                    text = "Blocked Socket Reads:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB74D),
                                    fontFamily = FontFamily.Monospace
                                )
                                snapshot.topBlockedThreads.forEach { trace ->
                                    Text(
                                        text = "• $trace",
                                        fontSize = 9.sp,
                                        color = Color(0xFFFFCC80),
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { 
                                        isVisible = false 
                                        onDismiss?.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Hide HUD", fontSize = 10.sp, color = Color(0xFFEF5350))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
