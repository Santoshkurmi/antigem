package ai.antigem.ui.settings

import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.ClaudeAccent
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
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
import androidx.compose.foundation.selection.selectable
import ai.antigem.theme.isAppInDarkTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ai.antigem.domain.model.AiModel
import ai.antigem.domain.model.ModelQuota
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.antigem.BuildConfig
import ai.antigem.data.updater.AppUpdateManager
import ai.antigem.data.updater.AppUpdateInfo
import ai.antigem.ui.updater.AppUpdateDialog
import ai.antigem.data.local.LocalServerManager
import android.app.DownloadManager
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.util.Log
import ai.antigem.data.admin.AntiGemDeviceAdminReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.daemon.IdeDaemonManager
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.i18n.L

enum class SettingsSection(private val titleText: () -> String, private val subtitleText: () -> String) {
    MAIN({ L.settings.dialog.settingsPreferences }, { L.settings.dialog.configureYourAntigemExperience }),
    THEMES({ L.common.themes }, { L.settings.dialog.lightAndDarkModeAndThe }),
    LANGUAGE({ L.common.language }, { L.settings.dialog.theAppsLanguageYourOwnTranslations }),
    /** The texts of one language (which one: the dialog's languageTextsId). */
    LANGUAGE_TEXTS({ L.settings.dialog.languageTexts }, { L.settings.dialog.everyTextOfTheAppWith }),
    APPEARANCE({ L.settings.dialog.appearance }, { L.settings.dialog.appearanceSubtitle }),
    PETS({ L.pets.settings.title }, { L.pets.settings.subtitle }),
    /** One pet's page (which one: the dialog's petEditId). */
    PET_EDIT({ L.pets.settings.editTitle }, { L.pets.settings.editSubtitle }),
    HOME_SCREEN({ L.settings.dialog.homeScreen }, { L.settings.dialog.widgetsShortcutsToChatsAndPages }),
    FILE_PICKER({ L.settings.dialog.filesInOtherApps }, { L.settings.dialog.antigemsAndUbuntusHomeFoldersIn }),
    STEM_RENDERING({ L.settings.dialog.stemDiagrams }, { L.settings.dialog.chemistryGraphsGeometrySimulations }),
    SERVERS({ L.settings.dialog.whereAgentsRun }, { L.settings.dialog.thisPhoneAntigemOrUbuntuChroot }),
    CONNECT_PC({ L.settings.dialog.connectToPc }, { L.settings.dialog.useTheAgentsOnYourPc }),
    WEBVIEW_EXTENSIONS({ L.settings.dialog.customAntigemPlugins }, { L.settings.dialog.installAndManagePluginsMcpTools }),
    SLASH_COMMANDS({ L.settings.dialog.slashCommands }, { L.settings.dialog.yourOwnCommandsAndWhatThey }),
    MCP({ L.settings.dialog.mcpServers }, { L.settings.dialog.modelContextProtocolToolsIntegrations }),
    SKILLS_PLUGINS({ L.settings.dialog.skillsPlugins }, { L.settings.dialog.agentCapabilitiesGooglePluginsAnd }),
    TERMINAL({ L.common.terminal }, { L.settings.dialog.fontCursorAndColorsAndSsh }),
    UBUNTU_CHROOT({ L.common.ubuntuChroot }, { L.settings.dialog.statusMountsSharedFoldersStoragePackages }),
    /** Not a page: shown in place of a Termux-only page while the Ubuntu chroot is the phone's runtime. */
    TERMUX_ONLY({ L.settings.dialog.termuxOnly }, { L.settings.dialog.worksWithTheTermuxEnvironment }),
    BACKUPS({ L.settings.dialog.backupsRestore }, { L.settings.dialog.backupRootfsEnvironmentAndAgyChat }),
    DATA_PROTECTION({ L.settings.dialog.dataLossProtection }, { L.settings.dialog.uninstallShieldAppInfoClearData }),
    PROJECT_CHECKPOINTS({ L.settings.dialog.projectCheckpoints }, { L.settings.dialog.goBackToBeforeAnyChat }),
    COMMANDS({ L.settings.dialog.commandsPermissions }, { L.settings.dialog.autoRunPolicySandboxModeAnd }),
    ANTIGRAVITY({ L.common.antigravity }, { L.settings.dialog.accountCliMcpServersSkillsPlugins }),
    AGY_CLI({ L.common.accountCli }, { L.settings.dialog.googleSignInAgyVersionAnd }),
    AGY_CLOUD({ L.settings.dialog.cloudAgents }, { L.settings.dialog.antigravityChatsOnAGoogleCloud }),
    AGY_VOICE({ L.settings.dialog.voiceCalls }, { L.settings.dialog.theVoiceCallTileAndShortcut }),
    CLAUDE({ L.common.claudeCode }, { L.settings.dialog.accountModelsPermissionsMcpPluginsMemory }),
    /** One Claude Code page (which one: the dialog's claudePage); not a page of its own for Jarvis. */
    CLAUDE_PAGE({ L.common.claudeCode }, { L.settings.dialog.accountModelsPermissionsMcpPluginsMemory }),
    OPENCODE({ "OpenCode" }, { L.settings.dialog.cliProvidersCustomOpenaiCompatible }),
    /** One OpenCode page (which one: the dialog's openCodePage). */
    OPENCODE_PAGE({ "OpenCode" }, { L.settings.dialog.cliProvidersEndpointsMcpApprovals }),
    CODEX({ L.common.codex }, { L.settings.dialog.accountCliUsageLimitsPermissionsMcp }),
    /** One Codex page (which one: the dialog's codexPage). */
    CODEX_PAGE({ L.common.codex }, { L.settings.dialog.accountCliUsageLimitsPermissionsMcp }),
    AGENTS({ L.settings.dialog.agents }, { L.settings.dialog.runAntigravityClaudeCodeOpencodeCodex }),
    GLOBAL_CONTEXT({ L.settings.dialog.globalContext }, { L.settings.dialog.whatEveryChatIsToldAbout }),
    AGENT_MEMORY({ L.settings.dialog.agentMemoryHistory }, { L.settings.dialog.memoriesEveryAgentKeepsAcrossChats }),
    AGENT_HISTORY({ L.settings.dialog.savedChats }, { L.settings.dialog.theChatHistoryTheAgentsCan }),
    EGM({ L.settings.dialog.egmElephantGoldfish }, { L.settings.dialog.planADesignAndHaveFresh }),
    JARVIS({ L.common.jarvis }, { L.settings.dialog.handsFreeVoiceControlOfThe }),
    JARVIS_AGENT({ L.settings.dialog.jarvisAgent }, { L.settings.dialog.whichAgentAndModelJarvisTalks }),
    JARVIS_PERSONALITY({ L.settings.dialog.jarvisPersonalityYou }, { L.settings.dialog.howJarvisSpeaksAndWhatIt }),
    JARVIS_SETUP({ L.settings.dialog.jarvisSetup }, { L.settings.dialog.theJarvisMcpServerPermissionsAnd }),
    JARVIS_WAKE({ L.settings.dialog.jarvisWakingJarvis }, { L.settings.dialog.buttonWakePhraseHandsFreeMode }),
    JARVIS_LISTEN({ L.settings.dialog.jarvisListeningSending }, { L.settings.dialog.whenYourWordsAreSentSend }),
    JARVIS_VOICE({ L.settings.dialog.jarvisVoiceOutput }, { L.settings.dialog.jarvisVoiceSpeedPitchAndQuiet }),
    JARVIS_TASKS({ L.settings.dialog.jarvisManagerTasks }, { L.settings.dialog.whatJarvisTellsYouAboutTasks }),
    JARVIS_APPROVALS({ L.settings.dialog.jarvisApprovals }, { L.settings.dialog.approvalsByVoiceAndAppSetting }),
    JARVIS_SCREEN({ L.settings.dialog.jarvisScreenLook }, { L.settings.dialog.screenOnTheOrbAndWhat }),
    JARVIS_CHAT({ L.settings.dialog.jarvisJarvisChat }, { L.settings.dialog.liveContextFreshChatsAndJarvis }),
    DIAGNOSTICS({ L.settings.dialog.diagnosticsPerformance }, { L.settings.dialog.liveNetworkConnectionsActiveStreams }),
    UTILITIES({ L.settings.dialog.utilities }, { L.settings.dialog.handyDeviceTools }),
    GITHUB({ "GitHub" }, { L.settings.dialog.signInWithTheGithubCli }),
    ADB_CONNECT({ L.settings.dialog.adbConnect }, { L.settings.dialog.shellAccessToThisPhoneThrough }),
    ANDROID_AUTOMATION({ L.settings.dialog.androidAutomation }, { L.settings.dialog.letAiAgentsSeeAndControl }),
    REA({ L.settings.dialog.reaReverseEngineering }, { L.settings.dialog.letAgentsAnalyzeApksFlutterReact }),
    REA_GROUPS({ L.settings.dialog.reaToolGroups }, { L.settings.dialog.whichReaToolsEachReaMcp }),
    ANDROID_DEV({ L.settings.dialog.androidDevelopment }, { L.settings.dialog.buildKotlinComposeFlutterAndReact }),
    TERMUX_SUPPORT({ L.settings.dialog.termuxSupport }, { L.settings.dialog.termuxApiAndTheX11Desktop }),
    TERMUX_API({ "Termux:API" }, { L.settings.dialog.howTermuxApiCommandsAreHandled }),
    X11_DESKTOP({ L.settings.dialog.x11Desktop }, { L.settings.dialog.linuxDesktopAppsWithTermuxX11 }),
    UPDATES({ L.settings.dialog.updates }, { L.settings.dialog.betaUpdatesAndHowOftenAntigem }),
    TROUBLESHOOT({ L.settings.dialog.troubleshoot }, { L.settings.dialog.troubleshootSubtitle }),
    CHANGELOG({ L.settings.dialog.whatsNew }, { L.settings.dialog.everyAntigemVersionAndWhatChanged }),
    ABOUT({ L.settings.dialog.aboutInfo }, { L.settings.dialog.appVersionPackageDetailsAndBridge });
    val title: String get() = titleText()
    val subtitle: String get() = subtitleText()

    /** The Jarvis section this page shows (Settings → Jarvis → …), or null. */
    val jarvisPage: ai.antigem.ui.jarvis.JarvisSettingsPage? get() = when (this) {
        JARVIS_AGENT -> ai.antigem.ui.jarvis.JarvisSettingsPage.AGENT
        JARVIS_PERSONALITY -> ai.antigem.ui.jarvis.JarvisSettingsPage.PERSONALITY
        JARVIS_SETUP -> ai.antigem.ui.jarvis.JarvisSettingsPage.SETUP
        JARVIS_WAKE -> ai.antigem.ui.jarvis.JarvisSettingsPage.WAKE
        JARVIS_LISTEN -> ai.antigem.ui.jarvis.JarvisSettingsPage.LISTEN
        JARVIS_VOICE -> ai.antigem.ui.jarvis.JarvisSettingsPage.VOICE
        JARVIS_TASKS -> ai.antigem.ui.jarvis.JarvisSettingsPage.TASKS
        JARVIS_APPROVALS -> ai.antigem.ui.jarvis.JarvisSettingsPage.APPROVALS
        JARVIS_SCREEN -> ai.antigem.ui.jarvis.JarvisSettingsPage.SCREEN
        JARVIS_CHAT -> ai.antigem.ui.jarvis.JarvisSettingsPage.CHAT
        else -> null
    }

    /** Where Back goes from this section. */
    val parent: SettingsSection get() = when (this) {
        AGY_CLI, AGY_CLOUD, AGY_VOICE, MCP, SKILLS_PLUGINS, COMMANDS -> ANTIGRAVITY
        CLAUDE_PAGE -> CLAUDE
        OPENCODE_PAGE -> OPENCODE
        CODEX_PAGE -> CODEX
        TERMUX_API -> TERMUX_SUPPORT
        // with the Ubuntu chroot the X11 row is on the main page (Termux Support is Termux-only there)
        X11_DESKTOP -> if (chrootRunsPhone()) MAIN else TERMUX_SUPPORT
        REA_GROUPS -> REA
        UBUNTU_CHROOT -> SERVERS
        GLOBAL_CONTEXT, AGENT_MEMORY, EGM -> AGENTS
        AGENT_HISTORY -> AGENT_MEMORY
        CHANGELOG -> ABOUT
        LANGUAGE_TEXTS -> LANGUAGE
        PET_EDIT -> PETS
        else -> if (jarvisPage != null) JARVIS else MAIN
    }
}

/**
 * Pages that run the Termux environment's own tools (Termux:API, backups, the GitHub CLI). REA, Android development
 * and the X11 desktop work in the Ubuntu chroot too (they install into it), so they are not here.
 */
private val TERMUX_ONLY_SECTIONS = setOf(
    SettingsSection.TERMUX_SUPPORT, SettingsSection.TERMUX_API, SettingsSection.BACKUPS, SettingsSection.GITHUB
)

