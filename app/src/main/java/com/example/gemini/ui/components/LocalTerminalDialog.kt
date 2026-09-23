package com.example.gemini.ui.components

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.example.gemini.data.local.LocalPtySession
import com.example.gemini.data.local.LocalTerminalManager
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.*
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class ModifierState {
    OFF,
    ONE_SHOT,
    LOCKED
}

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

    val isSyncingTmux by LocalTerminalManager.isSyncingTmux.collectAsState()

    var hasEverHadSessions by remember { mutableStateOf(false) }

    // Ensure sessions are restored from remote tmux or created
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            LocalTerminalManager.getOrCreateOrRestoreSessions(context)
        }
    }

    // Auto close terminal dialog ONLY when all sessions are closed by the user
    LaunchedEffect(sessions) {
        if (sessions.isNotEmpty()) {
            hasEverHadSessions = true
        } else if (hasEverHadSessions && !isSyncingTmux) {
            onClose()
        }
    }

    val activeSession = sessions.find { it.id == activeSessionId }
        ?: sessions.firstOrNull()

    if (activeSession == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = ClaudeTerracotta,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Restoring active terminal sessions...",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    val view = androidx.compose.ui.platform.LocalView.current

    SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: (view.context as? android.app.Activity)?.window
        if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    val authPreferences = remember { AuthPreferences(context) }
    val savedFontSize by authPreferences.terminalFontSize.collectAsState(initial = 14)
    val savedCursorStyle by authPreferences.terminalCursorStyle.collectAsState(initial = "BAR")
    val savedBufferSize by authPreferences.terminalBufferSize.collectAsState(initial = 20000)

    val density = androidx.compose.ui.platform.LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val baseTextSizePx = remember(savedFontSize, density) {
        (savedFontSize * density.density).roundToInt().coerceIn(16, 60)
    }

    val isExited by activeSession.isExited.collectAsState()
    val title by activeSession.title.collectAsState()

    var terminalTextSize by remember(baseTextSizePx) { mutableIntStateOf(baseTextSizePx) }
    var ctrlState by remember { mutableStateOf(ModifierState.OFF) }
    var altState by remember { mutableStateOf(ModifierState.OFF) }
    var isTabsMenuExpanded by remember { mutableStateOf(false) }
    var sessionToClose by remember { mutableStateOf<LocalPtySession?>(null) }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }

    LaunchedEffect(savedCursorStyle, savedBufferSize) {
        LocalTerminalManager.updatePreferences(savedCursorStyle, savedBufferSize)
        currentTerminalView?.currentSession?.emulator?.setCursorStyle()
        currentTerminalView?.invalidate()
    }

    val sendKeyToTerminal: (Int, String) -> Unit = sendKey@{ keyCode, fallbackString ->
        val currentPty = LocalTerminalManager.sessions.value.find { it.id == LocalTerminalManager.activeSessionId.value } ?: activeSession
        if (currentPty.isExited.value && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || fallbackString == "\r" || fallbackString == "\n")) {
            LocalTerminalManager.closeSession(currentPty.id)
            return@sendKey
        }
        val termView = currentTerminalView
        var handled = false
        val ctrl = ctrlState != ModifierState.OFF
        val alt = altState != ModifierState.OFF
        if (termView != null && keyCode != 0) {
            var keyMod = 0
            if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
            if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
            handled = termView.handleKeyCode(keyCode, keyMod)
        }

        if (!handled) {
            currentPty.write(fallbackString)
        }
        if (ctrlState == ModifierState.ONE_SHOT) {
            ctrlState = ModifierState.OFF
        }
        if (altState == ModifierState.ONE_SHOT) {
            altState = ModifierState.OFF
        }
        termView?.requestFocus()
    }

    DisposableEffect(activeSession, currentTerminalView) {
        val tv = currentTerminalView
        if (tv != null) {
            val listener: () -> Unit = {
                tv.post {
                    tv.onScreenUpdated()
                    tv.invalidate()
                }
            }
            activeSession.addTextChangedListener(listener)
            onDispose {
                activeSession.removeTextChangedListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val statusBarHeightDp = with(density) { statusBarHeightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            // NATIVE TERMUX TERMINAL VIEW (Full Screen, spans from the very top pixel)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(Color(0xFF000000))
                    .padding(horizontal = 4.dp)
            ) {
                key(activeSession.id) {
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTopPadding(statusBarHeightPx)
                                setTextSize(terminalTextSize)
                                isFocusable = true
                                isFocusableInTouchMode = true

                                val createClient: () -> TerminalViewClient = {
                                    object : TerminalViewClient {
                                        override fun onScale(scale: Float): Float {
                                            if (scale < 0.9f || scale > 1.1f) {
                                                val doIncrease = scale > 1.0f
                                                val newSize = if (doIncrease) terminalTextSize + 1 else terminalTextSize - 1
                                                if (newSize in 16..60) {
                                                    terminalTextSize = newSize
                                                    currentTerminalView?.setTextSize(terminalTextSize)
                                                    val newSp = (newSize / density.density).roundToInt().coerceIn(8, 28)
                                                    coroutineScope.launch {
                                                        authPreferences.saveTerminalPreferences(
                                                            fontSize = newSp,
                                                            cursorStyle = savedCursorStyle,
                                                            bufferSize = savedBufferSize,
                                                            theme = "DEFAULT"
                                                        )
                                                    }
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
                                        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                                            val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == session } ?: activeSession
                                            if (targetPty.isExited.value) {
                                                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                                                    LocalTerminalManager.closeSession(targetPty.id)
                                                    return true
                                                }
                                            }
                                            val isCtrl = e.isCtrlPressed || ctrlState != ModifierState.OFF
                                            if (isCtrl) {
                                                when (keyCode) {
                                                    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> {
                                                        LocalTerminalManager.selectPreviousSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> {
                                                        LocalTerminalManager.selectNextSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                    KeyEvent.KEYCODE_T -> {
                                                        LocalTerminalManager.createNewSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                }
                                            }
                                            return false
                                        }
                                        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                                        override fun onLongPress(event: MotionEvent): Boolean = false
                                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                                            val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == session } ?: activeSession
                                            if (targetPty.isExited.value) {
                                                if (codePoint == '\n'.code || codePoint == '\r'.code) {
                                                    LocalTerminalManager.closeSession(targetPty.id)
                                                    return true
                                                }
                                            }
                                            if (ctrlDown) {
                                                when (codePoint.toChar()) {
                                                    '1' -> {
                                                        LocalTerminalManager.selectPreviousSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                    '2' -> {
                                                        LocalTerminalManager.selectNextSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                    't', 'T' -> {
                                                        LocalTerminalManager.createNewSession(ctx)
                                                        if (ctrlState == ModifierState.ONE_SHOT) ctrlState = ModifierState.OFF
                                                        return true
                                                    }
                                                }
                                            }
                                            return false
                                        }
                                        override fun readControlKey(): Boolean {
                                            val active = ctrlState != ModifierState.OFF
                                            if (ctrlState == ModifierState.ONE_SHOT) {
                                                ctrlState = ModifierState.OFF
                                            }
                                            return active
                                        }
                                        override fun readAltKey(): Boolean {
                                            val active = altState != ModifierState.OFF
                                            if (altState == ModifierState.ONE_SHOT) {
                                                altState = ModifierState.OFF
                                            }
                                            return active
                                        }
                                        override fun readShiftKey(): Boolean = false
                                        override fun readFnKey(): Boolean = false
                                        override fun onEmulatorSet() {}
                                        override fun logError(tag: String, message: String) { android.util.Log.e("TerminalViewKey", message) }
                                        override fun logWarn(tag: String, message: String) { android.util.Log.w("TerminalViewKey", message) }
                                        override fun logInfo(tag: String, message: String) { android.util.Log.i("TerminalViewKey", message) }
                                        override fun logDebug(tag: String, message: String) { android.util.Log.d("TerminalViewKey", message) }
                                        override fun logVerbose(tag: String, message: String) { android.util.Log.v("TerminalViewKey", message) }
                                        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e("TerminalViewKey", message, e) }
                                        override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e("TerminalViewKey", "stacktrace", e) }
                                    }
                                }

                                setTerminalViewClient(createClient())
                                setTerminalInputListener(object : TerminalView.TerminalInputListener {
                                    override fun onTerminalInput(text: String) {
                                        val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == currentSession } ?: activeSession
                                        if (targetPty.isExited.value && (text.contains("\n") || text.contains("\r"))) {
                                            LocalTerminalManager.closeSession(targetPty.id)
                                            return
                                        }
                                        targetPty.write(text)
                                    }
                                    override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                        val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == currentSession } ?: activeSession
                                        if (targetPty.isExited.value && (codePoint == '\n'.code || codePoint == '\r'.code)) {
                                            LocalTerminalManager.closeSession(targetPty.id)
                                            return
                                        }
                                        targetPty.writeCodePoint(prependEscape, codePoint)
                                    }
                                })
                                setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                    activeSession.updateSize(cols, rows, widthPx, heightPx)
                                }
                                attachSession(activeSession.terminalSession)
                                activeSession.terminalSession.emulator?.setCursorStyle()
                                currentTerminalView = this
                                post {
                                    requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(this, 0)
                                }
                            }
                        },
                        update = { tv ->
                            tv.setTopPadding(statusBarHeightPx)
                            tv.setTextSize(terminalTextSize)
                            if (tv.currentSession != activeSession.terminalSession) {
                                tv.attachSession(activeSession.terminalSession)
                                activeSession.terminalSession.emulator?.setCursorStyle()
                            }
                            tv.setTerminalInputListener(object : TerminalView.TerminalInputListener {
                                override fun onTerminalInput(text: String) {
                                    val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == tv.currentSession } ?: activeSession
                                    if (targetPty.isExited.value && (text.contains("\n") || text.contains("\r"))) {
                                        LocalTerminalManager.closeSession(targetPty.id)
                                        return
                                    }
                                    targetPty.write(text)
                                }
                                override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                    val targetPty = LocalTerminalManager.sessions.value.find { it.terminalSession == tv.currentSession } ?: activeSession
                                    if (targetPty.isExited.value && (codePoint == '\n'.code || codePoint == '\r'.code)) {
                                        LocalTerminalManager.closeSession(targetPty.id)
                                        return
                                    }
                                    targetPty.writeCodePoint(prependEscape, codePoint)
                                }
                            })
                            tv.setTerminalSizeListener { cols, rows, widthPx, heightPx ->
                                activeSession.updateSize(cols, rows, widthPx, heightPx)
                            }
                            currentTerminalView = tv
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // EXTRA-KEYS TOOLBAR (Physical visual click feedback, auto-repeat for arrows, one-shot/lock modifiers)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // ROW 1: ESC | / | - | HOME | ↑ | END | PGUP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TermuxKey(label = "ESC", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_ESCAPE, "\u001B")
                    }
                    TermuxKey(label = "/", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_SLASH, "/")
                    }
                    TermuxKey(label = "-", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MINUS, "-")
                    }
                    TermuxKey(label = "HOME", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_HOME, "\u0001")
                    }
                    TermuxKey(label = "↑", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_UP, "\u001B[A")
                    }
                    TermuxKey(label = "END", modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_END, "\u0005")
                    }
                    TermuxKey(label = "PGUP", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_UP, "\u001B[5~")
                    }
                }

                // ROW 2: ↹ (TAB) | CTRL | ALT | ← | ↓ | → | PGDN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TermuxKey(label = "↹", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_TAB, "\t")
                    }
                    TermuxKey(
                        label = "CTRL",
                        isActive = ctrlState != ModifierState.OFF,
                        isLocked = ctrlState == ModifierState.LOCKED,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ctrlState = if (ctrlState == ModifierState.OFF) ModifierState.ONE_SHOT else ModifierState.OFF
                        },
                        onLongClick = {
                            ctrlState = if (ctrlState == ModifierState.LOCKED) ModifierState.OFF else ModifierState.LOCKED
                        }
                    )
                    TermuxKey(
                        label = "ALT",
                        isActive = altState != ModifierState.OFF,
                        isLocked = altState == ModifierState.LOCKED,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            altState = if (altState == ModifierState.OFF) ModifierState.ONE_SHOT else ModifierState.OFF
                        },
                        onLongClick = {
                            altState = if (altState == ModifierState.LOCKED) ModifierState.OFF else ModifierState.LOCKED
                        }
                    )
                    TermuxKey(label = "←", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_LEFT, "\u001B[D")
                    }
                    TermuxKey(label = "↓", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_DOWN, "\u001B[B")
                    }
                    TermuxKey(label = "→", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_RIGHT, "\u001B[C")
                    }
                    TermuxKey(label = "PGDN", enableRepeat = true, modifier = Modifier.weight(1f)) {
                        sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_DOWN, "\u001B[6~")
                    }
                }
            }
        }

        // FROSTED GLASS BLUR STATUS BAR OVERLAY
        if (statusBarHeightDp > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeightDp + 6.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0xD90A0A10),
                            0.7f to Color(0x880A0A10),
                            1.0f to Color(0x000A0A10)
                        )
                    )
            )
        }

        // DRAGGABLE FLOATING TAB PILL (Move anywhere on screen)
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 10.dp)
                .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    }
                },
            shape = RoundedCornerShape(20.dp),
            color = Color(0xF2181824),
            border = BorderStroke(1.dp, Color(0x38FFFFFF)),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Active Tab Name & Switcher Dropdown Anchor
                Box {
                    val activeTitle by activeSession.title.collectAsState()
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { isTabsMenuExpanded = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeTitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Switch Tab",
                            tint = Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Floating Sessions Dropdown Menu
                    DropdownMenu(
                        expanded = isTabsMenuExpanded,
                        onDismissRequest = { isTabsMenuExpanded = false },
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnClickOutside = true,
                            dismissOnBackPress = true
                        ),
                        modifier = Modifier.background(Color(0xFF1E1E26))
                    ) {
                        sessions.forEach { sess ->
                            val isSelected = sess.id == activeSession.id
                            val sessExited by sess.isExited.collectAsState()
                            val sessTitle by sess.title.collectAsState()
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (sessExited) Color(0xFFEF5350) else QuotaGreen)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = sessTitle,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) ClaudeTerracotta else Color.White,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                sessionToClose = sess
                                                isTabsMenuExpanded = false
                                            },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close Tab",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    LocalTerminalManager.selectSession(sess.id)
                                    isTabsMenuExpanded = false
                                }
                            )
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = Color(0x33FFFFFF))

                        Surface(
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    enabled = !isSyncingTmux,
                                    onClick = {
                                        LocalTerminalManager.createNewSession(context)
                                        isTabsMenuExpanded = false
                                    },
                                    onLongClick = {
                                        LocalTerminalManager.createNewSession(context, forceShell = "bash")
                                        android.widget.Toast.makeText(context, "Created Bash session", android.widget.Toast.LENGTH_SHORT).show()
                                        isTabsMenuExpanded = false
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Tab",
                                    tint = if (!isSyncingTmux) ClaudeTerracotta else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "New Session",
                                        color = if (!isSyncingTmux) ClaudeTerracotta else Color.Gray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Long-press to force Bash shell",
                                        color = Color(0x88FFFFFF),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (isSyncingTmux) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(12.dp),
                        color = ClaudeTerracotta,
                        strokeWidth = 1.5.dp
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // Close / Hide Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        if (sessionToClose != null) {
            val targetSession = sessionToClose!!
            val sessTitle by targetSession.title.collectAsState()
            AlertDialog(
                onDismissRequest = { sessionToClose = null },
                shape = RoundedCornerShape(18.dp),
                containerColor = Color(0xFF1E1E1E),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0x22EF5350)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = Color(0xFFEF5350),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Close Tab?",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                },
                text = {
                    Text(
                        text = "Are you sure you want to close \"$sessTitle\"?\nAny process running in this session will be terminated.",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val idToClose = targetSession.id
                            sessionToClose = null
                            LocalTerminalManager.closeSession(idToClose)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Close Tab", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { sessionToClose = null },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            )
        }
    }
}

@Composable
private fun TermuxKey(
    label: String,
    isActive: Boolean = false,
    isLocked: Boolean = false,
    modifier: Modifier = Modifier,
    enableRepeat: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val scope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }

    val keyModifier = if (enableRepeat) {
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                isPressed = true
                currentOnClick()

                val repeatJob = scope.launch {
                    delay(350)
                    while (true) {
                        currentOnClick()
                        delay(55)
                    }
                }

                try {
                    waitForUpOrCancellation()
                } finally {
                    repeatJob.cancel()
                    isPressed = false
                }
            }
        }
    } else {
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = {
                    currentOnClick()
                },
                onLongPress = {
                    currentOnLongClick?.invoke()
                }
            )
        }
    }

    val backgroundColor = when {
        isPressed -> Color(0xFF383844)
        isLocked -> ClaudeTerracotta.copy(alpha = 0.5f)
        isActive -> ClaudeTerracotta.copy(alpha = 0.25f)
        else -> Color.Transparent
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
        else -> null
    }

    Surface(
        modifier = keyModifier
            .height(34.dp)
            .clip(RoundedCornerShape(5.dp)),
        color = backgroundColor,
        border = border,
        shape = RoundedCornerShape(5.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isActive || isLocked || isPressed) FontWeight.Bold else FontWeight.SemiBold,
                    color = textColor
                )
                if (isLocked) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            }
        }
    }
}
