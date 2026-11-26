package ai.antigem.ui.components

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import ai.antigem.data.local.LocalPtySession
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.*
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ai.antigem.i18n.L

enum class ModifierState {
    OFF,
    ONE_SHOT,
    LOCKED
}

/**
 * Focus the terminal and show the keyboard (a tap on it, the drawer's KEYBOARD). If refused, asked again a moment
 * later (posted): older Android learns of the focus change late and refuses a request made right after it. Still
 * refused with the keyboard hidden: the forced toggle Termux's own Keyboard button uses (only when hidden, as a
 * toggle would otherwise close it).
 */
internal fun showTerminalKeyboard(tv: TerminalView) {
    val imm = tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    tv.requestFocus()
    if (imm.showSoftInput(tv, 0)) return
    tv.post {
        if (imm.showSoftInput(tv, 0)) return@post
        val imeShown = androidx.core.view.ViewCompat.getRootWindowInsets(tv)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (!imeShown) {
            @Suppress("DEPRECATION")
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }
    }
}

@Composable
fun LocalTerminalDialog(
    onDismiss: () -> Unit,
    /** The sessions drawer's gear; null = no gear (no settings screen where this terminal runs). */
    onOpenSettings: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LocalTerminalContent(onClose = onDismiss, onOpenSettings = onOpenSettings)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalTerminalContent(
    onClose: () -> Unit,
    customSession: LocalPtySession? = null,
    showFloatingPill: Boolean = true,
    backgroundColor: Color = ai.antigem.theme.AppColors.terminalBg,
    isMaximized: Boolean = true,
    onToggleMaximize: (() -> Unit)? = null,
    onDragDelta: ((Float) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** The title / retry / close bar above a [customSession]; off where the screen shows its own (the Run screen). */
    showSessionHeader: Boolean = true,
    /** The screen whose keyboard focus this terminal takes; null = none (the Run screen's terminal). */
    focusScreenMode: ai.antigem.AppViewMode? = ai.antigem.AppViewMode.TERMINAL,
    /** Focus the terminal and open the keyboard when it appears; off for the Run screen (a build log, maybe off screen). */
    showKeyboardOnOpen: Boolean = true,
    /** Whether this terminal is the screen shown now (hidden screens stay composed): a hidden one closes its sessions drawer. */
    isVisible: Boolean = true,
    /** Something drawn over this terminal (the IDE's sidebar): TalkBack can't reach the terminal meanwhile. */
    coveredForA11y: Boolean = false,
    /** The sessions drawer's gear: show Settings → Terminal; null = no gear. */
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val sessions by LocalTerminalManager.sessions.collectAsState()
    val activeSessionId by LocalTerminalManager.activeSessionId.collectAsState()

    val isSyncingTmux by LocalTerminalManager.isSyncingTmux.collectAsState()

    var hasEverHadSessions by remember { mutableStateOf(false) }

    // Ensure sessions are restored from remote tmux or created
    LaunchedEffect(customSession) {
        if (customSession == null && sessions.isEmpty()) {
            LocalTerminalManager.getOrCreateOrRestoreSessions(context)
        }
    }

    // Auto close terminal dialog ONLY when all sessions are closed by the user
    LaunchedEffect(sessions, customSession) {
        if (customSession == null) {
            if (sessions.isNotEmpty()) {
                hasEverHadSessions = true
            } else if (hasEverHadSessions && !isSyncingTmux) {
                onClose()
            }
        }
    }

    val activeSession = customSession
        ?: sessions.find { it.id == activeSessionId }
        ?: sessions.firstOrNull()

    if (activeSession == null) {
        // the first session is usually ready in well under a second: a spinner flashed for that moment makes the open
        // feel slow, so show only the background for the first second
        var showLoading by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(1000)
            showLoading = true
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            if (showLoading) Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = ClaudeTerracotta,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = L.components.localTerminal.restoringActiveTerminalSessions,
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    val view = androidx.compose.ui.platform.LocalView.current

    val isDialog = (view.parent as? androidx.compose.ui.window.DialogWindowProvider) != null
    DisposableEffect(Unit) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: (view.context as? android.app.Activity)?.window
        if (window != null) {
            if (isDialog) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        onDispose {}
    }

    val authPreferences = remember { AuthPreferences(context) }
    val initialFontSizeSp = remember { authPreferences.getTerminalFontSizeSync() }
    val savedFontSize by authPreferences.terminalFontSize.collectAsState(initial = initialFontSizeSp)
    val savedCursorStyle by authPreferences.terminalCursorStyle.collectAsState(initial = "BAR")
    val savedBufferSize by authPreferences.terminalBufferSize.collectAsState(initial = 20000)

    val density = androidx.compose.ui.platform.LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val isExited by activeSession.isExited.collectAsState()
    val title by activeSession.title.collectAsState()

    var terminalTextSize by remember {
        mutableIntStateOf((initialFontSizeSp * density.density).roundToInt().coerceIn(16, 60))
    }

    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }
    // a theme change repaints the terminal in its new colors
    val terminalThemeVersion = ai.antigem.theme.ThemeTerminal.version
    LaunchedEffect(terminalThemeVersion) { currentTerminalView?.onScreenUpdated() }
    val focusCoordinator = LocalAppFocusCoordinator.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    DisposableEffect(focusCoordinator, currentTerminalView, focusScreenMode) {
        if (focusScreenMode == null) return@DisposableEffect onDispose {}
        focusCoordinator.registerScreen(
            mode = focusScreenMode,
            onRequestFocus = {
                val tv = currentTerminalView
                if (tv != null) {
                    tv.requestFocus()
                    val imm = tv.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(tv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            },
            onClearFocus = {
                currentTerminalView?.clearFocus()
                focusManager.clearFocus(force = true)
            }
        )
        onDispose {
            focusCoordinator.unregisterScreen(focusScreenMode)
        }
    }

    LaunchedEffect(savedFontSize) {
        val currentSp = (terminalTextSize / density.density).roundToInt()
        if (currentSp != savedFontSize) {
            val newPx = (savedFontSize * density.density).roundToInt().coerceIn(16, 60)
            terminalTextSize = newPx
            currentTerminalView?.setTextSize(newPx)
        }
    }
    var ctrlState by remember { mutableStateOf(ModifierState.OFF) }
    var altState by remember { mutableStateOf(ModifierState.OFF) }
    var isTabsMenuExpanded by remember { mutableStateOf(false) }
    var sessionToClose by remember { mutableStateOf<LocalPtySession?>(null) }

    // Termux's left drawer (sessions, KEYBOARD, NEW SESSION): the full terminal only, not a single custom session
    val hasSessionsDrawer = customSession == null
    val drawerState = rememberTerminalDrawerState()
    // a Boolean, so the drawer's per-frame movement doesn't recompose the whole terminal
    val isDrawerShown by remember(drawerState) { derivedStateOf { drawerState.isShown } }
    LaunchedEffect(isVisible) { if (!isVisible) drawerState.snapClosed() }
    androidx.activity.compose.BackHandler(enabled = isVisible && isDrawerShown) { drawerState.close() }
    // TalkBack: the terminal (a native View) is out of reach while the drawer or a panel covers it, or it's hidden
    HideFromTalkBackWhile(currentTerminalView, isDrawerShown || !isVisible || coveredForA11y)
    // TalkBack's "Read terminal output": the whole output as selectable text
    var a11yTranscript by remember { mutableStateOf<String?>(null) }
    // bigger extra keys while TalkBack is on
    val talkBackOn = rememberTalkBackOn()
    val sessionPillEnabled by authPreferences.terminalSessionPillEnabled
        .collectAsState(initial = authPreferences.getTerminalSessionPillEnabledSync())
    val drawerSwipeArea by authPreferences.terminalDrawerSwipeArea
        .collectAsState(initial = authPreferences.getTerminalDrawerSwipeAreaSync())
    val charBasedInput by authPreferences.terminalCharBasedInput
        .collectAsState(initial = authPreferences.getTerminalCharBasedInputSync())
    // the keyboard reads the input mode when it connects: reconnect it when the setting changes
    LaunchedEffect(charBasedInput) {
        val tv = currentTerminalView ?: return@LaunchedEffect
        (tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(tv)
    }

    // The extra-keys bar has a second page (swipe it sideways, like Termux): a normal text box, where any keyboard works
    // fully (Bangla/Japanese keyboards' drafts, suggestions, voice typing, the keyboard's clipboard)
    val toolbarPager = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
    var toolbarInput by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val toolbarInputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Like Termux: on Enter the typed text goes into the terminal's line as it is (not run, so it can still be edited
    // there); Enter on an empty box sends Enter. A finished session closes, as Enter does in the terminal.
    val sendToolbarInput: () -> Unit = {
        val currentPty = customSession
            ?: LocalTerminalManager.sessions.value.find { it.id == LocalTerminalManager.activeSessionId.value }
            ?: activeSession
        if (currentPty.isExited.value) {
            if (customSession != null) onClose() else LocalTerminalManager.closeSession(currentPty.id)
        } else {
            currentPty.write(toolbarInput.text.ifEmpty { "\r" })
        }
        toolbarInput = androidx.compose.ui.text.input.TextFieldValue("")
    }
    // Like Termux: the box takes the typing when swiped to, the terminal again when swiped back
    LaunchedEffect(toolbarPager) {
        androidx.compose.runtime.snapshotFlow { toolbarPager.settledPage }
            .drop(1)
            .collect { page ->
                if (page == 1) {
                    currentTerminalView?.clearFocus()
                    runCatching { toolbarInputFocus.requestFocus() }
                } else {
                    currentTerminalView?.requestFocus()
                }
            }
    }

    // The drawer's KEYBOARD: hide it if shown, else focus the terminal and show it (see showTerminalKeyboard)
    val toggleKeyboard: () -> Unit = toggle@{
        val tv = currentTerminalView ?: return@toggle
        val imeShown = androidx.core.view.ViewCompat.getRootWindowInsets(tv)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (imeShown) {
            val imm = tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(tv.windowToken, 0)
        } else {
            showTerminalKeyboard(tv)
        }
    }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(savedCursorStyle, savedBufferSize) {
        LocalTerminalManager.updatePreferences(savedCursorStyle, savedBufferSize)
        currentTerminalView?.currentSession?.emulator?.setCursorStyle()
        currentTerminalView?.invalidate()
    }

    val sendKeyToTerminal: (Int, String) -> Unit = sendKey@{ keyCode, fallbackString ->
        val currentPty = customSession ?: LocalTerminalManager.sessions.value.find { it.id == LocalTerminalManager.activeSessionId.value } ?: activeSession
        if (currentPty.isExited.value && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || fallbackString == "\r" || fallbackString == "\n")) {
            if (customSession != null) {
                onClose()
            } else {
                LocalTerminalManager.closeSession(currentPty.id)
            }
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
            .background(backgroundColor)
            .navigationBarsPadding()
            // Final keyboard height at animation start: one resize instead of a per-frame terminal reflow
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            if (customSession != null && showSessionHeader) {
                val isSessExited by activeSession.isExited.collectAsState()
                val activeTitle by activeSession.title.collectAsState()
                val currentOnDragDelta by rememberUpdatedState(onDragDelta)
                Surface(
                    color = backgroundColor,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onDragDelta != null) {
                                Modifier.draggable(
                                    state = rememberDraggableState { delta ->
                                        currentOnDragDelta?.invoke(delta)
                                    },
                                    orientation = Orientation.Vertical
                                )
                            } else Modifier
                        )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Small handle pill at the center of the header
                        if (onDragDelta != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 5.dp, bottom = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.35f))
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isSessExited) ai.antigem.theme.AppColors.danger else ai.antigem.theme.AppColors.success)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )

                            if (onToggleMaximize != null) {
                                IconButton(
                                    onClick = onToggleMaximize,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = if (isMaximized) L.components.localTerminal.minimizeRestore else L.components.localTerminal.maximize,
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                            }

                            if (onRetry != null) {
                                IconButton(
                                    onClick = onRetry,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = L.common.retry,
                                        tint = ai.antigem.theme.AppColors.success,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                            }

                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = L.common.close,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = ai.antigem.theme.AppColors.editorText.copy(alpha = 0.1f), thickness = 0.5.dp)
            }

            // NATIVE TERMUX TERMINAL VIEW (Full Screen, spans from the very top pixel)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // swipe right from the left edge: the sessions drawer (before the padding: the real screen edge)
                    .then(
                        if (hasSessionsDrawer) {
                            Modifier.terminalDrawerEdgeSwipe(drawerState, drawerSwipeArea, statusBarHeightPx.toFloat())
                        } else Modifier
                    )
                    .clipToBounds()
                    .background(backgroundColor)
                    .padding(horizontal = 4.dp)
            ) {
                key(activeSession.id) {
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTopPadding(if (customSession != null) 0 else statusBarHeightPx)
                                setTextSize(terminalTextSize)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                addReadOutputA11yAction { a11yTranscript = it }
                                // the sessions drawer only opens by an edge swipe, which TalkBack can't do: an action for it
                                // in TalkBack's actions menu (nothing changes on screen or for touch)
                                if (hasSessionsDrawer) {
                                    androidx.core.view.ViewCompat.addAccessibilityAction(this, L.components.a11y.openTerminalSessions) { _, _ ->
                                        drawerState.open()
                                        true
                                    }
                                }

                                val createClient: () -> TerminalViewClient = {
                                    object : TerminalViewClient {
                                        override fun onScale(scale: Float): Float {
                                            if (scale < 0.92f || scale > 1.08f) {
                                                val doIncrease = scale > 1.0f
                                                val newSize = if (doIncrease) terminalTextSize + 1 else terminalTextSize - 1
                                                if (newSize in 16..60) {
                                                    terminalTextSize = newSize
                                                    currentTerminalView?.setTextSize(terminalTextSize)
                                                    val newSp = (newSize / density.density).roundToInt().coerceIn(8, 28)
                                                    authPreferences.saveTerminalPreferencesSync(
                                                        fontSize = newSp,
                                                        cursorStyle = savedCursorStyle,
                                                        bufferSize = savedBufferSize,
                                                        theme = "DEFAULT"
                                                    )
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
                                            showTerminalKeyboard(this@apply)
                                        }
                                        // two quick taps never reach onSingleTapUp: show the keyboard anyway
                                        override fun onDoubleTap(e: MotionEvent) {
                                            showTerminalKeyboard(this@apply)
                                        }
                                        // the keyboard's clipboard / paste: one bracketed paste, not hundreds of keystrokes
                                        override fun onPasteText(text: String): Boolean {
                                            val targetPty = customSession
                                                ?: LocalTerminalManager.sessions.value.find { it.terminalSession == currentSession }
                                                ?: activeSession
                                            targetPty.paste(text)
                                            return true
                                        }
                                        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                        // Settings → Terminal → Character-based input: on = the keyboard sends key presses
                                        // only (Android's TYPE_NULL, Termux's default); off = text mode, so the keyboard
                                        // shows its toolbar and clipboard (autocorrect and suggestions stay off)
                                        override fun shouldEnforceCharBasedInput(): Boolean = charBasedInput
                                        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                        override fun isTerminalViewSelected(): Boolean = true
                                        override fun copyModeChanged(copyMode: Boolean) {}
                                        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                                            val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == session } ?: activeSession
                                            if (targetPty.isExited.value) {
                                                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                                                    if (customSession != null) {
                                                        onClose()
                                                    } else {
                                                        LocalTerminalManager.closeSession(targetPty.id)
                                                    }
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
                                            val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == session } ?: activeSession
                                            if (targetPty.isExited.value) {
                                                if (codePoint == '\n'.code || codePoint == '\r'.code) {
                                                    if (customSession != null) {
                                                        onClose()
                                                    } else {
                                                        LocalTerminalManager.closeSession(targetPty.id)
                                                    }
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
                                        val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == currentSession } ?: activeSession
                                        if (targetPty.isExited.value && (text.contains("\n") || text.contains("\r"))) {
                                            if (customSession != null) {
                                                onClose()
                                            } else {
                                                LocalTerminalManager.closeSession(targetPty.id)
                                            }
                                            return
                                        }
                                        targetPty.write(text)
                                    }
                                    override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                        val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == currentSession } ?: activeSession
                                        if (targetPty.isExited.value && (codePoint == '\n'.code || codePoint == '\r'.code)) {
                                            if (customSession != null) {
                                                onClose()
                                            } else {
                                                LocalTerminalManager.closeSession(targetPty.id)
                                            }
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
                                if (showKeyboardOnOpen) post {
                                    if (hasWindowFocus()) {
                                        requestFocus()
                                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.showSoftInput(this, 0)
                                    } else {
                                        // A new window (the terminal's own app icon opens it in a dialog) gets the focus
                                        // a moment after it shows, and the keyboard is refused until then: wait for it
                                        val tv = this
                                        viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                                            override fun onWindowFocusChanged(hasFocus: Boolean) {
                                                if (!hasFocus) return
                                                tv.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                                                tv.post { showTerminalKeyboard(tv) }
                                            }
                                        })
                                    }
                                }
                            }
                        },
                        update = { tv ->
                            tv.setTopPadding(if (customSession != null) 0 else statusBarHeightPx)
                            tv.setTextSize(terminalTextSize)
                            if (tv.currentSession != activeSession.terminalSession) {
                                tv.attachSession(activeSession.terminalSession)
                                activeSession.terminalSession.emulator?.setCursorStyle()
                            }
                            tv.setTerminalInputListener(object : TerminalView.TerminalInputListener {
                                override fun onTerminalInput(text: String) {
                                    val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == tv.currentSession } ?: activeSession
                                    if (targetPty.isExited.value && (text.contains("\n") || text.contains("\r"))) {
                                        if (customSession != null) {
                                            onClose()
                                        } else {
                                            LocalTerminalManager.closeSession(targetPty.id)
                                        }
                                        return
                                    }
                                    targetPty.write(text)
                                }
                                override fun onTerminalInputCodePoint(prependEscape: Boolean, codePoint: Int) {
                                    val targetPty = customSession ?: LocalTerminalManager.sessions.value.find { it.terminalSession == tv.currentSession } ?: activeSession
                                    if (targetPty.isExited.value && (codePoint == '\n'.code || codePoint == '\r'.code)) {
                                        if (customSession != null) {
                                            onClose()
                                        } else {
                                            LocalTerminalManager.closeSession(targetPty.id)
                                        }
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

            // EXTRA-KEYS TOOLBAR (Physical visual click feedback, auto-repeat for arrows, one-shot/lock modifiers).
            // Swipe it sideways for a text box (like Termux), for keyboards that only work in a normal text field.
            androidx.compose.foundation.pager.HorizontalPager(
                state = toolbarPager,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .height(if (talkBackOn) 98.dp else 70.dp), // two rows of keys: 34dp + 2dp + 34dp (48dp each under TalkBack)
                verticalAlignment = Alignment.Top
            ) { page ->
                if (page == 1) {
                    TerminalTextInputBox(
                        value = toolbarInput,
                        onValueChange = { toolbarInput = it },
                        focusRequester = toolbarInputFocus,
                        onSend = sendToolbarInput
                    )
                } else Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // ROW 1: ESC | / | - | HOME | ↑ | END | PGUP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ExtraKey(label = "ESC", modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_ESCAPE, "\u001B")
                        }
                        ExtraKey(label = "/", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_SLASH, "/")
                        }
                        ExtraKey(label = "-", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_MINUS, "-")
                        }
                        ExtraKey(label = "HOME", modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_HOME, "\u0001")
                        }
                        ExtraKey(label = "↑", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_UP, "\u001B[A")
                        }
                        ExtraKey(label = "END", modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_MOVE_END, "\u0005")
                        }
                        ExtraKey(label = L.components.localTerminal.pgup, enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_UP, "\u001B[5~")
                        }
                    }

                    // ROW 2: ↹ (TAB) | CTRL | ALT | ← | ↓ | → | PGDN
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ExtraKey(label = "↹", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_TAB, "\t")
                        }
                        ExtraKey(
                            label = L.components.localTerminal.ctrl,
                            isActive = ctrlState != ModifierState.OFF,
                            isLocked = ctrlState == ModifierState.LOCKED,
                            modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn),
                            onClick = {
                                ctrlState = if (ctrlState == ModifierState.OFF) ModifierState.ONE_SHOT else ModifierState.OFF
                            },
                            onLongClick = {
                                ctrlState = if (ctrlState == ModifierState.LOCKED) ModifierState.OFF else ModifierState.LOCKED
                            }
                        )
                        ExtraKey(
                            label = L.components.localTerminal.alt,
                            isActive = altState != ModifierState.OFF,
                            isLocked = altState == ModifierState.LOCKED,
                            modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn),
                            onClick = {
                                altState = if (altState == ModifierState.OFF) ModifierState.ONE_SHOT else ModifierState.OFF
                            },
                            onLongClick = {
                                altState = if (altState == ModifierState.LOCKED) ModifierState.OFF else ModifierState.LOCKED
                            }
                        )
                        ExtraKey(label = "←", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_LEFT, "\u001B[D")
                        }
                        ExtraKey(label = "↓", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_DOWN, "\u001B[B")
                        }
                        ExtraKey(label = "→", enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_RIGHT, "\u001B[C")
                        }
                        ExtraKey(label = L.components.localTerminal.pgdn, enableRepeat = true, modifier = Modifier.weight(1f).a11yMinTouchTarget(talkBackOn)) {
                            sendKeyToTerminal(KeyEvent.KEYCODE_PAGE_DOWN, "\u001B[6~")
                        }
                    }
                }
            }
        }

        // FROSTED GLASS BLUR STATUS BAR OVERLAY (Only for full-screen standalone terminal dialog)
        if (statusBarHeightDp > 0.dp && customSession == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeightDp + 6.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0.0f to ai.antigem.theme.AppColors.terminalBg.copy(alpha = 0.85f),
                            0.7f to ai.antigem.theme.AppColors.terminalBg.copy(alpha = 0.53f),
                            1.0f to ai.antigem.theme.AppColors.terminalBg.copy(alpha = 0f)
                        )
                    )
            )
        }

        // DRAGGABLE FLOATING TAB PILL (Move anywhere on screen); can be turned off in Settings → Terminal
        if (showFloatingPill && sessionPillEnabled && customSession == null) {
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
                color = ai.antigem.theme.AppColors.editorPanel.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, ai.antigem.theme.AppColors.editorText.copy(alpha = 0.22f)),
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
                            contentDescription = L.components.localTerminal.switchTab,
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
                        modifier = Modifier.background(ai.antigem.theme.AppColors.editorPanel)
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
                                                .background(if (sessExited) ai.antigem.theme.AppColors.danger else QuotaGreen)
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
                                                contentDescription = L.common.closeTab,
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

                        HorizontalDivider(thickness = 0.5.dp, color = ai.antigem.theme.AppColors.editorText.copy(alpha = 0.2f))

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
                                        android.widget.Toast.makeText(context, L.components.localTerminal.createdBashSession, android.widget.Toast.LENGTH_SHORT).show()
                                        isTabsMenuExpanded = false
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = L.components.localTerminal.newTab,
                                    tint = if (!isSyncingTmux) ClaudeTerracotta else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = L.components.localTerminal.newSession,
                                        color = if (!isSyncingTmux) ClaudeTerracotta else Color.Gray,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = L.components.localTerminal.longPressToForceBashShell,
                                        color = ai.antigem.theme.AppColors.editorText.copy(alpha = 0.53f),
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
                        contentDescription = L.common.close,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        }

        // SESSIONS DRAWER (Termux's left drawer), above the terminal and the pill
        if (hasSessionsDrawer) {
            TerminalSessionsDrawer(
                state = drawerState,
                sessions = sessions,
                activeSessionId = activeSession.id,
                canCreateSession = !isSyncingTmux,
                onSelectSession = { LocalTerminalManager.selectSession(it.id) },
                onNewSession = { LocalTerminalManager.createNewSession(context) },
                onNewBashSession = {
                    LocalTerminalManager.createNewSession(context, forceShell = "bash")
                    android.widget.Toast.makeText(context, L.components.localTerminal.createdBashSession, android.widget.Toast.LENGTH_SHORT).show()
                },
                onToggleKeyboard = toggleKeyboard,
                onRenameSession = { sess, name -> LocalTerminalManager.renameSession(sess.id, name) },
                onCloseSession = { sessionToClose = it },
                onOpenSettings = onOpenSettings
            )
        }

        // TalkBack's "Read terminal output" (the terminal's actions menu)
        a11yTranscript?.let { text ->
            SelectTextSheet(text, onDismiss = { a11yTranscript = null }, title = L.components.a11y.terminalOutput, plainText = true)
        }

        if (sessionToClose != null) {
            val targetSession = sessionToClose!!
            val sessTitle by targetSession.title.collectAsState()
            AlertDialog(
                onDismissRequest = { sessionToClose = null },
                shape = RoundedCornerShape(18.dp),
                containerColor = ai.antigem.theme.AppColors.editorBg,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(ai.antigem.theme.AppColors.danger.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.danger,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = L.components.localTerminal.closeTab,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                },
                text = {
                    Text(
                        text = L.components.localTerminal.areYouSureYouWantTo(sessTitle),
                        fontSize = 13.sp,
                        color = ai.antigem.theme.AppColors.editorText,
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
                        colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.danger),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(L.common.closeTab, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { sessionToClose = null },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(L.common.cancel, color = Color.Gray, fontSize = 13.sp)
                    }
                }
            )
        }
    }
}

