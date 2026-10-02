package com.example.gemini.ui.chat

import com.example.gemini.ui.components.ChatToast
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.AppToastHelper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.gemini.data.preferences.AuthPreferences
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.example.gemini.data.daemon.FileNode
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.gemini.ui.components.ActiveContextSummaryCard
import com.example.gemini.ui.components.AttachmentSelectorBottomSheet
import com.example.gemini.ui.components.ChatInputBar
import com.example.gemini.ui.components.ChatTelemetryDialog
import com.example.gemini.ui.components.LiveSummarizingCard
import com.example.gemini.ui.components.MessageBubble
import com.example.gemini.ui.components.RawPayloadDialog
import com.example.gemini.ui.components.TerminalInspectorDialog
import com.example.gemini.ui.components.LocalTerminalDialog
import com.example.gemini.ui.components.LocalTerminalContent
import com.example.gemini.ui.settings.LocalToolsInstallDialog
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.ui.drawer.ChatHistoryDrawer
import com.example.gemini.ui.models.ModelSelectorBottomSheet
import com.example.gemini.ui.models.ThinkingSelectorBottomSheet
import com.example.gemini.ui.components.ToolApprovalDialog
import com.example.gemini.ui.components.ToolApprovalDockedPanel
import com.example.gemini.ui.settings.SettingsDialog
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.MarkdownBlockView
import com.example.gemini.ui.components.parseMarkdownBlocks
import com.example.gemini.ui.components.UserMessageBubble
import com.example.gemini.ui.components.AssistantMessageFooter
import com.example.gemini.ui.components.ThinkingAccordion
import com.example.gemini.ui.components.ModelTypingIndicator
import com.example.gemini.ui.components.ConnectionStatusBadge
import com.example.gemini.ui.components.FileLinkHandler
import com.example.gemini.ui.components.LocalFileLinkHandler
import com.example.gemini.ui.components.FileDetailsDialog
import com.example.gemini.ui.components.MarkdownDocViewerModal
import com.example.gemini.ui.components.SharedChatViewerModal
import com.example.gemini.ui.components.SharedChatLoadingDialog
import com.example.gemini.ui.components.ProjectPickerDialog
import com.example.gemini.ui.components.FileManagerDialog
import com.example.gemini.ui.components.ToolCallExpansionCache
import com.example.gemini.ui.components.CodeBlockExpansionCache
import com.example.gemini.ui.bubble.FloatingChatActivity
import android.app.Activity
import com.example.gemini.ui.drawer.ArtifactsDrawerContent
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.interaction.MutableInteractionSource
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

