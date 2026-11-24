package ai.antigem.ui.components

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
import ai.antigem.data.remote.core.AntiGemLiveDiagnostics
import kotlin.math.roundToInt
import ai.antigem.i18n.L

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
                color = ai.antigem.theme.AppColors.editorPanel.copy(alpha = 0.93f),
                border = BorderStroke(1.dp, if (snapshot.isLagging || snapshot.agyQueued > 0) ai.antigem.theme.AppColors.danger else ai.antigem.theme.AppColors.success.copy(alpha = 0.27f)),
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
                                .background(if (snapshot.isLagging || snapshot.agyQueued > 0) ai.antigem.theme.AppColors.danger else ai.antigem.theme.AppColors.success)
                        )

                        Text(
                            text = "🧵 ${snapshot.activeJvmThreads}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ai.antigem.theme.AppColors.editorText,
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = "⚡${snapshot.agyRunning + snapshot.ideRunning}r",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (snapshot.agyRunning > 0) ai.antigem.theme.AppColors.info else ai.antigem.theme.AppColors.editorMuted,
                            fontFamily = FontFamily.Monospace
                        )

                        if (snapshot.agyQueued > 0 || snapshot.ideQueued > 0) {
                            Text(
                                text = "⏳${snapshot.agyQueued + snapshot.ideQueued}q",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ai.antigem.theme.AppColors.danger,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = "🔌${snapshot.agyConns + snapshot.ideConns}c",
                            fontSize = 11.sp,
                            color = ai.antigem.theme.AppColors.editorMuted,
                            fontFamily = FontFamily.Monospace
                        )

                        Text(
                            text = if (snapshot.ioLagMs >= 0) "${snapshot.ioLagMs}ms" else L.components.floatingDiagnosticsOverlay.err,
                            fontSize = 10.sp,
                            color = if (snapshot.isLagging) ai.antigem.theme.AppColors.danger else ai.antigem.theme.AppColors.success,
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
                            HorizontalDivider(color = ai.antigem.theme.AppColors.editorText.copy(alpha = 0.2f), thickness = 0.5.dp)

                            // Groups
                            Text(
                                text = L.components.floatingDiagnosticsOverlay.threadGroupsThreadgroupsummary(snapshot.threadGroupSummary),
                                fontSize = 10.sp,
                                color = ai.antigem.theme.AppColors.editorMuted,
                                fontFamily = FontFamily.Monospace
                            )

                            // AgyGrpc Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = L.components.floatingDiagnosticsOverlay.agygrpcHub,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ai.antigem.theme.AppColors.editorInfoText,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = L.components.floatingDiagnosticsOverlay.runAgyrunningQAgyqueuedPoolAgyconns(snapshot.agyRunning, snapshot.agyQueued, snapshot.agyConns, snapshot.agyIdleConns),
                                    fontSize = 10.sp,
                                    color = ai.antigem.theme.AppColors.editorText,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // IdeHttp Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = L.components.floatingDiagnosticsOverlay.ideBridge,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ai.antigem.theme.AppColors.success,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = L.components.floatingDiagnosticsOverlay.runIderunningQIdequeuedPoolIdeconns(snapshot.ideRunning, snapshot.ideQueued, snapshot.ideConns, snapshot.ideIdleConns),
                                    fontSize = 10.sp,
                                    color = ai.antigem.theme.AppColors.editorText,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Blocked Threads breakdown
                            if (snapshot.topBlockedThreads.isNotEmpty()) {
                                Text(
                                    text = L.components.floatingDiagnosticsOverlay.blockedSocketReads,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ai.antigem.theme.AppColors.warning,
                                    fontFamily = FontFamily.Monospace
                                )
                                snapshot.topBlockedThreads.forEach { trace ->
                                    Text(
                                        text = "• $trace",
                                        fontSize = 9.sp,
                                        color = ai.antigem.theme.AppColors.warning,
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
                                    Text(L.components.floatingDiagnosticsOverlay.hideHud, fontSize = 10.sp, color = ai.antigem.theme.AppColors.danger)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
