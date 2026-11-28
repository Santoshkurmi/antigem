package ai.antigem.ui.netcap

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import ai.antigem.MainActivity
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapManager
import ai.antigem.data.netcap.NetcapVpnService
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.theme.GeminiTheme
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
 * The capture bubble: while a capture runs and AntiGem is not on screen, a small circle over the other apps shows the
 * request count (and paused breakpoints). Tapped, it opens a panel: pause, stop, clear, the latest requests, the
 * paused ones (continue / drop), and a way back to the app. Needs "Display over other apps"; on by default.
 */
object NetFloating : LifecycleOwner, SavedStateRegistryOwner {
    private const val TAG = "NetFloating"
    private const val PREFS = "netcap_prefs"
    private const val KEY_ON = "floating_bubble"

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var watchJob: Job? = null
    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var watching = false

    private val _expanded = MutableStateFlow(false)
    private val enabled = MutableStateFlow(true)
    private val enabledCompose = mutableStateOf(true)
    private var hiddenByUser = false
    private var circleSize = IntSize.Zero
    private var dockRight = true
    private var lastY: Int? = null

    private val _pending = MutableStateFlow<NetDest?>(null)
    /** A place in the Network page to open (from the bubble); the page takes it and clears it. */
    internal val pending: StateFlow<NetDest?> = _pending.asStateFlow()

    internal fun consumePending() { _pending.value = null }

    private val _pageWanted = MutableStateFlow(false)
    /** The bubble or the notification opened the app: MainActivity shows the Network page, then calls [pageShown]. */
    val pageWanted: StateFlow<Boolean> = _pageWanted.asStateFlow()

    fun pageShown() { _pageWanted.value = false }

    /** Opens the Network page (at [dest]) the next time the app is on screen: the notification's tap. */
    internal fun requestPage(dest: NetDest = NetDest.Home) {
        _pending.value = dest
        _pageWanted.value = true
    }

