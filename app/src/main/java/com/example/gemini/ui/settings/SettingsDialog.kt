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
import com.example.gemini.data.automation.AndroidAutomationService
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
    TERMINAL("Terminal & Shell", "SSH configuration, local tools, and styling"),
    AI_MODELS("AI Models & Account", "Google account, enabled models, and quotas"),
    PERMISSIONS("Permissions & Access", "Storage access and Android automation service"),
    DEVELOPER("Developer & Diagnostics", "Debug tools, verbose output, and daemon health")
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
                        agyHubUrl = agyHubUrl,
                        isServerOnline = isServerOnline,
                        useSshTerminal = useSshTerminal,
                        userEmail = userEmail,
                        tier = tier,
                        isDevModeEnabled = isDevModeEnabled,
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

                    SettingsSection.AI_MODELS -> AiModelsSubScreen(
                        userEmail = userEmail,
                        projectId = projectId,
                        tier = tier,
                        availableModels = availableModels,
                        enabledModelIds = enabledModelIds,
                        quotas = quotas,
                        contextWindowLimit = contextWindowLimit,
                        summaryModelId = summaryModelId,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onLoginWithGoogle = onLoginWithGoogle,
                        onLogout = onLogout,
                        onToggleModelEnabled = onToggleModelEnabled,
                        onEnableAllModels = onEnableAllModels,
                        onRefreshQuotas = onRefreshQuotas,
                        onSetContextWindowLimit = onSetContextWindowLimit,
                        onSetSummaryModelId = onSetSummaryModelId
                    )

                    SettingsSection.PERMISSIONS -> PermissionsSubScreen(
                        context = context,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )

                    SettingsSection.DEVELOPER -> DeveloperSubScreen(
                        isDevModeEnabled = isDevModeEnabled,
                        isServerListening = isServerListening,
                        isServerLoading = isServerLoading,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onToggleDevMode = onToggleDevMode,
                        onToggleServer = onToggleServer,
                        onManualTokenEntered = onManualTokenEntered
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
    agyHubUrl: String,
    isServerOnline: Boolean,
    useSshTerminal: Boolean,
    userEmail: String?,
    tier: String,
    isDevModeEnabled: Boolean,
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
        // Quick Status Header Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ClaudeTerracotta.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isServerOnline) QuotaGreen else Color.Red)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isServerOnline) "AGY Hub Connected" else "AGY Hub Disconnected",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = agyHubUrl,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = tier.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClaudeTerracotta,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

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
        SettingsCategoryCard(
            icon = Icons.Outlined.Dns,
            iconTint = QuotaGreen,
            title = "Servers & Connectivity",
            subtitle = "AGY Hub: 8090 • IDE Bridge: 8080",
            badgeText = if (isServerOnline) "Online" else "Offline",
            badgeColor = if (isServerOnline) QuotaGreen else Color.Red,
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

        // Section 4: AI Models & Account
        SettingsCategoryCard(
            icon = Icons.Outlined.AutoAwesome,
            iconTint = Color(0xFF7E57C2),
            title = "AI Models & Account",
            subtitle = userEmail ?: "Google Account & Model Quotas",
            badgeText = tier.uppercase(),
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.AI_MODELS) }
        )

        // Section 5: Permissions & Automation
        SettingsCategoryCard(
            icon = Icons.Outlined.Security,
            iconTint = Color(0xFF3F51B5),
            title = "Permissions & Automation",
            subtitle = "Storage (/sdcard) and UI automation service",
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.PERMISSIONS) }
        )

        // Section 6: Developer & Diagnostics
        SettingsCategoryCard(
            icon = Icons.Outlined.Code,
            iconTint = Color(0xFFFFB300),
            title = "Developer & Diagnostics",
            subtitle = "Debug inspection, verbose logs, and daemon health",
            badgeText = if (isDevModeEnabled) "DEV ON" else null,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.DEVELOPER) }
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
                        if (isSelected) 1.6.dp else 1.dp,
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
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(13.dp)
                            )
                        }
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
                            .background(if (isServerOnline) QuotaGreen else Color.Red)
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
                        hubTestStatus = "Pinging http://$hubHost:$hubPort/csrf_token..."
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val u = URL("http://$hubHost:$hubPort/csrf_token")
                                    val conn = u.openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    conn.requestMethod = "GET"
                                    val code = conn.responseCode
                                    conn.disconnect()
                                    code in 200..399
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
                        bridgeTestStatus = "Pinging http://$bridgeHost:$bridgePort/health..."
                        coroutineScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    val u = URL("http://$bridgeHost:$bridgePort/health")
                                    val conn = u.openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    val code = conn.responseCode
                                    conn.disconnect()
                                    code in 200..399
                                } catch (e: Exception) {
                                    false
                                }
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

