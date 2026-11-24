package ai.antigem.ui.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisController.Phase
import ai.antigem.data.jarvis.JarvisLauncher
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.data.jarvis.JarvisTasks
import ai.antigem.data.voice.AgyVoiceCall
import ai.antigem.data.voice.VoiceCallLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.hypot
import ai.antigem.i18n.L

/**
 * Floating Jarvis (Settings → Jarvis → Screen & look → Float over other apps, off by default): outside AntiGem the
 * orb floats over any app. Tap it to talk, long-press it for the Jarvis panel. While Jarvis works the status pill
 * shows at the bottom of the screen; while it listens or speaks, the card with the waves, the transcript and the send
 * buttons. The panel and the quick settings open as a sheet on the lower part of the screen (it takes the keyboard
 * while you type; Back closes it).
 *
 * Dragging the orb shows a ✕ at the bottom: dropped there, the orb hides until the user talks to Jarvis in the app
 * again (the card still shows while Jarvis listens or speaks, e.g. from the tile). Talking goes through
 * [JarvisLauncher.talkOutside]. Nothing floats while AntiGem is on screen (its own orb is there) or during an
 * Antigravity voice call.
 */
object JarvisFloating : LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
    private const val TAG = "JarvisFloating"

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher { closeSheet() }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    /** Main, not immediate: a tap in a window must not remove that window while it still handles the tap. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var attached = false

    private class Win(val view: View, val params: WindowManager.LayoutParams)
    private var orbWin: Win? = null
    private var cardWin: Win? = null
    private var sheetWin: Win? = null
    /** The ✕ the orb can be dropped on to hide it (up only while the orb is dragged). */
    private var targetWin: Win? = null
    private var orbSize = IntSize.Zero

    private val level = MutableStateFlow(0f)
    /** The dragged orb is over the ✕. */
    private val overTarget = MutableStateFlow(false)

    private data class Look(val phase: Phase, val notice: Boolean, val steps: Boolean, val showActivity: Boolean, val orbSize: String)
    private data class Shown(val orb: Boolean, val card: Boolean, val sheet: Boolean)

    /** Starts following Jarvis (once; MainActivity calls it after [JarvisController.attach]). Main thread. */
    fun attach(context: Context) {
        if (attached || !JarvisController.isAttached) return
        attached = true
        val a = context.applicationContext
        app = a
        wm = a.getSystemService(WindowManager::class.java)
        JarvisLauncher.attach(a)
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // which windows are up
        scope.launch {
            combine(
                combine(JarvisPreferences.floating.state, JarvisPreferences.enabled.state, JarvisPreferences.voiceAssistant.state, JarvisPreferences.floatingHidden.state) { f, e, v, h ->
                    (f && e && v) to h
                },
                VoiceCallLauncher.appVisible,
                AgyVoiceCall.state.map { it.active }.distinctUntilChanged(),
                combine(JarvisController.phase, JarvisController.notice, JarvisController.steps, JarvisPreferences.showActivity.state, JarvisPreferences.orbSize.state) { p, n, s, act, size ->
                    Look(p, n != null, s.isNotEmpty(), act, size)
                },
                combine(JarvisController.panelOpen, JarvisController.quickSettingsOpen) { p, q -> p || q }
            ) { (on, hidden), appVisible, inCall, look, sheet ->
                val base = on && !appVisible && !inCall && Settings.canDrawOverlays(a)
                val expanded = look.phase == Phase.LISTENING || look.phase == Phase.SPEAKING
                val active = expanded || look.phase == Phase.THINKING
                val pill = !expanded && look.showActivity && (look.phase == Phase.THINKING || look.steps)
                Shown(
                    orb = base && !hidden && !(look.orbSize == "hidden" && !active && !pill && !look.notice),
                    // hidden: only while Jarvis listens or speaks (from the tile, the shortcut, the wake phrase)
                    card = base && (expanded || (!hidden && (look.notice || pill))),
                    sheet = base && sheet
                )
            }.distinctUntilChanged().collect { show(it) }
        }

        // the live level for the orb and the card's waves (only while a floating window is up and Jarvis hears or speaks)
        scope.launch {
            JarvisController.phase.collectLatest { p ->
                if (p == Phase.LISTENING || p == Phase.SPEAKING) {
                    while (true) {
                        level.value = if (orbWin != null || cardWin != null) JarvisController.level() else 0f
                        delay(45)
                    }
                } else level.value = 0f
            }
        }

        // hidden with ✕: talking to Jarvis in the app brings the floating orb back
        scope.launch {
            combine(JarvisController.phase, VoiceCallLauncher.appVisible) { p, visible -> p == Phase.LISTENING && visible }
                .distinctUntilChanged()
                .collect { talkingInApp -> if (talkingInApp && JarvisPreferences.floatingHidden.value) JarvisPreferences.floatingHidden.set(false) }
        }
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** A tap on the floating orb (or Reply / Talk): talk to Jarvis with the app in the background. */
    private fun talk() {
        val a = app ?: return
        if (!JarvisController.hasMic()) {
            JarvisController.showProblem(L.jarvis.floating.jarvisNeedsTheMicrophoneAllowIt)
            JarvisLauncher.openApp(a)
            return
        }
        JarvisLauncher.talkOutside()
    }

    private fun openPanel() {
        JarvisLauncher.noteUse()
        JarvisController.panelOpen.value = true
    }

    private fun closeSheet() {
        JarvisController.quickSettingsOpen.value = false
        JarvisController.panelOpen.value = false
    }

    /** As in the app's orb, but what needs the app (the microphone permission) opens it. */
    private val onNoticeAction: (String) -> Unit = { action ->
        JarvisLauncher.noteUse()
        when {
            action == "mic" -> app?.let { JarvisLauncher.openApp(it) }
            action == "agent" -> JarvisController.requestSettings("JARVIS_AGENT")
            action.startsWith("chat:") -> {
                JarvisController.panelOpen.value = false
                JarvisController.vm.selectConversation(action.removePrefix("chat:"))
                JarvisController.vm.requestViewMode("CHAT")
            }
            else -> JarvisController.requestSettings("JARVIS")
        }
        JarvisController.clearNotice()
    }

    /** The orb was dropped on ✕: hidden until the user talks to Jarvis in the app. */
    private fun hideOrb() {
        JarvisPreferences.floatingHidden.set(true)
        app?.let { Toast.makeText(it, L.jarvis.floating.jarvisIsHiddenTalkToJarvis, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------------------------------------ windows

    private fun show(s: Shown) {
        val any = s.orb || s.card || s.sheet
        if (any) lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (s.orb) {
            if (orbWin == null) {
                // the ✕ first (invisible until a drag), so the orb's window stays above it
                targetWin = remove(targetWin)
                targetWin = addTarget()
                orbWin = addOrb()
            }
        } else {
            orbWin = remove(orbWin)
            targetWin = remove(targetWin)
        }
        if (s.card) { if (cardWin == null) cardWin = addCard() } else cardWin = remove(cardWin)
        if (s.sheet) { if (sheetWin == null) sheetWin = addSheet() } else sheetWin = remove(sheetWin)
        if (orbWin == null && cardWin == null && sheetWin == null) lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        flags,
        PixelFormat.TRANSLUCENT
    )

    /** [root] with the owners Compose needs, holding a ComposeView with [content]. */
    private fun host(root: FrameLayout, content: @Composable () -> Unit): FrameLayout {
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeOnBackPressedDispatcherOwner(this)
        root.addView(
            ComposeView(root.context).apply { setContent(content) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return root
    }

    private fun add(root: View, p: WindowManager.LayoutParams): Win? {
        val w = wm ?: return null
        val added = runCatching { w.addView(root, p) }
        if (added.isFailure) {
            Log.w(TAG, "floating window: ${added.exceptionOrNull()?.message}")
            return null
        }
        return Win(root, p)
    }

    private fun remove(win: Win?): Win? {
        win?.let { runCatching { wm?.removeView(it.view) } }
        return null
    }

    private fun update(win: Win) {
        runCatching { wm?.updateViewLayout(win.view, win.params) }
    }

    private fun dp(v: Int): Int = ((app?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    // ---- the orb

    private fun addOrb(): Win? {
        val a = app ?: return null
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply { gravity = Gravity.TOP or Gravity.START }
        placeOrb(p)
        val frame = OrbFrame(a).apply {
            onTap = { talk() }
            onLongPress = { openPanel() }
            onDragStart = { showTarget() }
            onDrag = { dx, dy, x, y -> moveOrb(dx, dy, x, y) }
            onDragEnd = { x, y -> dropOrb(x, y) }
        }
        return add(host(frame) { FloatingOrb(onSize = { orbSize = it; orbWin?.let { w -> placeOrb(w.params); update(w) } }) }, p)
    }

    /** The orb rests on its side at its height, like the app's orb (the same settings: it keeps its place). */
    private fun placeOrb(p: WindowManager.LayoutParams) {
        val m = (app ?: return).resources.displayMetrics
        val w = if (orbSize.width > 0) orbSize.width else dp(52)
        val h = if (orbSize.height > 0) orbSize.height else dp(52)
        val top = dp(64)
        val usable = (m.heightPixels - top - dp(84) - h).coerceAtLeast(1)
        p.x = if (JarvisPreferences.orbSide.value == "left") dp(4) else maxOf(0, m.widthPixels - dp(4) - w)
        p.y = top + (JarvisPreferences.orbY.value.coerceIn(0f, 1f) * usable).toInt()
    }

    private fun moveOrb(dx: Float, dy: Float, rawX: Float, rawY: Float) {
        val win = orbWin ?: return
        val m = (app ?: return).resources.displayMetrics
        win.params.x = (win.params.x + dx.toInt()).coerceIn(0, maxOf(0, m.widthPixels - orbSize.width))
        win.params.y = (win.params.y + dy.toInt()).coerceIn(0, maxOf(0, m.heightPixels - orbSize.height))
        update(win)
        val over = isOverTarget(rawX, rawY)
        if (over != overTarget.value) {
            overTarget.value = over
            if (over) win.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** Let go after a drag: on ✕ it hides; elsewhere it rests on the nearer side at that height (saved for the app's orb too). */
    private fun dropOrb(rawX: Float, rawY: Float) {
        val dropped = isOverTarget(rawX, rawY)
        overTarget.value = false
        targetWin?.view?.visibility = View.INVISIBLE
        val win = orbWin ?: return
        if (dropped) {
            hideOrb()
            return
        }
        val m = (app ?: return).resources.displayMetrics
        val top = dp(64)
        val usable = (m.heightPixels - top - dp(84) - orbSize.height).coerceAtLeast(1)
        JarvisPreferences.orbSide.set(if (win.params.x + orbSize.width / 2 < m.widthPixels / 2) "left" else "right")
        JarvisPreferences.orbY.set(((win.params.y - top).toFloat() / usable).coerceIn(0f, 1f))
        placeOrb(win.params)
        update(win)
    }

    // ---- the ✕ to drop the orb on

    /** The ✕ window: it doesn't take touches and stays invisible until the orb is dragged. */
    private fun addTarget(): Win? {
        val a = app ?: return null
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
        val root = host(FrameLayout(a)) { DropTarget() }
        root.visibility = View.INVISIBLE
        return add(root, p)
    }

    private fun showTarget() {
        targetWin?.view?.visibility = View.VISIBLE
    }

    /** The finger (raw screen position) is close to the ✕. */
    private fun isOverTarget(rawX: Float, rawY: Float): Boolean {
        val t = targetWin?.view ?: return false
        if (t.visibility != View.VISIBLE || t.width == 0) return false
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        return hypot(rawX - cx, rawY - cy) < dp(80)
    }

    // ---- the card (status pill, notice, listening / speaking card) at the bottom

    private fun addCard(): Win? {
        val a = app ?: return null
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(20)
        }
        return add(host(FrameLayout(a)) { FloatingCard() }, p)
    }

    // ---- the sheet (the Jarvis panel or quick settings)

    private fun addSheet(): Win? {
        val a = app ?: return null
        val m = a.resources.displayMetrics
        val height = (m.heightPixels * 0.62f).toInt().coerceAtLeast(dp(380)).coerceAtMost(m.heightPixels - dp(80))
        // focusable: the box to type to Jarvis takes the keyboard, and Back closes the sheet; touches above it still
        // reach the app underneath
        val p = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, height, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        return add(host(BackFrame(a)) { FloatingSheet() }, p)
    }

    // ------------------------------------------------------------------------------------------------ content

    @Composable
    private fun FloatingOrb(onSize: (IntSize) -> Unit) {
        val phase by JarvisController.phase.collectAsState()
        val tasks by JarvisTasks.tasks.collectAsState()
        val mcpProblem by JarvisController.mcpProblem.collectAsState()
        val size by JarvisPreferences.orbSize.state.collectAsState()
        val lvl by level.collectAsState()
        val active = phase == Phase.LISTENING || phase == Phase.SPEAKING || phase == Phase.THINKING
        val idleDp = when (size) { "tiny" -> 30.dp; "normal" -> 54.dp; else -> 40.dp }
        val orbDp = if (active) maxOf(idleDp, 50.dp) else idleDp
        val activeTasks = tasks.count { it.active }
        val attention = mcpProblem || tasks.any { it.state.needsUser }
        Box(Modifier.onSizeChanged(onSize).padding(4.dp).size(orbDp), contentAlignment = Alignment.Center) {
            if (active) JarvisOrb(phase, lvl, orbDp) else IdleOrb(orbDp, activeTasks, attention)
            if (active && (activeTasks > 0 || attention)) TaskBadge(orbDp, activeTasks, attention)
        }
    }

    @Composable
    private fun DropTarget() {
        val over by overTarget.collectAsState()
        val scale by animateFloatAsState(if (over) 1.25f else 1f, tween(140), label = "target")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Box(
                Modifier
                    .size(60.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(if (over) Danger.copy(alpha = 0.9f) else Color(0xE6101827))
                    .border(1.5.dp, if (over) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, L.jarvis.floating.hideJarvis, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(8.dp))
            Text(
                L.jarvis.floating.hideUntilYouTalkToJarvis,
                color = Color.White, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC101827)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun FloatingCard() {
        val phase by JarvisController.phase.collectAsState()
        val heard by JarvisController.heard.collectAsState()
        val said by JarvisController.said.collectAsState()
        val steps by JarvisController.steps.collectAsState()
        val notice by JarvisController.notice.collectAsState()
        val sendProgress by JarvisController.sendProgress.collectAsState()
        val holding by JarvisController.holding.collectAsState()
        val autoNow by JarvisController.autoSendNow.collectAsState()
        val tasks by JarvisTasks.tasks.collectAsState()
        val quiet by JarvisPreferences.quietMode.state.collectAsState()
        val showTranscript by JarvisPreferences.showTranscript.state.collectAsState()
        val showActivity by JarvisPreferences.showActivity.state.collectAsState()
        val hidden by JarvisPreferences.floatingHidden.state.collectAsState()
        val lvl by level.collectAsState()
        val expanded = phase == Phase.LISTENING || phase == Phase.SPEAKING
        val line = when (phase) {
            Phase.LISTENING -> heard
            Phase.SPEAKING -> said
            else -> ""
        }
        val pill = !hidden && !expanded && showActivity && (phase == Phase.THINKING || steps.isNotEmpty())
        Column(
            Modifier.padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!hidden) notice?.let { NoticeChip(it, onNoticeAction) }
            when {
                expanded -> ActivityCard(phase, lvl, steps, line, showActivity, showTranscript, sendProgress, holding, autoNow, quiet, tasks) { talk() }
                pill -> StatusPill(phase, steps)
            }
        }
    }

    @Composable
    private fun FloatingSheet() {
        val quick by JarvisController.quickSettingsOpen.collectAsState()
        if (quick) JarvisQuickSettings(onClose = { JarvisController.quickSettingsOpen.value = false }, floating = true)
        else JarvisPanel(onTalk = { talk() }, onNoticeAction = onNoticeAction, floating = true)
    }

    // ------------------------------------------------------------------------------------------------ touch & keys

    /**
     * The orb's touch handling: drags by raw screen positions (the window moves under the finger), a short touch is a
     * tap and holding it still is a long-press.
     */
    @SuppressLint("ViewConstructor")
    private class OrbFrame(context: Context) : FrameLayout(context) {
        var onTap: () -> Unit = {}
        var onLongPress: () -> Unit = {}
        var onDragStart: () -> Unit = {}
        var onDrag: (dx: Float, dy: Float, rawX: Float, rawY: Float) -> Unit = { _, _, _, _ -> }
        var onDragEnd: (rawX: Float, rawY: Float) -> Unit = { _, _ -> }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                    dragging = false; longFired = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && !longFired && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        dragging = true
                        removeCallbacks(longPress)
                        onDragStart()
                    }
                    if (dragging) {
                        onDrag(e.rawX - lastX, e.rawY - lastY, e.rawX, e.rawY)
                        lastX = e.rawX; lastY = e.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (dragging) onDragEnd(e.rawX, e.rawY) else if (!longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (dragging) onDragEnd(-1f, -1f)
                }
            }
            return true
        }
    }

    /** The sheet's window gets Back (it is focusable): the panel's and quick settings' back handling closes them. */
    private class BackFrame(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) backDispatcher.onBackPressed()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
