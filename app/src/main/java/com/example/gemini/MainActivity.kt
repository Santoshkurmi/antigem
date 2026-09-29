package com.example.gemini

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    BROWSER
}

class MainActivity : ComponentActivity() {

    companion object {
        private var instanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun showToast(message: String) {
            com.example.gemini.ui.components.AppToastHelper.showToast(message)
        }
    }

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = java.lang.ref.WeakReference(this)
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled =
            chatViewModel.authPreferences.getNetworkInspectorEnabledSync()
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled =
            chatViewModel.authPreferences.getFloatingNetworkInspectorEnabledSync()

        try {
            android.webkit.WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {}

        if (com.example.gemini.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            com.example.gemini.data.service.TermuxService.start(this)
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
        com.example.gemini.data.daemon.TermuxDaemonManager.init(this)
        com.example.gemini.ui.browser.BrowserSessionManager.instance.init(this)
        com.example.gemini.data.local.LocalTerminalBridge.instance.init(this)
        com.example.gemini.data.remote.AndroidLocalBridgeServer.instance.start(this)
        handleOAuthIntent(intent)
        handleIncomingFileIntent(intent)

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = com.example.gemini.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            val requestedMode by chatViewModel.requestedViewMode.collectAsState()
            val initialViewMode = remember {
                when (chatViewModel.requestedViewMode.value) {
                    "IDE" -> AppViewMode.IDE
                    "TERMINAL" -> AppViewMode.TERMINAL
                    "BROWSER" -> AppViewMode.BROWSER
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
                    }
                    chatViewModel.consumeRequestedViewMode()
                }
            }

            val isFloatingSwitcherEnabled by chatViewModel.isFloatingSwitcherEnabled.collectAsState()
            val floatingSwitcherOrientation by chatViewModel.floatingSwitcherOrientation.collectAsState()
            val floatingSwitcherItems by chatViewModel.floatingSwitcherItems.collectAsState()
            val floatingSwitcherAutoCollapseSec by chatViewModel.floatingSwitcherAutoCollapseSec.collectAsState()
            val floatingSwitcherPosX by chatViewModel.floatingSwitcherPosX.collectAsState()
            val floatingSwitcherPosY by chatViewModel.floatingSwitcherPosY.collectAsState()
            val isFloatingDiagnosticsEnabled by chatViewModel.isFloatingDiagnosticsEnabled.collectAsState()
            val isNetworkInspectorEnabled by chatViewModel.isNetworkInspectorEnabled.collectAsState()
            val isFloatingNetworkInspectorEnabled by chatViewModel.isFloatingNetworkInspectorEnabled.collectAsState()
            var showNetworkInspectorDialog by remember { mutableStateOf(false) }

            LaunchedEffect(isFloatingDiagnosticsEnabled) {
                if (isFloatingDiagnosticsEnabled) {
                    com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.start()
                } else {
                    com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.stop()
                }
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val view = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(currentViewMode, useDarkTheme) {
                val window = (context as? android.app.Activity)?.window
                if (window != null && !view.isInEditMode) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    if (currentViewMode == AppViewMode.TERMINAL) {
                        window.statusBarColor = android.graphics.Color.BLACK
                        window.navigationBarColor = android.graphics.Color.BLACK
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    } else {
                        val bgArgb = if (useDarkTheme) com.example.gemini.theme.ClaudeDarkBg.toArgb() else com.example.gemini.theme.ClaudeCream.toArgb()
                        window.statusBarColor = bgArgb
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

            val isTermuxPackage = remember { com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context) }
            var isInstalledState by remember { mutableStateOf(com.example.gemini.data.local.LocalEnvironmentManager.isInstalled(context)) }
            var hasSkippedInstaller by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            val showFullScreenInstaller = isTermuxPackage && !isInstalledState && !hasSkippedInstaller

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
                                if (com.example.gemini.data.local.LocalServerManager.hasServerScript(context)) {
                                    com.example.gemini.data.local.LocalServerManager.startServer(context, forceRestart = true)
                                }
                            }
                        )
                    } else {
                        BackHandler(enabled = currentViewMode != AppViewMode.CHAT && currentViewMode != AppViewMode.BROWSER) {
                            currentViewMode = if (previousViewMode != currentViewMode && previousViewMode != AppViewMode.BROWSER) previousViewMode else AppViewMode.CHAT
                        }

                        val terminalSessions by com.example.gemini.data.local.LocalTerminalManager.sessions.collectAsState()
                        var hasEverOpenedTerminal by remember { mutableStateOf(false) }
                        if (currentViewMode == AppViewMode.TERMINAL) {
                            hasEverOpenedTerminal = true
                        }

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
                                    onNavigateToChat = { currentViewMode = AppViewMode.CHAT },
                                    onExecuteRunCommand = { cmd ->
                                        // Connect with terminal / chat execution
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
                                            currentViewMode = if (previousViewMode == AppViewMode.TERMINAL) AppViewMode.CHAT else previousViewMode
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
                                    onClose = { currentViewMode = if (previousViewMode == AppViewMode.BROWSER) AppViewMode.CHAT else previousViewMode }
                                )
                            }

