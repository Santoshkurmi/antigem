package com.example.gemini

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.ui.chat.ChatScreen
import com.example.gemini.ui.browser.BrowserScreen
import com.example.gemini.ui.chat.ChatViewModel
import com.example.gemini.ui.chat.ChatViewModelHolder
import com.example.gemini.ui.ide.IdeScreen

enum class AppViewMode {
    CHAT,
    IDE,
    TERMINAL,
    BROWSER,
    NOTES,
    FILES
}

class MainActivity : ComponentActivity() {

    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        val currentInstance: MainActivity?
            get() = instanceRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        fun showToast(message: String) {
            com.example.gemini.ui.components.AppToastHelper.showToast(message)
        }
    }

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instanceRef = java.lang.ref.WeakReference(this)
        com.example.gemini.data.jarvis.JarvisPreferences.init(this)
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled =
            chatViewModel.authPreferences.getNetworkInspectorEnabledSync()
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled =
            chatViewModel.authPreferences.getFloatingNetworkInspectorEnabledSync()

        try {
            android.webkit.WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {
        }

        if (com.example.gemini.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            com.example.gemini.data.service.TermuxService.start(this)
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
        com.example.gemini.data.daemon.TermuxDaemonManager.init(this)
        com.example.gemini.ui.browser.BrowserSessionManager.instance.init(this)
        com.example.gemini.data.local.LocalTerminalBridge.instance.init(this)
        com.example.gemini.data.notes.NotesRepository.init(this)
        com.example.gemini.data.files.ExplorerPrefs.init(this)
        resetTerminalLauncherOnce()
        com.example.gemini.data.remote.AndroidLocalBridgeServer.instance.start(this)
        com.example.gemini.data.jarvis.JarvisController.attach(application, chatViewModel)
        com.example.gemini.data.agent.ChatCompletionMonitor.start(application, chatViewModel)
        handleOAuthIntent(intent)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = com.example.gemini.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }
            // the startup splash follows the app's own light / dark mode
            LaunchedEffect(themeMode) { com.example.gemini.theme.ThemeManager.syncSystemNightMode(themeMode) }

            val requestedMode by chatViewModel.requestedViewMode.collectAsState()
            val initialViewMode = remember {
                when (chatViewModel.requestedViewMode.value) {
                    "IDE" -> AppViewMode.IDE
                    "TERMINAL" -> AppViewMode.TERMINAL
                    "BROWSER" -> AppViewMode.BROWSER
                    "NOTES" -> AppViewMode.NOTES
                    "FILES" -> AppViewMode.FILES
                    else -> AppViewMode.CHAT
                }
            }
            var currentViewMode by remember { mutableStateOf(initialViewMode) }
            var previousViewMode by remember { mutableStateOf(AppViewMode.CHAT) }

            LaunchedEffect(requestedMode) {
                requestedMode?.let { mode ->
                    when (mode) {
                        "IDE" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.IDE
                        }

                        "CHAT" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.CHAT
                        }

                        "TERMINAL" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.TERMINAL
                        }

                        "BROWSER" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.BROWSER
                        }

                        "NOTES" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.NOTES
                        }

                        "FILES" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.FILES
                        }
                    }
                    chatViewModel.consumeRequestedViewMode()
                }
            }

            val floatingSwitcherOrientation by chatViewModel.floatingSwitcherOrientation.collectAsState()
            val floatingSwitcherItems by chatViewModel.floatingSwitcherItems.collectAsState()
            val floatingSwitcherAutoCollapseSec by chatViewModel.floatingSwitcherAutoCollapseSec.collectAsState()
            val floatingSwitcherPosX by chatViewModel.floatingSwitcherPosX.collectAsState()
            val floatingSwitcherPosY by chatViewModel.floatingSwitcherPosY.collectAsState()
            val isFloatingDiagnosticsEnabled by chatViewModel.isFloatingDiagnosticsEnabled.collectAsState()
            val isNetworkInspectorEnabled by chatViewModel.isNetworkInspectorEnabled.collectAsState()
            val isFloatingNetworkInspectorEnabled by chatViewModel.isFloatingNetworkInspectorEnabled.collectAsState()
            var showNetworkInspectorDialog by remember { mutableStateOf(false) }

            var pendingUpdateInfo by remember { mutableStateOf<com.example.gemini.data.updater.AppUpdateInfo?>(null) }
            val updateManager = remember { com.example.gemini.data.updater.AppUpdateManager(this@MainActivity) }

            LaunchedEffect(Unit) {
                updateManager.checkForUpdates().onSuccess { info ->
                    pendingUpdateInfo = info
                }
            }

            LaunchedEffect(isFloatingDiagnosticsEnabled) {
                if (isFloatingDiagnosticsEnabled) {
                    com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.start()
                } else {
                    com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.stop()
                }
            }

            // Jarvis (voice mode) needs to know which screen the user is on
            LaunchedEffect(currentViewMode) {
                com.example.gemini.data.jarvis.JarvisController.screen.value = currentViewMode.name.lowercase()
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(currentViewMode, useDarkTheme) {
                val window = (context as? android.app.Activity)?.window
                if (window != null && !view.isInEditMode) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    if (currentViewMode == AppViewMode.TERMINAL) {
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    } else {
                        val bgArgb =
                            if (useDarkTheme) com.example.gemini.theme.ClaudeDarkBg.toArgb() else com.example.gemini.theme.ClaudeCream.toArgb()
                        // Transparent like Theme.kt: screens draw their own background behind it, and the
                        // sidebar + scrim can extend over it.
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = bgArgb
                        insetsController.isAppearanceLightStatusBars = !useDarkTheme
                        insetsController.isAppearanceLightNavigationBars = !useDarkTheme
                    }
                }
                onDispose {}
            }

            var showPermissionsDialog by remember {
                mutableStateOf(!com.example.gemini.ui.components.PermissionUtils.hasNotificationPermission(context))
            }

            val isTermuxPackage =
                remember { com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context) }
            var isInstalledState by remember {
                mutableStateOf(
                    com.example.gemini.data.local.LocalEnvironmentManager.isInstalled(
                        context
                    )
                )
            }
            var hasSkippedInstaller by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            // Remote mode runs everything on the server: nothing to install on the phone
            val showFullScreenInstaller = !isInstalledState && !hasSkippedInstaller &&
                !com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode

            GeminiTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showPermissionsDialog) {
                        com.example.gemini.ui.components.AppPermissionsDialog(
                            onDismissOrCompleted = {
                                showPermissionsDialog = false
                                com.example.gemini.data.service.TermuxService.start(context)
                                com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(context)
                            }
                        )
                    } else if (showFullScreenInstaller) {
                        BackHandler {
                            hasSkippedInstaller = true
                        }
                        com.example.gemini.ui.settings.FullScreenLocalToolsInstaller(
                            authPreferences = chatViewModel.authPreferences,
                            onSkip = { hasSkippedInstaller = true },
                            onComplete = {
                                isInstalledState = true
                                hasSkippedInstaller = true
                                // before the first agent choice the server waits (the choice dialog starts it)
                                if (com.example.gemini.data.local.LocalServerManager.hasServerScript(context) &&
                                    com.example.gemini.data.agent.AgentPreferences.hasChosenSync(context)
                                ) {
                                    com.example.gemini.data.local.LocalServerManager.startServer(
                                        context,
                                        forceRestart = false
                                    )
                                }
                            }
                        )
                    } else {
                        BackHandler(enabled = currentViewMode != AppViewMode.CHAT && currentViewMode != AppViewMode.BROWSER) {
                            currentViewMode =
                                if (previousViewMode != currentViewMode && previousViewMode != AppViewMode.BROWSER) previousViewMode else AppViewMode.CHAT
                        }

                        val terminalSessions by com.example.gemini.data.local.LocalTerminalManager.sessions.collectAsState()
                        var hasEverOpenedTerminal by remember { mutableStateOf(false) }
                        if (currentViewMode == AppViewMode.TERMINAL) {
                            hasEverOpenedTerminal = true
                        }

                        val focusCoordinator = remember { com.example.gemini.ui.components.AppFocusCoordinator() }
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime

                        var previousModeForFocus by remember { mutableStateOf(currentViewMode) }

                        LaunchedEffect(currentViewMode) {
                            if (previousModeForFocus != currentViewMode) {
                                val isKeyboardOpen = imeInsets.getBottom(density) > 0
                                focusCoordinator.onScreenLeaving(previousModeForFocus, isKeyboardOpen)
                                focusCoordinator.onScreenEntering(currentViewMode, isKeyboardOpen)
                                previousModeForFocus = currentViewMode
                            }
                        }

                        CompositionLocalProvider(
                            com.example.gemini.ui.components.LocalAppFocusCoordinator provides focusCoordinator
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Persistent Chat Screen (Never destroyed on toggle)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.CHAT
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else -20000f
                                        }
                                ) {
                                    ChatScreen(
                                        viewModel = chatViewModel,
                                        onNavigateToIde = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.IDE
                                        },
                                        onNavigateToTerminal = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onNavigateToBrowser = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.BROWSER
                                        },
                                        onNavigateToNotes = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.NOTES
                                        },
                                        onNavigateToFiles = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.FILES
                                        }
                                    )
                                }

                                // Persistent IDE Screen (Retains open tabs, daemon connection & state)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.IDE
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    IdeScreen(
                                        viewModel = chatViewModel,
                                        isVisible = currentViewMode == AppViewMode.IDE,
                                        onNavigateToChat = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.CHAT
                                        },
                                        onExecuteRunCommand = { cmd ->
                                            // Connect with terminal / chat execution
                                        },
                                        onNavigateToTerminal = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onNavigateToBrowser = {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.BROWSER
                                        }
                                    )
                                }

                                // Persistent Terminal Screen (Retains terminal tmux sessions & state)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.TERMINAL
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                                        com.example.gemini.ui.components.LocalTerminalContent(
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.TERMINAL) AppViewMode.CHAT else previousViewMode
                                            }
                                        )
                                    }
                                }

                                // Persistent Web Preview Browser Screen (Retains open tabs, WebViews, state & navigation)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.BROWSER
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    BrowserScreen(
                                        isVisible = currentViewMode == AppViewMode.BROWSER,
                                        onClose = {
                                            currentViewMode =
                                                if (previousViewMode == AppViewMode.BROWSER) AppViewMode.CHAT else previousViewMode
                                        }
                                    )
                                }

                                // Persistent Notes Screen (Notes & to-do lists; open note and scroll survive switching away)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.NOTES
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    com.example.gemini.ui.notes.NotesScreen(
                                        isVisible = currentViewMode == AppViewMode.NOTES,
                                        onClose = {
                                            currentViewMode =
                                                if (previousViewMode == AppViewMode.NOTES) AppViewMode.CHAT else previousViewMode
                                        }
                                    )
                                }

                                // Persistent Files Screen (file explorer: folder, history and clipboard survive switching away)
                                var hasEverOpenedFiles by remember { mutableStateOf(false) }
                                if (currentViewMode == AppViewMode.FILES) hasEverOpenedFiles = true
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.FILES
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                ) {
                                    if (hasEverOpenedFiles) {
                                        com.example.gemini.ui.files.FileExplorer(
                                            stateKey = "screen",
                                            isVisible = currentViewMode == AppViewMode.FILES,
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.FILES) AppViewMode.CHAT else previousViewMode
                                            },
                                            onOpenedInIde = {
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            },
                                            onOpenFolderInIde = { folder ->
                                                com.example.gemini.data.files.IdeFileLauncher.openFolderAsProject(folder)
                                                chatViewModel.onProjectChanged(folder)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            },
                                            onProjectCreated = { proj ->
                                                com.example.gemini.data.daemon.TermuxDaemonManager.setActiveProject(proj)
                                                chatViewModel.onProjectChanged(proj.path)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            }
                                        )
                                    }
                                }

                                // Floating App Switcher Across Whole App (always on; renders on top of Chat, IDE, Terminal, Browser)
                                // Its Terminal needs a shell: SSH, server mode, or the local Linux tools (turned on here when
                                // installed but off); without any, it offers to install the Linux tools instead.
                                val switcherUseSsh by chatViewModel.useSshTerminal.collectAsState(initial = false)
                                val switcherToolsInstalled by chatViewModel.isLocalToolsInstalled.collectAsState(initial = false)
                                val switcherToolsEnabled by chatViewModel.isLocalToolsEnabled.collectAsState(initial = false)
                                var showSwitcherToolsInstall by remember { mutableStateOf(false) }
                                val openTerminalFromSwitcher: () -> Unit = {
                                    val ready = switcherUseSsh ||
                                        com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode ||
                                        (switcherToolsInstalled && switcherToolsEnabled)
                                    when {
                                        ready -> {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        }
                                        switcherToolsInstalled -> {
                                            chatViewModel.setLocalToolsEnabled(true)
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        }
                                        else -> showSwitcherToolsInstall = true
                                    }
                                }
                                // Double-tap "back" target: the screen shown just before the current one, however we
                                // got here (switcher, close buttons, other in-app navigation). Tracked separately from
                                // previousViewMode because the close handlers don't update that.
                                var switcherBackTarget by remember { mutableStateOf<AppViewMode?>(null) }
                                var switcherLastSeenMode by remember { mutableStateOf(currentViewMode) }
                                LaunchedEffect(currentViewMode) {
                                    if (currentViewMode != switcherLastSeenMode) {
                                        switcherBackTarget = switcherLastSeenMode
                                        switcherLastSeenMode = currentViewMode
                                    }
                                }
                                com.example.gemini.ui.components.FloatingSwitcherWidget(
                                    currentViewMode = currentViewMode,
                                    orientation = floatingSwitcherOrientation,
                                    items = floatingSwitcherItems,
                                    autoCollapseTimeoutSec = floatingSwitcherAutoCollapseSec,
                                    savedPosXRatio = floatingSwitcherPosX,
                                    savedPosYRatio = floatingSwitcherPosY,
                                    onNavigateToChat = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.CHAT
                                    },
                                    onNavigateToIde = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.IDE
                                    },
                                    onNavigateToBrowser = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.BROWSER
                                    },
                                    onNavigateToTerminal = openTerminalFromSwitcher,
                                    onNavigateToNotes = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.NOTES
                                    },
                                    onNavigateToFiles = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.FILES
                                    },
                                    onNavigateBack = {
                                        val target = switcherBackTarget
                                        when {
                                            target == null || target == currentViewMode -> Unit
                                            target == AppViewMode.TERMINAL -> openTerminalFromSwitcher()
                                            else -> {
                                                previousViewMode = currentViewMode
                                                currentViewMode = target
                                            }
                                        }
                                    },
                                    onPositionSaved = { x, y ->
                                        chatViewModel.saveFloatingSwitcherPosition(x, y)
                                    },
                                    onOrientationChange = { chatViewModel.setFloatingSwitcherOrientation(it) }
                                )
                                if (showSwitcherToolsInstall) {
                                    com.example.gemini.ui.settings.LocalToolsInstallDialog(
                                        authPreferences = chatViewModel.authPreferences,
                                        onOpenTerminal = {
                                            showSwitcherToolsInstall = false
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onDismiss = { showSwitcherToolsInstall = false }
                                    )
                                }

                                // Files shared or opened from other apps: ask what to do (open in the editor, save to a folder)
                                val incomingFiles by com.example.gemini.data.files.IncomingShare.pending.collectAsState()
                                val shareScope = rememberCoroutineScope()
                                incomingFiles?.let { items ->
                                    com.example.gemini.ui.files.IncomingShareDialog(
                                        items = items,
                                        onOpenInEditor = { item -> openIncomingInEditor(item) },
                                        onPreviewMarkdown = { item -> item.uri?.let { chatViewModel.loadMarkdownFromUri(context, it) } },
                                        onOpenSavedInIde = { path, name, size ->
                                            shareScope.launch {
                                                when (val r = com.example.gemini.data.files.IdeFileLauncher.open(path, name, size)) {
                                                    is com.example.gemini.data.files.IdeFileLauncher.Outcome.Opened -> {
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.IDE
                                                    }
                                                    is com.example.gemini.data.files.IdeFileLauncher.Outcome.Failed ->
                                                        com.example.gemini.ui.components.AppToastHelper.showToast(r.message, com.example.gemini.ui.components.ChatToastType.ERROR)
                                                    is com.example.gemini.data.files.IdeFileLauncher.Outcome.NeedsConfirm -> {
                                                        com.example.gemini.data.files.ExplorerNav.open("screen", path.substringBeforeLast('/'))
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.FILES
                                                    }
                                                }
                                            }
                                        },
                                        onShowInFiles = { folder ->
                                            com.example.gemini.data.files.ExplorerNav.open("screen", folder)
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.FILES
                                        },
                                        onDismiss = { com.example.gemini.data.files.IncomingShare.clear() }
                                    )
                                }

                                // Floating Video Player Overlay for active background/in-app playback controls
                                com.example.gemini.ui.components.FloatingVideoPlayerOverlay()

                                // Jarvis: voice orb (only when Jarvis is turned on in Settings)
                                com.example.gemini.ui.jarvis.JarvisOverlay()

                                // Real-time floating live diagnostics overlay (JVM Threads, OkHttp queues, connections, lag)
                                if (isFloatingDiagnosticsEnabled) {
                                    com.example.gemini.ui.components.FloatingDiagnosticsOverlay(
                                        onDismiss = {
                                            chatViewModel.setFloatingDiagnosticsEnabled(false)
                                        }
                                    )
                                }

                                // Floating Network Inspector Bubble (In-app draggable bubble across all screens)
                                if (isNetworkInspectorEnabled && isFloatingNetworkInspectorEnabled) {
                                    com.example.gemini.ui.components.FloatingNetworkInspectorBubble(
                                        onClick = { showNetworkInspectorDialog = true }
                                    )
                                }

                                // Global Network Inspector Dialog
                                if (showNetworkInspectorDialog) {
                                    com.example.gemini.ui.components.NetworkInspectorDialog(
                                        onDismiss = { showNetworkInspectorDialog = false }
                                    )
                                }

                                // Centralized In-App Update Dialog
                                pendingUpdateInfo?.let { updateInfo ->
                                    com.example.gemini.ui.updater.AppUpdateDialog(
                                        updateInfo = updateInfo,
                                        onDismiss = { pendingUpdateInfo = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        instanceRef = java.lang.ref.WeakReference(this)
        if (com.example.gemini.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            com.example.gemini.data.service.TermuxService.start(this)
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)
    }

    /**
     * One time after the update that made the separate terminal icon opt-in: if the icon is on (most users only got it by
     * confirming the old first-launch dialog, which had it preselected), switch it off and show that dialog again with
     * "hidden" preselected, so people who really use it can turn it back on with one tap.
     */
    private fun resetTerminalLauncherOnce() {
        val prefs = chatViewModel.authPreferences
        if (!prefs.takeTerminalLauncherResetSync()) return
        val launcher = com.example.gemini.data.preferences.TerminalLauncherManager
        if (!launcher.isLauncherEnabled(this)) return
        launcher.applyLauncherSetting(this, enabled = false, style = launcher.getLauncherStyle(this))
        lifecycleScope.launch {
            prefs.setTerminalLauncherEnabled(false)
            prefs.setHasSeenTerminalLauncherOnboarding(false)
        }
    }

    /** The Jarvis Quick Settings tile opened the app: start listening once the window is in front. */
    private fun handleJarvisIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.example.gemini.data.jarvis.JarvisTileService.EXTRA_TALK, false) != true) return
        intent.removeExtra(com.example.gemini.data.jarvis.JarvisTileService.EXTRA_TALK)
        window.decorView.postDelayed({
            if (com.example.gemini.data.jarvis.JarvisController.isAttached) com.example.gemini.data.jarvis.JarvisController.startListening()
        }, 700)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "gemini") {
            val code = uri.getQueryParameter("code")
            if (code != null) {
                chatViewModel.handleOAuthCode(code)
            }
        }
    }

    /**
     * Files opened with or shared to AntiGem. Shared conversations (.antigem) import directly; everything else (one file,
     * many files, or plain text / code) asks first: open in the code editor, preview, or save to a folder.
     */
    private fun handleIncomingFileIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val share = com.example.gemini.data.files.IncomingShare
        // links (OAuth callbacks, web pages) are not files
        val uris = share.urisOf(intent).filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            if (action == Intent.ACTION_SEND) {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) share.offer(listOf(share.textItem(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))))
            }
            return
        }
        val items = uris.map { share.describe(this, it, intent.type) }
        if (items.size == 1) {
            val item = items.first()
            val lower = item.name.lowercase()
            if (lower.endsWith(".antigem")) {
                chatViewModel.loadSharedConversationFromUri(this, item.uri!!)
                return
            }
            // exported trajectories can arrive without the .antigem name: peek at the header
            val kind = item.kind
            if (kind == com.example.gemini.data.files.FileKind.DATA || kind == com.example.gemini.data.files.FileKind.TEXT ||
                kind == com.example.gemini.data.files.FileKind.BINARY
            ) {
                val header = try {
                    contentResolver.openInputStream(item.uri!!)?.bufferedReader(Charsets.UTF_8)?.use {
                        val chars = CharArray(150)
                        val read = it.read(chars, 0, 150)
                        if (read > 0) String(chars, 0, read) else ""
                    } ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (header.contains("\"antigem_trajectory\"")) {
                    chatViewModel.loadSharedConversationFromUri(this, item.uri!!)
                    return
                }
            }
        }
        share.offer(items)
    }

    /** "Open in code editor" from the share dialog. */
    private fun openIncomingInEditor(item: com.example.gemini.data.files.IncomingItem) {
        val uri = item.uri
        when {
            item.text != null -> {
                com.example.gemini.data.daemon.TermuxDaemonManager.openOrSelectTab(
                    path = "shared-text:${System.currentTimeMillis()}/${item.name}",
                    name = item.name,
                    content = item.text,
                    isExternal = true
                )
                chatViewModel.requestViewMode("IDE")
            }
            // the IDE's image viewer reads a file path: keep a private copy for it
            item.kind == com.example.gemini.data.files.FileKind.IMAGE && uri != null -> lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dir = java.io.File(cacheDir, "shared_open").apply { mkdirs() }
                    val dest = java.io.File(dir, item.name)
                    contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        com.example.gemini.data.daemon.TermuxDaemonManager.openOrSelectTab(dest.absolutePath, item.name, "")
                        chatViewModel.requestViewMode("IDE")
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        com.example.gemini.ui.components.AppToastHelper.showToast("Could not open ${item.name}", com.example.gemini.ui.components.ChatToastType.ERROR)
                    }
                }
            }
            uri != null -> chatViewModel.openFileInIdeDirectly(this, uri)
        }
    }
}