/** The Ubuntu chroot is this phone's runtime (not server mode): Termux-only pages show a notice instead. */
private fun chrootRunsPhone(): Boolean =
    !ai.antigem.data.preferences.AuthPreferences.currentRemoteMode && ai.antigem.data.local.chroot.ChrootManager.isActiveNow()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel> = AiModel.DEFAULT_MODELS,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    contextWindowLimit: Int = 10,
    summaryModelId: String = "always_ask",
    isDevModeEnabled: Boolean = false,
    chatFontScale: Float = 1.0f,
    themeMode: String = "DARK",
    agyHubUrl: String = ai.antigem.data.preferences.AuthPreferences.currentHubUrl,
    agyBridgeHttpUrl: String = ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl,
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
    onLogout: () -> Unit = {},
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
    mcpServers: List<ai.antigem.domain.model.McpServerState> = emptyList(),
    isMcpLoading: Boolean = false,
    isMcpRefreshing: Boolean = false,
    refreshingMcpServer: String? = null,
    mcpErrorMessage: String? = null,
    mcpStatusMessage: String? = null,
    onClearMcpStatus: () -> Unit = {},
    onRefreshMcpServers: () -> Unit = {},
    onRefreshMcpServer: (String) -> Unit = {},
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onSaveMcpServer: (ai.antigem.domain.model.McpServerSpec, String?) -> Unit = { _, _ -> },
    onDeleteMcpServer: (String) -> Unit = {},
    availableCascadePlugins: List<ai.antigem.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (ai.antigem.data.remote.dto.AvailableCascadePluginDto) -> Unit = {},
    allSkills: List<ai.antigem.data.remote.dto.SkillDefinitionDto> = emptyList(),
    isSkillsLoading: Boolean = false,
    skillsFilterScope: String = "GLOBAL",
    onSetSkillsFilterScope: (String) -> Unit = {},
    onRefreshSkills: () -> Unit = {},
    installedPlugins: List<ai.antigem.data.remote.dto.InstalledPluginDto> = emptyList(),
    isInstalledPluginsLoading: Boolean = false,
    onRefreshInstalledPlugins: () -> Unit = {},
    googlePluginsCatalog: List<ai.antigem.data.remote.dto.BuildWithGooglePluginItemDto> = emptyList(),
    isGooglePluginsLoading: Boolean = false,
    installingGooglePluginId: String? = null,
    deletingPluginId: String? = null,
    onRefreshGooglePlugins: () -> Unit = {},
    onInstallGooglePlugin: (String, String) -> Unit = { _, _ -> },
    onDeletePlugin: (String, String) -> Unit = { _, _ -> },
    pluginActionStatusMessage: String? = null,
    pluginActionErrorMessage: String? = null,
    onClearPluginStatus: () -> Unit = {},
    floatingSwitcherOrientation: String = "VERTICAL",
    floatingSwitcherItems: List<String> = listOf("chat", "ide", "terminal", "browser", "notes", "files"),
    floatingSwitcherAutoCollapseSec: Int = 0,
    onSetFloatingSwitcherOrientation: (String) -> Unit = {},
    floatingSwitcherHandleIcon: String = "grid",
    onSetFloatingSwitcherHandleIcon: (String) -> Unit = {},
    onSetFloatingSwitcherItems: (List<String>) -> Unit = {},
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit = {},
    onResetFloatingSwitcherPosition: () -> Unit = {},
    chatHeaderItems: List<String> = listOf("newchat"),
    onSetChatHeaderItems: (List<String>) -> Unit = {},
    commandAutoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
    commandSandboxEnabled: Boolean = false,
    requireApprovalForFileEdits: Boolean = false,
    defaultApprovalScope: String = "PERMISSION_SCOPE_ONCE",
    groupAgentSteps: Boolean = true,
    onToggleGroupAgentSteps: (Boolean) -> Unit = {},
    groupStepsMin: Int = 2,
    onSetGroupStepsMin: (Int) -> Unit = {},
    showMessageStats: Boolean = true,
    onToggleShowMessageStats: (Boolean) -> Unit = {},
    globalSecuritySettings: ai.antigem.data.remote.AgyHubClient.GlobalUserSettings? = null,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean = false,
    projectsList: List<ai.antigem.data.remote.AgyHubClient.ProjectItem> = emptyList(),
    isProjectsLoading: Boolean = false,
    onSetGlobalArtifactReviewMode: (String) -> Unit = {},
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit = { _, _ -> },
    onSetGlobalCustomTerminalPolicy: (String) -> Unit = {},
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit = {},
    onSetGlobalTerminalSandbox: (Boolean) -> Unit = {},
    onSetProjectInheritGlobal: (ai.antigem.data.remote.AgyHubClient.ProjectItem) -> Unit = {},
    onSetProjectPreset: (ai.antigem.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit = { _, _, _ -> },
    onRefreshSecurityAndProjects: () -> Unit = {},
    onSetCommandAutoExecutionPolicy: (String) -> Unit = {},
    onSetCommandSandboxEnabled: (Boolean) -> Unit = {},
    onSetRequireApprovalForFileEdits: (Boolean) -> Unit = {},
    onSetDefaultApprovalScope: (String) -> Unit = {},
    onAddPermissionRule: (action: String, pattern: String, decision: String) -> Unit = { _, _, _ -> },
    onRemovePermissionRule: (rawRule: String) -> Unit = {},
    onChangePermissionRuleDecision: (rawRule: String, newDecision: String) -> Unit = { _, _ -> },
    isFloatingDiagnosticsEnabled: Boolean = false,
    onToggleFloatingDiagnostics: (Boolean) -> Unit = {},
    isNetworkInspectorEnabled: Boolean = false,
    onToggleNetworkInspector: (Boolean) -> Unit = {},
    isFloatingNetworkInspectorEnabled: Boolean = false,
    onToggleFloatingNetworkInspector: (Boolean) -> Unit = {},
    onOpenNetworkInspector: () -> Unit = {},
    claude: ai.antigem.ui.claude.ClaudeSettingsDeps? = null,
    initialClaudePage: ai.antigem.ui.claude.ClaudeSettingsPage? = null,
    openCode: ai.antigem.data.agent.opencode.OpenCodeChatBackend? = null,
    /** Opens on this OpenCode page (e.g. OpenCode CLI from an Install button). */
    initialOpenCodePage: ai.antigem.ui.opencode.OpenCodeSettingsPage? = null,
    codex: ai.antigem.data.agent.codex.CodexChatBackend? = null,
    /** Opens on this Codex page (e.g. Account & CLI from an Install button). */
    initialCodexPage: ai.antigem.ui.codex.CodexSettingsPage? = null,
    /** Antigravity's status and Google account, for its Account & CLI page (null: Antigravity is off). */
    agyStatus: ai.antigem.ui.agents.AgentStatusEntry? = null,
    agyAuth: ai.antigem.data.remote.AgyHubClient.AgyAuthInfo = ai.antigem.data.remote.AgyHubClient.AgyAuthInfo(),
    onAgySignOut: () -> Unit = {},
    onAgyCancelSignIn: () -> Unit = {},
    /** Opens on this page (e.g. Jarvis asked to show it); also switches to it while the dialog is open. */
    initialSection: SettingsSection? = null,
    enabledAgents: Set<ai.antigem.domain.model.AgentKind> = setOf(ai.antigem.domain.model.AgentKind.AGY),
    isApplyingAgents: Boolean = false,
    onApplyAgents: (Set<ai.antigem.domain.model.AgentKind>) -> Unit = {},
    /** Opens a project cloned from Settings → GitHub (the dialog closes itself). */
    onOpenProject: (ProjectItem) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentSection by remember { mutableStateOf(initialSection ?: if (initialClaudePage != null && claude != null) SettingsSection.CLAUDE_PAGE else SettingsSection.MAIN) }
    // the Ubuntu chroot shown on its page (null: the one in use)
    var chrootManagePath by remember { mutableStateOf<String?>(null) }
    var claudePage by remember { mutableStateOf(initialClaudePage ?: ai.antigem.ui.claude.ClaudeSettingsPage.ACCOUNT) }
    // Settings → Language → the texts of this language
    var languageTextsId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ai.antigem.i18n.LanguageManager.currentId) }
    var petEditId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ai.antigem.ui.components.pet.PetKind.GEMMY.id) }
    var openCodePage by remember { mutableStateOf(initialOpenCodePage ?: ai.antigem.ui.opencode.OpenCodeSettingsPage.SERVER) }
    LaunchedEffect(initialOpenCodePage) {
        if (initialOpenCodePage != null && openCode != null) {
            openCodePage = initialOpenCodePage
            currentSection = SettingsSection.OPENCODE_PAGE
        }
    }
    var codexPage by remember { mutableStateOf(initialCodexPage ?: ai.antigem.ui.codex.CodexSettingsPage.ACCOUNT) }
    LaunchedEffect(initialCodexPage) {
        if (initialCodexPage != null && codex != null) {
            codexPage = initialCodexPage
            currentSection = SettingsSection.CODEX_PAGE
        }
    }
    LaunchedEffect(initialSection) { if (initialSection != null) currentSection = initialSection }
    // Hoisted so the main list keeps its position while a sub-section is open
    val mainMenuScrollState = rememberScrollState()

    // Intercept system Back button
    BackHandler(enabled = true) {
        if (currentSection != SettingsSection.MAIN) {
            currentSection = currentSection.parent
        } else {
            onDismiss()
        }
    }

    // the Language page's lists are read ahead (once, off the main thread), so it opens complete instead of filling in
    LaunchedEffect(Unit) { ai.antigem.i18n.LanguageRepo.prewarm() }

    val isDark = isAppInDarkTheme()
    val cardBg = ai.antigem.theme.AppColors.card
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

    // every list in settings bounces at its ends like iOS instead of Android's stretch
    ai.antigem.ui.components.ProvideBouncyOverscroll {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.imeAnimationTarget)
        ) {
            // Top App Bar
            TopAppBar(
                title = {
                    // a Claude Code page names itself here (it has no header of its own)
                    val isClaudePage = currentSection == SettingsSection.CLAUDE_PAGE && claude != null
                    val isOpenCodePage = currentSection == SettingsSection.OPENCODE_PAGE && openCode != null
                    val isCodexPage = currentSection == SettingsSection.CODEX_PAGE && codex != null
                    Column {
                        Text(
                            text = if (isClaudePage) L.settings.dialog.claudeCodeTitle(claudePage.title) else if (isOpenCodePage) "OpenCode · ${openCodePage.title}"
                                else if (isCodexPage) "Codex · ${codexPage.title}" else currentSection.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentSection != SettingsSection.MAIN) {
                            Text(
                                text = if (isClaudePage) claudePage.subtitle else if (isOpenCodePage) openCodePage.subtitle
                                    else if (isCodexPage) codexPage.subtitle else currentSection.subtitle,
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
                            currentSection = currentSection.parent
                        } else {
                            onDismiss()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = L.common.back,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = L.common.done,
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
                    if (targetState != initialState.parent) {
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
                // pages that use the Termux environment's tools: a notice while the Ubuntu chroot runs this phone
                val termuxOnly = section in TERMUX_ONLY_SECTIONS && !ai.antigem.data.preferences.AuthPreferences.currentRemoteMode &&
                    ai.antigem.data.local.chroot.ChrootManager.isActiveNow()
                when (if (termuxOnly) SettingsSection.TERMUX_ONLY else section) {
                    SettingsSection.TERMUX_ONLY -> ai.antigem.ui.settings.chroot.TermuxOnlyNotice(section.title, cardBg, cardBorder,
                        note = if (section == SettingsSection.BACKUPS) L.settings.dialog.theUbuntuChrootHasItsOwn else null) {
                        currentSection = SettingsSection.SERVERS
                    }
                    SettingsSection.UBUNTU_CHROOT -> ai.antigem.ui.settings.chroot.ChrootManageScreen(
                        path = chrootManagePath,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenTerminal = {
                            onOpenLocalTerminal()
                            onDismiss()
                        },
                        onDeleted = {
                            chrootManagePath = null
                            currentSection = SettingsSection.SERVERS
                        }
                    )
                    SettingsSection.MAIN -> MainSettingsMenu(
                        themeMode = themeMode,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        useSshTerminal = useSshTerminal,
                        mcpServers = mcpServers,
                        isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
                        isLocalToolsInstalled = isLocalToolsInstalled,
                        cardBg = cardBg,
                        scrollState = mainMenuScrollState,
                        enabledAgents = enabledAgents,
                        claude = claude,
                        openCode = openCode,
                        codex = codex,
                        onNavigate = { currentSection = it }
                    )

                    SettingsSection.LANGUAGE -> LanguageScreen(onOpenTexts = { languageTextsId = it; currentSection = SettingsSection.LANGUAGE_TEXTS })
                    SettingsSection.LANGUAGE_TEXTS -> LanguageTextsScreen(languageTextsId, onOpen = { languageTextsId = it })
                    SettingsSection.PETS -> PetsSettingsScreen(onEditPet = { petEditId = it.id; currentSection = SettingsSection.PET_EDIT })
                    SettingsSection.PET_EDIT -> PetEditScreen(ai.antigem.ui.components.pet.PetKind.of(petEditId))

                    SettingsSection.THEMES -> ThemesSubScreen(
                        themeMode = themeMode,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetThemeMode = onSetThemeMode
                    )

                    SettingsSection.APPEARANCE -> AppearanceSubScreen(
                        chatFontScale = chatFontScale,
                        floatingSwitcherOrientation = floatingSwitcherOrientation,
                        floatingSwitcherItems = floatingSwitcherItems,
                        floatingSwitcherAutoCollapseSec = floatingSwitcherAutoCollapseSec,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetChatFontScale = onSetChatFontScale,
                        onSetFloatingSwitcherOrientation = onSetFloatingSwitcherOrientation,
                        floatingSwitcherHandleIcon = floatingSwitcherHandleIcon,
                        onSetFloatingSwitcherHandleIcon = onSetFloatingSwitcherHandleIcon,
                        onSetFloatingSwitcherItems = onSetFloatingSwitcherItems,
                        onSetFloatingSwitcherAutoCollapseSec = onSetFloatingSwitcherAutoCollapseSec,
                        onResetFloatingSwitcherPosition = onResetFloatingSwitcherPosition,
                        chatHeaderItems = chatHeaderItems,
                        onSetChatHeaderItems = onSetChatHeaderItems,
                        groupAgentSteps = groupAgentSteps,
                        onToggleGroupAgentSteps = onToggleGroupAgentSteps,
                        groupStepsMin = groupStepsMin,
                        onSetGroupStepsMin = onSetGroupStepsMin,
                        showMessageStats = showMessageStats,
                        onToggleShowMessageStats = onToggleShowMessageStats
                    )

                    // a PC in use (Connect to PC): the form would show the PC's values, so it waits for the switch back
                    SettingsSection.SERVERS -> if (ai.antigem.data.pc.PcConnect.active() != null) {
                        ai.antigem.ui.pc.PcServersNotice(cardBg, cardBorder, onOpenConnectPc = { currentSection = SettingsSection.CONNECT_PC })
                    } else ServersSubScreen(
                        currentHubUrl = agyHubUrl,
                        currentBridgeUrl = agyBridgeHttpUrl,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSaveServerUrls = onSaveServerUrls,
                        onInstallTermux = onInstallLocalTools,
                        onManageChroot = { path ->
                            chrootManagePath = path
                            currentSection = SettingsSection.UBUNTU_CHROOT
                        }
                    )

                    SettingsSection.CONNECT_PC -> ai.antigem.ui.pc.ConnectPcScreen(cardBg = cardBg, cardBorder = cardBorder)

                    SettingsSection.WEBVIEW_EXTENSIONS -> WebViewExtensionsScreen(onChanged = onRefreshMcpServers)

                    SettingsSection.SLASH_COMMANDS -> CustomSlashCommandsScreen()

                    SettingsSection.PROJECT_CHECKPOINTS -> ai.antigem.ui.checkpoint.ProjectCheckpointsScreen()

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
                        onInstallCascadePlugin = onInstallCascadePlugin,
                        onOpenRea = { currentSection = SettingsSection.REA },
                        onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
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
                        onOpenLocalTerminal = onOpenLocalTerminal,
                        onOpenRuntime = { currentSection = SettingsSection.SERVERS }
                    )

                    SettingsSection.BACKUPS -> BackupsSubScreen(
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onStopServer = {
                            LocalServerManager.stopServer()
                        }
                    )

                    SettingsSection.DATA_PROTECTION -> DataProtectionSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
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

                    SettingsSection.DIAGNOSTICS -> DiagnosticsSubScreen(
                        isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
                        onToggleFloatingDiagnostics = onToggleFloatingDiagnostics,
                        isNetworkInspectorEnabled = isNetworkInspectorEnabled,
                        onToggleNetworkInspector = onToggleNetworkInspector,
                        isFloatingNetworkInspectorEnabled = isFloatingNetworkInspectorEnabled,
                        onToggleFloatingNetworkInspector = onToggleFloatingNetworkInspector,
                        onOpenNetworkInspector = onOpenNetworkInspector,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )

                    SettingsSection.AGENTS -> ai.antigem.ui.agents.AgentsSettingsPage(
                        enabled = enabledAgents,
                        isApplying = isApplyingAgents,
                        onApply = onApplyAgents,
                        onOpenGlobalContext = { currentSection = SettingsSection.GLOBAL_CONTEXT },
                        onOpenAgentMemory = { currentSection = SettingsSection.AGENT_MEMORY },
                        onOpenEgm = { currentSection = SettingsSection.EGM }
                    )
                    SettingsSection.GLOBAL_CONTEXT -> GlobalContextScreen()
                    SettingsSection.EGM -> EgmScreen(cardBg = cardBg, cardBorder = cardBorder)
                    SettingsSection.AGENT_MEMORY -> AgentMemoryScreen(onOpenHistory = { currentSection = SettingsSection.AGENT_HISTORY })
                    SettingsSection.AGENT_HISTORY -> AgentHistoryScreen()
                    SettingsSection.STEM_RENDERING -> StemRenderingScreen()
                    SettingsSection.JARVIS -> ai.antigem.ui.jarvis.JarvisSettingsScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpen = { page -> currentSection = SettingsSection.entries.first { it.jarvisPage == page } }
                    )
                    SettingsSection.JARVIS_AGENT, SettingsSection.JARVIS_PERSONALITY, SettingsSection.JARVIS_SETUP,
                    SettingsSection.JARVIS_WAKE, SettingsSection.JARVIS_LISTEN, SettingsSection.JARVIS_VOICE,
                    SettingsSection.JARVIS_TASKS, SettingsSection.JARVIS_APPROVALS, SettingsSection.JARVIS_SCREEN,
                    SettingsSection.JARVIS_CHAT -> ai.antigem.ui.jarvis.JarvisSettingsSectionPage(
                        page = section.jarvisPage!!,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.ANTIGRAVITY -> AntigravitySettingsMenu(
                        mcpServers = mcpServers,
                        allSkills = allSkills,
                        installedPlugins = installedPlugins,
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        cardBg = cardBg,
                        onNavigate = { currentSection = it }
                    )
                    SettingsSection.AGY_CLI -> Column(
                        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ai.antigem.ui.agents.AgyCliCard()
                        ai.antigem.ui.agents.AgyAccountCard(agyStatus, agyAuth, onAgySignOut, onAgyCancelSignIn)
                        ai.antigem.ui.agents.AgyAccountsCard(agyStatus, agyAuth)
                    }
                    SettingsSection.AGY_CLOUD -> Column(
                        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        ai.antigem.ui.cloud.AgyCloudSettingsCard()
                    }
                    SettingsSection.AGY_VOICE -> Column(
                        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        ai.antigem.ui.voice.AgyVoiceCallSettingsCard()
                    }
                    SettingsSection.HOME_SCREEN -> Column(
                        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        ai.antigem.ui.homescreen.HomeScreenSettingsCard()
                    }
                    SettingsSection.FILE_PICKER -> Column(
                        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        ai.antigem.ui.files.FilePickerSettingsCard()
                    }
                    SettingsSection.CLAUDE -> if (claude != null) {
                        ai.antigem.ui.claude.ClaudeSettingsGroup(claude, cardBg) { page ->
                            claudePage = page
                            currentSection = SettingsSection.CLAUDE_PAGE
                        }
                    }
                    SettingsSection.OPENCODE -> if (openCode != null) {
                        ai.antigem.ui.opencode.OpenCodeSettingsGroup(openCode, cardBg) { page ->
                            openCodePage = page
                            currentSection = SettingsSection.OPENCODE_PAGE
                        }
                    }
                    SettingsSection.CODEX -> if (codex != null) {
                        ai.antigem.ui.codex.CodexSettingsGroup(codex, cardBg) { page ->
                            codexPage = page
                            currentSection = SettingsSection.CODEX_PAGE
                        }
                    }
                    SettingsSection.CODEX_PAGE -> if (codex != null) {
                        ai.antigem.ui.codex.CodexSettingsScreen(
                            codex, codexPage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        )
                    }
                    SettingsSection.OPENCODE_PAGE -> if (openCode != null) {
                        ai.antigem.ui.opencode.OpenCodeSettingsScreen(
                            openCode, openCodePage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        )
                    }
                    SettingsSection.CLAUDE_PAGE -> if (claude != null) {
                        ai.antigem.ui.claude.ClaudeSettingsScreen(
                            claude, claudePage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        ) { currentSection = SettingsSection.CLAUDE }
                    }
                    SettingsSection.UTILITIES -> UtilitiesSubScreen(
                        cardBg = cardBg,
                        onNavigate = { currentSection = it }
                    )
                    SettingsSection.GITHUB -> GitHubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenProject = { project ->
                            onOpenProject(project)
                            onDismiss()
                        }
                    )
                    SettingsSection.ADB_CONNECT -> AdbConnectScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.ANDROID_AUTOMATION -> AndroidAutomationScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT }
                    )
                    SettingsSection.TERMUX_SUPPORT -> TermuxSupportMenu(
                        cardBg = cardBg,
                        onNavigate = { currentSection = it }
                    )
                    SettingsSection.TERMUX_API -> TermuxApiSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.X11_DESKTOP -> X11DesktopScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT }
                    )
                    SettingsSection.REA -> ReaScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenGroups = { currentSection = SettingsSection.REA_GROUPS },
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT },
                        onOpenPlugins = { currentSection = SettingsSection.WEBVIEW_EXTENSIONS }
                    )
                    SettingsSection.ANDROID_DEV -> AndroidDevScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT },
                        onOpenPlugins = { currentSection = SettingsSection.WEBVIEW_EXTENSIONS }
                    )
                    SettingsSection.REA_GROUPS -> ReaGroupsScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenRea = { currentSection = SettingsSection.REA }
                    )
                    SettingsSection.ABOUT -> AboutSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenChangelog = { currentSection = SettingsSection.CHANGELOG }
                    )
                    SettingsSection.CHANGELOG -> ChangelogScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.UPDATES -> UpdateSettingsScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.TROUBLESHOOT -> TroubleshootScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onNavigate = { currentSection = it }
                    )
                }
            }
        }
    }
    } // ProvideBouncyOverscroll
}

/**
 * The main settings page: rounded groups of rows (no group titles, no descriptions), like a phone's settings app.
 * Antigravity and Claude Code are one row each that open their own page.
 */
