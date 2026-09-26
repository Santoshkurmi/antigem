package com.example.gemini.ui.settings

import android.Manifest
import android.content.ComponentName
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class SettingsSection(val title: String, val subtitle: String) {
    MAIN("Settings & Preferences", "Configure your AntiGem experience"),
    APPEARANCE("Appearance & Theme", "Theme, dark mode, and chat font scaling"),
    SERVERS("Servers & Connectivity", "Configure AGY Hub and IDE Bridge endpoints"),
    MCP("MCP Servers", "Model Context Protocol tools & integrations"),
    SKILLS_PLUGINS("Skills & Plugins", "Agent capabilities, Google plugins, and extensions"),
    AUTOMATION("Automation & Device Tools", "Browser & Terminal AI agent permissions"),
    TERMINAL("Terminal & Shell", "SSH configuration, local tools, and styling"),
    COMMANDS("Commands & Permissions", "Auto-run policy, sandbox mode, and tool approvals")
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
    agyHubUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentHubUrl,
    agyBridgeHttpUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl,
    isServerOnline: Boolean = true,
    isBridgeOnline: Boolean = false,
    useSshTerminal: Boolean = false,
    sshHost: String = "127.0.0.1",
    sshPort: Int = 8022,
    sshUser: String = "root",
    sshPass: String = "root",
    terminalFontSize: Int = 14,
    terminalCursorStyle: String = "BAR",
    terminalBufferSize: Int = 20000,
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
    mcpServers: List<com.example.gemini.domain.model.McpServerState> = emptyList(),
    isMcpLoading: Boolean = false,
    isMcpRefreshing: Boolean = false,
    refreshingMcpServer: String? = null,
    mcpErrorMessage: String? = null,
    mcpStatusMessage: String? = null,
    onClearMcpStatus: () -> Unit = {},
    onRefreshMcpServers: () -> Unit = {},
    onRefreshMcpServer: (String) -> Unit = {},
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onSaveMcpServer: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit = { _, _ -> },
    onDeleteMcpServer: (String) -> Unit = {},
    availableCascadePlugins: List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (com.example.gemini.data.remote.dto.AvailableCascadePluginDto) -> Unit = {},
    allSkills: List<com.example.gemini.data.remote.dto.SkillDefinitionDto> = emptyList(),
    isSkillsLoading: Boolean = false,
    skillsFilterScope: String = "GLOBAL",
    onSetSkillsFilterScope: (String) -> Unit = {},
    onRefreshSkills: () -> Unit = {},
    installedPlugins: List<com.example.gemini.data.remote.dto.InstalledPluginDto> = emptyList(),
    isInstalledPluginsLoading: Boolean = false,
    onRefreshInstalledPlugins: () -> Unit = {},
    googlePluginsCatalog: List<com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto> = emptyList(),
    isGooglePluginsLoading: Boolean = false,
    installingGooglePluginId: String? = null,
    deletingPluginId: String? = null,
    onRefreshGooglePlugins: () -> Unit = {},
    onInstallGooglePlugin: (String, String) -> Unit = { _, _ -> },
    onDeletePlugin: (String, String) -> Unit = { _, _ -> },
    pluginActionStatusMessage: String? = null,
    pluginActionErrorMessage: String? = null,
    onClearPluginStatus: () -> Unit = {},
    isBrowserAutomationEnabled: Boolean = true,
    isTerminalAutomationEnabled: Boolean = true,
    onToggleBrowserAutomation: (Boolean) -> Unit = {},
    onToggleTerminalAutomation: (Boolean) -> Unit = {},
    isFloatingSwitcherEnabled: Boolean = true,
    floatingSwitcherOrientation: String = "HORIZONTAL",
    floatingSwitcherItems: List<String> = listOf("chat", "ide", "terminal", "browser"),
    floatingSwitcherAutoCollapseSec: Int = 0,
    onToggleFloatingSwitcher: (Boolean) -> Unit = {},
    onSetFloatingSwitcherOrientation: (String) -> Unit = {},
    onSetFloatingSwitcherItems: (List<String>) -> Unit = {},
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit = {},
    onResetFloatingSwitcherPosition: () -> Unit = {},
    commandAutoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
    commandSandboxEnabled: Boolean = false,
    requireApprovalForFileEdits: Boolean = false,
    defaultApprovalScope: String = "PERMISSION_SCOPE_ONCE",
    groupChatsByWorkspace: Boolean = false,
    onToggleGroupChatsByWorkspace: (Boolean) -> Unit = {},
    globalSecuritySettings: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings? = null,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean = false,
    projectsList: List<com.example.gemini.data.remote.AgyHubClient.ProjectItem> = emptyList(),
    isProjectsLoading: Boolean = false,
    onSetGlobalArtifactReviewMode: (String) -> Unit = {},
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit = { _, _ -> },
    onSetGlobalCustomTerminalPolicy: (String) -> Unit = {},
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit = {},
    onSetGlobalTerminalSandbox: (Boolean) -> Unit = {},
    onSetProjectInheritGlobal: (com.example.gemini.data.remote.AgyHubClient.ProjectItem) -> Unit = {},
    onSetProjectPreset: (com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit = { _, _, _ -> },
    onRefreshSecurityAndProjects: () -> Unit = {},
    onSetCommandAutoExecutionPolicy: (String) -> Unit = {},
    onSetCommandSandboxEnabled: (Boolean) -> Unit = {},
    onSetRequireApprovalForFileEdits: (Boolean) -> Unit = {},
    onSetDefaultApprovalScope: (String) -> Unit = {},
    onAddPermissionRule: (action: String, pattern: String, decision: String) -> Unit = { _, _, _ -> },
    onRemovePermissionRule: (rawRule: String) -> Unit = {},
    onChangePermissionRuleDecision: (rawRule: String, newDecision: String) -> Unit = { _, _ -> },
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
                        mcpServers = mcpServers,
                        allSkills = allSkills,
                        installedPlugins = installedPlugins,
                        isBrowserAutomationEnabled = isBrowserAutomationEnabled,
                        isTerminalAutomationEnabled = isTerminalAutomationEnabled,
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        commandSandboxEnabled = commandSandboxEnabled,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onNavigate = { currentSection = it }
                    )

                    SettingsSection.APPEARANCE -> AppearanceSubScreen(
                        themeMode = themeMode,
                        chatFontScale = chatFontScale,
                        groupChatsByWorkspace = groupChatsByWorkspace,
                        isFloatingSwitcherEnabled = isFloatingSwitcherEnabled,
                        floatingSwitcherOrientation = floatingSwitcherOrientation,
                        floatingSwitcherItems = floatingSwitcherItems,
                        floatingSwitcherAutoCollapseSec = floatingSwitcherAutoCollapseSec,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetThemeMode = onSetThemeMode,
                        onSetChatFontScale = onSetChatFontScale,
                        onToggleGroupChatsByWorkspace = onToggleGroupChatsByWorkspace,
                        onToggleFloatingSwitcher = onToggleFloatingSwitcher,
                        onSetFloatingSwitcherOrientation = onSetFloatingSwitcherOrientation,
                        onSetFloatingSwitcherItems = onSetFloatingSwitcherItems,
                        onSetFloatingSwitcherAutoCollapseSec = onSetFloatingSwitcherAutoCollapseSec,
                        onResetFloatingSwitcherPosition = onResetFloatingSwitcherPosition
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

                    SettingsSection.MCP -> McpSubScreen(
                        mcpServers = mcpServers,
                        isLoading = isMcpLoading,
                        isRefreshing = isMcpRefreshing,
                        refreshingServerName = refreshingMcpServer,
                        errorMessage = mcpErrorMessage,
                        statusMessage = mcpStatusMessage,
                        onClearStatus = onClearMcpStatus,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onRefreshAll = onRefreshMcpServers,
                        onRefreshServer = onRefreshMcpServer,
                        onToggleServer = onToggleMcpServer,
                        onSaveServer = onSaveMcpServer,
                        onDeleteServer = onDeleteMcpServer,
                        availableCascadePlugins = availableCascadePlugins,
                        isCascadePluginsLoading = isCascadePluginsLoading,
                        installingCascadePluginId = installingCascadePluginId,
                        onSearchCascadePlugins = onSearchCascadePlugins,
                        onInstallCascadePlugin = onInstallCascadePlugin
                    )

                    SettingsSection.SKILLS_PLUGINS -> SkillsAndPluginsSubScreen(
                        skills = allSkills,
                        isSkillsLoading = isSkillsLoading,
                        skillsFilterScope = skillsFilterScope,
                        onSetSkillsFilterScope = onSetSkillsFilterScope,
                        onRefreshSkills = onRefreshSkills,
                        installedPlugins = installedPlugins,
                        isInstalledPluginsLoading = isInstalledPluginsLoading,
                        onRefreshInstalledPlugins = onRefreshInstalledPlugins,
                        googlePlugins = googlePluginsCatalog,
                        isGooglePluginsLoading = isGooglePluginsLoading,
                        installingGooglePluginId = installingGooglePluginId,
                        deletingPluginId = deletingPluginId,
                        onRefreshGooglePlugins = onRefreshGooglePlugins,
                        onInstallGooglePlugin = onInstallGooglePlugin,
                        onDeletePlugin = onDeletePlugin,
                        statusMessage = pluginActionStatusMessage,
                        errorMessage = pluginActionErrorMessage,
                        onClearStatus = onClearPluginStatus,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )

                    SettingsSection.AUTOMATION -> AutomationSubScreen(
                        isBrowserAutomationEnabled = isBrowserAutomationEnabled,
                        isTerminalAutomationEnabled = isTerminalAutomationEnabled,
                        onToggleBrowserAutomation = onToggleBrowserAutomation,
                        onToggleTerminalAutomation = onToggleTerminalAutomation,
                        cardBg = cardBg,
                        cardBorder = cardBorder
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

                    SettingsSection.COMMANDS -> CommandsSubScreen(
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        commandSandboxEnabled = commandSandboxEnabled,
                        requireApprovalForFileEdits = requireApprovalForFileEdits,
                        defaultApprovalScope = defaultApprovalScope,
                        globalSecuritySettings = globalSecuritySettings,
                        globalSettingsError = globalSettingsError,
                        isGlobalSettingsLoading = isGlobalSettingsLoading,
                        projectsList = projectsList,
                        isProjectsLoading = isProjectsLoading,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetGlobalArtifactReviewMode = onSetGlobalArtifactReviewMode,
                        onSetGlobalSecurityPreset = onSetGlobalSecurityPreset,
                        onSetGlobalCustomTerminalPolicy = onSetGlobalCustomTerminalPolicy,
                        onSetGlobalCustomFileAccessPolicy = onSetGlobalCustomFileAccessPolicy,
                        onSetGlobalTerminalSandbox = onSetGlobalTerminalSandbox,
                        onSetProjectInheritGlobal = onSetProjectInheritGlobal,
                        onSetProjectPreset = onSetProjectPreset,
                        onRefreshSecurityAndProjects = onRefreshSecurityAndProjects,
                        onSetCommandAutoExecutionPolicy = onSetCommandAutoExecutionPolicy,
                        onSetCommandSandboxEnabled = onSetCommandSandboxEnabled,
                        onSetRequireApprovalForFileEdits = onSetRequireApprovalForFileEdits,
                        onSetDefaultApprovalScope = onSetDefaultApprovalScope,
                        onAddPermissionRule = onAddPermissionRule,
                        onRemovePermissionRule = onRemovePermissionRule,
                        onChangePermissionRuleDecision = onChangePermissionRuleDecision
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
    mcpServers: List<com.example.gemini.domain.model.McpServerState>,
    allSkills: List<com.example.gemini.data.remote.dto.SkillDefinitionDto> = emptyList(),
    installedPlugins: List<com.example.gemini.data.remote.dto.InstalledPluginDto> = emptyList(),
    isBrowserAutomationEnabled: Boolean,
    isTerminalAutomationEnabled: Boolean,
    commandAutoExecutionPolicy: String,
    commandSandboxEnabled: Boolean,
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
            subtitle = "AGY Hub & IDE Bridge endpoints",
            badgeText = serverBadge,
            badgeColor = serverColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.SERVERS) }
        )

        // Section 3: MCP Servers & Tools
        val activeMcpCount = mcpServers.count { it.isEnabled }
        val totalMcpCount = mcpServers.size
        val mcpBadge = when {
            totalMcpCount == 0 -> "0 Configured"
            activeMcpCount > 0 -> "$activeMcpCount Active"
            else -> "Disabled"
        }
        val mcpColor = when {
            activeMcpCount > 0 -> QuotaGreen
            totalMcpCount > 0 -> Color(0xFFFFA000)
            else -> ClaudeTerracotta
        }
        SettingsCategoryCard(
            icon = Icons.Outlined.Extension,
            iconTint = mcpColor,
            title = "MCP Servers & Tools",
            subtitle = if (totalMcpCount > 0) "$totalMcpCount server(s) • global mcp_config.json" else "Model Context Protocol tools & integrations",
            badgeText = mcpBadge,
            badgeColor = mcpColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.MCP) }
        )

        // Section 3.5: Skills & Plugins
        val totalSkillsCount = allSkills.size
        val totalPluginsCount = installedPlugins.size
        val skillsBadge = "$totalSkillsCount Skills • $totalPluginsCount Plugins"
        SettingsCategoryCard(
            icon = Icons.Outlined.AutoAwesome,
            iconTint = GeminiBlue,
            title = "Skills & Plugins",
            subtitle = "Installed skills, Google plugins catalog, and markdown guides",
            badgeText = skillsBadge,
            badgeColor = GeminiBlue,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.SKILLS_PLUGINS) }
        )

        // Section 4: Automation & Device Bridge
        val autoActiveCount = (if (isBrowserAutomationEnabled) 1 else 0) + (if (isTerminalAutomationEnabled) 1 else 0)
        val (autoBadge, autoColor) = when (autoActiveCount) {
            2 -> "Full Access" to QuotaGreen
            1 -> "Partial" to Color(0xFF00ACC1)
            else -> "Disabled" to Color(0xFFFFA000)
        }
        val autoSummary = when {
            isBrowserAutomationEnabled && isTerminalAutomationEnabled -> "Browser: ON • Terminal: ON"
            isBrowserAutomationEnabled -> "Browser: ON • Terminal: OFF"
            isTerminalAutomationEnabled -> "Browser: OFF • Terminal: ON"
            else -> "All Automation Disabled"
        }
        SettingsCategoryCard(
            icon = Icons.Outlined.SmartToy,
            iconTint = autoColor,
            title = "Automation & Device Tools",
            subtitle = autoSummary,
            badgeText = autoBadge,
            badgeColor = autoColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.AUTOMATION) }
        )

        // Section 5: Terminal & Shell
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

        // Section 5: Commands & Permissions
        val (policyBadge, policyColor) = when {
            commandAutoExecutionPolicy.contains("EAGER", ignoreCase = true) -> "⚡ Auto-Run" to QuotaGreen
            commandAutoExecutionPolicy.contains("AUTO", ignoreCase = true) -> "🛡️ Smart Safety" to Color(0xFF00ACC1)
            else -> "✋ Ask User" to Color(0xFFF59E0B)
        }
        val sandboxSummary = if (commandSandboxEnabled) "Sandbox ON" else "Sandbox OFF"
        SettingsCategoryCard(
            icon = Icons.Outlined.Security,
            iconTint = policyColor,
            title = "Commands & Permissions",
            subtitle = "Policy: $policyBadge • $sandboxSummary",
            badgeText = policyBadge,
            badgeColor = policyColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.COMMANDS) }
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
    groupChatsByWorkspace: Boolean,
    isFloatingSwitcherEnabled: Boolean,
    floatingSwitcherOrientation: String,
    floatingSwitcherItems: List<String>,
    floatingSwitcherAutoCollapseSec: Int,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetThemeMode: (String) -> Unit,
    onSetChatFontScale: (Float) -> Unit,
    onToggleGroupChatsByWorkspace: (Boolean) -> Unit,
    onToggleFloatingSwitcher: (Boolean) -> Unit,
    onSetFloatingSwitcherOrientation: (String) -> Unit,
    onSetFloatingSwitcherItems: (List<String>) -> Unit,
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit,
    onResetFloatingSwitcherPosition: () -> Unit
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

        // Live Chat Message Preview Box (Directly under Chat Text Scaling)
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
                                text = "AntiGem connects via gRPC-Web to stream live agent steps and tool executions.",
                                fontSize = (13 * chatFontScale).sp,
                                lineHeight = (18 * chatFontScale).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Group Chats By Workspace Switch
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Group Chats by Workspace",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Categorize chat history in drawer under workspace folders matching repository paths",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = groupChatsByWorkspace,
                    onCheckedChange = onToggleGroupChatsByWorkspace,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
            }
        }

        // Floating App Switcher Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Floating App Switcher",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "A glassy floating pill across the whole app for instant switching between Chat, IDE, Terminal, and Browser",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isFloatingSwitcherEnabled,
                        onCheckedChange = onToggleFloatingSwitcher,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                if (isFloatingSwitcherEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Layout Orientation Selector
                    Text(
                        text = "Layout Orientation",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isVert = floatingSwitcherOrientation.equals("VERTICAL", ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSetFloatingSwitcherOrientation("VERTICAL") },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isVert) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Outlined.ExpandLess, contentDescription = null, tint = if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Vertical", fontSize = 12.sp, fontWeight = if (isVert) FontWeight.Bold else FontWeight.Normal, color = if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSetFloatingSwitcherOrientation("HORIZONTAL") },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isVert) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Horizontal", fontSize = 12.sp, fontWeight = if (!isVert) FontWeight.Bold else FontWeight.Normal, color = if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Auto-Collapse Timeout Selector
                    Text(
                        text = "Auto-Collapse Timeout",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Automatically collapses into a small arrow handle after inactivity",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val timeouts = listOf(0 to "Never", 3 to "3s", 5 to "5s", 10 to "10s", 15 to "15s")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        timeouts.forEach { (sec, label) ->
                            val isSelected = floatingSwitcherAutoCollapseSec == sec
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetFloatingSwitcherAutoCollapseSec(sec) },
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // Customize Items Order & Visibility
                    Text(
                        text = "Customize Icons & Order",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val allAvailable = listOf("chat", "ide", "terminal", "browser")
                    val currentItems = floatingSwitcherItems.toMutableList()
                    val displayedList = currentItems + allAvailable.filterNot { currentItems.contains(it) }

                    displayedList.forEach { itemId ->
                        val isEnabled = currentItems.contains(itemId)
                        val itemLabel = when (itemId) {
                            "chat" -> "Chat"
                            "ide" -> "IDE (Code Editor)"
                            "terminal" -> "Terminal"
                            "browser" -> "Web Browser"
                            else -> itemId
                        }
                        val itemIcon = when (itemId) {
                            "chat" -> Icons.Outlined.ChatBubbleOutline
                            "ide" -> Icons.Outlined.Code
                            "terminal" -> Icons.Outlined.Terminal
                            "browser" -> Icons.Outlined.Language
                            else -> Icons.Outlined.ChatBubbleOutline
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isEnabled,
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) {
                                                currentItems + itemId
                                            } else {
                                                if (currentItems.size > 1) currentItems.filter { it != itemId } else currentItems
                                            }
                                            onSetFloatingSwitcherItems(updated)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(itemIcon, contentDescription = null, tint = if (isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = itemLabel,
                                        fontSize = 12.sp,
                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }

                                if (isEnabled) {
                                    val itemOrderIndex = currentItems.indexOf(itemId)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                if (itemOrderIndex > 0) {
                                                    val copy = currentItems.toMutableList()
                                                    val temp = copy[itemOrderIndex]
                                                    copy[itemOrderIndex] = copy[itemOrderIndex - 1]
                                                    copy[itemOrderIndex - 1] = temp
                                                    onSetFloatingSwitcherItems(copy)
                                                }
                                            },
                                            enabled = itemOrderIndex > 0,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Outlined.ExpandLess, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                if (itemOrderIndex < currentItems.size - 1) {
                                                    val copy = currentItems.toMutableList()
                                                    val temp = copy[itemOrderIndex]
                                                    copy[itemOrderIndex] = copy[itemOrderIndex + 1]
                                                    copy[itemOrderIndex + 1] = temp
                                                    onSetFloatingSwitcherItems(copy)
                                                }
                                            },
                                            enabled = itemOrderIndex < currentItems.size - 1,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Reset Position Button
                    OutlinedButton(
                        onClick = onResetFloatingSwitcherPosition,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Screen Position to Right-Center", fontSize = 12.sp)
                    }
                }
            }
        }

        // AntiTerminal Standalone Launcher Icon Switch
        val context = LocalContext.current
        val terminalComponentName = remember { ComponentName(context, "com.example.gemini.TermuxActivity") }
        var isTerminalLauncherEnabled by remember {
            mutableStateOf(
                context.packageManager.getComponentEnabledSetting(terminalComponentName).let { state ->
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Separate AntiTerm App Icon",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Show or hide the standalone AntiTerm launcher icon on your Android home screen and app drawer",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = isTerminalLauncherEnabled,
                    onCheckedChange = { enable ->
                        isTerminalLauncherEnabled = enable
                        try {
                            val newState = if (enable) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            }
                            context.packageManager.setComponentEnabledSetting(
                                terminalComponentName,
                                newState,
                                PackageManager.DONT_KILL_APP
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsDialog", "Failed to update TermuxActivity component enabled state: ${e.message}")
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
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

    // Helper to split "http://host:port" into host and port
    fun parseHostPort(url: String, fallbackUrl: String): Pair<String, String> {
        val target = url.ifBlank { fallbackUrl }
        val clean = target.replace("http://", "").replace("https://", "").trimEnd('/')
        val parts = clean.split(":")
        val host = parts.getOrNull(0)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        val port = parts.getOrNull(1) ?: ""
        return host to port
    }

    val (initHubHost, initHubPort) = remember { parseHostPort(currentHubUrl, AuthPreferences.DEFAULT_HUB_URL) }
    val (initBridgeHost, initBridgePort) = remember { parseHostPort(currentBridgeUrl, AuthPreferences.DEFAULT_BRIDGE_HTTP_URL) }

    var hubHost by remember { mutableStateOf(initHubHost) }
    var hubPort by remember { mutableStateOf(initHubPort) }
    var bridgeHost by remember { mutableStateOf(initBridgeHost) }
    var bridgePort by remember { mutableStateOf(initBridgePort) }

    var hubTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingHub by remember { mutableStateOf(false) }

    var bridgeTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingBridge by remember { mutableStateOf(false) }

    var saveFeedback by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var showRestartDialog by remember { mutableStateOf(false) }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Reopen AntiGem?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Server URLs have been saved. Reopening the app ensures all daemon connections, gRPC streams, and file monitors cleanly initialize with the new address.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartDialog = false
                        val pm = context.packageManager
                        val intent = pm.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            val restartIntent = Intent.makeRestartActivityTask(intent.component)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reopen Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestartDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

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
                        Text(text = "Instance management & prewarm proxy", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                saveFeedback = "✓ Server addresses saved in settings!"
                showRestartDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save Server URLs", fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// SUB-SCREEN 3: MCP SERVERS & TOOLS SETTINGS
// ==========================================
@Composable
private fun McpSubScreen(
    mcpServers: List<com.example.gemini.domain.model.McpServerState>,
    isLoading: Boolean,
    isRefreshing: Boolean = false,
    refreshingServerName: String? = null,
    errorMessage: String? = null,
    statusMessage: String? = null,
    onClearStatus: () -> Unit = {},
    cardBg: Color,
    cardBorder: BorderStroke,
    onRefreshAll: () -> Unit,
    onRefreshServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onSaveServer: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit,
    onDeleteServer: (String) -> Unit,
    availableCascadePlugins: List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (com.example.gemini.data.remote.dto.AvailableCascadePluginDto) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var showCascadeCatalogDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<com.example.gemini.domain.model.McpServerSpec?>(null) }
    var serverToDelete by remember { mutableStateOf<String?>(null) }
    var expandedTools by remember { mutableStateOf(setOf<String>()) }
    var expandedErrors by remember { mutableStateOf(setOf<String>()) }

    // Auto-dismiss status toast after 3.5 seconds
    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            delay(3500)
            onClearStatus()
        }
    }

    LaunchedEffect(Unit) {
        if (mcpServers.isEmpty()) {
            onRefreshAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 68.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Error banner when servers are already loaded in the list
            if (!errorMessage.isNullOrBlank() && mcpServers.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorMessage,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClearStatus,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

        // Banner card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model Context Protocol (MCP)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Global config: ~/.gemini/config/mcp_config.json",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "Configure local process (Stdio) or remote (SSE) MCP servers. Discovered tools are dynamically available to all models during chat turns.",
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                // Top action row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isRefreshingAll = isRefreshing && refreshingServerName == null
                    OutlinedButton(
                        onClick = onRefreshAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isRefreshing && !isLoading,
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                    ) {
                        if (isRefreshingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeTerracotta
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRefreshingAll) "Testing All..." else "Test & Refresh", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            serverToEdit = null
                            showDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Server", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Browse Cascade MCP Marketplace button
                OutlinedButton(
                    onClick = { showCascadeCatalogDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta),
                    border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storefront,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = ClaudeTerracotta
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Browse MCP Catalog / Marketplace (1-Click)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta)
                }
            }
        }

        // Quick template chips
        Text(
            text = "Quick Presets & Templates",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val termuxEnv = mapOf(
                "LD_PRELOAD" to "/data/data/com.termux/files/usr/lib/libtermux-exec.so",
                "PATH" to "/data/data/com.termux/files/usr/bin:/system/bin"
            )
            val presets = listOf(
                Triple("Browser Automation", "SSE", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "android_bridge",
                    serverUrl = "http://127.0.0.1:8765/mcp"
                )),
                Triple("Local Tools", "Stdio", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "local_tools",
                    command = "sh",
                    args = listOf("-c", "python3 \"\$HOME/.gemini/local_tools.py\"")
                )),
                Triple("Stitch", "SSE", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "stitch",
                    serverUrl = "https://stitch.googleapis.com/mcp",
                    headers = mapOf("X-Goog-Api-Key" to "")
                )),
                Triple("Thinking", "Stdio", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "thinking",
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
                    env = termuxEnv
                )),
                Triple("Memory", "Stdio", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "memory",
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-memory"),
                    env = termuxEnv
                )),
                Triple("Filesystem", "Stdio", com.example.gemini.domain.model.McpServerSpec(
                    serverName = "filesystem",
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-filesystem", "."),
                    env = termuxEnv
                ))
            )
            presets.forEach { (pName, typeBadge, spec) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            serverToEdit = spec
                            showDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "+ $pName",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = if (typeBadge == "SSE") Color(0xFF1976D2).copy(alpha = 0.15f) else Color(0xFF7B1FA2).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = typeBadge,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (typeBadge == "SSE") Color(0xFF1976D2) else Color(0xFF7B1FA2),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // Server list header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Configured Servers (${mcpServers.size})",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (mcpServers.isNotEmpty()) {
                val totalTools = mcpServers.sumOf { it.tools.size }
                Text(
                    text = "$totalTools tools available",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // Empty / Error / Loading state
        if (mcpServers.isEmpty()) {
            if (isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = cardBorder
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = ClaudeTerracotta
                        )
                        Text(
                            text = "Loading MCP Servers...",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (!errorMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            text = "Unable to Load MCP Servers",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Could not retrieve MCP server states. Ensure the Antigravity daemon is running on this device or accessible at the configured Hub URL.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onRefreshAll,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta),
                            border = BorderStroke(1.dp, ClaudeTerracotta)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Connection", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = cardBorder
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No MCP Servers Configured",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add an MCP server to connect SQLite, filesystem, browser automation, or custom local tools to the assistant.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Server Cards
        mcpServers.forEach { server ->
            val isExpanded = expandedTools.contains(server.name)
            val isErrorExpanded = expandedErrors.contains(server.name)
            val isSse = server.spec?.serverUrl?.isNotBlank() == true

            val statusColor = when {
                !server.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                server.status.contains("RUNNING", ignoreCase = true) || server.status.contains("OK", ignoreCase = true) || server.tools.isNotEmpty() -> QuotaGreen
                server.status.contains("ERROR", ignoreCase = true) || !server.error.isNullOrBlank() -> Color.Red
                else -> Color(0xFFFFA000)
            }
            val statusLabel = when {
                !server.isEnabled -> "Disabled"
                server.status.contains("RUNNING", ignoreCase = true) || server.status.contains("OK", ignoreCase = true) || server.tools.isNotEmpty() -> "Ready"
                server.status.contains("ERROR", ignoreCase = true) || !server.error.isNullOrBlank() -> "Error"
                else -> server.status.removePrefix("MCP_SERVER_STATUS_").lowercase().replaceFirstChar { it.uppercase() }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = cardBorder
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Title and toggle row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSse) Color(0xFF1976D2).copy(alpha = 0.12f) else Color(0xFF7B1FA2).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = if (isSse) Color(0xFF1976D2) else Color(0xFF7B1FA2),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Transport badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSse) Color(0xFF1976D2).copy(alpha = 0.15f) else Color(0xFF7B1FA2).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isSse) "SSE" else "Stdio",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSse) Color(0xFF1976D2) else Color(0xFF7B1FA2),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }

                                // Status badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = statusColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = statusLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = server.isEnabled,
                            onCheckedChange = { onToggleServer(server.name, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = ClaudeTerracotta, checkedTrackColor = ClaudeTerracotta.copy(alpha = 0.4f))
                        )
                    }

                    // Command or URL info
                    val cmdText = if (isSse) {
                        server.spec?.serverUrl ?: ""
                    } else {
                        val baseCmd = server.spec?.command ?: ""
                        val argsJoined = server.spec?.args?.joinToString(" ") ?: ""
                        "$baseCmd $argsJoined".trim()
                    }
                    if (cmdText.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Text(
                                text = if (isSse) "URL: $cmdText" else "> $cmdText",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (isSse && !server.spec?.headers.isNullOrEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1976D2).copy(alpha = 0.08f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFF1976D2)
                                )
                                Text(
                                    text = "Headers: ${server.spec!!.headers.keys.joinToString(", ")}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF1976D2)
                                )
                            }
                        }
                    } else if (!isSse && !server.spec?.cwd.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "cwd: ${server.spec!!.cwd}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }

                    // Error banner
                    if (!server.error.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedErrors = if (isErrorExpanded) expandedErrors - server.name else expandedErrors + server.name
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Red.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                                        Text("Connection Issue", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                                    }
                                    Icon(
                                        imageVector = if (isErrorExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.Red,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = server.error,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red,
                                    maxLines = if (isErrorExpanded) 10 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // Discovered tools accordion
                    if (server.tools.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedTools = if (isExpanded) expandedTools - server.name else expandedTools + server.name
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = "${server.tools.size} Discovered Tool${if (server.tools.size > 1) "s" else ""}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    server.tools.forEach { tool ->
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = tool.name,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = ClaudeTerracotta
                                            )
                                            if (tool.description.isNotBlank()) {
                                                Text(
                                                    text = tool.description,
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isThisRefreshing = isRefreshing && (refreshingServerName == server.name)
                        TextButton(
                            onClick = { onRefreshServer(server.name) },
                            enabled = !isRefreshing && !isLoading,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            if (isThisRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.5.dp,
                                    color = ClaudeTerracotta
                                )
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isThisRefreshing) "Testing..." else "Refresh", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = {
                                serverToEdit = server.spec ?: com.example.gemini.domain.model.McpServerSpec(serverName = server.name)
                                showDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = { serverToDelete = server.name },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Floating Success Toast (Zero Layout Shift)
    AnimatedVisibility(
        visible = !statusMessage.isNullOrBlank(),
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = QuotaGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = statusMessage.orEmpty(),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(
                    onClick = onClearStatus,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

    // Delete confirmation dialog
    if (serverToDelete != null) {
        val targetName = serverToDelete ?: ""
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("Remove MCP Server?") },
            text = {
                Text("Are you sure you want to remove \"$targetName\" from global mcp_config.json? The server and its tools will be disconnected.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteServer(targetName)
                        serverToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Remove", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add / Edit Dialog
    if (showDialog) {
        AddEditMcpServerDialog(
            initialSpec = serverToEdit,
            onDismiss = {
                showDialog = false
                serverToEdit = null
            },
            onSave = { spec, rawJson ->
                onSaveServer(spec, rawJson)
                showDialog = false
                serverToEdit = null
            }
        )
    }

    // Cascade Marketplace Dialog
    if (showCascadeCatalogDialog) {
        CascadeMcpCatalogDialog(
            availablePlugins = availableCascadePlugins,
            isLoading = isCascadePluginsLoading,
            installingPluginId = installingCascadePluginId,
            existingServers = mcpServers,
            onSearch = onSearchCascadePlugins,
            onInstallPlugin = onInstallCascadePlugin,
            onDismiss = { showCascadeCatalogDialog = false }
        )
    }
}

private enum class McpEditMode {
    STDIO,
    SSE,
    RAW
}

@Composable
private fun AddEditMcpServerDialog(
    initialSpec: com.example.gemini.domain.model.McpServerSpec?,
    onDismiss: () -> Unit,
    onSave: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit
) {
    var mode by remember {
        mutableStateOf(
            if (initialSpec?.serverUrl?.isNotBlank() == true) McpEditMode.SSE
            else McpEditMode.STDIO
        )
    }
    var name by remember { mutableStateOf(initialSpec?.serverName ?: "") }
    var command by remember { mutableStateOf(initialSpec?.command ?: "") }
    var argsText by remember { mutableStateOf(initialSpec?.args?.joinToString(" ") ?: "") }
    var cwdText by remember { mutableStateOf(initialSpec?.cwd ?: "") }
    var serverUrl by remember { mutableStateOf(initialSpec?.serverUrl ?: "") }
    var headersText by remember { mutableStateOf(initialSpec?.headers?.map { "${it.key}: ${it.value}" }?.joinToString("\n") ?: "") }
    var envText by remember { mutableStateOf(initialSpec?.env?.map { "${it.key}=${it.value}" }?.joinToString("\n") ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun buildJsonFromFields(): String {
        val obj = org.json.JSONObject()
        if (mode == McpEditMode.SSE || serverUrl.isNotBlank()) {
            obj.put("serverUrl", serverUrl.trim())
            val headersMap = mutableMapOf<String, String>()
            headersText.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    val delimiter = if (trimmed.contains(':')) ':' else if (trimmed.contains('=')) '=' else null
                    if (delimiter != null) {
                        val parts = trimmed.split(delimiter, limit = 2)
                        val k = parts[0].trim()
                        val v = parts[1].trim()
                        if (k.isNotBlank()) headersMap[k] = v
                    }
                }
            }
            if (headersMap.isNotEmpty()) {
                val hObj = org.json.JSONObject()
                headersMap.forEach { (k, v) -> hObj.put(k, v) }
                obj.put("headers", hObj)
            }
        } else {
            obj.put("command", command.trim())
            val argsList = mutableListOf<String>()
            val regex = """[^\s"']+|"([^"]*)"|'([^']*)'""".toRegex()
            regex.findAll(argsText.trim()).forEach { m ->
                val arg = m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
                if (arg.isNotBlank()) argsList.add(arg)
            }
            if (argsList.isNotEmpty()) {
                val arr = org.json.JSONArray()
                argsList.forEach { arr.put(it) }
                obj.put("args", arr)
            }
            if (cwdText.trim().isNotBlank()) {
                obj.put("cwd", cwdText.trim())
            }
            val envMap = mutableMapOf<String, String>()
            envText.lines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                    envMap[parts[0].trim()] = parts[1].trim()
                }
            }
            if (envMap.isNotEmpty()) {
                val envObj = org.json.JSONObject()
                envMap.forEach { (k, v) -> envObj.put(k, v) }
                obj.put("env", envObj)
            }
        }
        if (initialSpec?.disabled == true) {
            obj.put("disabled", true)
        }
        return obj.toString(2)
    }

    fun parseFieldsFromJson(rawJson: String) {
        try {
            val obj = org.json.JSONObject(rawJson)
            val sUrl = obj.optString("serverUrl", "")
            if (sUrl.isNotBlank()) {
                serverUrl = sUrl
                val hObj = obj.optJSONObject("headers")
                if (hObj != null) {
                    val lines = mutableListOf<String>()
                    val keys = hObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        lines.add("$k: ${hObj.optString(k)}")
                    }
                    headersText = lines.joinToString("\n")
                }
            } else {
                val cmd = obj.optString("command", "")
                if (cmd.isNotBlank()) command = cmd
                val argsArr = obj.optJSONArray("args")
                if (argsArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until argsArr.length()) {
                        list.add(argsArr.optString(i))
                    }
                    argsText = list.joinToString(" ")
                }
                val cwd = obj.optString("cwd", "")
                if (cwd.isNotBlank()) cwdText = cwd
                val envObj = obj.optJSONObject("env")
                if (envObj != null) {
                    val lines = mutableListOf<String>()
                    val keys = envObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        lines.add("$k=${envObj.optString(k)}")
                    }
                    envText = lines.joinToString("\n")
                }
            }
        } catch (_: Exception) {}
    }

    var rawJsonText by remember {
        mutableStateOf(
            if (initialSpec != null) {
                val obj = org.json.JSONObject()
                if (initialSpec.serverUrl.isNotBlank()) {
                    obj.put("serverUrl", initialSpec.serverUrl)
                    if (initialSpec.headers.isNotEmpty()) {
                        val h = org.json.JSONObject()
                        initialSpec.headers.forEach { (k, v) -> h.put(k, v) }
                        obj.put("headers", h)
                    }
                } else {
                    obj.put("command", initialSpec.command)
                    if (initialSpec.args.isNotEmpty()) {
                        val arr = org.json.JSONArray()
                        initialSpec.args.forEach { arr.put(it) }
                        obj.put("args", arr)
                    }
                    if (initialSpec.cwd.isNotBlank()) obj.put("cwd", initialSpec.cwd)
                    if (initialSpec.env.isNotEmpty()) {
                        val envObj = org.json.JSONObject()
                        initialSpec.env.forEach { (k, v) -> envObj.put(k, v) }
                        obj.put("env", envObj)
                    }
                }
                if (initialSpec.disabled) obj.put("disabled", true)
                obj.toString(2)
            } else {
                """{
  "command": "npx",
  "args": [
    "-y",
    "@modelcontextprotocol/server-filesystem",
    "."
  ]
}"""
            }
        )
    }

    LaunchedEffect(initialSpec) {
        if (initialSpec != null && initialSpec.serverName.isNotBlank()) {
            try {
                val configRaw = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig()
                if (!configRaw.isNullOrBlank()) {
                    val cfgJson = org.json.JSONObject(configRaw)
                    val sObj = cfgJson.optJSONObject("mcpServers")?.optJSONObject(initialSpec.serverName)
                    if (sObj != null) {
                        rawJsonText = sObj.toString(2)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialSpec != null && initialSpec.serverName.isNotBlank()) "Edit MCP Server" else "Add MCP Server",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Mode Selector: Stdio | SSE | Raw
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        McpEditMode.STDIO to "Stdio",
                        McpEditMode.SSE to "SSE",
                        McpEditMode.RAW to "Raw"
                    ).forEach { (m, label) ->
                        val isSelected = mode == m
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (mode != m) {
                                        if (m == McpEditMode.RAW) {
                                            rawJsonText = buildJsonFromFields()
                                        } else if (mode == McpEditMode.RAW) {
                                            parseFieldsFromJson(rawJsonText)
                                        }
                                        mode = m
                                        validationError = null
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Server Name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text("Server Name *") },
                    placeholder = { Text("e.g. stitch, filesystem, github") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == McpEditMode.RAW) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Server Configuration JSON",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        val parsed = org.json.JSONObject(rawJsonText)
                                        rawJsonText = parsed.toString(2)
                                        validationError = null
                                    } catch (e: Exception) {
                                        validationError = "Invalid JSON: ${e.message}"
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Format", fontSize = 11.sp, color = ClaudeTerracotta)
                            }
                        }

                        OutlinedTextField(
                            value = rawJsonText,
                            onValueChange = {
                                rawJsonText = it
                                validationError = null
                            },
                            placeholder = {
                                Text("{\n  \"command\": \"npx\",\n  \"args\": [...]\n}")
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            minLines = 8,
                            maxLines = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (mode == McpEditMode.STDIO) {
                    // Command
                    OutlinedTextField(
                        value = command,
                        onValueChange = {
                            command = it
                            validationError = null
                        },
                        label = { Text("Command *") },
                        placeholder = { Text("e.g. npx, python3, node, uvx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Arguments
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (space separated)") },
                        placeholder = { Text("e.g. -y @modelcontextprotocol/server-filesystem .") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Working Directory
                    OutlinedTextField(
                        value = cwdText,
                        onValueChange = { cwdText = it },
                        label = { Text("Working Directory (optional)") },
                        placeholder = { Text("e.g. ~/projects/my-app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Environment Variables
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text("Environment Variables (KEY=VALUE per line)") },
                        placeholder = { Text("API_KEY=xyz\nDEBUG=true") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Server URL
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            validationError = null
                        },
                        label = { Text("SSE Endpoint URL *") },
                        placeholder = { Text("e.g. https://stitch.googleapis.com/mcp or http://127.0.0.1:8000/sse") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // HTTP Headers
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("HTTP Headers (Header: Value per line)") },
                        placeholder = { Text("X-Goog-Api-Key: AQ.Ab8RN6...\nAuthorization: Bearer token") },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = {
                            Text(
                                text = "Set custom headers like API keys or tokens required by the remote endpoint.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (validationError != null) {
                    Text(
                        text = validationError ?: "",
                        fontSize = 12.sp,
                        color = Color.Red,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    if (cleanName.isBlank()) {
                        validationError = "Server name is required"
                        return@Button
                    }

                    if (mode == McpEditMode.RAW) {
                        try {
                            val sObj = org.json.JSONObject(rawJsonText)
                            val sUrl = sObj.optString("serverUrl", "").trim()
                            val sCmd = sObj.optString("command", "").trim()
                            if (sUrl.isBlank() && sCmd.isBlank()) {
                                validationError = "JSON must specify either 'command' or 'serverUrl'"
                                return@Button
                            }
                            val isDis = sObj.optBoolean("disabled", initialSpec?.disabled ?: false)
                            val spec = com.example.gemini.domain.model.McpServerSpec(
                                serverName = cleanName,
                                command = sCmd,
                                serverUrl = sUrl,
                                disabled = isDis
                            )
                            onSave(spec, rawJsonText.trim())
                        } catch (e: Exception) {
                            validationError = "Invalid JSON: ${e.message}"
                            return@Button
                        }
                    } else if (mode == McpEditMode.STDIO) {
                        val cleanCmd = command.trim()
                        if (cleanCmd.isBlank()) {
                            validationError = "Command is required"
                            return@Button
                        }
                        // Parse arguments, supporting quoted strings
                        val argsList = mutableListOf<String>()
                        val regex = """[^\s"']+|"([^"]*)"|'([^']*)'""".toRegex()
                        regex.findAll(argsText.trim()).forEach { m ->
                            val arg = m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
                            if (arg.isNotBlank()) argsList.add(arg)
                        }

                        val envMap = mutableMapOf<String, String>()
                        envText.lines().forEach { line ->
                            val parts = line.split("=", limit = 2)
                            if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                                envMap[parts[0].trim()] = parts[1].trim()
                            }
                        }

                        val spec = com.example.gemini.domain.model.McpServerSpec(
                            serverName = cleanName,
                            command = cleanCmd,
                            args = argsList,
                            env = envMap,
                            cwd = cwdText.trim(),
                            disabled = initialSpec?.disabled ?: false
                        )
                        onSave(spec, null)
                    } else {
                        val cleanUrl = serverUrl.trim()
                        if (cleanUrl.isBlank()) {
                            validationError = "Server URL is required"
                            return@Button
                        }

                        val headersMap = mutableMapOf<String, String>()
                        headersText.lines().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotBlank()) {
                                val delimiter = if (trimmed.contains(':')) ':' else if (trimmed.contains('=')) '=' else null
                                if (delimiter != null) {
                                    val parts = trimmed.split(delimiter, limit = 2)
                                    val k = parts[0].trim()
                                    val v = parts[1].trim()
                                    if (k.isNotBlank()) {
                                        headersMap[k] = v
                                    }
                                }
                            }
                        }

                        val spec = com.example.gemini.domain.model.McpServerSpec(
                            serverName = cleanName,
                            serverUrl = cleanUrl,
                            headers = headersMap,
                            disabled = initialSpec?.disabled ?: false
                        )
                        onSave(spec, null)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// SUB-SCREEN 4: TERMINAL & SHELL SETTINGS
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showResetWarningDialog by remember { mutableStateOf(false) }
    var showResetPasswordDialog by remember { mutableStateOf(false) }
    var resetPasswordInput by remember { mutableStateOf("") }
    var isResettingRootfs by remember { mutableStateOf(false) }

    var hostState by remember { mutableStateOf(sshHost) }
    var portState by remember { mutableStateOf(sshPort.toString()) }
    var userState by remember { mutableStateOf(sshUser) }
    var passState by remember { mutableStateOf(sshPass) }
    var isTestingSsh by remember { mutableStateOf(false) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }
    var sshSaveFeedback by remember { mutableStateOf<String?>(null) }

    var fontSizeState by remember { mutableIntStateOf(terminalFontSize) }
    var cursorStyleState by remember { mutableStateOf(terminalCursorStyle) }
    var bufferSizeState by remember { mutableIntStateOf(terminalBufferSize) }
    var themeState by remember { mutableStateOf(terminalTheme) }

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
                                sshTestStatus = null
                                sshSaveFeedback = null
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
                                sshTestStatus = null
                                sshSaveFeedback = null
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
                                sshTestStatus = null
                                sshSaveFeedback = null
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
                                sshTestStatus = null
                                sshSaveFeedback = null
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
                                    port = portState.toIntOrNull() ?: sshPort,
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

                    if (sshSaveFeedback != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = QuotaGreen.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = sshSaveFeedback ?: "",
                                fontSize = 12.sp,
                                color = QuotaGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val p = portState.toIntOrNull() ?: sshPort
                            onSaveSshSettings(hostState.trim(), p, userState.trim(), passState)
                            sshSaveFeedback = "✓ SSH settings saved successfully!"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save SSH Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isLocalToolsInstalled) {
                            OutlinedButton(
                                onClick = { showResetWarningDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f))
                            ) {
                                Icon(imageVector = Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.Red)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete Rootfs", fontSize = 12.5.sp, color = Color.Red, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = onInstallLocalTools,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text("Download & Install Local Tools", fontSize = 12.5.sp)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val buffers = listOf(
                        1000 to "1k",
                        5000 to "5k",
                        10000 to "10k",
                        20000 to "20k",
                        50000 to "50k",
                        100000 to "Unlimited"
                    )
                    buffers.forEach { (buf, label) ->
                        val isSel = bufferSizeState == buf || (buf == 100000 && bufferSizeState >= 100000)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    bufferSizeState = buf
                                    onSaveTerminalPreferences(fontSizeState, cursorStyleState, buf, themeState)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface)
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

    if (showResetWarningDialog) {
        AlertDialog(
            onDismissRequest = { showResetWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Reset Local Linux Rootfs?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Warning: All installed packages, custom configurations, files in \$HOME, and local project history will be permanently deleted.\n\nThis action cannot be undone. Are you sure you want to proceed?",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetWarningDialog = false
                        resetPasswordInput = ""
                        showResetPasswordDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Yes, Continue", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetWarningDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showResetPasswordDialog) {
        val isMatch = resetPasswordInput.trim() == "iknowwhatiamdoing"
        AlertDialog(
            onDismissRequest = { showResetPasswordDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Confirm Destruction",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "To confirm and permanently delete the entire local rootfs, type the confirmation phrase exactly as shown below:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "iknowwhatiamdoing",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    OutlinedTextField(
                        value = resetPasswordInput,
                        onValueChange = { resetPasswordInput = it },
                        singleLine = true,
                        placeholder = { Text("iknowwhatiamdoing", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = resetPasswordInput.isNotEmpty() && !isMatch
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isMatch) {
                            showResetPasswordDialog = false
                            isResettingRootfs = true
                            LocalEnvironmentManager.launchReset(context) {
                                isResettingRootfs = false
                                onResetLocalTools()
                            }
                        }
                    },
                    enabled = isMatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        disabledContainerColor = Color.Red.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete Everything", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetPasswordDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (isResettingRootfs) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.Red
                    )
                    Text(
                        text = "Resetting rootfs & environment... Please wait.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 5: COMMANDS & PERMISSIONS
// ==========================================
@Composable
private fun CommandsSubScreen(
    commandAutoExecutionPolicy: String,
    commandSandboxEnabled: Boolean,
    requireApprovalForFileEdits: Boolean,
    defaultApprovalScope: String,
    globalSecuritySettings: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings?,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean,
    projectsList: List<com.example.gemini.data.remote.AgyHubClient.ProjectItem>,
    isProjectsLoading: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetGlobalArtifactReviewMode: (String) -> Unit,
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit,
    onSetGlobalCustomTerminalPolicy: (String) -> Unit,
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit,
    onSetGlobalTerminalSandbox: (Boolean) -> Unit,
    onSetProjectInheritGlobal: (com.example.gemini.data.remote.AgyHubClient.ProjectItem) -> Unit,
    onSetProjectPreset: (com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit,
    onRefreshSecurityAndProjects: () -> Unit,
    onSetCommandAutoExecutionPolicy: (String) -> Unit,
    onSetCommandSandboxEnabled: (Boolean) -> Unit,
    onSetRequireApprovalForFileEdits: (Boolean) -> Unit,
    onSetDefaultApprovalScope: (String) -> Unit,
    onAddPermissionRule: (action: String, pattern: String, decision: String) -> Unit,
    onRemovePermissionRule: (rawRule: String) -> Unit,
    onChangePermissionRuleDecision: (rawRule: String, newDecision: String) -> Unit
) {
    if (globalSecuritySettings == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Global Security & Policies",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Live daemon settings applied across all workspaces via Jetbox",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRefreshSecurityAndProjects,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isGlobalSettingsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (isGlobalSettingsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp,
                            color = ClaudeTerracotta
                        )
                        Text(
                            text = "Connecting to AGY Hub & loading permissions...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Unable to Load Permissions from AGY Hub",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = "Could not retrieve daemon permissions and security state:\n${globalSettingsError ?: "RPC connection failed"}\n\nMake sure AGY is running (e.g. agy --hub) and check your Server settings.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onRefreshSecurityAndProjects,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Connection", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        return
    }

    val activeAutoExec = globalSecuritySettings.autoExecutionPolicy
    val activeFileAccess = globalSecuritySettings?.nonWorkspaceFileAccessPolicy ?: "AGENT_SETTING_POLICY_ASK"
    val activeArtifactReview = globalSecuritySettings?.artifactReviewMode ?: "ARTIFACT_REVIEW_MODE_ALWAYS"
    val activeSandbox = globalSecuritySettings?.enableTerminalSandbox ?: commandSandboxEnabled

    val isDefaultMode = activeAutoExec.contains("OFF", ignoreCase = true) && activeFileAccess.contains("ASK", ignoreCase = true)
    val isMachineMode = activeAutoExec.contains("OFF", ignoreCase = true) && activeFileAccess.contains("ALLOW", ignoreCase = true)
    val isTurboMode = activeAutoExec.contains("EAGER", ignoreCase = true) && activeFileAccess.contains("ALLOW", ignoreCase = true)
    var customExpanded by remember { mutableStateOf(!isDefaultMode && !isMachineMode && !isTurboMode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header with Sync Info & Refresh Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Global Security & Policies",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Live daemon settings applied across all workspaces via Jetbox",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefreshSecurityAndProjects,
                modifier = Modifier.size(36.dp)
            ) {
                if (isGlobalSettingsLoading || isProjectsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ClaudeTerracotta
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ==========================================
        // 1. ARTIFACT REVIEW POLICY
        // ==========================================
        Column {
            Text(
                text = "Artifact Review Policy",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Controls whether the assistant asks you to review and approve documents it creates.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isAutoReview = activeArtifactReview.contains("TURBO", ignoreCase = true)
            val isAlwaysReview = activeArtifactReview.contains("ALWAYS", ignoreCase = true) || !isAutoReview

            // Auto Option
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_TURBO") },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAutoReview) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isAutoReview) 1.5.dp else 1.dp,
                    if (isAutoReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ Auto (Turbo)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAutoReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = isAutoReview,
                            onClick = { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_TURBO") },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Edits and creates documents immediately without asking.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }

            // Always Ask Option
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_ALWAYS") },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAlwaysReview) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isAlwaysReview) 1.5.dp else 1.dp,
                    if (isAlwaysReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✋ Always Ask",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAlwaysReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = isAlwaysReview,
                            onClick = { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_ALWAYS") },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Suspends and requests your approval before saving document edits.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 2. SECURITY PRESETS
        // ==========================================
        Column {
            Text(
                text = "Security Presets",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Controls terminal command execution and file access outside working directory.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val presets = listOf(
            Triple(
                "DEFAULT",
                "🛡️ Default Mode",
                "Terminal commands require confirmation. Files outside workspace require confirmation."
            ) to ("Recommended" to Color(0xFF00ACC1)),
            Triple(
                "FULL_MACHINE",
                "💻 Full Machine Mode",
                "Terminal commands require confirmation. Full read/write access to all files on device."
            ) to ("Open Files" to Color(0xFFF59E0B)),
            Triple(
                "TURBO",
                "⚡ Turbo Mode",
                "Full auto-run: terminal commands execute immediately and all files can be accessed."
            ) to ("Full Auto" to QuotaGreen),
            Triple(
                "CUSTOM",
                "⚙️ Custom Granular Mode",
                "Configure terminal command execution and external filesystem permissions separately."
            ) to ("Custom" to Color(0xFF8B5CF6))
        )

        presets.forEach { (presetInfo, badgeInfo) ->
            val (presetKey, title, desc) = presetInfo
            val (badgeText, badgeColor) = badgeInfo
            val isSelected = when (presetKey) {
                "DEFAULT" -> isDefaultMode
                "FULL_MACHINE" -> isMachineMode
                "TURBO" -> isTurboMode
                "CUSTOM" -> customExpanded || (!isDefaultMode && !isMachineMode && !isTurboMode)
                else -> false
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        when (presetKey) {
                            "DEFAULT" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                            }
                            "FULL_MACHINE" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                            }
                            "TURBO" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                            }
                            "CUSTOM" -> {
                                customExpanded = true
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                when (presetKey) {
                                    "DEFAULT" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                                    }
                                    "FULL_MACHINE" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                                    }
                                    "TURBO" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                                    }
                                    "CUSTOM" -> {
                                        customExpanded = true
                                    }
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 32.dp)
                    )

                    // Granular Controls inside Custom Mode
                    if (presetKey == "CUSTOM" && isSelected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // Granular: Terminal Execution
                        Text(
                            text = "Terminal Command Execution:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isTermOff = activeAutoExec.contains("OFF", ignoreCase = true)
                            val isTermEager = activeAutoExec.contains("EAGER", ignoreCase = true)

                            FilterChip(
                                selected = isTermOff,
                                onClick = { onSetGlobalCustomTerminalPolicy("CASCADE_COMMANDS_AUTO_EXECUTION_OFF") },
                                label = { Text("✋ Ask Every Time", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isTermEager,
                                onClick = { onSetGlobalCustomTerminalPolicy("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER") },
                                label = { Text("⚡ Auto-Run (Eager)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Granular: Files Outside Workspace
                        Text(
                            text = "Files Outside Workspace:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isFileAsk = activeFileAccess.contains("ASK", ignoreCase = true)
                            val isFileAllow = activeFileAccess.contains("ALLOW", ignoreCase = true)
                            val isFileDeny = activeFileAccess.contains("DENY", ignoreCase = true)

                            FilterChip(
                                selected = isFileAsk,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_ASK") },
                                label = { Text("✋ Ask", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isFileAllow,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_ALLOW") },
                                label = { Text("✅ Allow", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isFileDeny,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_DENY") },
                                label = { Text("🚫 Deny", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 3. SECURITY & SANDBOX TOGGLE
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Terminal Sandbox Isolation",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activeSandbox) "Enabled: Isolates commands in sandbox without host network access."
                            else "Disabled (Recommended for Hub): Commands execute directly in your Termux / Linux environment.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = activeSandbox,
                        onCheckedChange = { onSetGlobalTerminalSandbox(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Review File Edits & Writes",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Prompt for confirmation before assistant applies file edits or writes code to disk.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = requireApprovalForFileEdits,
                        onCheckedChange = onSetRequireApprovalForFileEdits,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ==========================================
        // 4. GRANULAR PERMISSION GRANTS (globalPermissionGrants)
        // ==========================================
        val grants = globalSecuritySettings.globalPermissionGrants
        val allRules = remember(grants) {
            val list = mutableListOf<PermissionGrantRule>()
            grants.allow.forEach { list.add(PermissionGrantRule.parse(it, "ALLOW")) }
            grants.deny.forEach { list.add(PermissionGrantRule.parse(it, "DENY")) }
            grants.ask.forEach { list.add(PermissionGrantRule.parse(it, "ASK")) }
            list
        }

        var selectedFilter by remember { mutableStateOf("ALL") }
        var showAddRuleDialog by remember { mutableStateOf(false) }

        val filteredRules = remember(allRules, selectedFilter) {
            if (selectedFilter == "ALL") allRules
            else allRules.filter { it.action.equals(selectedFilter, ignoreCase = true) }
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Granular Permission Grants",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Explicit Allow, Deny, and Ask rules for commands, MCP tools, and file paths (globalPermissionGrants).",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showAddRuleDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val filters = listOf(
                    "ALL" to "All (${allRules.size})",
                    "command" to "⌨️ Command (${allRules.count { it.action.equals("command", ignoreCase = true) }})",
                    "mcp" to "🔌 MCP (${allRules.count { it.action.equals("mcp", ignoreCase = true) }})",
                    "read_file" to "📖 Read (${allRules.count { it.action.equals("read_file", ignoreCase = true) }})",
                    "write_file" to "✏️ Write (${allRules.count { it.action.equals("write_file", ignoreCase = true) }})"
                )

                filters.forEach { (key, label) ->
                    val isSel = selectedFilter == key
                    FilterChip(
                        selected = isSel,
                        onClick = { selectedFilter = key },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }

        if (filteredRules.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (allRules.isEmpty()) "No custom permission rules configured." else "No rules match the selected filter.",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tools and file accesses will use your global presets above. Click \"Add Rule\" to whitelist, blacklist, or prompt for specific commands, MCP tools, or file paths.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredRules.forEach { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = "${rule.actionIcon} ${rule.actionLabel}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = rule.pattern,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                IconButton(
                                    onClick = { onRemovePermissionRule(rule.rawString) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Rule",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Decision segmented selector: Allow | Deny | Ask
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val decisions = listOf(
                                    Triple("ALLOW", "✅ Allow", QuotaGreen),
                                    Triple("DENY", "🚫 Deny", MaterialTheme.colorScheme.error),
                                    Triple("ASK", "❓ Ask", Color(0xFFF59E0B))
                                )

                                decisions.forEach { (decKey, decLabel, decColor) ->
                                    val isSelected = rule.decision.equals(decKey, ignoreCase = true)
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (!isSelected) {
                                                    onChangePermissionRuleDecision(rule.rawString, decKey)
                                                }
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) decColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        border = BorderStroke(
                                            if (isSelected) 1.5.dp else 0.5.dp,
                                            if (isSelected) decColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = decLabel,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) decColor else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddRuleDialog) {
            AddPermissionRuleDialog(
                onDismiss = { showAddRuleDialog = false },
                onAdd = { act, pat, dec ->
                    onAddPermissionRule(act, pat, dec)
                    showAddRuleDialog = false
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ==========================================
        // 5. PROJECT OVERRIDES & INHERITANCE (NEW!)
        // ==========================================
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Project Overrides & Inheritance",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (projectsList.isNotEmpty()) {
                    Text(
                        text = "${projectsList.count { it.isInheritingGlobal }}/${projectsList.size} Inheriting",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Projects can inherit the global policy above or maintain specific overrides.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isProjectsLoading && projectsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ClaudeTerracotta, modifier = Modifier.size(24.dp))
            }
        } else if (projectsList.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No workspace projects registered yet on AGY Hub.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                projectsList.forEach { project ->
                    var projectExpanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { projectExpanded = !projectExpanded },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = project.name.ifBlank { project.id },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = project.id,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (project.isInheritingGlobal) QuotaGreen.copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (project.isInheritingGlobal) "🌐 Inherits Global" else "⚠️ Custom Override",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (project.isInheritingGlobal) QuotaGreen else Color(0xFFF59E0B),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            if (projectExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                Spacer(modifier = Modifier.height(10.dp))

                                // Quick button to Inherit Global
                                if (!project.isInheritingGlobal) {
                                    Button(
                                        onClick = {
                                            onSetProjectInheritGlobal(project)
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                                    ) {
                                        Text("🌐 Set to Inherit Global Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Text(
                                    text = "Set Project Override:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("🛡️ Default", fontSize = 10.5.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("💻 Machine", fontSize = 10.5.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("⚡ Turbo", fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 5. APPROVAL SCOPE & PERSISTENCE BANNER
        // ==========================================
        Column {
            Text(
                text = "Default Approval Scope",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "When you click approve on a tool prompt, how long should permission remain granted?",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val scopes = listOf(
                Triple("PERMISSION_SCOPE_ONCE", "Run Once", "Single call"),
                Triple("PERMISSION_SCOPE_CONVERSATION", "This Chat", "Current session"),
                Triple("PERMISSION_SCOPE_WORKSPACE", "Workspace", "Always")
            )

            scopes.forEach { (scopeKey, title, subtitle) ->
                val isSelected = defaultApprovalScope == scopeKey
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSetDefaultApprovalScope(scopeKey) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else cardBg,
                    border = BorderStroke(
                        1.5.dp,
                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = QuotaGreen.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = QuotaGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Live synced with AGY Hub via Jetbox & Project APIs with zero local-storage desync.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private data class PermissionGrantRule(
    val action: String,
    val pattern: String,
    val decision: String,
    val rawString: String
) {
    val actionLabel: String
        get() = when (action.lowercase()) {
            "command" -> "Command"
            "mcp" -> "MCP Tool"
            "read_file" -> "Read File"
            "write_file" -> "Write File"
            else -> action
        }

    val actionIcon: String
        get() = when (action.lowercase()) {
            "command" -> "⌨️"
            "mcp" -> "🔌"
            "read_file" -> "📖"
            "write_file" -> "✏️"
            else -> "⚙️"
        }

    companion object {
        fun parse(raw: String, decision: String): PermissionGrantRule {
            val parenStart = raw.indexOf('(')
            val parenEnd = raw.lastIndexOf(')')
            if (parenStart != -1 && parenEnd > parenStart) {
                val action = raw.substring(0, parenStart).trim()
                val pattern = raw.substring(parenStart + 1, parenEnd).trim()
                return PermissionGrantRule(action, pattern, decision.uppercase(), raw)
            }
            return PermissionGrantRule("command", raw, decision.uppercase(), raw)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPermissionRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (action: String, pattern: String, decision: String) -> Unit
) {
    var selectedAction by remember { mutableStateOf("command") }
    var patternText by remember { mutableStateOf("") }
    var selectedDecision by remember { mutableStateOf("ALLOW") }

    val quickTemplates = remember(selectedAction) {
        when (selectedAction) {
            "command" -> listOf("*", "npm*", "git*", "python3*", "cargo*", "rm*")
            "mcp" -> listOf("*", "local_tools/*", "filesystem/*", "test_memory/*")
            "read_file" -> listOf("*", "/home/cat/.gemini", "/opt/*", "/tmp/*")
            "write_file" -> listOf("*", "/home/cat/extra/*", "/tmp/*")
            else -> listOf("*")
        }
    }

    val actionOptions = listOf(
        Triple("command", "⌨️ Command", "Terminal bash command pattern"),
        Triple("mcp", "🔌 MCP Tool", "MCP server or tool pattern"),
        Triple("read_file", "📖 Read File", "Filesystem directory or file read path"),
        Triple("write_file", "✏️ Write File", "Filesystem directory or file write path")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Add Permission Grant Rule", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Step 1: Select Action
                Text("1. Operation Type", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actionOptions.forEach { (key, label, desc) ->
                        val isSel = selectedAction == key
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedAction = key
                                    if (patternText.isBlank() || patternText == "*") {
                                        patternText = if (key == "mcp" || key == "command") "*" else ""
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                if (isSel) 1.5.dp else 1.dp,
                                if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = { selectedAction = key },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(desc, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Step 2: Target Pattern
                Text("2. Target Pattern", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                OutlinedTextField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (selectedAction) {
                                "command" -> "e.g. npm*, git*, *"
                                "mcp" -> "e.g. *, local_tools/*"
                                "read_file" -> "e.g. /home/cat/.gemini, *"
                                "write_file" -> "e.g. /home/cat/extra/*, *"
                                else -> "e.g. *"
                            },
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp)
                )

                // Quick Templates
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickTemplates.forEach { tpl ->
                        SuggestionChip(
                            onClick = { patternText = tpl },
                            label = { Text(tpl, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace) }
                        )
                    }
                }

                // Step 3: Decision
                Text("3. Policy Decision", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val decs = listOf(
                        Triple("ALLOW", "✅ Allow", QuotaGreen),
                        Triple("DENY", "🚫 Deny", MaterialTheme.colorScheme.error),
                        Triple("ASK", "❓ Ask", Color(0xFFF59E0B))
                    )
                    decs.forEach { (key, label, col) ->
                        val isSel = selectedDecision == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedDecision = key },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) col.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                if (isSel) 1.5.dp else 0.5.dp,
                                if (isSel) col else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) col else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // Preview
                if (patternText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Rule: ${selectedAction}(${patternText.trim()}) → $selectedDecision",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(selectedAction, patternText.trim(), selectedDecision) },
                enabled = patternText.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Text("Save Rule", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AutomationSubScreen(
    isBrowserAutomationEnabled: Boolean,
    isTerminalAutomationEnabled: Boolean,
    onToggleBrowserAutomation: (Boolean) -> Unit,
    onToggleTerminalAutomation: (Boolean) -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Info / Overview Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ClaudeTerracotta.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Device Automation Controls",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Control which device capabilities the AI model can access via the embedded MCP bridge. Toggling off any category immediately hides those tools from the AI.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // 1. Browser Automation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = if (isBrowserAutomationEnabled) BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.4f)) else cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isBrowserAutomationEnabled) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Language,
                                    contentDescription = null,
                                    tint = if (isBrowserAutomationEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Browser Automation",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isBrowserAutomationEnabled) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isBrowserAutomationEnabled) "ACTIVE" else "DISABLED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBrowserAutomationEnabled) QuotaGreen else Color.Gray,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "WebView UI, DOM inspection & screenshots",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isBrowserAutomationEnabled,
                        onCheckedChange = onToggleBrowserAutomation,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Allows the AI to open web URLs in background tabs, capture high-res viewport or full-page screenshots, inspect the live DOM tree with bounding rects, click elements, fill forms, and read console/network error logs.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Exposed MCP Tools (9):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val browserTools = listOf(
                        "browser_open_url", "browser_set_view_mode", "browser_screenshot", "browser_inspect_dom",
                        "browser_interact", "browser_eval_js", "browser_get_console_logs",
                        "browser_list_tabs", "browser_switch_tab", "browser_close_tab"
                    )
                    browserTools.forEach { tool ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Text(
                                text = tool,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isBrowserAutomationEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Terminal Automation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = if (isTerminalAutomationEnabled) BorderStroke(1.dp, Color(0xFF00ACC1).copy(alpha = 0.4f)) else cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isTerminalAutomationEnabled) Color(0xFF00ACC1).copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Terminal,
                                    contentDescription = null,
                                    tint = if (isTerminalAutomationEnabled) Color(0xFF00ACC1) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Terminal Automation",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isTerminalAutomationEnabled) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isTerminalAutomationEnabled) "ACTIVE" else "DISABLED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTerminalAutomationEnabled) QuotaGreen else Color.Gray,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Live shell execution, output buffer & signals",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isTerminalAutomationEnabled,
                        onCheckedChange = onToggleTerminalAutomation,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF00ACC1)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Allows the AI to inspect active in-app terminal sessions, run background commands (e.g. dev servers, package managers), read terminal transcripts in real time, send kill/interrupt signals (Ctrl+C, SIGTERM, SIGKILL), and manage terminal tabs.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Exposed MCP Tools (6):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val terminalTools = listOf(
                        "terminal_list_sessions", "terminal_send_command", "terminal_read_output",
                        "terminal_kill_process", "terminal_create_session", "terminal_close_session"
                    )
                    terminalTools.forEach { tool ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Text(
                                text = tool,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isTerminalAutomationEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}