                            // Floating App Switcher Across Whole App (Renders on top of Chat, IDE, Terminal, Browser)
                            if (isFloatingSwitcherEnabled) {
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
                                    onNavigateToTerminal = {
                                        previousViewMode = currentViewMode
                                        currentViewMode = AppViewMode.TERMINAL
                                    },
                                    onPositionSaved = { x, y ->
                                        chatViewModel.saveFloatingSwitcherPosition(x, y)
                                    }
                                )
                            }

                            // Floating Video Player Overlay for active background/in-app playback controls
                            com.example.gemini.ui.components.FloatingVideoPlayerOverlay()

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
                        }
                    }
                }
            }
        }
    }



    override fun onResume() {
        super.onResume()
        if (com.example.gemini.ui.components.PermissionUtils.hasNotificationPermission(this)) {
            com.example.gemini.data.service.TermuxService.start(this)
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(this)
        }
        lifecycleScope.launch {
            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
        handleIncomingFileIntent(intent)
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

    private fun handleIncomingFileIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri: android.net.Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }

        if (uri != null) {
            val intentMime = intent.type?.lowercase() ?: ""
            val crMime = try { contentResolver.getType(uri)?.lowercase() } catch (_: Exception) { null } ?: ""
            val resolvedMime = intentMime.ifBlank { crMime }
            val uriPath = uri.path?.lowercase() ?: ""

            var fileName: String? = null
            if (uri.scheme == "content") {
                try {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) {
                                fileName = cursor.getString(nameIdx)?.lowercase()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            val displayName = fileName ?: uri.lastPathSegment?.substringAfterLast('/')?.lowercase() ?: uriPath.substringAfterLast('/')

            val isMarkdown = resolvedMime.contains("markdown") || displayName.endsWith(".md") || displayName.endsWith(".markdown") || uriPath.endsWith(".md") || uriPath.endsWith(".markdown")
            val isAntigem = displayName.endsWith(".antigem") || displayName.endsWith(".jsonl.antigem") || uriPath.endsWith(".antigem") || uriPath.endsWith(".jsonl.antigem")

            when {
                isMarkdown -> {
                    chatViewModel.loadMarkdownFromUri(this, uri)
                }
                isAntigem -> {
                    chatViewModel.loadSharedConversationFromUri(this, uri)
                }
                resolvedMime.startsWith("text/") || resolvedMime.contains("json") || resolvedMime.contains("javascript") ||
                resolvedMime.contains("xml") || resolvedMime.contains("sql") || resolvedMime.contains("html") ||
                resolvedMime.contains("css") || resolvedMime.contains("x-sh") || resolvedMime.contains("script") ||
                resolvedMime.contains("code") || displayName.endsWith(".html") || displayName.endsWith(".htm") ||
                displayName.endsWith(".js") || displayName.endsWith(".ts") || displayName.endsWith(".py") ||
                displayName.endsWith(".json") || displayName.endsWith(".txt") || displayName.endsWith(".xml") -> {
                    chatViewModel.openFileInIdeDirectly(this, uri)
                }
                else -> {
                    // Probe if text or antigem trajectory header
                    try {
                        val headerSample = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                            val chars = CharArray(150)
                            val read = it.read(chars, 0, 150)
                            if (read > 0) String(chars, 0, read) else ""
                        } ?: ""
                        if (headerSample.contains("\"antigem_trajectory\"")) {
                            chatViewModel.loadSharedConversationFromUri(this, uri)
                        } else {
                            chatViewModel.openFileInIdeDirectly(this, uri)
                        }
                    } catch (_: Exception) {
                        chatViewModel.openFileInIdeDirectly(this, uri)
                    }
                }
            }
        }
    }
}
