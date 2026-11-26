package ai.antigem.ui.chat

import ai.antigem.ui.components.ChatToast
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.AppToastHelper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import ai.antigem.ui.components.label
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import ai.antigem.data.preferences.AuthPreferences
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import ai.antigem.data.daemon.FileNode
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.daemon.IdeDaemonManager
import ai.antigem.domain.model.AiModel
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.MessageRole
import ai.antigem.domain.model.ToolType
import ai.antigem.theme.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.antigem.ui.components.ActiveContextSummaryCard
import ai.antigem.ui.components.AttachmentSelectorBottomSheet
import ai.antigem.ui.components.ChatInputBar
import ai.antigem.ui.components.ChatTelemetryDialog
import ai.antigem.ui.components.LiveSummarizingCard
import ai.antigem.ui.components.MessageBubble
import ai.antigem.ui.components.RawPayloadDialog
import ai.antigem.ui.components.TerminalInspectorDialog
import ai.antigem.ui.components.LocalTerminalDialog
import ai.antigem.ui.components.LocalTerminalContent
import ai.antigem.ui.settings.LocalToolsInstallDialog
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.ui.drawer.ChatHistoryDrawer
import ai.antigem.ui.models.ModelSelectorBottomSheet
import ai.antigem.ui.models.ThinkingSelectorBottomSheet
import ai.antigem.ui.settings.SettingsDialog
import ai.antigem.ui.components.MarkdownBlock
import ai.antigem.ui.components.MarkdownBlockView
import ai.antigem.ui.components.parseMarkdownBlocks
import ai.antigem.ui.components.UserMessageBubble
import ai.antigem.ui.components.AssistantMessageFooter
import ai.antigem.ui.components.ThinkingAccordion
import ai.antigem.ui.components.ConnectionStatusBadge
import ai.antigem.ui.components.FileLinkHandler
import ai.antigem.ui.components.LocalFileLinkHandler
import ai.antigem.ui.components.FileDetailsDialog
import ai.antigem.ui.components.MarkdownDocViewerModal
import ai.antigem.ui.components.SharedChatViewerModal
import ai.antigem.ui.components.SharedChatLoadingDialog
import ai.antigem.ui.components.ProjectPickerDialog
import ai.antigem.ui.components.ToolCallExpansionCache
import ai.antigem.ui.components.CodeBlockExpansionCache
import ai.antigem.ui.bubble.FloatingChatActivity
import android.app.Activity
import ai.antigem.ui.drawer.ArtifactsDrawerContent
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.interaction.MutableInteractionSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.antigem.ui.cloud.cloudProjectsSection
import ai.antigem.ui.components.hiddenWhileOffscreen
import ai.antigem.i18n.L

enum class ScrollDirection { UP, DOWN }

/**
 * Keeps the end of the chat on screen while the agent works ([working]): the scroll is requested in the same
 * composition as the new rows, so it applies in the same layout pass and the end never drops below the screen while a
 * reply grows. It reads the auto-scroll and dragging flags itself: when they flip (every scroll starts and ends with
 * them) only this recomposes, not the chat screen.
 */
@Composable
private fun FollowTail(
    listState: LazyListState,
    // the rows themselves, not their count: a reply growing inside its last row makes a new list of the same size,
    // and the end must be pinned again for it
    rows: List<ChatFeedItem>,
    working: Boolean,
    shouldAutoScroll: () -> Boolean,
    isUserDragging: () -> Boolean
) {
    if (rows.isNotEmpty() && working && shouldAutoScroll() && !isUserDragging()) {
        SideEffect { listState.requestScrollToItem(rows.size) }
    }
}

/** How often the chat screen takes message updates while the newest message is off screen (see [pacedMessages]). */
private const val MESSAGE_PACE_MS = 250L

/**
 * The messages for the chat screen. While [slow] (no row of the newest message is on screen: the user reads older
 * messages while a reply may be streaming), updates are passed on at most every [MESSAGE_PACE_MS], always the newest
 * one: the screen and its list rebuild a few times a second instead of on every streamed word. Passed on at once:
 * every update when not [slow], the first list, another chat's list, a list that got shorter or emptied (edit,
 * rewind, reload), and the newest list the moment [slow] ends. Nothing is ever dropped: the last update always
 * arrives, at most [MESSAGE_PACE_MS] late.
 */
private fun pacedMessages(
    source: kotlinx.coroutines.flow.StateFlow<List<ChatMessage>>,
    slow: () -> Boolean
): kotlinx.coroutines.flow.Flow<List<ChatMessage>> = kotlinx.coroutines.flow.channelFlow {
    var sent: List<ChatMessage>? = null
    var sentAt = 0L
    var timer: kotlinx.coroutines.Job? = null
    suspend fun pass(list: List<ChatMessage>) {
        sent = list
        sentAt = android.os.SystemClock.uptimeMillis()
        send(list)
    }
    launch {
        // the newest message came on screen: what was held back follows at once
        snapshotFlow { slow() }.collect { isSlow ->
            if (!isSlow) {
                timer?.cancel()
                timer = null
                val newest = source.value
                if (newest !== sent) pass(newest)
            }
        }
    }
    source.collect { list ->
        val last = sent
        val urgent = last == null || !slow() || list.isEmpty() || last.isEmpty() || list.size < last.size ||
            list.first().conversationId != last.first().conversationId
        val wait = MESSAGE_PACE_MS - (android.os.SystemClock.uptimeMillis() - sentAt)
        if (urgent || wait <= 0) {
            timer?.cancel()
            timer = null
            pass(list)
        } else if (timer == null) {
            timer = launch {
                delay(wait)
                timer = null
                pass(source.value)
            }
        }
    }
}

/** The index of the first row of the newest message in [rows] (rows.size when no row belongs to a message). */
private fun firstRowOfNewestMessage(rows: List<ChatFeedItem>): Int {
    var newest: String? = null
    var first = rows.size
    for (i in rows.indices.reversed()) {
        val id = rows[i].messageIdOrNull() ?: continue
        if (newest == null) newest = id else if (id != newest) break
        first = i
    }
    return first
}

/** The message a row belongs to (null for dividers and voice-call markers). */
private fun ChatFeedItem.messageIdOrNull(): String? = when (this) {
    is ChatFeedItem.AssistantBlock -> messageId
    is ChatFeedItem.AssistantThinking -> messageId
    is ChatFeedItem.AssistantTyping -> messageId
    is ChatFeedItem.AssistantFooter -> message.id
    is ChatFeedItem.StreamingMessage -> message.id
    is ChatFeedItem.User -> message.id
    is ChatFeedItem.Summary -> message.id
    is ChatFeedItem.JarvisEvent -> message.id
    is ChatFeedItem.StepGroup -> steps.firstOrNull()?.messageId
    else -> null
}

/**
 * The floating jump-to-top / jump-to-bottom button. It reads the scroll flags itself (shown, direction, at the top,
 * at the bottom), so while scrolling only the button redraws when they change, not the chat screen.
 */