@Composable
private fun ExtraKey(
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
        isPressed -> ai.antigem.theme.AppColors.editorLine
        isLocked -> ClaudeTerracotta.copy(alpha = 0.5f)
        isActive -> ClaudeTerracotta.copy(alpha = 0.25f)
        else -> Color.Transparent
    }

    val textColor = when {
        isPressed -> Color.White
        isLocked -> Color.White
        isActive -> ClaudeTerracotta
        // Termux: white keys on its black bar; a light terminal theme keeps the theme's text color
        ai.antigem.theme.AppColors.terminalBg.luminance() < 0.5f -> Color.White
        else -> ai.antigem.theme.AppColors.editorText
    }

    val border = when {
        isLocked -> BorderStroke(1.dp, ClaudeTerracotta)
        isActive -> BorderStroke(0.5.dp, ClaudeTerracotta.copy(alpha = 0.5f))
        else -> null
    }

    Surface(
        modifier = keyModifier
            // TalkBack: the key's full name, a double-tap presses it, CTRL / ALT say their state
            .terminalKeySemantics(label, isActive, isLocked, currentOnClick, currentOnLongClick)
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
                    // Termux's key text (Material button text): Roboto Medium 14sp, the same weight when active
                    fontSize = 14.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.0892857143.em,
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

/**
 * The extra-keys bar's second page (swipe it sideways, like Termux's text input page): a normal text box, so any
 * keyboard works fully there: Bangla/Japanese keyboards' drafts show while typed, suggestions, voice typing, the
 * keyboard's clipboard. Enter (or the button) hands the text to the terminal, see sendToolbarInput.
 */
@Composable
private fun TerminalTextInputBox(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    onSend: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(ai.antigem.theme.AppColors.editorPanel)
            .border(1.dp, ai.antigem.theme.AppColors.editorText.copy(alpha = 0.18f), shape)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text(
                    text = L.components.localTerminal.typeHereEnterPutsItIn,
                    color = ai.antigem.theme.AppColors.editorText.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ClaudeTerracotta),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                // keyboards differ in which action their Enter key reports: all of them send
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSend() },
                    onDone = { onSend() },
                    onGo = { onSend() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
        IconButton(onClick = onSend, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                contentDescription = L.components.localTerminal.putInTheTerminalEnter,
                tint = ClaudeTerracotta,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
