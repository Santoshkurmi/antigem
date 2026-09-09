package com.example.gemini.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class SettingsSection(val title: String, val subtitle: String) {
    MAIN("Settings & Preferences", "Configure your AntiGem experience"),
    APPEARANCE("Appearance & Theme", "Theme, dark mode, and chat font scaling"),
    SERVERS("Servers & Connectivity", "Configure AGY Hub (8090) and IDE Bridge (8080)"),
    TERMINAL("Terminal & Shell", "SSH configuration, local tools, and styling")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel> = AiModel.DEFAULT_MODELS,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    isServerListening: Boolean = false,
    isServerLoading: Boolean = false,
    contextWindowLimit: Int = 10,
    summaryModelId: String = "always_ask",
    isDevModeEnabled: Boolean = false,
    chatFontScale: Float = 1.0f,
    themeMode: String = "SYSTEM",
    agyHubUrl: String = "http://127.0.0.1:8090",
    agyBridgeHttpUrl: String = "http://127.0.0.1:8080",
    isServerOnline: Boolean = true,
    isBridgeOnline: Boolean = false,
    useSshTerminal: Boolean = false,
    sshHost: String = "127.0.0.1",
    sshPort: Int = 8022,
    sshUser: String = "root",
    sshPass: String = "root",
    terminalFontSize: Int = 13,
    terminalCursorStyle: String = "BLOCK",
    terminalBufferSize: Int = 2000,
    terminalTheme: String = "DEFAULT",
    isLocalToolsEnabled: Boolean = false,
    isLocalToolsInstalled: Boolean = false,
    onLoginWithGoogle: () -> Unit,
    onToggleServer: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onManualTokenEntered: (String) -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit = {},
    onSetSummaryModelId: (String) -> Unit = {},
    onSetChatFontScale: (Float) -> Unit = {},
    onSetThemeMode: (String) -> Unit = {},
    onSaveServerUrls: (String, String) -> Unit = { _, _ -> },
    onSaveTerminalPreferences: (Int, String, Int, String) -> Unit = { _, _, _, _ -> },
    onToggleDevMode: (Boolean) -> Unit = {},
    onToggleUseSshTerminal: (Boolean) -> Unit = {},
    onSaveSshSettings: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
    onToggleLocalTools: (Boolean) -> Unit = {},
    onInstallLocalTools: () -> Unit = {},
    onOpenLocalTerminal: () -> Unit = {},
    onResetLocalTools: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentSection by remember { mutableStateOf(SettingsSection.MAIN) }

    // Intercept system Back button
    BackHandler(enabled = true) {
        if (currentSection != SettingsSection.MAIN) {
            currentSection = SettingsSection.MAIN
        } else {
            onDismiss()
        }
    }

    val isDark = isAppInDarkTheme()
    val cardBg = if (isDark) ClaudeDarkSurface else Color.White
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top App Bar
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentSection.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentSection != SettingsSection.MAIN) {
                            Text(
                                text = currentSection.subtitle,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentSection != SettingsSection.MAIN) {
                            currentSection = SettingsSection.MAIN
                        } else {
                            onDismiss()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Done",
                            color = ClaudeTerracotta,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )

            HorizontalDivider(
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            // Animated Screen Content
            AnimatedContent(
                targetState = currentSection,
                transitionSpec = {
                    if (targetState != SettingsSection.MAIN) {
                        (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> -width / 4 } + fadeOut(animationSpec = tween(180)))
                    } else {
                        (slideInHorizontally { width -> -width / 4 } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(180)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                label = "SettingsSectionTransition"
            ) { section ->
                when (section) {
                    SettingsSection.MAIN -> MainSettingsMenu(
                        themeMode = themeMode,
                        chatFontScale = chatFontScale,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        useSshTerminal = useSshTerminal,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onNavigate = { currentSection = it }
                    )

                    SettingsSection.APPEARANCE -> AppearanceSubScreen(
                        themeMode = themeMode,
                        chatFontScale = chatFontScale,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetThemeMode = onSetThemeMode,
                        onSetChatFontScale = onSetChatFontScale
                    )

                    SettingsSection.SERVERS -> ServersSubScreen(
                        currentHubUrl = agyHubUrl,
                        currentBridgeUrl = agyBridgeHttpUrl,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSaveServerUrls = onSaveServerUrls
                    )

                    SettingsSection.TERMINAL -> TerminalSubScreen(
                        useSshTerminal = useSshTerminal,
                        sshHost = sshHost,
                        sshPort = sshPort,
                        sshUser = sshUser,
                        sshPass = sshPass,
                        isLocalToolsEnabled = isLocalToolsEnabled,
                        isLocalToolsInstalled = isLocalToolsInstalled,
                        terminalFontSize = terminalFontSize,
                        terminalCursorStyle = terminalCursorStyle,
                        terminalBufferSize = terminalBufferSize,
                        terminalTheme = terminalTheme,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onToggleUseSshTerminal = onToggleUseSshTerminal,
                        onSaveSshSettings = onSaveSshSettings,
                        onToggleLocalTools = onToggleLocalTools,
                        onInstallLocalTools = onInstallLocalTools,
                        onResetLocalTools = onResetLocalTools,
                        onSaveTerminalPreferences = onSaveTerminalPreferences,
                        onOpenLocalTerminal = onOpenLocalTerminal
                    )
                }
            }
        }
    }
}

@Composable
private fun MainSettingsMenu(
    themeMode: String,
    chatFontScale: Float,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean,
    useSshTerminal: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onNavigate: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section 1: Appearance & Display
        val themeLabel = when (themeMode) {
            "DARK" -> "Dark Mode"
            "LIGHT" -> "Light Mode"
            else -> "Auto (System)"
        }
        SettingsCategoryCard(
            icon = Icons.Outlined.Palette,
            iconTint = ClaudeTerracotta,
            title = "Appearance & Theme",
            subtitle = "$themeLabel • ${(chatFontScale * 100).toInt()}% text scale",
            badgeText = themeLabel,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.APPEARANCE) }
        )

        // Section 2: Servers & Network
        val (serverBadge, serverColor) = when {
            isServerOnline && isBridgeOnline -> "Online" to QuotaGreen
            isServerOnline || isBridgeOnline -> "Partial" to Color(0xFFFFA000)
            else -> "Offline" to Color.Red
        }
        SettingsCategoryCard(
            icon = Icons.Outlined.Dns,
            iconTint = serverColor,
            title = "Servers & Connectivity",
            subtitle = "AGY Hub: 8090 • IDE Bridge: 8080",
            badgeText = serverBadge,
            badgeColor = serverColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.SERVERS) }
        )

        // Section 3: Terminal & Shell
        SettingsCategoryCard(
            icon = Icons.Outlined.Terminal,
            iconTint = Color(0xFF00ACC1),
            title = "Terminal & Shell Execution",
            subtitle = if (useSshTerminal) "SSH Mode (Termux sshd)" else "Local Linux Environment",
            badgeText = if (useSshTerminal) "SSH" else "Local",
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.TERMINAL) }
        )
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = ClaudeTerracotta,
    cardBg: Color,
    cardBorder: BorderStroke,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badgeText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ==========================================
