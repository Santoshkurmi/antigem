package com.example.gemini

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.example.gemini.ui.ide.IdeScreen

enum class AppViewMode {
    CHAT,
    IDE
}

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        com.example.gemini.data.daemon.TermuxDaemonManager.init(this)

        handleOAuthIntent(intent)

        setContent {
            val themeMode by chatViewModel.themeMode.collectAsState(initial = "SYSTEM")
            val isSystemDark = com.example.gemini.theme.isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            GeminiTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentViewMode by remember { mutableStateOf(AppViewMode.CHAT) }

                    BackHandler(enabled = currentViewMode == AppViewMode.IDE) {
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
