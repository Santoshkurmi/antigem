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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.ui.chat.ChatScreen
import com.example.gemini.ui.chat.ChatViewModel
import com.example.gemini.ui.chat.ChatViewModelHolder
import com.example.gemini.ui.ide.IdeScreen

enum class AppViewMode {
    CHAT,
    IDE,
    TERMINAL
}

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        com.example.gemini.data.service.TermuxService.start(this)
        com.example.gemini.data.daemon.TermuxDaemonManager.init(this)
        com.example.gemini.data.local.LocalTerminalManager.autoLaunchServerIfReady(this)
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

            val context = androidx.compose.ui.platform.LocalContext.current
            val isTermuxPackage = remember { com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context) }
            var isInstalledState by remember { mutableStateOf(com.example.gemini.data.local.LocalEnvironmentManager.isInstalled(context)) }
            var hasSkippedInstaller by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            val showFullScreenInstaller = isTermuxPackage && !isInstalledState && !hasSkippedInstaller

            GeminiTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showFullScreenInstaller) {
                        BackHandler {
                            hasSkippedInstaller = true
                        }
                        com.example.gemini.ui.settings.FullScreenLocalToolsInstaller(
                            authPreferences = chatViewModel.authPreferences,
                            onSkip = { hasSkippedInstaller = true },
                            onComplete = {
                                isInstalledState = true
                                hasSkippedInstaller = true
                                com.example.gemini.data.local.LocalTerminalManager.autoLaunchServerIfReady(context)
                            }
                        )
                    } else {
                        BackHandler(enabled = currentViewMode != AppViewMode.CHAT) {
                            currentViewMode = AppViewMode.CHAT
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
                                    onNavigateToIde = { currentViewMode = AppViewMode.IDE }
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
                        }
                    }
                }
            }
        }
    }



    override fun onResume() {
        super.onResume()
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
