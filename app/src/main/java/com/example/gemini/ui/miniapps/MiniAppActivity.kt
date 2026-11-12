package com.example.gemini.ui.miniapps

import android.app.ActivityManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.gemini.R
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.ThemeManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** A native dialog a page asked for; [result] completes when the user answers. */
sealed class NativeDialog {
    val result = CompletableDeferred<Any?>()
    class Alert(val title: String, val message: String, val ok: String) : NativeDialog()
    class Confirm(val title: String, val message: String, val ok: String, val cancel: String, val destructive: Boolean) : NativeDialog()
    class Prompt(val title: String, val message: String, val value: String, val placeholder: String, val ok: String, val cancel: String) : NativeDialog()
    class SheetItem(val id: String, val label: String, val icon: String, val destructive: Boolean)
    class Sheet(val title: String, val items: List<SheetItem>) : NativeDialog()
    /** Permission prompt for one or more permissions; result = Map<String, Boolean>. */
    class Permissions(val app: MiniApp, val names: List<String>, val firstLaunch: Boolean) : NativeDialog()
    /** The typed "iknowiamstupid" gate for broad file access; result = Boolean. [name]: a permission or "picked:<path>". */
    class TypedConfirm(val app: MiniApp, val name: String) : NativeDialog()
    /** The native photo / video viewer; result = the index shown when it was closed. */
    class MediaViewer(val items: List<MediaItem>, val index: Int) : NativeDialog()
    /** An MCP tool call that needs the user's OK; result = "once" | "always" | null (denied). */
    class McpCall(val app: MiniApp, val server: String, val tool: String, val description: String, val args: String) : NativeDialog()
}

/**
 * A mini app on screen. The root page ([MiniAppActivity]) is its own task (own Recents card, icon and color);
 * pages opened with AntiGem.nav.push are [MiniAppPageActivity] in the same task, so back/swipe and the
 * slide transitions are the system's.
 */