enum class ScrollDirection { UP, DOWN }



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    isInFloatingWindow: Boolean = false,
    onOpenFullScreen: () -> Unit = {},
    onMinimizeWindow: () -> Unit = {},
    onNavigateToIde: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showArtifactsDrawer by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val conversations by viewModel.conversations.collectAsState()
    val currentConv by viewModel.currentConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val artifacts by viewModel.artifacts.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val enabledModelIds by viewModel.enabledModelIds.collectAsState()
    val isRefreshingModels by viewModel.isRefreshingModels.collectAsState()
    val quotas by viewModel.quotas.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val projectId by viewModel.projectId.collectAsState()
    val tier by viewModel.tier.collectAsState()
    val summarizingModelName by viewModel.summarizingModelName.collectAsState()
    val pendingQueuedUserMessage by viewModel.pendingQueuedUserMessage.collectAsState()
    val contextWindowLimit by viewModel.contextWindowLimit.collectAsState()

    val summaryModelIdPref by viewModel.summaryModelIdPref.collectAsState()
    val sharedConversationPreview by viewModel.sharedConversationPreview.collectAsState()
    val isSharedConversationLoading by viewModel.isSharedConversationLoading.collectAsState()
    val incomingMarkdownPreview by viewModel.incomingMarkdownPreview.collectAsState()
    val isDevModeEnabled by viewModel.isDevModeEnabled.collectAsState()
    val chatFontScale by viewModel.chatFontScale.collectAsState(initial = 1.0f)
    val bridgeStatusMessage by viewModel.bridgeStatusMessage.collectAsState()
    val isServerOnline by viewModel.isServerOnline.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val isBridgeOnline by viewModel.isBridgeOnline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val conversationError by viewModel.conversationError.collectAsState()
    val activeInstances by viewModel.activeInstances.collectAsState()
    val quotaSummary by viewModel.quotaSummary.collectAsState()
    val preferredModelName by viewModel.preferredModelName.collectAsState()
    val isLocalToolsEnabled by viewModel.isLocalToolsEnabled.collectAsState(initial = false)
    val isLocalToolsInstalled by viewModel.isLocalToolsInstalled.collectAsState(initial = false)
    val useSshTerminal by viewModel.useSshTerminal.collectAsState(initial = false)
    val sshHost by viewModel.termuxSshHost.collectAsState(initial = "127.0.0.1")
    val sshPort by viewModel.termuxSshPort.collectAsState(initial = 8022)
    val sshUser by viewModel.termuxSshUser.collectAsState(initial = "root")
    val sshPass by viewModel.termuxSshPass.collectAsState(initial = "root")
    val themeMode by viewModel.themeMode.collectAsState(initial = viewModel.authPreferences.getThemeModeSync())
    val agyHubUrl by viewModel.agyHubUrl.collectAsState(initial = AuthPreferences.currentHubUrl)
    val agyBridgeHttpUrl by viewModel.agyBridgeHttpUrl.collectAsState(initial = AuthPreferences.currentBridgeHttpUrl)
    val terminalFontSize by viewModel.terminalFontSize.collectAsState(initial = 14)
    val terminalCursorStyle by viewModel.terminalCursorStyle.collectAsState(initial = "BAR")
    val terminalBufferSize by viewModel.terminalBufferSize.collectAsState(initial = 20000)
    val terminalTheme by viewModel.terminalTheme.collectAsState(initial = "DEFAULT")
    val commandAutoExecutionPolicy by viewModel.commandAutoExecutionPolicy.collectAsState(initial = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER")
    val commandSandboxEnabled by viewModel.commandSandboxEnabled.collectAsState(initial = false)
    val requireApprovalForFileEdits by viewModel.requireApprovalForFileEdits.collectAsState(initial = false)
    val defaultApprovalScope by viewModel.defaultApprovalScope.collectAsState(initial = "PERMISSION_SCOPE_ONCE")
    val globalSecuritySettings by viewModel.globalSecuritySettings.collectAsState()
    val globalSettingsError by viewModel.globalSettingsError.collectAsState()
    val isGlobalSettingsLoading by viewModel.isGlobalSettingsLoading.collectAsState()
    val projectsList by viewModel.projectsList.collectAsState()
    val isProjectsLoading by viewModel.isProjectsLoading.collectAsState()
    val groupChatsByWorkspace by viewModel.groupChatsByWorkspace.collectAsState()
    val isBrowserAutomationEnabled by viewModel.isBrowserAutomationEnabled.collectAsState()
    val isTerminalAutomationEnabled by viewModel.isTerminalAutomationEnabled.collectAsState()
    val isFloatingSwitcherEnabled by viewModel.isFloatingSwitcherEnabled.collectAsState()
    val floatingSwitcherOrientation by viewModel.floatingSwitcherOrientation.collectAsState()
    val floatingSwitcherItems by viewModel.floatingSwitcherItems.collectAsState()
    val floatingSwitcherAutoCollapseSec by viewModel.floatingSwitcherAutoCollapseSec.collectAsState()
    val isFloatingDiagnosticsEnabled by viewModel.isFloatingDiagnosticsEnabled.collectAsState()
    val isNetworkInspectorEnabled by viewModel.isNetworkInspectorEnabled.collectAsState()
    val isFloatingNetworkInspectorEnabled by viewModel.isFloatingNetworkInspectorEnabled.collectAsState()
    var showNetworkInspectorDialog by remember { mutableStateOf(false) }
    val hasSeenTerminalLauncherOnboarding by viewModel.authPreferences.hasSeenTerminalLauncherOnboarding.collectAsState(initial = true)
    val isTerminalLauncherEnabledPref by viewModel.authPreferences.isTerminalLauncherEnabled.collectAsState(initial = true)
    val terminalLauncherStylePref by viewModel.authPreferences.terminalLauncherStyle.collectAsState(initial = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM)
    val isTranscribingAudio by viewModel.isTranscribingAudio.collectAsState()
    val pendingLoginUrl by viewModel.pendingLoginUrl.collectAsState()
    val hubStatus by viewModel.hubStatus.collectAsState()
    val systemConnectionState by viewModel.systemConnectionState.collectAsState()
    val isNetworkConnected by viewModel.isNetworkConnectedState.collectAsState()
    val isAnyGenerationOrTaskActive by viewModel.isAnyGenerationOrTaskActive.collectAsState()

    DisposableEffect(isAnyGenerationOrTaskActive) {
        val window = (context as? Activity)?.window
        if (isAnyGenerationOrTaskActive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            viewModel.onDrawerOpened()
        }
    }

    var showModelSelector by remember { mutableStateOf(false) }
    var showThinkingSelector by remember { mutableStateOf(false) }
    var showTerminalInspector by remember { mutableStateOf(false) }
    var showLocalTerminalDialog by rememberSaveable { mutableStateOf(false) }
    var showLocalToolsInstallDialog by remember { mutableStateOf(false) }
    var showLocalServerOutputDialog by remember { mutableStateOf(false) }
    val serverStatus by com.example.gemini.data.local.LocalServerManager.status.collectAsState()
    val isLocalRunning = serverStatus is com.example.gemini.data.local.LocalServerStatus.Running
    val isLocalStarting = serverStatus is com.example.gemini.data.local.LocalServerStatus.Starting
    val isLocalStopped = serverStatus is com.example.gemini.data.local.LocalServerStatus.Stopped
    val isLocalError = serverStatus is com.example.gemini.data.local.LocalServerStatus.Error
    var showRawPayloadDialog by remember { mutableStateOf<String?>(null) }
    var showChatTelemetryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAttachmentSelector by remember { mutableStateOf(false) }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    var showWorkspaceFolderBrowserDialog by remember { mutableStateOf(false) }
    var showStopConfirmDialog by remember { mutableStateOf(false) }
    var isInitialGracePeriod by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500)
        isInitialGracePeriod = false
    }

    LaunchedEffect(showSettingsDialog) {
        if (showSettingsDialog) {
            viewModel.loadSecurityAndProjectSettings()
        }
    }

    val pendingApprovals by viewModel.pendingApprovals.collectAsState()

    var currentToast by remember { mutableStateOf<ChatToast?>(null) }

    fun showToast(message: String, type: ChatToastType? = null) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        val resolvedType = type ?: run {
            val lower = trimmed.lowercase()
            when {
                lower.contains("success") || lower.contains("signed in") || lower.contains("logged in") ||
                lower.contains("saved") || lower.contains("switched") || lower.contains("copied") ||
                lower.contains("connected") || lower.contains("ready") -> ChatToastType.SUCCESS

                lower.contains("error") || lower.contains("fail") || lower.contains("cannot") ||
                lower.contains("can't") || lower.contains("invalid") || lower.contains("refused") ||
                lower.contains("timeout") || lower.contains("exception") || lower.contains("denied") -> ChatToastType.ERROR

                else -> ChatToastType.INFO
            }
        }
        currentToast = ChatToast(message = trimmed, type = resolvedType)
    }

    LaunchedEffect(currentToast) {
        if (currentToast != null) {
            delay(3500)
            currentToast = null
        }
    }

    val agyAuthInfo by viewModel.agyAuthInfo.collectAsState()
    val isAuthBusy by viewModel.isAuthBusy.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.authFeedbackMessage.collect { msg ->
            showToast(msg)
        }
    }

    LaunchedEffect(Unit) {
        com.example.gemini.ui.components.AppToastHelper.toastFlow.collect { toast ->
            currentToast = toast
        }
    }

    val attachments by viewModel.attachments.collectAsState()
    val isUploadingAttachment by viewModel.isUploadingAttachment.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachmentsFromUris(uris, context)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachmentsFromUris(uris, context)
        }
    }
    var activeConversationKey by remember { mutableStateOf(currentConv?.id ?: "new") }
    var textFieldValue by remember {
        mutableStateOf(viewModel.getDraft(currentConv?.id ?: "new"))
    }

    LaunchedEffect(currentConv?.id) {
        val newKey = currentConv?.id ?: "new"
        if (newKey != activeConversationKey) {
            viewModel.setDraft(activeConversationKey, textFieldValue)
            activeConversationKey = newKey
            textFieldValue = viewModel.getDraft(newKey)
            ToolCallExpansionCache.setChat(newKey)
            CodeBlockExpansionCache.setChat(newKey)
        }
    }
    val inputText = textFieldValue.text
    var pendingMessageAction by remember { mutableStateOf<PendingMessageAction?>(null) }
    val thinkingPref by viewModel.thinkingPreference.collectAsState()
    val terminatedToolDialogState by viewModel.terminatedToolDialog.collectAsState()
    val isOAuthServerListening by viewModel.isOAuthServerListening.collectAsState()
    val isOAuthServerLoading by viewModel.isOAuthServerLoading.collectAsState()
    val isConversationsLoading by viewModel.isConversationsLoading.collectAsState()
    val hasReceivedInitialSync by viewModel.hasReceivedInitialSync.collectAsState()
    val isLoadingConversation by viewModel.isLoadingConversation.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val isMcpLoading by viewModel.isMcpLoading.collectAsState()
    val isMcpRefreshing by viewModel.isMcpRefreshing.collectAsState()
    val refreshingMcpServer by viewModel.refreshingMcpServer.collectAsState()
    val mcpErrorMessage by viewModel.mcpErrorMessage.collectAsState()
    val mcpStatusMessage by viewModel.mcpStatusMessage.collectAsState()

    val availableCascadePlugins by viewModel.availableCascadePlugins.collectAsState()
    val isCascadePluginsLoading by viewModel.isCascadePluginsLoading.collectAsState()
    val installingCascadePluginId by viewModel.installingCascadePluginId.collectAsState()

    val allSkills by viewModel.allSkills.collectAsState()
    val isSkillsLoading by viewModel.isSkillsLoading.collectAsState()
    val skillsFilterScope by viewModel.skillsFilterScope.collectAsState()

    val installedPlugins by viewModel.installedPlugins.collectAsState()
    val isInstalledPluginsLoading by viewModel.isInstalledPluginsLoading.collectAsState()

    val googlePluginsCatalog by viewModel.googlePluginsCatalog.collectAsState()
    val isGooglePluginsLoading by viewModel.isGooglePluginsLoading.collectAsState()
    val installingGooglePluginId by viewModel.installingGooglePluginId.collectAsState()
    val deletingPluginId by viewModel.deletingPluginId.collectAsState()

    val pluginActionStatusMessage by viewModel.pluginActionStatusMessage.collectAsState()
    val pluginActionErrorMessage by viewModel.pluginActionErrorMessage.collectAsState()

    var allSlashCommands by remember { mutableStateOf(com.example.gemini.data.remote.SlashCommandsCache.getCachedSync()) }

    LaunchedEffect(agyHubUrl, systemConnectionState) {
        if (systemConnectionState.isHubOnline) {
            val fetched = com.example.gemini.data.remote.SlashCommandsCache.getCommands(agyHubUrl)
            if (fetched.isNotEmpty()) {
                allSlashCommands = fetched
            }
            val curId = currentConv?.id
            val isExisting = currentConv != null && currentConv?.title != "New Chat" && conversations.any { it.id == currentConv?.id }
            if (!curId.isNullOrBlank() && curId != "new" && isExisting && messages.isEmpty()) {
                viewModel.startPersistentStream(curId)
            }
        }
    }

    // Granular block-level feed item expansion from pre-warmed background cache (0ms UI thread work)
    val feedItems = remember(messages, selectedModelId) {
        ChatFeedCache.buildFeedItems(messages, selectedModelId)
    }

    // Fresh LazyListState per conversation — restores saved position if returning, or initializes directly at bottom
    val convKey = currentConv?.id ?: "empty"
    val hasFeedItems = feedItems.isNotEmpty()
    val initialSavedPos = remember(convKey) { ConversationScrollCache.get(convKey) }
    val listState = remember(convKey, hasFeedItems) {
        val savedPos = ConversationScrollCache.get(convKey)
        if (savedPos != null) {
            LazyListState(
                firstVisibleItemIndex = savedPos.index.coerceIn(0, maxOf(0, feedItems.size)),
                firstVisibleItemScrollOffset = savedPos.offset
            )
        } else {
            val initialIdx = if (feedItems.isNotEmpty()) feedItems.size else 0
            LazyListState(firstVisibleItemIndex = initialIdx)
        }
    }

    // Determine whether user is scrolled near the bottom (within the last item)
    val isAtBottom by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) true
            else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisibleItem == null) false
                else {
                    lastVisibleItem.index >= totalItems - 2
                }
            }
        }
    }

    val isAtTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    var scrollDirection by remember { mutableStateOf(ScrollDirection.DOWN) }
    var showScrollButton by remember { mutableStateOf(false) }
    var shouldAutoScroll by remember(convKey) { mutableStateOf(initialSavedPos?.isNearBottom ?: true) }
    var isUserDragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var isKeyboardAnimating by remember { mutableStateOf(false) }

    val emptyScrollState = rememberScrollState()

    // Track user drag interactions so programmatic scrolling never turns off shouldAutoScroll
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isUserDragging = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    isUserDragging = false
                }
            }
        }
    }

    // Synchronized Chat & Keyboard movement:
    // When keyboard rises, scroll list / empty state up in lockstep with the rising input bar so messages and recent workspaces stay visible.
    // When keyboard hides, Compose & Android handle layout expansion natively.
    LaunchedEffect(imeInsets, density, listState, emptyScrollState) {
        var previousIme = imeInsets.getBottom(density)

        snapshotFlow { imeInsets.getBottom(density) }
            .collect { currentIme ->
                val delta = currentIme - previousIme
                if (delta > 0) {
                    isKeyboardAnimating = true
                    if (feedItems.isNotEmpty()) {
                        // Messages list: scroll up
                        listState.scrollBy(delta.toFloat())
                    } else {
                        // Empty state in new chat: scroll up so recent workspaces remain visible
                        emptyScrollState.scrollBy(delta.toFloat())
                    }
                }
                if (currentIme == 0) {
                    isKeyboardAnimating = false
                    if (feedItems.isEmpty()) {
                        emptyScrollState.scrollTo(0)
                    }
                }
                previousIme = currentIme
            }
    }

    // Decoupled asynchronous scroll observer - only user drags can change autoscroll state
    LaunchedEffect(listState) {
        var prevIdx = listState.firstVisibleItemIndex
        var prevOff = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (isScrolling, currentIndex, currentOffset) ->
            if (isScrolling && !isKeyboardAnimating) {
                showScrollButton = true
                val newDir = if (currentIndex < prevIdx || (currentIndex == prevIdx && currentOffset < prevOff)) {
                    ScrollDirection.UP
                } else if (currentIndex > prevIdx || (currentIndex == prevIdx && currentOffset > prevOff)) {
                    ScrollDirection.DOWN
                } else null

                if (newDir != null && newDir != scrollDirection) {
                    scrollDirection = newDir
                }
                // ONLY disable auto-scroll if the USER is actively dragging/swiping upwards
                if (isUserDragging && newDir == ScrollDirection.UP) {
                    shouldAutoScroll = false
                }
                prevIdx = currentIndex
                prevOff = currentOffset
            }
        }
    }

    // Re-enable auto-scroll whenever the user scrolls back to the bottom
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            shouldAutoScroll = true
        }
    }

    // Hide scroll button after scroll stops
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && showScrollButton) {
            delay(4000)
            showScrollButton = false
        }
    }

    DisposableEffect(convKey) {
        onDispose {
            if (convKey != "empty" && feedItems.isNotEmpty()) {
                ConversationScrollCache.save(convKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
            }
        }
    }



    var userSentMessageTrigger by remember { mutableStateOf(0) }

    val isDarkTheme = com.example.gemini.theme.isAppInDarkTheme()

    // Background prewarm to ensure zero UI-thread lag during scroll (recent window of 12 messages)
    LaunchedEffect(messages.size, isDarkTheme) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ChatFeedCache.prewarm(messages, windowSize = 12, isDark = isDarkTheme)
        }
    }


    // Scroll to bottom when user explicitly sends a message (instant)
    LaunchedEffect(userSentMessageTrigger) {
        if (userSentMessageTrigger > 0 && feedItems.isNotEmpty()) {
            shouldAutoScroll = true
            listState.scrollToItem(feedItems.size)
        }
    }

    // Smart auto-scroll during streaming & tool execution: follows live stream and tool calls smoothly without getting stuck
    val lastMsg = messages.lastOrNull()
    val lastContentLen = lastMsg?.content?.length ?: 0
    val lastThoughtLen = lastMsg?.thoughtText?.length ?: 0
    val lastToolCalls = lastMsg?.toolCalls.orEmpty()
    val toolCallsPayloadLen = lastToolCalls.sumOf { it.command.length + it.output.length + it.status.length }
    val isRunningOrStreaming = isStreaming || (currentConv?.isRunning == true) || (lastMsg?.isStreaming == true)

    LaunchedEffect(feedItems.size, lastContentLen, lastThoughtLen, toolCallsPayloadLen, isRunningOrStreaming) {
        if (feedItems.isNotEmpty() && isRunningOrStreaming && shouldAutoScroll && !isUserDragging) {
            listState.scrollToItem(feedItems.size)
        }
    }

    val currentModel = AiModel.findInList(enabledModels, selectedModelId, preferredModelName)
    val currentQuota = quotas.find { it.modelId == selectedModelId }

    val activeChatProject by TermuxDaemonManager.activeProject.collectAsState()
    val usedProjects by TermuxDaemonManager.projects.collectAsState()
    var showProjectDropdown by remember { mutableStateOf(false) }
    var chatProjectFiles by remember { mutableStateOf<List<com.example.gemini.data.daemon.FileNode>>(emptyList()) }

    LaunchedEffect(Unit) {
        TermuxDaemonManager.loadProjects(conversations)
    }

    LaunchedEffect(Unit) {
        TermuxDaemonManager.serverReconnectedEvent.collect {
            TermuxDaemonManager.loadProjects(conversations)
        }
    }

    LaunchedEffect(activeChatProject) {
        if (activeChatProject != null) {
            scope.launch {
                val tree = IdeApiClient.getFileTree(activeChatProject?.path)
                chatProjectFiles = flattenFileNodes(tree)
            }
        }
    }

    var activeMarkdownDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeFileDetailsPath by remember { mutableStateOf<String?>(null) }

    val conversationBackStack = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } }
        )
    ) {
        mutableStateListOf<String>()
    }

    val navigateBackConversation: () -> Unit = {
        while (conversationBackStack.isNotEmpty()) {
            val prevId = conversationBackStack.removeAt(conversationBackStack.lastIndex)
            if (conversations.isEmpty() || conversations.any { it.id.equals(prevId, ignoreCase = true) }) {
                val currentId = currentConv?.id
                if (!currentId.isNullOrBlank() && feedItems.isNotEmpty()) {
                    ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                }
                viewModel.selectConversation(prevId)
                break
            }
        }
    }

    BackHandler(enabled = showArtifactsDrawer) {
        showArtifactsDrawer = false
    }

    BackHandler(enabled = conversationBackStack.isNotEmpty() && !drawerState.isOpen && drawerState.targetValue != DrawerValue.Open && !showArtifactsDrawer) {
        navigateBackConversation()
    }

    val currentUriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    val fileLinkHandler = remember(scope, conversations, currentConv?.id, listState, feedItems.size, isAtBottom) {
        FileLinkHandler(
            onOpenFile = { rawUrl ->
                try {
                    val decoded = try { java.net.URLDecoder.decode(rawUrl, "UTF-8") } catch (_: Exception) { rawUrl }
                    val cleanPath = decoded.removePrefix("file://").substringBefore("#")
                    val isMd = cleanPath.endsWith(".md", ignoreCase = true) || cleanPath.endsWith(".markdown", ignoreCase = true)
                    if (isMd) {
                        scope.launch {
                            val content = try {
                                val f = File(cleanPath)
                                if (f.exists()) f.readText() else (IdeApiClient.readFile(cleanPath) ?: "")
                            } catch (e: Exception) {
                                IdeApiClient.readFile(cleanPath) ?: ""
                            }
                            activeMarkdownDoc = cleanPath to content
                        }
                    } else {
                        scope.launch {
                            val content = try {
                                val f = File(cleanPath)
                                if (f.exists()) f.readText() else (IdeApiClient.readFile(cleanPath) ?: "")
                            } catch (e: Exception) {
                                IdeApiClient.readFile(cleanPath) ?: ""
                            }
                            val fileName = File(cleanPath).name.ifBlank { "file" }
                            TermuxDaemonManager.openOrSelectTab(cleanPath, fileName, content)
                            onNavigateToIde()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Error opening file link $rawUrl", e)
                }
            },
            onShowDetails = { rawUrl ->
                val decoded = try { java.net.URLDecoder.decode(rawUrl, "UTF-8") } catch (_: Exception) { rawUrl }
                val cleanPath = decoded.removePrefix("file://").substringBefore("#")
                activeFileDetailsPath = cleanPath
            },
            onOpenConversation = { convId ->
                val cleanId = convId.trim()
                val target = conversations.find { it.id.equals(cleanId, ignoreCase = true) }
                if (target != null) {
                    val currentId = currentConv?.id
                    if (!currentId.isNullOrBlank() && !currentId.equals(target.id, ignoreCase = true)) {
                        if (feedItems.isNotEmpty()) {
                            ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                        }
                        conversationBackStack.add(currentId)
                    }
                    viewModel.selectConversation(target.id)
                    if (drawerState.isOpen) {
                        scope.launch { drawerState.close() }
                    }
                } else {
                    android.widget.Toast.makeText(context, "This conversation doesn't exist", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    LaunchedEffect(fileLinkHandler) {
        com.example.gemini.ui.components.ActiveFileLinkHandlerHolder.current = fileLinkHandler
    }

    val safeUriHandler = remember(currentUriHandler, fileLinkHandler) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("file://", ignoreCase = true) || uri.startsWith("/")) {
                    fileLinkHandler.onOpenFile(uri)
                } else if (uri.startsWith("conversation://", ignoreCase = true)) {
                    val convId = uri.removePrefix("conversation://").substringBefore("#").substringBefore("/").trim()
                    fileLinkHandler.onOpenConversation(convId)
                } else {
                    try {
                        currentUriHandler.openUri(uri)
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Failed to open external URI: $uri", e)
                    }
                }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    CompositionLocalProvider(
        LocalFileLinkHandler provides fileLinkHandler,
        androidx.compose.ui.platform.LocalUriHandler provides safeUriHandler,
        com.example.gemini.ui.components.LocalSnackbarHostState provides snackbarHostState
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = conversations,
                currentConversationId = currentConv?.id,
                activeInstances = activeInstances,
                isLoading = isConversationsLoading,
                hasReceivedInitialSync = hasReceivedInitialSync,
                systemConnectionState = systemConnectionState,
                errorMessage = conversationError,
                isStreaming = isStreaming,
                groupByWorkspace = groupChatsByWorkspace,
                onToggleGroupByWorkspace = { viewModel.setGroupChatsByWorkspace(it) },
                onRetry = { viewModel.retryConnections() },
                isOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open,
                onSelectConversation = { id ->
                    Log.d("CHAT_OPEN_DEBUG", "🎯 [ChatScreen] User selected conversation: id=$id")
                    val currentId = currentConv?.id
                    if (!currentId.isNullOrBlank() && feedItems.isNotEmpty()) {
                        ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                    }
                    conversationBackStack.clear()
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    val currentId = currentConv?.id
                    if (!currentId.isNullOrBlank() && feedItems.isNotEmpty()) {
                        ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                    }
                    conversationBackStack.clear()
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    ConversationScrollCache.clear(id)
                    conversationBackStack.removeAll { it.equals(id, ignoreCase = true) }
                    viewModel.deleteConversation(id)
                },
                onForkConversation = { id ->
                    conversationBackStack.clear()
                    viewModel.forkConversation(id)
                    scope.launch { drawerState.close() }
                },
                onTerminateInstance = { id ->
                    viewModel.terminateInstance(id)
                },
                onSearchQueryChange = { query ->
                    viewModel.searchConversations(query)
                },
                authInfo = agyAuthInfo,
                isAuthBusy = isAuthBusy,
                onLogin = {
                    viewModel.loginToAgyHub(force = true)
                },
                onLogout = {
                    viewModel.logoutFromAgyHub()
                },
                onCheckAuth = {
                    viewModel.checkAgyAuthStatus(userInitiated = true)
                },
                onCancelLogin = {
                    viewModel.cancelAgyLogin()
                },
                onOpenSettings = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        if (pendingLoginUrl != null) {
            val targetUrl = pendingLoginUrl!!
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingLoginUrl() },
                title = {
                    Text(
                        text = "Sign In with Browser",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Antigravity requires Google authentication to access Gemini models.",
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Do you want to open your browser to log in now?",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("ChatScreen", "Failed to open browser: ${e.message}")
                            }
                            viewModel.clearPendingLoginUrl()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Text("Open Browser", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearPendingLoginUrl() }) {
                        Text("Cancel")
                    }
                }
            )
        }
        Scaffold(
            snackbarHost = {},
            topBar = {
                TopAppBar(
                    title = {
                        val displayedChatTitle = remember(currentConv?.title, conversations, currentConv?.id) {
                            val fromList = conversations.firstOrNull { it.id == currentConv?.id }
                            val listTitle = fromList?.title?.takeIf {
                                it.isNotBlank() && it != "New Chat" && it != "Conversation"
                            }
                            val activeTitle = currentConv?.title?.takeIf {
                                it.isNotBlank() && it != "New Chat" && it != "Conversation"
                            }
                            listTitle ?: activeTitle ?: currentConv?.title?.takeIf { it.isNotBlank() } ?: "Antigravity Chat"
                        }

                        Column {
                            Text(
                                text = displayedChatTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isChatStarted = messages.isNotEmpty()
                                val displayedProjectName = remember(currentConv?.workspaceUri, isChatStarted) {
                                    val convUri = currentConv?.workspaceUri
                                    if (!convUri.isNullOrBlank()) {
                                        val clean = convUri.removePrefix("file://").trimEnd('/')
                                        File(clean).name.ifBlank { "Workspace" }
                                    } else {
                                        if (isChatStarted) "Outside" else "Select Project"
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isChatStarted) 0.4f else 0.6f),
                                    modifier = if (isChatStarted) Modifier else Modifier.clickable {
                                        showProjectPickerDialog = true
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(11.dp),
                                            tint = if (isChatStarted) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = displayedProjectName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = if (isChatStarted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!isChatStarted) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (conversationBackStack.isNotEmpty()) {
                            IconButton(onClick = navigateBackConversation) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Drawer",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    actions = {
                        if (isInFloatingWindow) {
                            IconButton(onClick = onMinimizeWindow) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Minimize to Bubble",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                            IconButton(onClick = onOpenFullScreen) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "Open Full Screen",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }
                        if (isLocalToolsInstalled || com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context) || systemConnectionState.status != com.example.gemini.data.remote.SystemStatus.OFFLINE) {
                            val dotColor = systemConnectionState.dotColor

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable { showLocalServerOutputDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToBrowser) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = "Web Browser Preview",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(onClick = {
                            if (useSshTerminal || (isLocalToolsInstalled && isLocalToolsEnabled)) {
                                onNavigateToTerminal()
                            } else if (isLocalToolsInstalled) {
                                viewModel.setLocalToolsEnabled(true)
                                onNavigateToTerminal()
                            } else {
                                showLocalToolsInstallDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = "Terminal",
                                tint = if (useSshTerminal || (isLocalToolsInstalled && isLocalToolsEnabled)) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        IconButton(onClick = onNavigateToIde) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Code Editor IDE",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (isDevModeEnabled) {
                            IconButton(onClick = { showChatTelemetryDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Analytics,
                                    contentDescription = "Chat Telemetry & Tokens",
                                    tint = ClaudeTerracotta
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            // Synchronized container that moves seamlessly with the IME keyboard
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val sysStatus = systemConnectionState.status
                    val isServerInitializing = sysStatus == com.example.gemini.data.remote.SystemStatus.STARTING || sysStatus == com.example.gemini.data.remote.SystemStatus.ACQUIRING_CSRF || (sysStatus == com.example.gemini.data.remote.SystemStatus.OFFLINE && isInitialGracePeriod)
                    val isServerStopped = sysStatus == com.example.gemini.data.remote.SystemStatus.OFFLINE && !isInitialGracePeriod
                    val isExistingConversation = currentConv != null && currentConv?.title != "New Chat" && conversations.any { it.id == currentConv?.id }
                    val isExistingChat = messages.isEmpty() && isExistingConversation && isLoadingConversation
                    Log.d("CHAT_OPEN_DEBUG", "🖥️ [ChatScreen Render] convId=${currentConv?.id}, title='${currentConv?.title}', isLoading=$isLoadingConversation, isExisting=$isExistingChat, msgCount=${messages.size}, error=$conversationError, isServerStopped=$isServerStopped")

                    if (isServerStopped && messages.isEmpty()) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val minHeight = maxHeight
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(emptyScrollState)
                                    .heightIn(min = minHeight)
                                    .padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                com.example.gemini.ui.components.ServerStoppedPromptCard(
                                    onStartServerClick = {
                                        com.example.gemini.data.local.LocalServerManager.startServer(context)
                                        viewModel.retryConnections()
                                    }
                                )
                            }
                        }
                    } else if (isServerInitializing && messages.isEmpty()) {
                        com.example.gemini.ui.components.EngineWarmingUpView()
                    } else if (!conversationError.isNullOrBlank() && messages.isEmpty() && (isExistingConversation || currentConv != null)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Unable to Load Chat",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    text = conversationError ?: "Failed to load conversation messages.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    val convId = currentConv?.id
                                    if (!convId.isNullOrBlank()) {
                                        viewModel.selectConversation(convId)
                                    } else {
                                        viewModel.retryConnections()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", fontSize = 13.5.sp)
                            }
                        }
                    } else if (isExistingChat) {
                        com.example.gemini.ui.components.ConversationLoadingSkeleton()
                    } else if (messages.isEmpty()) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val minHeight = maxHeight
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(emptyScrollState)
                                    .heightIn(min = minHeight)
                                    .padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (!isNetworkConnected) {
                                    com.example.gemini.ui.components.NewChatOfflinePromptCard(
                                        modifier = Modifier.padding(bottom = 20.dp)
                                    )
                                }

                                when (systemConnectionState.status) {
                                    com.example.gemini.data.remote.SystemStatus.CHECKING_AUTH -> {
                                        com.example.gemini.ui.components.NewChatCheckingAuthPromptCard()
                                    }
                                    com.example.gemini.data.remote.SystemStatus.UNAUTHENTICATED -> {
                                        if (isNetworkConnected) {
                                            com.example.gemini.ui.components.NewChatSignInPromptCard(
                                                onSignInClick = { viewModel.loginToAgyHub(force = true) }
                                            )
                                        }
                                    }
                                    else -> {
                                        Text(
                                            text = "How can I help you today?",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Ask a question, brainstorm ideas, or start coding",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val currentDensity = androidx.compose.ui.platform.LocalDensity.current
                        val customDensity = remember(currentDensity, chatFontScale) {
                            androidx.compose.ui.unit.Density(
                                density = currentDensity.density,
                                fontScale = currentDensity.fontScale * chatFontScale
                            )
                        }

                        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides customDensity) {
                            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
                                ) {
                                    items(
                                        items = feedItems,
                                        key = { it.key },
                                        contentType = { it.contentType }
                                    ) { feedItem ->
                                        when (feedItem) {
                                        is ChatFeedItem.Summary -> {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                if (feedItem.message.isStreaming) {
                                                    LiveSummarizingCard(
                                                        modelName = summarizingModelName,
                                                        pendingQueuedMessage = pendingQueuedUserMessage
                                                    )
                                                } else {
                                                    ActiveContextSummaryCard(
                                                        summaryText = feedItem.message.content,
                                                        onEditSummary = { viewModel.updateSummaryMessage(feedItem.message.id, it) },
                                                        onDeleteSummary = { viewModel.deleteSummaryMessage(feedItem.message.id) }
                                                    )
                                                }
                                            }
                                        }
                                        is ChatFeedItem.User -> {
                                            val msgIndex = messages.indexOfFirst { it.id == feedItem.message.id }
                                            val willDeleteOutput = msgIndex < messages.lastIndex
                                            UserMessageBubble(
                                                message = feedItem.message,
                                                isLastUserMessage = true,
                                                isDevModeEnabled = isDevModeEnabled,
                                                onEdit = { targetMsg ->
                                                    if (willDeleteOutput) {
                                                        pendingMessageAction = PendingMessageAction(MessageActionType.EDIT, targetMsg)
                                                    } else {
                                                        viewModel.revertAndEditLastUserMessage(targetMsg) { restoredText ->
                                                            val tfv = TextFieldValue(restoredText, selection = TextRange(restoredText.length))
                                                            textFieldValue = tfv
                                                            viewModel.setDraft(activeConversationKey, tfv)
                                                        }
                                                    }
                                                },
                                                onViewRawPayload = { payloadJson ->
                                                    showRawPayloadDialog = payloadJson
                                                }
                                            )
                                        }
                                        is ChatFeedItem.AssistantThinking -> {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                                            ) {
                                                ThinkingAccordion(
                                                    thoughtText = feedItem.thoughtText,
                                                    durationMs = feedItem.durationMs,
                                                    isStreaming = feedItem.isStreaming
                                                )
                                            }
                                        }
                                        is ChatFeedItem.AssistantBlock -> {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                                            ) {
                                                val isTool = feedItem.block is MarkdownBlock.AgentTool
                                                MarkdownBlockView(
                                                    block = feedItem.block,
                                                    onApproveTool = if (isTool) { { toolCall -> viewModel.approveAndExecuteTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                    onRejectTool = if (isTool) { { toolCall -> if (toolCall.toolType == ToolType.ASK_CHOICE) viewModel.cancelUserChoices(toolCall, feedItem.messageId) else viewModel.rejectTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                    onTerminateTool = if (isTool) { { toolCall -> viewModel.terminateRunningTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                    onSubmitChoices = if (isTool) { { toolCall, responses, summaryPayload -> viewModel.submitUserChoices(toolCall, feedItem.messageId, responses, summaryPayload) } } else null,
                                                    onSkipChoices = if (isTool) { { toolCall, responses -> viewModel.skipUserChoices(toolCall, feedItem.messageId, responses) } } else null
                                                )
                                            }
                                        }
                                        is ChatFeedItem.AssistantTyping -> {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                            ) {
                                                ModelTypingIndicator(modelId = feedItem.modelId)
                                            }
                                        }
                                        is ChatFeedItem.AssistantFooter -> {
                                            AssistantMessageFooter(
                                                message = feedItem.message,
                                                isDevModeEnabled = isDevModeEnabled,
                                                onRetry = { targetMsg ->
                                                    viewModel.retryMessage(targetMsg.id)
                                                    userSentMessageTrigger++
                                                },
                                                onViewRawPayload = { payloadJson ->
                                                    showRawPayloadDialog = payloadJson
                                                },
                                                onShowArtifacts = { showArtifactsDrawer = true },
                                                artifactsCount = artifacts.size
                                            )
                                        }
                                        is ChatFeedItem.StreamingMessage -> {
                                            MessageBubble(
                                                message = feedItem.message,
                                                modelId = selectedModelId,
                                                isDevModeEnabled = isDevModeEnabled,
                                                onApproveTool = { toolCall, msgId ->
                                                    viewModel.approveAndExecuteTerminalTool(toolCall, msgId)
                                                },
                                                onRejectTool = { toolCall, msgId ->
                                                    if (toolCall.toolType == ToolType.ASK_CHOICE) {
                                                        viewModel.cancelUserChoices(toolCall, msgId)
                                                    } else {
                                                        viewModel.rejectTerminalTool(toolCall, msgId)
                                                    }
                                                },
                                                onTerminateTool = { toolCall, msgId ->
                                                    viewModel.terminateRunningTerminalTool(toolCall, msgId)
                                                },
                                                onSubmitChoices = { toolCall, msgId, responses, summaryPayload ->
                                                    viewModel.submitUserChoices(toolCall, msgId, responses, summaryPayload)
                                                },
                                                onSkipChoices = { toolCall, msgId, responses ->
                                                    viewModel.skipUserChoices(toolCall, msgId, responses)
                                                },
                                                summarizingModelName = summarizingModelName,
                                                pendingQueuedUserMessage = pendingQueuedUserMessage,
                                                onShowArtifacts = { showArtifactsDrawer = true },
                                                artifactsCount = artifacts.size
                                            )
                                        }
                                        }
                                    }

                                    // Bottom spacer to ensure scrolling reaches comfortably above the input box
                                    item(key = "bottom_anchor") {
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Floating Scroll Up / Scroll Down Button (Instant Movement)
                    val showUpArrow = showScrollButton && scrollDirection == ScrollDirection.UP && !isAtTop
                    val showDownArrow = showScrollButton && scrollDirection == ScrollDirection.DOWN && !isAtBottom
                    val isVisible = (showUpArrow || showDownArrow) && feedItems.size > 2
                                            
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn() + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + scaleOut(targetScale = 0.8f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable {
                                    showScrollButton = false
                                    scope.launch {
                                        if (showUpArrow) {
                                            listState.scrollToItem(0)
                                        } else {
                                            listState.scrollToItem(feedItems.size)
                                        }
                                    }
                                },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = if (showUpArrow) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (showUpArrow) "Jump to Top" else "Jump to Bottom",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Floating Slash Commands & Skills Autocomplete Suggestions
                    val slashIndex = inputText.lastIndexOf('/')
                    val isSlashCommand = slashIndex == 0 || (slashIndex > 0 && inputText.getOrNull(slashIndex - 1)?.isWhitespace() == true)
                    val isSlashActive = isSlashCommand && !inputText.substring(slashIndex).contains(" ")
                    val slashQuery = if (isSlashActive) inputText.substring(slashIndex + 1) else ""

                    val slashSuggestions = remember(slashQuery, allSlashCommands, isSlashActive) {
                        if (!isSlashActive || allSlashCommands.isEmpty()) emptyList()
                        else {
                            allSlashCommands.filter { item ->
                                slashQuery.isBlank() ||
                                item.name.contains(slashQuery, ignoreCase = true) ||
                                item.command.removePrefix("/").contains(slashQuery, ignoreCase = true) ||
                                item.description.contains(slashQuery, ignoreCase = true)
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = slashSuggestions.isNotEmpty(),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.4f)),
                            shadowElevation = 8.dp,
                            tonalElevation = 6.dp
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .padding(vertical = 4.dp)
                            ) {
                                items(slashSuggestions, key = { item -> "${item.type}_${item.command}" }) { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val before = inputText.substring(0, slashIndex)
                                                val newText = "$before${item.command} "
                                                val tfv = TextFieldValue(
                                                    text = newText,
                                                    selection = TextRange(newText.length)
                                                )
                                                textFieldValue = tfv
                                                viewModel.setDraft(activeConversationKey, tfv)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (item.type == "command") Icons.Default.Bolt else Icons.Default.Extension,
                                            contentDescription = null,
                                            tint = if (item.type == "command") ClaudeTerracotta else QuotaGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = item.command,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (item.type == "command") ClaudeTerracotta.copy(alpha = 0.15f) else QuotaGreen.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = (item.pluginName ?: item.type).uppercase(),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (item.type == "command") ClaudeTerracotta else QuotaGreen,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            if (item.description.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = item.description,
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Floating File Autocomplete Suggestions when user types @
                    val atIndex = inputText.lastIndexOf('@')
                    val isAtMentioning = atIndex >= 0 && (atIndex == inputText.length - 1 || !inputText.substring(atIndex + 1).contains(" "))
                    val atQuery = if (atIndex >= 0 && atIndex < inputText.length) inputText.substring(atIndex + 1) else ""

                    val fileSuggestions = remember(atQuery, chatProjectFiles, isAtMentioning) {
                        if (!isAtMentioning || chatProjectFiles.isEmpty()) emptyList()
                        else {
                            chatProjectFiles.filter { file ->
                                !file.isDir && (atQuery.isBlank() || file.name.contains(atQuery, ignoreCase = true) || file.path.contains(atQuery, ignoreCase = true))
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = fileSuggestions.isNotEmpty(),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            shadowElevation = 8.dp,
                            tonalElevation = 6.dp
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .padding(vertical = 4.dp)
                            ) {
                                items(fileSuggestions, key = { file -> file.path }) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val newText = inputText.substring(0, atIndex) + "@${file.path} "
                                                val tfv = TextFieldValue(
                                                    text = newText,
                                                    selection = TextRange(newText.length)
                                                )
                                                textFieldValue = tfv
                                                viewModel.setDraft(activeConversationKey, tfv)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = file.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = file.path,
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dynamic Bridge / Project / Model Initialization Banner
                AnimatedVisibility(
                    visible = !bridgeStatusMessage.isNullOrBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeTerracotta
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = bridgeStatusMessage ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Disconnection / Reconnecting Inline Banner (Compact, non-intrusive with Reconnect action)
                AnimatedVisibility(
                    visible = (isServerOnline == false || isReconnecting) && messages.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isReconnecting) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, if (isReconnecting) MaterialTheme.colorScheme.outline.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                if (isReconnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isReconnecting) "Connecting to Antigravity..." else "Antigravity Disconnected",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isReconnecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!isReconnecting) {
                                TextButton(
                                    onClick = { viewModel.retryConnections() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Reconnect",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                // Docked Inline Tool Permission Approval Panel (Row list directly above input bar)
                AnimatedVisibility(
                    visible = pendingApprovals.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ToolApprovalDockedPanel(
                        pendingApprovals = pendingApprovals,
                        onApprove = { toolCall, msgId, scope ->
                            viewModel.approveAndExecuteTerminalTool(toolCall, msgId, scope)
                        },
                        onReject = { toolCall, msgId ->
                            viewModel.rejectTerminalTool(toolCall, msgId)
                        },
                        onApproveAll = {
                            viewModel.approveAllPendingTools(pendingApprovals)
                        },
                        onRejectAll = {
                            viewModel.rejectAllPendingTools(pendingApprovals)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }

                // Chat Input Bar with Bottom Model & Thinking Selector Pills (Claude Android Style)
                ChatInputBar(
                    isOnline = systemConnectionState.canSend,
                    isAuth = systemConnectionState.isAuth,
                    selectedModel = currentModel,
                    quota = currentQuota,
                    thinkingPreference = thinkingPref,
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = {
                        textFieldValue = it
                        viewModel.setDraft(activeConversationKey, it)
                        if (it.text.isNotEmpty()) {
                            viewModel.onUserStartedTyping()
                        }
                    },
                    onOpenModelSelector = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        showModelSelector = true
                    },
                    onOpenThinkingSelector = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        showThinkingSelector = true
                    },
                    isStreaming = isStreaming,
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                        viewModel.clearDraft(activeConversationKey)
                        textFieldValue = TextFieldValue("")
                        userSentMessageTrigger++
                    },
                    onStopStreaming = { showStopConfirmDialog = true },
                    attachments = attachments,
                    isUploadingAttachment = isUploadingAttachment,
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAddAttachment = { viewModel.addAttachment(it) },
                    onAttachClick = { showAttachmentSelector = true },
                    onTranscribeAudioFile = { file, onDone, onError ->
                        viewModel.transcribeAudioFile(file, onDone, onError)
                    },
                    isTranscribingAudio = isTranscribingAudio,
                    speechManager = viewModel.speechManager,
                    cascadeId = activeConversationKey
                )
            }
        }
    }

    // Model Selector Bottom Sheet (Active Model at Top & Expandable Categories)
    if (showModelSelector) {
        ModelSelectorBottomSheet(
            selectedModelId = selectedModelId,
            availableModels = enabledModels,
            quotas = quotas,
            quotaSummary = quotaSummary,
            isRefreshing = isRefreshingModels,
            onRefresh = { viewModel.refreshQuotas(force = true, showToastFeedback = true) },
            onSelectModel = { modelId -> viewModel.selectModel(modelId) },
            onDismiss = { showModelSelector = false }
        )
    }

    // Thinking Selector Bottom Sheet
    if (showThinkingSelector) {
        ThinkingSelectorBottomSheet(
            currentPreference = thinkingPref,
            onPreferenceSelected = { pref -> viewModel.setThinkingPreference(pref) },
            onDismiss = { showThinkingSelector = false }
        )
    }

    // Settings Full-Screen Animated Overlay
    AnimatedVisibility(
        visible = showSettingsDialog,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(320, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(250)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(280, easing = FastOutLinearInEasing)
        ) + fadeOut(animationSpec = tween(200)),
        modifier = Modifier.fillMaxSize()
    ) {
        SettingsDialog(
            userEmail = userEmail,
            projectId = projectId,
            tier = tier,
            availableModels = availableModels,
            enabledModelIds = enabledModelIds,
            quotas = quotas,
            isServerListening = isOAuthServerListening,
            isServerLoading = isOAuthServerLoading,
            contextWindowLimit = contextWindowLimit,
            summaryModelId = summaryModelIdPref,
            isDevModeEnabled = isDevModeEnabled,
            chatFontScale = chatFontScale,
            themeMode = themeMode,
            agyHubUrl = agyHubUrl,
            agyBridgeHttpUrl = agyBridgeHttpUrl,
            isServerOnline = isServerOnline == true,
            isBridgeOnline = isBridgeOnline == true,
            useSshTerminal = useSshTerminal,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUser = sshUser,
            sshPass = sshPass,
            terminalFontSize = terminalFontSize,
            terminalCursorStyle = terminalCursorStyle,
            terminalBufferSize = terminalBufferSize,
            terminalTheme = terminalTheme,
            isLocalToolsEnabled = isLocalToolsEnabled,
            isLocalToolsInstalled = isLocalToolsInstalled,
            onLoginWithGoogle = {
                val url = viewModel.getGoogleOAuthUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            },
            onToggleServer = { enable ->
                viewModel.toggleOAuthServer(enable)
            },
            onLogout = {
                viewModel.logout()
            },
            onManualTokenEntered = { token ->
                viewModel.applyManualInput(token)
            },
            onToggleModelEnabled = { modelId, isEnabled ->
                viewModel.setModelEnabled(modelId, isEnabled)
            },
            onEnableAllModels = {
                viewModel.enableAllModels()
            },
            onRefreshQuotas = {
                viewModel.refreshQuotas(force = true, showToastFeedback = true)
            },
            onSetContextWindowLimit = { viewModel.setContextWindowLimit(it) },
            onSetSummaryModelId = { viewModel.setSummaryModelId(it) },
            onSetChatFontScale = { viewModel.setChatFontScale(it) },
            onSetThemeMode = { viewModel.setThemeMode(it) },
            onSaveServerUrls = { hub, bridge -> viewModel.saveServerUrls(hub, bridge) },
            onSaveTerminalPreferences = { size, cursor, buf, thm -> viewModel.saveTerminalPreferences(size, cursor, buf, thm) },
            onToggleDevMode = { viewModel.setDevModeEnabled(it) },
            onToggleUseSshTerminal = { viewModel.setUseSshTerminal(it) },
            onSaveSshSettings = { h, p, u, pass -> viewModel.saveSshSettings(h, p, u, pass) },
            onToggleLocalTools = { viewModel.setLocalToolsEnabled(it) },
            onInstallLocalTools = { showLocalToolsInstallDialog = true },
            onOpenLocalTerminal = onNavigateToTerminal,
            onResetLocalTools = {
                LocalEnvironmentManager.launchReset(context) {
                    viewModel.setLocalToolsEnabled(false)
                    scope.launch { viewModel.authPreferences.setLocalToolsInstalled(false) }
                }
            },
            mcpServers = mcpServers,
            isMcpLoading = isMcpLoading,
            isMcpRefreshing = isMcpRefreshing,
            refreshingMcpServer = refreshingMcpServer,
            mcpErrorMessage = mcpErrorMessage,
            mcpStatusMessage = mcpStatusMessage,
            onClearMcpStatus = { viewModel.clearMcpStatus() },
            onRefreshMcpServers = { viewModel.refreshMcpServers() },
            onRefreshMcpServer = { name -> viewModel.refreshMcpServers(name) },
            onToggleMcpServer = { name, enabled -> viewModel.toggleMcpServer(name, enabled) },
            onSaveMcpServer = { spec, rawJson -> viewModel.saveMcpServer(spec, rawJson) },
            onDeleteMcpServer = { name -> viewModel.deleteMcpServer(name) },
            availableCascadePlugins = availableCascadePlugins,
            isCascadePluginsLoading = isCascadePluginsLoading,
            installingCascadePluginId = installingCascadePluginId,
            onSearchCascadePlugins = { q -> viewModel.loadAvailableCascadePlugins(q) },
            onInstallCascadePlugin = { p -> viewModel.installCascadeMcpPlugin(p) },
            allSkills = allSkills,
            isSkillsLoading = isSkillsLoading,
            skillsFilterScope = skillsFilterScope,
            onSetSkillsFilterScope = { s -> viewModel.setSkillsFilterScope(s) },
            onRefreshSkills = { viewModel.loadAllSkills() },
            installedPlugins = installedPlugins,
            isInstalledPluginsLoading = isInstalledPluginsLoading,
            onRefreshInstalledPlugins = { viewModel.loadAllInstalledPlugins() },
            googlePluginsCatalog = googlePluginsCatalog,
            isGooglePluginsLoading = isGooglePluginsLoading,
            installingGooglePluginId = installingGooglePluginId,
            deletingPluginId = deletingPluginId,
            onRefreshGooglePlugins = { viewModel.loadGooglePluginsCatalog() },
            onInstallGooglePlugin = { id, name -> viewModel.installGooglePlugin(id, name) },
            onDeletePlugin = { id, name -> viewModel.deleteInstalledPlugin(id, name) },
            pluginActionStatusMessage = pluginActionStatusMessage,
            pluginActionErrorMessage = pluginActionErrorMessage,
            onClearPluginStatus = { viewModel.clearPluginActionStatus() },
            isBrowserAutomationEnabled = isBrowserAutomationEnabled,
            isTerminalAutomationEnabled = isTerminalAutomationEnabled,
            onToggleBrowserAutomation = { viewModel.setBrowserAutomationEnabled(it) },
            onToggleTerminalAutomation = { viewModel.setTerminalAutomationEnabled(it) },
            isFloatingSwitcherEnabled = isFloatingSwitcherEnabled,
            floatingSwitcherOrientation = floatingSwitcherOrientation,
            floatingSwitcherItems = floatingSwitcherItems,
            floatingSwitcherAutoCollapseSec = floatingSwitcherAutoCollapseSec,
            onToggleFloatingSwitcher = { viewModel.setFloatingSwitcherEnabled(it) },
            onSetFloatingSwitcherOrientation = { viewModel.setFloatingSwitcherOrientation(it) },
            onSetFloatingSwitcherItems = { viewModel.setFloatingSwitcherItems(it) },
            onSetFloatingSwitcherAutoCollapseSec = { viewModel.setFloatingSwitcherAutoCollapseSec(it) },
            onResetFloatingSwitcherPosition = { viewModel.resetFloatingSwitcherPosition() },
            commandAutoExecutionPolicy = commandAutoExecutionPolicy,
            commandSandboxEnabled = commandSandboxEnabled,
            requireApprovalForFileEdits = requireApprovalForFileEdits,
            defaultApprovalScope = defaultApprovalScope,
            globalSecuritySettings = globalSecuritySettings,
            globalSettingsError = globalSettingsError,
            isGlobalSettingsLoading = isGlobalSettingsLoading,
            projectsList = projectsList,
            isProjectsLoading = isProjectsLoading,
            onSetGlobalArtifactReviewMode = { viewModel.updateGlobalArtifactReviewMode(it) },
            onSetGlobalSecurityPreset = { autoExec, fileAccess -> viewModel.updateGlobalSecurityPreset(autoExec, fileAccess) },
            onSetGlobalCustomTerminalPolicy = { viewModel.updateGlobalCustomTerminalPolicy(it) },
            onSetGlobalCustomFileAccessPolicy = { viewModel.updateGlobalCustomFileAccessPolicy(it) },
            onSetGlobalTerminalSandbox = { viewModel.updateGlobalTerminalSandbox(it) },
            onSetProjectInheritGlobal = { viewModel.setProjectInheritGlobal(it) },
            onSetProjectPreset = { proj, autoExec, fileAccess -> viewModel.updateProjectPreset(proj, autoExec, fileAccess) },
            onRefreshSecurityAndProjects = { viewModel.loadSecurityAndProjectSettings() },
            onSetCommandAutoExecutionPolicy = { viewModel.setCommandAutoExecutionPolicy(it) },
            onSetCommandSandboxEnabled = { viewModel.setCommandSandboxEnabled(it) },
            onSetRequireApprovalForFileEdits = { viewModel.setRequireApprovalForFileEdits(it) },
            onSetDefaultApprovalScope = { viewModel.setDefaultApprovalScope(it) },
            onAddPermissionRule = { action, pattern, decision -> viewModel.addGlobalPermissionGrant(action, pattern, decision) },
            onRemovePermissionRule = { rawRule -> viewModel.removeGlobalPermissionGrant(rawRule) },
            onChangePermissionRuleDecision = { rawRule, newDecision -> viewModel.changeGlobalPermissionGrantDecision(rawRule, newDecision) },
            groupChatsByWorkspace = groupChatsByWorkspace,
            onToggleGroupChatsByWorkspace = { viewModel.setGroupChatsByWorkspace(it) },
            isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
            onToggleFloatingDiagnostics = { viewModel.setFloatingDiagnosticsEnabled(it) },
            isNetworkInspectorEnabled = isNetworkInspectorEnabled,
            onToggleNetworkInspector = { viewModel.setNetworkInspectorEnabled(it) },
            isFloatingNetworkInspectorEnabled = isFloatingNetworkInspectorEnabled,
            onToggleFloatingNetworkInspector = { viewModel.setFloatingNetworkInspectorEnabled(it) },
            onOpenNetworkInspector = { showNetworkInspectorDialog = true },
            onDismiss = { showSettingsDialog = false }
        )
    }



    // Confirmation Dialog for Edit / Retry when deleting subsequent output
    pendingMessageAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingMessageAction = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = if (action.type == MessageActionType.EDIT) "Edit Message?" else "Regenerate Response?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = if (action.type == MessageActionType.EDIT) {
                        "Editing this message will delete the subsequent response so you can edit and send a fresh query. Do you want to continue?"
                    } else {
                        "Retrying will delete the current response and regenerate a fresh answer. Do you want to continue?"
                    },
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = action.message
                        if (action.type == MessageActionType.EDIT) {
                            viewModel.revertAndEditLastUserMessage(target) { restoredText ->
                                val tfv = TextFieldValue(restoredText, selection = TextRange(restoredText.length))
                                textFieldValue = tfv
                                viewModel.setDraft(activeConversationKey, tfv)
                            }
                        } else {
                            viewModel.retryMessage(target.id)
                            userSentMessageTrigger++
                        }
                        pendingMessageAction = null
                    }
                ) {
                    Text(
                        text = if (action.type == MessageActionType.EDIT) "Edit & Delete" else "Regenerate",
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMessageAction = null }) {
                    Text(text = "Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        )
    }

    // Confirmation Dialog before stopping active model generation
    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop Generation?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.5.sp
                    )
                }
            },
            text = {
                Text(
                    text = "Do you want to stop the model from outputting? The current generation will be stopped immediately.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        viewModel.stopStreaming()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Stop Output",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) {
                    Text(text = "Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        )
    }

    // Command Terminated Action Dialog (When user clicks Stop during command execution)
    terminatedToolDialogState?.let { (toolCall, msgId) ->
        var userFeedback by remember(toolCall.id) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { viewModel.dismissTerminatedToolDialog() },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Command Terminated",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "You stopped the running command:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D0E15),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$ ${toolCall.command}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ClaudeTerracotta,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Explain why or give new instructions to AI (optional):",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = userFeedback,
                        onValueChange = { userFeedback = it },
                        placeholder = { Text("e.g. It was taking too long, please use grep or find a faster way...", fontSize = 12.5.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.proceedAfterTermination(toolCall, msgId, userFeedback)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Send to AI & Proceed", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissTerminatedToolDialog() }
                ) {
                    Text(text = "Stop Turn Here", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.5.sp)
                }
            }
        )
    }




    // Termux Terminal Inspector Dialog
    if (showTerminalInspector) {
        TerminalInspectorDialog(
            authPreferences = viewModel.authPreferences,
            onDismiss = { showTerminalInspector = false }
        )
    }



    // Fully Working Local Terminal (Termux Shell with close button above)
    if (showLocalTerminalDialog) {
        if (isInFloatingWindow) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f),
                color = MaterialTheme.colorScheme.background
            ) {
                LocalTerminalContent(
                    onClose = { showLocalTerminalDialog = false }
                )
            }
        } else {
            LocalTerminalDialog(
                onDismiss = { showLocalTerminalDialog = false }
            )
        }
    }

    // Local Server Output & Logs Dialog
    if (showLocalServerOutputDialog) {
        com.example.gemini.ui.components.LocalServerOutputDialog(
            onDismiss = { showLocalServerOutputDialog = false }
        )
    }

    // Local Tools Setup & Progress Dialog
    if (showLocalToolsInstallDialog) {
        LocalToolsInstallDialog(
            authPreferences = viewModel.authPreferences,
            onOpenTerminal = onNavigateToTerminal,
            onDismiss = { showLocalToolsInstallDialog = false }
        )
    }

    // Raw Request Payload Inspector Dialog (Developer Mode)
    if (showRawPayloadDialog != null) {
        RawPayloadDialog(
            payloadJson = showRawPayloadDialog!!,
            onDismiss = { showRawPayloadDialog = null }
        )
    }

    // Terminal Launcher First-Launch Onboarding Dialog
    if (!hasSeenTerminalLauncherOnboarding && !isInFloatingWindow) {
        com.example.gemini.ui.onboarding.TerminalLauncherOnboardingDialog(
            initialEnabled = remember(context) { com.example.gemini.data.preferences.TerminalLauncherManager.isLauncherEnabled(context) },
            initialStyle = remember(context) { com.example.gemini.data.preferences.TerminalLauncherManager.getLauncherStyle(context) },
            onConfirm = { enabled, style ->
                scope.launch {
                    viewModel.authPreferences.setTerminalLauncherEnabled(enabled)
                    viewModel.authPreferences.setTerminalLauncherStyle(style)
                    viewModel.authPreferences.setHasSeenTerminalLauncherOnboarding(true)
                    com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                        context = context,
                        enabled = enabled,
                        style = style
                    )
                }
            },
            onDismiss = {
                scope.launch {
                    viewModel.authPreferences.setHasSeenTerminalLauncherOnboarding(true)
                }
            }
        )
    }



    // Attachment Selector Bottom Sheet
    if (showAttachmentSelector) {
        AttachmentSelectorBottomSheet(
            onPickImage = { photoPickerLauncher.launch("image/*") },
            onPickFile = { filePickerLauncher.launch("*/*") },
            onDismiss = { showAttachmentSelector = false }
        )
    }

    // Chat Telemetry & Tokens Dialog (Developer Mode)
    if (showChatTelemetryDialog) {
        ChatTelemetryDialog(
            conversation = currentConv,
            messages = messages,
            onDismiss = { showChatTelemetryDialog = false }
        )
    }

    // Markdown Document Fullscreen Viewer Modal (.md files)
    activeMarkdownDoc?.let { (docPath, docContent) ->
        MarkdownDocViewerModal(
            filePath = docPath,
            content = docContent,
            onDismiss = { activeMarkdownDoc = null },
            onOpenInIde = { path ->
                activeMarkdownDoc = null
                scope.launch {
                    val content = try { File(path).readText() } catch (e: Exception) { docContent }
                    TermuxDaemonManager.openOrSelectTab(path, File(path).name, content)
                    onNavigateToIde()
                }
            }
        )
    }

    // External / Shared Incoming Markdown Document Viewer
    incomingMarkdownPreview?.let { (docPath, docContent) ->
        MarkdownDocViewerModal(
            filePath = docPath,
            content = docContent,
            onDismiss = { viewModel.dismissIncomingMarkdownPreview() },
            onOpenInIde = { path ->
                viewModel.dismissIncomingMarkdownPreview()
                scope.launch {
                    val content = try { File(path).readText() } catch (e: Exception) { docContent }
                    TermuxDaemonManager.openOrSelectTab(path, File(path).name, content)
                    onNavigateToIde()
                }
            }
        )
    }

    // Shared Conversation Viewer Modal (.antigem / .jsonl shared chats)
    sharedConversationPreview?.let { previewData ->
        SharedChatViewerModal(
            data = previewData,
            onDismiss = { viewModel.dismissSharedConversation() }
        )
    }

    if (isSharedConversationLoading) {
        SharedChatLoadingDialog(
            onCancel = { viewModel.cancelSharedConversationLoading() }
        )
    }

    // File Details & Quick Action Dialog (Long press / metadata)
    activeFileDetailsPath?.let { path ->
        FileDetailsDialog(
            filePath = path,
            onDismiss = { activeFileDetailsPath = null },
            onOpenInIde = { filePath ->
                activeFileDetailsPath = null
                scope.launch {
                    val content = try { File(filePath).readText() } catch (e: Exception) { IdeApiClient.readFile(filePath) ?: "" }
                    TermuxDaemonManager.openOrSelectTab(filePath, File(filePath).name, content)
                    onNavigateToIde()
                }
            },
            onOpenMarkdownViewer = { filePath ->
                activeFileDetailsPath = null
                scope.launch {
                    val content = try { File(filePath).readText() } catch (e: Exception) { IdeApiClient.readFile(filePath) ?: "" }
                    activeMarkdownDoc = filePath to content
                }
            }
        )
    }

    if (showProjectPickerDialog) {
        val currentConvPath = currentConv?.workspaceUri?.removePrefix("file://")?.trimEnd('/') ?: ""
        val activeProj = if (currentConvPath.isNotBlank()) {
            usedProjects.find { it.path == currentConvPath } ?: ProjectItem(File(currentConvPath).name, currentConvPath)
        } else null

        ProjectPickerDialog(
            projects = usedProjects,
            activeProject = activeProj,
            onSelectProject = { proj ->
                TermuxDaemonManager.setActiveProject(proj)
                viewModel.onProjectChanged(proj?.path ?: "")
                showProjectPickerDialog = false
            },
            onBrowseFolder = {
                showProjectPickerDialog = false
                showWorkspaceFolderBrowserDialog = true
            },
            onRefresh = {
                scope.launch {
                    TermuxDaemonManager.loadProjects(conversations)
                }
            },
            onDismiss = { showProjectPickerDialog = false }
        )
    }

    if (showWorkspaceFolderBrowserDialog) {
        FileManagerDialog(
            initialPath = "~",
            onOpenAsProject = { selectedProj ->
                showWorkspaceFolderBrowserDialog = false
                TermuxDaemonManager.setActiveProject(selectedProj)
                viewModel.onProjectChanged(selectedProj.path)
            },
            onDismiss = { showWorkspaceFolderBrowserDialog = false }
        )
    }

    // Right-side Artifacts Drawer Modal Overlay (Opened via topbar/message button, 0 gesture conflicts, standard LTR)
    AnimatedVisibility(
        visible = showArtifactsDrawer,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(90000f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showArtifactsDrawer = false }
        ) {
            AnimatedVisibility(
                visible = showArtifactsDrawer,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Absorb clicks */ }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 16.dp
                ) {
                    ArtifactsDrawerContent(
                        artifacts = artifacts,
                        onClose = { showArtifactsDrawer = false }
                    )
                }
            }
        }
    }

    // Bottom-Level Floating Toast Banner (Renders above all drawers, sidebar, dialogs, and scaffolds)
    AnimatedVisibility(
        visible = currentToast != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .zIndex(99999f)
    ) {
        currentToast?.let { toast ->
            ChatToastBanner(
                toast = toast,
                onDismiss = { currentToast = null },
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }

    // Bottom-Level SnackbarHost (Renders above all drawers, sidebar, dialogs, and scaffolds with Open button)
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .zIndex(99999f)
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = ClaudeTerracotta,
            actionContentColor = ClaudeTerracotta,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Network Inspector Dialog (DevTools from Settings)
    if (showNetworkInspectorDialog) {
        com.example.gemini.ui.components.NetworkInspectorDialog(
            onDismiss = { showNetworkInspectorDialog = false }
        )
    }
    } // End of Box
    } // End of CompositionLocalProvider
} // End of fun ChatScreen

@Composable
fun ChatToastBanner(
    toast: ChatToast,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when (toast.type) {
        ChatToastType.SUCCESS -> Color(0xFF143324)
        ChatToastType.ERROR -> Color(0xFF381919)
        ChatToastType.INFO -> Color(0xFF1F222A)
    }
    val borderColor = when (toast.type) {
        ChatToastType.SUCCESS -> Color(0xFF34D399).copy(alpha = 0.7f)
        ChatToastType.ERROR -> Color(0xFFF87171).copy(alpha = 0.7f)
        ChatToastType.INFO -> ClaudeTerracotta.copy(alpha = 0.7f)
    }
    val iconColor = when (toast.type) {
        ChatToastType.SUCCESS -> Color(0xFF34D399)
        ChatToastType.ERROR -> Color(0xFFF87171)
        ChatToastType.INFO -> ClaudeTerracotta
    }
    val icon = when (toast.type) {
        ChatToastType.SUCCESS -> Icons.Outlined.CheckCircle
        ChatToastType.ERROR -> Icons.Outlined.ErrorOutline
        ChatToastType.INFO -> Icons.Outlined.Info
    }
    val textColor = Color(0xFFF9FAFB)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = toast.message,
                color = textColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

enum class MessageActionType { EDIT, RETRY }
data class PendingMessageAction(val type: MessageActionType, val message: ChatMessage)

private fun flattenFileNodes(nodes: List<com.example.gemini.data.daemon.FileNode>): List<com.example.gemini.data.daemon.FileNode> {
    val result = mutableListOf<com.example.gemini.data.daemon.FileNode>()
    for (node in nodes) {
        if (!node.isDir) {
            result.add(node)
        }
        if (node.children.isNotEmpty()) {
            result.addAll(flattenFileNodes(node.children))
        }
    }
    return result
}

