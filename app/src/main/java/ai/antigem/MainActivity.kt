package ai.antigem

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
import ai.antigem.theme.GeminiTheme
import ai.antigem.ui.chat.ChatScreen
import ai.antigem.ui.browser.BrowserScreen
import ai.antigem.ui.chat.ChatViewModel
import ai.antigem.ui.chat.ChatViewModelHolder
import ai.antigem.ui.ide.IdeScreen
import ai.antigem.ui.components.pageSnapshotSource
import ai.antigem.ui.components.hiddenWhileOffscreen
import ai.antigem.i18n.L

enum class AppViewMode {
    CHAT,
    IDE,
    TERMINAL,
    BROWSER,
    NOTES,
    FILES,
    /** The Run screen: a folder's commands and their live output (runs keep going on other screens) */
    RUN,
    /** Private Space: passwords, API keys, 2FA and private notes, encrypted; composed only while shown */
    PRIVATE_SPACE
} 

class MainActivity : ComponentActivity() {

    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        val currentInstance: MainActivity?
            get() = instanceRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

        fun showToast(message: String) {
            ai.antigem.ui.components.AppToastHelper.showToast(message)
        }
    }

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instanceRef = java.lang.ref.WeakReference(this)
        ai.antigem.data.jarvis.JarvisPreferences.init(this)
        ai.antigem.data.context.GlobalContext.init(this)
        ai.antigem.data.memory.AgentMemory.init(this)
        ai.antigem.data.egm.EgmManager.init(this)
        ai.antigem.data.remote.inspector.NetworkInspectorManager.isEnabled =
            chatViewModel.authPreferences.getNetworkInspectorEnabledSync()
        ai.antigem.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled =
            chatViewModel.authPreferences.getFloatingNetworkInspectorEnabledSync()

        try {
            android.webkit.WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {
        }

        if (ai.antigem.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            ai.antigem.data.service.AntiGemService.start(this, fromForeground = true)
            ai.antigem.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
        ai.antigem.data.daemon.IdeDaemonManager.init(this)
        ai.antigem.ui.browser.BrowserSessionManager.instance.init(this)
        ai.antigem.data.local.LocalTerminalBridge.instance.init(this)
        ai.antigem.data.notes.NotesRepository.init(this)
        ai.antigem.data.files.ExplorerPrefs.init(this)
        // temporary video / attachment / share copies left from earlier runs
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { ai.antigem.data.files.CacheJanitor.trimAll(applicationContext, force = true) }
        resetTerminalLauncherOnce()
        ai.antigem.data.remote.AndroidLocalBridgeServer.instance.start(this)
        ai.antigem.data.jarvis.JarvisController.attach(application, chatViewModel)
        // Jarvis over other apps (Settings → Jarvis → Screen & look → Float over other apps)
        ai.antigem.ui.jarvis.JarvisFloating.attach(this)
        ai.antigem.data.agent.ChatCompletionMonitor.start(application, chatViewModel)
        ai.antigem.data.memory.MemorySync.start(chatViewModel)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)
        handleVoiceCallIntent(intent)
        // Home-screen shortcuts and widgets: the chat / page / script they asked for (not again when recreated); the
        // chat list they show is kept up to date from here on
        if (savedInstanceState == null) ai.antigem.data.homescreen.HomeRouter.handle(this, chatViewModel, intent)
        ai.antigem.data.homescreen.HomeSnapshot.start(application, chatViewModel)
        // links are signed one by one now: shortcuts and widgets made before get their signature once
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { ai.antigem.data.homescreen.HomeLinks.resignOnce(applicationContext) }
        ai.antigem.ui.homescreen.HomeWidgets.publishPreviews(this)
        // Voice call tile / shortcut: the floating call window stays away while this screen is up; the long-press menu
        // of the app icon gets "Voice call"
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> ai.antigem.data.voice.VoiceCallLauncher.setAppVisible(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> ai.antigem.data.voice.VoiceCallLauncher.setAppVisible(false)
                else -> Unit
            }
        })
        ai.antigem.data.voice.AgyVoicePreferences.init(this)
        ai.antigem.data.voice.VoiceCallLauncher.publishShortcut(this)
        ai.antigem.ui.voice.AgyVoiceBubble.attach(this)

        // Back that no screen handles (the chat root) puts the app in the background instead of closing it, on every
        // Android version. Registered before setContent, so every screen's own BackHandler still runs first.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        // Android 7-8 focus the first focusable view on their own (when the window opens and every time focus is
        // cleared). Inside Compose that is the first text field on screen, e.g. the Browser's address bar, whose search
        // panel and keyboard then open by themselves and come back after every Back. The plain content frame takes that
        // focus instead, so nothing is focused until the user taps, as on Android 9+.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            findViewById<android.view.View>(android.R.id.content).isFocusableInTouchMode = true
        }

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = ai.antigem.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }
            // the startup splash follows the app's own light / dark mode
            LaunchedEffect(themeMode) { ai.antigem.theme.ThemeManager.syncSystemNightMode(themeMode) }

            val requestedMode by chatViewModel.requestedViewMode.collectAsState()
            val initialViewMode = remember {
                when (chatViewModel.requestedViewMode.value) {
                    "IDE" -> AppViewMode.IDE
                    "TERMINAL" -> AppViewMode.TERMINAL
                    "BROWSER" -> AppViewMode.BROWSER
                    "NOTES" -> AppViewMode.NOTES
                    "FILES" -> AppViewMode.FILES
                    "RUN" -> AppViewMode.RUN
                    else -> AppViewMode.CHAT
                }
            }
            // The page overview: a card per page, opened from the switcher's pill or the overview pill
            var showPageOverview by remember { mutableStateOf(false) }
            // Every page change goes through here. While the overview can be opened, the page being left is pictured for
            // its card (not while the overview covers it: it pictured that page when it opened)
            var currentViewMode by remember {
                ai.antigem.ui.components.PageChangeState(initialViewMode) { left ->
                    if (!showPageOverview && chatViewModel.authPreferences.isPageOverviewInUseSync()) {
                        ai.antigem.ui.components.PageSnapshots.capture(this@MainActivity, left)
                    }
                }
            }
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

                        "RUN" -> {
                            previousViewMode = currentViewMode
                            currentViewMode = AppViewMode.RUN
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
            val floatingSwitcherHandleIcon by chatViewModel.floatingSwitcherHandleIcon.collectAsState()
            val switcherPrefs = chatViewModel.authPreferences
            // The page overview opens from a tap on the switcher's pill ("SWITCHER"), from its own pill ("OWN_PILL"), or is off
            val pageOverviewMode by switcherPrefs.pageOverviewMode
                .collectAsState(initial = switcherPrefs.getPageOverviewModeSync())
            val pageOverviewKeepScroll by switcherPrefs.pageOverviewKeepScroll
                .collectAsState(initial = switcherPrefs.getPageOverviewKeepScrollSync())
            val switcherTapOpensOverview = pageOverviewMode == "SWITCHER"
            val overviewPillEnabled = pageOverviewMode == "OWN_PILL"
            val floatingSwitcherScale by switcherPrefs.floatingSwitcherScale
                .collectAsState(initial = switcherPrefs.getFloatingSwitcherScaleSync())
            val overviewPillScale by switcherPrefs.overviewPillScale
                .collectAsState(initial = switcherPrefs.getOverviewPillScaleSync())
            val overviewPillPosX by switcherPrefs.overviewPillPosX
                .collectAsState(initial = switcherPrefs.getOverviewPillPositionSync().first)
            val overviewPillPosY by switcherPrefs.overviewPillPosY
                .collectAsState(initial = switcherPrefs.getOverviewPillPositionSync().second)
            val pageOverviewLayout by switcherPrefs.pageOverviewLayout
                .collectAsState(initial = switcherPrefs.getPageOverviewLayoutSync())
            // the page overview can be opened (from the switcher's tap or the overview pill): record the pages for it
            val pageOverviewInUse = switcherTapOpensOverview || overviewPillEnabled
            // Where the chat's bottom input area starts (root px); read only inside the switcher, so updates don't
            // recompose this whole screen.
            val chatBottomAreaTop = remember { mutableStateOf<Float?>(null) }
            val isFloatingDiagnosticsEnabled by chatViewModel.isFloatingDiagnosticsEnabled.collectAsState()
            val isNetworkInspectorEnabled by chatViewModel.isNetworkInspectorEnabled.collectAsState()
            val isFloatingNetworkInspectorEnabled by chatViewModel.isFloatingNetworkInspectorEnabled.collectAsState()
            var showNetworkInspectorDialog by remember { mutableStateOf(false) }

            var pendingUpdateInfo by remember { mutableStateOf<ai.antigem.data.updater.AppUpdateInfo?>(null) }
            val updateManager = remember { ai.antigem.data.updater.AppUpdateManager(this@MainActivity) }

            var appUpdateCheckDone by remember { mutableStateOf(false) }
            // beta updates turned off by themselves with this stable version: told once, after the app's update dialog
            var showBetaOffNotice by remember { mutableStateOf(ai.antigem.data.updater.BetaUpdates.offNoticeDue(this@MainActivity)) }

            LaunchedEffect(Unit) {
                updateManager.checkForUpdates().onSuccess { info ->
                    pendingUpdateInfo = info
                }
                appUpdateCheckDone = true
            }

            // The app in a community language: its update is looked for in the background each time the app comes to the
            // foreground (at most every 30 minutes), and offered only once the app's own check is done and its dialog is
            // closed (one dialog at a time)
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
                ai.antigem.i18n.LanguageUpdateCheck.start(this@MainActivity)
            }
            val languageUpdateOffer = ai.antigem.i18n.LanguageUpdateCheck.offer
            var languageUpdateTurn by remember { mutableStateOf(false) }
            LaunchedEffect(appUpdateCheckDone, pendingUpdateInfo == null, showBetaOffNotice, languageUpdateOffer) {
                languageUpdateTurn = false
                if (appUpdateCheckDone && pendingUpdateInfo == null && !showBetaOffNotice && languageUpdateOffer != null) {
                    // a moment after the app's dialog closed, so it doesn't look like the same dialog coming back
                    kotlinx.coroutines.delay(600)
                    languageUpdateTurn = true
                }
            }

            LaunchedEffect(isFloatingDiagnosticsEnabled) {
                if (isFloatingDiagnosticsEnabled) {
                    ai.antigem.data.remote.core.AntiGemLiveDiagnostics.start()
                } else {
                    ai.antigem.data.remote.core.AntiGemLiveDiagnostics.stop()
                }
            }

            // Jarvis (voice mode) needs to know which screen the user is on
            LaunchedEffect(currentViewMode) {
                ai.antigem.data.jarvis.JarvisController.screen.value = currentViewMode.name.lowercase()
            }

            // Any page change (a pill's icon panel, a notification, a screen's own button) closes the page overview
            LaunchedEffect(currentViewMode) { showPageOverview = false }
            // Overview turned off in Settings: free its page pictures
            LaunchedEffect(switcherTapOpensOverview, overviewPillEnabled) {
                if (!switcherTapOpensOverview && !overviewPillEnabled) {
                    ai.antigem.ui.components.PageSnapshots.shots.clear()
                    showPageOverview = false
                }
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
                            if (useDarkTheme) ai.antigem.theme.ClaudeDarkBg.toArgb() else ai.antigem.theme.ClaudeCream.toArgb()
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
                mutableStateOf(!ai.antigem.ui.components.PermissionUtils.hasNotificationPermission(context))
            }

            var isInstalledState by remember {
                mutableStateOf(
                    ai.antigem.data.local.LocalEnvironmentManager.isInstalled(
                        context
                    )
                )
            }
            var hasSkippedInstaller by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            // Remote mode runs everything on the server: nothing to install on the phone
            val showFullScreenInstaller = !isInstalledState && !hasSkippedInstaller &&
                !ai.antigem.data.preferences.AuthPreferences.currentRemoteMode

            GeminiTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showPermissionsDialog) {
                        ai.antigem.ui.components.AppPermissionsDialog(
                            onDismissOrCompleted = {
                                showPermissionsDialog = false
                                ai.antigem.data.service.AntiGemService.start(context, fromForeground = true)
                                ai.antigem.data.local.LocalServerManager.autoStartOnAppLaunch(context)
                            }
                        )
                    } else if (showFullScreenInstaller) {
                        BackHandler {
                            hasSkippedInstaller = true
                        }
                        ai.antigem.ui.settings.FullScreenLocalToolsInstaller(
                            authPreferences = chatViewModel.authPreferences,
                            onSkip = { hasSkippedInstaller = true },
                            onComplete = {
                                isInstalledState = true
                                hasSkippedInstaller = true
                                // before the first agent choice the server waits (the choice dialog starts it)
                                if (ai.antigem.data.local.LocalServerManager.hasServerScript(context) &&
                                    ai.antigem.data.agent.AgentPreferences.hasChosenSync(context)
                                ) {
                                    ai.antigem.data.local.LocalServerManager.startServer(
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

                        val terminalSessions by ai.antigem.data.local.LocalTerminalManager.sessions.collectAsState()
                        var hasEverOpenedTerminal by remember { mutableStateOf(false) }
                        if (currentViewMode == AppViewMode.TERMINAL) {
                            hasEverOpenedTerminal = true
                        }

                        val focusCoordinator = remember { ai.antigem.ui.components.AppFocusCoordinator() }
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
                            ai.antigem.ui.components.LocalAppFocusCoordinator provides focusCoordinator
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.CHAT, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.CHAT)
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
                                        },
                                        onBottomAreaTopChanged = { chatBottomAreaTop.value = it }
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.IDE, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.IDE)
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.TERMINAL, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.TERMINAL)
                                ) {
                                    if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                                        ai.antigem.ui.components.LocalTerminalContent(
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.TERMINAL) AppViewMode.CHAT else previousViewMode
                                            },
                                            isVisible = currentViewMode == AppViewMode.TERMINAL,
                                            // the drawer's gear: Settings lives on the chat screen
                                            onOpenSettings = {
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.CHAT
                                                chatViewModel.requestSettingsSection("TERMINAL")
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.BROWSER, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.BROWSER)
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.NOTES, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.NOTES)
                                ) {
                                    ai.antigem.ui.notes.NotesScreen(
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
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.FILES, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.FILES)
                                ) {
                                    if (hasEverOpenedFiles) {
                                        ai.antigem.ui.files.FileExplorer(
                                            stateKey = "screen",
                                            // server / PC: a "This phone" tab too, to copy and move files between them
                                            allowPhoneTab = true,
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
                                                ai.antigem.data.files.IdeFileLauncher.openFolderAsProject(folder)
                                                chatViewModel.onProjectChanged(folder)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            },
                                            onProjectCreated = { proj ->
                                                ai.antigem.data.daemon.IdeDaemonManager.setActiveProject(proj)
                                                chatViewModel.onProjectChanged(proj.path)
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.IDE
                                            }
                                        )
                                    }
                                }

                                // Persistent Run Screen (a folder's commands and their live output; runs keep going on other screens)
                                var hasEverOpenedRun by remember { mutableStateOf(false) }
                                if (currentViewMode == AppViewMode.RUN) hasEverOpenedRun = true
                                LaunchedEffect(currentViewMode) {
                                    ai.antigem.data.run.RunPanel.setVisible(currentViewMode == AppViewMode.RUN)
                                }
                                // the chat header's Run icon, the IDE's "Open Run panel" and the bubble ask for it
                                LaunchedEffect(Unit) {
                                    ai.antigem.data.run.RunPanel.openRequests.collect {
                                        if (currentViewMode != AppViewMode.RUN) {
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.RUN
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            val isVisible = currentViewMode == AppViewMode.RUN
                                            alpha = if (isVisible) 1f else 0f
                                            translationX = if (isVisible) 0f else 20000f
                                        }
                                        // its drawing, for its picture in the page overview (without the floating pills over it)
                                        .pageSnapshotSource(AppViewMode.RUN, pageOverviewInUse)
                                        // hidden: out of TalkBack's and the keyboard's reach (draws the same)
                                        .hiddenWhileOffscreen(currentViewMode == AppViewMode.RUN)
                                ) {
                                    if (hasEverOpenedRun) {
                                        ai.antigem.ui.run.RunScreen(
                                            isVisible = currentViewMode == AppViewMode.RUN,
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.RUN) AppViewMode.CHAT else previousViewMode
                                            },
                                            onSendToChat = {
                                                previousViewMode = currentViewMode
                                                currentViewMode = AppViewMode.CHAT
                                            }
                                        )
                                    }
                                }

                                // Private Space (passwords, keys, private notes): composed only while it is the page shown, so nothing of
                                // it exists off screen, and never pictured for the page overview (PageSnapshots skips it)
                                if (currentViewMode == AppViewMode.PRIVATE_SPACE) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        ai.antigem.ui.privatespace.PrivateSpaceScreen(
                                            onClose = {
                                                currentViewMode =
                                                    if (previousViewMode == AppViewMode.PRIVATE_SPACE) AppViewMode.CHAT else previousViewMode
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
                                        ai.antigem.data.preferences.AuthPreferences.currentRemoteMode ||
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
                                // Double-tap on a pill: back to the page shown before (closing the overview if a tap opened it)
                                val switcherNavigateBack: () -> Unit = {
                                    val target = switcherBackTarget
                                    when {
                                        target == null || target == currentViewMode -> Unit
                                        target == AppViewMode.TERMINAL -> openTerminalFromSwitcher()
                                        else -> {
                                            previousViewMode = currentViewMode
                                            currentViewMode = target
                                        }
                                    }
                                    // after the page change, so the overview on screen isn't pictured as the page that was left
                                    showPageOverview = false
                                }
                                // A pill tap (overview mode): open the overview, picturing the shown page first; again closes it
                                val togglePageOverview: () -> Unit = {
                                    if (showPageOverview) {
                                        showPageOverview = false
                                    } else {
                                        ai.antigem.ui.components.PageSnapshots.capture(this@MainActivity, currentViewMode)
                                        showPageOverview = true
                                    }
                                }
                                val openPageFromOverview: (String) -> Unit = { id ->
                                    val mode = when (id) {
                                        "chat" -> AppViewMode.CHAT
                                        "ide" -> AppViewMode.IDE
                                        "browser" -> AppViewMode.BROWSER
                                        "notes" -> AppViewMode.NOTES
                                        "files" -> AppViewMode.FILES
                                        "run" -> AppViewMode.RUN
                                        "private" -> AppViewMode.PRIVATE_SPACE
                                        else -> null
                                    }
                                    when {
                                        id == "terminal" -> openTerminalFromSwitcher()
                                        mode != null && mode != currentViewMode -> {
                                            previousViewMode = currentViewMode
                                            currentViewMode = mode
                                        }
                                    }
                                    showPageOverview = false // after the page change (see switcherNavigateBack)
                                }
                                val pageOverviewScope = rememberCoroutineScope()
                                ai.antigem.ui.components.PageOverview(
                                    visible = showPageOverview,
                                    currentViewMode = currentViewMode,
                                    items = floatingSwitcherItems,
                                    layout = pageOverviewLayout,
                                    chatWorking = chatViewModel.isStreaming,
                                    chatConversation = chatViewModel.currentConversation,
                                    onOpenPage = openPageFromOverview,
                                    onLayoutChange = { layout ->
                                        pageOverviewScope.launch { switcherPrefs.savePageOverviewLayout(layout) }
                                    },
                                    // a tile held and dragged to a new place: the switcher's order (its panel and Settings follow)
                                    onReorder = { order -> chatViewModel.setFloatingSwitcherItems(order) },
                                    onDismiss = { showPageOverview = false },
                                    keepScroll = pageOverviewKeepScroll
                                )
                                ai.antigem.ui.components.FloatingSwitcherWidget(
                                    currentViewMode = currentViewMode,
                                    orientation = floatingSwitcherOrientation,
                                    items = floatingSwitcherItems,
                                    autoCollapseTimeoutSec = floatingSwitcherAutoCollapseSec,
                                    savedPosXRatio = floatingSwitcherPosX,
                                    savedPosYRatio = floatingSwitcherPosY,
                                    handleIcon = floatingSwitcherHandleIcon,
                                    scale = floatingSwitcherScale,
                                    tapOpensOverview = switcherTapOpensOverview,
                                    onOpenOverview = togglePageOverview,
                                    // the chat stays composed (moved off-screen) under other screens: use it only when shown
                                    bottomAreaTopPx = {
                                        if (currentViewMode == AppViewMode.CHAT) chatBottomAreaTop.value else null
                                    },
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
                                    onNavigateToRun = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.RUN
                                    },
                                    onNavigateToPrivateSpace = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.PRIVATE_SPACE
                                    },
                                    onNavigateBack = switcherNavigateBack,
                                    onPositionSaved = { x, y ->
                                        chatViewModel.saveFloatingSwitcherPosition(x, y)
                                    },
                                    onOrientationChange = { chatViewModel.setFloatingSwitcherOrientation(it) }
                                )
                                // The overview pill: a second pill that only opens the page overview (Settings → Appearance)
                                if (overviewPillEnabled) {
                                    val overviewPillScope = rememberCoroutineScope()
                                    ai.antigem.ui.components.FloatingSwitcherWidget(
                                        currentViewMode = currentViewMode,
                                        orientation = floatingSwitcherOrientation,
                                        items = floatingSwitcherItems,
                                        autoCollapseTimeoutSec = 0,
                                        savedPosXRatio = overviewPillPosX,
                                        savedPosYRatio = overviewPillPosY,
                                        handleIconOverride = ai.antigem.ui.components.PageOverviewPillIcon,
                                        scale = overviewPillScale,
                                        tapOpensOverview = true,
                                        onOpenOverview = togglePageOverview,
                                        bottomAreaTopPx = {
                                            if (currentViewMode == AppViewMode.CHAT) chatBottomAreaTop.value else null
                                        },
                                        // it never opens the icon panel
                                        onNavigateToChat = {},
                                        onNavigateToIde = {},
                                        onNavigateToBrowser = {},
                                        onNavigateToTerminal = {},
                                        onNavigateBack = switcherNavigateBack,
                                        onPositionSaved = { x, y ->
                                            overviewPillScope.launch { switcherPrefs.saveOverviewPillPosition(x, y) }
                                        }
                                    )
                                }
                                if (showSwitcherToolsInstall) {
                                    ai.antigem.ui.settings.LocalToolsInstallDialog(
                                        authPreferences = chatViewModel.authPreferences,
                                        onOpenTerminal = {
                                            showSwitcherToolsInstall = false
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.TERMINAL
                                        },
                                        onDismiss = { showSwitcherToolsInstall = false }
                                    )
                                }

                                // Commands running: the screen stays on, and on other screens a bubble shows them (tap → Run)
                                ai.antigem.ui.run.RunBubbleHost(isRunScreen = currentViewMode == AppViewMode.RUN)

                                // the built-in video player, full screen (from chat videos, tapped paths and Files)
                                ai.antigem.ui.components.FullscreenVideoHost()

                                // Files shared or opened from other apps: ask what to do (open in the editor, save to a folder)
                                val incomingFiles by ai.antigem.data.files.IncomingShare.pending.collectAsState()
                                val shareScope = rememberCoroutineScope()
                                incomingFiles?.let { items ->
                                    ai.antigem.ui.files.IncomingShareDialog(
                                        items = items,
                                        onOpenInEditor = { item -> openIncomingInEditor(item) },
                                        onPreviewMarkdown = { item -> item.uri?.let { chatViewModel.loadMarkdownFromUri(context, it) } },
                                        onOpenSavedInIde = { path, name, size ->
                                            shareScope.launch {
                                                when (val r = ai.antigem.data.files.IdeFileLauncher.open(path, name, size)) {
                                                    is ai.antigem.data.files.IdeFileLauncher.Outcome.Opened -> {
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.IDE
                                                    }
                                                    is ai.antigem.data.files.IdeFileLauncher.Outcome.Failed ->
                                                        ai.antigem.ui.components.AppToastHelper.showToast(r.message, ai.antigem.ui.components.ChatToastType.ERROR)
                                                    is ai.antigem.data.files.IdeFileLauncher.Outcome.NeedsConfirm -> {
                                                        ai.antigem.data.files.ExplorerNav.open("screen", path.substringBeforeLast('/'))
                                                        previousViewMode = currentViewMode
                                                        currentViewMode = AppViewMode.FILES
                                                    }
                                                }
                                            }
                                        },
                                        onShowInFiles = { folder ->
                                            ai.antigem.data.files.ExplorerNav.open("screen", folder)
                                            previousViewMode = currentViewMode
                                            currentViewMode = AppViewMode.FILES
                                        },
                                        onDismiss = { ai.antigem.data.files.IncomingShare.clear() }
                                    )
                                }

                                // Floating Video Player Overlay for active background/in-app playback controls
                                ai.antigem.ui.components.FloatingVideoPlayerOverlay()

                                // Jarvis: voice orb (only when Jarvis is turned on in Settings)
                                ai.antigem.ui.jarvis.JarvisOverlay()

                                // questions only the user may answer (Jarvis' looser approval modes, the AI reply language…)
                                ai.antigem.ui.components.UserConfirmHost()

                                // Real-time floating live diagnostics overlay (JVM Threads, OkHttp queues, connections, lag)
                                if (isFloatingDiagnosticsEnabled) {
                                    ai.antigem.ui.components.FloatingDiagnosticsOverlay(
                                        onDismiss = {
                                            chatViewModel.setFloatingDiagnosticsEnabled(false)
                                        }
                                    )
                                }

                                // Floating Network Inspector Bubble (In-app draggable bubble across all screens)
                                if (isNetworkInspectorEnabled && isFloatingNetworkInspectorEnabled) {
                                    ai.antigem.ui.components.FloatingNetworkInspectorBubble(
                                        onClick = { showNetworkInspectorDialog = true }
                                    )
                                }

                                // The floating pet (Gemmy and friends: drag/fling, tap, double-tap, long-press menu; reacts to the
                                // agent working). Shown after its one-time intro was answered and while on in Settings → Pets.
                                ai.antigem.ui.components.PetOverlay(
                                    prefs = chatViewModel.authPreferences,
                                    agentWorking = chatViewModel.isCurrentChatActivelyRunning,
                                    onOpenSettings = {
                                        chatViewModel.requestViewMode("CHAT")
                                        chatViewModel.requestSettingsSection("PETS")
                                    }
                                )

                                // Global Network Inspector Dialog
                                if (showNetworkInspectorDialog) {
                                    ai.antigem.ui.components.NetworkInspectorDialog(
                                        onDismiss = { showNetworkInspectorDialog = false }
                                    )
                                }

                                // Centralized In-App Update Dialog
                                pendingUpdateInfo?.let { updateInfo ->
                                    ai.antigem.ui.updater.AppUpdateDialog(
                                        updateInfo = updateInfo,
                                        onDismiss = { pendingUpdateInfo = null }
                                    )
                                }

                                // Beta updates turned off by this stable version (once, after the app's update dialog)
                                if (showBetaOffNotice && appUpdateCheckDone && pendingUpdateInfo == null) {
                                    ai.antigem.ui.updater.BetaOffNoticeDialog(onDismiss = {
                                        ai.antigem.data.updater.BetaUpdates.offNoticeShown(this@MainActivity)
                                        showBetaOffNotice = false
                                    })
                                }

                                // A newer version of the community language on screen (never together with the app's)
                                if (languageUpdateTurn && pendingUpdateInfo == null && !showBetaOffNotice) languageUpdateOffer?.let { offer ->
                                    ai.antigem.ui.updater.LanguageUpdateDialog(
                                        offer = offer,
                                        onNotNow = { ai.antigem.i18n.LanguageUpdateCheck.notNow(this@MainActivity) },
                                        onDone = { ai.antigem.i18n.LanguageUpdateCheck.clear() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    // A view the app does not own (e.g. one the system puts in the window) can leave state that
    // cannot be read back on recreate; drop the old view state instead of crashing. Compose
    // rememberSaveable state is restored separately (SavedStateRegistry), so it is not lost.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } catch (e: RuntimeException) {
            android.util.Log.w("MainActivity", "Skipped restoring view state", e)
        }
    }

    override fun onResume() {
        super.onResume()
        instanceRef = java.lang.ref.WeakReference(this)
        if (ai.antigem.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            ai.antigem.data.service.AntiGemService.start(this, fromForeground = true)
            ai.antigem.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFileIntent(intent)
        handleJarvisIntent(intent)
        handleVoiceCallIntent(intent)
        ai.antigem.data.homescreen.HomeRouter.handle(this, chatViewModel, intent)
    }

    /**
     * One time after the update that made the separate terminal icon opt-in: if the icon is on (most users only got it by
     * confirming the old first-launch dialog, which had it preselected), switch it off and show that dialog again with
     * "hidden" preselected, so people who really use it can turn it back on with one tap.
     */
    private fun resetTerminalLauncherOnce() {
        val prefs = chatViewModel.authPreferences
        if (!prefs.takeTerminalLauncherResetSync()) return
        val launcher = ai.antigem.data.preferences.TerminalLauncherManager
        if (!launcher.isLauncherEnabled(this)) return
        launcher.applyLauncherSetting(this, enabled = false, style = launcher.getLauncherStyle(this))
        lifecycleScope.launch {
            prefs.setTerminalLauncherEnabled(false)
            prefs.setHasSeenTerminalLauncherOnboarding(false)
        }
    }

    /**
     * The Jarvis Quick Settings tile opened the app: start listening once the window is in front. Only for an intent
     * the app signed: this screen is exported, and another app must not switch the microphone on.
     */
    private fun handleJarvisIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ai.antigem.data.jarvis.JarvisTileService.EXTRA_TALK, false) != true) return
        intent.removeExtra(ai.antigem.data.jarvis.JarvisTileService.EXTRA_TALK)
        if (!ai.antigem.data.security.AppIntentKey.isTrusted(this, intent, ai.antigem.data.security.AppIntentKey.JARVIS_TALK)) return
        window.decorView.postDelayed({
            if (ai.antigem.data.jarvis.JarvisController.isAttached) ai.antigem.data.jarvis.JarvisController.startListening()
        }, 700)
    }

    /** The Voice call tile or shortcut opened the app: the chat screen starts the call once the server is up (signed only). */
    private fun handleVoiceCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ai.antigem.data.voice.VoiceCallLauncher.EXTRA_START_CALL, false) != true) return
        intent.removeExtra(ai.antigem.data.voice.VoiceCallLauncher.EXTRA_START_CALL)
        if (!ai.antigem.data.security.AppIntentKey.isTrusted(this, intent, ai.antigem.data.security.AppIntentKey.VOICE_CALL)) return
        ai.antigem.data.voice.VoiceCallLauncher.requestStart()
    }

    /**
     * Files opened with or shared to AntiGem. Shared conversations (.antigem) import directly; everything else (one file,
     * many files, or plain text / code) asks first: open in the code editor, preview, or save to a folder.
     */
    private fun handleIncomingFileIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val share = ai.antigem.data.files.IncomingShare
        // links (OAuth callbacks, web pages) are not files
        val fileUris = share.urisOf(intent).filter { it.scheme == "content" || it.scheme == "file" }
        // another app can't make AntiGem open AntiGem's own private files (logins, keys, settings) for it
        val uris = fileUris.filter { ai.antigem.data.files.IncomingUriGuard.allowed(this, intent, it) }
        if (uris.size < fileUris.size) ai.antigem.data.files.IncomingUriGuard.toastRefused(this)
        if (fileUris.isNotEmpty() && uris.isEmpty()) return
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
                chatViewModel.loadSharedConversationFromUri(this, item.uri!!, item.name)
                return
            }
            // a mini app package opened with / shared to "AntiGem" instead of "Mini app installer": install it
            if (lower.endsWith(".agapp") || intent.type == ai.antigem.data.miniapps.MiniAppSigning.MIME) {
                val install = Intent(this, ai.antigem.ui.miniapps.MiniAppInstallActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(item.uri, ai.antigem.data.miniapps.MiniAppSigning.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // a private uri got here only from AntiGem itself (checked above): the installer may open it too
                if (ai.antigem.data.files.IncomingUriGuard.isAntiGemPrivate(this, item.uri!!)) ai.antigem.data.files.IncomingUriGuard.signOwn(this, install)
                startActivity(install)
                return
            }
            // exported trajectories can arrive without the .antigem name: peek at the header
            val kind = item.kind
            if (kind == ai.antigem.data.files.FileKind.DATA || kind == ai.antigem.data.files.FileKind.TEXT ||
                kind == ai.antigem.data.files.FileKind.BINARY
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
                    chatViewModel.loadSharedConversationFromUri(this, item.uri!!, item.name)
                    return
                }
            }
        }
        share.offer(items)
    }

    /** "Open in code editor" from the share dialog. */
    private fun openIncomingInEditor(item: ai.antigem.data.files.IncomingItem) {
        val uri = item.uri
        when {
            item.text != null -> {
                ai.antigem.data.daemon.IdeDaemonManager.openOrSelectTab(
                    path = "shared-text:${System.currentTimeMillis()}/${item.name}",
                    name = item.name,
                    content = item.text,
                    isExternal = true
                )
                chatViewModel.requestViewMode("IDE")
            }
            // the IDE's image viewer reads a file path: keep a private copy for it
            item.kind == ai.antigem.data.files.FileKind.IMAGE && uri != null -> lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dir = java.io.File(cacheDir, "shared_open").apply { mkdirs() }
                    val dest = java.io.File(dir, item.name)
                    contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        ai.antigem.data.daemon.IdeDaemonManager.openOrSelectTab(dest.absolutePath, item.name, "")
                        chatViewModel.requestViewMode("IDE")
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        ai.antigem.ui.components.AppToastHelper.showToast(L.app.mainActivity.couldNotOpenName(item.name), ai.antigem.ui.components.ChatToastType.ERROR)
                    }
                }
            }
            uri != null -> chatViewModel.openFileInIdeDirectly(this, uri)
        }
    }
}
