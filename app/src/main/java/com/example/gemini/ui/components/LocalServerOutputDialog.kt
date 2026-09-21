package com.example.gemini.ui.components

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.local.LocalServerManager
import com.example.gemini.data.local.LocalServerStatus
import com.example.gemini.theme.*
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

@Composable
fun LocalServerOutputDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val status by LocalServerManager.status.collectAsState()
    val serverSession by LocalServerManager.serverSession.collectAsState()
    val isDark = isAppInDarkTheme() || isSystemInDarkTheme()

    var terminalTextSize by remember { mutableStateOf(24) }
    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }

    // Live terminal redraw hook: listen to pty text updates and trigger onScreenUpdated/invalidate
    DisposableEffect(serverSession, currentTerminalView) {
        val tv = currentTerminalView
        val session = serverSession
        if (tv != null && session != null) {
            val listener: () -> Unit = {
                tv.post {
                    tv.onScreenUpdated()
                    tv.invalidate()
                }
            }
            session.addTextChangedListener(listener)
            // Immediately draw current buffer state
            tv.post {
                tv.onScreenUpdated()
                tv.invalidate()
            }
            onDispose {
                session.removeTextChangedListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    val dialogBg = if (isDark) Color(0xFF141414) else Color(0xFF1E1E1E)
    val dialogBorder = BorderStroke(1.dp, Color(0xFF2C2C2C))
    val consoleBg = Color(0xFF000000)
    val textPrimary = Color(0xFFEEEEEE)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = dialogBg,
            border = dialogBorder,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Static Status Dot (no animation/blinking)
                        val dotColor = when (status) {
                            is LocalServerStatus.Running -> Color(0xFF22C55E)
                            is LocalServerStatus.Starting -> Color(0xFFF59E0B)
                            is LocalServerStatus.Stopped -> Color(0xFF9CA3AF)
                            is LocalServerStatus.Error -> Color(0xFFEF4444)
                            is LocalServerStatus.Idle -> Color(0xFF9CA3AF)
                        }

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )

                        Column {
                            Text(
                                text = "Local Server Logs",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )

                            val statusText = when (val s = status) {
                                is LocalServerStatus.Running -> "Running"
                                is LocalServerStatus.Starting -> "Starting..."
                                is LocalServerStatus.Stopped -> "Stopped"
                                is LocalServerStatus.Error -> "Error: ${s.message.take(25)}"
                                is LocalServerStatus.Idle -> "Idle"
                            }

                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = dotColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Restart / Start Button
                        IconButton(
                            onClick = { LocalServerManager.restartServer(context) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (status is LocalServerStatus.Running) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                contentDescription = "Restart Server",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Stop Button (Ctrl+C / terminate child processes)
                        if (status is LocalServerStatus.Running || status is LocalServerStatus.Starting) {
                            IconButton(
                                onClick = { LocalServerManager.stopServer() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Server",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Clear Logs / Delete History Button
                        IconButton(
                            onClick = { LocalServerManager.clearLogs() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Close Dialog Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Terminal Console Area (Exact matching style as the terminal one)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    color = consoleBg,
                    border = BorderStroke(1.dp, Color(0xFF222222))
                ) {
                    val currentSession = serverSession
                    if (currentSession != null) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx, null).apply {
                                    setTextSize(terminalTextSize)
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    setTerminalViewClient(object : TerminalViewClient {
                                        override fun onScale(scale: Float): Float {
                                            if (scale < 0.9f || scale > 1.1f) {
                                                val doIncrease = scale > 1.0f
                                                val newSize = if (doIncrease) terminalTextSize + 1 else terminalTextSize - 1
                                                if (newSize in 14..60) {
                                                    terminalTextSize = newSize
                                                    this@apply.setTextSize(terminalTextSize)
                                                }
                                                return 1.0f
                                            }
                                            return scale
                                        }

                                        override fun onSingleTapUp(e: MotionEvent) {
                                            // No soft keyboard popup on tap
                                        }

                                        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                        override fun shouldEnforceCharBasedInput(): Boolean = true
                                        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                        override fun isTerminalViewSelected(): Boolean = true
                                        override fun copyModeChanged(copyMode: Boolean) {}
                                        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
                                        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                                        override fun onLongPress(event: MotionEvent): Boolean = false
                                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
                                        override fun readControlKey(): Boolean = false
                                        override fun readAltKey(): Boolean = false
                                        override fun readShiftKey(): Boolean = false
                                        override fun readFnKey(): Boolean = false
                                        override fun onEmulatorSet() {
                                            post {
                                                onScreenUpdated()
                                                invalidate()
                                            }
                                        }
                                        override fun logError(tag: String, message: String) {}
                                        override fun logWarn(tag: String, message: String) {}
                                        override fun logInfo(tag: String, message: String) {}
                                        override fun logDebug(tag: String, message: String) {}
                                        override fun logVerbose(tag: String, message: String) {}
                                        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                        override fun logStackTrace(tag: String, e: Exception) {}
                                    })
                                    setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                        currentSession.updateSize(cols, rows, widthPx, heightPx)
                                    }
                                    attachSession(currentSession.terminalSession)
                                    currentTerminalView = this
                                }
                            },
                            update = { tv ->
                                currentTerminalView = tv
                                tv.setTextSize(terminalTextSize)
                                tv.setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                    currentSession.updateSize(cols, rows, widthPx, heightPx)
                                }
                                if (tv.currentSession != currentSession.terminalSession) {
                                    tv.attachSession(currentSession.terminalSession)
                                }
                                tv.post {
                                    tv.onScreenUpdated()
                                    tv.invalidate()
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .background(consoleBg)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(consoleBg)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No server process running.\nTap 'Restart' above to run ./start",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF71717A),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