// SUB-SCREEN 1: APPEARANCE & THEME
// ==========================================
@Composable
private fun AppearanceSubScreen(
    themeMode: String,
    chatFontScale: Float,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetThemeMode: (String) -> Unit,
    onSetChatFontScale: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme Selector Header
        Text(
            text = "Color Theme",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 3 Theme Options Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val themes = listOf(
                Triple("SYSTEM", "Auto (System)", Icons.Outlined.BrightnessAuto),
                Triple("LIGHT", "Light", Icons.Outlined.LightMode),
                Triple("DARK", "Dark", Icons.Outlined.DarkMode)
            )

            themes.forEach { (mode, label, icon) ->
                val isSelected = themeMode.equals(mode, ignoreCase = true)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSetThemeMode(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else cardBg,
                    border = BorderStroke(
                        1.5.dp,
                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Font Scaling Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Chat Text Scaling (${(chatFontScale * 100).toInt()}%)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scales messages, markdown, and code blocks",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (chatFontScale != 1.0f) {
                TextButton(
                    onClick = { onSetChatFontScale(1.0f) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Reset (1.0x)", fontSize = 12.sp, color = ClaudeTerracotta)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("A", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.width(10.dp))
                    Slider(
                        value = chatFontScale,
                        onValueChange = onSetChatFontScale,
                        valueRange = 0.75f..1.60f,
                        steps = 16,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = ClaudeTerracotta,
                            activeTrackColor = ClaudeTerracotta
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("A", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(modifier = Modifier.height(8.dp))

                val presets = listOf(0.85f to "Small", 1.00f to "Normal", 1.15f to "Medium", 1.30f to "Large", 1.50f to "Huge")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { (scale, label) ->
                        val isSelected = kotlin.math.abs(chatFontScale - scale) < 0.04f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSetChatFontScale(scale) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Chat Message Preview Box
        Text(
            text = "Live Preview",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Simulated User message
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "How does AntiGem connect to AGY Hub?",
                            fontSize = (13 * chatFontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Simulated Assistant reply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "AntiGem connects via gRPC-Web on port 8090 to stream live agent steps and tool executions.",
                                fontSize = (13 * chatFontScale).sp,
                                lineHeight = (18 * chatFontScale).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 2: SERVERS & CONNECTIVITY
// ==========================================
@Composable
private fun ServersSubScreen(
    currentHubUrl: String,
    currentBridgeUrl: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSaveServerUrls: (String, String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Helper to split "http://127.0.0.1:8090" into host and port
    fun parseHostPort(url: String, defaultPort: String): Pair<String, String> {
        val clean = url.replace("http://", "").replace("https://", "").trimEnd('/')
        val parts = clean.split(":")
        val host = parts.getOrNull(0)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        val port = parts.getOrNull(1)?.ifBlank { defaultPort } ?: defaultPort
        return host to port
    }

    val (initHubHost, initHubPort) = remember(currentHubUrl) { parseHostPort(currentHubUrl, "8090") }
    val (initBridgeHost, initBridgePort) = remember(currentBridgeUrl) { parseHostPort(currentBridgeUrl, "8080") }

    var hubHost by remember(initHubHost) { mutableStateOf(initHubHost) }
    var hubPort by remember(initHubPort) { mutableStateOf(initHubPort) }
    var bridgeHost by remember(initBridgeHost) { mutableStateOf(initBridgeHost) }
    var bridgePort by remember(initBridgePort) { mutableStateOf(initBridgePort) }

    var hubTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingHub by remember { mutableStateOf(false) }

    var bridgeTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingBridge by remember { mutableStateOf(false) }

    var saveFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // AGY Hub Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Dns, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Antigravity Hub (Daemon)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Primary gRPC-Web Language Server (agy --hub)", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServerOnline || hubTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hubHost,
                        onValueChange = { hubHost = it; saveFeedback = null },
                        label = { Text("Hub IP / Host") },
                        modifier = Modifier.weight(2.2f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = hubPort,
                        onValueChange = { hubPort = it; saveFeedback = null },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Endpoint: http://$hubHost:$hubPort",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                if (hubTestStatus != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = hubTestStatus ?: "",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (hubTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        isTestingHub = true
                        hubTestStatus = "Pinging http://$hubHost:$hubPort..."
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val u = URL("http://$hubHost:$hubPort")
                                    val conn = u.openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    val code = conn.responseCode
                                    conn.disconnect()
                                    code in 200..499
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            isTestingHub = false
                            hubTestStatus = if (ok) "✓ Connected to AGY Hub successfully!" else "✗ Failed to reach Hub on port $hubPort"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isTestingHub
                ) {
                    if (isTestingHub) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Hub Connection", fontSize = 12.sp)
                }
            }
        }

        // AGY IDE Bridge Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.CloudSync, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "IDE Bridge (HTTP / WebSocket)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Instance management & prewarm proxy (port 8080)", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isBridgeOnline || bridgeTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bridgeHost,
                        onValueChange = { bridgeHost = it; saveFeedback = null },
                        label = { Text("Bridge IP / Host") },
                        modifier = Modifier.weight(2.2f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = bridgePort,
                        onValueChange = { bridgePort = it; saveFeedback = null },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Endpoint: http://$bridgeHost:$bridgePort",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                if (bridgeTestStatus != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = bridgeTestStatus ?: "",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (bridgeTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        isTestingBridge = true
                        bridgeTestStatus = "Pinging http://$bridgeHost:$bridgePort..."
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val base = "http://${bridgeHost.trim()}:${bridgePort.trim()}"
                                val endpoints = listOf("$base/api/health", "$base/health", base)
                                var reachable = false
                                for (ep in endpoints) {
                                    try {
                                        val u = URL(ep)
                                        val conn = (u.openConnection() as HttpURLConnection).apply {
                                            connectTimeout = 3000
                                            readTimeout = 3000
                                            requestMethod = "GET"
                                            instanceFollowRedirects = true
                                        }
                                        val code = conn.responseCode
                                        conn.disconnect()
                                        if (code in 200..399) {
                                            reachable = true
                                            break
                                        }
                                    } catch (_: Exception) {}
                                }
                                reachable
                            }
                            isTestingBridge = false
                            bridgeTestStatus = if (ok) "✓ Connected to IDE Bridge successfully!" else "✗ Failed to reach Bridge on port $bridgePort"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isTestingBridge
                ) {
                    if (isTestingBridge) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Bridge Connection", fontSize = 12.sp)
                }
            }
        }

        if (saveFeedback != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = QuotaGreen.copy(alpha = 0.12f)
            ) {
                Text(
                    text = saveFeedback ?: "",
                    fontSize = 12.sp,
                    color = QuotaGreen,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Button(
            onClick = {
                val fullHub = "http://${hubHost.trim()}:${hubPort.trim()}"
                val fullBridge = "http://${bridgeHost.trim()}:${bridgePort.trim()}"
                onSaveServerUrls(fullHub, fullBridge)
                saveFeedback = "✓ Server addresses updated! Reconnecting streams..."
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Reconnect Servers", fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// SUB-SCREEN 3: TERMINAL & SHELL SETTINGS
// ==========================================
@Composable
private fun TerminalSubScreen(
    useSshTerminal: Boolean,
    sshHost: String,
    sshPort: Int,
    sshUser: String,
    sshPass: String,
    isLocalToolsEnabled: Boolean,
    isLocalToolsInstalled: Boolean,
    terminalFontSize: Int,
    terminalCursorStyle: String,
    terminalBufferSize: Int,
    terminalTheme: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    onToggleUseSshTerminal: (Boolean) -> Unit,
    onSaveSshSettings: (String, Int, String, String) -> Unit,
    onToggleLocalTools: (Boolean) -> Unit,
    onInstallLocalTools: () -> Unit,
    onResetLocalTools: () -> Unit,
    onSaveTerminalPreferences: (Int, String, Int, String) -> Unit,
    onOpenLocalTerminal: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var hostState by remember(sshHost) { mutableStateOf(sshHost) }
    var portState by remember(sshPort) { mutableStateOf(sshPort.toString()) }
    var userState by remember(sshUser) { mutableStateOf(sshUser) }
    var passState by remember(sshPass) { mutableStateOf(sshPass) }
    var isTestingSsh by remember { mutableStateOf(false) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }

    var fontSizeState by remember(terminalFontSize) { mutableIntStateOf(terminalFontSize) }
    var cursorStyleState by remember(terminalCursorStyle) { mutableStateOf(terminalCursorStyle) }
    var bufferSizeState by remember(terminalBufferSize) { mutableIntStateOf(terminalBufferSize) }
    var themeState by remember(terminalTheme) { mutableStateOf(terminalTheme) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = if (useSshTerminal) QuotaGreen else ClaudeTerracotta,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Use Terminal via SSH", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (useSshTerminal) QuotaGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = if (useSshTerminal) "SSH Mode" else "Local Mode",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (useSshTerminal) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = if (useSshTerminal) "Direct SSH to Termux sshd without local rootfs" else "Built-in chroot rootfs environment",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = useSshTerminal,
                        onCheckedChange = { onToggleUseSshTerminal(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = QuotaGreen
                        )
                    )
                }

                if (useSshTerminal) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostState,
                            onValueChange = {
                                hostState = it
                                onSaveSshSettings(it, portState.toIntOrNull() ?: 8022, userState, passState)
                            },
                            label = { Text("SSH Host") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = portState,
                            onValueChange = {
                                portState = it
                                onSaveSshSettings(hostState, it.toIntOrNull() ?: 8022, userState, passState)
                            },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = userState,
                            onValueChange = {
                                userState = it
                                onSaveSshSettings(hostState, portState.toIntOrNull() ?: 8022, it, passState)
                            },
                            label = { Text("User") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = passState,
                            onValueChange = {
                                passState = it
                                onSaveSshSettings(hostState, portState.toIntOrNull() ?: 8022, userState, it)
                            },
                            label = { Text("Password") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    if (sshTestStatus != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = sshTestStatus ?: "",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (sshTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            isTestingSsh = true
                            sshTestStatus = "Testing SSH connection to $hostState..."
                            coroutineScope.launch {
                                val res = com.example.gemini.data.ssh.TermuxSshManager.testConnection(
                                    host = hostState.trim(),
                                    port = portState.toIntOrNull() ?: 8022,
                                    user = userState.trim(),
                                    pass = passState
                                )
                                isTestingSsh = false
                                sshTestStatus = if (res.isSuccess) "✓ SSH connection successful!" else "✗ Failed: ${res.exceptionOrNull()?.localizedMessage ?: "Connection refused"}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTestingSsh
                    ) {
                        if (isTestingSsh) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Test SSH Connection", fontSize = 12.sp)
                    }
                } else {
                    // Local tools mode details
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Embedded Linux Rootfs", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = if (isLocalToolsInstalled) "Installed and ready" else "Not installed (>30MB download)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLocalToolsInstalled) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isLocalToolsInstalled) "Installed ✓" else "Not Installed",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLocalToolsInstalled) QuotaGreen else ClaudeTerracotta,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isLocalToolsInstalled) {
                            OutlinedButton(onClick = onInstallLocalTools, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                Text("Reinstall", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onResetLocalTools,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                            ) {
                                Text("Reset Rootfs", fontSize = 12.sp, color = Color.Red)
                            }
                        } else {
                            Button(
                                onClick = onInstallLocalTools,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text("Download & Install Local Tools", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Terminal Appearance Customization Card
        Text(
            text = "Terminal Customization",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Font Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font Size", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("${fontSizeState}pt", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                }
                Slider(
                    value = fontSizeState.toFloat(),
                    onValueChange = {
                        fontSizeState = it.toInt()
                        onSaveTerminalPreferences(fontSizeState, cursorStyleState, bufferSizeState, themeState)
                    },
                    valueRange = 10f..24f,
                    steps = 14,
                    colors = SliderDefaults.colors(thumbColor = ClaudeTerracotta, activeTrackColor = ClaudeTerracotta)
                )

                // Cursor Style
                Text("Cursor Style", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cursorOptions = listOf("BLOCK" to "Block █", "UNDERLINE" to "Underline  ", "BAR" to "Bar |")
                    cursorOptions.forEach { (style, label) ->
                        val isSel = cursorStyleState == style
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    cursorStyleState = style
                                    onSaveTerminalPreferences(fontSizeState, style, bufferSizeState, themeState)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // Buffer Size
                Text("Scrollback Buffer", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val buffers = listOf(1000 to "1,000", 2000 to "2,000", 5000 to "5,000", 10000 to "10,000")
                    buffers.forEach { (buf, label) ->
                        val isSel = bufferSizeState == buf
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    bufferSizeState = buf
                                    onSaveTerminalPreferences(fontSizeState, cursorStyleState, buf, themeState)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = label, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }

        // Action Button: Open Terminal
        Button(
            onClick = onOpenLocalTerminal,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Launch Terminal Now", fontWeight = FontWeight.Bold)
        }
    }
}

