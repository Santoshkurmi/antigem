package ai.antigem.ui.voice

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.antigem.MainActivity
import ai.antigem.data.voice.AgyVoiceCall
import ai.antigem.data.voice.AgyVoicePreferences
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.domain.model.Conversation
import ai.antigem.ui.chat.ChatViewModelHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The floating call window (Settings → Antigravity → Voice calls, off by default): drawn over other apps ("Display over
 * other apps") while a call runs and AntiGem is not on screen. Open, it is a panel on the lower half of the screen
 * ([AgyVoiceFloatingPanel]); minimized, a circle that can be dragged and docks to a side ([AgyVoiceFloatingCircle]).
 *
 * A call started from the Voice call tile or shortcut opens as the panel; leaving the app during a call shows the
 * circle; a failed call opens the panel to say why. After that the user's last choice holds for the call. It follows
 * the call itself (not the call service, which stops as soon as a call fails), so a failure stays up until Close.
 */
object AgyVoiceBubble : LifecycleOwner, SavedStateRegistryOwner {
    private const val TAG = "AgyVoiceCall"

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Main, not immediate: a tap in the window must not remove that window while it is still handling the tap. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var watchJob: Job? = null
    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private val _expanded = MutableStateFlow(false)
    /** The panel is open (false: the circle). */
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    /** The circle's size, and where it sits (kept for the next call). */
    private var circleSize = IntSize.Zero
    private var dockRight = true
    private var lastY: Int? = null

    /** The call it is showing (its start time), and what that call has done so far. */
    private var callKey = 0L
    private var lastPhase = AgyVoiceCall.Phase.IDLE
    private var hiddenByUser = false
    private var openNext = false

    private val notRunning = MutableStateFlow(false)
    private val noChat = MutableStateFlow<Conversation?>(null)

    /** Starts following calls (once per process; the call service calls it when a call starts). Main thread. */
    fun attach(context: Context) {
        if (watchJob != null) return
        val a = context.applicationContext
        app = a
        wm = a.getSystemService(WindowManager::class.java)
        AgyVoicePreferences.init(a)
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        watchJob = scope.launch {
            combine(AgyVoiceCall.state, AgyVoicePreferences.bubble, VoiceCallLauncher.appVisible) { st, on, appVisible ->
                Triple(st, on, appVisible)
            }.collect { (st, on, appVisible) -> follow(st, on, appVisible) }
        }
    }

    /** The next call opens as the panel (started from the tile or shortcut, or "Try again" in the panel). */
    fun openPanelForNextCall() {
        openNext = true
    }

    private fun follow(st: AgyVoiceCall.State, on: Boolean, appVisible: Boolean) {
        if (st.active && st.startedAtWall != callKey) {
            // a new call
            callKey = st.startedAtWall
            hiddenByUser = false
            setExpanded(openNext)
            openNext = false
        }
        if (st.phase == AgyVoiceCall.Phase.FAILED && lastPhase != AgyVoiceCall.Phase.FAILED) {
            // say why, even when it was hidden
            hiddenByUser = false
            setExpanded(true)
        }
        lastPhase = st.phase
        // the app shows the call itself; leaving it again shows the circle
        if (appVisible && st.active && st.phase != AgyVoiceCall.Phase.FAILED) setExpanded(false)
        if (st.active && on && !appVisible && !hiddenByUser) show() else hide()
    }

    private fun setExpanded(open: Boolean) {
        if (_expanded.value == open) return
        _expanded.value = open
        val v = view ?: return
        val p = params ?: return
        layout(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** The window's size and place: the panel on the lower half, or the circle against its side. */
    private fun layout(p: WindowManager.LayoutParams) {
        val screen = (app ?: return).resources.displayMetrics
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (_expanded.value) {
            val dp = screen.density
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = (screen.heightPixels * 0.52f).toInt()
                .coerceAtLeast((360 * dp).toInt())
                .coerceAtMost(screen.heightPixels - (80 * dp).toInt())
            p.gravity = Gravity.BOTTOM
            p.x = 0
            p.y = 0
            // like the call screen in the app: the screen stays on while the panel is up
            p.flags = baseFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.TOP or Gravity.START
            p.x = if (dockRight) maxOf(0, screen.widthPixels - circleSize.width) else 0
            p.y = (lastY ?: (screen.heightPixels / 3)).coerceIn(0, maxOf(0, screen.heightPixels - circleSize.height))
            p.flags = baseFlags
        }
    }

    private fun show() {
        val a = app ?: return
        val w = wm ?: return
        if (view != null || !Settings.canDrawOverlays(a)) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        layout(p)
        val v = ComposeView(a).apply {
            setViewTreeLifecycleOwner(this@AgyVoiceBubble)
            setViewTreeSavedStateRegistryOwner(this@AgyVoiceBubble)
            setContent {
                val open by expanded.collectAsState()
                val vm = remember { ChatViewModelHolder.peek() }
                val running by (vm?.isCurrentChatActivelyRunning ?: notRunning).collectAsState()
                val chat by (vm?.currentConversation ?: noChat).collectAsState()
                val st by AgyVoiceCall.state.collectAsState()
                if (open) {
                    AgyVoiceFloatingPanel(
                        agentWorking = running && chat?.id == st.conversationId,
                        onMinimize = { setExpanded(false) },
                        onHide = {
                            hiddenByUser = true
                            scope.launch { hide() }
                        },
                        onOpenApp = { openApp() },
                        onRetry = {
                            openPanelForNextCall()
                            AgyVoiceCall.retry(a)
                        }
                    )
                } else {
                    AgyVoiceFloatingCircle(
                        onTap = { setExpanded(true) },
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onDragEnd = { dock() },
                        onSize = {
                            circleSize = it
                            placeCircle()
                        }
                    )
                }
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val added = runCatching { w.addView(v, p) }
        if (added.isFailure) {
            Log.w(TAG, "floating call window: ${added.exceptionOrNull()?.message}")
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            return
        }
        view = v
        params = p
    }

    private fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { wm?.removeView(v) }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (_expanded.value) return
        val v = view ?: return
        val p = params ?: return
        val screen = (app ?: return).resources.displayMetrics
        p.x = (p.x + dx.toInt()).coerceIn(0, maxOf(0, screen.widthPixels - circleSize.width))
        p.y = (p.y + dy.toInt()).coerceIn(0, maxOf(0, screen.heightPixels - circleSize.height))
        lastY = p.y
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** Let go after a drag: the circle docks to the nearer side. */
    private fun dock() {
        val p = params ?: return
        val screen = (app ?: return).resources.displayMetrics
        dockRight = p.x + circleSize.width / 2 > screen.widthPixels / 2
        lastY = p.y
        placeCircle()
    }

    /** Keeps the circle against its side and on screen (its size is known after it is drawn). */
    private fun placeCircle() {
        if (_expanded.value) return
        val v = view ?: return
        val p = params ?: return
        layout(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** "Open" in the panel: the app shows the call; leaving it again shows the circle. */
    private fun openApp() {
        val a = app ?: return
        setExpanded(false)
        AgyVoiceCall.setMinimized(false)
        val intent = Intent(a, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { a.startActivity(intent) }.onFailure { Log.w(TAG, "open app: ${it.message}") }
    }
}
