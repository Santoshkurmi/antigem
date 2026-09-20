package com.example.gemini.ui.bubble

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.gemini.AppViewMode
import com.example.gemini.MainActivity
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.theme.isSystemInDarkThemeRobust
import com.example.gemini.ui.chat.ChatScreen
import com.example.gemini.ui.chat.ChatViewModel
import com.example.gemini.ui.chat.ChatViewModelHolder
import com.example.gemini.ui.components.LocalTerminalContent
import com.example.gemini.ui.ide.IdeScreen

class FloatingChatActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overridePendingTransition(0, 0)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        setContent {
            var showOverlayPermissionDialog by remember {
                mutableStateOf(!Settings.canDrawOverlays(this@FloatingChatActivity))
            }

            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            GeminiTheme(darkTheme = useDarkTheme) {
                if (showOverlayPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showOverlayPermissionDialog = false },
                        title = {
                            Text(
                                text = "Display Over Other Apps Permission",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        text = {
                            Text(
                                text = "To show the floating multitasking window above other apps smoothly, please grant 'Display over other apps' permission in Settings."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showOverlayPermissionDialog = false
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        ).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        startActivity(intent)
                                    }
                                }
                            ) {
                                Text("Open Settings")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showOverlayPermissionDialog = false }) {
                                Text("Continue")
                            }
                        }
                    )
                }

                FloatingChatWindow(
                    viewModel = chatViewModel,
                    onOpenFullApp = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                        overridePendingTransition(0, 0)
                    },
                    onMinimize = {
                        finish()
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
fun FloatingChatWindow(
    viewModel: ChatViewModel,
    onOpenFullApp: () -> Unit,
    onMinimize: () -> Unit
) {
    var currentViewMode by rememberSaveable { mutableStateOf(AppViewMode.CHAT) }
    var hasEverOpenedTerminal by rememberSaveable { mutableStateOf(false) }

    if (currentViewMode == AppViewMode.TERMINAL) {
        hasEverOpenedTerminal = true
    }

    BackHandler(enabled = currentViewMode == AppViewMode.IDE || currentViewMode == AppViewMode.TERMINAL) {
        currentViewMode = AppViewMode.CHAT
    }

    BackHandler(enabled = currentViewMode == AppViewMode.CHAT) {
        onMinimize()
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val isKeyboardOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > navInsets.getBottom(density) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onMinimize() }
            )
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 8.dp,
                    bottom = if (isKeyboardOpen) 0.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = if (isKeyboardOpen) {
                    Modifier
                        .fillMaxWidth(0.98f)
                        .weight(1f)
                } else {
                    Modifier
                        .fillMaxWidth(0.98f)
                        .fillMaxHeight(0.92f)
                }
            ) {
                // Multitasking Floating Window Card
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume clicks inside card
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Persistent Floating Chat Screen
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
                                viewModel = viewModel,
                                isInFloatingWindow = true,
                                onOpenFullScreen = onOpenFullApp,
                                onMinimizeWindow = onMinimize,
                                onNavigateToIde = { currentViewMode = AppViewMode.IDE },
                                onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL }
                            )
                        }

                        // Persistent Floating IDE Screen
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
                                viewModel = viewModel,
                                isVisible = currentViewMode == AppViewMode.IDE,
                                onNavigateToChat = { currentViewMode = AppViewMode.CHAT },
                                onExecuteRunCommand = { cmd ->
                                    // Connect with terminal / chat execution
                                }
                            )
                        }

                        // Persistent Floating Terminal Screen
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
                                LocalTerminalContent(
                                    onClose = { currentViewMode = AppViewMode.CHAT }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