    /** For MainActivity: true when [intent] is the capture notification's tap (the Network page should show). */
    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_OPEN, false) != true) return false
        intent.removeExtra(EXTRA_OPEN) // once: not again when the activity is recreated
        _pending.value = NetDest.Home
        return true
    }

    const val EXTRA_OPEN = "ai.antigem.netcap.OPEN_PAGE"

    /** Starts following the capture (once per process). Main thread. */
    fun attach(context: Context) {
        if (watchJob != null) return
        val a = context.applicationContext
        app = a
        wm = a.getSystemService(WindowManager::class.java)
        val on = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
        enabled.value = on
        enabledCompose.value = on
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        watchJob = scope.launch {
            combine(NetcapManager.state, NetStore.state, enabled, VoiceCallLauncher.appVisible) { m, s, en, visible ->
                val capturing = m.capturing // the VPN service's own state (the engine's can lag behind)
                Triple(capturing, en, visible)
            }.collect { (capturing, en, visible) ->
                if (!capturing) hiddenByUser = false
                if (capturing && en && !visible && !hiddenByUser) show() else hide()
            }
        }
    }

    /** In the page: makes sure the bubble follows captures. */
    @Composable
    internal fun Attach() {
        val context = LocalContext.current
        LaunchedEffect(Unit) { attach(context) }
    }

    @Composable
    internal fun enabledState(): Boolean = enabledCompose.value

    /** The menu switch; asks for "Display over other apps" when it is missing. */
    internal fun toggleEnabled() {
        val a = app ?: return
        val on = !enabled.value
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        enabled.value = on
        enabledCompose.value = on
        if (on && !Settings.canDrawOverlays(a)) {
            runCatching {
                a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun setExpanded(open: Boolean) {
        if (_expanded.value == open) return
        _expanded.value = open
        val v = view ?: return
        val p = params ?: return
        layout(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun layout(p: WindowManager.LayoutParams) {
        val screen = (app ?: return).resources.displayMetrics
        val dp = screen.density
        p.gravity = Gravity.TOP or Gravity.START
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (_expanded.value) {
            p.width = (screen.widthPixels - (24 * dp).toInt()).coerceAtMost((380 * dp).toInt())
            p.height = (screen.heightPixels * 0.5f).toInt().coerceAtLeast((340 * dp).toInt())
            p.x = if (dockRight) screen.widthPixels - p.width - (12 * dp).toInt() else (12 * dp).toInt()
            p.y = (lastY ?: (screen.heightPixels / 4)).coerceIn((24 * dp).toInt(), maxOf(0, screen.heightPixels - p.height - (24 * dp).toInt()))
        } else {
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.x = if (dockRight) maxOf(0, screen.widthPixels - circleSize.width) else 0
            p.y = (lastY ?: (screen.heightPixels / 3)).coerceIn(0, maxOf(0, screen.heightPixels - circleSize.height))
        }
    }

    private fun show() {
        val a = app ?: return
        val w = wm ?: return
        if (view != null || !Settings.canDrawOverlays(a)) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        _expanded.value = false
        layout(p)
        val v = ComposeView(a).apply {
            setViewTreeLifecycleOwner(this@NetFloating)
            setViewTreeSavedStateRegistryOwner(this@NetFloating)
            setContent {
                GeminiTheme {
                    val open by _expanded.collectAsState()
                    if (open) Panel() else Circle()
                }
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (runCatching { w.addView(v, p) }.isFailure) {
            Log.w(TAG, "could not show the capture bubble")
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            return
        }
        view = v
        params = p
        if (!watching) { watching = true; NetStore.watch() }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { wm?.removeView(v) }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        if (watching) { watching = false; NetStore.unwatch() }
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

    private fun dock() {
        val v = view ?: return
        val p = params ?: return
        val screen = (app ?: return).resources.displayMetrics
        dockRight = p.x + circleSize.width / 2 > screen.widthPixels / 2
        lastY = p.y
        layout(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** Back to the app, at [dest] in the Network page. */
    private fun openApp(dest: NetDest) {
        val a = app ?: return
        setExpanded(false)
        requestPage(dest)
        runCatching {
            a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    @Composable
    private fun Circle() {
        val st by NetStore.state.collectAsState()
        val mgr by NetcapManager.state.collectAsState()
        val paused by NetStore.paused.collectAsState()
        val count = st.sessionFlows // the live count (the bubble keeps the stream open)
        val ring = when {
            paused.isNotEmpty() -> AppColors.danger
            st.capture.paused -> AppColors.warning
            else -> AppColors.success
        }
        Box(
            Modifier.padding(6.dp)
                .onSizeChanged { circleSize = IntSize(it.width + 0, it.height + 0); layoutLater() }
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { dock() }) { change, drag -> change.consume(); moveBy(drag.x, drag.y) }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { setExpanded(true) }, onLongPress = { hiddenByUser = true; hide() })
                }
        ) {
            Box(
                Modifier.size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(AppColors.card).border(2.5.dp, ring, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.SwapVert, null, tint = ring, modifier = Modifier.size(18.dp))
                    Text(if (count > 9999) "${count / 1000}k" else "$count", color = AppColors.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (paused.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(AppColors.danger),
                    contentAlignment = Alignment.Center
                ) { Text("${paused.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    private fun layoutLater() {
        scope.launch {
            val v = view ?: return@launch
            val p = params ?: return@launch
            if (_expanded.value) return@launch
            layout(p)
            runCatching { wm?.updateViewLayout(v, p) }
        }
    }

    @Composable
    private fun Panel() {
        val context = LocalContext.current
        val st by NetStore.state.collectAsState()
        val mgr by NetcapManager.state.collectAsState()
        val rows by NetStore.rows.collectAsState()
        val paused by NetStore.paused.collectAsState()
        val count = st.sessionFlows // the live count (the bubble keeps the stream open)
        Column(
            Modifier.fillMaxSize().padding(4.dp).shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                .background(AppColors.surface).border(1.dp, AppColors.border, RoundedCornerShape(18.dp))
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (st.capture.paused) AppColors.warning else AppColors.success))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (st.capture.paused) L.netcap.ui.paused else L.netcap.screen.capturing, color = AppColors.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(L.netcap.ui.requestsCount(count), color = AppColors.textSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = { hiddenByUser = true; hide() }) { Icon(Icons.Outlined.VisibilityOff, L.netcap.ui.hideBubble, tint = AppColors.textSecondary) }
                IconButton(onClick = { setExpanded(false) }) { Icon(Icons.Filled.Close, L.common.close, tint = AppColors.onBackground) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { scope.launch { netTry { NetStore.pauseCapture(!st.capture.paused) } } }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(if (st.capture.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (st.capture.paused) L.netcap.ui.resume else L.common.pause, fontSize = 13.sp)
                }
                FilledTonalButton(
                    onClick = { NetcapVpnService.stop(context) }, contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.dangerContainer, contentColor = AppColors.danger)
                ) { Icon(Icons.Filled.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(L.common.stop, fontSize = 13.sp) }
                IconButton(onClick = {
                    scope.launch {
                        netTry { NetStore.clearAll() }?.let { android.widget.Toast.makeText(context, L.netcap.ui.cleared, android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }) { Icon(Icons.Outlined.DeleteSweep, L.netcap.ui.clearList, tint = AppColors.onBackground) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { openApp(NetDest.Home) }) { Icon(Icons.Outlined.OpenInNew, L.netcap.ui.openApp, tint = AppColors.primary) }
            }
            HorizontalDivider(color = AppColors.border)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(paused, key = { "p" + it.id }) { p ->
                    Column(Modifier.fillMaxWidth().background(AppColors.warningContainer.copy(alpha = 0.45f)).clickable { openApp(NetDest.PausedEdit(p.id)) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tag(if (p.isResponse) L.netcap.ui.phaseResponse else L.netcap.ui.phaseRequest, AppColors.warning, filled = true)
                            Spacer(Modifier.width(6.dp))
                            Text(p.url, color = AppColors.onBackground, fontFamily = Mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(L.netcap.ui.secondsLeft(rememberSecondsLeft(p)), color = AppColors.textSecondary, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { scope.launch { netTry { resolvePaused(p.id, "drop") } } }) { Text(L.netcap.ui.drop, color = AppColors.danger, fontSize = 13.sp) }
                            TextButton(onClick = { openApp(NetDest.PausedEdit(p.id)) }) { Text(L.common.edit, fontSize = 13.sp) }
                            TextButton(onClick = { scope.launch { netTry { resolvePaused(p.id, "continue") } } }) { Text(L.netcap.ui.continueButton, fontSize = 13.sp) }
                        }
                    }
                }
                items(rows.take(60), key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { openApp(NetDest.Detail(r.id)) }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(r.app, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Tag(if (r.kind == "http") r.method.ifEmpty { "?" } else r.kind.uppercase(), methodColor(r.method, r.kind))
                        Spacer(Modifier.width(4.dp))
                        if (r.status > 0) Tag("${r.status}", statusColor(r.status, r.error)) else if (!r.done) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(r.host + r.pathAndQuery, color = AppColors.onBackground, fontFamily = Mono, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