@Composable
private fun MainSettingsMenu(
    themeMode: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean,
    useSshTerminal: Boolean,
    mcpServers: List<ai.antigem.domain.model.McpServerState>,
    isFloatingDiagnosticsEnabled: Boolean = false,
    isLocalToolsInstalled: Boolean = false,
    cardBg: Color,
    scrollState: androidx.compose.foundation.ScrollState,
    claude: ai.antigem.ui.claude.ClaudeSettingsDeps? = null,
    openCode: ai.antigem.data.agent.opencode.OpenCodeChatBackend? = null,
    codex: ai.antigem.data.agent.codex.CodexChatBackend? = null,
    enabledAgents: Set<ai.antigem.domain.model.AgentKind> = setOf(ai.antigem.domain.model.AgentKind.AGY),
    onNavigate: (SettingsSection) -> Unit
) {
    val agyOn = ai.antigem.domain.model.AgentKind.AGY in enabledAgents
    val claudeOn = ai.antigem.domain.model.AgentKind.CLAUDE in enabledAgents
    val openCodeOn = ai.antigem.domain.model.AgentKind.OPENCODE in enabledAgents
    val openCodeState = openCode?.state?.collectAsState()?.value
    val codexOn = ai.antigem.domain.model.AgentKind.CODEX in enabledAgents
    val codexState = codex?.state?.collectAsState()?.value
    val c = ai.antigem.theme.AppColors
    val appContext = LocalContext.current
    // read again whenever the main page comes back (it leaves the composition while a page is open)
    val filePickerOn = remember { ai.antigem.data.files.AntiGemDocumentsProvider.isEnabled(appContext) }
    fun row(section: SettingsSection, icon: ImageVector, tint: Color, value: String? = null, valueColor: Color? = null) =
        SettingsRowItem(icon, tint, section.title, value, valueColor) { onNavigate(section) }

    val (serverValue, serverColor) = when {
        isServerOnline && isBridgeOnline -> L.settings.dialog.online to QuotaGreen
        isServerOnline || isBridgeOnline -> L.settings.dialog.partial to c.warning
        else -> L.settings.dialog.offline to c.danger
    }
    val jarvisOn by ai.antigem.data.jarvis.JarvisPreferences.enabled.state.collectAsState()
    // Project checkpoints: off / chosen projects / all git projects
    val checkpointSettings by ai.antigem.data.checkpoint.ProjectCheckpoints.settings.collectAsState()
    LaunchedEffect(Unit) { ai.antigem.data.checkpoint.ProjectCheckpoints.refreshSettings() }
    val checkpointsRow = row(SettingsSection.PROJECT_CHECKPOINTS, Icons.Outlined.History, c.info,
        when (checkpointSettings?.mode) {
            ai.antigem.data.checkpoint.CheckpointSettings.MODE_SELECTED -> L.settings.dialog.chosenProjects
            ai.antigem.data.checkpoint.CheckpointSettings.MODE_ALL -> L.common.allProjects
            null -> null
            else -> L.common.off
        },
        if (checkpointSettings?.isOn == true) QuotaGreen else null)
    val activeMcp = mcpServers.count { it.isEnabled }
    val deviceTools = rememberDeviceToolRows(onNavigate)
    val githubRow = rememberGitHubRow(onNavigate)
    val reaRow = rememberReaRow(onNavigate)
    val androidDevRow = rememberAndroidDevRow(onNavigate)
    // Connect to PC: off / on / the PC in use
    val pcContext = LocalContext.current
    LaunchedEffect(Unit) { ai.antigem.data.pc.PcConnect.init(pcContext) }
    val pcEnabled by ai.antigem.data.pc.PcConnect.enabled.collectAsState()
    val pcList by ai.antigem.data.pc.PcConnect.pcs.collectAsState()
    val pcActiveId by ai.antigem.data.pc.PcConnect.activeId.collectAsState()
    val pcInUse = pcList.firstOrNull { it.id == pcActiveId }?.takeIf { pcEnabled }
    val pcRow = row(SettingsSection.CONNECT_PC, Icons.Outlined.Computer, c.accentTeal,
        when { !pcEnabled -> L.common.off; pcInUse != null -> pcInUse.name; else -> L.common.on },
        if (pcInUse != null) QuotaGreen else null)

    // STEM & diagrams: whether the render bundle is installed
    val stemContext = LocalContext.current
    remember { ai.antigem.data.render.RenderBundle.init(stemContext) }
    val stemInstalled by ai.antigem.data.render.RenderBundle.installed.collectAsState()
    val stemRow = row(SettingsSection.STEM_RENDERING, Icons.Outlined.Science, c.accentTeal,
        if (stemInstalled) L.common.installed else null, if (stemInstalled) QuotaGreen else null)

    // Claude Code's row shows the account state (signed in / plan / signed out)
    var claudeValue: String? = null
    var claudeColor: Color? = null
    if (claude != null && claudeOn) {
        val status by claude.account.status.collectAsState()
        val state by claude.account.state.collectAsState()
        LaunchedEffect(Unit) { if (status == null) claude.account.refreshStatus() }
        claudeValue = when (state) {
            ai.antigem.data.agent.claude.ClaudeStatus.READY ->
                status?.auth?.subscriptionType?.let { ai.antigem.ui.agents.claudePlanLabel(it) } ?: L.common.signedIn
            ai.antigem.data.agent.claude.ClaudeStatus.SIGNED_OUT -> L.settings.dialog.signedOut
            else -> state.label
        }
        claudeColor = state.dotColor
    }

    // Pets row: the chosen pet's name, or Off
    val petContext = LocalContext.current
    val petPrefs = remember(petContext) { ai.antigem.data.preferences.AuthPreferences(petContext) }
    val petOn by petPrefs.isGemmyEnabled.collectAsState(initial = true)
    val petJson by petPrefs.petSettingsJson.collectAsState(initial = "")
    val petValue = if (!petOn) L.common.off else ai.antigem.ui.components.pet.PetSettings.fromJson(petJson).let { it.look().name(it.kind) }

    SettingsRowList(
        scrollState = scrollState,
        cardBg = cardBg,
        groups = listOf(
            listOf(
                row(SettingsSection.THEMES, Icons.Outlined.Palette, c.accentPink, ai.antigem.theme.ThemeManager.current.name),
                row(SettingsSection.LANGUAGE, Icons.Outlined.Translate, c.accentTeal,
                    ai.antigem.i18n.LanguageManager.currentId.let { id -> ai.antigem.i18n.LanguageManager.languages.firstOrNull { it.id == id }?.name ?: if (id == ai.antigem.i18n.LanguageManager.ENGLISH) L.settings.dialog.english else id }),
                row(SettingsSection.APPEARANCE, Icons.Outlined.Tune, c.accentAlt),
                row(SettingsSection.PETS, Icons.Outlined.Pets, c.accentPink, petValue),
                row(SettingsSection.HOME_SCREEN, Icons.Outlined.Widgets, c.accentTeal),
                row(SettingsSection.FILE_PICKER, Icons.Outlined.FolderShared, c.warning, if (filePickerOn) L.common.on else L.common.off),
                stemRow,
                // where agents run (Termux, Ubuntu chroot or a server), and whether the app reaches it
                row(SettingsSection.SERVERS, Icons.Outlined.Dns, c.info,
                    when {
                        ai.antigem.data.preferences.AuthPreferences.currentRemoteMode -> L.common.server
                        ai.antigem.data.local.chroot.ChrootManager.isActiveNow() -> L.common.ubuntuChroot
                        else -> "AntiGem"
                    } + if (serverColor == QuotaGreen) "" else " · $serverValue", serverColor),
                pcRow
            ),
            listOfNotNull(
                row(SettingsSection.AGENTS, Icons.Outlined.SmartToy, MaterialTheme.colorScheme.primary, when {
                    enabledAgents.size == ai.antigem.domain.model.AgentKind.entries.size -> L.settings.dialog.allAgents
                    enabledAgents.size >= 3 -> "${enabledAgents.size} agents"
                    enabledAgents.size == 2 -> ai.antigem.domain.model.AgentKind.entries.filter { it in enabledAgents }
                        .joinToString(" + ") {
                            when (it) {
                                ai.antigem.domain.model.AgentKind.AGY -> "AGY"
                                ai.antigem.domain.model.AgentKind.CLAUDE -> L.common.claude
                                ai.antigem.domain.model.AgentKind.OPENCODE -> "OpenCode"
                                ai.antigem.domain.model.AgentKind.CODEX -> L.common.codex
                            }
                        }
                    claudeOn -> L.common.claudeCode
                    openCodeOn -> "OpenCode"
                    codexOn -> L.common.codex
                    else -> L.common.antigravity
                }),
                if (agyOn) row(SettingsSection.ANTIGRAVITY, Icons.Outlined.RocketLaunch, GeminiBlue,
                    if (activeMcp > 0) "$activeMcp MCP" else null) else null,
                if (claude != null && claudeOn) row(SettingsSection.CLAUDE, Icons.Outlined.AutoAwesome, ClaudeAccent, claudeValue, claudeColor) else null,
                if (openCode != null && openCodeOn) row(SettingsSection.OPENCODE, Icons.Outlined.Code, ai.antigem.ui.components.OpenCodeAccent, openCodeState?.label,
                    if (openCodeState == ai.antigem.data.agent.opencode.OpenCodeState.READY) QuotaGreen else c.warning) else null,
                if (codex != null && codexOn) row(SettingsSection.CODEX, Icons.Outlined.Terminal, ai.antigem.ui.components.CodexAccent, codexState?.label,
                    if (codexState == ai.antigem.data.agent.codex.CodexState.READY) QuotaGreen else c.warning) else null,
                row(SettingsSection.JARVIS, Icons.Outlined.RecordVoiceOver, c.accentTeal, if (jarvisOn) L.common.on else L.common.off,
                    if (jarvisOn) QuotaGreen else null)
            ),
            listOf(
                row(SettingsSection.WEBVIEW_EXTENSIONS, Icons.Outlined.Extension, c.accentAlt),
                row(SettingsSection.SLASH_COMMANDS, Icons.Outlined.Bolt, c.warning),
                checkpointsRow
            ),
            listOfNotNull(
                row(SettingsSection.TERMINAL, Icons.Outlined.Terminal, c.accentIndigo,
                    if (useSshTerminal || ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) "SSH" else L.settings.dialog.local),
                githubRow,
                deviceTools.adb,
                deviceTools.automation,
                reaRow,
                androidDevRow,
                // Termux:API and X11, used by the Termux packages in the terminal; with the Ubuntu chroot only X11
                // works (Termux Support is Termux-only there), so its row takes the place of the menu
                if (chrootRunsPhone()) rememberX11Row(onNavigate)
                else row(SettingsSection.TERMUX_SUPPORT, Icons.Outlined.Cable, c.accentIndigo),
                // Utilities only shows once it has something in it (its old items are rows of their own now)
                if (utilityRows(onNavigate).isNotEmpty()) row(SettingsSection.UTILITIES, Icons.Outlined.Handyman, c.accentTeal) else null,
                row(SettingsSection.DIAGNOSTICS, Icons.Outlined.Speed, c.success,
                    if (isFloatingDiagnosticsEnabled) L.settings.dialog.hudOn else null, if (isFloatingDiagnosticsEnabled) QuotaGreen else null)
            ),
            listOfNotNull(
                // backups are of the phone's own Linux environment, so only with it installed and not in server mode
                if (isLocalToolsInstalled && !ai.antigem.data.preferences.AuthPreferences.currentRemoteMode)
                    row(SettingsSection.BACKUPS, Icons.Outlined.Backup, c.info) else null,
                row(SettingsSection.DATA_PROTECTION, Icons.Outlined.Security, c.success)
            ),
            listOf(
                row(SettingsSection.UPDATES, Icons.Outlined.SystemUpdateAlt, c.info),
                row(SettingsSection.TROUBLESHOOT, Icons.Outlined.HealthAndSafety, c.warning),
                row(SettingsSection.ABOUT, Icons.Outlined.Info, c.accentAlt, "v${BuildConfig.VERSION_NAME}")
            )
        )
    )
}

/** Antigravity's own page: its MCP servers, skills & plugins, and commands & permissions. */
@Composable
private fun AntigravitySettingsMenu(
    mcpServers: List<ai.antigem.domain.model.McpServerState>,
    allSkills: List<ai.antigem.data.remote.dto.SkillDefinitionDto>,
    installedPlugins: List<ai.antigem.data.remote.dto.InstalledPluginDto>,
    commandAutoExecutionPolicy: String,
    cardBg: Color,
    onNavigate: (SettingsSection) -> Unit
) {
    val c = ai.antigem.theme.AppColors
    val activeMcp = mcpServers.count { it.isEnabled }
    val (mcpValue, mcpColor) = when {
        mcpServers.isEmpty() -> L.settings.dialog.none to null
        activeMcp > 0 -> L.settings.dialog.countActive(activeMcp) to QuotaGreen
        else -> L.settings.dialog.allOff to c.warning
    }
    val (policyValue, policyColor) = when {
        commandAutoExecutionPolicy.contains("EAGER", ignoreCase = true) -> L.settings.dialog.autoRun to QuotaGreen
        commandAutoExecutionPolicy.contains("AUTO", ignoreCase = true) -> L.settings.dialog.smartSafety to c.accentTeal
        else -> L.common.askFirst to c.warning
    }
    // the CLI row shows the installed version (like Claude Code's Account & CLI row)
    val cli by ai.antigem.data.remote.BridgeInfo.agyCli.collectAsState()
    LaunchedEffect(Unit) { if (cli == null) ai.antigem.data.remote.BridgeInfo.refreshAgyCli() }
    val (cliValue, cliColor) = when {
        cli == null -> null to null
        cli!!.installed -> cli!!.version.ifBlank { L.common.installed } to QuotaGreen
        else -> L.common.notInstalled to c.warning
    }
    val cloudOn by ai.antigem.data.agent.agy.AgyCloud.enabled.collectAsState()
    val voiceBubbleOn by ai.antigem.data.voice.AgyVoicePreferences.bubble.collectAsState()
    SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(
                SettingsRowItem(Icons.Outlined.Terminal, GeminiBlue, SettingsSection.AGY_CLI.title, cliValue, cliColor) { onNavigate(SettingsSection.AGY_CLI) },
                SettingsRowItem(Icons.Outlined.Cloud, GeminiBlue, SettingsSection.AGY_CLOUD.title,
                    if (cloudOn) L.common.on else L.common.off, if (cloudOn) QuotaGreen else null) { onNavigate(SettingsSection.AGY_CLOUD) },
                SettingsRowItem(Icons.Outlined.HeadsetMic, GeminiBlue, SettingsSection.AGY_VOICE.title,
                    if (voiceBubbleOn) L.settings.dialog.floatingOn else null, if (voiceBubbleOn) QuotaGreen else null) { onNavigate(SettingsSection.AGY_VOICE) }
            ),
            listOf(
                SettingsRowItem(Icons.Outlined.Hub, GeminiBlue, SettingsSection.MCP.title, mcpValue, mcpColor) { onNavigate(SettingsSection.MCP) },
                SettingsRowItem(Icons.Outlined.AutoAwesome, c.accentAlt, SettingsSection.SKILLS_PLUGINS.title,
                    L.settings.dialog.allskillscountSkills(allSkills.size, installedPlugins.size)) { onNavigate(SettingsSection.SKILLS_PLUGINS) },
                SettingsRowItem(Icons.Outlined.Security, c.warning, SettingsSection.COMMANDS.title, policyValue, policyColor) { onNavigate(SettingsSection.COMMANDS) }
            )
        )
    )
}

/** Settings → Termux Support: the features that work with Termux companion apps (they share AntiGem's UID). */
@Composable
private fun TermuxSupportMenu(
    cardBg: Color,
    onNavigate: (SettingsSection) -> Unit
) {
    val context = LocalContext.current
    val c = ai.antigem.theme.AppColors
    LaunchedEffect(Unit) {
        ai.antigem.data.x11.X11DesktopManager.init(context)
        ai.antigem.data.x11.X11DesktopManager.refresh()
    }
    val x11 by ai.antigem.data.x11.X11DesktopManager.state.collectAsState()
    val apiMode = if (ai.antigem.data.local.LocalEnvironmentManager.getTermuxApiProvider(context) ==
        ai.antigem.data.local.LocalEnvironmentManager.TERMUX_API_PROVIDER_EXTERNAL) L.settings.dialog.termuxApiApp else L.common.builtIn
    val (x11Value, x11Color) = when {
        x11.running -> L.common.running to QuotaGreen
        else -> null to null
    }
    SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(
                SettingsRowItem(Icons.Outlined.Api, c.accentTeal, SettingsSection.TERMUX_API.title,
                    apiMode) { onNavigate(SettingsSection.TERMUX_API) },
                SettingsRowItem(Icons.Outlined.DesktopWindows, c.accentIndigo, SettingsSection.X11_DESKTOP.title,
                    x11Value, x11Color) { onNavigate(SettingsSection.X11_DESKTOP) }
            )
        )
    )
}

/** The X11 Desktop row of the main settings page (Ubuntu chroot mode), with whether the X server runs. */
@Composable
private fun rememberX11Row(onNavigate: (SettingsSection) -> Unit): SettingsRowItem {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ai.antigem.data.x11.X11DesktopManager.init(context)
        ai.antigem.data.x11.X11DesktopManager.refresh()
    }
    val x11 by ai.antigem.data.x11.X11DesktopManager.state.collectAsState()
    val c = ai.antigem.theme.AppColors
    return SettingsRowItem(Icons.Outlined.DesktopWindows, c.accentIndigo, SettingsSection.X11_DESKTOP.title,
        if (x11.running) L.common.running else null, if (x11.running) QuotaGreen else null) {
        onNavigate(SettingsSection.X11_DESKTOP)
    }
}

/** What Settings → Utilities lists. Empty for now: when it is, the Utilities row is hidden. Add future handy tools here. */
private fun utilityRows(onNavigate: (SettingsSection) -> Unit): List<SettingsRowItem> = emptyList()

@Composable
private fun UtilitiesSubScreen(
    cardBg: Color,
    onNavigate: (SettingsSection) -> Unit
) {
    SettingsRowList(cardBg = cardBg, groups = listOf(utilityRows(onNavigate)))
}

/** The GitHub row of the main settings page, with the sign-in state. */
@Composable
private fun rememberGitHubRow(onNavigate: (SettingsSection) -> Unit): SettingsRowItem? {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ai.antigem.data.github.GitHubManager.init(context)
        ai.antigem.data.github.GitHubManager.refreshIfStale()
    }
    val gh by ai.antigem.data.github.GitHubManager.state.collectAsState()
    val c = ai.antigem.theme.AppColors
    val (value, color) = when {
        gh.signedIn -> (gh.account?.login ?: L.common.signedIn) to QuotaGreen
        gh.ghInstalled == false -> L.common.notInstalled to c.warning
        gh.ghInstalled == true -> L.settings.dialog.signedOut to null
        else -> null to null
    }
    return SettingsRowItem(Icons.Outlined.AccountTree, c.accentAlt, SettingsSection.GITHUB.title, value, color) {
        onNavigate(SettingsSection.GITHUB)
    }
}

/** The REA row of the main settings page, with the setup state. */
@Composable
private fun rememberReaRow(onNavigate: (SettingsSection) -> Unit): SettingsRowItem? {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ai.antigem.data.rea.ReaManager.init(context)
        ai.antigem.data.rea.ReaManager.refresh()
    }
    val rea by ai.antigem.data.rea.ReaManager.state.collectAsState()
    val c = ai.antigem.theme.AppColors
    val (value, color) = when {
        rea.busy -> L.common.installing to c.info
        rea.usable && rea.requiredDone -> (rea.reaVersion?.let { "v$it" } ?: L.common.ready) to QuotaGreen
        rea.installed.isNotEmpty() -> L.common.partlySetUp to c.warning
        else -> null to null
    }
    return SettingsRowItem(Icons.Outlined.BugReport, c.accentPink, SettingsSection.REA.title, value, color) {
        onNavigate(SettingsSection.REA)
    }
}

/** The Android Development row of the main settings page, with the setup state. */
@Composable
private fun rememberAndroidDevRow(onNavigate: (SettingsSection) -> Unit): SettingsRowItem? {
    // its setup (arm64 aapt2, rebuilt NDK, qemu for x86-only tools) is for arm64 phones only: hidden in the x86_64 build
    if (ai.antigem.BuildConfig.TARGET_ABI == "x86_64") return null
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ai.antigem.data.androiddev.AndroidDevManager.init(context)
        ai.antigem.data.androiddev.AndroidDevManager.refresh()
    }
    val dev by ai.antigem.data.androiddev.AndroidDevManager.state.collectAsState()
    val c = ai.antigem.theme.AppColors
    val (value, color) = when {
        dev.busy -> L.common.working to c.info
        dev.anyReady -> ai.antigem.data.androiddev.AndroidDevManager.Framework.entries.filter { dev.ready(it) }
            .joinToString(" · ") { when (it) {
                ai.antigem.data.androiddev.AndroidDevManager.Framework.KOTLIN -> L.settings.dialog.kotlin
                ai.antigem.data.androiddev.AndroidDevManager.Framework.FLUTTER -> L.settings.dialog.flutter
                ai.antigem.data.androiddev.AndroidDevManager.Framework.REACT_NATIVE -> "RN"
            } } to QuotaGreen
        dev.installed.any { it == ai.antigem.data.androiddev.AndroidDevManager.Component.SDK || it == ai.antigem.data.androiddev.AndroidDevManager.Component.LAUNCHER } -> L.common.partlySetUp to c.warning
        else -> null to null
    }
    return SettingsRowItem(Icons.Outlined.Android, c.success, SettingsSection.ANDROID_DEV.title, value, color) {
        onNavigate(SettingsSection.ANDROID_DEV)
    }
}

/** The ADB Connect and Android Automation rows of the main settings page, with their live state. */
private class DeviceToolRows(val adb: SettingsRowItem, val automation: SettingsRowItem)

@Composable
private fun rememberDeviceToolRows(onNavigate: (SettingsSection) -> Unit): DeviceToolRows {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ai.antigem.data.adb.AdbConnectManager.init(context)
        ai.antigem.data.adb.DeviceAccess.init(context)
        ai.antigem.data.adb.AdbConnectManager.refresh()
        ai.antigem.data.adb.ShizukuShell.refresh()
    }
    val adb by ai.antigem.data.adb.AdbConnectManager.state.collectAsState()
    val connected = adb.connectedDevice
    val accessMode by ai.antigem.data.adb.DeviceAccess.mode.collectAsState()
    val shizuku by ai.antigem.data.adb.ShizukuShell.status.collectAsState()
    val root by ai.antigem.data.adb.RootShell.state.collectAsState()
    var automationAllowed by remember { mutableStateOf(ai.antigem.data.automation.AndroidAutomation.isServiceRunning()) }
    LaunchedEffect(Unit) {
        while (true) {
            automationAllowed = ai.antigem.data.automation.AndroidAutomation.isServiceRunning()
            delay(1000)
        }
    }
    val c = ai.antigem.theme.AppColors
    return DeviceToolRows(
        adb = SettingsRowItem(
            Icons.Outlined.PhonelinkSetup, c.accentTeal, SettingsSection.ADB_CONNECT.title,
            when (accessMode) {
                ai.antigem.data.adb.DeviceAccess.Mode.ADB -> when {
                    connected != null -> L.settings.dialog.connected
                    adb.adbInstalled == false -> L.common.notInstalled
                    else -> null
                }
                ai.antigem.data.adb.DeviceAccess.Mode.SHIZUKU -> if (shizuku.ready) L.settings.dialog.shizuku else L.settings.dialog.shizukuNotReady
                ai.antigem.data.adb.DeviceAccess.Mode.ROOT ->
                    if (root.check == ai.antigem.data.adb.RootShell.Check.GRANTED) L.common.root else L.settings.dialog.rootNotGranted
            },
            when (accessMode) {
                ai.antigem.data.adb.DeviceAccess.Mode.ADB ->
                    if (connected != null) QuotaGreen else if (adb.adbInstalled == false) c.warning else null
                ai.antigem.data.adb.DeviceAccess.Mode.SHIZUKU -> if (shizuku.ready) QuotaGreen else c.warning
                ai.antigem.data.adb.DeviceAccess.Mode.ROOT ->
                    if (root.check == ai.antigem.data.adb.RootShell.Check.GRANTED) QuotaGreen else c.warning
            }
        ) { onNavigate(SettingsSection.ADB_CONNECT) },
        automation = SettingsRowItem(
            Icons.Outlined.TouchApp, c.accentAlt, SettingsSection.ANDROID_AUTOMATION.title,
            if (automationAllowed) L.common.allowed else L.common.off, if (automationAllowed) QuotaGreen else null
        ) { onNavigate(SettingsSection.ANDROID_AUTOMATION) }
    )
}