open class MiniAppActivity : ComponentActivity(), MiniAppHost {
    companion object {
        const val EXTRA_APP_ID = "mini_app_id"
        const val EXTRA_PATH = "mini_app_path"

        fun rootIntent(context: Context, appId: String): Intent = Intent(context, MiniAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("antigem-miniapp://app/" + Uri.encode(appId)))

        /**
         * Opens the app like a launcher icon should: an app already running comes forward exactly as it was (the page
         * it was on, pushed pages and all), like from Recents; otherwise it starts.
         * (Starting the root intent instead would clear the task: documentLaunchMode="intoExisting" restarts it.)
         */
        fun launch(context: Context, appId: String) {
            val uri = Uri.parse("antigem-miniapp://app/" + Uri.encode(appId))
            val task = runCatching {
                context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { t ->
                    val base = t.taskInfo.baseIntent
                    base.data == uri && base.component?.className == MiniAppActivity::class.java.name
                }
            }.getOrNull()
            if (task != null && runCatching { task.moveToFront() }.isSuccess) return
            val i = rootIntent(context, appId)
            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    override val context: Context get() = this
    override val isForeground: Boolean get() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    override val isPushed = false
    lateinit var app: MiniApp
        private set
    private lateinit var page: MiniAppPage
    final override var isDark by mutableStateOf(false)
        private set
    final override var backgroundColor = 0
        private set

    private val dialogs = mutableStateListOf<NativeDialog>()
    private var splash by mutableStateOf(true)
    private var statusColor by mutableStateOf<Int?>(null)
    private var consoleOpen by mutableStateOf(false)
    private var devMenuOpen by mutableStateOf(false)
    private var infoOpen by mutableStateOf(false)
    private var devMode by mutableStateOf(true)
    /** Required permissions that are missing: the app is not loaded, a screen explains why. */
    private var blocked by mutableStateOf<List<String>?>(null)
    private var cantRun by mutableStateOf<String?>(null)
    private var pageLoaded = false
    private var folderPick by mutableStateOf<Pair<CompletableDeferred<Pair<String, com.example.gemini.data.files.ExplorerSide>?>, String>?>(null)
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // ---- activity results as suspend functions
    private var pickCont: CancellableContinuation<List<Uri>>? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> pickCont?.resume(listOfNotNull(uri)); pickCont = null }
    private val pickMany = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> pickCont?.resume(uris); pickCont = null }
    private var permCont: CancellableContinuation<Boolean>? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permCont?.resume(r.values.all { it }); permCont = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_APP_ID) ?: intent.data?.lastPathSegment
        val found = id?.let { MiniApps.find(this, it) }
        if (found == null) {
            Toast.makeText(this, "Mini app not found: $id", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        app = found
        isDark = computeDark()
        backgroundColor = themeColor("background")
        devMode = getSharedPreferences("mini_apps", MODE_PRIVATE).getBoolean("dev_mode", true)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundColor))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        applyTaskDescription()

        page = MiniAppPage(this, app, intent.getStringExtra(EXTRA_PATH) ?: app.entry)
        applySystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) { hideFullscreen(); return }
                page.handleBack { handled ->
                    if (handled) return@handleBack
                    // like a real app: the root page goes to the background (instant to reopen), pushed pages close
                    if (isPushed) finish() else moveTaskToBack(true)
                }
            }
        })

        setContent { Screen() }

        // a modified installed app, or a development app with Developer mode off, doesn't run at all
        cantRun = when {
            app.tampered -> "${app.name} was changed after it was installed: its files are no longer the ones ${app.developer} signed. Uninstall it and install it again from its developer."
            !app.signed && !MiniApps.devMode(this) -> "${app.name} is a development app (unsigned, from ~/mini-apps). Development apps only run with Developer mode on (Mini Apps → </>)."
            else -> null
        }
        if (cantRun != null) { splash = false; if (isPushed) finish() }
        else if (isPushed) page.load() else lifecycleScope.launch {
            val pending = MiniAppPermissions.pending(this@MiniAppActivity, app)
            if (pending.isNotEmpty()) {
                val answers = showDialog(NativeDialog.Permissions(app, pending, firstLaunch = true)) as? Map<*, *>
                pending.forEach { MiniAppPermissions.set(this@MiniAppActivity, app, it, answers?.get(it) == true) }
            }
            loadIfAllowed()
        }
    }

    /** Required permissions first: without them the app doesn't run at all, a native screen says why. */
    private fun loadIfAllowed() {
        val missing = MiniAppPermissions.missingRequired(this, app)
        if (missing.isNotEmpty()) {
            blocked = missing
            splash = false
            if (pageLoaded) { page.webView.stopLoading(); page.webView.loadUrl("about:blank"); pageLoaded = false }
            return
        }
        blocked = null
        if (!pageLoaded) { pageLoaded = true; page.load() }
    }

    private fun reviewBlocked() = lifecycleScope.launch {
        val missing = blocked ?: return@launch
        val answers = showDialog(NativeDialog.Permissions(app, missing, firstLaunch = true)) as? Map<*, *>
        missing.forEach { MiniAppPermissions.set(this@MiniAppActivity, app, it, answers?.get(it) == true) }
        loadIfAllowed()
    }

    private fun computeDark(): Boolean {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (runCatching { AuthPreferences(applicationContext).getThemeModeSync() }.getOrDefault("SYSTEM")) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }
    }

    private fun themeColor(token: String): Int =
        (ThemeManager.resolvedColors(ThemeManager.themeFor(isDark))[token] ?: if (isDark) 0xFF121212 else 0xFFFFFFFF).toInt()

    private fun applyTaskDescription() {
        val color = (app.themeColor ?: backgroundColor) or 0xFF000000.toInt()
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(app.name, MiniApps.iconBitmap(app, 144), color))
    }

    private fun isLight(color: Int): Boolean {
        val r = android.graphics.Color.red(color); val g = android.graphics.Color.green(color); val b = android.graphics.Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }

    /** Opaque system bars in the app's color (not edge-to-edge), so the keyboard resizes the WebView natively. */
    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val status = statusColor ?: app.themeColor ?: backgroundColor
        window.statusBarColor = status
        window.navigationBarColor = backgroundColor
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.isAppearanceLightStatusBars = isLight(status)
        c.isAppearanceLightNavigationBars = isLight(backgroundColor)
    }

    // ---------------------------------------------------------------- called by the page

    override fun onPageVisible(p: MiniAppPage) { if (p === page) splash = false }

    override fun onRendererGone(p: MiniAppPage) {
        if (p !== page) return
        // a renderer crash must not take AntiGem down: start this page again
        startActivity(Intent(intent))
        finish()
    }

    override fun onManifestChanged(newApp: MiniApp) {
        app = newApp
        applyTaskDescription(); applySystemBars()
    }

    override fun toast(text: String, long: Boolean) = Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    override suspend fun showDialog(d: NativeDialog): Any? {
        dialogs += d
        return try { d.result.await() } finally { dialogs -= d }
    }

    override suspend fun requestPermission(app: MiniApp, name: String): MiniAppPermissions.State {
        val state = MiniAppPermissions.state(this, app, name)
        if (state == MiniAppPermissions.State.GRANTED || state == MiniAppPermissions.State.UNDECLARED) return state
        // "storage" asks for every storage folder not granted yet, each with its own switch
        val names = if (name == "storage" && name !in app.permissions)
            app.permissions.keys.filter { it.startsWith("storage:") && MiniAppPermissions.state(this, app, it) != MiniAppPermissions.State.GRANTED }
        else listOf(name)
        val answers = showDialog(NativeDialog.Permissions(app, names, firstLaunch = names.size > 1)) as? Map<*, *>
        names.forEach { MiniAppPermissions.set(this, app, it, answers?.get(it) == true) }
        page.pushPermissions()
        if (!isPushed) loadIfAllowed()
        return MiniAppPermissions.state(this, app, name)
    }

    /** AntiGem's own file explorer, to pick a folder on this phone; the real (canonical) folder, or null. */
    override suspend fun pickFolder(title: String): java.io.File? {
        val d = CompletableDeferred<Pair<String, com.example.gemini.data.files.ExplorerSide>?>()
        folderPick = d to title
        val picked = try { d.await() } finally { folderPick = null } ?: return null
        val (path, side) = picked
        val remote = com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode
        if (remote && side == com.example.gemini.data.files.ExplorerSide.BRIDGE)
            throw com.example.gemini.data.miniapps.MiniAppError("NotAllowedError", "Pick a folder on this phone, not on the PC / server")
        // the explorer's "~" is the Termux home on its main side and shared storage on the phone tab
        val file = when {
            path == "~" || path.startsWith("~/") -> java.io.File(
                if (side == com.example.gemini.data.files.ExplorerSide.PHONE) android.os.Environment.getExternalStorageDirectory() else MiniApps.home(this),
                path.removePrefix("~").trimStart('/'),
            )
            else -> java.io.File(path)
        }
        return file.canonicalFile
    }

    override suspend fun androidPermissions(perms: Array<String>): Boolean {
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
        return suspendCancellableCoroutine { c -> permCont = c; permLauncher.launch(perms) }
    }

    private var captureCont: kotlinx.coroutines.CancellableContinuation<Uri?>? = null
    private var captureUri: Uri? = null
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> captureCont?.resume(if (ok) captureUri else null); captureCont = null }
    private val takeVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> captureCont?.resume(if (ok) captureUri else null); captureCont = null }

    override suspend fun capture(video: Boolean): Uri? = suspendCancellableCoroutine { c ->
        captureCont?.resume(null)
        val dir = java.io.File(cacheDir, "miniapp-capture").also { d -> d.mkdirs(); d.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() } }
        val f = java.io.File(dir, (if (video) "VID_" else "IMG_") + System.currentTimeMillis() + if (video) ".mp4" else ".jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        captureUri = uri
        captureCont = c
        runCatching { if (video) takeVideo.launch(uri) else takePicture.launch(uri) }.onFailure { captureCont = null; if (c.isActive) c.resume(null) }
    }

    override suspend fun pickFiles(accept: String, multiple: Boolean): List<Uri> = suspendCancellableCoroutine { c ->
        pickCont?.resume(emptyList())
        pickCont = c
        if (multiple) pickMany.launch(accept) else pickOne.launch(accept)
    }

    private fun dialogTheme() = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    override suspend fun pickDate(value: String, title: String): String? = suspendCancellableCoroutine { c ->
        val cal = Calendar.getInstance()
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(value)?.destructured?.let { (y, m, d) -> cal.set(y.toInt(), m.toInt() - 1, d.toInt()) }
        var done = false
        val dlg = DatePickerDialog(this, dialogTheme(), { _, y, m, d -> done = true; c.resume("%04d-%02d-%02d".format(y, m + 1, d)) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        if (title.isNotBlank()) dlg.setTitle(title)
        dlg.setOnDismissListener { if (!done && c.isActive) c.resume(null) }
        dlg.show()
    }

    override suspend fun pickTime(value: String): String? = suspendCancellableCoroutine { c ->
        val cal = Calendar.getInstance()
        var h = cal.get(Calendar.HOUR_OF_DAY); var min = cal.get(Calendar.MINUTE)
        Regex("^(\\d{1,2}):(\\d{2})").find(value)?.destructured?.let { (hh, mm) -> h = hh.toInt(); min = mm.toInt() }
        var done = false
        val dlg = TimePickerDialog(this, dialogTheme(), { _, hh, mm -> done = true; c.resume("%02d:%02d".format(hh, mm)) }, h, min, android.text.format.DateFormat.is24HourFormat(this))
        dlg.setOnDismissListener { if (!done && c.isActive) c.resume(null) }
        dlg.show()
    }

    override fun setStatusBar(color: String) {
        statusColor = MiniApps.parseColor(color)
        applySystemBars()
    }

    override fun push(path: String) {
        startActivity(Intent(this, MiniAppPageActivity::class.java).putExtra(EXTRA_APP_ID, app.id).putExtra(EXTRA_PATH, path.trimStart('/').ifBlank { app.entry }))
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.miniapp_push_in, R.anim.miniapp_push_out)
    }

    override fun closePage() = if (isPushed) finish() else moveTaskToBack(true).let {}

    override fun closeApp() = finishAndRemoveTask()

    override fun showConsole() { consoleOpen = true }

    override fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        hideFullscreen()
        fullscreenView = view; fullscreenCallback = callback
        (window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.setBackgroundColor(android.graphics.Color.BLACK)
        WindowCompat.getInsetsController(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun hideFullscreen() {
        val v = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(v)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- lifecycle

    private var wasPaused = false

    override fun onResume() {
        super.onResume()
        if (!::page.isInitialized) return
        // a required permission may have been turned off meanwhile (App info, the launcher)
        if (isPushed) { if (MiniAppPermissions.missingRequired(this, app).isNotEmpty()) { finish(); return } }
        else if (pageLoaded || blocked != null) loadIfAllowed()
        val dark = computeDark()
        if (dark != isDark) onThemeChanged(dark)
        // "resume" means back on screen after being away (the first open is the page's own load)
        if (wasPaused) page.emit("lifecycle", "resume")
        wasPaused = false
        page.flushPendingEvents()
    }

    // ---- 'network' and 'battery' events while the app is on screen (only when something changed)
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastNet = ""
    private var lastBattery = ""
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val b = com.example.gemini.data.miniapps.MiniAppDeviceStatus.battery(c, i)
            val key = "${b.opt("level")}|${b.optBoolean("charging")}|${b.optBoolean("saver")}"
            if (key != lastBattery) { if (lastBattery.isNotEmpty() && ::page.isInitialized) page.emit("battery", b); lastBattery = key }
        }
    }

    private fun emitNetwork() {
        val n = com.example.gemini.data.miniapps.MiniAppDeviceStatus.network(this)
        val key = n.toString()
        if (key != lastNet) { if (lastNet.isNotEmpty() && ::page.isInitialized) page.emit("network", n); lastNet = key }
    }

    override fun onStart() {
        super.onStart()
        if (!::page.isInitialized) return
        lastNet = com.example.gemini.data.miniapps.MiniAppDeviceStatus.network(this).toString()
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = runOnUiThread { emitNetwork() }
            override fun onLost(network: android.net.Network) = runOnUiThread { emitNetwork() }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = runOnUiThread { emitNetwork() }
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStop() {
        netCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        if (::page.isInitialized) { page.emit("lifecycle", "pause"); wasPaused = true }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::page.isInitialized) return
        val dark = computeDark()
        if (dark != isDark) onThemeChanged(dark)
    }

    private fun onThemeChanged(dark: Boolean) {
        isDark = dark
        backgroundColor = themeColor("background")
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(backgroundColor))
        page.webView.setBackgroundColor(backgroundColor)
        applySystemBars(); applyTaskDescription()
        page.pushTheme()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        if (isPushed) overridePendingTransition(R.anim.miniapp_pop_in, R.anim.miniapp_pop_out)
    }

    override fun onDestroy() {
        if (::page.isInitialized) page.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        val scheme = ThemeManager.schemeOf(ThemeManager.themeFor(isDark))
        // theme edits (Settings → Themes, theme_mcp) reach the open app live
        LaunchedEffect(ThemeManager.lightId, ThemeManager.darkId, ThemeManager.customThemes) {
            backgroundColor = themeColor("background"); applySystemBars(); page.pushTheme()
        }
        MaterialTheme(colorScheme = scheme, typography = com.example.gemini.theme.Typography) {
            Box(Modifier.fillMaxSize().background(Color(backgroundColor))) {
                AndroidView(factory = { page.webView.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = Modifier.fillMaxSize())

                AnimatedVisibility(visible = splash && !isPushed, exit = fadeOut()) { Splash() }
                // never leave a blank splash up if the page is slow to paint
                LaunchedEffect(Unit) { delay(4000); splash = false }

                if (devMode && !app.signed) DevBubble()

                dialogs.lastOrNull()?.let { d -> NativeDialogHost(d) }
                if (consoleOpen) ConsoleSheet(app.id, onEval = { code, done -> page.evaluate(code, done) }, onDevtools = { consoleOpen = false; page.openDevtools() }, onDismiss = { consoleOpen = false })
                if (devMenuOpen) DevMenu()
                if (infoOpen) AppInfoSheet(app, onDismiss = { infoOpen = false; page.pushPermissions(); if (!isPushed) loadIfAllowed() }, onClearData = { page.reload() })
                blocked?.let { BlockedScreen(it) }
                cantRun?.let { CantRunScreen(it) }
                folderPick?.let { (d, title) ->
                    com.example.gemini.ui.files.FileExplorerDialog(
                        stateKey = "miniapp_pick_folder",
                        onDismiss = { d.complete(null) },
                        title = title,
                        confirmLabel = "Give access to this folder",
                        onConfirmFolder = { path -> d.complete(path to com.example.gemini.data.files.ExplorerSide.BRIDGE) },
                        onConfirmFolderAt = { path, side -> d.complete(path to side) },
                        showClipboard = false,
                        allowPhoneTab = true,
                        initialSide = com.example.gemini.data.files.ExplorerSide.PHONE,
                    )
                }
            }
        }
    }

    /** The app must not run: modified after install, or a development app with Developer mode off. */
    @Composable
    private fun CantRunScreen(message: String) {
        val icon = remember { MiniApps.iconBitmap(app, 256).asImageBitmap() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Image(icon, null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
                Text("${app.name} can't open", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (app.tampered) androidx.compose.material3.Button(onClick = {
                    runCatching { com.example.gemini.data.miniapps.MiniAppInstaller.uninstall(this@MiniAppActivity, app) }
                        .onSuccess { toast("${app.name} uninstalled", false) }.onFailure { toast(it.message ?: "Couldn't uninstall", true) }
                    closeApp()
                }, modifier = Modifier.padding(top = 24.dp)) { Text("Uninstall") }
                androidx.compose.material3.TextButton(onClick = { closeApp() }) { Text("Close") }
            }
        }
    }

    /** The app can't run: a required permission is missing. */
    @Composable
    private fun BlockedScreen(missing: List<String>) {
        val icon = remember { MiniApps.iconBitmap(app, 256).asImageBitmap() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Image(icon, null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
                Text("${app.name} can't run without", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                missing.forEach { name ->
                    val info = MiniAppPermissions.describe(app, name)
                    Text("• ${info.title}" + if (info.detail.isNotBlank()) ": ${info.detail}" else "", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
                }
                androidx.compose.material3.Button(onClick = { reviewBlocked() }, modifier = Modifier.padding(top = 24.dp)) { Text("Review permissions") }
                androidx.compose.material3.TextButton(onClick = { closeApp() }) { Text("Close") }
            }
        }
    }

    @Composable
    private fun Splash() {
        val bg = app.themeColor ?: backgroundColor
        val fg = if (isLight(bg)) Color(0xFF1C1B1F) else Color.White
        val icon = remember { MiniApps.iconBitmap(app, 256).asImageBitmap() }
        Box(Modifier.fillMaxSize().background(Color(bg)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(icon, null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
                Text(app.name, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
                if (!app.signed) Text("DEV", color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun DevBubble() {
        val density = LocalDensity.current
        var offset by remember { mutableStateOf(IntOffset(with(density) { (resources.displayMetrics.widthPixels - 52.dp.toPx()).roundToInt() }, with(density) { 120.dp.toPx().roundToInt() })) }
        Surface(
            onClick = { devMenuOpen = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.55f),
            modifier = Modifier.offset { offset }.size(40.dp).shadow(4.dp, CircleShape)
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); offset = IntOffset(offset.x + drag.x.roundToInt(), offset.y + drag.y.roundToInt()) } },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.MoreHoriz, "Developer menu", tint = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }

    @Composable
    private fun DevMenu() {
        val items = listOf(
            NativeDialog.SheetItem("reload", "Reload", "↻", false),
            NativeDialog.SheetItem("console", "Console", "▤", false),
            NativeDialog.SheetItem("devtools", "DevTools (elements, network, storage)", "⚙", false),
            NativeDialog.SheetItem("info", "App info & permissions", "ⓘ", false),
            NativeDialog.SheetItem("home", "Add to home screen", "⌂", false),
            NativeDialog.SheetItem("close", "Close app", "✕", true),
        )
        ActionSheet(title = "${app.name} ${app.version}", items = items, onDismiss = { devMenuOpen = false }) { id ->
            devMenuOpen = false
            when (id) {
                "reload" -> page.reload()
                "console" -> consoleOpen = true
                "devtools" -> page.openDevtools()
                "info" -> infoOpen = true
                "home" -> MiniAppShortcuts.addToHome(this, app)
                "close" -> closeApp()
            }
        }
    }
}

/** A page opened with AntiGem.nav.push: same task as the root, slides in and out. */
class MiniAppPageActivity : MiniAppActivity() {
    override val isPushed = true
}