@Composable
private fun ChatJumpButton(
    showScrollButton: () -> Boolean,
    scrollDirection: () -> ScrollDirection,
    isAtTop: () -> Boolean,
    isAtBottom: () -> Boolean,
    rowCount: Int,
    modifier: Modifier,
    onJump: (toTop: Boolean) -> Unit
) {
    val showUpArrow = showScrollButton() && scrollDirection() == ScrollDirection.UP && !isAtTop()
    val showDownArrow = showScrollButton() && scrollDirection() == ScrollDirection.DOWN && !isAtBottom()
    val isVisible = (showUpArrow || showDownArrow) && rowCount > 2

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { onJump(showUpArrow) },
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
                    contentDescription = if (showUpArrow) L.chat.screen.jumpToTop else L.chat.screen.jumpToBottom,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    isInFloatingWindow: Boolean = false,
    onOpenFullScreen: () -> Unit = {},
    onMinimizeWindow: () -> Unit = {},
    onNavigateToIde: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    // null where that screen doesn't exist (the floating chat window): its header icon is then hidden
    onNavigateToNotes: (() -> Unit)? = null,
    onNavigateToFiles: (() -> Unit)? = null,
    // Y (root px) where the bottom input area starts, including the jump-to-top/bottom button that floats just above
    // it; the floating app switcher keeps above it while the keyboard is open
    onBottomAreaTopChanged: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    // The sidebar is 82% of the screen width, so turning the phone (e.g. a landscape full-screen video)
    // changes its width; the drawer then moves to whichever position is nearest and can open by itself.
    // Remember where it was before the width changed and put it back once the new layout is in.
    val drawerScreenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val drawerValueBeforeResize = remember(drawerScreenWidthDp) { drawerState.targetValue }
    LaunchedEffect(drawerScreenWidthDp) {
        withFrameNanos { }
        withFrameNanos { }
        if (drawerState.currentValue != drawerValueBeforeResize && !drawerState.isAnimationRunning) {
            drawerState.snapTo(drawerValueBeforeResize)
        }
    }
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
    val jarvisConversations by viewModel.jarvisConversations.collectAsState()
    val jarvisEnabled by ai.antigem.data.jarvis.JarvisPreferences.enabled.state.collectAsState()
    val currentConv by viewModel.currentConversation.collectAsState()
    // a Claude Code subagent's transcript: it can only be read
    val isReadOnlyChat = currentConv?.readOnly == true
    // subagents of the chat on screen (and theirs), newest first: the agents chip of the Claude strip
    val chatSubagents = remember(conversations, currentConv?.id) {
        val root = currentConv?.id
        if (root == null) emptyList() else {
            val byParent = conversations.filter { it.parentConversationId != null }.groupBy { it.parentConversationId }
            val seen = HashSet<String>()
            val out = ArrayList<ai.antigem.domain.model.Conversation>()
            fun collect(id: String) {
                byParent[id].orEmpty().forEach { c -> if (seen.add(c.id)) { out.add(c); collect(c.id) } }
            }
            collect(root)
            out.sortedByDescending { it.createdAt }
        }
    }
    val newChatAgent by viewModel.newChatAgent.collectAsState()
    val isClaudeChat = currentConv?.agent == ai.antigem.domain.model.AgentKind.CLAUDE
    val isOpenCodeChat = currentConv?.agent == ai.antigem.domain.model.AgentKind.OPENCODE
    val isCodexChat = currentConv?.agent == ai.antigem.domain.model.AgentKind.CODEX
    /** The chat on screen is Antigravity's (its hub, bridge banners and sign-in apply). */
    val isAgyChat = !isClaudeChat && !isOpenCodeChat && !isCodexChat
    val openCodeBackend = viewModel.openCodeBackend
    val openCodePending by openCodeBackend.pendingPermissions.collectAsState()
    val openCodeState by openCodeBackend.state.collectAsState()
    val openCodeStatus by openCodeBackend.status.collectAsState()
    val openCodeModelsError by openCodeBackend.modelsError.collectAsState()
    var openOpenCodeSettings by remember { mutableStateOf(false) }
    // the OpenCode page Settings opens on (OpenCode CLI for the Install button, Providers from the model picker)
    var openCodeSettingsPage by remember { mutableStateOf<ai.antigem.ui.opencode.OpenCodeSettingsPage?>(null) }
    LaunchedEffect(Unit) {
        openCodeBackend.toasts.collect {
            ai.antigem.ui.components.AppToastHelper.showToast(it, ai.antigem.ui.components.ChatToastType.INFO)
        }
    }
    val codexBackend = viewModel.codexBackend
    val codexPending by codexBackend.pendingApprovals.collectAsState()
    val codexState by codexBackend.state.collectAsState()
    val codexStatus by codexBackend.status.collectAsState()
    val codexAccount by codexBackend.account.collectAsState()
    val codexModelsError by codexBackend.modelsError.collectAsState()
    var openCodexSettings by remember { mutableStateOf(false) }
    // the Codex page Settings opens on (Account & CLI for the Install button and Sign in, MCP for /mcp)
    var codexSettingsPage by remember { mutableStateOf<ai.antigem.ui.codex.CodexSettingsPage?>(null) }
    LaunchedEffect(Unit) {
        codexBackend.toasts.collect {
            ai.antigem.ui.components.AppToastHelper.showToast(it, ai.antigem.ui.components.ChatToastType.INFO)
        }
    }
    // a fresh Jarvis text chat has the agent picker too; switching starts it again with that agent, still a Jarvis chat
    val jarvisManagers by ai.antigem.data.jarvis.JarvisManagers.managers.collectAsState()
    val isJarvisTextChat = currentConv?.id?.let { it in jarvisManagers.keys } == true
    val isFreshNewChat = currentConv == null || currentConv?.title == "New Chat"
    val pickAgent: (ai.antigem.domain.model.AgentKind) -> Unit = { agent ->
        val id = currentConv?.id
        if (isJarvisTextChat && id != null) ai.antigem.data.jarvis.JarvisController.switchTextChatAgent(id, agent)
        else viewModel.setNewChatAgent(agent)
    }
    val claudeBackend = viewModel.claudeBackend
    val claudePending by claudeBackend.pendingPermissions.collectAsState()
    val claudeStatus by viewModel.claudeAccount.status.collectAsState()
    val claudeSlashCommands by claudeBackend.slashCommands.collectAsState()
    var showClaudeLogin by remember { mutableStateOf(false) }
    var claudeSettingsPage by remember { mutableStateOf<ai.antigem.ui.claude.ClaudeSettingsPage?>(null) }
    val claudeSettingsDeps = remember(viewModel) {
        ai.antigem.ui.claude.ClaudeSettingsDeps(viewModel.claudeAccount, viewModel.claudeConfig, viewModel.claudeBackend, viewModel.claudePrefs)
    }
    LaunchedEffect(Unit) {
        viewModel.claudeAccount.messages.collect {
            ai.antigem.ui.components.AppToastHelper.showToast(it, ai.antigem.ui.components.ChatToastType.INFO)
        }
    }
    // True while none of the newest message's rows is on screen (the user reads older messages while a reply may be
    // streaming): the screen then takes message updates a few times a second instead of on every streamed word, so
    // scrolling through older messages stays smooth (see pacedMessages). Set further down, from the list's layout.
    val readingOlder = remember { mutableStateOf(false) }
    val messagesSource = viewModel.messages
    val messages by remember(messagesSource) { pacedMessages(messagesSource) { readingOlder.value } }
        .collectAsState(initial = messagesSource.value)
    val artifacts by viewModel.artifacts.collectAsState()
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
    val claudeCompacting by claudeBackend.isCompacting.collectAsState()
    val bridgeStatusMessage by viewModel.bridgeStatusMessage.collectAsState()
    val isServerOnline by viewModel.isServerOnline.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val isBridgeOnline by viewModel.isBridgeOnline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val conversationError by viewModel.conversationError.collectAsState()
    val activeInstances by viewModel.activeInstances.collectAsState()
    val isActivelyRunning by viewModel.isCurrentChatActivelyRunning.collectAsState()
    // TalkBack on: the chat speaks "Reply finished", approvals and questions (ChatTalkBackAnnouncer); off: nothing runs
    val talkBackOn = ai.antigem.ui.components.rememberTalkBackOn()
    val isBackgroundActive by viewModel.isCurrentChatBackgroundActive.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
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
    // the floating theme picker opened from the sidebar's palette button
    var showThemePanel by remember { mutableStateOf(false) }
    // Connect to PC: the side bar's PC button and its sheet
    val pcContext = LocalContext.current
    LaunchedEffect(Unit) { ai.antigem.data.pc.PcConnect.init(pcContext) }
    val pcConnectEnabled by ai.antigem.data.pc.PcConnect.enabled.collectAsState()
    var showPcSheet by remember { mutableStateOf(false) }
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
    val groupAgentSteps by viewModel.groupAgentSteps.collectAsState()
    val groupStepsMin by viewModel.groupStepsMin.collectAsState()
    val showMessageStats by viewModel.showMessageStats.collectAsState()
    val showArchivedChats by viewModel.showArchivedChats.collectAsState()
    val floatingSwitcherOrientation by viewModel.floatingSwitcherOrientation.collectAsState()
    val floatingSwitcherItems by viewModel.floatingSwitcherItems.collectAsState()
    val floatingSwitcherHandleIcon by viewModel.floatingSwitcherHandleIcon.collectAsState()
    val chatHeaderItems by viewModel.chatHeaderItems.collectAsState()
    val floatingSwitcherAutoCollapseSec by viewModel.floatingSwitcherAutoCollapseSec.collectAsState()
    val isFloatingDiagnosticsEnabled by viewModel.isFloatingDiagnosticsEnabled.collectAsState()
    val isNetworkInspectorEnabled by viewModel.isNetworkInspectorEnabled.collectAsState()
    val isFloatingNetworkInspectorEnabled by viewModel.isFloatingNetworkInspectorEnabled.collectAsState()
    var showNetworkInspectorDialog by remember { mutableStateOf(false) }
    val hasSeenTerminalLauncherOnboarding by viewModel.authPreferences.hasSeenTerminalLauncherOnboarding.collectAsState(initial = true)
    val hasSeenGemmyIntro by viewModel.authPreferences.hasSeenGemmyIntro.collectAsState(initial = true)
    val isTranscribingAudio by viewModel.isTranscribingAudio.collectAsState()
    val pendingLoginUrl by viewModel.pendingLoginUrl.collectAsState()
    val agyLoginIssue by viewModel.agyLoginIssue.collectAsState()
    val hubStatus by viewModel.hubStatus.collectAsState()
    val systemConnectionState by viewModel.systemConnectionState.collectAsState()
    val isNetworkConnected by viewModel.isNetworkConnectedState.collectAsState()
    val isAnyGenerationOrTaskActive by viewModel.isAnyGenerationOrTaskActive.collectAsState()
    val screenAwakeReasons by viewModel.screenAwakeReasons.collectAsState()
    var screenAwakeInfoOpen by remember { mutableStateOf(false) }

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
    val serverStatus by ai.antigem.data.local.LocalServerManager.status.collectAsState()
    val isLocalRunning = serverStatus is ai.antigem.data.local.LocalServerStatus.Running
    val isLocalStarting = serverStatus is ai.antigem.data.local.LocalServerStatus.Starting
    val isLocalStopped = serverStatus is ai.antigem.data.local.LocalServerStatus.Stopped
    val isLocalError = serverStatus is ai.antigem.data.local.LocalServerStatus.Error
    var showRawPayloadDialog by remember { mutableStateOf<String?>(null) }
    var showChatTelemetryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    // an error card's "Reconnect in Settings" (an OpenCode account the provider rejected)
    LaunchedEffect(Unit) {
        ai.antigem.ui.opencode.OpenCodeUiEvents.openProviders.collect {
            openCodeSettingsPage = ai.antigem.ui.opencode.OpenCodeSettingsPage.PROVIDERS
            showSettingsDialog = true
        }
    }
    var showAttachmentSelector by remember { mutableStateOf(false) }
    var showAttachFromFiles by remember { mutableStateOf(false) }
    var showProjectPickerDialog by remember { mutableStateOf(false) }
    var showGitCloneDialog by remember { mutableStateOf(false) }
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

    // Jarvis (voice or its panel) asked to show a settings page
    var requestedSettingsSection by remember { mutableStateOf<ai.antigem.ui.settings.SettingsSection?>(null) }
    val jarvisSettingsRequest by ai.antigem.data.jarvis.JarvisController.settingsRequest.collectAsState()
    LaunchedEffect(jarvisSettingsRequest) {
        val name = jarvisSettingsRequest ?: return@LaunchedEffect
        requestedSettingsSection = runCatching { ai.antigem.ui.settings.SettingsSection.valueOf(name) }.getOrNull()
        showSettingsDialog = true
        ai.antigem.data.jarvis.JarvisController.consumeSettingsRequest()
    }
    // another screen asked to show a settings page (the terminal drawer's gear: Settings → Terminal)
    val screenSettingsRequest by viewModel.requestedSettingsSection.collectAsState()
    LaunchedEffect(screenSettingsRequest) {
        val name = screenSettingsRequest ?: return@LaunchedEffect
        requestedSettingsSection = runCatching { ai.antigem.ui.settings.SettingsSection.valueOf(name) }.getOrNull()
        showSettingsDialog = true
        viewModel.consumeRequestedSettingsSection()
    }

    // Antigravity cloud agents (Remote Agents): cloud projects, through the hub
    LaunchedEffect(Unit) { ai.antigem.data.agent.agy.AgyCloud.init(context) }
    val cloudProjects by ai.antigem.data.agent.agy.AgyCloud.projects.collectAsState()
    // off by default (Settings → Antigravity → Cloud agents): then nothing cloud is asked for or shown
    val cloudEnabled by ai.antigem.data.agent.agy.AgyCloud.enabled.collectAsState()
    LaunchedEffect(systemConnectionState.canSend, cloudEnabled) {
        if (systemConnectionState.canSend && cloudEnabled) ai.antigem.data.agent.agy.AgyCloud.refreshProjects()
    }
    var showCloudProjects by remember { mutableStateOf(false) }
    var showCloudCreate by remember { mutableStateOf(false) }
    var showCloudPreview by remember { mutableStateOf(false) }
    /** The in-app preview on screen: (cloud chat, port). */
    var cloudPreviewInApp by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showCloudRunCommand by remember { mutableStateOf(false) }

    // Antigravity voice call (a live spoken call with the agent about the chat on screen)
    val voiceCall by ai.antigem.data.voice.AgyVoiceCall.state.collectAsState()
    val agyPickedModel by viewModel.agyBackend.selectedModelId.collectAsState()
    val beginVoiceCall: () -> Unit = {
        val title = currentConv?.title?.takeIf { it.isNotBlank() && it != "New Chat" } ?: L.chat.screen.voiceChat
        ai.antigem.data.voice.AgyVoiceCall.start(context, title) { viewModel.agyBackend.prepareVoiceCall() }
    }
    val voiceCallPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoiceCall()
        else ai.antigem.ui.components.AppToastHelper.showToast(
            L.chat.screen.voiceCallsNeedMicrophoneAccess, ai.antigem.ui.components.ChatToastType.ERROR
        )
    }
    val callWithMicPermission: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) beginVoiceCall()
        else voiceCallPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    // before the first call: pick a voice (swipe through them and hear each one)
    var showVoicePicker by remember { mutableStateOf(false) }
    val onStartVoiceCall: () -> Unit = {
        ai.antigem.data.voice.AgyVoicePreferences.init(context)
        when {
            voiceCall.active -> ai.antigem.data.voice.AgyVoiceCall.setMinimized(false)
            !systemConnectionState.canSend -> ai.antigem.ui.components.AppToastHelper.showToast(
                L.chat.screen.startTheServerToCallAntigravity, ai.antigem.ui.components.ChatToastType.ERROR
            )
            // agy refuses voice calls for cloud chats (its /voice/ws answers 404 for them)
            currentConv?.cloudProjectId != null -> ai.antigem.ui.components.AppToastHelper.showToast(
                ai.antigem.data.voice.AgyVoiceCall.CLOUD_CHAT_NO_VOICE, ai.antigem.ui.components.ChatToastType.INFO
            )
            !ai.antigem.data.voice.AgyVoicePreferences.picked.value -> showVoicePicker = true
            else -> callWithMicPermission()
        }
    }
    if (showVoicePicker) {
        ai.antigem.ui.voice.AgyVoicePickerDialog(
            initialVoice = ai.antigem.data.voice.AgyVoicePreferences.voice.value,
            actionLabel = { L.chat.screen.callWithValue(it) },
            onChoose = { name ->
                ai.antigem.data.voice.AgyVoicePreferences.setVoice(name)
                ai.antigem.data.voice.AgyVoicePreferences.setPicked()
                showVoicePicker = false
                callWithMicPermission()
            },
            onDismiss = { showVoicePicker = false }
        )
    }
    // the voice agent moved the call to another chat (it started a new one): show that chat
    LaunchedEffect(Unit) {
        ai.antigem.data.voice.AgyVoiceCall.conversationMoved.collect { id -> viewModel.selectConversation(id) }
    }
    // the Voice call tile or shortcut opened the app (it was not running, or not ready to call out of sight): once the
    // server is up, open the last chat that had a call and call as the call button does (voice picker, mic permission)
    val startVoiceCallNow by rememberUpdatedState(onStartVoiceCall)
    LaunchedEffect(Unit) {
        ai.antigem.data.voice.VoiceCallLauncher.pending.collect { at ->
            if (at == 0L) return@collect
            val serverUp = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                viewModel.systemConnectionState.firstOrNull { it.canSend }
            } != null
            if (!ai.antigem.data.voice.VoiceCallLauncher.consumePending()) return@collect
            when {
                ai.antigem.data.voice.AgyVoiceCall.state.value.active -> ai.antigem.data.voice.AgyVoiceCall.setMinimized(false)
                !serverUp -> ai.antigem.ui.components.AppToastHelper.showToast(
                    L.chat.screen.startTheServerToCallAntigravity, ai.antigem.ui.components.ChatToastType.ERROR
                )
                else -> {
                    viewModel.openLastCallChat()
                    startVoiceCallNow()
                }
            }
        }
    }
    // the model picked for the chat changed during the call: the agent plans with it from now on
    LaunchedEffect(agyPickedModel, voiceCall.inCall) {
        if (voiceCall.inCall) ai.antigem.data.voice.AgyVoiceCall.setPlanModel(viewModel.agyBackend.planModelNumber())
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

    // Per-agent health for the status dot, accounts, new-chat picker and empty chat (only enabled agents)
    val enabledAgents by viewModel.enabledAgents.collectAsState()
    val hasChosenAgents by viewModel.hasChosenAgents.collectAsState()
    val isApplyingAgents by viewModel.isApplyingAgents.collectAsState()
    val claudeStartup by claudeBackend.startupStatus.collectAsState()
    val claudeModelList by claudeBackend.availableModels.collectAsState()
    val claudeModelsError by claudeBackend.modelsError.collectAsState()
    val claudeModelsRefreshing by claudeBackend.isRefreshingModels.collectAsState()
    var showClaudeUsage by remember { mutableStateOf(false) }
    var showClaudeContext by remember { mutableStateOf(false) }
    val claudeState by viewModel.claudeAccount.state.collectAsState()
    // a chat on a custom provider's model needs no Claude login: it can send (and shows no sign-in prompt) signed out
    val claudeChatState = if (
        ai.antigem.data.agent.claude.ClaudeChatBackend.providerOf(selectedModelId).isNotEmpty() &&
        (claudeState == ai.antigem.data.agent.claude.ClaudeStatus.SIGNED_OUT || claudeState == ai.antigem.data.agent.claude.ClaudeStatus.SIGNING_IN)
    ) ai.antigem.data.agent.claude.ClaudeStatus.READY else claudeState
    val claudeStatusError by viewModel.claudeAccount.statusError.collectAsState()
    // a reply failed because the Claude sign-in expired while the app stayed open: once the re-check confirms the
    // sign-out, show the sign-in (as after a fresh start) instead of only the error; custom-provider chats need no login
    val claudeNeedsLogin by claudeBackend.needsLogin.collectAsState()
    LaunchedEffect(claudeNeedsLogin, claudeChatState) {
        if (claudeNeedsLogin && isClaudeChat && claudeChatState == ai.antigem.data.agent.claude.ClaudeStatus.SIGNED_OUT) showClaudeLogin = true
    }
    val openClaudeSetup = {
        claudeSettingsPage = ai.antigem.ui.claude.ClaudeSettingsPage.ACCOUNT
        showSettingsDialog = true
    }
    val serverRestarting = serverStatus is ai.antigem.data.local.LocalServerStatus.Starting ||
        serverStatus is ai.antigem.data.local.LocalServerStatus.Stopping
    val restartLocalServer = {
        ai.antigem.ui.components.AppToastHelper.showToast(if (AuthPreferences.currentRemoteMode) L.chat.screen.restartingTheServerOverSsh else L.chat.screen.restartingTheLocalServer, ai.antigem.ui.components.ChatToastType.INFO)
        ai.antigem.data.local.LocalServerManager.restartServer(context)
    }
    val recheckClaude = {
        ai.antigem.ui.components.AppToastHelper.showToast(L.chat.screen.checkingClaudeCode, ai.antigem.ui.components.ChatToastType.INFO)
        viewModel.claudeAccount.refreshStatus()
    }
    val agyCliInfo by ai.antigem.data.remote.BridgeInfo.agyCli.collectAsState()
    val agyNotInstalled = ai.antigem.domain.model.AgentKind.AGY in enabledAgents &&
        ai.antigem.ui.agents.agyNotInstalled(systemConnectionState, agyCliInfo)
    // installs right away; Settings → Antigravity → Account & CLI shows the progress (the hub starts when it is done)
    val installAgy = {
        ai.antigem.data.remote.BridgeInfo.installAgy()
        requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.AGY_CLI
        showSettingsDialog = true
    }
    val agyEntry = if (ai.antigem.domain.model.AgentKind.AGY in enabledAgents) {
        ai.antigem.ui.agents.agyStatusEntry(
            state = systemConnectionState,
            authInfo = agyAuthInfo,
            isAuthBusy = isAuthBusy,
            inStartupGrace = isInitialGracePeriod,
            serverRestarting = serverRestarting,
            onSignIn = { viewModel.loginToAgyHub(force = true) },
            onCheckAuth = { viewModel.checkAgyAuthStatus(userInitiated = true) },
            onRetry = {
                ai.antigem.ui.components.AppToastHelper.showToast(L.chat.screen.reconnectingToAntigravity, ai.antigem.ui.components.ChatToastType.INFO)
                viewModel.retryConnections()
            },
            onRestartServer = restartLocalServer,
            notInstalled = agyNotInstalled,
            onInstall = installAgy
        )
    } else null
    val claudeEntry = if (ai.antigem.domain.model.AgentKind.CLAUDE in enabledAgents) {
        ai.antigem.ui.agents.claudeStatusEntry(
            state = claudeState,
            status = claudeStatus,
            error = claudeStatusError,
            serverRestarting = serverRestarting,
            onSignIn = { showClaudeLogin = true },
            onSetup = openClaudeSetup,
            onRestartServer = restartLocalServer,
            onRetry = recheckClaude
        )
    } else null
    val openCodeEntry = if (ai.antigem.domain.model.AgentKind.OPENCODE in enabledAgents) {
        ai.antigem.ui.agents.openCodeStatusEntry(
            state = openCodeState,
            status = openCodeStatus,
            serverRestarting = serverRestarting,
            // installs right away; Settings → OpenCode shows the progress
            onSetup = {
                viewModel.openCodeBackend.startCliJob("install")
                openCodeSettingsPage = ai.antigem.ui.opencode.OpenCodeSettingsPage.SERVER
                showSettingsDialog = true
            },
            onRestartServer = restartLocalServer,
            onRetry = { viewModel.openCodeBackend.retryConnections() }
        )
    } else null
    val codexEntry = if (ai.antigem.domain.model.AgentKind.CODEX in enabledAgents) {
        ai.antigem.ui.agents.codexStatusEntry(
            state = codexState,
            status = codexStatus,
            account = codexAccount,
            serverRestarting = serverRestarting,
            // installs right away; Settings → Codex shows the progress
            onSetup = {
                codexBackend.startCliJob("install")
                codexSettingsPage = ai.antigem.ui.codex.CodexSettingsPage.ACCOUNT
                showSettingsDialog = true
            },
            // starts the sign-in; Settings → Codex shows the code or the browser link
            onSignIn = {
                codexBackend.startLogin(deviceCode = ai.antigem.data.preferences.AuthPreferences.currentRemoteMode)
                codexSettingsPage = ai.antigem.ui.codex.CodexSettingsPage.ACCOUNT
                showSettingsDialog = true
            },
            onRestartServer = restartLocalServer,
            onRetry = { codexBackend.retryConnections() }
        )
    } else null
    val agentEntries = listOfNotNull(agyEntry, claudeEntry, openCodeEntry, codexEntry)

    // Codex's "/" commands that open a screen of the app instead of going to Codex
    val handleCodexCommand: (ai.antigem.data.agent.codex.CodexChatBackend.AppCommand) -> Unit = { cmd ->
        when (cmd) {
            ai.antigem.data.agent.codex.CodexChatBackend.AppCommand.MODEL_PICKER -> showModelSelector = true
            ai.antigem.data.agent.codex.CodexChatBackend.AppCommand.SETTINGS -> { openCodexSettings = true; showSettingsDialog = true }
            ai.antigem.data.agent.codex.CodexChatBackend.AppCommand.MCP -> {
                codexSettingsPage = ai.antigem.ui.codex.CodexSettingsPage.MCP
                showSettingsDialog = true
            }
            ai.antigem.data.agent.codex.CodexChatBackend.AppCommand.NEW_CHAT -> viewModel.startNewChat(ai.antigem.domain.model.AgentKind.CODEX)
        }
    }

    // Slash commands Claude's CLI only shows in a terminal: the app opens its own screen instead
    val handleClaudeCommand: (ai.antigem.data.agent.claude.ClaudeAppCommand) -> Unit = { cmd ->
        when (cmd) {
            ai.antigem.data.agent.claude.ClaudeAppCommand.Usage -> showClaudeUsage = true
            ai.antigem.data.agent.claude.ClaudeAppCommand.Context -> showClaudeContext = true
            ai.antigem.data.agent.claude.ClaudeAppCommand.ModelPicker -> showModelSelector = true
            ai.antigem.data.agent.claude.ClaudeAppCommand.Mcp -> {
                claudeSettingsPage = ai.antigem.ui.claude.ClaudeSettingsPage.MCP
                showSettingsDialog = true
            }
            ai.antigem.data.agent.claude.ClaudeAppCommand.Settings -> {
                claudeSettingsPage = ai.antigem.ui.claude.ClaudeSettingsPage.DEFAULTS
                showSettingsDialog = true
            }
            ai.antigem.data.agent.claude.ClaudeAppCommand.NewChat -> viewModel.startNewChat(ai.antigem.domain.model.AgentKind.CLAUDE)
            is ai.antigem.data.agent.claude.ClaudeAppCommand.Done ->
                ai.antigem.ui.components.AppToastHelper.showToast(cmd.message, ai.antigem.ui.components.ChatToastType.SUCCESS)
        }
    }
    var showAccountsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.authFeedbackMessage.collect { msg ->
            showToast(msg)
        }
    }

    LaunchedEffect(Unit) {
        ai.antigem.ui.components.AppToastHelper.toastFlow.collect { toast ->
            currentToast = toast
        }
    }

    val attachments by viewModel.attachments.collectAsState()
    val isUploadingAttachment by viewModel.isUploadingAttachment.collectAsState()
    val attachmentProgress by viewModel.attachmentProgress.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachmentsFromUris(uris, context)
        }
    }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempCameraUri?.let { uri ->
                viewModel.addAttachmentsFromUris(listOf(uri), context)
            }
        }
    }

    val launchCamera: () -> Unit = {
        try {
            val photoFile = File.createTempFile("camera_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            tempCameraUri = uri
            cameraPhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e("ChatScreen", "Failed to launch camera", e)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            launchCamera()
        }
    }

    val onTakePhotoClick: () -> Unit = {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
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
    // The keyboard edits this buffer directly (like a native EditText). Holding the text as a TextFieldValue here made
    // every key press round-trip through this screen; with long text that lagged and some keyboards (e.g. Samsung)
    // lost track of the word being typed, merging words ("I a" -> "Ia").
    val inputState = remember {
        viewModel.getDraft(currentConv?.id ?: "new").let { TextFieldState(it.text, it.selection) }
    }

    LaunchedEffect(currentConv?.id) {
        val newKey = currentConv?.id ?: "new"
        if (newKey != activeConversationKey) {
            viewModel.setDraft(activeConversationKey, TextFieldValue(inputState.text.toString(), inputState.selection))
            activeConversationKey = newKey
            val draft = viewModel.getDraft(newKey)
            inputState.edit {
                replace(0, length, draft.text)
                selection = draft.selection
            }
            ToolCallExpansionCache.setChat(newKey)
            CodeBlockExpansionCache.setChat(newKey)
        }
    }
    // Keep the open chat's draft in step with the input, after each frame rather than inside the key press
    LaunchedEffect(inputState) {
        snapshotFlow { activeConversationKey to TextFieldValue(inputState.text.toString(), inputState.selection) }
            .collect { (key, value) ->
                viewModel.setDraft(key, value)
                if (value.text.isNotEmpty()) viewModel.onUserStartedTyping()
            }
    }
    // Run panel → "Send to chat": a run's command and output land in the message box (added after any draft), not sent
    if (!isInFloatingWindow) {
        val runHandoff by ai.antigem.data.run.RunPanel.chatHandoff.collectAsState()
        LaunchedEffect(runHandoff) {
            val text = runHandoff ?: return@LaunchedEffect
            val current = inputState.text.toString()
            inputState.setTextAndPlaceCursorAtEnd(if (current.isBlank()) text else current.trimEnd() + "\n\n" + text)
            ai.antigem.data.run.RunPanel.chatHandoff.value = null
        }
    }
    // The `/` and `@` popups depend only on the command / mention being typed, so ordinary typing doesn't redraw this screen
    val slashTrigger by remember {
        derivedStateOf {
            val text = inputState.text
            val index = text.lastIndexOf('/')
            val isCommand = index == 0 || (index > 0 && text[index - 1].isWhitespace())
            if (isCommand && !text.subSequence(index, text.length).contains(' ')) {
                SlashTrigger(index, text.subSequence(index + 1, text.length).toString(), text.subSequence(0, index).isBlank())
            } else null
        }
    }
    val mentionTrigger by remember {
        derivedStateOf {
            val text = inputState.text
            val index = text.lastIndexOf('@')
            if (index >= 0 && !text.subSequence(index + 1, text.length).contains(' ')) {
                MentionTrigger(index, text.subSequence(index + 1, text.length).toString())
            } else null
        }
    }
    var pendingMessageAction by remember { mutableStateOf<PendingMessageAction?>(null) }
    // Project checkpoints: the open chat's restore points, the "go back" sheet, and the one-time "track this project?"
    val messageCheckpoints by viewModel.messageCheckpoints.collectAsState()
    val checkpointSettings by ai.antigem.data.checkpoint.ProjectCheckpoints.settings.collectAsState()
    var restoreSheetFor by remember { mutableStateOf<Pair<ChatMessage, ai.antigem.data.checkpoint.CheckpointSummary>?>(null) }
    var trackProjectAsk by remember { mutableStateOf<ai.antigem.data.checkpoint.CheckpointProjectState?>(null) }
    val askedTrackProjects = remember { mutableSetOf<String>() }
    val newChatProjectPath = ai.antigem.data.checkpoint.ProjectCheckpoints.pathOf(currentConv?.workspaceUri)
    val newChatEmpty = messages.isEmpty()
    LaunchedEffect(newChatProjectPath, newChatEmpty, checkpointSettings?.mode, currentConv?.isCloud) {
        val path = newChatProjectPath ?: return@LaunchedEffect
        if (!newChatEmpty || currentConv?.isCloud == true) return@LaunchedEffect
        if (checkpointSettings?.mode != ai.antigem.data.checkpoint.CheckpointSettings.MODE_SELECTED) return@LaunchedEffect
        if (path in askedTrackProjects) return@LaunchedEffect
        delay(400) // the project picker closes first
        val st = ai.antigem.data.checkpoint.ProjectCheckpoints.projectState(path).getOrNull() ?: return@LaunchedEffect
        if (st.shouldAsk) {
            askedTrackProjects.add(path)
            trackProjectAsk = st
        }
    }
    val thinkingPref by viewModel.thinkingPreference.collectAsState()
    val terminatedToolDialogState by viewModel.terminatedToolDialog.collectAsState()
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

    val allSlashCommands by ai.antigem.data.remote.SlashCommandsCache.commands.collectAsState()
    val codexSlashCommands by codexBackend.slashCommands.collectAsState()
    val openCodeSlashCommands by openCodeBackend.slashCommands.collectAsState()
    // each agent's own list (what it really loads: the user's, the plugins' and the project's skills, its commands)
    val effectiveSlashCommands = if (isClaudeChat) claudeSlashCommands else if (isCodexChat) codexSlashCommands else if (isOpenCodeChat) openCodeSlashCommands else allSlashCommands

    // the "/" menu follows the open chat's project
    LaunchedEffect(currentConv?.id, currentConv?.workspaceUri, currentConv?.agent) {
        when {
            isClaudeChat -> claudeBackend.refreshInfoForChat()
            isCodexChat -> codexBackend.refreshSkills(force = false)
            isOpenCodeChat -> openCodeBackend.refreshSlashCommands()
        }
    }

    LaunchedEffect(agyHubUrl, systemConnectionState) {
        if (systemConnectionState.isHubOnline) {
            ai.antigem.data.remote.SlashCommandsCache.getCommands(agyHubUrl)
            val curId = currentConv?.id
            val isExisting = currentConv != null && currentConv?.title != "New Chat" && conversations.any { it.id == currentConv?.id }
            if (!curId.isNullOrBlank() && curId != "new" && isExisting && messages.isEmpty()) {
                viewModel.startPersistentStream(curId)
            }
        }
    }

    // Granular block-level feed item expansion from pre-warmed background cache (0ms UI thread work)
    // model ids → display names for the "Switched to …" dividers (Claude: resolved ids; Antigravity: enum names)
    val claudeModelInfos by claudeBackend.modelInfos.collectAsState()
    val modelNameOf: (String) -> String = remember(isClaudeChat, claudeModelInfos, availableModels) {
        { id ->
            if (isClaudeChat) {
                (claudeModelInfos.firstOrNull { it.resolvedModel == id && it.value != "default" } ?: claudeModelInfos.firstOrNull { it.value == id })
                    ?.displayName?.takeIf { it.isNotBlank() } ?: id.removePrefix("claude-")
            } else {
                availableModels.firstOrNull { it.id == id || it.key == id }?.displayName ?: id
            }
        }
    }
    // Antigravity voice calls of this chat (saved by the hub) and the one going on now, as markers in the chat
    val agyVoiceChats by ai.antigem.data.voice.AgyVoiceChats.chats.collectAsState()
    LaunchedEffect(currentConv?.id, isAgyChat) {
        if (isAgyChat) currentConv?.id?.let { ai.antigem.data.voice.AgyVoiceChats.refresh(it) }
    }
    val voiceCallItems = remember(agyVoiceChats, currentConv?.id, isAgyChat, voiceCall.active, voiceCall.conversationId, voiceCall.startedAtWall) {
        val id = currentConv?.id
        if (!isAgyChat || id == null) emptyList() else {
            val live = voiceCall.active && voiceCall.conversationId == id && voiceCall.startedAtWall > 0
            buildList {
                agyVoiceChats[id].orEmpty().forEach { r ->
                    val start = r.startedAtMs ?: return@forEach
                    // the call going on now is shown live, not from an unfinished saved copy
                    if (live && r.stoppedAtMs == null) return@forEach
                    add(ChatFeedItem.VoiceCall("${id}_$start", start, r.durationMs, live = false, record = r))
                }
                if (live) add(ChatFeedItem.VoiceCall("${id}_live", voiceCall.startedAtWall, null, live = true, record = null))
            }.sortedBy { it.startedAt }
        }
    }
    var openVoiceTranscript by remember { mutableStateOf<ai.antigem.data.voice.VoiceChatRecord?>(null) }
    openVoiceTranscript?.let { rec ->
        ai.antigem.ui.voice.AgyVoiceTranscriptSheet(rec) { openVoiceTranscript = null }
    }
    // the step pills the user opened (Settings → Appearance → Group Agent Steps): only for this visit of this chat,
    // so another chat (or coming back) starts with every pill closed
    val openStepGroups = remember(currentConv?.id) { mutableStateMapOf<String, Boolean>() }
    val expandedStepGroups = openStepGroups.keys.toSet()
    val convKey = currentConv?.id ?: "empty"

    // The chat's rows are built off the main thread: parsing replies (a long one again on every update while it
    // streams, or every message when a big chat opens) never holds up drawing. Updates that come while one is being
    // built are merged: only the newest is built. The rows arrive with the messages they were made from (FeedSnapshot),
    // and everything below that compares messages with rows reads that snapshot, so the two never disagree.
    val feedInput = remember(convKey, messages, selectedModelId, modelNameOf, voiceCallItems) {
        FeedInput(convKey, messages, selectedModelId, modelNameOf, voiceCallItems)
    }
    val latestFeedInput by rememberUpdatedState(feedInput)
    var builtFeed by remember { mutableStateOf<FeedSnapshot?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { latestFeedInput }
            .conflate()
            .collect { input ->
                val built = withContext(Dispatchers.Default) {
                    try {
                        FeedSnapshot.build(input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Could not build the chat's rows", e)
                        null
                    }
                }
                if (built != null) builtFeed = built
            }
    }
    // rows built while this chat had no messages yet (the moment it opened) don't count once its messages are here:
    // the loading view stays until the real rows exist, never an empty list in between
    val builtForThisChat = builtFeed?.takeIf { it.convKey == convKey && (it.messages.isNotEmpty() || messages.isEmpty()) }
    // no rows for this chat yet (it was just opened): when every message is parsed already (coming back to it) they
    // are built right here, so there is never a frame without them; otherwise the loading view shows until they come
    val instantFeed = remember(feedInput, builtForThisChat == null) {
        if (builtForThisChat == null && ChatFeedCache.allParsed(messages)) FeedSnapshot.build(feedInput) else null
    }
    val feedSnapshot = builtForThisChat ?: instantFeed
    /** The messages the rows on screen were made from (the live [messages] may be a frame ahead). */
    val rowMessages = feedSnapshot?.messages ?: emptyList()
    val feedNotReady = feedSnapshot == null && messages.isNotEmpty()
    // folding steps into pills parses nothing: done here, so opening or closing a pill is instant
    val feedItems = remember(feedSnapshot, groupAgentSteps, groupStepsMin, expandedStepGroups) {
        StepGroups.apply(feedSnapshot?.rows ?: emptyList(), groupAgentSteps, groupStepsMin, expandedStepGroups, rowMessages)
    }

    // Fresh LazyListState per conversation — restores saved position if returning, or initializes directly at bottom
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
    val isAtBottomState = remember(listState) {
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
    val isAtBottom by isAtBottomState

    // The rows, the list and "at bottom" as they are right now, for actions that run on a tap (opening a link, a Jarvis
    // card, editing a prompt): read when tapped, so those actions are made once and don't change on every update
    val linkRows by rememberUpdatedState(feedItems)
    val linkListState by rememberUpdatedState(listState)
    val linkAtBottomState by rememberUpdatedState(isAtBottomState)
    val latestRowMessages by rememberUpdatedState(rowMessages)

    // C: is any row of the newest message on screen? While none is, message updates are paced (see readingOlder)
    val newestMessageFirstRow = remember(feedItems) { firstRowOfNewestMessage(feedItems) }
    LaunchedEffect(listState, newestMessageFirstRow) {
        snapshotFlow {
            // nothing laid out yet counts as on screen: updates are never held back while a chat opens
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible < newestMessageFirstRow
        }.collect { readingOlder.value = it }
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
    val imeInsets = WindowInsets.imeAnimationTarget
    var isKeyboardAnimating by remember { mutableStateOf(false) }

    val focusCoordinator = ai.antigem.ui.components.LocalAppFocusCoordinator.current
    val chatInputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    DisposableEffect(focusCoordinator) {
        focusCoordinator.registerScreen(
            mode = ai.antigem.AppViewMode.CHAT,
            onRequestFocus = {
                try {
                    chatInputFocusRequester.requestFocus()
                } catch (_: Exception) {}
            },
            onClearFocus = {
                focusManager.clearFocus(force = true)
            }
        )
        onDispose {
            focusCoordinator.unregisterScreen(ai.antigem.AppViewMode.CHAT)
        }
    }

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

    // Re-enable auto-scroll whenever the user scrolls back to the bottom. Watched as a flow, not read by the screen:
    // a flag read here would rebuild the whole screen each time it flips while scrolling
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottomState.value }.collect { atBottom ->
            if (atBottom) {
                shouldAutoScroll = true
            }
        }
    }

    // Hide scroll button after scroll stops (a new scroll cancels the wait, as before)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling && showScrollButton) {
                delay(4000)
                showScrollButton = false
            }
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

    val isDarkTheme = ai.antigem.theme.isAppInDarkTheme()

    // Background prewarm to ensure zero UI-thread lag during scroll (recent window of 12 messages)
    LaunchedEffect(messages.size, isDarkTheme) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ChatFeedCache.prewarm(messages, windowSize = 12, isDark = isDarkTheme)
        }
    }


    // Scroll to bottom when user explicitly sends a message (instant), and once more when the rows hold the prompt
    // just sent (they are built off the main thread, so it may come a frame later); given up after a few seconds
    var promptsAtSend by remember(convKey) { mutableStateOf(-1) }
    LaunchedEffect(userSentMessageTrigger) {
        if (userSentMessageTrigger > 0) {
            shouldAutoScroll = true
            promptsAtSend = rowMessages.count { it.role == ai.antigem.domain.model.MessageRole.USER }
            if (feedItems.isNotEmpty()) listState.scrollToItem(feedItems.size)
            delay(3_000)
            promptsAtSend = -1
        }
    }
    LaunchedEffect(feedSnapshot) {
        val before = promptsAtSend
        if (before >= 0 && feedItems.isNotEmpty() &&
            rowMessages.count { it.role == ai.antigem.domain.model.MessageRole.USER } > before) {
            promptsAtSend = -1
            listState.scrollToItem(feedItems.size)
        }
    }

    // Smart auto-scroll during streaming & tool execution: follows live stream and tool calls smoothly without getting stuck
    val lastMsg = rowMessages.lastOrNull()
    val lastToolCalls = lastMsg?.toolCalls.orEmpty()
    val isRunningOrStreaming = isActivelyRunning || (lastMsg?.isStreaming == true)

    // The end of a turn is one step: the working row stays until the reply's footer (tokens, actions) exists, then
    // the two cross-fade in place while the end of the chat stays pinned (no animated scroll afterwards)
    val lastIsAssistantWithText = lastMsg?.role == ai.antigem.domain.model.MessageRole.ASSISTANT && lastMsg.content.isNotEmpty()
    val footerShown = feedItems.lastOrNull() is ChatFeedItem.AssistantFooter
    var tailFinishing by remember(convKey) { mutableStateOf(false) }
    var wasRunning by remember(convKey) { mutableStateOf(isRunningOrStreaming) }
    LaunchedEffect(convKey, isRunningOrStreaming) {
        val finished = wasRunning && !isRunningOrStreaming
        wasRunning = isRunningOrStreaming
        if (!finished) {
            tailFinishing = false
            return@LaunchedEffect
        }
        tailFinishing = true
        delay(1_000) // safety: never hold the end longer than this
        tailFinishing = false
    }
    LaunchedEffect(tailFinishing, footerShown, lastIsAssistantWithText) {
        if (tailFinishing && (footerShown || !lastIsAssistantWithText)) {
            // keep pinning for the frames in which the footer is laid out and fades in
            delay(300)
            tailFinishing = false
        }
    }
    val holdingTail = tailFinishing && lastIsAssistantWithText && !footerShown

    // Applied in the same layout pass as the new content (not a frame later), so the end of the chat (the working
    // row) never drops below the screen while the reply grows: the output grows above it
    FollowTail(
        listState = listState,
        rows = feedItems,
        working = isRunningOrStreaming || tailFinishing,
        shouldAutoScroll = { shouldAutoScroll },
        isUserDragging = { isUserDragging }
    )

    // The Claude Code-style working row: under the reply for as long as the agent works, but not while it waits for
    // the user (an approval or a question)
    val waitingForUser = (isClaudeChat && claudePending.isNotEmpty()) || (isOpenCodeChat && openCodePending.isNotEmpty()) || (isCodexChat && codexPending.isNotEmpty()) ||
        lastToolCalls.any { it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE" }
    val showWorkingRow = (isRunningOrStreaming || holdingTail) && !waitingForUser && !footerShown

    val currentModel = if (isClaudeChat) {
        claudeModelList.find { it.id == selectedModelId } ?: AiModel(
            id = selectedModelId.ifBlank { "default" },
            displayName = when {
                claudeModelsRefreshing -> L.chat.screen.loadingModels
                claudeModelsError != null -> L.chat.screen.modelsUnavailable
                else -> L.chat.screen.chooseModel
            },
            family = ai.antigem.domain.model.ModelFamily.CLAUDE
        )
    } else if (isOpenCodeChat) {
        enabledModels.find { it.id == selectedModelId } ?: AiModel(
            id = selectedModelId.ifBlank { "default" },
            displayName = when {
                isRefreshingModels -> L.chat.screen.loadingModels
                openCodeModelsError != null -> L.chat.screen.noModels
                selectedModelId.isNotBlank() -> selectedModelId.substringAfter('/')
                else -> L.chat.screen.chooseModel
            }
        )
    } else if (isCodexChat) {
        enabledModels.find { it.id == selectedModelId } ?: AiModel(
            id = selectedModelId.ifBlank { "default" },
            displayName = when {
                isRefreshingModels -> L.chat.screen.loadingModels
                codexModelsError != null -> L.chat.screen.noModels
                selectedModelId.isNotBlank() -> selectedModelId
                else -> L.chat.screen.chooseModel
            },
            family = ai.antigem.domain.model.ModelFamily.OTHER
        )
    } else AiModel.findInList(enabledModels, selectedModelId, preferredModelName)
    val currentQuota = quotas.find { it.modelId == selectedModelId }

    val activeChatProject by IdeDaemonManager.activeProject.collectAsState()
    val usedProjects by IdeDaemonManager.projects.collectAsState()
    var showProjectDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        IdeDaemonManager.loadProjects(conversations)
    }

    LaunchedEffect(Unit) {
        IdeDaemonManager.serverReconnectedEvent.collect {
            IdeDaemonManager.loadProjects(conversations)
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

    /** Opens a chat Jarvis points at (a task of its team) and keeps the way back to the Jarvis chat. */
    // made once (it goes to the Jarvis cards in the list): reads the chat, rows and scroll position when tapped
    val openJarvisLinkedChat: (String) -> Unit = remember(viewModel, conversationBackStack) {
        { targetId ->
            val currentId = currentConv?.id
            if (!currentId.isNullOrBlank() && !currentId.equals(targetId, ignoreCase = true)) {
                if (linkRows.isNotEmpty()) {
                    ConversationScrollCache.save(currentId, linkListState.firstVisibleItemIndex, linkListState.firstVisibleItemScrollOffset, linkAtBottomState.value)
                }
                conversationBackStack.add(currentId)
            }
            viewModel.selectConversation(targetId)
        }
    }
    val navigateBackConversation: () -> Unit = {
        while (conversationBackStack.isNotEmpty()) {
            val prevId = conversationBackStack.removeAt(conversationBackStack.lastIndex)
            // Jarvis chats are not in `conversations` (listed apart, under Jarvis) but are valid places to go back to
            if (conversations.isEmpty() || conversations.any { it.id.equals(prevId, ignoreCase = true) } ||
                jarvisConversations.any { it.id.equals(prevId, ignoreCase = true) } || prevId in jarvisManagers.keys) {
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

    // tapped paths that are not Markdown: text opens in the IDE, anything else (video, PDF, archive…) asks which app
    val fileLinkOpener = ai.antigem.ui.files.rememberFileLinkOpener(
        onOpenedInIde = onNavigateToIde,
        onPreviewMarkdown = { mdPath ->
            scope.launch {
                val content = try {
                    val f = File(mdPath)
                    if (f.exists()) f.readText() else (IdeApiClient.readFile(mdPath) ?: "")
                } catch (e: Exception) {
                    IdeApiClient.readFile(mdPath) ?: ""
                }
                activeMarkdownDoc = mdPath to content
            }
        },
        onShowFolder = onNavigateToFiles?.let { showFiles ->
            { folder: String ->
                ai.antigem.data.files.ExplorerNav.open("screen", folder)
                showFiles()
            }
        }
    )

    // What the link handler needs, as it is when a link is tapped. The handler itself is made once: it goes to every
    // row through a static composition local, so a new one rebuilt the whole screen (it was made again whenever
    // "at bottom" flipped, a row was added or the chat list refreshed, which made scrolling and streaming stutter)
    val linkFileOpener by rememberUpdatedState(fileLinkOpener)
    val fileLinkHandler = remember(scope) {
        FileLinkHandler(
            onOpenFile = { rawUrl ->
                try {
                    val decoded = try { java.net.URLDecoder.decode(rawUrl, "UTF-8") } catch (_: Exception) { rawUrl }
                    val cleanPath = resolveChatFileLink(decoded.removePrefix("file://").substringBefore("#"), currentConv?.workspaceUri)
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
                        linkFileOpener.open(cleanPath)
                    }
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Error opening file link $rawUrl", e)
                }
            },
            onShowDetails = { rawUrl ->
                val decoded = try { java.net.URLDecoder.decode(rawUrl, "UTF-8") } catch (_: Exception) { rawUrl }
                val cleanPath = resolveChatFileLink(decoded.removePrefix("file://").substringBefore("#"), currentConv?.workspaceUri)
                activeFileDetailsPath = cleanPath
            },
            onOpenConversation = { convId ->
                val cleanId = convId.trim()
                val target = conversations.find { it.id.equals(cleanId, ignoreCase = true) }
                if (target != null) {
                    val currentId = currentConv?.id
                    if (!currentId.isNullOrBlank() && !currentId.equals(target.id, ignoreCase = true)) {
                        if (linkRows.isNotEmpty()) {
                            ConversationScrollCache.save(currentId, linkListState.firstVisibleItemIndex, linkListState.firstVisibleItemScrollOffset, linkAtBottomState.value)
                        }
                        conversationBackStack.add(currentId)
                    }
                    viewModel.selectConversation(target.id)
                    if (drawerState.isOpen) {
                        scope.launch { drawerState.close() }
                    }
                } else {
                    android.widget.Toast.makeText(context, L.chat.screen.thisConversationDoesntExist, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    LaunchedEffect(fileLinkHandler) {
        ai.antigem.ui.components.ActiveFileLinkHandlerHolder.current = fileLinkHandler
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
        ai.antigem.ui.components.LocalSnackbarHostState provides snackbarHostState
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // while the artifacts panel or fullscreen floating terminal is open over it, keep TalkBack & D-pad focus out of the chat behind it
                modifier = Modifier.hiddenWhileOffscreen(!showArtifactsDrawer && !(showLocalTerminalDialog && isInFloatingWindow)),
        drawerContent = {
            ChatHistoryDrawer(
                conversations = conversations,
                jarvisEnabled = jarvisEnabled,
                jarvisConversations = jarvisConversations,
                onNewJarvisChat = { agent ->
                    val currentId = currentConv?.id
                    if (!currentId.isNullOrBlank() && feedItems.isNotEmpty()) {
                        ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                    }
                    conversationBackStack.clear()
                    val problem = ai.antigem.data.jarvis.JarvisController.startTextChat(agent)
                    if (problem != null) android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
                    else scope.launch { drawerState.close() }
                },
                enabledAgents = enabledAgents,
                currentConversationId = currentConv?.id,
                activeInstances = activeInstances,
                isLoading = isConversationsLoading,
                hasReceivedInitialSync = hasReceivedInitialSync,
                systemConnectionState = systemConnectionState,
                errorMessage = conversationError,
                isStreaming = isActivelyRunning,
                groupByWorkspace = groupChatsByWorkspace,
                showArchived = showArchivedChats,
                onToggleShowArchived = { viewModel.setShowArchivedChats(it) },
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
                },
                // the sidebar stays open: the picker floats over it so the new look shows on the sidebar and the chat
                onOpenThemes = { showThemePanel = !showThemePanel },
                pcButton = if (pcConnectEnabled) ({ ai.antigem.ui.pc.PcDrawerButton(onClick = { showPcSheet = true }) }) else null,
                agyEnabled =ai.antigem.domain.model.AgentKind.AGY in enabledAgents,
                accountsButton = {
                    ai.antigem.ui.agents.AccountsButton(
                        agy = agyEntry,
                        claude = claudeEntry,
                        agyAuth = agyAuthInfo,
                        claudeStatus = claudeStatus,
                        onClick = { showAccountsDialog = true },
                        openCode = openCodeEntry,
                        codex = codexEntry,
                        codexEmail = codexAccount?.email
                    )
                },
                claudeActions = remember(viewModel) {
                    ai.antigem.ui.drawer.ClaudeDrawerActions(
                        loadMessages = { id ->
                            if (viewModel.openCodeBackend.conversations.value.any { it.id == id }) viewModel.loadOpenCodeMessages(id)
                            else if (viewModel.codexBackend.conversations.value.any { it.id == id }) viewModel.loadCodexMessages(id)
                            else viewModel.loadClaudeMessages(id)
                        },
                        rename = { id, title -> viewModel.renameConversation(id, title) },
                        archive = { id, archived -> viewModel.setConversationArchived(id, archived) },
                        restart = { id, onDone -> viewModel.claudeBackend.restartChat(id, onDone) }
                    )
                }
            )
        }
    ) {
        ai.antigem.ui.agents.BridgeVersionDialog()
        if (pendingLoginUrl != null) {
            val targetUrl = pendingLoginUrl!!
            val remoteSignIn = AuthPreferences.currentRemoteMode
            var pastedCallback by remember(targetUrl) { mutableStateOf("") }
            var callbackStatus by remember(targetUrl) { mutableStateOf<String?>(null) }
            var callbackBusy by remember(targetUrl) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingLoginUrl() },
                title = {
                    Text(
                        text = L.chat.screen.signInWithBrowser,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text = L.chat.screen.antigravityRequiresGoogleAuthentication,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!remoteSignIn) {
                            Text(
                                text = L.chat.screen.doYouWantToOpenYour,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = L.chat.screen.n1OpenTheBrowserAndSign,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pastedCallback,
                                onValueChange = { pastedCallback = it; callbackStatus = null },
                                label = { Text("Address starting with http://localhost") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            callbackStatus?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = if (it.startsWith("✓")) ai.antigem.theme.AppColors.success else Color.Red
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (remoteSignIn) {
                            Button(
                                onClick = {
                                    callbackBusy = true
                                    callbackStatus = L.chat.screen.sendingToTheServer
                                    viewModel.forwardLoginCallback(pastedCallback) { result ->
                                        callbackBusy = false
                                        result.fold(
                                            {
                                                callbackStatus = L.chat.screen.sentFinishingSignIn
                                                viewModel.clearPendingLoginUrl()
                                            },
                                            { callbackStatus = "✗ ${it.message}" }
                                        )
                                    }
                                },
                                enabled = pastedCallback.isNotBlank() && !callbackBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text(L.chat.screen.finish, fontWeight = FontWeight.SemiBold)
                            }
                        }
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
                                // a remote sign-in stays open for the paste step
                                if (!remoteSignIn) viewModel.clearPendingLoginUrl()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text(L.chat.screen.openBrowser, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearPendingLoginUrl() }) {
                        Text(L.common.cancel)
                    }
                }
            )
        }
        agyLoginIssue?.let { issue ->
            val verifyUrl = issue.verificationUrl
            AlertDialog(
                onDismissRequest = { viewModel.clearAgyLoginIssue() },
                title = {
                    Text(
                        text = if (verifyUrl != null) L.chat.screen.accountVerificationRequired else L.chat.screen.cantSignInToAntigravity,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                text = {
                    Text(
                        text = issue.message,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    if (verifyUrl != null) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatScreen", "Failed to open browser: ${e.message}")
                                }
                                viewModel.clearAgyLoginIssue()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text(L.chat.screen.verifyAccount, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.clearAgyLoginIssue() },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text(L.common.ok, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                dismissButton = if (verifyUrl != null) {
                    {
                        TextButton(onClick = { viewModel.clearAgyLoginIssue() }) {
                            Text(L.common.cancel)
                        }
                    }
                } else null
            )
        }
        Scaffold(
            snackbarHost = {},
            topBar = {
                // The chat's title on the left, the agents' status dot with the project on the right. A thin line shows
                // under the bar only once messages scroll beneath it.
                val scrolledUnder by remember(listState) { derivedStateOf { listState.canScrollBackward } }
                val topBarLineAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (scrolledUnder && messages.isNotEmpty()) 1f else 0f,
                    animationSpec = tween(180),
                    label = "top_bar_line"
                )
                val topBarLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                TopAppBar(
                    // shorter than Material's 64dp: one line of title and the small project pill need less
                    expandedHeight = 52.dp,
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        if (topBarLineAlpha > 0f) {
                            drawLine(
                                color = topBarLineColor.copy(alpha = topBarLineColor.alpha * topBarLineAlpha),
                                start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                                end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f),
                                strokeWidth = 1f
                            )
                        }
                    },
                    title = {
                        val displayedChatTitle = remember(currentConv?.title, conversations, currentConv?.id) {
                            val fromList = conversations.firstOrNull { it.id == currentConv?.id }
                            val listTitle = fromList?.title?.takeIf {
                                it.isNotBlank() && it != "New Chat" && it != "Conversation"
                            }
                            val activeTitle = currentConv?.title?.takeIf {
                                it.isNotBlank() && it != "New Chat" && it != "Conversation"
                            }
                            listTitle ?: activeTitle ?: currentConv?.title?.takeIf { it.isNotBlank() } ?: L.chat.screen.antigravityChat
                        }

                        val isChatStarted = messages.isNotEmpty()
                        val displayedProjectName = remember(currentConv?.workspaceUri, isChatStarted, currentConv?.cloudProjectId, cloudProjects) {
                            val convUri = currentConv?.workspaceUri
                            if (currentConv?.isCloud == true) {
                                // an Antigravity cloud chat: its cloud project
                                "☁ " + (ai.antigem.data.agent.agy.AgyCloud.projectName(currentConv?.cloudProjectId) ?: L.chat.screen.cloud)
                            } else if (!convUri.isNullOrBlank()) {
                                val clean = convUri.removePrefix("file://").trimEnd('/')
                                File(clean).name.ifBlank { L.common.workspace }
                            } else {
                                if (isChatStarted) L.chat.screen.outside else L.chat.screen.selectProject
                            }
                        }
                        val showStatusDot = isLocalToolsInstalled || AuthPreferences.currentRemoteMode ||
                            ai.antigem.data.local.LocalEnvironmentManager.isInstalled(context) ||
                            systemConnectionState.status != ai.antigem.data.remote.SystemStatus.OFFLINE
                        var statusMenuOpen by remember { mutableStateOf(false) }

                        // title on the left; "◐ project" and the new-chat button on the right. Title and project shorten
                        // with "…" and keep a gap between them and from the screen edge, so nothing runs into anything.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // long-press the title: this chat on the home screen (an icon, or a widget)
                            var titleMenuOpen by remember { mutableStateOf(false) }
                            // same rule as the long-press below: TalkBack offers it as its long-press action
                            val titleConv = currentConv
                            val canPinTitle = titleConv != null && isChatStarted && !titleConv.readOnly && !isInFloatingWindow
                            Box(Modifier.weight(1f)) {
                            // the real title fades in when the chat gets one (instead of jumping from "New Chat")
                            Crossfade(
                                targetState = displayedChatTitle,
                                animationSpec = tween(250),
                                label = "chat_title",
                                modifier = Modifier.pointerInput(currentConv?.id, isChatStarted) {
                                    detectTapGestures(onLongPress = {
                                        val conv = currentConv
                                        if (conv != null && isChatStarted && !conv.readOnly && !isInFloatingWindow) titleMenuOpen = true
                                    })
                                }.semantics(mergeDescendants = true) {
                                    heading()
                                    if (canPinTitle) onLongClick(label = L.common.addToHomeScreen) { titleMenuOpen = true; true }
                                }
                            ) { chatTitle ->
                                Text(
                                    text = chatTitle,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(expanded = titleMenuOpen, onDismissRequest = { titleMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(L.common.addToHomeScreen) },
                                    leadingIcon = { Icon(Icons.Outlined.AppShortcut, null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        titleMenuOpen = false
                                        currentConv?.let { ai.antigem.data.homescreen.HomeShortcuts.pinChat(context, it.id, it.agent, displayedChatTitle) }
                                    }
                                )
                            }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Box {
                                // before the chat starts a tap picks the project; after, it shows the agents' status
                                Row(
                                    modifier = Modifier
                                        .widthIn(max = 150.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                        // nothing to open (chat started, no status dot): plain text for TalkBack, not a "disabled" button
                                        .then(
                                            if (!isChatStarted || showStatusDot) Modifier.clickable(
                                                role = Role.Button,
                                                onClickLabel = if (!isChatStarted) L.chat.screen.chooseProject else L.components.a11y.agentsStatus
                                            ) {
                                                if (!isChatStarted) showProjectPickerDialog = true
                                                else statusMenuOpen = true
                                            } else Modifier
                                        )
                                        .padding(start = if (showStatusDot) 4.dp else 10.dp, end = if (isChatStarted) 10.dp else 4.dp, top = 3.dp, bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (showStatusDot) {
                                        // upper half Antigravity, lower half Claude Code (one solid dot when they agree);
                                        // the dot itself always opens the agents' status, also before the chat starts
                                        // one slice per enabled agent (Antigravity, Claude Code, OpenCode)
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .clickable(role = Role.Button) { statusMenuOpen = true }
                                                .semantics {
                                                    contentDescription = L.components.a11y.agentStatusValue(
                                                        agentEntries.joinToString { "${it.agent.label()}: ${it.label}" }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ai.antigem.ui.agents.AgentStatusDot(
                                                colors = agentEntries.map { it.color },
                                                size = if (agentEntries.size > 2) 10.dp else 8.dp,
                                                pulsing = agentEntries.any { it.pending }
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }
                                    if (isAnyGenerationOrTaskActive) {
                                        // the screen is kept on (work is running): a sun, tap it to see why
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .clickable(role = Role.Button) { screenAwakeInfoOpen = true },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.LightMode,
                                                contentDescription = L.chat.screen.screenStaysOn,
                                                tint = ai.antigem.theme.AppColors.warning,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            DropdownMenu(
                                                expanded = screenAwakeInfoOpen,
                                                onDismissRequest = { screenAwakeInfoOpen = false }
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .widthIn(min = 220.dp, max = 300.dp)
                                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.LightMode,
                                                            contentDescription = null,
                                                            tint = ai.antigem.theme.AppColors.warning,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = L.chat.screen.screenStaysOn,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = L.chat.screen.theScreenWontTurnOffBy,
                                                        fontSize = 12.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                    )
                                                    screenAwakeReasons.forEach { reason ->
                                                        Text(
                                                            text = "• $reason",
                                                            fontSize = 12.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            modifier = Modifier.padding(top = 4.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = L.chat.screen.itTurnsOffNormallyAgainOnce,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(2.dp))
                                    }
                                    Text(
                                        text = displayedProjectName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isChatStarted) 0.65f else 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!isChatStarted) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                ai.antigem.ui.agents.AgentStatusMenu(
                                    entries = agentEntries,
                                    expanded = statusMenuOpen,
                                    onDismiss = { statusMenuOpen = false },
                                    onOpenServerLogs = { showLocalServerOutputDialog = true },
                                    onRestartServer = { ai.antigem.data.local.LocalServerManager.restartServer(context) }
                                )
                            }
                            // shortcut icons chosen in Settings > Appearance > Chat Header Icons (default: just New chat)
                            val headerShortcuts = chatHeaderItems.filter { id ->
                                when (id) {
                                    "newchat", "ide", "terminal", "browser" -> true
                                    "notes" -> onNavigateToNotes != null
                                    "files" -> onNavigateToFiles != null
                                    // the Run panel lives in the main window, not in the floating chat
                                    "run" -> !isInFloatingWindow
                                    else -> false
                                }
                            }
                            val runsGoing by ai.antigem.data.run.RunManager.runs.collectAsState()
                            val anyRunGoing = runsGoing.any { it.status == ai.antigem.data.run.RunStatus.RUNNING }
                            if (headerShortcuts.isNotEmpty()) Spacer(modifier = Modifier.width(4.dp))
                            headerShortcuts.forEach { id ->
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) {
                                            when (id) {
                                                "newchat" -> {
                                                    // same as the sidebar's New chat
                                                    val currentId = currentConv?.id
                                                    if (!currentId.isNullOrBlank() && feedItems.isNotEmpty()) {
                                                        ConversationScrollCache.save(currentId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isAtBottom)
                                                    }
                                                    conversationBackStack.clear()
                                                    viewModel.startNewChat()
                                                }
                                                "ide" -> onNavigateToIde()
                                                "browser" -> onNavigateToBrowser()
                                                "notes" -> onNavigateToNotes?.invoke()
                                                "files" -> onNavigateToFiles?.invoke()
                                                "run" -> ai.antigem.data.run.RunPanel.show()
                                                "terminal" -> {
                                                    // like the floating switcher: needs SSH, server mode or the local Linux tools
                                                    val ready = useSshTerminal || AuthPreferences.currentRemoteMode ||
                                                        (isLocalToolsInstalled && isLocalToolsEnabled)
                                                    when {
                                                        ready -> onNavigateToTerminal()
                                                        isLocalToolsInstalled -> {
                                                            viewModel.setLocalToolsEnabled(true)
                                                            onNavigateToTerminal()
                                                        }
                                                        else -> showLocalToolsInstallDialog = true
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // a command running: a ring around the Run icon
                                    if (id == "run" && anyRunGoing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(30.dp).clearAndSetSemantics { },
                                            strokeWidth = 2.dp,
                                            color = ClaudeTerracotta
                                        )
                                    }
                                    Icon(
                                        imageVector = when (id) {
                                            "run" -> Icons.Outlined.PlayCircle
                                            "ide" -> Icons.Outlined.Code
                                            "terminal" -> Icons.Outlined.Terminal
                                            "browser" -> Icons.Outlined.Language
                                            "notes" -> Icons.Outlined.EditNote
                                            "files" -> Icons.Outlined.FolderOpen
                                            else -> Icons.Outlined.AddComment
                                        },
                                        contentDescription = when (id) {
                                            "ide" -> L.chat.screen.openIde
                                            "terminal" -> L.chat.screen.openTerminal
                                            "browser" -> L.chat.screen.openBrowser
                                            "notes" -> L.chat.screen.openNotes
                                            "files" -> L.chat.screen.openFiles
                                            "run" -> if (anyRunGoing) L.chat.screen.runACommandIsRunning else L.chat.screen.openRunPanel
                                            else -> L.chat.screen.newChat
                                        },
                                        tint = if (id == "run" && anyRunGoing) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (conversationBackStack.isNotEmpty()) {
                            IconButton(onClick = navigateBackConversation) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = L.common.back,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = L.chat.screen.openDrawer,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    actions = {
                        // Browser, Terminal and the IDE are in the floating switcher (always on)
                        if (isInFloatingWindow) {
                            IconButton(onClick = onMinimizeWindow) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = L.chat.screen.minimizeToBubble,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                            IconButton(onClick = onOpenFullScreen) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = L.chat.screen.openFullScreen,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }
                        if (isDevModeEnabled) {
                            IconButton(onClick = { showChatTelemetryDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Analytics,
                                    contentDescription = L.chat.screen.chatTelemetryTokens,
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
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            // This box ends where the input area starts; the jump button sits 12dp + 38dp above that.
                            // Y only: the chat is hidden by moving it sideways, which doesn't change it.
                            val bottomY = coords.localToRoot(Offset(0f, coords.size.height.toFloat())).y
                            onBottomAreaTopChanged(bottomY - with(density) { (12 + 38).dp.toPx() })
                        }
                ) {
                    val sysStatus = systemConnectionState.status
                    val isServerInitializing = sysStatus == ai.antigem.data.remote.SystemStatus.STARTING || sysStatus == ai.antigem.data.remote.SystemStatus.ACQUIRING_CSRF || (sysStatus == ai.antigem.data.remote.SystemStatus.OFFLINE && isInitialGracePeriod)
                    val isServerStopped = sysStatus == ai.antigem.data.remote.SystemStatus.OFFLINE && !isInitialGracePeriod
                    // Jarvis' chats are not in `conversations` (they are hidden and listed apart, under Jarvis), but they are
                    // existing chats too: while one loads, show the loading skeleton like for any other chat
                    val isExistingConversation = currentConv != null && currentConv?.title != "New Chat" &&
                        (conversations.any { it.id == currentConv?.id } || jarvisConversations.any { it.id == currentConv?.id })
                    // agy missing: the hub's "agy is not installed" error is also this chat's error, and an existing chat
                    // never finishes loading. Both step aside for the empty view's "not installed" card with Install.
                    val agyMissingHere = isAgyChat && agyNotInstalled
                    val isExistingChat = (messages.isEmpty() && isExistingConversation && isLoadingConversation && !agyMissingHere) ||
                        // the messages are here but their rows are still being built (a big chat just opened)
                        feedNotReady
                    Log.d("CHAT_OPEN_DEBUG", "🖥️ [ChatScreen Render] convId=${currentConv?.id}, title='${currentConv?.title}', isLoading=$isLoadingConversation, isExisting=$isExistingChat, msgCount=${messages.size}, error=$conversationError, isServerStopped=$isServerStopped")

                    if (isAgyChat && isServerStopped && messages.isEmpty()) {
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
                                if (isFreshNewChat) {
                                    ai.antigem.ui.components.AgentPicker(
                                        selected = currentConv?.agent ?: newChatAgent,
                                        onSelect = pickAgent,
                                        modifier = Modifier.padding(bottom = 20.dp),
                                        agents = enabledAgents,
                                        statusColors = agentEntries.associate { it.agent to it.color }
                                    )
                                }
                                ai.antigem.ui.components.ServerStoppedPromptCard()
                            }
                        }
                    } else if (isAgyChat && isServerInitializing && messages.isEmpty()) {
                        if (isFreshNewChat && enabledAgents.size > 1) {
                            // a new chat keeps its agent picker while Antigravity starts (Claude can be picked and used
                            // meanwhile); before, the whole view was replaced and the picker vanished until the hub was up
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
                                    ai.antigem.ui.components.AgentPicker(
                                        selected = currentConv?.agent ?: newChatAgent,
                                        onSelect = pickAgent,
                                        modifier = Modifier.padding(bottom = 20.dp),
                                        agents = enabledAgents,
                                        statusColors = agentEntries.associate { it.agent to it.color }
                                    )
                                    ai.antigem.ui.components.EngineWarmingUpView()
                                }
                            }
                        } else {
                            ai.antigem.ui.components.EngineWarmingUpView()
                        }
                    } else if (!conversationError.isNullOrBlank() && messages.isEmpty() && (isExistingConversation || currentConv != null) && !agyMissingHere) {
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
                                text = L.chat.screen.unableToLoadChat,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    text = conversationError ?: L.chat.screen.failedToLoadConversationMessages,
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
                                Text(L.common.retry, fontSize = 13.5.sp)
                            }
                        }
                    } else if (isExistingChat) {
                        ai.antigem.ui.components.ConversationLoadingSkeleton()
                    } else if (messages.isEmpty() && voiceCallItems.isEmpty()) {
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
                                if (isFreshNewChat) {
                                    ai.antigem.ui.components.AgentPicker(
                                        selected = currentConv?.agent ?: newChatAgent,
                                        onSelect = pickAgent,
                                        modifier = Modifier.padding(bottom = 20.dp),
                                        agents = enabledAgents,
                                        statusColors = agentEntries.associate { it.agent to it.color }
                                    )
                                }

                                if (!isNetworkConnected) {
                                    ai.antigem.ui.components.NewChatOfflinePromptCard(
                                        modifier = Modifier.padding(bottom = 20.dp)
                                    )
                                }

                                if (isClaudeChat && claudeChatState != ai.antigem.data.agent.claude.ClaudeStatus.READY) {
                                    ai.antigem.ui.claude.ClaudeStatusPrompt(
                                        state = claudeChatState,
                                        error = claudeStatusError,
                                        onSignIn = { showClaudeLogin = true },
                                        onSetup = openClaudeSetup,
                                        onRestartServer = { ai.antigem.data.local.LocalServerManager.restartServer(context) },
                                        onRetry = { viewModel.claudeAccount.refreshStatus() },
                                        onServerLogs = { showLocalServerOutputDialog = true },
                                        serverStarting = serverRestarting
                                    )
                                } else if (isOpenCodeChat && openCodeState != ai.antigem.data.agent.opencode.OpenCodeState.READY) {
                                    ai.antigem.ui.agents.AgentSetupPrompt(
                                        agent = ai.antigem.domain.model.AgentKind.OPENCODE,
                                        title = when (openCodeState) {
                                            ai.antigem.data.agent.opencode.OpenCodeState.CHECKING -> L.chat.screen.startingOpencode
                                            ai.antigem.data.agent.opencode.OpenCodeState.NOT_INSTALLED -> L.chat.screen.opencodeIsNotInstalled
                                            else -> L.chat.screen.opencodeIsUnavailable
                                        },
                                        message = if (openCodeState == ai.antigem.data.agent.opencode.OpenCodeState.CHECKING)
                                            L.chat.screen.waitingForTheServerToCome
                                        else openCodeEntry?.detail ?: openCodeState.label,
                                        statusColor = openCodeEntry?.color,
                                        actionLabel = openCodeEntry?.action?.label,
                                        onAction = { openCodeEntry?.action?.onClick?.invoke() },
                                        secondaryLabel = if (openCodeState == ai.antigem.data.agent.opencode.OpenCodeState.CHECKING) null else L.common.serverLogs,
                                        onSecondary = { showLocalServerOutputDialog = true }
                                    )
                                } else if (isCodexChat && codexState != ai.antigem.data.agent.codex.CodexState.READY) {
                                    ai.antigem.ui.agents.AgentSetupPrompt(
                                        agent = ai.antigem.domain.model.AgentKind.CODEX,
                                        title = when (codexState) {
                                            ai.antigem.data.agent.codex.CodexState.CHECKING -> L.chat.screen.startingCodex
                                            ai.antigem.data.agent.codex.CodexState.NOT_INSTALLED -> L.chat.screen.codexIsNotInstalled
                                            ai.antigem.data.agent.codex.CodexState.SIGNED_OUT -> L.chat.screen.signInToCodex
                                            else -> L.chat.screen.codexIsUnavailable
                                        },
                                        message = if (codexState == ai.antigem.data.agent.codex.CodexState.CHECKING)
                                            L.chat.screen.waitingForTheServerToCome
                                        else codexEntry?.detail ?: codexState.label,
                                        statusColor = codexEntry?.color,
                                        actionLabel = codexEntry?.action?.label,
                                        onAction = { codexEntry?.action?.onClick?.invoke() },
                                        secondaryLabel = if (codexState == ai.antigem.data.agent.codex.CodexState.CHECKING) null else L.common.serverLogs,
                                        onSecondary = { showLocalServerOutputDialog = true }
                                    )
                                } else if (isAgyChat && agyNotInstalled) {
                                    ai.antigem.ui.agents.AgentSetupPrompt(
                                        agent = ai.antigem.domain.model.AgentKind.AGY,
                                        title = L.chat.screen.antigravityIsNotInstalled,
                                        message = agyEntry?.detail ?: L.chat.screen.antigravityAgyIsNotInstalledYet,
                                        statusColor = agyEntry?.color,
                                        actionLabel = agyEntry?.action?.label,
                                        onAction = { agyEntry?.action?.onClick?.invoke() },
                                        secondaryLabel = L.common.serverLogs,
                                        onSecondary = { showLocalServerOutputDialog = true }
                                    )
                                } else when (if (isClaudeChat || isOpenCodeChat || isCodexChat) null else systemConnectionState.status) {
                                    ai.antigem.data.remote.SystemStatus.CHECKING_AUTH -> {
                                        ai.antigem.ui.components.NewChatCheckingAuthPromptCard()
                                    }
                                    ai.antigem.data.remote.SystemStatus.UNAUTHENTICATED -> {
                                        if (isNetworkConnected) {
                                            ai.antigem.ui.components.NewChatSignInPromptCard(
                                                onSignInClick = { viewModel.loginToAgyHub(force = true) }
                                            )
                                        }
                                    }
                                    else -> if (isJarvisTextChat) {
                                        ai.antigem.ui.jarvis.JarvisChatWelcome { example ->
                                            inputState.setTextAndPlaceCursorAtEnd(example)
                                        }
                                    } else {
                                        Text(
                                            text = L.chat.screen.howCanIHelpYouToday,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.semantics { heading() }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = L.chat.screen.askAQuestionBrainstormIdeasOr,
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

                        // web views of this chat's blocks stay alive while the chat is open; all destroyed when it is left
                        val chatWebViewCache = remember(currentConv?.id) { ai.antigem.ui.components.ChatWebViewCache(context) }
                        DisposableEffect(chatWebViewCache) { onDispose { chatWebViewCache.close() } }

                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalDensity provides customDensity,
                            ai.antigem.ui.components.LocalQuestionPanelActive provides true,
                            ai.antigem.ui.components.LocalChatWebViewCache provides chatWebViewCache
                        ) {
                                // iOS-style vertical bounce at the top and bottom (also for a short chat that cannot scroll);
                                // only this list: code blocks and other sideways scrollers keep Android's own edge effect
                                val chatBounce = ai.antigem.ui.components.rememberBouncyLazyScroll(listState)
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().then(chatBounce.modifier),
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                                    userScrollEnabled = chatBounce.userScrollEnabled,
                                    overscrollEffect = chatBounce.overscrollEffect
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
                                        is ChatFeedItem.Divider -> ai.antigem.ui.components.WaveDivider(label = feedItem.label)
                                        is ChatFeedItem.VoiceCall -> ai.antigem.ui.voice.VoiceCallMarker(feedItem) {
                                            if (feedItem.live) ai.antigem.data.voice.AgyVoiceCall.setMinimized(false)
                                            else openVoiceTranscript = feedItem.record
                                        }
                                        is ChatFeedItem.JarvisEvent -> ai.antigem.ui.jarvis.JarvisEventCard(
                                            raw = feedItem.message.content,
                                            onOpenChat = openJarvisLinkedChat
                                        )
                                        is ChatFeedItem.User -> {
                                            UserMessageBubble(
                                                message = feedItem.message,
                                                isLastUserMessage = true,
                                                isDevModeEnabled = isDevModeEnabled,
                                                canEdit = !isReadOnlyChat,
                                                onEdit = { targetMsg ->
                                                    // asks first when replies follow this prompt (editing deletes them):
                                                    // judged on the messages on screen when tapped, so the row doesn't
                                                    // depend on the message list (it changes on every streamed word)
                                                    val rows = latestRowMessages
                                                    val willDeleteOutput = rows.indexOfFirst { it.id == feedItem.message.id } < rows.lastIndex
                                                    if (willDeleteOutput) {
                                                        pendingMessageAction = PendingMessageAction(MessageActionType.EDIT, targetMsg)
                                                    } else {
                                                        viewModel.revertAndEditLastUserMessage(targetMsg) { restoredText ->
                                                            inputState.setTextAndPlaceCursorAtEnd(restoredText)
                                                        }
                                                    }
                                                },
                                                onViewRawPayload = { payloadJson ->
                                                    showRawPayloadDialog = payloadJson
                                                },
                                                checkpoint = messageCheckpoints[feedItem.message.id],
                                                onRestoreCheckpoint = { msg ->
                                                    messageCheckpoints[msg.id]?.let { restoreSheetFor = msg to it }
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
                                                val blockView: @Composable () -> Unit = {
                                                    MarkdownBlockView(
                                                        block = feedItem.block,
                                                        onApproveTool = if (isTool) { { toolCall -> viewModel.approveAndExecuteTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                        onRejectTool = if (isTool) { { toolCall -> if (toolCall.toolType == ToolType.ASK_CHOICE) viewModel.cancelUserChoices(toolCall, feedItem.messageId) else viewModel.rejectTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                        onTerminateTool = if (isTool) { { toolCall -> viewModel.terminateRunningTerminalTool(toolCall, feedItem.messageId) } } else null,
                                                        onSubmitChoices = if (isTool) { { toolCall, responses, summaryPayload -> viewModel.submitUserChoices(toolCall, feedItem.messageId, responses, summaryPayload) } } else null,
                                                        onSkipChoices = if (isTool) { { toolCall, responses -> viewModel.skipUserChoices(toolCall, feedItem.messageId, responses) } } else null
                                                    )
                                                }
                                                // Text is selectable per block: each list item has its own selection container, so a
                                                // selection never outlives the item when it scrolls away (one container spanning many
                                                // items crashed). Interactive blocks (tool cards, web views, media) keep their own gestures.
                                                val selectable = when (feedItem.block) {
                                                    is MarkdownBlock.Paragraph, is MarkdownBlock.Header, is MarkdownBlock.Bullet,
                                                    is MarkdownBlock.Numbered, is MarkdownBlock.Task, is MarkdownBlock.Blockquote,
                                                    is MarkdownBlock.Alert, is MarkdownBlock.Table -> true
                                                    else -> false
                                                }
                                                CompositionLocalProvider(ai.antigem.ui.components.LocalWebViewSlot provides feedItem.key) {
                                                    if (selectable) SelectionContainer { blockView() } else blockView()
                                                }
                                            }
                                        }
                                        // with the working animation on, the working row below replaces these dots
                                        // progress shows in the working row under the reply (the working animation is always on)
                                        is ChatFeedItem.AssistantTyping -> Unit
                                        is ChatFeedItem.StepGroup -> StepGroupPill(
                                            group = feedItem,
                                            onToggle = { if (openStepGroups.remove(feedItem.key) == null) openStepGroups[feedItem.key] = true },
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                        is ChatFeedItem.AssistantFooter -> {
                                            AssistantMessageFooter(
                                                message = feedItem.message,
                                                showStats = showMessageStats,
                                                // fades in where the working row fades out (no pop-in at the end of a turn)
                                                modifier = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = null, fadeOutSpec = null),
                                                isDevModeEnabled = isDevModeEnabled,
                                                canRetry = !isReadOnlyChat,
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
                                                showStats = showMessageStats,
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

                                    if (showWorkingRow) {
                                        item(key = "working_spinner", contentType = "working_spinner") {
                                            ai.antigem.ui.components.AgentWorkingSpinner(
                                                color = if (isClaudeChat) ai.antigem.ui.components.ClaudeAccent else if (isOpenCodeChat) ai.antigem.ui.components.OpenCodeAccent else if (isCodexChat) ai.antigem.ui.components.CodexAccent else ai.antigem.ui.components.AgyAccent,
                                                compacting = isClaudeChat && claudeCompacting,
                                                // "Clauding" only in Claude chats
                                                claude = isClaudeChat,
                                                // leaves in the frame the reply's footer arrives, which fades in in its place
                                                modifier = Modifier.animateItem(fadeInSpec = tween(180), placementSpec = null, fadeOutSpec = null)
                                            )
                                        }
                                    }

                                    // Bottom spacer to ensure scrolling reaches comfortably above the input box
                                    item(key = "bottom_anchor") {
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                        }
                    }

                    // Floating Scroll Up / Scroll Down Button (Instant Movement). It reads the scroll flags itself, so only
                    // the button redraws when they change, not this whole area (see ChatJumpButton)
                    ChatJumpButton(
                        showScrollButton = { showScrollButton },
                        scrollDirection = { scrollDirection },
                        isAtTop = { isAtTop },
                        isAtBottom = { isAtBottom },
                        rowCount = feedItems.size,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp),
                        onJump = { toTop ->
                            showScrollButton = false
                            scope.launch {
                                if (toTop) {
                                    listState.scrollToItem(0)
                                } else {
                                    listState.scrollToItem(feedItems.size)
                                }
                            }
                        }
                    )

                    // Floating Slash Commands & Skills Autocomplete Suggestions
                    val slash = slashTrigger
                    val slashIndex = slash?.index ?: -1
                    val isSlashActive = slash != null
                    val slashQuery = slash?.query ?: ""

                    // opening the "/" menu reads the agent's skills again (at most every 10 s), so ones added meanwhile show
                    var lastSlashRefresh by remember { mutableStateOf(0L) }
                    LaunchedEffect(isSlashActive) {
                        if (!isSlashActive) return@LaunchedEffect
                        val now = System.currentTimeMillis()
                        if (now - lastSlashRefresh < 10_000) return@LaunchedEffect
                        lastSlashRefresh = now
                        when {
                            isClaudeChat -> claudeBackend.refreshInfoForChat()
                            isCodexChat -> codexBackend.refreshSkills()
                            isOpenCodeChat -> openCodeBackend.refreshSlashCommands()
                            systemConnectionState.isHubOnline -> ai.antigem.data.remote.SlashCommandsCache.refresh(agyHubUrl)
                        }
                    }

                    // `/plu:` browses custom AntiGem plugins and their skills; custom commands join the normal list
                    val customCommands = ai.antigem.data.remote.CustomSlashCommands.commands.toList()
                    val installedPlugins = ai.antigem.ui.browser.BrowserExtensionManager.instance.extensions.toList()
                    val slashSuggestions = remember(slashQuery, effectiveSlashCommands, isSlashActive, customCommands, installedPlugins) {
                        if (!isSlashActive) emptyList()
                        else if (slashQuery.startsWith("plu:", ignoreCase = true)) ai.antigem.data.remote.PromptExpansion.pluginSuggestions(slashQuery.substring(4))
                        else {
                            (ai.antigem.data.remote.PromptExpansion.baseSlashItems() + effectiveSlashCommands).filter { item ->
                                slashQuery.isBlank() ||
                                item.name.contains(slashQuery, ignoreCase = true) ||
                                item.command.removePrefix("/").contains(slashQuery, ignoreCase = true) ||
                                item.description.contains(slashQuery, ignoreCase = true)
                            }
                        }
                    }

                    // a Claude command that needs no argument runs on tap (when nothing else is typed and no turn runs)
                    val slashRunsOnTap: (ai.antigem.data.remote.SlashCommandItem) -> Boolean = { item ->
                        (isClaudeChat || (isCodexChat && item.type == "command")) && item.type != "app" && item.type != "plugin" && item.type != "custom" && item.argumentHint.isBlank() &&
                            (slash == null || slash.nothingBefore) && !isActivelyRunning && attachments.isEmpty()
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = slashSuggestions.isNotEmpty(),
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        SlashCommandOverlay(
                            items = slashSuggestions,
                            query = slashQuery,
                            runsOnTap = slashRunsOnTap,
                            onPick = { item ->
                                val before = inputState.text.substring(0, slashIndex)
                                val appCmd = if (isClaudeChat && item.type == "app") claudeBackend.appCommand(item.command) else null
                                val codexCmd = if (isCodexChat && item.type == "app") codexBackend.appCommand(item.command) else null
                                when {
                                    codexCmd != null -> {
                                        inputState.setTextAndPlaceCursorAtEnd(before)
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        handleCodexCommand(codexCmd)
                                    }
                                    appCmd != null -> {
                                        inputState.setTextAndPlaceCursorAtEnd(before)
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        handleClaudeCommand(appCmd)
                                    }
                                    slashRunsOnTap(item) -> {
                                        // e.g. /compact: Claude Code runs a command sent as a message
                                        viewModel.sendMessage(item.command)
                                        userSentMessageTrigger++
                                        viewModel.clearDraft(activeConversationKey)
                                        inputState.clearText()
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                    }
                                    else -> {
                                        // "/plu:" and "/plu:<plugin>:" keep the picker open for the next level
                                        val newText = if (item.command.endsWith(":")) "$before${item.command}" else "$before${item.command} "
                                        inputState.setTextAndPlaceCursorAtEnd(newText)
                                    }
                                }
                            }
                        )
                    }

                    // Floating File Autocomplete Suggestions when user types @
                    val mention = mentionTrigger
                    val atIndex = mention?.index ?: -1
                    val isAtMentioning = mention != null
                    val atQuery = mention?.query ?: ""

                    // Nothing is loaded before an @mention: the bridge searches the project's files and folders by name
                    // (by path when the text has a "/") once something is typed. A new keystroke (or removing the @,
                    // sending, leaving) cancels the running search.
                    val mentionProjectPath = activeChatProject?.path
                    var fileSuggestions by remember { mutableStateOf<List<ai.antigem.data.files.ExplorerEntry>>(emptyList()) }
                    LaunchedEffect(isAtMentioning, atQuery, mentionProjectPath) {
                        if (!isAtMentioning || atQuery.isBlank() || mentionProjectPath.isNullOrBlank()) {
                            fileSuggestions = emptyList()
                            return@LaunchedEffect
                        }
                        delay(250) // wait for a pause in typing
                        fileSuggestions = ai.antigem.data.files.FileExplorerApi.searchFileNames(mentionProjectPath, atQuery, filesOnly = false)
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
                            modifier = Modifier.fillMaxWidth().semantics { paneTitle = L.common.files },
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
                                            .clickable(role = Role.Button) {
                                                val newText = inputState.text.substring(0, atIndex) + "@${file.path} "
                                                inputState.setTextAndPlaceCursorAtEnd(newText)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (file.isDir) Icons.Default.Folder else Icons.Default.Description,
                                            contentDescription = if (file.isDir) L.files.fileKinds.folder else null,
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
                    visible = isAgyChat && !bridgeStatusMessage.isNullOrBlank(),
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
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }

                // Disconnection / Reconnecting Inline Banner (Compact, non-intrusive with Reconnect action)
                AnimatedVisibility(
                    visible = isAgyChat && (isServerOnline == false || isReconnecting) && messages.isNotEmpty(),
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
                                    text = if (isReconnecting) L.chat.screen.connectingToAntigravity else L.chat.screen.antigravityDisconnected,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isReconnecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
                                        text = L.common.reconnect,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                // Agent prompts docked above the input (both agents): permission requests, then questions
                val approvalItems = remember(pendingApprovals, claudePending, openCodePending, codexPending, isClaudeChat, isOpenCodeChat, isCodexChat) {
                    if (isCodexChat) {
                        ai.antigem.ui.codex.codexApprovalItems(codexPending, codexBackend, messages)
                    } else if (isOpenCodeChat) {
                        ai.antigem.ui.opencode.openCodeApprovalItems(openCodePending, openCodeBackend)
                    } else if (isClaudeChat) {
                        ai.antigem.ui.claude.claudeApprovalItems(claudePending, claudeBackend)
                    } else {
                        ai.antigem.ui.agents.agyApprovalItems(
                            pendingApprovals,
                            onApprove = { toolCall, msgId, scope -> viewModel.approveAndExecuteTerminalTool(toolCall, msgId, scope) },
                            onReject = { toolCall, msgId, reason -> viewModel.rejectTerminalTool(toolCall, msgId, reason) }
                        )
                    }
                }
                // keep the last requests on screen while the panel animates away
                val lastApprovalItems = remember { arrayOf(emptyList<ai.antigem.ui.agents.ApprovalItem>()) }
                SideEffect { if (approvalItems.isNotEmpty()) lastApprovalItems[0] = approvalItems }
                AnimatedVisibility(
                    visible = approvalItems.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ai.antigem.ui.agents.AgentApprovalPanel(
                        items = approvalItems.ifEmpty { lastApprovalItems[0] },
                        onAllowAll = if (isCodexChat) {
                            { codexPending.forEach { codexBackend.approve(it.key) } }
                        } else if (isOpenCodeChat) {
                            { openCodePending.forEach { openCodeBackend.replyPermission(it.id, "once") } }
                        } else if (isClaudeChat) {
                            { claudePending.filter { it.toolName != "ExitPlanMode" }.forEach { claudeBackend.approveToolAlways(it.toolUseId, -1) } }
                        } else {
                            { viewModel.approveAllPendingTools(pendingApprovals) }
                        },
                        onDenyAll = if (isCodexChat) {
                            { codexPending.forEach { codexBackend.deny(it.key) } }
                        } else if (isOpenCodeChat) {
                            { openCodePending.forEach { openCodeBackend.replyPermission(it.id, "reject") } }
                        } else if (isClaudeChat) {
                            { claudePending.forEach { claudeBackend.rejectToolById(it.toolUseId, null) } }
                        } else {
                            { viewModel.rejectAllPendingTools(pendingApprovals) }
                        }
                    )
                }

                val pendingQuestion = remember(messages, isClaudeChat, isOpenCodeChat, isCodexChat) {
                    ai.antigem.ui.agents.findPendingQuestion(
                        currentConv?.agent ?: ai.antigem.domain.model.AgentKind.AGY,
                        messages
                    )
                }
                if (talkBackOn) {
                    ChatTalkBackAnnouncer(
                        chatKey = currentConv?.id,
                        running = isActivelyRunning,
                        needsApproval = approvalItems.isNotEmpty(),
                        hasQuestion = pendingQuestion != null,
                        error = conversationError
                    )
                }
                AnimatedVisibility(
                    visible = pendingQuestion != null && approvalItems.isEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    pendingQuestion?.let { q ->
                        ai.antigem.ui.agents.AgentQuestionPanel(
                            question = q,
                            onSubmit = { responses, summary -> viewModel.submitUserChoices(q.toolCall, q.messageId, responses, summary) },
                            onSkip = { responses -> viewModel.skipUserChoices(q.toolCall, q.messageId, responses) },
                            onCancel = { viewModel.cancelUserChoices(q.toolCall, q.messageId) }
                        )
                    }
                }

                // Claude Code chat: status notice (the empty chat shows the full prompt instead) and chat controls
                if (isClaudeChat) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = claudeStartup != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ai.antigem.ui.claude.ClaudeStartupNotice(claudeStartup ?: "")
                    }
                    if (messages.isNotEmpty() && !serverRestarting) {
                        ai.antigem.ui.claude.ClaudeStatusBanner(
                            state = claudeChatState,
                            onSignIn = { showClaudeLogin = true },
                            onSetup = openClaudeSetup,
                            onRestartServer = { ai.antigem.data.local.LocalServerManager.restartServer(context) },
                            onRetry = { viewModel.claudeAccount.refreshStatus() }
                        )
                    }
                    if (!isReadOnlyChat) ai.antigem.ui.claude.ClaudeControlsStrip(
                        claudeBackend, viewModel.claudePrefs,
                        subagents = chatSubagents,
                        // like a conversation:// link: Back returns to this chat
                        onOpenSubagent = { id -> fileLinkHandler.onOpenConversation(id) }
                    )
                }
                if (isOpenCodeChat) {
                    ai.antigem.ui.opencode.OpenCodeControlsStrip(openCodeBackend)
                }
                if (isCodexChat) {
                    ai.antigem.ui.codex.CodexControlsStrip(codexBackend)
                }

                // Jarvis chat: its command strip (mission, teams' tasks, voice replies, autopilot)
                if (jarvisEnabled && currentConv != null && (isJarvisTextChat || jarvisConversations.any { it.id == currentConv?.id })) {
                    val jarvisToolsMissing = remember(messages) {
                        messages.lastOrNull { it.role == ai.antigem.domain.model.MessageRole.ASSISTANT }?.content
                            ?.contains(ai.antigem.data.jarvis.JarvisSkill.MCP_MISSING) == true
                    }
                    ai.antigem.ui.jarvis.JarvisCommandStrip(chatId = currentConv!!.id, onOpenChat = openJarvisLinkedChat, toolsMissing = jarvisToolsMissing)
                }

                // a minimized Antigravity voice call
                ai.antigem.ui.voice.AgyVoiceCallPill(
                    agentWorking = isActivelyRunning && currentConv?.id == voiceCall.conversationId
                )

                // an Antigravity cloud chat: its project, web preview, run a command, the chat on the web
                if (isAgyChat && currentConv?.isCloud == true) {
                    ai.antigem.ui.cloud.CloudChatStrip(
                        projectName = cloudProjects.firstOrNull { it.id == currentConv?.cloudProjectId }?.name,
                        onProject = { showCloudProjects = true },
                        onPreview = {
                            if (messages.isEmpty()) ai.antigem.ui.components.AppToastHelper.showToast(
                                L.chat.screen.sendTheCloudAgentAMessage, ai.antigem.ui.components.ChatToastType.INFO
                            ) else showCloudPreview = true
                        },
                        onRunCommand = { showCloudRunCommand = true }
                    )
                }

                // a subagent's chat can only be read: a note instead of the input
                if (isReadOnlyChat) {
                    ai.antigem.ui.claude.ReadOnlySubagentNote(currentConv?.subagentTypeName)
                }

                // Chat Input Bar with Bottom Model & Thinking Selector Pills (Claude Android Style)
                if (!isReadOnlyChat) ChatInputBar(
                    isOnline = if (isCodexChat) codexState != ai.antigem.data.agent.codex.CodexState.OFFLINE && codexState != ai.antigem.data.agent.codex.CodexState.NOT_INSTALLED && codexState != ai.antigem.data.agent.codex.CodexState.DISABLED && codexState != ai.antigem.data.agent.codex.CodexState.OUTDATED
                        else if (isOpenCodeChat) !openCodeState.isBlocking else if (isClaudeChat) !claudeState.isBlocking else systemConnectionState.canSend,
                    isAuth = if (isCodexChat) codexState != ai.antigem.data.agent.codex.CodexState.SIGNED_OUT else if (isOpenCodeChat) true else if (isClaudeChat) claudeChatState != ai.antigem.data.agent.claude.ClaudeStatus.SIGNED_OUT && claudeChatState != ai.antigem.data.agent.claude.ClaudeStatus.SIGNING_IN else systemConnectionState.isAuth,
                    offlineMessage = if (isCodexChat) when (codexState) {
                        ai.antigem.data.agent.codex.CodexState.NOT_INSTALLED -> L.chat.screen.codexIsNotInstalledOnThe
                        ai.antigem.data.agent.codex.CodexState.DISABLED, ai.antigem.data.agent.codex.CodexState.OUTDATED -> L.chat.screen.restartTheServerToUseCodex
                        else -> L.chat.screen.theServerIsOfflineStartIt
                    } else if (isOpenCodeChat) when (openCodeState) {
                        ai.antigem.data.agent.opencode.OpenCodeState.NOT_INSTALLED -> L.chat.screen.opencodeIsNotInstalledOnThe
                        ai.antigem.data.agent.opencode.OpenCodeState.DISABLED, ai.antigem.data.agent.opencode.OpenCodeState.OUTDATED -> L.chat.screen.restartTheServerToUseOpencode
                        else -> L.chat.screen.theServerIsOfflineStartIt
                    } else if (isClaudeChat) when (claudeState) {
                        ai.antigem.data.agent.claude.ClaudeStatus.NOT_INSTALLED -> L.chat.screen.claudeCodeIsNotInstalledSet
                        ai.antigem.data.agent.claude.ClaudeStatus.BRIDGE_OUTDATED -> L.chat.screen.restartTheLocalServerToUse
                        else -> L.chat.screen.theLocalServerIsOfflineStart
                    } else if (agyNotInstalled) L.chat.screen.antigravityAgyIsNotInstalledInstall
                    else L.chat.screen.serverIsOfflineStartTheServer,
                    selectedModel = currentModel,
                    quota = currentQuota,
                    thinkingPreference = thinkingPref,
                    textFieldState = inputState,
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
                    isStreaming = isActivelyRunning,
                    isBackgroundActive = isBackgroundActive,
                    onSendMessage = { text ->
                        val appCmd = if (isClaudeChat && attachments.isEmpty()) claudeBackend.appCommand(text) else null
                        val codexCmd = if (isCodexChat && attachments.isEmpty()) codexBackend.appCommand(text) else null
                        if (codexCmd != null) {
                            handleCodexCommand(codexCmd)
                            viewModel.clearDraft(activeConversationKey)
                            inputState.clearText()
                            return@ChatInputBar
                        }
                        val unreadable = if (isClaudeChat) attachments.filter { a ->
                            a.isAudio || a.mimeType?.startsWith("audio/") == true || a.mimeType?.startsWith("video/") == true
                        } else emptyList()
                        if (unreadable.isNotEmpty()) {
                            // Claude models cannot read audio or video: say so instead of sending a file it would reject
                            ai.antigem.ui.components.AppToastHelper.showToast(
                                L.chat.screen.claudeCantReadAudioOrVideo,
                                ai.antigem.ui.components.ChatToastType.ERROR
                            )
                            return@ChatInputBar
                        }
                        if (appCmd != null) {
                            handleClaudeCommand(appCmd)
                        } else {
                            viewModel.sendMessage(text)
                            userSentMessageTrigger++
                        }
                        viewModel.clearDraft(activeConversationKey)
                        inputState.clearText()
                    },
                    onStopStreaming = { showStopConfirmDialog = true },
                    attachments = attachments,
                    isUploadingAttachment = isUploadingAttachment,
                    attachmentProgress = attachmentProgress,
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    onAddAttachment = { viewModel.addAttachment(it) },
                    onAttachClick = { showAttachmentSelector = true },
                    onTranscribeAudioFile = { file, onDone, onError ->
                        viewModel.transcribeAudioFile(file, onDone, onError)
                    },
                    isTranscribingAudio = isTranscribingAudio,
                    speechManager = if (isClaudeChat) viewModel.claudeVoice else if (isCodexChat) viewModel.codexVoice else viewModel.speechManager,
                    // Claude and Codex cannot take audio: a voice note becomes text
                    voiceNotesAsText = isClaudeChat || isCodexChat,
                    voiceEnabled = !isOpenCodeChat,
                    cascadeId = activeConversationKey,
                    focusRequester = chatInputFocusRequester,
                    queuedMessages = queuedMessages,
                    onSendQueuedNow = { viewModel.sendQueuedNow(it) },
                    onDeleteQueued = { viewModel.deleteQueued(it) },
                    onStartVoiceCall = if (isAgyChat) onStartVoiceCall else null,
                    voiceCallActive = voiceCall.active
                )
            }
        }
    }

    // Antigravity cloud agents: projects and connections, preview, run a command
    if (showCloudProjects || showCloudCreate) {
        ai.antigem.ui.cloud.CloudProjectsDialog(
            startWithCreate = showCloudCreate,
            onStartChatIn = { projectId ->
                showCloudProjects = false
                showCloudCreate = false
                ai.antigem.data.agent.agy.AgyCloud.select(projectId)
                viewModel.startNewChat(ai.antigem.domain.model.AgentKind.AGY)
                viewModel.agyBackend.setNewChatCloudProject(projectId)
            },
            onDismiss = { showCloudProjects = false; showCloudCreate = false }
        )
    }
    if (showCloudPreview && currentConv?.isCloud == true) {
        val previewConvId = currentConv!!.id
        val previewPorts = remember(messages) { ai.antigem.ui.cloud.cloudPreviewPorts(messages) }
        // first: which port, and in the app or in the system browser
        ai.antigem.ui.cloud.CloudPreviewChooser(
            suggestedPorts = previewPorts,
            onOpenInApp = { port -> showCloudPreview = false; cloudPreviewInApp = previewConvId to port },
            onOpenInBrowser = { port ->
                showCloudPreview = false
                runCatching {
                    // served on this phone through the hub, so the system browser can show the VM's page
                    val url = ai.antigem.data.agent.agy.CloudPreviewServer.urlFor(previewConvId, port)
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    ai.antigem.ui.components.AppToastHelper.showToast(L.chat.screen.couldntOpenTheBrowserMessage(it.message), ai.antigem.ui.components.ChatToastType.ERROR)
                }
            },
            onDismiss = { showCloudPreview = false }
        )
    }
    cloudPreviewInApp?.let { (convId, port) ->
        val previewPorts = remember(messages) { ai.antigem.ui.cloud.cloudPreviewPorts(messages) }
        ai.antigem.ui.cloud.CloudPreviewDialog(
            conversationId = convId,
            suggestedPorts = previewPorts,
            initialPort = port,
            onDismiss = { cloudPreviewInApp = null }
        )
    }
    if (showCloudRunCommand) {
        ai.antigem.ui.cloud.CloudRunCommandDialog(
            onRun = { cmd ->
                viewModel.sendMessage(ai.antigem.ui.cloud.cloudRunCommandPrompt(cmd))
                userSentMessageTrigger++
            },
            onDismiss = { showCloudRunCommand = false }
        )
    }

    // the Antigravity voice call screen (Back minimizes it to the pill above the message box)
    ai.antigem.ui.voice.AgyVoiceCallScreen(
        agentWorking = isActivelyRunning && currentConv?.id == voiceCall.conversationId,
        onOpenAgySettings = {
            requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.AGY_CLI
            showSettingsDialog = true
        }
    )

    // Model Selector Bottom Sheet (Active Model at Top & Expandable Categories)
    if (isClaudeChat) ai.antigem.ui.claude.ClaudeProviderSwitchDialog(claudeBackend)
    if (showModelSelector && isClaudeChat) {
        ai.antigem.ui.claude.ClaudeModelSheet(claudeBackend, viewModel.claudeAccount) { showModelSelector = false }
    } else if (showModelSelector && isCodexChat) {
        ai.antigem.ui.codex.CodexModelSheet(
            codexBackend,
            onOpenSettings = { openCodexSettings = true; showSettingsDialog = true }
        ) { showModelSelector = false }
    } else if (showModelSelector && isOpenCodeChat) {
        ai.antigem.ui.opencode.OpenCodeModelSheet(
            openCodeBackend,
            onOpenSettings = { openCodeSettingsPage = ai.antigem.ui.opencode.OpenCodeSettingsPage.PROVIDERS; showSettingsDialog = true }
        ) { showModelSelector = false }
    } else if (showModelSelector) {
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
            onLogout = {
                viewModel.logout()
            },
            agyStatus = agyEntry,
            agyAuth = agyAuthInfo,
            onAgySignOut = { viewModel.logoutFromAgyHub() },
            onAgyCancelSignIn = { viewModel.cancelAgyLogin() },
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
            floatingSwitcherOrientation = floatingSwitcherOrientation,
            floatingSwitcherItems = floatingSwitcherItems,
            floatingSwitcherAutoCollapseSec = floatingSwitcherAutoCollapseSec,
            onSetFloatingSwitcherOrientation = { viewModel.setFloatingSwitcherOrientation(it) },
            floatingSwitcherHandleIcon = floatingSwitcherHandleIcon,
            onSetFloatingSwitcherHandleIcon = { viewModel.setFloatingSwitcherHandleIcon(it) },
            onSetFloatingSwitcherItems = { viewModel.setFloatingSwitcherItems(it) },
            onSetFloatingSwitcherAutoCollapseSec = { viewModel.setFloatingSwitcherAutoCollapseSec(it) },
            onResetFloatingSwitcherPosition = { viewModel.resetFloatingSwitcherPosition() },
            chatHeaderItems = chatHeaderItems,
            onSetChatHeaderItems = { viewModel.setChatHeaderItems(it) },
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
            groupAgentSteps = groupAgentSteps,
            onToggleGroupAgentSteps = { viewModel.setGroupAgentSteps(it) },
            groupStepsMin = groupStepsMin,
            onSetGroupStepsMin = { viewModel.setGroupStepsMin(it) },
            showMessageStats = showMessageStats,
            onToggleShowMessageStats = { viewModel.setShowMessageStats(it) },
            isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
            onToggleFloatingDiagnostics = { viewModel.setFloatingDiagnosticsEnabled(it) },
            isNetworkInspectorEnabled = isNetworkInspectorEnabled,
            onToggleNetworkInspector = { viewModel.setNetworkInspectorEnabled(it) },
            isFloatingNetworkInspectorEnabled = isFloatingNetworkInspectorEnabled,
            onToggleFloatingNetworkInspector = { viewModel.setFloatingNetworkInspectorEnabled(it) },
            onOpenNetworkInspector = { showNetworkInspectorDialog = true },
            claude = claudeSettingsDeps,
            initialClaudePage = claudeSettingsPage,
            openCode = viewModel.openCodeBackend,
            initialOpenCodePage = openCodeSettingsPage,
            codex = viewModel.codexBackend,
            initialCodexPage = codexSettingsPage,
            initialSection = requestedSettingsSection
                ?: if (openOpenCodeSettings) ai.antigem.ui.settings.SettingsSection.OPENCODE
                else if (openCodexSettings) ai.antigem.ui.settings.SettingsSection.CODEX else null,
            enabledAgents = enabledAgents,
            isApplyingAgents = isApplyingAgents,
            onApplyAgents = { viewModel.applyAgents(it) },
            onOpenProject = { project ->
                IdeDaemonManager.setActiveProject(project)
                viewModel.onProjectChanged(project.path)
            },
            onDismiss = {
                showSettingsDialog = false
                claudeSettingsPage = null
                requestedSettingsSection = null
                openOpenCodeSettings = false
                openCodeSettingsPage = null
                openCodexSettings = false
                codexSettingsPage = null
            }
        )
    }

    if (showClaudeLogin) {
        ai.antigem.ui.claude.ClaudeLoginDialog(viewModel.claudeAccount) { showClaudeLogin = false }
    }

    // asked once the local environment (rootfs) is installed, before the local server starts
    // First-launch setup, one full-screen step at a time: the agents, then the terminal icon (that one waits for the
    // agent choice so the two never show at once). The steps are numbered while both are still to do.
    val agentStepPending = !hasChosenAgents &&
        (isLocalToolsInstalled || ai.antigem.data.local.LocalEnvironmentManager.isInstalled(context) || AuthPreferences.currentRemoteMode)
    val launcherStepPending = !hasSeenTerminalLauncherOnboarding && !isInFloatingWindow
    var setupSteps by remember { mutableStateOf(0) }
    LaunchedEffect(agentStepPending, launcherStepPending) {
        if (agentStepPending && launcherStepPending) setupSteps = 2
    }
    if (agentStepPending) {
        ai.antigem.ui.agents.AgentChoiceDialog(step = 1, total = if (launcherStepPending) 2 else 1) { viewModel.applyAgents(it) }
    }
    if (isApplyingAgents) {
        ai.antigem.ui.agents.AgentsApplyingDialog()
    }
    if (showClaudeUsage) {
        ai.antigem.ui.claude.ClaudeUsageDialog(viewModel.claudeAccount, claudeBackend) { showClaudeUsage = false }
    }
    if (showClaudeContext) {
        ai.antigem.ui.claude.ClaudeContextDialog(claudeBackend) { showClaudeContext = false }
    }

    if (showAccountsDialog) {
        ai.antigem.ui.agents.AccountsDialog(
            agy = agyEntry,
            claude = claudeEntry,
            agyAuth = agyAuthInfo,
            claudeStatus = claudeStatus,
            onAgySignOut = { viewModel.logoutFromAgyHub() },
            onAgyCancelSignIn = { viewModel.cancelAgyLogin() },
            onAgySettings = {
                scope.launch { drawerState.close() }
                requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.ANTIGRAVITY
                showSettingsDialog = true
            },
            onClaudeSignOut = { viewModel.claudeAccount.logout() },
            onClaudeSettings = {
                scope.launch { drawerState.close() }
                requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.CLAUDE
                showSettingsDialog = true
            },
            onDismiss = { showAccountsDialog = false },
            openCode = openCodeEntry,
            openCodeFacts = listOfNotNull(
                openCodeStatus?.version?.let { "CLI $it" }
            ),
            onOpenCodeSettings = {
                scope.launch { drawerState.close() }
                openOpenCodeSettings = true
                showSettingsDialog = true
            },
            codex = codexEntry,
            codexEmail = codexAccount?.email,
            codexFacts = listOfNotNull(
                codexAccount?.plan?.replaceFirstChar { it.uppercase() }?.let { "$it plan" },
                codexStatus?.version?.let { "CLI $it" }
            ),
            onCodexSignOut = { viewModel.codexBackend.logout() },
            onCodexSettings = {
                scope.launch { drawerState.close() }
                openCodexSettings = true
                showSettingsDialog = true
            }
        )
    }



    // Project checkpoints: go back to before a message (files, chat, or both), with a preview
    restoreSheetFor?.let { (msg, cp) ->
        val path = ai.antigem.data.checkpoint.ProjectCheckpoints.pathOf(currentConv?.workspaceUri)
        if (path == null) {
            restoreSheetFor = null
        } else {
            ai.antigem.ui.checkpoint.CheckpointRestoreSheet(
                projectPath = path,
                checkpoint = cp,
                onDismiss = { restoreSheetFor = null },
                onRestoreChat = if (isReadOnlyChat) null else {
                    {
                        viewModel.revertAndEditLastUserMessage(msg) { restoredText ->
                            inputState.setTextAndPlaceCursorAtEnd(restoredText)
                        }
                    }
                },
                agentBusy = isActivelyRunning,
                otherRunningChats = remember(path) { viewModel.runningChatsInProject(path) }
            )
        }
    }
    trackProjectAsk?.let { st ->
        ai.antigem.ui.checkpoint.TrackProjectDialog(st) { answer ->
            trackProjectAsk = null
            if (answer != null) scope.launch { ai.antigem.data.checkpoint.ProjectCheckpoints.setDecision(st.path, answer) }
        }
    }

    // Confirmation Dialog for Edit / Retry when deleting subsequent output
    pendingMessageAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingMessageAction = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = if (action.type == MessageActionType.EDIT) L.chat.screen.editMessage else L.chat.screen.regenerateResponse,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = when {
                            (isClaudeChat || isOpenCodeChat || isCodexChat) && action.type == MessageActionType.EDIT ->
                                L.chat.screen.thisChatGoesBackToRight
                            isClaudeChat || isOpenCodeChat || isCodexChat ->
                                L.chat.screen.thisChatGoesBackToRight2(if (isOpenCodeChat) "OpenCode" else if (isCodexChat) L.chat.screen.codex else L.chat.screen.claude)
                            action.type == MessageActionType.EDIT ->
                                L.chat.screen.editingThisMessageWillDeleteThe
                            else ->
                                L.chat.screen.retryingWillDeleteTheCurrentResponse
                        },
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    // files go back only through the message's restore point (Settings → Project checkpoints)
                    val cp = messageCheckpoints[action.message.id]
                    if (action.type == MessageActionType.EDIT && cp != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) {
                                    restoreSheetFor = action.message to cp
                                    pendingMessageAction = null
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.History, null, tint = ai.antigem.theme.AppColors.info, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(L.chat.screen.putFilesBackTooUseThis, fontSize = 13.5.sp, color = ai.antigem.theme.AppColors.info)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = action.message
                        if ((isClaudeChat || isOpenCodeChat || isCodexChat) && action.type == MessageActionType.EDIT) {
                            viewModel.editClaudeMessage(target, restoreCode = false) { restoredText ->
                                inputState.setTextAndPlaceCursorAtEnd(restoredText)
                            }
                        } else if (action.type == MessageActionType.EDIT) {
                            viewModel.revertAndEditLastUserMessage(target) { restoredText ->
                                inputState.setTextAndPlaceCursorAtEnd(restoredText)
                            }
                        } else {
                            viewModel.retryMessage(target.id)
                            userSentMessageTrigger++
                        }
                        pendingMessageAction = null
                    }
                ) {
                    Text(
                        text = when {
                            (isClaudeChat || isOpenCodeChat || isCodexChat) && action.type == MessageActionType.EDIT -> L.common.edit
                            action.type == MessageActionType.EDIT -> L.chat.screen.editDelete
                            else -> L.chat.screen.regenerate
                        },
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMessageAction = null }) {
                    Text(text = L.common.cancel, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                        text = L.chat.screen.stopGeneration,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.5.sp
                    )
                }
            },
            text = {
                Text(
                    text = L.chat.screen.doYouWantToStopThe,
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
                        text = L.chat.screen.stopOutput,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) {
                    Text(text = L.common.cancel, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                        tint = ai.antigem.theme.AppColors.danger,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = L.chat.screen.commandTerminated,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = L.chat.screen.youStoppedTheRunningCommand,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ai.antigem.theme.AppColors.terminalBg,
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
                        text = L.chat.screen.explainWhyOrGiveNewInstructions,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = userFeedback,
                        onValueChange = { userFeedback = it },
                        placeholder = { Text(L.chat.screen.eGItWasTakingToo, fontSize = 12.5.sp) },
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
                    Text(text = L.chat.screen.sendToAiProceed, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissTerminatedToolDialog() }
                ) {
                    Text(text = L.chat.screen.stopTurnHere, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.5.sp)
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
    // the terminal drawer's gear: close the terminal, show Settings → Terminal
    val openTerminalSettingsFromTerminal: () -> Unit = {
        showLocalTerminalDialog = false
        requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.TERMINAL
        showSettingsDialog = true
    }
    if (showLocalTerminalDialog) {
        if (isInFloatingWindow) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f),
                color = MaterialTheme.colorScheme.background
            ) {
                LocalTerminalContent(
                    onClose = { showLocalTerminalDialog = false },
                    onOpenSettings = openTerminalSettingsFromTerminal
                )
            }
        } else {
            LocalTerminalDialog(
                onDismiss = { showLocalTerminalDialog = false },
                onOpenSettings = openTerminalSettingsFromTerminal
            )
        }
    }

    // Local Server Output & Logs Dialog
    if (showLocalServerOutputDialog) {
        ai.antigem.ui.components.LocalServerOutputDialog(
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

    // Terminal Launcher First-Launch Onboarding Dialog: tells users the separate terminal icon exists,
    // with "hidden" preselected (the icon is off by default; most users only need the in-app terminal)
    if (launcherStepPending && hasChosenAgents && !agentStepPending) {
        ai.antigem.ui.onboarding.TerminalLauncherOnboardingDialog(
            step = if (setupSteps == 2) 2 else 1,
            total = if (setupSteps == 2) 2 else 1,
            initialEnabled = remember(context) { ai.antigem.data.preferences.TerminalLauncherManager.isLauncherEnabled(context) },
            initialStyle = remember(context) { ai.antigem.data.preferences.TerminalLauncherManager.getLauncherStyle(context) },
            onConfirm = { enabled, style ->
                scope.launch {
                    viewModel.authPreferences.setTerminalLauncherEnabled(enabled)
                    viewModel.authPreferences.setTerminalLauncherStyle(style)
                    viewModel.authPreferences.setHasSeenTerminalLauncherOnboarding(true)
                    ai.antigem.data.preferences.TerminalLauncherManager.applyLauncherSetting(
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

    // "Meet Gemmy": once the whole first-launch setup is done and the user is in chat, ask once whether to show the
    // floating buddy (on by default, can be turned off right there). Gemmy itself only appears after this is answered.
    val gemmyIntroDue = !hasSeenGemmyIntro && hasChosenAgents && !agentStepPending && !launcherStepPending &&
        !isApplyingAgents && !isInFloatingWindow
    var showGemmyIntro by remember { mutableStateOf(false) }
    LaunchedEffect(gemmyIntroDue) {
        if (gemmyIntroDue) delay(700) // let the chat settle after the setup steps
        showGemmyIntro = gemmyIntroDue
    }
    if (showGemmyIntro && gemmyIntroDue) {
        ai.antigem.ui.onboarding.GemmyIntroDialog(
            onConfirm = { enabled ->
                showGemmyIntro = false
                scope.launch {
                    viewModel.authPreferences.setGemmyEnabled(enabled)
                    viewModel.authPreferences.setHasSeenGemmyIntro(true)
                }
            }
        )
    }

    // Attachment Selector Bottom Sheet
    if (showAttachmentSelector) {
        AttachmentSelectorBottomSheet(
            onPickImage = { photoPickerLauncher.launch("image/*") },
            onTakePhoto = onTakePhotoClick,
            onPickFile = { filePickerLauncher.launch("*/*") },
            onPickFromFiles = { showAttachFromFiles = true },
            onDismiss = { showAttachmentSelector = false }
        )
    }

    // Files on the agent's machine, attached by path (nothing is copied)
    if (showAttachFromFiles) {
        ai.antigem.ui.files.FileExplorerDialog(
            stateKey = "chat_attach",
            title = L.chat.screen.attachFiles,
            onPickFiles = { picked ->
                showAttachFromFiles = false
                viewModel.addExplorerFiles(picked)
            },
            pickFilesLabel = L.chat.screen.attach,
            showClipboard = false,
            onDismiss = { showAttachFromFiles = false }
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
                    IdeDaemonManager.openOrSelectTab(path, File(path).name, content)
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
                    IdeDaemonManager.openOrSelectTab(path, File(path).name, content)
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

    // "Open with" sheet, archive contents and progress for tapped file paths
    ai.antigem.ui.files.FileLinkOpenerHost(fileLinkOpener)

    // File Details & Quick Action Dialog (Long press / metadata)
    activeFileDetailsPath?.let { path ->
        FileDetailsDialog(
            filePath = path,
            onDismiss = { activeFileDetailsPath = null },
            onOpenInIde = { filePath ->
                activeFileDetailsPath = null
                fileLinkOpener.open(filePath)
            },
            onOpenWith = { filePath ->
                activeFileDetailsPath = null
                fileLinkOpener.openWith(filePath)
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

        val cloudSelectedId by ai.antigem.data.agent.agy.AgyCloud.selectedProjectId.collectAsState()
        val cloudError by ai.antigem.data.agent.agy.AgyCloud.error.collectAsState()
        val chatCloudId = if (isAgyChat) currentConv?.cloudProjectId ?: cloudSelectedId else null
        val cloudPickerSection: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = if (!isAgyChat || !cloudEnabled) null else fun androidx.compose.foundation.lazy.LazyListScope.() {
            cloudProjectsSection(
                projects = cloudProjects,
                selectedId = chatCloudId,
                error = cloudError,
                onSelect = { id ->
                    viewModel.agyBackend.setNewChatCloudProject(id)
                    showProjectPickerDialog = false
                },
                onCreate = { showProjectPickerDialog = false; showCloudCreate = true },
                onManage = { showProjectPickerDialog = false; showCloudProjects = true }
            )
        }
        ProjectPickerDialog(
            projects = usedProjects,
            activeProject = if (chatCloudId != null) null else activeProj,
            onSelectProject = { proj ->
                if (isAgyChat) viewModel.agyBackend.setNewChatCloudProject(null)
                IdeDaemonManager.setActiveProject(proj)
                viewModel.onProjectChanged(proj?.path ?: "")
                showProjectPickerDialog = false
            },
            // Antigravity chats can also run in Google's cloud (Remote Agents) with a cloud project's repos
            cloudSection = cloudPickerSection,
            cloudSelected = chatCloudId != null,
            onBrowseFolder = {
                showProjectPickerDialog = false
                showWorkspaceFolderBrowserDialog = true
            },
            onCloneRepo = {
                showProjectPickerDialog = false
                showGitCloneDialog = true
            },
            onRefresh = {
                scope.launch {
                    IdeDaemonManager.loadProjects(conversations)
                }
            },
            onDismiss = { showProjectPickerDialog = false }
        )
    }

    if (showGitCloneDialog) {
        ai.antigem.ui.components.GitCloneDialog(
            onCloned = { clonedProj ->
                showGitCloneDialog = false
                IdeDaemonManager.setActiveProject(clonedProj)
                viewModel.onProjectChanged(clonedProj.path)
            },
            onDismiss = { showGitCloneDialog = false }
        )
    }

    if (showWorkspaceFolderBrowserDialog) {
        ai.antigem.ui.files.FileExplorerDialog(
            stateKey = "chat_workspace",
            title = L.chat.screen.chooseTheChatsProjectFolder,
            confirmLabel = L.chat.screen.useThisFolder,
            onConfirmFolder = { folder ->
                showWorkspaceFolderBrowserDialog = false
                val selectedProj = ProjectItem(File(folder).name.ifBlank { folder }, folder)
                IdeDaemonManager.setActiveProject(selectedProj)
                viewModel.onProjectChanged(selectedProj.path)
            },
            onOpenedInIde = {
                showWorkspaceFolderBrowserDialog = false
                onNavigateToIde()
            },
            onProjectCreated = { created ->
                showWorkspaceFolderBrowserDialog = false
                IdeDaemonManager.setActiveProject(created)
                viewModel.onProjectChanged(created.path)
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
                // a tap outside the panel closes it. A plain tap detector, not .clickable: that would fold all of the
                // panel's text into this one TalkBack item. TalkBack gets its own "Close" item instead.
                .pointerInput(Unit) { detectTapGestures(onTap = { showArtifactsDrawer = false }) }
                .semantics {
                    contentDescription = L.common.close
                    onClick(label = L.common.close) { showArtifactsDrawer = false; true }
                }
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
                    // absorbs taps so they don't reach the backdrop (no semantics: TalkBack reads the panel's items one by one)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
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
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
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
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
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
        ai.antigem.ui.components.NetworkInspectorDialog(
            onDismiss = { showNetworkInspectorDialog = false }
        )
    }

    // Connect to PC: a PC in use that cannot be reached after the app opened asks what to do
    if (pcConnectEnabled) ai.antigem.ui.pc.PcUnreachablePrompt()

    // Connect to PC: the side bar's sheet (switch PC, add one, back to this phone)
    if (showPcSheet) {
        ai.antigem.ui.pc.PcQuickSheet(
            onDismiss = { showPcSheet = false },
            onManage = {
                showPcSheet = false
                requestedSettingsSection = ai.antigem.ui.settings.SettingsSection.CONNECT_PC
                showSettingsDialog = true
                scope.launch { drawerState.close() }
            }
        )
    }

    // Floating theme picker: above the sidebar and the chat, touches outside it still reach them
    if (showThemePanel) {
        ai.antigem.ui.settings.ThemeQuickPanel(
            themeMode = themeMode,
            onSetThemeMode = { viewModel.setThemeMode(it) },
            onClose = { showThemePanel = false },
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(9999f)
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
        ChatToastType.SUCCESS -> ai.antigem.theme.AppColors.successContainer
        ChatToastType.ERROR -> ai.antigem.theme.AppColors.dangerContainer
        ChatToastType.INFO -> ai.antigem.theme.AppColors.card
    }
    val borderColor = when (toast.type) {
        ChatToastType.SUCCESS -> ai.antigem.theme.AppColors.success.copy(alpha = 0.7f)
        ChatToastType.ERROR -> ai.antigem.theme.AppColors.danger.copy(alpha = 0.7f)
        ChatToastType.INFO -> ClaudeTerracotta.copy(alpha = 0.7f)
    }
    val iconColor = when (toast.type) {
        ChatToastType.SUCCESS -> ai.antigem.theme.AppColors.success
        ChatToastType.ERROR -> ai.antigem.theme.AppColors.danger
        ChatToastType.INFO -> ClaudeTerracotta
    }
    val icon = when (toast.type) {
        ChatToastType.SUCCESS -> Icons.Outlined.CheckCircle
        ChatToastType.ERROR -> Icons.Outlined.ErrorOutline
        ChatToastType.INFO -> Icons.Outlined.Info
    }
    val textColor = ai.antigem.theme.AppColors.onBackground

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
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
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
                    contentDescription = L.common.dismiss,
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

enum class MessageActionType { EDIT, RETRY }
data class PendingMessageAction(val type: MessageActionType, val message: ChatMessage)

/** A `/command` being typed (no space after it yet): where its `/` is, the text after it, and whether only blanks precede it. */
private data class SlashTrigger(val index: Int, val query: String, val nothingBefore: Boolean)

/** An `@file` mention being typed (no space after it yet): where its `@` is and the text after it. */
private data class MentionTrigger(val index: Int, val query: String)

/**
 * A file link from a chat as a full path: a relative one ([Foo.kt](src/Foo.kt), as VS Code chats write them) is a file
 * in the chat's project folder (on the phone, or on the PC / server the bridge runs on).
 */
internal fun resolveChatFileLink(path: String, workspaceUri: String?): String {
    if (path.startsWith("/") || path.isBlank()) return path
    val base = workspaceUri?.removePrefix("file://")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return path
    val parts = ArrayDeque(base.split('/').filter { it.isNotEmpty() })
    for (seg in path.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(seg)
        }
    }
    return "/" + parts.joinToString("/")
}

