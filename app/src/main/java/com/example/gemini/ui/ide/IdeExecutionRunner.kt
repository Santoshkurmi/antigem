package com.example.gemini.ui.ide

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gemini.data.local.LocalPtySession
import com.example.gemini.theme.ClaudeTerracotta
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.UUID

data class IdeExecutionRequest(
    val id: String = UUID.randomUUID().toString(),
    val profileName: String,
    val command: String,
    val workingDir: String? = null
)

private enum class IdeKeyModifierState { OFF, ONE_SHOT, LOCKED }

@Composable
fun IdeExecutionRunnerSheet(
    request: IdeExecutionRequest,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var sessionKey by remember { mutableStateOf(request.id) }
    var terminalTextSize by remember { mutableIntStateOf((13 * density.density).toInt().coerceIn(16, 60)) }
    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }
    var ctrlState by remember { mutableStateOf(IdeKeyModifierState.OFF) }
    var altState by remember { mutableStateOf(IdeKeyModifierState.OFF) }

    val ptySession = remember(sessionKey) {
        val displayCmd = request.command
        val escapedCmd = displayCmd.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val wrappedCmd = "echo -e \"\\033[1;32m▶ $escapedCmd\\033[0m\"; $displayCmd"
        LocalPtySession(
            id = "ide_run_${UUID.randomUUID()}",
            initialTitle = request.profileName,
            context = context,
            forceShell = "bash",
            initialWorkingDir = request.workingDir,
            initialCommand = wrappedCmd
        )
    }

    val isExited by ptySession.isExited.collectAsState()

    DisposableEffect(ptySession) {
        onDispose {
            ptySession.close()
        }
    }

    fun sendKeyToTerminal(keyCode: Int, fallbackString: String) {
        val tv = currentTerminalView ?: return
        val currentSession = ptySession.terminalSession

        val isCtrl = ctrlState != IdeKeyModifierState.OFF
        val isAlt = altState != IdeKeyModifierState.OFF

        val event = KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0,
            (if (isCtrl) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
                    (if (isAlt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0)
        )

        val handled = tv.mClient?.onKeyDown(keyCode, event, currentSession) == true
        if (!handled) {
            ptySession.write(fallbackString)
        }

        if (ctrlState == IdeKeyModifierState.ONE_SHOT) ctrlState = IdeKeyModifierState.OFF
        if (altState == IdeKeyModifierState.ONE_SHOT) altState = IdeKeyModifierState.OFF
    }

    Surface(
        color = Color(0xFF141418),
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E24))
                    .border(BorderStroke(0.5.dp, Color(0xFF2E2E38)))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (!isExited) Color(0xFF4CAF50) else Color(0xFFEF4444))
                    )

                    Column {
                        Text(
                            text = request.profileName,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (!isExited) "Running: ${request.command}" else "Finished / Exited",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (!isExited) Color(0xFF4CAF50) else Color(0xFFEF4444)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Re-run / Restart
                    IconButton(
                        onClick = {
                            ptySession.close()
                            sessionKey = UUID.randomUUID().toString()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Restart",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Stop / Interrupt (Ctrl+C)
                    IconButton(
                        onClick = {
                            if (!isExited) {
                                ptySession.write("\u0003")
                            } else {
                                ptySession.close()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = if (!isExited) Color(0xFFEF4444) else Color.DarkGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Clear Screen
                    IconButton(
                        onClick = {
                            try {
                                ptySession.terminalSession.reset()
                                ptySession.write("\u000c")
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Close
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // Interactive Native Termux Terminal View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F12))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                key(sessionKey) {
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTextSize(terminalTextSize)
                                isFocusable = true
                                isFocusableInTouchMode = true

                                setTerminalViewClient(object : TerminalViewClient {
                                    override fun onScale(scale: Float): Float {
                                        if (scale < 0.92f || scale > 1.08f) {
                                            val doIncrease = scale > 1.0f
                                            val newSize = if (doIncrease) terminalTextSize + 1 else terminalTextSize - 1
                                            if (newSize in 16..60) {
                                                terminalTextSize = newSize
                                                this@apply.setTextSize(terminalTextSize)
                                            }
                                            return 1.0f
                                        }
                                        return scale
                                    }

                                    override fun onSingleTapUp(e: MotionEvent) {
                                        this@apply.requestFocus()
                                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.showSoftInput(this@apply, 0)
                                    }

                                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                    override fun shouldEnforceCharBasedInput(): Boolean = false
                                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                    override fun isTerminalViewSelected(): Boolean = true
                                    override fun copyModeChanged(copyMode: Boolean) {}

                                    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                                        val isCtrl = e.isCtrlPressed || ctrlState != IdeKeyModifierState.OFF
                                        val isAlt = e.isAltPressed || altState != IdeKeyModifierState.OFF

                                        if (isCtrl && keyCode == KeyEvent.KEYCODE_C) {
                                            ptySession.write("\u0003")
                                            if (ctrlState == IdeKeyModifierState.ONE_SHOT) ctrlState = IdeKeyModifierState.OFF
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                                    override fun onLongPress(event: MotionEvent): Boolean = false
                                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

                                    override fun readControlKey(): Boolean = ctrlState != IdeKeyModifierState.OFF
                                    override fun readAltKey(): Boolean = altState != IdeKeyModifierState.OFF
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

                                setTerminalInputListener(object : TerminalView.TerminalInputListener {
                                    override fun onTerminalInput(text: String) {
                                        ptySession.write(text)
                                    }
                                    override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                        ptySession.writeCodePoint(prependEscape, codePoint)
                                    }
                                })

                                setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                    ptySession.updateSize(cols, rows, widthPx, heightPx)
                                }

                                attachSession(ptySession.terminalSession)
                                currentTerminalView = this

                                post {
                                    requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
                            }
                        },
                        update = { tv ->
                            currentTerminalView = tv
                            tv.setTextSize(terminalTextSize)
                            tv.setTerminalInputListener(object : TerminalView.TerminalInputListener {
                                override fun onTerminalInput(text: String) {
                                    ptySession.write(text)
                                }
                                override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                    ptySession.writeCodePoint(prependEscape, codePoint)
                                }
                            })
                            tv.setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                ptySession.updateSize(cols, rows, widthPx, heightPx)
                            }
                            if (tv.currentSession != ptySession.terminalSession) {
                                tv.attachSession(ptySession.terminalSession)
                            }
                            tv.post {
                                tv.onScreenUpdated()
                                tv.invalidate()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Bottom Extra Keys Bar (Quick access to terminal navigation)
            Surface(
                color = Color(0xFF141418),
                border = BorderStroke(0.5.dp, Color(0xFF26262E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    IdeTermKey(label = "ESC", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_ESCAPE, "\u001B")
                    }
                    IdeTermKey(label = "TAB", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_TAB, "\t")
                    }
                    IdeTermKey(
                        label = "CTRL",
                        isActive = ctrlState != IdeKeyModifierState.OFF,
                        isLocked = ctrlState == IdeKeyModifierState.LOCKED,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ctrlState = if (ctrlState == IdeKeyModifierState.OFF) IdeKeyModifierState.ONE_SHOT else IdeKeyModifierState.OFF
                        },
                        onLongClick = {
                            ctrlState = if (ctrlState == IdeKeyModifierState.LOCKED) IdeKeyModifierState.OFF else IdeKeyModifierState.LOCKED
                        }
                    )
                    IdeTermKey(
                        label = "ALT",
                        isActive = altState != IdeKeyModifierState.OFF,
                        isLocked = altState == IdeKeyModifierState.LOCKED,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            altState = if (altState == IdeKeyModifierState.OFF) IdeKeyModifierState.ONE_SHOT else IdeKeyModifierState.OFF
                        },
                        onLongClick = {
                            altState = if (altState == IdeKeyModifierState.LOCKED) IdeKeyModifierState.OFF else IdeKeyModifierState.LOCKED
                        }
                    )
                    IdeTermKey(label = "↑", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_UP, "\u001B[A")
                    }
                    IdeTermKey(label = "↓", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_DOWN, "\u001B[B")
                    }
                    IdeTermKey(label = "←", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_LEFT, "\u001B[D")
                    }
                    IdeTermKey(label = "→", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_RIGHT, "\u001B[C")
                    }
                }
            }
        }
    }
}

