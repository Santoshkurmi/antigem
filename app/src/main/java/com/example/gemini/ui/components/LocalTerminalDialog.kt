package com.example.gemini.ui.components

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.gemini.data.local.LocalPtySession
import com.example.gemini.data.local.LocalTerminalManager
import com.example.gemini.theme.*
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

@Composable
fun LocalTerminalDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LocalTerminalContent(onClose = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTerminalContent(
    onClose: () -> Unit
) {
    val context = LocalContext.current

    val sessions by LocalTerminalManager.sessions.collectAsState()
    val activeSessionId by LocalTerminalManager.activeSessionId.collectAsState()

    // Ensure at least one active primary session
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            LocalTerminalManager.getOrCreatePrimarySession(context)
        }
    }

    // Auto close terminal dialog when all sessions are closed
    LaunchedEffect(sessions) {
        if (sessions.isEmpty()) {
            onClose()
        }
    }

    val activeSession = sessions.find { it.id == activeSessionId }
        ?: sessions.firstOrNull()
        ?: remember { LocalTerminalManager.getOrCreatePrimarySession(context) }

    val isExited by activeSession.isExited.collectAsState()
    val title by activeSession.title.collectAsState()

    var terminalTextSize by remember { mutableIntStateOf(34) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }

    val sendKeyToTerminal: (Int, String) -> Unit = { keyCode, fallbackString ->
        val view = currentTerminalView
        val consumed = if (keyCode != 0 && view != null) {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            view.dispatchKeyEvent(down) && view.dispatchKeyEvent(up)
        } else false

        if (!consumed) {
            activeSession.write(fallbackString)
        }
        view?.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            // TOP HEADER BAR: Terminal session tabs, path, and controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F11))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot (Green when active, Red when exited)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isExited) Color.Red else QuotaGreen)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Sessions Tabs
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEach { sess ->
                        val isSelected = sess.id == activeSession.id
                        Surface(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { LocalTerminalManager.selectSession(sess.id) },
                            color = if (isSelected) Color(0xFF222226) else Color.Transparent,
                            shape = RoundedCornerShape(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sess.name,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color.Gray
                                )

                                if (sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(11.dp)
                                            .clickable { LocalTerminalManager.closeSession(sess.id) }
                                    )
                                }
                            }
                        }
                    }

                    // Add session '+'
                    IconButton(
                        onClick = { LocalTerminalManager.createNewSession(context) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Close / Hide Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // NATIVE TERMUX TERMINAL VIEW (Full PTY, TUI support for nano, vim, htop, etc.)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(Color(0xFF000000))
                    .clickable {
                        currentTerminalView?.requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        currentTerminalView?.let { imm.showSoftInput(it, 0) }
                    }
                    .padding(horizontal = 4.dp)
            ) {
                key(activeSession.id) {
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTextSize(terminalTextSize)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                setTerminalViewClient(object : TerminalViewClient {
                                    override fun onScale(scale: Float): Float {
                                        if (scale < 0.9f || scale > 1.1f) {
                                            val doIncrease = scale > 1.0f
                                            val newSize = if (doIncrease) terminalTextSize + 1 else terminalTextSize - 1
                                            if (newSize in 18..60) {
                                                terminalTextSize = newSize
                                                currentTerminalView?.setTextSize(terminalTextSize)
                                            }
                                            return 1.0f
                                        }
                                        return scale
                                    }
                                    override fun onSingleTapUp(e: MotionEvent) {
                                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        this@apply.requestFocus()
                                        imm.showSoftInput(this@apply, 0)
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
                                    override fun readControlKey(): Boolean = isCtrlActive
                                    override fun readAltKey(): Boolean = isAltActive
                                    override fun readShiftKey(): Boolean = false
                                    override fun readFnKey(): Boolean = false
                                    override fun onEmulatorSet() {}
                                    override fun logError(tag: String, message: String) {}
                                    override fun logWarn(tag: String, message: String) {}
                                    override fun logInfo(tag: String, message: String) {}
                                    override fun logDebug(tag: String, message: String) {}
                                    override fun logVerbose(tag: String, message: String) {}
                                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                    override fun logStackTrace(tag: String, e: Exception) {}
                                })
                                attachSession(activeSession.terminalSession)
                                activeSession.onTextChangedListener = {
                                    post {
                                        onScreenUpdated()
                                        invalidate()
                                    }
                                }
                                currentTerminalView = this
                                post {
                                    requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
                            }
                        },
                        update = { tv ->
                            tv.setTextSize(terminalTextSize)
                            if (tv.currentSession != activeSession.terminalSession) {
                                tv.attachSession(activeSession.terminalSession)
                            }
                            activeSession.onTextChangedListener = {
                                tv.post {
                                    tv.onScreenUpdated()
                                    tv.invalidate()
                                }
                            }
                            currentTerminalView = tv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // EXTRA-KEYS TOOLBAR (Exact 2-Row Termux Layout from Screenshot)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C0C0E))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // ROW 1: ESC | / | - | HOME | ↑ | END | PGUP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TermuxKey(label = "ESC", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_ESCAPE, "\u001B")
                    }
                    TermuxKey(label = "/", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_SLASH, "/")
                    }
                    TermuxKey(label = "-", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MINUS, "-")
                    }
                    TermuxKey(label = "HOME", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_HOME, "\u001B[H")
                    }
                    TermuxKey(label = "↑", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_UP, "\u001B[A")
                    }
                    TermuxKey(label = "END", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_END, "\u001B[F")
                    }
                    TermuxKey(label = "PGUP", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_UP, "\u001B[5~")
                    }
                }

                // ROW 2: ↹ (TAB) | CTRL | ALT | ← | ↓ | → | PGDN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TermuxKey(label = "↹", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_TAB, "\t")
                    }
                    TermuxKey(
                        label = "CTRL",
                        isActive = isCtrlActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        isCtrlActive = !isCtrlActive
                    }
                    TermuxKey(
                        label = "ALT",
                        isActive = isAltActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        isAltActive = !isAltActive
                    }
                    TermuxKey(label = "←", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_LEFT, "\u001B[D")
                    }
                    TermuxKey(label = "↓", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_DOWN, "\u001B[B")
                    }
                    TermuxKey(label = "→", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_RIGHT, "\u001B[C")
                    }
                    TermuxKey(label = "PGDN", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_DOWN, "\u001B[6~")
                    }
                }
            }
        }
    }
}

@Composable
private fun TermuxKey(
    label: String,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable { onClick() },
        color = if (isActive) ClaudeTerracotta else Color(0xFF1E1E22),
        shape = RoundedCornerShape(3.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) Color.White else Color(0xFFE2E8F0)
            )
        }
    }
}

