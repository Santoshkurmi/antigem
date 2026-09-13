package com.example.gemini.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.example.gemini.ui.settings.LocalToolsInstallDialog
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.ui.drawer.ChatHistoryDrawer
import com.example.gemini.ui.models.ModelSelectorBottomSheet
import com.example.gemini.ui.models.ThinkingSelectorBottomSheet
import com.example.gemini.ui.components.ToolApprovalDialog
import com.example.gemini.ui.components.ToolApprovalDockedPanel
import com.example.gemini.ui.settings.SettingsDialog
import android.util.Log
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
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

enum class ScrollDirection { UP, DOWN }



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigateToIde: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForegrounded()
                scope.launch {
                    TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
                }
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
    val isDevModeEnabled by viewModel.isDevModeEnabled.collectAsState()
    val chatFontScale by viewModel.chatFontScale.collectAsState(initial = 1.0f)
    val bridgeStatusMessage by viewModel.bridgeStatusMessage.collectAsState()
    val isServerOnline by viewModel.isServerOnline.collectAsState()
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
    val themeMode by viewModel.themeMode.collectAsState(initial = "SYSTEM")
    val agyHubUrl by viewModel.agyHubUrl.collectAsState(initial = "http://127.0.0.1:8090")
    val agyBridgeHttpUrl by viewModel.agyBridgeHttpUrl.collectAsState(initial = "http://127.0.0.1:8080")
    val terminalFontSize by viewModel.terminalFontSize.collectAsState(initial = 13)
    val terminalCursorStyle by viewModel.terminalCursorStyle.collectAsState(initial = "BLOCK")
    val terminalBufferSize by viewModel.terminalBufferSize.collectAsState(initial = 2000)
    val terminalTheme by viewModel.terminalTheme.collectAsState(initial = "DEFAULT")
    val commandAutoExecutionPolicy by viewModel.commandAutoExecutionPolicy.collectAsState(initial = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER")
    val commandSandboxEnabled by viewModel.commandSandboxEnabled.collectAsState(initial = false)
    val requireApprovalForFileEdits by viewModel.requireApprovalForFileEdits.collectAsState(initial = false)
    val defaultApprovalScope by viewModel.defaultApprovalScope.collectAsState(initial = "PERMISSION_SCOPE_ONCE")
    val globalSecuritySettings by viewModel.globalSecuritySettings.collectAsState()
    val isGlobalSettingsLoading by viewModel.isGlobalSettingsLoading.collectAsState()
    val projectsList by viewModel.projectsList.collectAsState()
    val isProjectsLoading by viewModel.isProjectsLoading.collectAsState()

    var showModelSelector by remember { mutableStateOf(false) }
    var showThinkingSelector by remember { mutableStateOf(false) }
    var showTerminalInspector by remember { mutableStateOf(false) }
    var showLocalTerminalDialog by remember { mutableStateOf(false) }
    var showLocalToolsInstallDialog by remember { mutableStateOf(false) }
    var showRawPayloadDialog by remember { mutableStateOf<String?>(null) }
    var showChatTelemetryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAttachmentSelector by remember { mutableStateOf(false) }

    LaunchedEffect(showSettingsDialog) {
        if (showSettingsDialog) {
            viewModel.loadSecurityAndProjectSettings()
        }
    }

    val pendingApprovals by viewModel.pendingApprovals.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(conversationError) {
        val err = conversationError
        if (!err.isNullOrBlank()) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = err,
                duration = SnackbarDuration.Long,
                withDismissAction = true
            )
        }
    }

    val agyAuthInfo by viewModel.agyAuthInfo.collectAsState()
    val isAuthBusy by viewModel.isAuthBusy.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.authFeedbackMessage.collect { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
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
        }
    }
    val inputText = textFieldValue.text
    var pendingMessageAction by remember { mutableStateOf<PendingMessageAction?>(null) }
    val thinkingPref by viewModel.thinkingPreference.collectAsState()
    val terminatedToolDialogState by viewModel.terminatedToolDialog.collectAsState()
    val isOAuthServerListening by viewModel.isOAuthServerListening.collectAsState()
    val isOAuthServerLoading by viewModel.isOAuthServerLoading.collectAsState()
    val isLoadingConversation by viewModel.isLoadingConversation.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val isMcpLoading by viewModel.isMcpLoading.collectAsState()
    val isMcpRefreshing by viewModel.isMcpRefreshing.collectAsState()
    val refreshingMcpServer by viewModel.refreshingMcpServer.collectAsState()
    val mcpErrorMessage by viewModel.mcpErrorMessage.collectAsState()
    val mcpStatusMessage by viewModel.mcpStatusMessage.collectAsState()

    // Granular block-level feed item expansion from pre-warmed background cache (0ms UI thread work)
    val feedItems = remember(messages, selectedModelId) {
        ChatFeedCache.buildFeedItems(messages, selectedModelId)
    }

    // Fresh LazyListState per conversation — initialize directly at bottom so item 0 is NEVER composed
    val convKey = currentConv?.id ?: "empty"
    val initialBottomIndex = remember(convKey, isLoadingConversation) {
        if (!isLoadingConversation && feedItems.isNotEmpty()) {
            feedItems.size - 1
        } else 0
    }
    val listState = remember(convKey, isLoadingConversation) {
        LazyListState(firstVisibleItemIndex = initialBottomIndex)
    }
    var lastScrolledConvId by remember { mutableStateOf<String?>(null) }
    var lastScrolledMessageCount by remember { mutableStateOf(-1) }

    // Determine whether user is scrolled near the bottom (within the last item)
    val isAtBottom by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) true
            else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                lastVisibleItem != null && lastVisibleItem.index >= totalItems - 2
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
    var shouldAutoScroll by remember { mutableStateOf(true) }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var isKeyboardAnimating by remember { mutableStateOf(false) }

    // Synchronized Chat & Keyboard movement:
    // When keyboard rises, scroll list up in lockstep with the rising input bar so messages above it stay visible.
    // When keyboard hides, Compose & Android handle layout expansion natively.
    LaunchedEffect(imeInsets, density, listState) {
        var previousIme = imeInsets.getBottom(density)

        snapshotFlow { imeInsets.getBottom(density) }
            .collect { currentIme ->
                val delta = currentIme - previousIme
                if (delta > 0 && feedItems.isNotEmpty()) {
                    isKeyboardAnimating = true
                    // Keyboard is rising / opening
                    listState.scrollBy(delta.toFloat())
                }
                if (currentIme == 0) {
                    isKeyboardAnimating = false
                }
                previousIme = currentIme
            }
    }

    // Decoupled asynchronous scroll observer - zero recomposition during pixel scroll
    LaunchedEffect(listState) {
        var prevIdx = 0
        var prevOff = 0
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
                if (newDir == ScrollDirection.UP) {
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

    var userSentMessageTrigger by remember { mutableStateOf(0) }

    // Always scroll to very bottom when a conversation is opened or loaded
    LaunchedEffect(currentConv?.id, messages.size, feedItems.size) {
        val convId = currentConv?.id
        if (convId != null && feedItems.isNotEmpty()) {
            if (lastScrolledConvId != convId || lastScrolledMessageCount != messages.size) {
                lastScrolledConvId = convId
                lastScrolledMessageCount = messages.size
                shouldAutoScroll = true
                if (listState.firstVisibleItemIndex < feedItems.size - 2) {
                    listState.scrollToItem(maxOf(0, feedItems.size - 1))
                }
            }
        }
    }

    // Scroll to bottom when user explicitly sends a message (instant)
    LaunchedEffect(userSentMessageTrigger) {
        if (userSentMessageTrigger > 0 && feedItems.isNotEmpty()) {
            shouldAutoScroll = true
            listState.scrollToItem(maxOf(0, feedItems.size - 1))
        }
    }

    // Smart auto-scroll during streaming: follows live stream smoothly
    val lastMsg = messages.lastOrNull()
    val lastContentLen = lastMsg?.content?.length ?: 0
    val lastThoughtLen = lastMsg?.thoughtText?.length ?: 0
    val contentBucket = (lastContentLen + lastThoughtLen) / 50

    LaunchedEffect(feedItems.size, contentBucket, isStreaming) {
        if (feedItems.isNotEmpty() && isStreaming && shouldAutoScroll && !listState.isScrollInProgress) {
            listState.scrollToItem(maxOf(0, feedItems.size - 1))
        }
    }

    val currentModel = AiModel.findInList(enabledModels, selectedModelId, preferredModelName)
    val currentQuota = quotas.find { it.modelId == selectedModelId }

    val activeChatProject by TermuxDaemonManager.activeProject.collectAsState()
    var chatProjectsList by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var showProjectDropdown by remember { mutableStateOf(false) }
    var chatProjectFiles by remember { mutableStateOf<List<com.example.gemini.data.daemon.FileNode>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch {
            var list = IdeApiClient.getProjects()
            if (list.isEmpty()) {
                val httpUrl = viewModel.authPreferences.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val res = com.example.gemini.data.remote.AgyBridgeService().fetchProjects(httpUrl)
                if (res.isSuccess) {
                    list = res.getOrThrow().map { ProjectItem(it.name, it.path) }
                }
            }
            chatProjectsList = list
            if (activeChatProject == null && list.isNotEmpty()) {
                TermuxDaemonManager.setActiveProject(list.first())
            }
        }
    }

    LaunchedEffect(Unit) {
        TermuxDaemonManager.serverReconnectedEvent.collect {
            var list = IdeApiClient.getProjects()
            if (list.isEmpty()) {
                val httpUrl = viewModel.authPreferences.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                val res = com.example.gemini.data.remote.AgyBridgeService().fetchProjects(httpUrl)
                if (res.isSuccess) {
                    list = res.getOrThrow().map { ProjectItem(it.name, it.path) }
                }
            }
            if (list.isNotEmpty()) {
                chatProjectsList = list
                if (activeChatProject == null) {
                    TermuxDaemonManager.setActiveProject(list.first())
                }
            }
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

    val fileLinkHandler = remember(scope) {
        FileLinkHandler(
            onOpenFile = { rawUrl ->
                val cleanPath = rawUrl.removePrefix("file://").substringBefore("#")
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
                        val fileName = File(cleanPath).name
                        TermuxDaemonManager.openOrSelectTab(cleanPath, fileName, content)
                        onNavigateToIde()
                    }
                }
            },
            onShowDetails = { rawUrl ->
                val cleanPath = rawUrl.removePrefix("file://").substringBefore("#")
                activeFileDetailsPath = cleanPath
            }
        )
    }

    CompositionLocalProvider(LocalFileLinkHandler provides fileLinkHandler) {
        ModalNavigationDrawer(
            drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = conversations,
                currentConversationId = currentConv?.id,
                activeInstances = activeInstances,
                isLoading = isLoadingConversation,
                errorMessage = conversationError,
                isStreaming = isStreaming,
                onRetry = { viewModel.retryConnections() },
                isOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open,
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                },
                onForkConversation = { id ->
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
                    viewModel.loginToAgyHub()
                },
                onLogout = {
                    viewModel.logoutFromAgyHub()
                },
                onOpenSettings = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentConv?.title?.takeIf { it.isNotBlank() } ?: "Antigravity Chat",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
                                                var list = IdeApiClient.getProjects()
                                                if (list.isEmpty()) {
                                                    val httpUrl = viewModel.authPreferences.agyBridgeHttpUrl.firstOrNull() ?: "http://127.0.0.1:8080"
                                                    val res = com.example.gemini.data.remote.AgyBridgeService().fetchProjects(httpUrl)
                                                    if (res.isSuccess) {
                                                        list = res.getOrThrow().map { ProjectItem(it.name, it.path) }
                                                    }
                                                }
                                                chatProjectsList = list
                                                showProjectDropdown = true
                                            }
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
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = activeChatProject?.name ?: "Select Project",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showProjectDropdown,
                                        onDismissRequest = { showProjectDropdown = false }
                                    ) {
                                        if (chatProjectsList.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("No projects found in Termux") },
                                                onClick = { showProjectDropdown = false }
                                            )
                                        } else {
                                            chatProjectsList.forEach { proj ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = proj.name,
                                                            color = if (proj.path == activeChatProject?.path) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    },
                                                    onClick = {
                                                        TermuxDaemonManager.setActiveProject(proj)
                                                        showProjectDropdown = false
                                                        viewModel.onProjectChanged(proj.path)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Drawer",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (useSshTerminal) {
                                showLocalTerminalDialog = true
                            } else if (isLocalToolsInstalled && isLocalToolsEnabled) {
                                showLocalTerminalDialog = true
                            } else if (isLocalToolsInstalled) {
                                viewModel.setLocalToolsEnabled(true)
                                showLocalTerminalDialog = true
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
                    if (isLoadingConversation) {
                        com.example.gemini.ui.components.ConversationLoadingSkeleton()
                    } else if (!conversationError.isNullOrBlank() && messages.isEmpty()) {
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = conversationError ?: "",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    viewModel.retryConnections()
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
                    } else if (messages.isEmpty()) {
                        // Empty state
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "How can I help you today?",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Powered by Google Antigravity CloudCode",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
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
                                        val isLastUserMsg = messages.indexOfLast { it.role == MessageRole.USER } == msgIndex
                                        val willDeleteOutput = isLastUserMsg && msgIndex < messages.lastIndex
                                        UserMessageBubble(
                                            message = feedItem.message,
                                            isLastUserMessage = isLastUserMsg,
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
                                                .padding(horizontal = 16.dp, vertical = 1.dp)
                                        ) {
                                            val isTool = feedItem.block is MarkdownBlock.AgentTool
                                            MarkdownBlockView(
                                                block = feedItem.block,
                                                onApproveTool = if (isTool) { { toolCall -> viewModel.approveAndExecuteTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                onRejectTool = if (isTool) { { toolCall -> viewModel.rejectTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                onTerminateTool = if (isTool) { { toolCall -> viewModel.terminateRunningTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                onSubmitChoices = if (isTool) { { toolCall, summaryPayload -> viewModel.submitUserChoices(toolCall, feedItem.messageId, summaryPayload) } } else null,
                                                onSkipChoices = if (isTool) { { toolCall -> viewModel.skipUserChoices(toolCall, feedItem.messageId) } } else null
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
                                            }
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
                                                viewModel.rejectTerminalTool(toolCall, msgId)
                                            },
                                            onTerminateTool = { toolCall, msgId ->
                                                viewModel.terminateRunningTerminalTool(toolCall, msgId)
                                            },
                                            onSubmitChoices = { toolCall, msgId, summaryPayload ->
                                                viewModel.submitUserChoices(toolCall, msgId, summaryPayload)
                                            },
                                            onSkipChoices = { toolCall, msgId ->
                                                viewModel.skipUserChoices(toolCall, msgId)
                                            },
                                            summarizingModelName = summarizingModelName,
                                            pendingQueuedUserMessage = pendingQueuedUserMessage
                                        )
                                    }
                                }
                            }

                            // Bottom spacer to ensure scrolling reaches below the very bottom
                            item(key = "bottom_anchor") {
                                Spacer(modifier = Modifier.height(8.dp))
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
                }

                // File Autocomplete Suggestions when user types @
                val atIndex = inputText.lastIndexOf('@')
                val isAtMentioning = atIndex >= 0 && (atIndex == inputText.length - 1 || !inputText.substring(atIndex + 1).contains(" "))
                val atQuery = if (atIndex >= 0 && atIndex < inputText.length) inputText.substring(atIndex + 1) else ""

                val fileSuggestions = remember(atQuery, chatProjectFiles, isAtMentioning) {
                    if (!isAtMentioning || chatProjectFiles.isEmpty()) emptyList()
                    else {
                        chatProjectFiles.filter { file ->
                            !file.isDir && (atQuery.isBlank() || file.name.contains(atQuery, ignoreCase = true) || file.path.contains(atQuery, ignoreCase = true))
                        }.take(5)
                    }
                }

                if (fileSuggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            fileSuggestions.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newText = inputText.substring(0, atIndex) + "@${file.path} "
                                            val tfv = TextFieldValue(
                                                text = newText,
                                                selection = androidx.compose.ui.text.TextRange(newText.length)
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
                    onStopStreaming = { viewModel.stopStreaming() },
                    attachments = attachments,
                    isUploadingAttachment = isUploadingAttachment,
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAddAttachment = { viewModel.addAttachment(it) },
                    onAttachClick = { showAttachmentSelector = true }
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
            onRefresh = { viewModel.refreshQuotas(force = true) },
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
                viewModel.refreshQuotas()
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
            onOpenLocalTerminal = { showLocalTerminalDialog = true },
            onResetLocalTools = {
                LocalEnvironmentManager.resetEnvironment(context)
                viewModel.setLocalToolsEnabled(false)
                scope.launch { viewModel.authPreferences.setLocalToolsInstalled(false) }
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
            commandAutoExecutionPolicy = commandAutoExecutionPolicy,
            commandSandboxEnabled = commandSandboxEnabled,
            requireApprovalForFileEdits = requireApprovalForFileEdits,
            defaultApprovalScope = defaultApprovalScope,
            globalSecuritySettings = globalSecuritySettings,
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
        LocalTerminalDialog(
            onDismiss = { showLocalTerminalDialog = false }
        )
    }

    // Local Tools Setup & Progress Dialog
    if (showLocalToolsInstallDialog) {
        LocalToolsInstallDialog(
            authPreferences = viewModel.authPreferences,
            onOpenTerminal = { showLocalTerminalDialog = true },
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



    // Attachment Selector Bottom Sheet
    if (showAttachmentSelector) {
        AttachmentSelectorBottomSheet(
            onPickImage = { photoPickerLauncher.launch("image/*") },
            onPickFile = { filePickerLauncher.launch("*/*") },
            onPickProjectFile = {
                onNavigateToIde()
            },
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
    } // End of CompositionLocalProvider
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