@Composable
private fun IdeTermKey(
    label: String,
    isActive: Boolean = false,
    isLocked: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    var isPressed by remember { mutableStateOf(false) }

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)

    val keyModifier = modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                isPressed = true
                try {
                    tryAwaitRelease()
                } finally {
                    isPressed = false
                }
            },
            onTap = {
                currentOnClick()
            },
            onLongPress = {
                currentOnLongClick?.invoke()
            }
        )
    }

    val backgroundColor = when {
        isPressed -> Color(0xFF383844)
        isLocked -> ClaudeTerracotta.copy(alpha = 0.5f)
        isActive -> ClaudeTerracotta.copy(alpha = 0.25f)
        else -> Color(0xFF202028)
    }

    val textColor = when {
        isPressed -> Color.White
        isLocked -> Color.White
        isActive -> ClaudeTerracotta
        else -> Color(0xFFE2E2E6)
    }

    val border = when {
        isLocked -> BorderStroke(1.dp, ClaudeTerracotta)
        isActive -> BorderStroke(0.5.dp, ClaudeTerracotta.copy(alpha = 0.5f))
        else -> BorderStroke(0.5.dp, Color(0xFF32323E))
    }

    Surface(
        modifier = keyModifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp)),
        color = backgroundColor,
        border = border,
        shape = RoundedCornerShape(6.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive || isLocked || isPressed) FontWeight.Bold else FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
