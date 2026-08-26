package com.example.gemini.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gemini.domain.model.AiModel
import com.example.gemini.theme.*
import com.example.gemini.ui.components.ChatInputBar
import com.example.gemini.ui.components.MessageBubble
import com.example.gemini.ui.drawer.ChatHistoryDrawer
import com.example.gemini.ui.models.ModelSelectorBottomSheet
import com.example.gemini.ui.models.ThinkingSelectorBottomSheet
import com.example.gemini.ui.settings.SettingsDialog
import kotlinx.coroutines.launch

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.delay

enum class ScrollDirection { UP, DOWN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
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

    var showModelSelector by remember { mutableStateOf(false) }
    var showThinkingSelector by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val thinkingPref by viewModel.thinkingPreference.collectAsState()

    // Independent LazyListState per conversation
    val convKey = currentConv?.id ?: "empty"
    val listState = rememberSaveable(convKey, saver = LazyListState.Saver) { LazyListState() }
    var lastScrolledConvId by remember { mutableStateOf<String?>(null) }

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
    var prevIndex by remember { mutableIntStateOf(0) }
    var prevOffset by remember { mutableIntStateOf(0) }

    // Track scroll direction strictly while scroll is in progress
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.isScrollInProgress) {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            if (currentIndex < prevIndex || (currentIndex == prevIndex && currentOffset < prevOffset)) {
                scrollDirection = ScrollDirection.UP
            } else if (currentIndex > prevIndex || (currentIndex == prevIndex && currentOffset > prevOffset)) {
                scrollDirection = ScrollDirection.DOWN
            }
            prevIndex = currentIndex
            prevOffset = currentOffset
        }
    }

    // Show button during scroll and timeout after 5 seconds of inactivity
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            showScrollButton = true
        } else if (showScrollButton) {
            delay(5000)
            showScrollButton = false
        }
    }

    // Hide immediately when reaching the very top or very bottom
    LaunchedEffect(isAtTop, isAtBottom) {
        if (isAtTop && scrollDirection == ScrollDirection.UP) {
            showScrollButton = false
        }
        if (isAtBottom && scrollDirection == ScrollDirection.DOWN) {
            showScrollButton = false
        }
    }

    var userSentMessageTrigger by remember { mutableStateOf(0) }

    // Scroll to very bottom when a conversation is first opened / loaded
    LaunchedEffect(currentConv?.id, messages.isNotEmpty()) {
        if (currentConv?.id != null && messages.isNotEmpty() && lastScrolledConvId != currentConv?.id) {
            lastScrolledConvId = currentConv?.id
            listState.scrollToItem(messages.size)
        }
    }

    // Scroll to bottom when user explicitly sends a message (instant)
    LaunchedEffect(userSentMessageTrigger) {
        if (userSentMessageTrigger > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size)
        }
    }

    // Smart auto-scroll during streaming: only auto-scroll if user is already at the bottom
    val lastMessageContentLength = messages.lastOrNull()?.content?.length ?: 0
    val lastMessageThoughtLength = messages.lastOrNull()?.thoughtText?.length ?: 0

    LaunchedEffect(messages.size, lastMessageContentLength, lastMessageThoughtLength) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(messages.size)
        }
    }

    val currentModel = AiModel.findInList(enabledModels, selectedModelId)
    val currentQuota = quotas.find { it.modelId == selectedModelId }

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
                        Text(
                            text = currentConv?.title?.takeIf { it.isNotBlank() } ?: "Antigravity Chat",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = { viewModel.startNewChat() }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Chat",
                                tint = ClaudeTerracotta
                            )
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
                    if (messages.isEmpty()) {
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
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(message = msg, modelId = selectedModelId)
                            }
                            // Bottom spacer to ensure scrolling reaches below the very bottom of the last message
                            item(key = "bottom_anchor") {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }

                    // Floating Scroll Up / Scroll Down Button (Instant Movement)
                    val showUpArrow = showScrollButton && scrollDirection == ScrollDirection.UP && !isAtTop
                    val showDownArrow = showScrollButton && scrollDirection == ScrollDirection.DOWN && !isAtBottom
                    val isVisible = (showUpArrow || showDownArrow) && messages.size > 2

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
                                            listState.scrollToItem(messages.size)
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

                // Chat Input Bar with Bottom Model & Thinking Selector Pills (Claude Android Style)
                ChatInputBar(
                    selectedModel = currentModel,
                    quota = currentQuota,
                    thinkingPreference = thinkingPref,
                    onOpenModelSelector = { showModelSelector = true },
                    onOpenThinkingSelector = { showThinkingSelector = true },
                    isStreaming = isStreaming,
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                        userSentMessageTrigger++
                    },
                    onStopStreaming = { viewModel.stopStreaming() }
                )
            }
        }
    }

    // Model Selector Bottom Sheet (Grouped Categories & Thinking Config)
    if (showModelSelector) {
        ModelSelectorBottomSheet(
            selectedModelId = selectedModelId,
            availableModels = enabledModels,
            quotas = quotas,
            thinkingPreference = thinkingPref,
            onOpenThinkingConfig = { showThinkingSelector = true },
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
            onLoginWithGoogle = {
                val url = viewModel.getGoogleOAuthUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
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
            onDismiss = { showSettingsDialog = false }
        )
    }
}