/**
 * Settings → Termux Support → Termux:API: how termux-api commands are handled, by AntiGem's built-in
 * handlers or by the external Termux:API companion app (it shares AntiGem's UID).
 */
@Composable
private fun TermuxApiSubScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val env = ai.antigem.data.local.LocalEnvironmentManager
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(env.getTermuxApiProvider(context)) }
    var activeCommandExecution by remember { mutableStateOf<Pair<String, String>?>(null) }
    // both can change outside this page (an install in the terminal, the app installed from its APK): re-checked
    var packageInstalled by remember { mutableStateOf(env.isTermuxApiPackageInstalled(context)) }
    var appInstalled by remember { mutableStateOf(env.isTermuxApiAppInstalled(context)) }
    // pkg only runs inside the local environment: without it the package can't be installed from here
    var envInstalled by remember { mutableStateOf(env.isTermuxInstalled(context)) }
    LaunchedEffect(activeCommandExecution) {
        while (true) {
            envInstalled = withContext(Dispatchers.IO) { env.isTermuxInstalled(context) }
            packageInstalled = withContext(Dispatchers.IO) { env.isTermuxApiPackageInstalled(context) }
            val nowInstalled = withContext(Dispatchers.IO) { env.isTermuxApiAppInstalled(context) }
            if (nowInstalled != appInstalled) {
                appInstalled = nowInstalled
                // the dispatcher says whether the app is installed: rewritten now, not only at the next start
                withContext(Dispatchers.IO) { env.ensureTermuxApiDispatcher(context) }
            }
            delay(2000)
        }
    }
    val apiApp = env.termuxApiAppPackage(context)

    fun choose(newMode: String) {
        if (mode == newMode) return
        mode = newMode
        scope.launch(Dispatchers.IO) { env.setTermuxApiProvider(context, newMode) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = L.settings.dialog.whoHandlesTheTermuxCommandsTermux,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TermuxApiModeOption(
                    selected = mode == env.TERMUX_API_PROVIDER_BUILTIN,
                    title = L.common.builtIn,
                    tag = "No app needed",
                    description = L.settings.dialog.antigemHandlesClipboardToastTorch,
                    enabled = true,
                    onClick = { choose(env.TERMUX_API_PROVIDER_BUILTIN) }
                )
                TermuxApiModeOption(
                    selected = mode == env.TERMUX_API_PROVIDER_EXTERNAL,
                    title = L.settings.dialog.antigemsTermuxApiApp,
                    tag = if (appInstalled) null else "Install it first",
                    description = "Every termux-* command goes to the app ($apiApp), all of them supported.",
                    enabled = appInstalled || mode == env.TERMUX_API_PROVIDER_EXTERNAL,
                    onClick = { choose(env.TERMUX_API_PROVIDER_EXTERNAL) }
                )
                if (mode == env.TERMUX_API_PROVIDER_EXTERNAL && !appInstalled) {
                    Text(
                        text = L.settings.dialog.theAppIsntInstalledTermuxCommands,
                        fontSize = 11.5.sp,
                        color = ai.antigem.theme.AppColors.warning
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TermuxApiStatusRow(
                    title = "termux-api package",
                    detail = L.settings.dialog.theTermuxCommandsInTheTerminal,
                    installed = packageInstalled
                ) {
                    if (envInstalled) {
                        OutlinedButton(
                            onClick = { activeCommandExecution = L.settings.dialog.installTermuxApi to "pkg install -y termux-api" },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text(L.common.install, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text(
                            text = L.settings.dialog.installTheLocalEnvironmentFirst,
                            modifier = Modifier.widthIn(max = 130.dp),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.End,
                            color = ai.antigem.theme.AppColors.warning
                        )
                    }
                }
                TermuxApiStatusRow(
                    title = L.settings.dialog.antigemsTermuxApiApp,
                    detail = "Location, camera, sensors, SMS and the other commands ($apiApp)",
                    installed = appInstalled
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/santoshkurmi/termux-api/releases")))
                            } catch (_: Exception) {}
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(L.settings.dialog.getTheApp, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    activeCommandExecution?.let { (title, cmd) ->
        CommandExecutionDialog(
            title = title,
            command = cmd,
            onDismiss = {
                activeCommandExecution = null
                // a package just installed: its own libexec/termux-api is moved aside and AntiGem's put back
                scope.launch(Dispatchers.IO) { env.ensureTermuxApiDispatcher(context) }
            }
        )
    }
}

/** One choice of the Termux:API page: radio button, title (with an optional tag) and what it does. */
@Composable
private fun TermuxApiModeOption(
    selected: Boolean,
    title: String,
    tag: String?,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (tag != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta)
                    }
                }
                Text(description, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Installed or not, with an action ([action]) shown only when it isn't. */
@Composable
private fun TermuxApiStatusRow(title: String, detail: String, installed: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (installed) L.common.installed else L.common.notInstalled,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (installed) QuotaGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!installed) {
            Spacer(modifier = Modifier.width(10.dp))
            action()
        }
    }
}

// ==========================================
// SUB-SCREEN: THEMES (light / dark mode, theme gallery)
// ==========================================
@Composable
private fun ThemesSubScreen(
    themeMode: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetThemeMode: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme Selector Header
        Text(
            text = L.settings.dialog.colorTheme,
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
                Triple("SYSTEM", L.settings.dialog.autoSystem, Icons.Outlined.BrightnessAuto),
                Triple("LIGHT", L.settings.dialog.light, Icons.Outlined.LightMode),
                Triple("DARK", L.settings.dialog.dark, Icons.Outlined.DarkMode)
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

        // Which light theme and which dark theme (built-in and custom ones)
        ThemeGallery(cardBg = cardBg, themeMode = themeMode, onSetThemeMode = onSetThemeMode)
    }
}

// ==========================================
// SUB-SCREEN 1: APPEARANCE
// ==========================================
@Composable
private fun AppearanceSubScreen(
    chatFontScale: Float,
    floatingSwitcherOrientation: String,
    floatingSwitcherItems: List<String>,
    floatingSwitcherAutoCollapseSec: Int,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetChatFontScale: (Float) -> Unit,
    onSetFloatingSwitcherOrientation: (String) -> Unit,
    floatingSwitcherHandleIcon: String,
    onSetFloatingSwitcherHandleIcon: (String) -> Unit,
    onSetFloatingSwitcherItems: (List<String>) -> Unit,
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit,
    onResetFloatingSwitcherPosition: () -> Unit,
    chatHeaderItems: List<String>,
    onSetChatHeaderItems: (List<String>) -> Unit,
    groupAgentSteps: Boolean,
    onToggleGroupAgentSteps: (Boolean) -> Unit,
    groupStepsMin: Int,
    onSetGroupStepsMin: (Int) -> Unit,
    showMessageStats: Boolean,
    onToggleShowMessageStats: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Font Scaling Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = L.settings.dialog.chatTextScalingValue((chatFontScale * 100).toInt()),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = L.settings.dialog.scalesMessagesMarkdownAndCodeBlocks,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
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

                val presets = listOf(0.85f to L.settings.dialog.small, 1.00f to L.settings.dialog.normal, 1.15f to L.settings.dialog.medium, 1.30f to L.settings.dialog.large, 1.50f to L.settings.dialog.huge)
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
            text = L.settings.dialog.livePreview,
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
                            text = L.settings.dialog.howDoesAntigemConnectToAgy,
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
                                text = L.settings.dialog.antigemConnectsViaGrpcWebTo,
                                fontSize = (13 * chatFontScale).sp,
                                lineHeight = (18 * chatFontScale).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Group Agent Steps Switch: runs of a reply's thinking and tool calls fold into one progress pill
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(groupAgentSteps, onToggleGroupAgentSteps),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = L.settings.dialog.groupAgentSteps,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.runsOfThinkingAndToolCalls,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = groupAgentSteps,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = onToggleGroupAgentSteps,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }
                // Fold after: how many steps in a row fold into a pill (1+ = every run, even a single step)
                if (groupAgentSteps) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = L.settings.dialog.foldAfterStepsInARow,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (1..6).forEach { n ->
                            val isSelected = groupStepsMin == n
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetGroupStepsMin(n) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$n+",
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
        }

        // Message Stats Switch: the token, cache and time stats under each reply
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .toggleRowSemantics(showMessageStats, onToggleShowMessageStats),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = L.settings.dialog.showMessageStats,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.tokenCountsCachePercentageAndTime,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = showMessageStats,
                    modifier = Modifier.mergedIntoRow(),
                    onCheckedChange = onToggleShowMessageStats,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
            }
        }

        // Chat Header Icons Card: shortcut buttons in the chat's top bar, next to the project
        ChatHeaderIconsCard(
            items = chatHeaderItems,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onSetItems = onSetChatHeaderItems
        )

        // Floating App Switcher Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = L.settings.dialog.floatingAppSwitcher,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.theFloatingPillAcrossTheWhole,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(14.dp))

                // The page overview (on/off, which pill opens it, its layout) and the pills' size
                SwitcherOverviewOptions(handleIcon = floatingSwitcherHandleIcon)
                // The icon panel's own options only while the switcher pill has one (not when its tap opens the overview)
                val iconPanelInUse = rememberPageOverviewMode() != "SWITCHER"

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(14.dp))

                if (iconPanelInUse) {
                    // Layout Orientation Selector
                    Text(
                        text = L.settings.dialog.layoutOrientation,
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
                                .selectable(selected = isVert, role = Role.RadioButton) { onSetFloatingSwitcherOrientation("VERTICAL") },
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
                                Text(L.settings.dialog.vertical, fontSize = 12.sp, fontWeight = if (isVert) FontWeight.Bold else FontWeight.Normal, color = if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .selectable(selected = !isVert, role = Role.RadioButton) { onSetFloatingSwitcherOrientation("HORIZONTAL") },
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
                                Text(L.settings.dialog.horizontal, fontSize = 12.sp, fontWeight = if (!isVert) FontWeight.Bold else FontWeight.Normal, color = if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Collapsed Pill Icon Selector
                Text(
                    text = L.settings.dialog.collapsedIcon,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = L.settings.dialog.theIconOnTheSmallHandle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val handleIcons = ai.antigem.ui.components.FloatingSwitcherHandleIcons
                // an unknown saved id shows the default icon, so mark that one
                val selectedHandleIcon = handleIcons.firstOrNull { it.first == floatingSwitcherHandleIcon }?.first
                    ?: handleIcons.first().first
                handleIcons.chunked(4).forEach { rowIcons ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowIcons.forEach { (iconId, icon) ->
                            val isSelected = iconId == selectedHandleIcon
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSetFloatingSwitcherHandleIcon(iconId) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = iconId.replaceFirstChar { it.uppercase() },
                                        tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (iconPanelInUse) {
                    // Auto-Collapse Timeout Selector
                    Text(
                        text = L.settings.dialog.autoCollapseTimeout,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.automaticallyCollapsesIntoASmallHandle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val timeouts = listOf(0 to L.common.never, 3 to "3s", 5 to "5s", 10 to "10s", 15 to "15s")
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
                }

                // Customize Items Order & Visibility
                Text(
                    text = L.settings.dialog.customizeIconsOrder,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))

                val allAvailable = listOf("chat", "ide", "terminal", "browser", "notes", "files", "run", "private")
                val currentItems = floatingSwitcherItems.toMutableList()
                val displayedList = currentItems + allAvailable.filterNot { currentItems.contains(it) }

                displayedList.forEach { itemId ->
                    val isEnabled = currentItems.contains(itemId)
                    val itemLabel = when (itemId) {
                        "chat" -> L.common.chat
                        "ide" -> L.settings.dialog.ideCodeEditor
                        "terminal" -> L.common.terminal
                        "browser" -> L.settings.dialog.webBrowser
                        "notes" -> L.common.notesToDos
                        "files" -> L.settings.dialog.filesFileExplorer
                        "run" -> L.settings.dialog.runBuildRunCommands
                        "private" -> L.privateSpace.settings.switcherLabel
                        else -> itemId
                    }
                    val itemIcon = when (itemId) {
                        "chat" -> Icons.Outlined.ChatBubbleOutline
                        "ide" -> Icons.Outlined.Code
                        "terminal" -> Icons.Outlined.Terminal
                        "browser" -> Icons.Outlined.Language
                        "notes" -> Icons.Outlined.EditNote
                        "files" -> Icons.Outlined.FolderOpen
                        "run" -> Icons.Outlined.PlayCircle
                        "private" -> Icons.Outlined.Shield
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
                            val onToggleSwitcherItem: (Boolean) -> Unit = { checked ->
                                val updated = if (checked) {
                                    currentItems + itemId
                                } else {
                                    if (currentItems.size > 1) currentItems.filter { it != itemId } else currentItems
                                }
                                onSetFloatingSwitcherItems(updated)
                            }
                            Row(Modifier.toggleRowSemantics(isEnabled, onToggleSwitcherItem, role = Role.Checkbox), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isEnabled,
                                    onCheckedChange = onToggleSwitcherItem,
                                    colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                    modifier = Modifier.size(24.dp).mergedIntoRow()
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
                                        Icon(Icons.Outlined.ExpandLess, contentDescription = L.settings.dialog.moveUp, modifier = Modifier.size(16.dp))
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
                                        Icon(Icons.Outlined.ExpandMore, contentDescription = L.settings.dialog.moveDown, modifier = Modifier.size(16.dp))
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
                    Text(L.settings.dialog.resetScreenPositionToRightCenter, fontSize = 12.sp)
                }
            }
        }

        // Standalone Terminal Launcher Icon & Style Settings (PackageManager as source of truth)
        val context = LocalContext.current
        val authPrefs = remember(context) { ai.antigem.data.preferences.AuthPreferences(context) }
        var isTerminalLauncherEnabled by remember(context) {
            mutableStateOf(ai.antigem.data.preferences.TerminalLauncherManager.isLauncherEnabled(context))
        }
        var terminalLauncherStyle by remember(context) {
            mutableStateOf(ai.antigem.data.preferences.TerminalLauncherManager.getLauncherStyle(context))
        }
        val coroutineScope = rememberCoroutineScope()

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                val onToggleTerminalLauncher: (Boolean) -> Unit = { enable ->
                    isTerminalLauncherEnabled = enable
                    ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                        context = context,
                        enabled = enable,
                        style = terminalLauncherStyle
                    )
                    coroutineScope.launch {
                        authPrefs.setTerminalLauncherEnabled(enable)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(isTerminalLauncherEnabled, onToggleTerminalLauncher),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = L.settings.dialog.separateTerminalAppIcon,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.showADedicatedTerminalIconOn,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isTerminalLauncherEnabled,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = onToggleTerminalLauncher,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                AnimatedVisibility(
                    visible = isTerminalLauncherEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        Text(
                            text = L.settings.dialog.launcherIconNameStyle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Option 1: AntiTerm
                        val isAntiTerm = terminalLauncherStyle == ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isAntiTerm) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(
                                1.dp,
                                if (isAntiTerm) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    terminalLauncherStyle = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                    ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                        context = context,
                                        enabled = true,
                                        style = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                    )
                                    coroutineScope.launch {
                                        authPrefs.setTerminalLauncherStyle(ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ai.antigem.ui.components.AppIconImage(
                                        contentDescription = "AntiTerm",
                                        modifier = Modifier.matchParentSize()
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AntiTerm",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = L.settings.dialog.modernAntigemStyledIconName,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                RadioButton(
                                    selected = isAntiTerm,
                                    onClick = {
                                        terminalLauncherStyle = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                        ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                            context = context,
                                            enabled = true,
                                            style = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                        )
                                        coroutineScope.launch {
                                            authPrefs.setTerminalLauncherStyle(ai.antigem.data.preferences.TerminalLauncherManager.STYLE_ANTITERM)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Option 2: Termux Classic
                        val isTermux = terminalLauncherStyle == ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTermux) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(
                                1.dp,
                                if (isTermux) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    terminalLauncherStyle = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                    ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                        context = context,
                                        enabled = true,
                                        style = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                    )
                                    coroutineScope.launch {
                                        authPrefs.setTerminalLauncherStyle(ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                        .padding(3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = ai.antigem.R.drawable.ic_termux_foreground),
                                        contentDescription = L.common.termux,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = L.common.termux,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = L.settings.dialog.classicPromptIconName,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                RadioButton(
                                    selected = isTermux,
                                    onClick = {
                                        terminalLauncherStyle = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                        ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                            context = context,
                                            enabled = true,
                                            style = ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                        )
                                        coroutineScope.launch {
                                            authPrefs.setTerminalLauncherStyle(ai.antigem.data.preferences.TerminalLauncherManager.STYLE_TERMUX)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 3: MCP SERVERS & TOOLS SETTINGS
// ==========================================
private data class McpPreset(
    val name: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val spec: ai.antigem.domain.model.McpServerSpec?,
    /** Opens its own flow instead of the server editor (REA's tool group picker). */
    val onClick: (() -> Unit)? = null,
    val added: Boolean = false
)

/** One preset in the MCP screens' grid (also used by the Claude CLI MCP settings). */
@Composable
internal fun McpPresetTile(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    added: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    addedLabel: String = L.settings.dialog.addedTapToEdit,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (added) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (added) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = L.common.added, tint = QuotaGreen, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    if (added) addedLabel else subtitle,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Picks which shortcut icons the chat's top bar shows (none is fine) and their order, like the floating switcher. */
@Composable
private fun ChatHeaderIconsCard(
    items: List<String>,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetItems: (List<String>) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = L.settings.dialog.chatHeaderIcons,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = L.settings.dialog.shortcutButtonsInTheChatsTop,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(10.dp))

            val allAvailable = listOf("newchat", "ide", "terminal", "browser", "notes", "files", "run")
            val currentItems = items.filter { it in allAvailable }
            val displayedList = currentItems + allAvailable.filterNot { currentItems.contains(it) }

            displayedList.forEach { itemId ->
                val isEnabled = currentItems.contains(itemId)
                val itemLabel = when (itemId) {
                    "newchat" -> L.settings.dialog.newChat
                    "ide" -> L.settings.dialog.ideCodeEditor
                    "terminal" -> L.common.terminal
                    "browser" -> L.settings.dialog.webBrowser
                    "notes" -> L.common.notesToDos
                    "files" -> L.settings.dialog.filesFileExplorer
                    "run" -> L.settings.dialog.runBuildRunCommands
                    else -> itemId
                }
                val itemIcon = when (itemId) {
                    "newchat" -> Icons.Outlined.AddComment
                    "ide" -> Icons.Outlined.Code
                    "terminal" -> Icons.Outlined.Terminal
                    "browser" -> Icons.Outlined.Language
                    "notes" -> Icons.Outlined.EditNote
                    "files" -> Icons.Outlined.FolderOpen
                    "run" -> Icons.Outlined.PlayCircle
                    else -> Icons.Outlined.AddComment
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
                        val onToggleShortcutItem: (Boolean) -> Unit = { checked ->
                            onSetItems(if (checked) currentItems + itemId else currentItems.filter { it != itemId })
                        }
                        Row(Modifier.toggleRowSemantics(isEnabled, onToggleShortcutItem, role = Role.Checkbox), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isEnabled,
                                onCheckedChange = onToggleShortcutItem,
                                colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                modifier = Modifier.size(24.dp).mergedIntoRow()
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
                            val index = currentItems.indexOf(itemId)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val copy = currentItems.toMutableList()
                                            copy[index] = copy[index - 1].also { copy[index - 1] = copy[index] }
                                            onSetItems(copy)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Outlined.ExpandLess, contentDescription = L.settings.dialog.moveUp, modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < currentItems.size - 1) {
                                            val copy = currentItems.toMutableList()
                                            copy[index] = copy[index + 1].also { copy[index + 1] = copy[index] }
                                            onSetItems(copy)
                                        }
                                    },
                                    enabled = index < currentItems.size - 1,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(Icons.Outlined.ExpandMore, contentDescription = L.settings.dialog.moveDown, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpSubScreen(
    mcpServers: List<ai.antigem.domain.model.McpServerState>,
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
    onSaveServer: (ai.antigem.domain.model.McpServerSpec, String?) -> Unit,
    onDeleteServer: (String) -> Unit,
    availableCascadePlugins: List<ai.antigem.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (ai.antigem.data.remote.dto.AvailableCascadePluginDto) -> Unit = {},
    onOpenRea: () -> Unit = {},
    onOpenReaGroups: () -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var chooseWebViewExtension by remember { mutableStateOf(false) }
    var showReaDialog by remember { mutableStateOf(false) }
    var showCascadeCatalogDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<ai.antigem.domain.model.McpServerSpec?>(null) }
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
                .bouncyVerticalScroll(rememberScrollState())
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
                                contentDescription = L.common.dismiss,
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
                            text = L.settings.dialog.modelContextProtocolMcp,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = L.settings.dialog.globalConfigGeminiConfigMcpConfig,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = L.settings.dialog.configureLocalProcessStdioOrRemote,
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
                        Text(if (isRefreshingAll) L.settings.dialog.testingAll else L.settings.dialog.testRefresh, fontSize = 13.sp)
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
                        Text(L.settings.dialog.addServer, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    Text(L.settings.dialog.browseMcpCatalogMarketplace1Click, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta)
                }
            }
        }

        // Quick presets: a two-column grid of tiles, all visible without scrolling sideways
        Text(
            text = L.settings.dialog.quickPresetsTemplates,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        run {
            val context = LocalContext.current
            val deviceId = remember(context) { ai.antigem.data.remote.LocalServerAuth.key(context) }
            val extensionManager = remember { ai.antigem.ui.browser.BrowserExtensionManager.instance }
            if (chooseWebViewExtension) WebViewExtensionMcpPicker(onDismiss = { chooseWebViewExtension = false }) { extension ->
                serverToEdit = ai.antigem.domain.model.McpServerSpec(
                    serverName = extensionManager.mcpServerName(extension.id),
                    serverUrl = extensionManager.mcpUrl(extension.id),
                    headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                )
                chooseWebViewExtension = false
                showDialog = true
            }
            val bridge = ai.antigem.data.remote.AndroidLocalBridgeServer
            val local = "http://127.0.0.1:${bridge.DEFAULT_PORT}"
            val deviceHeaders = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
            fun localSpec(name: String, path: String) =
                ai.antigem.domain.model.McpServerSpec(serverName = name, serverUrl = "$local$path", headers = deviceHeaders)
            // Already added (the plugin developer also under its old name): edit that one instead of adding a duplicate
            fun existing(spec: ai.antigem.domain.model.McpServerSpec) = mcpServers.firstOrNull {
                it.name == spec.serverName || (bridge.isDeveloperMcpName(spec.serverName) && bridge.isDeveloperMcpName(it.name))
            }
            val presets = listOf(
                // No spec: opens the picker for an installed plugin's own MCP
                McpPreset(L.common.customPluginMcp, L.common.pickAnInstalledPlugin, Icons.Outlined.Extension, ClaudeTerracotta, null),
                McpPreset(L.common.pluginDeveloper, L.settings.dialog.buildTestAntigemPlugins, Icons.Outlined.DeveloperMode, GeminiPurple,
                    localSpec(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
                McpPreset(L.common.miniAppDeveloper, L.settings.dialog.buildTestSignMiniApps, androidx.compose.material.icons.Icons.Outlined.Apps, GeminiBlue,
                    localSpec(bridge.MINI_APP_DEVELOPER_MCP_NAME, "/mini_app_developer")),
                McpPreset(L.common.browser, L.common.controlTheInAppBrowser, Icons.Outlined.Language, GeminiBlue, localSpec("browser_mcp", "/browser")),
                McpPreset(L.common.terminal, L.settings.dialog.runCommandsInTheTerminal, Icons.Outlined.Terminal, QuotaGreen, localSpec("terminal_mcp", "/terminal")),
                McpPreset(L.settings.dialog.android, L.settings.dialog.tapTypeReadThisPhone, Icons.Outlined.PhoneAndroid, ai.antigem.theme.AppColors.accentTeal,
                    localSpec(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
                McpPreset(L.common.notesToDos, L.settings.dialog.readEditYourNotes, Icons.Outlined.EditNote, ai.antigem.theme.AppColors.warning, localSpec(bridge.NOTES_MCP_NAME, "/notes")),
                McpPreset(L.common.jarvis, L.settings.dialog.voiceControlOfThisApp, Icons.Outlined.RecordVoiceOver, ai.antigem.theme.AppColors.accentTeal, localSpec(bridge.JARVIS_MCP_NAME, "/jarvis")),
                McpPreset(L.common.theme, L.settings.dialog.createSwitchAppThemes, Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentAlt, localSpec(bridge.THEME_MCP_NAME, "/theme")),
                McpPreset(L.common.language, L.settings.dialog.translateTheAppAddLanguages, Icons.Outlined.Translate, ai.antigem.theme.AppColors.accentTeal, localSpec(bridge.LANGUAGE_MCP_NAME, "/language")),
                McpPreset(L.common.stitch, L.settings.dialog.googleStitchUiDesignApiKey, Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentPink,
                    ai.antigem.domain.model.McpServerSpec(serverName = "stitch", serverUrl = "https://stitch.googleapis.com/mcp", headers = mapOf("X-Goog-Api-Key" to ""))),
                // REA runs in the terminal's proot-distro Ubuntu; the tile picks its tool groups
                McpPreset("REA", L.settings.dialog.reverseEngineeringApkNative, Icons.Outlined.BugReport, ai.antigem.theme.AppColors.accentPink, null,
                    onClick = { showReaDialog = true },
                    added = mcpServers.any { it.spec?.serverUrl.isNullOrBlank() && ai.antigem.data.rea.ReaManager.isReaCommand(it.spec?.command) })
            ).filterNotNull()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            val found = p.spec?.let { existing(it) }
                            McpPresetTile(p.name, p.subtitle, p.icon, p.accent, added = found != null || p.added, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (p.onClick != null) {
                                    p.onClick.invoke()
                                } else if (p.spec == null) {
                                    chooseWebViewExtension = true
                                } else {
                                    serverToEdit = found?.spec ?: p.spec
                                    showDialog = true
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
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
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
            )
            if (mcpServers.isNotEmpty()) {
                val totalTools = mcpServers.sumOf { it.tools.size }
                Text(
                    text = L.settings.dialog.totaltoolsToolsAvailable(totalTools),
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
                            text = L.settings.dialog.loadingMcpServers,
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
                            text = L.settings.dialog.unableToLoadMcpServers,
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
                            text = L.settings.dialog.couldNotRetrieveMcpServerStates,
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
                            Text(L.settings.dialog.retryConnection, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
                            text = L.settings.dialog.noMcpServersConfigured,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = L.settings.dialog.addAnMcpServerToConnect,
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
                else -> ai.antigem.theme.AppColors.warning
            }
            // off: no status badge, the switch shows it
            val statusLabel = when {
                !server.isEnabled -> null
                server.status.contains("RUNNING", ignoreCase = true) || server.status.contains("OK", ignoreCase = true) || server.tools.isNotEmpty() -> L.common.ready
                server.status.contains("ERROR", ignoreCase = true) || !server.error.isNullOrBlank() -> L.settings.dialog.error
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
                        modifier = Modifier.fillMaxWidth().toggleRowSemantics(server.isEnabled, { onToggleServer(server.name, it) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSse) ai.antigem.theme.AppColors.info.copy(alpha = 0.12f) else ai.antigem.theme.AppColors.accentAlt.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = if (isSse) ai.antigem.theme.AppColors.info else ai.antigem.theme.AppColors.accentAlt,
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
                                    color = if (isSse) ai.antigem.theme.AppColors.info.copy(alpha = 0.15f) else ai.antigem.theme.AppColors.accentAlt.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isSse) L.settings.dialog.sse else L.settings.dialog.stdio,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSse) ai.antigem.theme.AppColors.info else ai.antigem.theme.AppColors.accentAlt,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }

                                // Status badge
                                if (statusLabel != null) Surface(
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
                            modifier = Modifier.mergedIntoRow(),
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
                            color = ai.antigem.theme.AppColors.info.copy(alpha = 0.08f)
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
                                    tint = ai.antigem.theme.AppColors.info
                                )
                                Text(
                                    text = L.settings.dialog.headersValue(server.spec!!.headers.keys.joinToString(", ")),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = ai.antigem.theme.AppColors.info
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
                                    text = L.settings.dialog.cwdCwd(server.spec!!.cwd),
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
                                        Text(L.settings.dialog.connectionIssue, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Red)
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
                                            text = L.settings.dialog.toolscountDiscoveredToolValue(server.tools.size, if (server.tools.size > 1) "s" else ""),
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
                            Text(if (isThisRefreshing) L.settings.dialog.testing else L.common.refresh, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = {
                                serverToEdit = server.spec ?: ai.antigem.domain.model.McpServerSpec(serverName = server.name)
                                showDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(L.common.edit, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = { serverToDelete = server.name },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(L.common.remove, fontSize = 12.sp)
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
                        contentDescription = L.common.dismiss,
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
            title = { Text(L.settings.dialog.removeMcpServer) },
            text = {
                Text(L.settings.dialog.areYouSureYouWantTo(targetName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteServer(targetName)
                        serverToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text(L.common.remove, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // REA preset (the same in every agent's MCP settings): a stdio server running the rea-mcp launcher
    if (showReaDialog) {
        val reaServers = mcpServers.mapNotNull { srv ->
            srv.spec?.takeIf { it.serverUrl.isBlank() && ai.antigem.data.rea.ReaManager.isReaCommand(it.command) }?.let { srv.name to it }
        }.toMap()
        ReaServerDialog(
            agent = "Antigravity",
            existing = reaServers.mapValues { it.value.env },
            takenNames = mcpServers.map { it.name }.toSet(),
            canReplace = true,
            onDismiss = { showReaDialog = false },
            onSave = { name, env ->
                val base = reaServers[name] ?: ai.antigem.domain.model.McpServerSpec(serverName = name)
                onSaveServer(base.copy(serverName = name, command = ai.antigem.data.rea.ReaManager.LAUNCHER, args = emptyList(), env = env), null)
                showReaDialog = false
            },
            onOpenRea = { showReaDialog = false; onOpenRea() },
            onOpenGroups = { showReaDialog = false; onOpenReaGroups() }
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
    initialSpec: ai.antigem.domain.model.McpServerSpec?,
    onDismiss: () -> Unit,
    onSave: (ai.antigem.domain.model.McpServerSpec, String?) -> Unit
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
                val configRaw = ai.antigem.data.daemon.IdeApiClient.getMcpConfig()
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
                text = if (initialSpec != null && initialSpec.serverName.isNotBlank()) L.settings.dialog.editMcpServer else L.settings.dialog.addMcpServer,
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
                        McpEditMode.STDIO to L.settings.dialog.stdio,
                        McpEditMode.SSE to "SSE",
                        McpEditMode.RAW to L.settings.dialog.raw
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
                    label = { Text(L.settings.dialog.serverName) },
                    placeholder = { Text(L.settings.dialog.eGStitchFilesystemGithub) },
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
                                text = L.settings.dialog.serverConfigurationJson,
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
                                        validationError = L.settings.dialog.invalidJsonMessage(e.message)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(L.settings.dialog.format, fontSize = 11.sp, color = ClaudeTerracotta)
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
                        placeholder = { Text(L.settings.dialog.eGNpxPython3NodeUvx) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Arguments
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(L.settings.dialog.argumentsSpaceSeparated) },
                        placeholder = { Text(L.settings.dialog.eGYModelcontextprotocolServerFilesystem) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Working Directory
                    OutlinedTextField(
                        value = cwdText,
                        onValueChange = { cwdText = it },
                        label = { Text(L.settings.dialog.workingDirectoryOptional) },
                        placeholder = { Text(L.settings.dialog.eGProjectsMyApp) },
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
                        label = { Text(L.settings.dialog.httpHeadersHeaderValuePerLine) },
                        placeholder = { Text("X-Goog-Api-Key: AQ.Ab8RN6...\nAuthorization: Bearer token") },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = {
                            Text(
                                text = L.settings.dialog.setCustomHeadersLikeApiKeys,
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
                        validationError = L.settings.dialog.serverNameIsRequired
                        return@Button
                    }

                    if (mode == McpEditMode.RAW) {
                        try {
                            val sObj = org.json.JSONObject(rawJsonText)
                            val sUrl = sObj.optString("serverUrl", "").trim()
                            val sCmd = sObj.optString("command", "").trim()
                            if (sUrl.isBlank() && sCmd.isBlank()) {
                                validationError = L.settings.dialog.jsonMustSpecifyEitherCommandOr
                                return@Button
                            }
                            val isDis = sObj.optBoolean("disabled", initialSpec?.disabled ?: false)
                            val spec = ai.antigem.domain.model.McpServerSpec(
                                serverName = cleanName,
                                command = sCmd,
                                serverUrl = sUrl,
                                disabled = isDis
                            )
                            onSave(spec, rawJsonText.trim())
                        } catch (e: Exception) {
                            validationError = L.settings.dialog.invalidJsonMessage(e.message)
                            return@Button
                        }
                    } else if (mode == McpEditMode.STDIO) {
                        val cleanCmd = command.trim()
                        if (cleanCmd.isBlank()) {
                            validationError = L.settings.dialog.commandIsRequired
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

                        val spec = ai.antigem.domain.model.McpServerSpec(
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
                            validationError = L.settings.dialog.serverUrlIsRequired
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

                        val spec = ai.antigem.domain.model.McpServerSpec(
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
                Text(L.common.save, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L.common.cancel)
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
    onOpenLocalTerminal: () -> Unit,
    /** Where agents run (the phone's runtime: install, switch, delete). */
    onOpenRuntime: () -> Unit = {}
) {
    val context = LocalContext.current
    val remoteMode = ai.antigem.data.preferences.AuthPreferences.currentRemoteMode
    val coroutineScope = rememberCoroutineScope()
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
    val terminalPrefs = remember(context) { ai.antigem.data.preferences.AuthPreferences(context) }
    val sessionPillEnabled by terminalPrefs.terminalSessionPillEnabled
        .collectAsState(initial = terminalPrefs.getTerminalSessionPillEnabledSync())
    val drawerSwipeArea by terminalPrefs.terminalDrawerSwipeArea
        .collectAsState(initial = terminalPrefs.getTerminalDrawerSwipeAreaSync())
    val charBasedInput by terminalPrefs.terminalCharBasedInput
        .collectAsState(initial = terminalPrefs.getTerminalCharBasedInputSync())


    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
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
                    modifier = Modifier.fillMaxWidth().then(if (!remoteMode) Modifier.toggleRowSemantics(useSshTerminal, { onToggleUseSshTerminal(it) }) else Modifier),
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
                        Text(text = if (remoteMode) L.settings.dialog.terminalOnTheRemoteServer else L.settings.dialog.useTerminalViaSsh, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = if (remoteMode) L.settings.dialog.runsOnYourRemoteServerOver else if (useSshTerminal) L.settings.dialog.directSshToTermuxSshdWithout else L.settings.dialog.builtInChrootRootfsEnvironment,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (!remoteMode) Switch(
                        checked = useSshTerminal,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = { onToggleUseSshTerminal(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = QuotaGreen
                        )
                    )
                }

                if (remoteMode) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = L.settings.dialog.remoteServerModeIsOnThe((if (sshHost.isNotBlank()) " ($sshUser@$sshHost:$sshPort)" else "")),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (useSshTerminal && !remoteMode) {
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
                            label = { Text(L.settings.dialog.sshHost) },
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
                            label = { Text(L.common.port) },
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
                            label = { Text(L.common.user) },
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
                            label = { Text(L.settings.dialog.password) },
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
                            sshTestStatus = L.settings.dialog.testingSshConnectionToHoststate(hostState)
                            coroutineScope.launch {
                                val res = ai.antigem.data.ssh.SshSessionManager.testConnection(
                                    host = hostState.trim(),
                                    port = portState.toIntOrNull() ?: sshPort,
                                    user = userState.trim(),
                                    pass = passState
                                )
                                isTestingSsh = false
                                sshTestStatus = if (res.isSuccess) L.settings.dialog.sshConnectionSuccessful else "✗ Failed: ${res.exceptionOrNull()?.localizedMessage ?: "Connection refused"}"
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
                        Text(L.settings.dialog.testSshConnection, fontSize = 12.sp)
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
                            sshSaveFeedback = L.settings.dialog.sshSettingsSavedSuccessfully
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(L.settings.dialog.saveSshSettings, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else if (!remoteMode) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = L.settings.dialog.terminalTabsOpenAShellWhere,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    TextButton(onClick = onOpenRuntime) { Text(L.settings.dialog.whereAgentsRun, color = ClaudeTerracotta) }
                }
            }
        }

        // Terminal Appearance Customization Card
        Text(
            text = L.settings.dialog.terminalCustomization,
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
                    Text(L.settings.dialog.fontSize, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                Text(L.settings.dialog.cursorStyle, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cursorOptions = listOf("BLOCK" to "Block █", "UNDERLINE" to L.settings.dialog.underline, "BAR" to "Bar |")
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
                Text(L.settings.dialog.scrollbackBuffer, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                        100000 to L.settings.dialog.unlimited
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

                // Floating session pill on/off
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(sessionPillEnabled, { enabled -> coroutineScope.launch { terminalPrefs.saveTerminalSessionPillEnabled(enabled) } }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(L.settings.dialog.floatingSessionPill, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.theSessionNameAtTheTop,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = sessionPillEnabled,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = { enabled -> coroutineScope.launch { terminalPrefs.saveTerminalSessionPillEnabled(enabled) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                // Where on the left edge the drawer swipe beats gesture navigation's Back
                Column {
                    Text(L.settings.dialog.drawerSwipeArea, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.withGestureNavigationASwipeFrom,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("TOP" to L.settings.dialog.top, "MIDDLE" to L.settings.dialog.middle, "BOTTOM" to L.settings.dialog.bottom).forEach { (area, label) ->
                            val isSel = drawerSwipeArea.equals(area, ignoreCase = true)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { coroutineScope.launch { terminalPrefs.saveTerminalDrawerSwipeArea(area) } },
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
                }

                // Keyboard input mode: text mode (keyboard toolbar + clipboard) or key presses only (TYPE_NULL)
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(charBasedInput, { enabled -> coroutineScope.launch { terminalPrefs.saveTerminalCharBasedInput(enabled) } }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(L.settings.dialog.characterBasedInput, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.forKeyboardsThatMisbehaveInThe,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = charBasedInput,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = { enabled -> coroutineScope.launch { terminalPrefs.saveTerminalCharBasedInput(enabled) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
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
            Text(L.settings.dialog.launchTerminalNow, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * "Delete all app data": a warning, the typed confirmation, a countdown, then Android wipes AntiGem's data (the app
 * closes). [show] opens it; [onShown] resets the caller's flag.
 */
@Composable
internal fun FactoryResetDialogs(show: Boolean, onShown: () -> Unit) {
    val context = LocalContext.current
    var showResetWarningDialog by remember { mutableStateOf(false) }
    var showResetPasswordDialog by remember { mutableStateOf(false) }
    var resetPasswordInput by remember { mutableStateOf("") }
    var showShutdownCountdownDialog by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableIntStateOf(5) }
    LaunchedEffect(show) {
        if (show) {
            showResetWarningDialog = true
            onShown()
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
                    text = L.settings.dialog.deleteAllFactoryReset,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = L.settings.dialog.warningThisWillPerformAnAndroid,
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
                    Text(L.settings.dialog.yesContinue, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetWarningDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(L.common.cancel)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showResetPasswordDialog) {
        val isMatch = resetPasswordInput.trim() == "iknowwhatiamdoing"  // i18n: keep (compared with what the user types)
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
                    text = L.settings.dialog.confirmFullAppWipe,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = L.settings.dialog.toConfirmAndPermanentlyWipeAll,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "iknowwhatiamdoing", // typed back and compared: never translated  // i18n: keep (compared with what the user types)
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
                            countdownSeconds = 5
                            showShutdownCountdownDialog = true
                        }
                    },
                    enabled = isMatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        disabledContainerColor = Color.Red.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(L.settings.dialog.deleteAllData, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetPasswordDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(L.common.cancel)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showShutdownCountdownDialog) {
        LaunchedEffect(Unit) {
            for (i in 5 downTo 1) {
                countdownSeconds = i
                delay(1000)
            }
            countdownSeconds = 0
            delay(300)
            LocalEnvironmentManager.clearAppDataAndReset(context)
        }

        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp,
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${countdownSeconds}s",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Red,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = L.settings.dialog.factoryResetInProgress,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = L.settings.dialog.antigemIsWipingAllLocalApp(countdownSeconds),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = Color.Red
                    )
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN: BACKUPS & RESTORE
// ==========================================

data class ScriptExecutionInfo(
    val title: String,
    val scriptName: String,
    val args: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupsSubScreen(
    isServerOnline: Boolean,
    isBridgeOnline: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemConnectionState by ai.antigem.data.remote.AgyBridgeService.instance.systemConnectionState.collectAsState()
    val isAnyServerRunning = systemConnectionState !is ai.antigem.data.remote.SystemConnectionState.Offline

    var activeScriptExecution by remember { mutableStateOf<ScriptExecutionInfo?>(null) }
    var showServerRunningAlert by remember { mutableStateOf<String?>(null) }

    var showBackupChatsPasswordDialog by remember { mutableStateOf(false) }
    // Linux environment backup: asks whether SSH keys and sign-ins go in too (off unless the user ticks it)
    var showEnvBackupDialog by remember { mutableStateOf(false) }
    var envBackupIncludeSecrets by remember { mutableStateOf(false) }
    var backupChatsPassword by remember { mutableStateOf("") }
    var showBackupPasswordText by remember { mutableStateOf(false) }

    var showRestoreChatsDialog by remember { mutableStateOf(false) }
    var restoreFilePath by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showRestorePasswordText by remember { mutableStateOf(false) }

    var showProjectsBackupRestoreDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.cacheDir, "restore_payload_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        restoreFilePath = destFile.absolutePath
                    }
                } catch (e: Exception) {
                    Log.e("BackupsSubScreen", "Failed copying selected backup file", e)
                }
            }
        }
    }

    val requestStoragePermission = { onGranted: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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
                onGranted()
            }
        } else {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, L.settings.dialog.storagePermissionNeededToSaveTo, Toast.LENGTH_SHORT).show()
            }
            onGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Server running: backups and restores need it stopped first
        if (isAnyServerRunning) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ai.antigem.theme.AppColors.warning.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, ai.antigem.theme.AppColors.warning.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = ai.antigem.theme.AppColors.warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = L.settings.dialog.serverIsRunning,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.stopItBeforeBackingUpOr,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onStopServer,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(L.settings.dialog.stopServer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        BackupSectionLabel(L.settings.dialog.backup)

        BackupActionCard(
            icon = Icons.Outlined.Inventory2,
            title = L.settings.dialog.linuxEnvironment,
            subtitle = L.settings.dialog.installedPackagesBinariesAndYourHome,
            description = L.settings.dialog.aFullPortableCopyOfThe,
            savesTo = "Download/Antigem/backups/bootrapz_<time>.zip",
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            BackupPrimaryButton(
                text = L.settings.dialog.backUpEnvironment,
                icon = Icons.Outlined.Archive,
                onClick = {
                    if (isAnyServerRunning) {
                        showServerRunningAlert = L.settings.dialog.backupRootfs
                    } else {
                        envBackupIncludeSecrets = false
                        showEnvBackupDialog = true
                    }
                }
            )
        }

        BackupActionCard(
            icon = Icons.Outlined.Chat,
            title = L.settings.dialog.chatsCredentials,
            subtitle = L.settings.dialog.conversationsTranscriptsSkillsAndLogin,
            description = L.settings.dialog.archivesYourGeminiFolderChatHistory,
            savesTo = "Download/Antigem/backups/agy_chats_backup_<time>.zip",
            note = L.settings.dialog.includesYourLoginTokenDontShare,
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            BackupPrimaryButton(
                text = L.settings.dialog.backUpChats,
                icon = Icons.Outlined.CloudUpload,
                onClick = {
                    if (isAnyServerRunning) {
                        showServerRunningAlert = L.settings.dialog.backupChats
                    } else {
                        showBackupChatsPasswordDialog = true
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        BackupSectionLabel(L.settings.dialog.restore)

        BackupActionCard(
            icon = Icons.Outlined.SettingsBackupRestore,
            title = L.settings.dialog.chatsCredentials,
            subtitle = L.settings.dialog.fromAChatsBackupZip,
            description = L.settings.dialog.restoresChatsTheConversationIndexAnd,
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            BackupSecondaryButton(
                text = L.settings.dialog.restoreChats,
                icon = Icons.Outlined.SettingsBackupRestore,
                onClick = {
                    if (isAnyServerRunning) {
                        showServerRunningAlert = L.settings.dialog.restoreChats2
                    } else {
                        showRestoreChatsDialog = true
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        BackupSectionLabel(L.common.projects)

        BackupActionCard(
            icon = Icons.Outlined.Workspaces,
            title = "IDE & AGY projects",
            subtitle = L.settings.dialog.backUpOrRestoreSelectedProjects,
            description = L.settings.dialog.pickProjectsFromTheIdeAnd,
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            BackupSecondaryButton(
                text = L.settings.dialog.manageProjects,
                icon = Icons.Outlined.FolderZip,
                onClick = { showProjectsBackupRestoreDialog = true }
            )
        }
    }

    // 1. Server Running Alert Dialog
    showServerRunningAlert?.let { actionName ->
        AlertDialog(
            onDismissRequest = { showServerRunningAlert = null },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            title = { Text(L.settings.dialog.stopTheServerFirst, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    L.settings.dialog.theAgyIdeBridgeAndHub(actionName),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onStopServer()
                        showServerRunningAlert = null
                        Toast.makeText(context, L.settings.dialog.stoppingServerPleaseReRunActionname(actionName), Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White)
                ) {
                    Text(L.settings.dialog.stopServer, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerRunningAlert = null }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // 2. Backup Chats Password Dialog
    if (showBackupChatsPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showBackupChatsPasswordDialog = false },
            icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            title = { Text(L.settings.dialog.backUpChats, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = L.settings.dialog.optionallyEnterAPasswordToEncrypt,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    BackupNote(L.settings.dialog.dontShareThisFileAnyoneWho)
                    OutlinedTextField(
                        value = backupChatsPassword,
                        onValueChange = { backupChatsPassword = it },
                        label = { Text(L.settings.dialog.encryptionPasswordOptional) },
                        singleLine = true,
                        visualTransformation = if (showBackupPasswordText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showBackupPasswordText = !showBackupPasswordText }) {
                                Icon(
                                    imageVector = if (showBackupPasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showBackupPasswordText) L.components.a11y.hidePassword else L.components.a11y.showPassword,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = backupChatsPassword.trim()
                        showBackupChatsPasswordDialog = false
                        backupChatsPassword = ""
                        requestStoragePermission {
                            activeScriptExecution = ScriptExecutionInfo(
                                title = L.settings.dialog.agyChatsCredentialsBackup,
                                scriptName = "backup_chats",
                                args = if (pass.isNotEmpty()) listOf("", pass) else emptyList()
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White)
                ) {
                    Text(L.settings.dialog.startBackup, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupChatsPasswordDialog = false
                    backupChatsPassword = ""
                }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    if (showEnvBackupDialog) {
        AlertDialog(
            onDismissRequest = { showEnvBackupDialog = false },
            icon = { Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            title = { Text(L.settings.dialog.backUpEnvironment, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = L.settings.dialog.savesTheLinuxSystemAndYour,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { envBackupIncludeSecrets = !envBackupIncludeSecrets }
                            .semantics { role = Role.Checkbox; toggleableState = ToggleableState(envBackupIncludeSecrets) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = envBackupIncludeSecrets, onCheckedChange = { envBackupIncludeSecrets = it }, modifier = Modifier.mergedIntoRow())
                        Column(modifier = Modifier.weight(1f)) {
                            Text(L.settings.dialog.includeSshKeysAndSignIns, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                L.settings.dialog.sshKeysAgentAndGithubLogins,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                    if (envBackupIncludeSecrets) {
                        BackupNote(L.settings.dialog.neverShareThisBackupWithAnyone)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val includeSecrets = envBackupIncludeSecrets
                        showEnvBackupDialog = false
                        requestStoragePermission {
                            activeScriptExecution = ScriptExecutionInfo(
                                title = L.settings.dialog.rootfsSystemPackager,
                                scriptName = "backup_bootstrap",
                                args = if (includeSecrets) listOf("--include-secrets") else emptyList()
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White)
                ) {
                    Text(L.settings.dialog.startBackup, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnvBackupDialog = false }) { Text(L.common.cancel) }
            }
        )
    }

    // 3. Restore Chats Dialog
    if (showRestoreChatsDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreChatsDialog = false },
            icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            title = { Text(L.settings.dialog.restoreChats, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = L.settings.dialog.selectYourBackupZipArchiveTo,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    OutlinedTextField(
                        value = restoreFilePath,
                        onValueChange = { restoreFilePath = it },
                        label = { Text(L.settings.dialog.backupArchivePath) },
                        placeholder = { Text("/sdcard/Download/agy_chats_backup_....zip") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = L.settings.dialog.chooseFile, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(L.settings.dialog.archivePasswordIfEncrypted) },
                        singleLine = true,
                        visualTransformation = if (showRestorePasswordText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showRestorePasswordText = !showRestorePasswordText }) {
                                Icon(
                                    imageVector = if (showRestorePasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showRestorePasswordText) L.components.a11y.hidePassword else L.components.a11y.showPassword,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = restoreFilePath.trim()
                        val pass = restorePassword.trim()
                        showRestoreChatsDialog = false
                        restoreFilePath = ""
                        restorePassword = ""
                        activeScriptExecution = ScriptExecutionInfo(
                            title = L.settings.dialog.restoreAgyChatsCredentials,
                            scriptName = "restore_chats",
                            args = if (pass.isNotEmpty()) listOf(path, pass) else listOf(path)
                        )
                    },
                    enabled = restoreFilePath.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White)
                ) {
                    Text(L.settings.dialog.startRestore, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreChatsDialog = false
                    restoreFilePath = ""
                    restorePassword = ""
                }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // 4. Live Script Execution Dialog
    activeScriptExecution?.let { executionInfo ->
        ScriptExecutionDialog(
            info = executionInfo,
            onDismiss = { activeScriptExecution = null }
        )
    }

    // 5. Projects Backup & Restore Dialog
    if (showProjectsBackupRestoreDialog) {
        ProjectsBackupRestoreDialog(
            onDismiss = { showProjectsBackupRestoreDialog = false },
            onRequestStoragePermission = requestStoragePermission,
            onExecuteScript = { executionInfo ->
                activeScriptExecution = executionInfo
            }
        )
    }
}

@Composable
private fun BackupSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Small neutral note row (lock icon + text) used for the "keep this file private" hint. */
@Composable
private fun BackupNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = ai.antigem.theme.AppColors.warning,
            modifier = Modifier.size(15.dp).padding(top = 1.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/** One backup / restore action: neutral icon tile, title, short description, optional save path and note, then its button. */
@Composable
private fun BackupActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    description: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    savesTo: String? = null,
    note: String? = null,
    action: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = cardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )

            if (savesTo != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = savesTo,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (note != null) {
                Spacer(modifier = Modifier.height(10.dp))
                BackupNote(note)
            }

            Spacer(modifier = Modifier.height(14.dp))
            action()
        }
    }
}

@Composable
private fun BackupPrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White),
        elevation = null,
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun BackupSecondaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

data class ProjectBackupManifestItem(
    val name: String,
    val originalPath: String,
    val backupSubpath: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProjectsBackupRestoreDialog(
    onDismiss: () -> Unit,
    onRequestStoragePermission: (() -> Unit) -> Unit,
    onExecuteScript: (ScriptExecutionInfo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Backup, 1 = Restore
    // Projects list from IdeDaemonManager
    val allProjects by IdeDaemonManager.projects.collectAsState()
    val homeDir = remember { LocalEnvironmentManager.getHomeDir(context).absolutePath.trimEnd('/') }

    fun normalizeProjectPath(p: String, home: String): String {
        var clean = p.trim().removePrefix("file://").trim()
        if (clean.startsWith("~/")) {
            clean = home + clean.removePrefix("~")
        } else if (clean == "~") {
            clean = home
        }
        if (clean.startsWith("/data/user/0/")) {
            clean = "/data/data/" + clean.removePrefix("/data/user/0/")
        }
        clean = clean.replace(Regex("/+"), "/")
        return clean.trimEnd('/')
    }

    // Filter out root $HOME, root filesystem paths, and duplicates
    val validProjects = remember(allProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        allProjects.filter { proj ->
            val p = normalizeProjectPath(proj.path, normHome)
            p.isNotBlank() && p != normHome && p != "/" && p != "/data/data/${BuildConfig.APPLICATION_ID}/files" && p != "/data/data/${BuildConfig.APPLICATION_ID}"
        }.distinctBy { normalizeProjectPath(it.path, normHome) }
    }

    LaunchedEffect(Unit) {
        IdeDaemonManager.loadProjects()
    }

    // --- Backup Tab State ---
    val selectedProjectPaths = remember { mutableStateListOf<String>() }
    var hasAutoSelected by remember { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf("git") } // "git" or "full"
    var searchQuery by remember { mutableStateOf("") }

    // Auto-select valid projects by default only once on initial load
    LaunchedEffect(validProjects) {
        if (!hasAutoSelected && validProjects.isNotEmpty()) {
            selectedProjectPaths.clear()
            selectedProjectPaths.addAll(validProjects.map { it.path })
            hasAutoSelected = true
        }
    }

    val filteredProjects = remember(validProjects, searchQuery) {
        if (searchQuery.isBlank()) validProjects
        else validProjects.filter { it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true) }
    }

    val selectedCount = remember(selectedProjectPaths.toList(), validProjects) {
        validProjects.count { selectedProjectPaths.contains(it.path) }
    }

    // Build nested tree list with path normalization
    val flattenedTree = remember(filteredProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        val normMap = filteredProjects.associateWith { normalizeProjectPath(it.path, normHome) }
        val sorted = filteredProjects.sortedBy { normMap[it]?.length ?: 0 }

        fun findParent(proj: ProjectItem): ProjectItem? {
            val childNorm = normMap[proj] ?: return null
            return sorted.filter { parent ->
                val parentNorm = normMap[parent] ?: ""
                parentNorm.isNotBlank() && parentNorm != childNorm && childNorm.startsWith("$parentNorm/")
            }.maxByOrNull { normMap[it]?.length ?: 0 }
        }

        val parentMap = mutableMapOf<String, ProjectItem?>()
        filteredProjects.forEach { proj ->
            parentMap[proj.path] = findParent(proj)
        }

        val result = mutableListOf<Triple<ProjectItem, Int, ProjectItem?>>() // item, level, parent
        fun addNodeAndChildren(proj: ProjectItem, level: Int) {
            result.add(Triple(proj, level, parentMap[proj.path]))
            val children = filteredProjects.filter { parentMap[it.path]?.path == proj.path }
            children.forEach { child ->
                addNodeAndChildren(child, level + 1)
            }
        }

        val roots = filteredProjects.filter { parentMap[it.path] == null }
        roots.forEach { root ->
            addNodeAndChildren(root, 0)
        }
        result
    }

    val effectiveBackupPaths = remember(selectedProjectPaths.toList(), validProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        val normMap = validProjects.associateWith { normalizeProjectPath(it.path, normHome) }
        val selected = validProjects.filter { selectedProjectPaths.contains(it.path) }
        selected.filter { item ->
            val itemNorm = normMap[item] ?: ""
            selected.none { other ->
                val otherNorm = normMap[other] ?: ""
                other.path != item.path && otherNorm.isNotBlank() && itemNorm.startsWith("$otherNorm/")
            }
        }.map { it.path }
    }

    // --- Restore Tab State ---
    var restoreZipPath by remember { mutableStateOf("") }
    var conflictStrategy by remember { mutableStateOf("bak") } // "bak" or "overwrite"
    var customRestoreDir by remember { mutableStateOf("") }
    var useCustomRestoreDir by remember { mutableStateOf(false) }
    var manifestProjects by remember { mutableStateOf<List<ProjectBackupManifestItem>>(emptyList()) }
    var manifestBackupMode by remember { mutableStateOf<String?>(null) }
    var isInspectingArchive by remember { mutableStateOf(false) }
    var archiveError by remember { mutableStateOf<String?>(null) }

    val inspectArchive: (String) -> Unit = { path ->
        if (path.isBlank()) {
            manifestProjects = emptyList()
            manifestBackupMode = null
            archiveError = null
        } else {
            scope.launch(Dispatchers.IO) {
                isInspectingArchive = true
                archiveError = null
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        withContext(Dispatchers.Main) {
                            archiveError = L.settings.dialog.archiveFileNotFoundAtPath(path)
                            manifestProjects = emptyList()
                            isInspectingArchive = false
                        }
                    } else {
                        val zip = ZipFile(file)
                        val entry = zip.getEntry("antigem_project_manifest.json")
                            ?: zip.getEntry(".antigem_project_manifest.json")
                        if (entry != null) {
                            val stream = zip.getInputStream(entry)
                            val content = stream.bufferedReader().use { it.readText() }
                            val json = JSONObject(content)
                            val projectsArr = json.optJSONArray("projects") ?: JSONArray()
                            val list = mutableListOf<ProjectBackupManifestItem>()
                            for (i in 0 until projectsArr.length()) {
                                val obj = projectsArr.getJSONObject(i)
                                list.add(
                                    ProjectBackupManifestItem(
                                        name = obj.optString("name", "Project"),
                                        originalPath = obj.optString("original_path", ""),
                                        backupSubpath = obj.optString("backup_subpath", "")
                                    )
                                )
                            }
                            val mode = json.optString("backup_mode", "git")
                            zip.close()
                            withContext(Dispatchers.Main) {
                                manifestProjects = list
                                manifestBackupMode = mode
                                isInspectingArchive = false
                            }
                        } else {
                            val entries = zip.entries().asSequence().map { it.name }.toList()
                            zip.close()
                            val roots = entries.map { it.substringBefore('/') }.filter { it.isNotBlank() && !it.startsWith("__") }.distinct()
                            val list = roots.map { ProjectBackupManifestItem(name = it, originalPath = "", backupSubpath = it) }
                            withContext(Dispatchers.Main) {
                                manifestProjects = list
                                manifestBackupMode = "raw"
                                isInspectingArchive = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        archiveError = L.settings.dialog.errorReadingArchiveMessage(e.message)
                        manifestProjects = emptyList()
                        isInspectingArchive = false
                    }
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.cacheDir, "project_restore_payload_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        restoreZipPath = destFile.absolutePath
                        inspectArchive(destFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e("ProjectsBackup", "Failed copying selected backup file", e)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = ClaudeTerracotta.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Workspaces,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = L.settings.dialog.projectsBackupRestore,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = L.settings.dialog.backupIdeAgyWorkspacesOrRestore,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = L.common.close, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Switcher
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = ClaudeTerracotta,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(L.settings.dialog.backupProjects, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(L.settings.dialog.restoreProjects, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // ==========================================
                    // TAB 0: BACKUP PROJECTS
                    // ==========================================
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Backup Mode Selection Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = L.settings.dialog.backupFormatPackagingMode,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Git-Aware (Clean)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (backupMode == "git") ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (backupMode == "git") ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .selectable(selected = backupMode == "git", role = Role.RadioButton) { backupMode = "git" }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (backupMode == "git") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (backupMode == "git") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Column {
                                                Text(L.settings.dialog.gitAwareClean, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (backupMode == "git") ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                                                Text(L.settings.dialog.excludesNodeModulesBuildCaches, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    // Full Raw Archive
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (backupMode == "full") ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (backupMode == "full") ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .selectable(selected = backupMode == "full", role = Role.RadioButton) { backupMode = "full" }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (backupMode == "full") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (backupMode == "full") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Column {
                                                Text(L.settings.dialog.fullRawArchive, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (backupMode == "full") ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                                                Text(L.settings.dialog.allFilesSymlinksPermissionsAsIs, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Select All / Deselect Bar + Count
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = L.settings.dialog.selectProjectsSelectedcount(selectedCount, validProjects.size),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        selectedProjectPaths.clear()
                                        selectedProjectPaths.addAll(validProjects.map { it.path })
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(L.settings.dialog.selectAll, fontSize = 11.5.sp, color = ClaudeTerracotta)
                                }
                                TextButton(
                                    onClick = { selectedProjectPaths.clear() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(L.settings.dialog.deselectAll, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Project Items Tree List
                        if (validProjects.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = L.settings.dialog.noOpenProjectsOrWorkspacesDetected,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val normHome = remember(homeDir) { normalizeProjectPath(homeDir, homeDir) }
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(flattenedTree, key = { it.first.path }) { (proj, level, parentProj) ->
                                    val isParentSelected = parentProj != null && selectedProjectPaths.contains(parentProj.path)
                                    val isDirectlySelected = selectedProjectPaths.contains(proj.path)
                                    val isCoveredByParent = isParentSelected
                                    val isSelected = isDirectlySelected || isCoveredByParent
                                    val projNorm = normalizeProjectPath(proj.path, normHome)
                                    val childProjectsCount = validProjects.count {
                                        val otherNorm = normalizeProjectPath(it.path, normHome)
                                        it.path != proj.path && otherNorm.startsWith("$projNorm/")
                                    }

                                    val toggleNode: () -> Unit = {
                                        if (isCoveredByParent && parentProj != null) {
                                            selectedProjectPaths.remove(parentProj.path)
                                            val parentNorm = normalizeProjectPath(parentProj.path, normHome)
                                            val siblings = validProjects.filter {
                                                val otherNorm = normalizeProjectPath(it.path, normHome)
                                                it.path != parentProj.path && otherNorm.startsWith("$parentNorm/")
                                            }
                                            siblings.forEach { sib ->
                                                if (sib.path != proj.path && !selectedProjectPaths.contains(sib.path)) {
                                                    selectedProjectPaths.add(sib.path)
                                                }
                                            }
                                            selectedProjectPaths.remove(proj.path)
                                        } else {
                                            if (isDirectlySelected) {
                                                selectedProjectPaths.remove(proj.path)
                                                val descendants = validProjects.filter {
                                                    val otherNorm = normalizeProjectPath(it.path, normHome)
                                                    otherNorm.startsWith("$projNorm/")
                                                }
                                                descendants.forEach { selectedProjectPaths.remove(it.path) }
                                            } else {
                                                selectedProjectPaths.add(proj.path)
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = (level * 22).dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (level > 0) {
                                            Text(
                                                text = "└──",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = ClaudeTerracotta.copy(alpha = 0.8f),
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) ClaudeTerracotta.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { toggleNode() }
                                                .semantics { role = Role.Checkbox; toggleableState = ToggleableState(isSelected) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { toggleNode() },
                                                    colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                                    modifier = Modifier.size(20.dp).mergedIntoRow()
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    FlowRow(
                                                        itemVerticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            text = proj.name,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (childProjectsCount > 0) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = ClaudeTerracotta.copy(alpha = 0.15f)
                                                            ) {
                                                                Text(
                                                                    text = "$childProjectsCount ${if (childProjectsCount == 1) "sub-project" else "sub-projects"}",
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = ClaudeTerracotta,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                        if (isCoveredByParent && parentProj != null) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = ai.antigem.theme.AppColors.success.copy(alpha = 0.15f)
                                                            ) {
                                                                Text(
                                                                    text = L.settings.dialog.includedWithName(parentProj.name),
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = ai.antigem.theme.AppColors.success,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        } else if (parentProj != null) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = ClaudeTerracotta.copy(alpha = 0.12f)
                                                            ) {
                                                                Text(
                                                                    text = L.settings.dialog.insideName(parentProj.name),
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = ClaudeTerracotta,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = proj.path,
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Backup Action Button
                        Button(
                            onClick = {
                                if (effectiveBackupPaths.isEmpty()) {
                                    Toast.makeText(context, L.settings.dialog.pleaseSelectAtLeastOneProject, Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onRequestStoragePermission {
                                    val targetsFile = File(context.cacheDir, "backup_targets_${System.currentTimeMillis()}.txt")
                                    targetsFile.writeText(effectiveBackupPaths.joinToString("\n"))
                                    onDismiss()
                                    onExecuteScript(
                                        ScriptExecutionInfo(
                                            title = L.settings.dialog.projectsBackupPackager,
                                            scriptName = "backup_projects",
                                            args = listOf("", backupMode, "--paths-file", targetsFile.absolutePath)
                                        )
                                    )
                                }
                            },
                            enabled = selectedCount > 0,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (effectiveBackupPaths.size == selectedCount) L.settings.dialog.backupSelectedcountSelectedProjects(selectedCount)
                                       else L.settings.dialog.backupSelectedcountSelectedProjects2(selectedCount, effectiveBackupPaths.size),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    // ==========================================
                    // TAB 1: RESTORE PROJECTS
                    // ==========================================
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = L.settings.dialog.selectAProjectsBackupArchiveZip,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )

                        // File Picker Input
                        OutlinedTextField(
                            value = restoreZipPath,
                            onValueChange = {
                                restoreZipPath = it
                                inspectArchive(it.trim())
                            },
                            label = { Text(L.settings.dialog.projectsArchiveFilePath) },
                            placeholder = { Text("/sdcard/Download/Antigem/backups/projects_backup_....zip") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = L.settings.dialog.chooseFile, tint = ClaudeTerracotta)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Archive Inspection Summary
                        if (isInspectingArchive) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                                Text(L.settings.dialog.inspectingArchiveContentsManifest, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        archiveError?.let { err ->
                            Text(text = err, color = ai.antigem.theme.AppColors.danger, fontSize = 11.5.sp)
                        }

                        if (manifestProjects.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Projects in Archive (${manifestProjects.size}):",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        manifestBackupMode?.let { mode ->
                                            Text(L.settings.dialog.modeMode(mode), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    manifestProjects.forEach { item ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(16.dp))
                                            Column {
                                                Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                if (item.originalPath.isNotBlank()) {
                                                    Text(
                                                        L.settings.dialog.targetOriginalpath(item.originalPath),
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Conflict Strategy Option
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = L.settings.dialog.ifTargetProjectFolderAlreadyExists,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // Option A: Move to .bak
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .selectable(selected = conflictStrategy == "bak", role = Role.RadioButton) { conflictStrategy = "bak" }
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (conflictStrategy == "bak") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (conflictStrategy == "bak") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(L.settings.dialog.moveExistingToBakTimestampSafe, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text(L.settings.dialog.preservesExistingCodeInABackup, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                // Option B: Overwrite
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .selectable(selected = conflictStrategy == "overwrite", role = Role.RadioButton) { conflictStrategy = "overwrite" }
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (conflictStrategy == "overwrite") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (conflictStrategy == "overwrite") ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(L.settings.dialog.overwriteFilesInPlace, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text(L.settings.dialog.overwritesMatchingFilesDirectlyInside, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        // Optional: Custom Root Target Directory
                        Row(
                            modifier = Modifier.toggleRowSemantics(useCustomRestoreDir, { useCustomRestoreDir = it }, role = Role.Checkbox),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = useCustomRestoreDir,
                                onCheckedChange = { useCustomRestoreDir = it },
                                colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                modifier = Modifier.size(20.dp).mergedIntoRow()
                            )
                            Text(L.settings.dialog.restoreToCustomParentDirectory, fontSize = 12.sp)
                        }

                        if (useCustomRestoreDir) {
                            OutlinedTextField(
                                value = customRestoreDir,
                                onValueChange = { customRestoreDir = it },
                                label = { Text(L.settings.dialog.customRestoreDirectory) },
                                placeholder = { Text("/data/data/${BuildConfig.APPLICATION_ID}/files/home/restored_projects") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Restore Action Button
                        Button(
                            onClick = {
                                val zipPath = restoreZipPath.trim()
                                if (zipPath.isBlank()) {
                                    Toast.makeText(context, L.settings.dialog.pleaseEnterOrPickABackup, Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onDismiss()
                                onExecuteScript(
                                    ScriptExecutionInfo(
                                        title = L.settings.dialog.projectsArchiveRestorer,
                                        scriptName = "restore_projects",
                                        args = listOf(zipPath, conflictStrategy, if (useCustomRestoreDir) customRestoreDir.trim() else "")
                                    )
                                )
                                scope.launch(Dispatchers.IO) {
                                    delay(1500)
                                    IdeDaemonManager.loadProjects()
                                }
                            },
                            enabled = restoreZipPath.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(L.settings.dialog.startRestoreRestoreProjects, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptExecutionDialog(
    info: ScriptExecutionInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(true) }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    var executionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val activity = (context as? android.app.Activity) ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
    DisposableEffect(isRunning) {
        val window = activity?.window
        if (isRunning) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(info) {
        executionJob?.cancel()
        executionJob = scope.launch {
            isRunning = true
            logs.clear()
            logs.add("⚡ Initializing ${info.scriptName}...")
            val result = LocalEnvironmentManager.executeScriptLive(
                context = context,
                scriptName = info.scriptName,
                args = info.args
            ) { line ->
                val trimmed = line.trim()
                val isProgressBar = (trimmed.startsWith("[") || trimmed.contains("%")) && trimmed.contains("/")
                val prevIsProgressBar = logs.isNotEmpty() && logs.last().let { (it.trim().startsWith("[") || it.contains("%")) && it.contains("/") }
                if (isProgressBar && prevIsProgressBar) {
                    logs[logs.size - 1] = line
                } else {
                    logs.add(line)
                }
            }
            exitCode = result.exitCode
            durationMs = result.durationMs
            isRunning = false
        }
    }

    LaunchedEffect(logs.size, if (logs.isNotEmpty()) logs.last() else "") {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isRunning,
            dismissOnClickOutside = !isRunning,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.80f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isRunning) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = GeminiBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        } else if (exitCode == 0) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(22.dp))
                        }

                        Column {
                            Text(
                                text = info.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isRunning) L.settings.dialog.executingInRootfsEnvironment else if (exitCode == 0) L.settings.dialog.completedInValueSExit0((durationMs / 1000.0)) else L.settings.dialog.finishedWithErrorExitExitcode(exitCode),
                                fontSize = 11.5.sp,
                                color = if (isRunning) GeminiBlue else if (exitCode == 0) QuotaGreen else Color.Red
                            )
                        }
                    }

                    if (!isRunning) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = L.common.close, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Terminal output container
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ai.antigem.theme.AppColors.terminalBg,
                    border = BorderStroke(1.dp, ai.antigem.theme.AppColors.editorBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        items(logs) { line ->
                            val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                            val lineColor = when {
                                cleanLine.startsWith("❌") || cleanLine.contains("Error", ignoreCase = true) -> ai.antigem.theme.AppColors.danger
                                cleanLine.startsWith("✅") || cleanLine.startsWith("🎉") || cleanLine.contains("Success", ignoreCase = true) -> ai.antigem.theme.AppColors.success
                                cleanLine.startsWith("⚠️") || cleanLine.contains("Note:", ignoreCase = true) || cleanLine.contains("Warning:", ignoreCase = true) -> ai.antigem.theme.AppColors.warning
                                cleanLine.startsWith("=") || cleanLine.startsWith("[") -> ai.antigem.theme.AppColors.info
                                else -> ai.antigem.theme.AppColors.editorText
                            }
                            Text(
                                text = cleanLine,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = lineColor,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isRunning) {
                        Button(
                            onClick = {
                                executionJob?.cancel()
                                isRunning = false
                                exitCode = 130
                                logs.add(L.settings.dialog.executionCancelledByUser)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.danger)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(L.settings.dialog.stopExecution, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (exitCode == 0) QuotaGreen else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(L.common.done, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandExecutionDialog(
    title: String,
    command: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(true) }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    var executionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val activity = (context as? android.app.Activity) ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
    DisposableEffect(isRunning) {
        val window = activity?.window
        if (isRunning) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(command) {
        executionJob?.cancel()
        executionJob = scope.launch {
            isRunning = true
            logs.clear()
            logs.add("⚡ Running: $command...")
            val result = ai.antigem.data.local.LocalEnvironmentManager.executeCommandLive(
                context = context,
                command = command
            ) { line ->
                val trimmed = line.trim()
                val isProgressBar = (trimmed.startsWith("[") || trimmed.contains("%")) && trimmed.contains("/")
                val prevIsProgressBar = logs.isNotEmpty() && logs.last().let { (it.trim().startsWith("[") || it.contains("%")) && it.contains("/") }
                if (isProgressBar && prevIsProgressBar) {
                    logs[logs.size - 1] = line
                } else {
                    logs.add(line)
                }
            }
            exitCode = result.exitCode
            durationMs = result.durationMs
            isRunning = false
        }
    }

    LaunchedEffect(logs.size, if (logs.isNotEmpty()) logs.last() else "") {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isRunning,
            dismissOnClickOutside = !isRunning,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.80f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isRunning) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = GeminiBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        } else if (exitCode == 0) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(22.dp))
                        }

                        Column {
                            Text(
                                text = title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isRunning) L.settings.dialog.executingInTerminalEnvironment else if (exitCode == 0) L.settings.dialog.completedSuccessfullyInValueS((durationMs / 1000.0)) else L.settings.dialog.finishedWithExitCodeExitcode(exitCode),
                                fontSize = 11.5.sp,
                                color = if (isRunning) GeminiBlue else if (exitCode == 0) QuotaGreen else Color.Red
                            )
                        }
                    }

                    if (!isRunning) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = L.common.close, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Terminal output container
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ai.antigem.theme.AppColors.terminalBg,
                    border = BorderStroke(1.dp, ai.antigem.theme.AppColors.editorBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        items(logs) { line ->
                            val cleanLine = line.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
                            val lineColor = when {
                                cleanLine.contains("ERROR", ignoreCase = true) || cleanLine.contains("FAILED", ignoreCase = true) -> ai.antigem.theme.AppColors.danger
                                cleanLine.contains("SUCCESS", ignoreCase = true) || cleanLine.contains("✓") || cleanLine.contains("Setting up termux-api") -> ai.antigem.theme.AppColors.success
                                cleanLine.startsWith("⚡") -> ai.antigem.theme.AppColors.accentTeal
                                else -> ai.antigem.theme.AppColors.editorText
                            }
                            Text(
                                text = cleanLine,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = lineColor,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isRunning) {
                        Button(
                            onClick = {
                                executionJob?.cancel()
                                isRunning = false
                                exitCode = 130
                                logs.add(L.settings.dialog.executionCancelledByUser)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.danger)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(L.settings.dialog.stopExecution, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (exitCode == 0) QuotaGreen else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(L.common.done, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN: DATA LOSS PROTECTION
// ==========================================
@Composable
private fun DataProtectionSubScreen(
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAdminActive by remember { mutableStateOf(AntiGemDeviceAdminReceiver.isAdminActive(context)) }
    var isShieldEnabled by remember { mutableStateOf(ManageSpaceActivity.isShieldEnabled(context)) }
    var showDeactivateAdminDialog by remember { mutableStateOf(false) }

    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
                isShieldEnabled = ManageSpaceActivity.isShieldEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Banner info
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = ai.antigem.theme.AppColors.success.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, ai.antigem.theme.AppColors.success.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = ai.antigem.theme.AppColors.success,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = L.settings.dialog.dataLossPrevention,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = L.settings.dialog.protectYourLocalLinuxEnvironmentCli,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Feature 1: Accidental Uninstall Protection (Device Administrator)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isAdminActive) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isAdminActive) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = null,
                                tint = if (isAdminActive) QuotaGreen else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = L.settings.dialog.accidentalUninstallProtection,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAdminActive) L.settings.dialog.statusProtectedDeviceAdminActive else L.settings.dialog.statusInactiveOptional,
                            fontSize = 12.sp,
                            color = if (isAdminActive) QuotaGreen else Color.Gray,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = L.settings.dialog.whyEnableThisAntigemHostsA,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                if (isAdminActive) {
                    OutlinedButton(
                        onClick = { showDeactivateAdminDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(L.settings.dialog.deactivateUninstallProtection, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            try {
                                val intent = AntiGemDeviceAdminReceiver.createActivationIntent(context)
                                adminLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.w("SettingsDialog", "Direct admin prompt failed, opening Device Admin settings", e)
                                try {
                                    context.startActivity(AntiGemDeviceAdminReceiver.createDeviceAdminSettingsIntent())
                                } catch (e2: Exception) {
                                    Toast.makeText(context, L.settings.dialog.couldNotOpenDeviceAdminMessage(e2.message), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(L.settings.dialog.activateUninstallProtection, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(AntiGemDeviceAdminReceiver.createDeviceAdminSettingsIntent())
                            } catch (e: Exception) {
                                Toast.makeText(context, L.settings.dialog.couldNotOpenSettingsMessage(e.message), Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(L.settings.dialog.openSystemDeviceAdminAppsList, fontSize = 12.5.sp)
                    }

                    if (Build.VERSION.SDK_INT >= 33) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = L.settings.dialog.sideloadedAppTipAndroid13If,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        try {
                                            val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(appInfoIntent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(L.settings.dialog.openAppInfo, fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Feature 2: Clear Data Shield (ManageSpaceActivity)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val onToggleShield: (Boolean) -> Unit = { enabled ->
                    ManageSpaceActivity.setShieldEnabled(context, enabled)
                    isShieldEnabled = ManageSpaceActivity.isShieldEnabled(context)
                }
                Row(
                    modifier = Modifier.toggleRowSemantics(isShieldEnabled, onToggleShield),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isShieldEnabled) GeminiBlue.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.CleaningServices,
                                contentDescription = null,
                                tint = if (isShieldEnabled) GeminiBlue else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = L.settings.dialog.appInfoClearDataShield,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = L.settings.dialog.replacesClearDataWithTheSpace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isShieldEnabled,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = onToggleShield
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = L.settings.dialog.whenEnabledDefaultTappingClearData,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(context, ManageSpaceActivity::class.java))
                        } catch (e: Exception) {
                            Toast.makeText(context, L.settings.dialog.couldNotOpenSpaceManagerMessage(e.message), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(L.settings.dialog.openSpaceManagerPreview, fontSize = 13.sp)
                }
            }
        }
    }

    if (showDeactivateAdminDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateAdminDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(L.settings.dialog.disableUninstallProtection) },
            text = {
                Text(
                    L.settings.dialog.thisWillRemoveAntigemsDeviceAdmin,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeactivateAdminDialog = false
                        AntiGemDeviceAdminReceiver.deactivateAdmin(context)
                        isAdminActive = false
                        scope.launch {
                            delay(300)
                            isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(L.settings.dialog.deactivate)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateAdminDialog = false }) {
                    Text(L.common.cancel)
                }
            }
        )
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
    globalSecuritySettings: ai.antigem.data.remote.AgyHubClient.GlobalUserSettings?,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean,
    projectsList: List<ai.antigem.data.remote.AgyHubClient.ProjectItem>,
    isProjectsLoading: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetGlobalArtifactReviewMode: (String) -> Unit,
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit,
    onSetGlobalCustomTerminalPolicy: (String) -> Unit,
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit,
    onSetGlobalTerminalSandbox: (Boolean) -> Unit,
    onSetProjectInheritGlobal: (ai.antigem.data.remote.AgyHubClient.ProjectItem) -> Unit,
    onSetProjectPreset: (ai.antigem.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit,
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
                        text = L.settings.dialog.globalSecurityPolicies,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.liveDaemonSettingsAppliedAcrossAll,
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
                            contentDescription = L.common.refresh,
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
                            text = L.settings.dialog.connectingToAgyHubLoadingPermissions,
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
                            text = L.settings.dialog.unableToLoadPermissionsFromAgy,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = L.settings.dialog.couldNotRetrieveDaemonPermissionsAnd(globalSettingsError ?: L.settings.dialog.rpcConnectionFailed),
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
                            Text(L.settings.dialog.retryConnection, fontWeight = FontWeight.Bold)
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
            .bouncyVerticalScroll(rememberScrollState())
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
                    text = L.settings.dialog.globalSecurityPolicies,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = L.settings.dialog.liveDaemonSettingsAppliedAcrossAll,
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
                        contentDescription = L.common.refresh,
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
                text = L.settings.dialog.artifactReviewPolicy,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = L.settings.dialog.controlsWhetherTheAssistantAsksYou,
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
                            text = L.settings.dialog.autoTurbo,
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
                        text = L.settings.dialog.editsAndCreatesDocumentsImmediately,
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
                            text = L.settings.dialog.alwaysAsk,
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
                        text = L.settings.dialog.suspendsAndRequestsYourApprovalBefore,
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
                text = L.settings.dialog.securityPresets,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = L.settings.dialog.controlsTerminalCommandExecutionAndFile,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val presets = listOf(
            Triple(
                "DEFAULT",
                L.settings.dialog.defaultMode,
                L.settings.dialog.terminalCommandsRequireConfirmationFiles
            ) to (L.common.recommended to ai.antigem.theme.AppColors.accentTeal),
            Triple(
                "FULL_MACHINE",
                L.settings.dialog.fullMachineMode,
                L.settings.dialog.terminalCommandsRequireConfirmationFull
            ) to (L.settings.dialog.openFiles to ai.antigem.theme.AppColors.warning),
            Triple(
                "TURBO",
                L.settings.dialog.turboMode,
                L.settings.dialog.fullAutoRunTerminalCommandsExecute
            ) to (L.settings.dialog.fullAuto to QuotaGreen),
            Triple(
                "CUSTOM",
                L.settings.dialog.customGranularMode,
                L.settings.dialog.configureTerminalCommandExecutionAnd
            ) to (L.settings.dialog.custom to ai.antigem.theme.AppColors.accentAlt)
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
                            text = L.settings.dialog.terminalCommandExecution,
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
                                label = { Text(L.settings.dialog.askEveryTime, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isTermEager,
                                onClick = { onSetGlobalCustomTerminalPolicy("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER") },
                                label = { Text(L.settings.dialog.autoRunEager, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Granular: Files Outside Workspace
                        Text(
                            text = L.settings.dialog.filesOutsideWorkspace,
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
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(activeSandbox, { onSetGlobalTerminalSandbox(it) }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = L.settings.dialog.terminalSandboxIsolation,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.isolatesCommandsInASandboxWithout,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = activeSandbox,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = { onSetGlobalTerminalSandbox(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(requireApprovalForFileEdits, onSetRequireApprovalForFileEdits),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = L.settings.dialog.reviewFileEditsWrites,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = L.settings.dialog.promptForConfirmationBeforeAssistant,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = requireApprovalForFileEdits,
                        modifier = Modifier.mergedIntoRow(),
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
                        text = L.settings.dialog.granularPermissionGrants,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = L.settings.dialog.explicitAllowDenyAndAskRules,
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
                    Text(L.settings.dialog.addRule, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        text = if (allRules.isEmpty()) L.settings.dialog.noCustomPermissionRulesConfigured else L.settings.dialog.noRulesMatchTheSelectedFilter,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = L.settings.dialog.toolsAndFileAccessesWillUse,
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
                                        contentDescription = L.settings.dialog.deleteRule,
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
                                    Triple("ASK", "❓ Ask", ai.antigem.theme.AppColors.warning)
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
                    text = L.settings.dialog.projectOverridesInheritance,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
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
                text = L.settings.dialog.projectsCanInheritTheGlobalPolicy,
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
                    text = L.settings.dialog.noWorkspaceProjectsRegisteredYetOn,
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
                                    color = if (project.isInheritingGlobal) QuotaGreen.copy(alpha = 0.15f) else ai.antigem.theme.AppColors.warning.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (project.isInheritingGlobal) L.settings.dialog.inheritsGlobal else L.settings.dialog.customOverride,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (project.isInheritingGlobal) QuotaGreen else ai.antigem.theme.AppColors.warning,
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
                                        Text(L.settings.dialog.setToInheritGlobalSettings, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Text(
                                    text = L.settings.dialog.setProjectOverride,
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
                text = L.settings.dialog.defaultApprovalScope,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = L.settings.dialog.whenYouClickApproveOnA,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val scopes = listOf(
                Triple("PERMISSION_SCOPE_ONCE", L.settings.dialog.runOnce, L.settings.dialog.singleCall),
                Triple("PERMISSION_SCOPE_CONVERSATION", L.settings.dialog.thisChat, L.settings.dialog.currentSession),
                Triple("PERMISSION_SCOPE_WORKSPACE", L.common.workspace, L.common.always)
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
                    text = L.settings.dialog.liveSyncedWithAgyHubVia,
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
            "command" -> L.common.command
            "mcp" -> L.settings.dialog.mcpTool
            "read_file" -> L.settings.dialog.readFile
            "write_file" -> L.settings.dialog.writeFile
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
        Triple("command", "⌨️ Command", L.settings.dialog.terminalBashCommandPattern),
        Triple("mcp", L.settings.dialog.mcpTool2, L.settings.dialog.mcpServerOrToolPattern),
        Triple("read_file", L.settings.dialog.readFile2, L.settings.dialog.filesystemDirectoryOrFileReadPath),
        Triple("write_file", L.settings.dialog.writeFile2, L.settings.dialog.filesystemDirectoryOrFileWritePath)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = L.settings.dialog.addPermissionGrantRule, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Step 1: Select Action
                Text(L.settings.dialog.n1OperationType, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
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
                Text(L.settings.dialog.n2TargetPattern, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                OutlinedTextField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (selectedAction) {
                                "command" -> L.settings.dialog.eGNpmGit
                                "mcp" -> "e.g. *, local_tools/*"
                                "read_file" -> L.settings.dialog.eGHomeCatGemini
                                "write_file" -> L.settings.dialog.eGHomeCatExtra
                                else -> L.settings.dialog.eG
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
                Text(L.settings.dialog.n3PolicyDecision, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val decs = listOf(
                        Triple("ALLOW", "✅ Allow", QuotaGreen),
                        Triple("DENY", "🚫 Deny", MaterialTheme.colorScheme.error),
                        Triple("ASK", "❓ Ask", ai.antigem.theme.AppColors.warning)
                    )
                    decs.forEach { (key, label, col) ->
                        val isSel = selectedDecision == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(selected = isSel, role = Role.RadioButton) { selectedDecision = key },
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
                Text(L.settings.dialog.saveRule, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L.common.cancel)
            }
        }
    )
}

// ==========================================
// SUB-SCREEN 8: DIAGNOSTICS & PERFORMANCE
// ==========================================
@Composable
private fun DiagnosticsSubScreen(
    isFloatingDiagnosticsEnabled: Boolean,
    onToggleFloatingDiagnostics: (Boolean) -> Unit,
    isNetworkInspectorEnabled: Boolean,
    onToggleNetworkInspector: (Boolean) -> Unit,
    isFloatingNetworkInspectorEnabled: Boolean,
    onToggleFloatingNetworkInspector: (Boolean) -> Unit,
    onOpenNetworkInspector: () -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    DisposableEffect(isFloatingDiagnosticsEnabled) {
        ai.antigem.data.remote.core.AntiGemLiveDiagnostics.start()
        onDispose {
            if (!isFloatingDiagnosticsEnabled) {
                ai.antigem.data.remote.core.AntiGemLiveDiagnostics.stop()
            }
        }
    }

    val snapshot by ai.antigem.data.remote.core.AntiGemLiveDiagnostics.snapshot.collectAsState()
    val logs by ai.antigem.data.remote.inspector.NetworkInspectorManager.logs.collectAsState()
    val activeStreams by ai.antigem.data.remote.inspector.NetworkInspectorManager.activeStreamsCount.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingInspectorRestart by remember { mutableStateOf<Boolean?>(null) }

    if (pendingInspectorRestart != null) {
        val targetState = pendingInspectorRestart!!
        AlertDialog(
            onDismissRequest = { pendingInspectorRestart = null },
            title = {
                Text(
                    text = L.settings.dialog.restartRequired,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = if (targetState) {
                        L.settings.dialog.enablingNetworkInspectorRequires
                    } else {
                        L.settings.dialog.disablingNetworkInspectorRequires
                    },
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toEnable = targetState
                        pendingInspectorRestart = null
                        ai.antigem.data.preferences.AuthPreferences(context).saveNetworkInspectorEnabledSync(toEnable)
                        val pm = context.packageManager
                        val intent = pm.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            val restartIntent = android.content.Intent.makeRestartActivityTask(intent.component)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.info),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(L.settings.dialog.restartApp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingInspectorRestart = null }
                ) {
                    Text(L.common.cancel)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Network Inspector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val onToggleNetworkInspector: (Boolean) -> Unit = { targetState ->
                    pendingInspectorRestart = targetState
                }
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(isNetworkInspectorEnabled, onToggleNetworkInspector),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = (if (isNetworkInspectorEnabled) ai.antigem.theme.AppColors.info else Color.Gray).copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = if (isNetworkInspectorEnabled) ai.antigem.theme.AppColors.info else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = L.settings.dialog.networkInspectorLiveLogs,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = L.settings.dialog.inspectIdeBridgeHttpAgyDaemon,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isNetworkInspectorEnabled,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = onToggleNetworkInspector,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ai.antigem.theme.AppColors.info
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = L.settings.dialog.zeroOverheadArchitectureWhenDisabled,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 16.sp
                )

                if (isNetworkInspectorEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Floating Inspector Bubble toggle
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleRowSemantics(isFloatingNetworkInspectorEnabled, onToggleFloatingNetworkInspector),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = L.settings.dialog.floatingNetworkBubble,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = L.settings.dialog.showOnScreenDraggablePillWith,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = isFloatingNetworkInspectorEnabled,
                            modifier = Modifier.mergedIntoRow(),
                            onCheckedChange = onToggleFloatingNetworkInspector,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ai.antigem.theme.AppColors.info
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Open Inspector Button
                    Button(
                        onClick = onOpenNetworkInspector,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ai.antigem.theme.AppColors.info
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = L.settings.dialog.openNetworkInspectorLogscountCalls(logs.size),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        // Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleRowSemantics(isFloatingDiagnosticsEnabled, onToggleFloatingDiagnostics),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = (if (isFloatingDiagnosticsEnabled) QuotaGreen else Color.Gray).copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Speed,
                                    contentDescription = null,
                                    tint = if (isFloatingDiagnosticsEnabled) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = L.settings.dialog.floatingDiagnosticsHud,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = L.settings.dialog.liveOnScreenThreadQueueConnection,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isFloatingDiagnosticsEnabled,
                        modifier = Modifier.mergedIntoRow(),
                        onCheckedChange = onToggleFloatingDiagnostics,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = QuotaGreen
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = L.settings.dialog.displaysADraggableRealTimeFloating,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )
            }
        }

        // Live Snapshot Metric Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = L.settings.dialog.currentTelemetrySnapshot,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.8.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = L.settings.dialog.activeJvmThreads,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${snapshot.activeJvmThreads} threads",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = L.settings.dialog.agyhubStreamRpcs,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = L.settings.dialog.runAgyrunningQAgyqueued(snapshot.agyRunning, snapshot.agyQueued),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (snapshot.agyQueued > 0) ai.antigem.theme.AppColors.danger else MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = L.settings.dialog.agyhubConnectionPool,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = L.settings.dialog.agyconnsSocketsAgyidleconnsIdle(snapshot.agyConns, snapshot.agyIdleConns),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = L.settings.dialog.ideBridgeSockets,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = L.settings.dialog.ideconnsSocketsIdeidleconnsIdle(snapshot.ideConns, snapshot.ideIdleConns),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = L.settings.dialog.eventLoopIoLatency,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (snapshot.ioLagMs >= 0) "${snapshot.ioLagMs} ms" else L.settings.dialog.err,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (snapshot.isLagging) ai.antigem.theme.AppColors.danger else QuotaGreen
                    )
                }

                if (snapshot.threadGroupSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = L.settings.dialog.threadBreakdown,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snapshot.threadGroupSummary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Crash this app: a real crash on purpose, to see the crash screen and its report (release builds too)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = L.settings.dialog.crashThisApp,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = L.settings.dialog.crashThisAppWarning,
                    fontSize = 11.5.sp,
                    color = ai.antigem.theme.AppColors.danger,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    // thrown on the main thread, so it goes to CrashHandler like any real crash
                    onClick = { throw RuntimeException("Test crash from Settings > Diagnostics & Performance > Crash this app") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.danger)
                ) {
                    Text(
                        text = L.settings.dialog.crashNow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSubScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenChangelog: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateCheckResultText by remember { mutableStateOf<String?>(null) }
    var isUpdateCheckError by remember { mutableStateOf(false) }

    // Bridge file tracking in public Downloads directory
    val downloadDir = remember { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
    val bridgeFile = remember { File(downloadDir, "agy_ide_bridge") }
    var bridgeExists by remember { mutableStateOf(bridgeFile.exists() && bridgeFile.length() > 0) }
    var bridgeSize by remember { mutableStateOf(if (bridgeFile.exists()) bridgeFile.length() else 0L) }
    var bridgeLastModified by remember { mutableStateOf(if (bridgeFile.exists()) bridgeFile.lastModified() else 0L) }
    var isExportingBridge by remember { mutableStateOf(false) }

    fun refreshBridgeStatus() {
        val f = File(downloadDir, "agy_ide_bridge")
        bridgeExists = f.exists() && f.length() > 0
        bridgeSize = if (f.exists()) f.length() else 0L
        bridgeLastModified = if (f.exists()) f.lastModified() else 0L
    }

    if (updateInfo != null) {
        AppUpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { updateInfo = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncyVerticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. App Header & Branding Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ai.antigem.ui.components.AppIconImage(
                    contentDescription = "AntiGem",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "AntiGem",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = L.settings.dialog.antigravityMobileHubAiPairProgrammer,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                val variantColor = QuotaGreen
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = variantColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, variantColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = variantColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // What's new: this version's changes, opens the full timeline
        WhatsNewCard(cardBg = cardBg, cardBorder = cardBorder, onClick = onOpenChangelog)

        // 2. Package & Environment Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = L.settings.dialog.buildPackageInformation,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val bridgeVersion by ai.antigem.data.remote.BridgeInfo.version.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = L.settings.dialog.ideBridgeConnected,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val bv = bridgeVersion
                    val bridgeMatches = bv != null && ai.antigem.data.remote.BridgeInfo.compareBridgeToApp(bv) == 0
                    Text(
                        text = when {
                            bv == null -> L.settings.dialog.notConnected
                            bridgeMatches -> "v$bv"
                            else -> L.settings.dialog.vBvAppIsVVersion(bv, BuildConfig.VERSION_NAME)
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = if (bv == null || bridgeMatches) MaterialTheme.colorScheme.onSurface else ai.antigem.theme.AppColors.warning
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = L.settings.dialog.applicationId,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = BuildConfig.APPLICATION_ID,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = L.settings.dialog.buildType,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = BuildConfig.BUILD_TYPE.uppercase(Locale.ROOT),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = L.settings.dialog.nativeArchitecture,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Build.SUPPORTED_ABIS.firstOrNull() ?: L.settings.dialog.arm64V8a,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 3. Updates Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = L.settings.dialog.softwareUpdates,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Text(
                    text = L.settings.dialog.checksGithubReleasesForNewVersions,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                if (updateCheckResultText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUpdateCheckError) ai.antigem.theme.AppColors.danger.copy(alpha = 0.12f) else QuotaGreen.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isUpdateCheckError) ai.antigem.theme.AppColors.danger.copy(alpha = 0.3f) else QuotaGreen.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isUpdateCheckError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isUpdateCheckError) ai.antigem.theme.AppColors.danger else QuotaGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = updateCheckResultText!!,
                                fontSize = 12.sp,
                                color = if (isUpdateCheckError) ai.antigem.theme.AppColors.danger else QuotaGreen
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateCheckResultText = null
                            isUpdateCheckError = false
                            coroutineScope.launch {
                                val updateManager = AppUpdateManager(context)
                                val checkResult = updateManager.checkForUpdates(forceCheck = true)
                                isCheckingUpdate = false
                                checkResult.onSuccess { info ->
                                    if (info != null) {
                                        updateInfo = info
                                        updateCheckResultText = null
                                    } else {
                                        isUpdateCheckError = false
                                        updateCheckResultText = L.settings.dialog.antigemIsUpToDateV(BuildConfig.VERSION_NAME)
                                        Toast.makeText(context, L.settings.dialog.antigemIsUpToDate, Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { err ->
                                    isUpdateCheckError = true
                                    val msg = err.localizedMessage ?: L.settings.dialog.couldNotConnectToGithub
                                    updateCheckResultText = msg
                                    Toast.makeText(context, L.settings.dialog.updateCheckFailedMsg(msg), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(L.settings.dialog.checkingGithub)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(L.settings.dialog.checkForUpdates)
                    }
                }
            }
        }

        // 4. AGY IDE Bridge Server & Downloads Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderZip,
                            contentDescription = null,
                            tint = ai.antigem.theme.AppColors.accentTeal,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = L.settings.dialog.agyIdeBridgeBinary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (bridgeExists) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (bridgeExists) "IN DOWNLOADS" else "NOT EXPORTED",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (bridgeExists) QuotaGreen else Color.Gray,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = L.settings.dialog.exportTheCompiledAgyIdeBridge,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = L.settings.dialog.pathAbsolutepath(bridgeFile.absolutePath),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                        if (bridgeExists) {
                            val sizeKb = bridgeSize / 1024
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(bridgeLastModified))
                            Text(
                                text = L.settings.dialog.sizeSizekbKbModifiedDatestr(sizeKb, dateStr),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = QuotaGreen
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isExportingBridge = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val exported = LocalServerManager.saveBridgeToDownloads(context)
                                withContext(Dispatchers.Main) {
                                    isExportingBridge = false
                                    refreshBridgeStatus()
                                    if (exported != null && exported.exists()) {
                                        Toast.makeText(context, L.settings.dialog.savedAgyIdeBridgeToDownloads, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, L.settings.dialog.failedToExportBridgeBinary, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isExportingBridge,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.accentTeal)
                    ) {
                        if (isExportingBridge) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(L.settings.dialog.exportToDownloads, fontSize = 12.5.sp)
                        }
                    }

                    if (bridgeExists) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, L.settings.dialog.downloadsAbsolutepath(bridgeFile.absolutePath), Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(0.7f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, ai.antigem.theme.AppColors.accentTeal.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.accentTeal,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(L.settings.dialog.viewFile, fontSize = 12.5.sp, color = ai.antigem.theme.AppColors.accentTeal)
                        }
                    }
                }
            }
        }

        // 5. GitHub & External Links
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = L.settings.dialog.resourcesRepository,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/pJvvTDTc6G"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = L.settings.dialog.discordCommunity,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = L.settings.dialog.getHelpShareBuildsIdeas,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/santoshkurmi/antigem"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = L.settings.dialog.githubRepository,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "github.com/santoshkurmi/antigem",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/santoshkurmi/antigem/releases"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = L.settings.dialog.releaseChangelogs,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = L.settings.dialog.viewTagNotesApkDownloads,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
