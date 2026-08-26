package com.example.gemini

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

        handleOAuthIntent(intent)

        setContent {
            GeminiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentViewMode by remember { mutableStateOf(AppViewMode.CHAT) }

                    Crossfade(targetState = currentViewMode, label = "ScreenTransition") { mode ->
                        when (mode) {
                            AppViewMode.CHAT -> {
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    onNavigateToIde = { currentViewMode = AppViewMode.IDE }
                                )
                            }
                            AppViewMode.IDE -> {
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
