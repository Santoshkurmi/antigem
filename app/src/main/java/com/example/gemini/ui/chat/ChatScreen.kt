package com.example.gemini.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.sp
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
import com.example.gemini.ui.components.ActiveContextSummaryCard
import com.example.gemini.ui.components.ChatInputBar
import com.example.gemini.ui.components.ChatTelemetryDialog
import com.example.gemini.ui.components.ContextSummarizeAlertBanner
import com.example.gemini.ui.components.CustomSystemPromptDialog
import com.example.gemini.ui.components.LiveSummarizingCard
import com.example.gemini.ui.components.MessageBubble
import com.example.gemini.ui.components.RawPayloadDialog
import com.example.gemini.ui.components.SummaryModelPickerDialog
import com.example.gemini.ui.components.TerminalInspectorDialog
import com.example.gemini.ui.drawer.ChatHistoryDrawer
import com.example.gemini.ui.models.ModelSelectorBottomSheet
import com.example.gemini.ui.models.ThinkingSelectorBottomSheet
import com.example.gemini.ui.settings.SettingsDialog
import com.example.gemini.ui.tools.ToolsBottomSheet
import android.util.Log
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.MarkdownBlockView
import com.example.gemini.ui.components.parseMarkdownBlocks
import com.example.gemini.ui.components.UserMessageBubble
import com.example.gemini.ui.components.AssistantMessageFooter
import com.example.gemini.ui.components.ThinkingAccordion
import com.example.gemini.ui.components.ModelTypingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ScrollDirection { UP, DOWN }

sealed class ChatFeedItem(val key: String, val contentType: String) {
    data class Summary(val message: ChatMessage) : ChatFeedItem("summary_${message.id}", "SUMMARY")
    data class User(val message: ChatMessage) : ChatFeedItem("user_${message.id}", "USER")
    data class AssistantThinking(val messageId: String, val thoughtText: String, val durationMs: Long?, val isStreaming: Boolean) : ChatFeedItem("thought_$messageId", "THOUGHT")
    data class AssistantBlock(val messageId: String, val blockIndex: Int, val block: MarkdownBlock) : ChatFeedItem("${messageId}_b$blockIndex", "ASSISTANT_BLOCK")
    data class AssistantTyping(val messageId: String, val modelId: String) : ChatFeedItem("typing_$messageId", "TYPING")
    data class AssistantFooter(val message: ChatMessage) : ChatFeedItem("footer_${message.id}", "FOOTER")
    data class StreamingMessage(val message: ChatMessage) : ChatFeedItem("streaming_${message.id}", "STREAMING")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val isSummarizing by viewModel.isSummarizing.collectAsState()
    val summarizingModelName by viewModel.summarizingModelName.collectAsState()
    val summaryError by viewModel.summaryError.collectAsState()
    val pendingQueuedUserMessage by viewModel.pendingQueuedUserMessage.collectAsState()
    val contextWindowLimit by viewModel.contextWindowLimit.collectAsState()
    val postponedThreshold by viewModel.postponedThreshold.collectAsState()
    val showSummaryModelPicker by viewModel.showSummaryModelPicker.collectAsState()
    val summaryModelIdPref by viewModel.summaryModelIdPref.collectAsState()
    val isDevModeEnabled by viewModel.isDevModeEnabled.collectAsState()

