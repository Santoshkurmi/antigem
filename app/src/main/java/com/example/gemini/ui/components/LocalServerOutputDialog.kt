package com.example.gemini.ui.components

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
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
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.*
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

@Composable
fun LocalServerOutputDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val status by LocalServerManager.status.collectAsState()
    val serverSession by LocalServerManager.serverSession.collectAsState()
    val isDark = isAppInDarkTheme() || isSystemInDarkTheme()

    val authPreferences = remember { AuthPreferences(context) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val initialFontSizeSp = remember { authPreferences.getTerminalFontSizeSync() }
    var terminalTextSize by remember {
        mutableIntStateOf((initialFontSizeSp * density.density).roundToInt().coerceIn(16, 60))
    }
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

    val dialogBg = com.example.gemini.theme.AppColors.editorBg
    val dialogBorder = BorderStroke(1.dp, com.example.gemini.theme.AppColors.editorBorder)
    val consoleBg = com.example.gemini.theme.AppColors.terminalBg
    val textPrimary = com.example.gemini.theme.AppColors.editorText

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
                            is LocalServerStatus.Running -> com.example.gemini.theme.AppColors.success
                            is LocalServerStatus.Starting -> com.example.gemini.theme.AppColors.warning
                            is LocalServerStatus.Stopping -> com.example.gemini.theme.AppColors.warning
                            is LocalServerStatus.Stopped -> com.example.gemini.theme.AppColors.editorMuted
                            is LocalServerStatus.Error -> com.example.gemini.theme.AppColors.danger
                            is LocalServerStatus.Idle -> com.example.gemini.theme.AppColors.editorMuted
                        }

                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )

                        Column {
                            Text(
                                text = if (AuthPreferences.currentRemoteMode) "Server Logs (SSH)" else "Local Server Logs",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )

                            val statusText = when (val s = status) {
                                is LocalServerStatus.Running -> "Running"
                                is LocalServerStatus.Starting -> "Starting..."
                                is LocalServerStatus.Stopping -> "Stopping..."
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
                        // Copy Button: copies the whole log shown below
                        IconButton(
                            onClick = {
                                val session = serverSession
                                val text = if (session != null) {
                                    session.terminalSession.emulator?.screen?.getTranscriptText() ?: ""
                                } else {
                                    com.example.gemini.data.remote.RemoteServerManager.log.value
                                }
                                if (text.isBlank()) {
                                    AppToastHelper.showToast("The log is empty", ChatToastType.INFO)
                                } else {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Server log", text))
                                    AppToastHelper.showToast("Log copied", ChatToastType.SUCCESS)
                                }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy log",
                                tint = textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Start Button (only while the server is not running)
                        if (status !is LocalServerStatus.Running && status !is LocalServerStatus.Starting) {
                            IconButton(
                                onClick = { LocalServerManager.restartServer(context) },
                                enabled = status !is LocalServerStatus.Stopping,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start Server",
                                    tint = if (status is LocalServerStatus.Stopping) Color.Gray else ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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
                                    tint = com.example.gemini.theme.AppColors.danger,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                    border = BorderStroke(1.dp, com.example.gemini.theme.AppColors.editorBorder)
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
                    } else if (AuthPreferences.currentRemoteMode) {
                        // the bridge runs on the server: show its log, read over SSH
                        val remoteLog by com.example.gemini.data.remote.RemoteServerManager.log.collectAsState()
                        val sshState by com.example.gemini.data.remote.RemoteServerManager.sshState.collectAsState()
                        LaunchedEffect(Unit) {
                            while (true) {
                                com.example.gemini.data.remote.RemoteServerManager.refreshLog(context)
                                kotlinx.coroutines.delay(3000)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(consoleBg)
                                .padding(10.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState(), reverseScrolling = true)
                        ) {
                            Text(
                                text = remoteLog.ifBlank { "SSH: ${sshState.message}\n\nNo log yet. Tap 'Restart' above to start the bridge on the server." },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = com.example.gemini.theme.AppColors.editorText
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(consoleBg)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No bridge process running.\nTap 'Restart' above to run agy_ide_bridge",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = com.example.gemini.theme.AppColors.editorMuted,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