// ==========================================
// SUB-SCREEN 4: AI MODELS & ACCOUNT
// ==========================================
@Composable
private fun AiModelsSubScreen(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel>,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    contextWindowLimit: Int,
    summaryModelId: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    onLoginWithGoogle: () -> Unit,
    onLogout: () -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit,
    onSetSummaryModelId: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Account Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.AccountCircle, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = userEmail ?: "Google Account", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Project: $projectId • Tier: ${tier.uppercase()}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (userEmail != null) {
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                    ) {
                        Text("Sign Out", fontSize = 12.sp, color = Color.Red)
                    }
                } else {
                    Button(
                        onClick = onLoginWithGoogle,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Text("Connect Google Account", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Quotas & Usage Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Model Quotas & Limits", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = onRefreshQuotas, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = ClaudeTerracotta)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh", fontSize = 12.sp, color = ClaudeTerracotta)
                    }
                }

                if (quotas.isEmpty()) {
                    Text(text = "Quotas will load once connected to active models.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    quotas.forEach { q ->
                        val displayName = availableModels.find { it.id == q.modelId }?.displayName ?: q.modelId
                        val frac = q.remainingFraction ?: 1.0f
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(text = "${(frac * 100).toInt()}% remaining", fontSize = 11.sp, color = if (frac > 0.3f) QuotaGreen else Color.Red)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = if (frac > 0.3f) QuotaGreen else Color.Red,
                                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }
        }

        // Context Window Limit Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Context Window Limit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("$contextWindowLimit msgs", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                }
                Text("Controls recent conversation turns included in context", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(6.dp))
                Slider(
                    value = contextWindowLimit.toFloat(),
                    onValueChange = { onSetContextWindowLimit(it.toInt()) },
                    valueRange = 5f..30f,
                    steps = 25,
                    colors = SliderDefaults.colors(thumbColor = ClaudeTerracotta, activeTrackColor = ClaudeTerracotta)
                )
            }
        }

        // Enabled Models List
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Active Models", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = onEnableAllModels, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Enable All", fontSize = 12.sp, color = ClaudeTerracotta)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                availableModels.forEach { model ->
                    val isEnabled = enabledModelIds?.contains(model.id) ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(model.id, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { onToggleModelEnabled(model.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ClaudeTerracotta)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 5: PERMISSIONS & SYSTEM ACCESS
// ==========================================
@Composable
private fun PermissionsSubScreen(
    context: Context,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val isAutomationActive by AndroidAutomationService.isServiceActive.collectAsState()
    var isStorageGranted by remember { mutableStateOf(isStoragePermissionGranted(context)) }

    DisposableEffect(Unit) {
        isStorageGranted = isStoragePermissionGranted(context)
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage Permission Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Folder, contentDescription = null, tint = if (isStorageGranted) QuotaGreen else ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("All Files & Storage (/sdcard)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Read & write access for scripts, tools, and downloads", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isStorageGranted) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isStorageGranted) "Granted ✓" else "Missing",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isStorageGranted) QuotaGreen else ClaudeTerracotta,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                if (!isStorageGranted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { requestStoragePermission(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Text("Grant All Files Permission", fontSize = 12.sp)
                    }
                }
            }
        }

        // Accessibility & Automation Card
        val isAccessibilityEnabled = isAutomationActive || AndroidAutomationService.isRunning()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.TouchApp, contentDescription = null, tint = if (isAccessibilityEnabled) QuotaGreen else ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("UI Automation Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Allows AI to click, tap, and interact with Android apps", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isAccessibilityEnabled) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isAccessibilityEnabled) "Active ✓" else "Disabled",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityEnabled) QuotaGreen else ClaudeTerracotta,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Open Android Accessibility Settings", fontSize = 12.sp)
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 6: DEVELOPER & DIAGNOSTICS
// ==========================================
@Composable
private fun DeveloperSubScreen(
    isDevModeEnabled: Boolean,
    isServerListening: Boolean,
    isServerLoading: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onToggleDevMode: (Boolean) -> Unit,
    onToggleServer: (Boolean) -> Unit,
    onManualTokenEntered: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var manualToken by remember { mutableStateOf("") }
    var daemonStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Dev Mode Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer Inspection Mode", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Show raw trajectory step indices and detailed logs", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Switch(
                        checked = isDevModeEnabled,
                        onCheckedChange = onToggleDevMode,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ClaudeTerracotta)
                    )
                }
            }
        }

        // Termux Daemon Health Check
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Termux Daemon Manager", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Background keep-alive and RPC reconnect", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

                if (daemonStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(daemonStatus ?: "", fontSize = 11.5.sp, color = QuotaGreen, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        daemonStatus = "Pinging Termux daemon..."
                        coroutineScope.launch {
                            com.example.gemini.data.daemon.TermuxDaemonManager.checkHealthAndReconnect(isSilent = false)
                            daemonStatus = "✓ Daemon health check executed!"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Trigger Health Check & Reconnect", fontSize = 12.sp)
                }
            }
        }

        // Manual Token Entry
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Manual OAuth / Token Injection", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Paste raw OAuth response URL or access token directly", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = { Text("Token or callback URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (manualToken.isNotBlank()) {
                            onManualTokenEntered(manualToken.trim())
                            manualToken = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Apply Token", fontSize = 12.sp)
                }
            }
        }
    }
}

// Storage permission helper
private fun isStoragePermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requestStoragePermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    } else {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