    var showModelSelector by remember { mutableStateOf(false) }
    var showThinkingSelector by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showTerminalInspector by remember { mutableStateOf(false) }
    var showRawPayloadDialog by remember { mutableStateOf<String?>(null) }
    var showCustomSystemPromptDialog by remember { mutableStateOf(false) }
    var showChatTelemetryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf("") }
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val inputText = textFieldValue.text
    var pendingMessageAction by remember { mutableStateOf<PendingMessageAction?>(null) }
    val thinkingPref by viewModel.thinkingPreference.collectAsState()
    val terminatedToolDialogState by viewModel.terminatedToolDialog.collectAsState()
    val isOAuthServerListening by viewModel.isOAuthServerListening.collectAsState()
    val isOAuthServerLoading by viewModel.isOAuthServerLoading.collectAsState()
    val isLoadingConversation by viewModel.isLoadingConversation.collectAsState()

    // Fresh LazyListState per conversation initialized directly at the bottom
    val convKey = currentConv?.id ?: "empty"
    val listState = remember(convKey) { LazyListState(firstVisibleItemIndex = Int.MAX_VALUE) }
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

    // Decoupled asynchronous scroll observer - zero recomposition during pixel scroll
    LaunchedEffect(listState) {
        var prevIdx = 0
        var prevOff = 0
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (isScrolling, currentIndex, currentOffset) ->
            if (isScrolling) {
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

    // Immutable Cache for completed historical messages (zero AST parsing during streaming)
    val feedItemCache = remember { mutableMapOf<String, List<ChatFeedItem>>() }

    // Granular block-level feed item expansion for smooth 120 FPS virtualization
    val feedItems = remember(messages) {
        val t0 = System.nanoTime()
        val result = mutableListOf<ChatFeedItem>()
        for (msg in messages) {
            if (!msg.isStreaming) {
                val cacheKey = "${msg.id}_${msg.content.hashCode()}_${msg.toolCalls.hashCode()}_${msg.thoughtText?.hashCode() ?: 0}"
                val cached = feedItemCache[cacheKey]
                if (cached != null) {
                    result.addAll(cached)
                    continue
                }
                val msgItems = mutableListOf<ChatFeedItem>()
                when (msg.role) {
                    MessageRole.SUMMARY -> {
                        msgItems.add(ChatFeedItem.Summary(msg))
                    }
                    MessageRole.USER -> {
                        msgItems.add(ChatFeedItem.User(msg))
                    }
                    MessageRole.ASSISTANT -> {
                        if (!msg.thoughtText.isNullOrEmpty()) {
                            msgItems.add(ChatFeedItem.AssistantThinking(
                                messageId = msg.id,
                                thoughtText = msg.thoughtText,
                                durationMs = msg.thoughtDurationMs,
                                isStreaming = false
                            ))
                        }
                        if (msg.content.isNotEmpty() || msg.toolCalls.isNotEmpty()) {
                            val blocks = parseMarkdownBlocks(msg.content, msg.toolCalls)
                            blocks.forEachIndexed { idx, block ->
                                msgItems.add(ChatFeedItem.AssistantBlock(
                                    messageId = msg.id,
                                    blockIndex = idx,
                                    block = block
                                ))
                            }
                        }
                        if (msg.content.isNotEmpty()) {
                            msgItems.add(ChatFeedItem.AssistantFooter(msg))
                        }
                    }
                    else -> {}
                }
                feedItemCache[cacheKey] = msgItems
                result.addAll(msgItems)
            } else {
                // Active streaming message: virtualize blocks directly in LazyColumn so only visible blocks compose!
                val hasActiveRunningTool = msg.toolCalls.any { 
                    it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE" 
                }
                if (!msg.thoughtText.isNullOrEmpty()) {
                    result.add(ChatFeedItem.AssistantThinking(
                        messageId = msg.id,
                        thoughtText = msg.thoughtText,
                        durationMs = msg.thoughtDurationMs,
                        isStreaming = !hasActiveRunningTool
                    ))
                }
                if (msg.content.isNotEmpty() || msg.toolCalls.isNotEmpty()) {
                    val blocks = parseMarkdownBlocks(msg.content, msg.toolCalls)
                    blocks.forEachIndexed { idx, block ->
                        result.add(ChatFeedItem.AssistantBlock(
                            messageId = msg.id,
                            blockIndex = idx,
                            block = block
                        ))
                    }
                    if (!hasActiveRunningTool) {
                        result.add(ChatFeedItem.AssistantTyping(msg.id, selectedModelId))
                    }
                } else if (!hasActiveRunningTool) {
                    result.add(ChatFeedItem.AssistantTyping(msg.id, selectedModelId))
                }
            }
        }
        val dt = (System.nanoTime() - t0) / 1_000_000.0
        Log.d("PERF_TRACE", "⚡ [FeedItems Calc] count=${result.size}, took=${"%.2f".format(dt)}ms")
        result
    }

    // Always scroll to very bottom when a conversation is opened or loaded
    LaunchedEffect(currentConv?.id, messages.size, feedItems.size) {
        val convId = currentConv?.id
        if (convId != null && feedItems.isNotEmpty()) {
            if (lastScrolledConvId != convId || lastScrolledMessageCount != messages.size) {
                lastScrolledConvId = convId
                lastScrolledMessageCount = messages.size
                shouldAutoScroll = true
                listState.scrollToItem(maxOf(0, feedItems.size - 1))
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

    // Smart auto-scroll during streaming: follows live stream until user drags up
    val lastMsg = messages.lastOrNull()
    val lastContentLen = lastMsg?.content?.length ?: 0
    val lastThoughtLen = lastMsg?.thoughtText?.length ?: 0

    LaunchedEffect(feedItems.size, lastContentLen, lastThoughtLen, isStreaming) {
        if (feedItems.isNotEmpty() && isStreaming && shouldAutoScroll && !listState.isScrollInProgress) {
            listState.scrollToItem(maxOf(0, feedItems.size - 1))
            Log.d("PERF_TRACE", "📜 [Auto-Scroll] target=${feedItems.size - 1}, items=${feedItems.size}")
        }
    }

    val currentModel = AiModel.findInList(enabledModels, selectedModelId)
    val currentQuota = quotas.find { it.modelId == selectedModelId }

    val activeChatProject by TermuxDaemonManager.activeProject.collectAsState()
    var chatProjectsList by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var showProjectDropdown by remember { mutableStateOf(false) }
    var chatProjectFiles by remember { mutableStateOf<List<com.example.gemini.data.daemon.FileNode>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch {
            val list = IdeApiClient.getProjects()
            chatProjectsList = list
            if (activeChatProject == null && list.isNotEmpty()) {
                TermuxDaemonManager.setActiveProject(list.first())
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = conversations,
                currentConversationId = currentConv?.id,
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
                onOpenSettings = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        editTitleText = currentConv?.title ?: ""
                                        showEditTitleDialog = true
                                    }
                            ) {
                                Text(
                                    text = currentConv?.title?.takeIf { it.isNotBlank() } ?: "Antigravity Chat",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename Chat",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            chatProjectsList = IdeApiClient.getProjects()
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
                                                        fontWeight = if (proj.path == activeChatProject?.path) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    TermuxDaemonManager.setActiveProject(proj)
                                                    showProjectDropdown = false
                                                }
                                            )
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
                        IconButton(onClick = { viewModel.openManualSummaryPicker() }) {
                            Icon(
                                imageVector = Icons.Outlined.Compress,
                                contentDescription = "Summarize Context",
                                tint = if (!currentConv?.summary.isNullOrBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        IconButton(onClick = { showToolsSheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Handyman,
                                contentDescription = "Tools",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        IconButton(onClick = { showTerminalInspector = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = "Termux Terminal",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
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
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.5.dp
                            )
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
                                        UserMessageBubble(
                                            message = feedItem.message,
                                            isDevModeEnabled = isDevModeEnabled,
                                            onEdit = { targetMsg ->
                                                val msgIndex = messages.indexOfFirst { it.id == targetMsg.id }
                                                val isLastUserMsg = messages.indexOfLast { it.role == MessageRole.USER } == msgIndex
                                                val willDeleteOutput = isLastUserMsg && msgIndex < messages.lastIndex
                                                if (willDeleteOutput) {
                                                    pendingMessageAction = PendingMessageAction(MessageActionType.EDIT, targetMsg)
                                                } else {
                                                    val text = viewModel.prepareEditMessage(targetMsg.id)
                                                    if (text != null) {
                                                        textFieldValue = TextFieldValue(text, selection = TextRange(text.length))
                                                    }
                                                }
                                            },
                                            onRetry = { targetMsg ->
                                                val msgIndex = messages.indexOfFirst { it.id == targetMsg.id }
                                                val isLastUserMsg = targetMsg.role == MessageRole.USER && (messages.indexOfLast { it.role == MessageRole.USER } == msgIndex)
                                                val willDeleteOutput = (targetMsg.role == MessageRole.ASSISTANT) || (isLastUserMsg && msgIndex < messages.lastIndex)
                                                if (willDeleteOutput) {
                                                    pendingMessageAction = PendingMessageAction(MessageActionType.RETRY, targetMsg)
                                                } else {
                                                    viewModel.retryMessage(targetMsg.id)
                                                    userSentMessageTrigger++
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
                                            MarkdownBlockView(
                                                block = feedItem.block,
                                                onApproveTool = { toolCall -> viewModel.approveAndExecuteTerminalTool(toolCall, feedItem.messageId) },
                                                onRejectTool = { toolCall -> viewModel.rejectTerminalTool(toolCall, feedItem.messageId) },
                                                onTerminateTool = { toolCall -> viewModel.terminateRunningTerminalTool(toolCall, feedItem.messageId) },
                                                onSubmitChoices = { toolCall, summaryPayload -> viewModel.submitUserChoices(toolCall, feedItem.messageId, summaryPayload) },
                                                onSkipChoices = { toolCall -> viewModel.skipUserChoices(toolCall, feedItem.messageId) }
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

                // In-Chat Summarize Alert Banner when threshold is reached (Pinned above input)
                if (!isSummarizing && messages.size > (postponedThreshold ?: contextWindowLimit) && currentConv?.summary.isNullOrBlank()) {
                    ContextSummarizeAlertBanner(
                        messageCount = messages.size,
                        windowLimit = contextWindowLimit,
                        onSummarizeNow = { viewModel.openManualSummaryPicker() },
                        onPostpone = { viewModel.postponeSummarization(it) }
                    )
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
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 6.dp
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text(
                                text = "📁 Mention File in ${activeChatProject?.name ?: "Project"}:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            fileSuggestions.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            val relPath = file.path.removePrefix(activeChatProject?.path ?: "").removePrefix("/")
                                            val replacement = "@$relPath "
                                            val newText = inputText.substring(0, atIndex) + replacement
                                            textFieldValue = TextFieldValue(
                                                text = newText,
                                                selection = TextRange(newText.length)
                                            )
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = file.path.removePrefix(activeChatProject?.path ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Chat Input Bar with Bottom Model & Thinking Selector Pills (Claude Android Style)
                ChatInputBar(
                    selectedModel = currentModel,
                    quota = currentQuota,
                    thinkingPreference = thinkingPref,
                    textFieldValue = textFieldValue,
                    onTextFieldValueChange = { textFieldValue = it },
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
                        textFieldValue = TextFieldValue("")
                        userSentMessageTrigger++
                    },
                    onStopStreaming = { viewModel.stopStreaming() }
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
            isRefreshing = isRefreshingModels,
            onRefresh = { viewModel.refreshQuotas() },
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

    // Settings Dialog
    if (showSettingsDialog) {
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
            onToggleDevMode = { viewModel.setDevModeEnabled(it) },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // AI Tools & Capabilities Bottom Sheet (Termux SSH Terminal Access)
    if (showToolsSheet) {
        ToolsBottomSheet(
            authPreferences = viewModel.authPreferences,
            onDismiss = { showToolsSheet = false }
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
                            val text = viewModel.prepareEditMessage(target.id)
                            if (text != null) {
                                textFieldValue = TextFieldValue(text, selection = TextRange(text.length))
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

    // Context Summary Model Picker Dialog
    if (showSummaryModelPicker) {
        SummaryModelPickerDialog(
            availableModels = enabledModels,
            currentChatModelId = selectedModelId,
            defaultModelId = summaryModelIdPref,
            errorMessage = summaryError,
            onSelectModel = { 
                viewModel.requestSummarization(it) {
                    android.widget.Toast.makeText(context, "Context summary updated!", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { viewModel.dismissSummaryModelPicker() }
        )
    }

    // Termux Terminal Inspector Dialog
    if (showTerminalInspector) {
        TerminalInspectorDialog(
            authPreferences = viewModel.authPreferences,
            onDismiss = { showTerminalInspector = false }
        )
    }

    // Raw Request Payload Inspector Dialog (Developer Mode)
    if (showRawPayloadDialog != null) {
        RawPayloadDialog(
            payloadJson = showRawPayloadDialog!!,
            onDismiss = { showRawPayloadDialog = null }
        )
    }

    // Custom System Prompt Override Dialog (Developer Mode)
    if (showCustomSystemPromptDialog) {
        CustomSystemPromptDialog(
            initialPrompt = currentConv?.customSystemPrompt,
            onSavePrompt = { viewModel.updateCustomSystemPrompt(it) },
            onDismiss = { showCustomSystemPromptDialog = false }
        )
    }

    // Chat Telemetry & Tokens Dialog (Developer Mode)
    if (showChatTelemetryDialog) {
        ChatTelemetryDialog(
            conversation = currentConv,
            messages = messages,
            onOpenSystemPrompt = { showCustomSystemPromptDialog = true },
            onDismiss = { showChatTelemetryDialog = false }
        )
    }

    // Rename Chat Title Dialog
    if (showEditTitleDialog) {
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Rename Chat",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = { editTitleText = it },
                    label = { Text("Chat Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editTitleText.trim()
                        if (trimmed.isNotBlank()) {
                            currentConv?.id?.let { id ->
                                viewModel.updateConversationTitle(id, trimmed)
                            }
                        }
                        showEditTitleDialog = false
                    }
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitleDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        )
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

