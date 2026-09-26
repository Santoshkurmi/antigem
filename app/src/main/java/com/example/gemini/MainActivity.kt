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

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = com.example.gemini.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            var currentViewMode by remember { mutableStateOf(AppViewMode.CHAT) }
            var previousViewMode by remember { mutableStateOf(AppViewMode.CHAT) }

            val isFloatingSwitcherEnabled by chatViewModel.isFloatingSwitcherEnabled.collectAsState()
            val floatingSwitcherOrientation by chatViewModel.floatingSwitcherOrientation.collectAsState()
            val floatingSwitcherItems by chatViewModel.floatingSwitcherItems.collectAsState()
            val floatingSwitcherAutoCollapseSec by chatViewModel.floatingSwitcherAutoCollapseSec.collectAsState()
            val floatingSwitcherPosX by chatViewModel.floatingSwitcherPosX.collectAsState()
            val floatingSwitcherPosY by chatViewModel.floatingSwitcherPosY.collectAsState()

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
                                if (currentViewMode == AppViewMode.TERMINAL || hasEverOpenedTerminal) {
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
}
