package ai.antigem.ui.drawer

import ai.antigem.ui.components.accent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Public
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import android.widget.Toast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.domain.model.Conversation
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.GeminiBlue
import androidx.compose.material.icons.outlined.Share
import ai.antigem.ui.components.ConversationExportHelper
import ai.antigem.ui.components.ConversationShareHelper
import ai.antigem.ui.components.DrawerEngineWarmingUpView
import ai.antigem.ui.components.SidebarChatListSkeleton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import ai.antigem.i18n.L
import ai.antigem.ui.components.mergedIntoRow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

/** Group key of the pinned chats when grouping by workspace (can't clash with a folder name). */
private const val PINNED_GROUP = "\u0000pinned"

@Composable
fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    activeInstances: List<ai.antigem.data.remote.AgyActiveInstance> = emptyList(),
    isLoading: Boolean = false,
    hasReceivedInitialSync: Boolean = false,
    systemConnectionState: ai.antigem.data.remote.SystemConnectionState = ai.antigem.data.remote.SystemConnectionState.Offline,
    errorMessage: String? = null,
    isStreaming: Boolean = false,
    authInfo: AgyHubClient.AgyAuthInfo = AgyHubClient.AgyAuthInfo(),
    isAuthBusy: Boolean = false,
    groupByWorkspace: Boolean = false,
    onToggleGroupByWorkspace: (Boolean) -> Unit = {},
    /** Archive view: only archived chats are listed (otherwise only the others). */
    showArchived: Boolean = false,
    onToggleShowArchived: (Boolean) -> Unit = {},
    onRetry: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit = {},
    onTerminateInstance: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    /** Opens the floating theme picker (a palette button next to Settings; hidden when null). */
    onOpenThemes: (() -> Unit)? = null,
    /** Connect to PC's button next to Themes (only while the feature is on; null hides it). */
    pcButton: (@Composable () -> Unit)? = null,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckAuth: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    isOpen: Boolean = false,
    /** Accounts of the enabled agents; replaces the Antigravity-only profile / sign-in button when set. */
    accountsButton: (@Composable () -> Unit)? = null,
    /** Antigravity runs: its hub start-up and first sync drive the list's loading states. */
    agyEnabled: Boolean = true,
    claudeActions: ClaudeDrawerActions? = null,
    /** Jarvis (voice mode) is on: the filter row gets a Jarvis chip listing Jarvis' hidden chats. */
    jarvisEnabled: Boolean = false,
    jarvisConversations: List<Conversation> = emptyList(),
    /** Starts a Jarvis text chat (the command center) with that agent, or Jarvis' own agent for null. */
    onNewJarvisChat: ((ai.antigem.domain.model.AgentKind?) -> Unit)? = null,
    enabledAgents: Set<ai.antigem.domain.model.AgentKind> = emptySet(),
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showFullSearchDialog by remember { mutableStateOf(false) }
    var instanceToTerminate by remember {
        mutableStateOf<Pair<ai.antigem.data.remote.AgyActiveInstance, String>?>(
            null
        )
    }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showSigningInProgressDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<Conversation?>(null) }
    var isInitialGracePeriod by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500)
        isInitialGracePeriod = false
    }
    val context = LocalContext.current
    // the Ubuntu chroot in use: the Exit dialog asks whether it stops too (preset from its exit setting)
    val chrootInUse = remember(showExitConfirmDialog) { ai.antigem.data.local.chroot.ChrootManager.isActiveNow() }
    var stopChrootOnExit by remember(showExitConfirmDialog) {
        mutableStateOf(ai.antigem.data.local.chroot.ChrootManager.isActiveNow() &&
            ai.antigem.data.local.chroot.ChrootManager.exitBehavior(context) != ai.antigem.data.local.chroot.ChrootExitBehavior.KEEP_RUNNING)
    }
    // pinned chats (app level, by conversation id) sit at the top of the list
    remember { ai.antigem.data.preferences.PinnedChats.init(context) }
    val pinnedIds by ai.antigem.data.preferences.PinnedChats.ids.collectAsState()

    var agentFilter by remember { mutableStateOf<ai.antigem.domain.model.AgentKind?>(null) }
    val visibleAgents = remember(enabledAgents, conversations) {
        val base = if (enabledAgents.isNotEmpty()) enabledAgents else conversations.map { it.agent }.distinct().toSet()
        ai.antigem.domain.model.AgentKind.entries.filter { it in base }
    }
    val showFilterBar = (visibleAgents.size + if (jarvisEnabled) 1 else 0) > 1
    if (agentFilter != null && agentFilter !in visibleAgents) agentFilter = null
    var jarvisFilter by remember { mutableStateOf(false) }
    if (!jarvisEnabled && jarvisFilter) jarvisFilter = false
    val jarvisIds = remember(jarvisConversations) { jarvisConversations.map { it.id }.toSet() }
    // archived chats, sub-agent chats included when a parent up the chain is archived (they move with it)
    val archivedIds = remember(conversations, jarvisConversations) {
        val byId = (conversations + jarvisConversations).associateBy { it.id }
        fun isArchived(c: Conversation): Boolean {
            var cur: Conversation? = c
            var hops = 0
            while (cur != null && hops++ < 32) {
                if (cur.archived) return true
                val p = cur.parentConversationId
                if (p == null || p == cur.id) return false
                cur = byId[p]
            }
            return false
        }
        byId.values.filter(::isArchived).map { it.id }.toSet()
    }
    val filtered = remember(conversations, jarvisConversations, searchQuery, agentFilter, jarvisFilter, showArchived, archivedIds) {
        val byAgent = (if (jarvisFilter) jarvisConversations
        else agentFilter?.let { a -> conversations.filter { it.agent == a } } ?: conversations)
            .filter { (it.id in archivedIds) == showArchived }
        if (searchQuery.isBlank()) byAgent
        else byAgent.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    (it.subagentRole != null && it.subagentRole.contains(searchQuery, ignoreCase = true))
        }
    }

    val allFilteredIds = remember(filtered) { filtered.map { it.id }.toSet() }

    // Map parentConversationId -> List<Conversation> (subagents)
    // Only if parentConversationId != null and parentConversationId is found in allFilteredIds!
    val subagentsByParent = remember(filtered, allFilteredIds) {
        filtered.filter { conv ->
            conv.parentConversationId != null &&
                    conv.parentConversationId != conv.id &&
                    conv.parentConversationId in allFilteredIds
        }.groupBy { it.parentConversationId!! }
    }

    // Top-level conversations (conversations without a valid parent in the list)
    // pinned ones first (a stable sort: both parts keep their usual newest-first order)
    val topLevelChats = remember(filtered, allFilteredIds, pinnedIds) {
        filtered.filter { conv ->
            val pId = conv.parentConversationId
            pId == null || pId == conv.id || !allFilteredIds.contains(pId)
        }.sortedByDescending { it.id in pinnedIds }
    }

    var expandedParentIds by remember { mutableStateOf(setOf<String>()) }

    // Auto expand ancestor parents only when the current conversation is itself a nested subagent
    LaunchedEffect(currentConversationId) {
        if (!currentConversationId.isNullOrBlank()) {
            val toExpand = mutableSetOf<String>()
            val curr = conversations.find { it.id == currentConversationId }
            if (curr != null) {
                // Walk up the parent chain to expand all ancestors (nested parent of parent)
                var pId = curr.parentConversationId
                while (!pId.isNullOrBlank()) {
                    val parentConv = conversations.find { it.id == pId }
                    if (parentConv != null) {
                        toExpand.add(parentConv.id)
                        pId = parentConv.parentConversationId
                    } else {
                        break
                    }
                }
            }
            if (toExpand.isNotEmpty()) {
                expandedParentIds = expandedParentIds + toExpand
            }
        }
    }

    // grouped by workspace: pinned chats get their own "Pinned" group above the workspaces
    val grouped = remember(topLevelChats, pinnedIds) {
        topLevelChats.groupBy { conv ->
            if (conv.id in pinnedIds) {
                PINNED_GROUP
            } else if (conv.workspaceUri.isNotBlank()) {
                val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                java.io.File(clean).name.ifBlank { L.common.workspace }
            } else {
                "General"
            }
        }
    }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Ensure selected conversation is visible, or scroll to very top if on a new chat
    LaunchedEffect(isOpen, currentConversationId, filtered.isEmpty()) {
        if (!isOpen) return@LaunchedEffect

        val isExistingChat = !currentConversationId.isNullOrBlank() && filtered.any { it.id == currentConversationId }
        if (!isExistingChat) {
            // Brand new chat or conversation not in list -> always start at the very top!
            listState.scrollToItem(0)
            return@LaunchedEffect
        }

        val targetIndex = if (!groupByWorkspace) {
            val visibleTree = buildVisibleChatTree(topLevelChats, subagentsByParent, expandedParentIds)
            visibleTree.indexOfFirst { it.conv.id == currentConversationId }
        } else {
            val targetGroup = grouped.entries.firstOrNull { (_, chats) ->
                chats.any { top ->
                    val allDescendantIds = mutableSetOf<String>()
                    fun collect(id: String) {
                        allDescendantIds.add(id)
                        subagentsByParent[id]?.forEach { collect(it.id) }
                    }
                    collect(top.id)
                    allDescendantIds.contains(currentConversationId)
                }
            }
            val currentExpanded = if (targetGroup != null) {
                val chatIndexInGroup = targetGroup.value.indexOfFirst { top ->
                    val allDescendantIds = mutableSetOf<String>()
                    fun collect(id: String) {
                        allDescendantIds.add(id)
                        subagentsByParent[id]?.forEach { collect(it.id) }
                    }
                    collect(top.id)
                    allDescendantIds.contains(currentConversationId)
                }
                if (chatIndexInGroup >= 5 && !expandedGroups.contains(targetGroup.key)) {
                    expandedGroups = expandedGroups + targetGroup.key
                    kotlinx.coroutines.yield()
                    expandedGroups + targetGroup.key
                } else {
                    expandedGroups
                }
            } else {
                expandedGroups
            }

            var index = 0
            var foundIndex = -1
            for ((groupName, chats) in grouped) {
                index++ // item(key = "hdr_$groupName")
                val isExp = currentExpanded.contains(groupName)
                val displayChats = if (isExp) chats else chats.take(5)
                val groupTree = buildVisibleChatTree(displayChats, subagentsByParent, expandedParentIds)
                val idxInTree = groupTree.indexOfFirst { it.conv.id == currentConversationId }
                if (idxInTree != -1) {
                    foundIndex = index + idxInTree
                    break
                }
                index += groupTree.size
                if (chats.size > 5) {
                    index++ // item(key = "more_$groupName")
                }
            }
            foundIndex
        }

        if (targetIndex >= 0) {
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val visibleItem = visibleInfo.firstOrNull { it.index == targetIndex }
            val isFullyVisible = visibleItem != null &&
                    visibleItem.offset >= listState.layoutInfo.viewportStartOffset &&
                    (visibleItem.offset + visibleItem.size) <= listState.layoutInfo.viewportEndOffset

            if (!isFullyVisible) {
                val scrollPos = (targetIndex - 1).coerceAtLeast(0)
                listState.scrollToItem(scrollPos)
            }
        } else {
            listState.scrollToItem(0)
        }
    }

    if (instanceToTerminate != null) {
        val (inst, title) = instanceToTerminate!!
        AlertDialog(
            onDismissRequest = { instanceToTerminate = null },
            title = { Text(L.drawer.chatHistoryDrawer.activeCliProcess, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = L.drawer.chatHistoryDrawer.aLiveAntigravityCliProcessIs,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(L.drawer.chatHistoryDrawer.chatTitle(title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(L.drawer.chatHistoryDrawer.modelModel(inst.model), fontSize = 12.sp)
                            Text(L.drawer.chatHistoryDrawer.uptimeUptimesecondsS(inst.uptimeSeconds), fontSize = 12.sp)
                            if (inst.pid > 0) {
                                Text(L.drawer.chatHistoryDrawer.pidPid(inst.pid), fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = L.drawer.chatHistoryDrawer.terminateThisInstanceToImmediatelyFree,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTerminateInstance(inst.conversationId)
                        instanceToTerminate = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(L.drawer.chatHistoryDrawer.terminateFreeRam, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToTerminate = null }) {
                    Text(L.drawer.chatHistoryDrawer.keepRunning, fontSize = 13.sp)
                }
            }
        )
    }

    if (showSigningInProgressDialog) {
        AlertDialog(
            onDismissRequest = { showSigningInProgressDialog = false },
            title = {
                Text(
                    text = L.drawer.chatHistoryDrawer.signInInProgress,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = L.drawer.chatHistoryDrawer.aSignInRequestIsCurrently,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showSigningInProgressDialog = false
                            onCheckAuth()
                        }
                    ) {
                        Text(L.drawer.chatHistoryDrawer.checkStatus)
                    }
                    Button(
                        onClick = {
                            showSigningInProgressDialog = false
                            onLogin()
                        }
                    ) {
                        Text(L.drawer.chatHistoryDrawer.signInAgain)
                    }
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showSigningInProgressDialog = false
                            onCancelLogin()
                        }
                    ) {
                        Text(L.common.later)
                    }
                    TextButton(onClick = { showSigningInProgressDialog = false }) {
                        Text(L.common.cancel)
                    }
                }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = L.drawer.chatHistoryDrawer.exitAntigem,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = if (chrootInUse) L.drawer.chatHistoryDrawer.doYouWantToExitThe
                        else L.drawer.chatHistoryDrawer.doYouWantToExitThe2,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                    // the Ubuntu chroot runs outside the app: keep it working, or stop it too (this exit only)
                    if (chrootInUse) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp).clickable { stopChrootOnExit = !stopChrootOnExit }
                                .semantics { role = Role.Checkbox; toggleableState = ToggleableState(stopChrootOnExit) }
                        ) {
                            Checkbox(checked = stopChrootOnExit, onCheckedChange = { stopChrootOnExit = it }, modifier = Modifier.mergedIntoRow())
                            Text(
                                if (stopChrootOnExit) L.drawer.chatHistoryDrawer.alsoStopTheUbuntuChrootIts
                                else L.drawer.chatHistoryDrawer.alsoStopTheUbuntuChrootOff,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        if (chrootInUse) {
                            val saved = ai.antigem.data.local.chroot.ChrootManager.exitBehavior(context)
                            ai.antigem.data.local.chroot.ChrootManager.exitOverride = when {
                                !stopChrootOnExit -> ai.antigem.data.local.chroot.ChrootExitBehavior.KEEP_RUNNING
                                saved != ai.antigem.data.local.chroot.ChrootExitBehavior.KEEP_RUNNING -> saved
                                else -> ai.antigem.data.local.chroot.ChrootExitBehavior.STOP_ALL
                            }
                        }
                        try {
                            ai.antigem.data.service.AntiGemService.stop(context)
                        } catch (e: Exception) {
                            Log.e("ChatHistoryDrawer", "Error stopping service", e)
                            try {
                                ai.antigem.data.local.LocalServerManager.forceKillAll()
                            } catch (_: Exception) {
                            }
                            try {
                                ai.antigem.data.local.LocalTerminalManager.closeAll()
                            } catch (_: Exception) {
                            }
                            (context as? android.app.Activity)?.finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                            kotlin.system.exitProcess(0)
                        }
                        try {
                            (context as? android.app.Activity)?.finishAffinity()
                        } catch (e: Exception) {
                            Log.e("ChatHistoryDrawer", "Error finishing activity", e)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(L.drawer.chatHistoryDrawer.exitApp, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExitConfirmDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(L.common.cancel, fontSize = 13.5.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (conversationToDelete != null) {
        val targetConv = conversationToDelete!!
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = L.drawer.chatHistoryDrawer.deleteConversation,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = L.drawer.chatHistoryDrawer.areYouSureYouWantTo(targetConv.title.ifBlank { L.drawer.chatHistoryDrawer.thisConversation }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = targetConv.id
                        conversationToDelete = null
                        ai.antigem.data.preferences.PinnedChats.setPinned(id, false)
                        onDeleteConversation(id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(L.common.delete, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { conversationToDelete = null },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(L.common.cancel, fontSize = 13.5.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp)
        )
    }

    ModalDrawerSheet(
        modifier = modifier.fillMaxWidth(0.82f),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // top 6dp (+2dp on the row, +18dp half the 36dp row) centers the header 26dp under the status bar,
                // the same line as the chat's 52dp top bar title and icons
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 6.dp)
        ) {
            // ---------------- header: brand on the left, power + new chat on the right ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AntiGem",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                @Composable
                fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (active) ClaudeTerracotta.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(onClick = onClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = description,
                            tint = if (active) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                HeaderIcon(Icons.Default.Search, L.common.search, isSearchActive || searchQuery.isNotBlank()) {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) {
                        searchQuery = ""
                        onSearchQueryChange("")
                    }
                }
                HeaderIcon(
                    if (groupByWorkspace) Icons.Default.Folder else Icons.Outlined.FolderOpen,
                    if (groupByWorkspace) L.drawer.chatHistoryDrawer.disableWorkspaceGrouping else L.drawer.chatHistoryDrawer.groupByWorkspace,
                    groupByWorkspace
                ) { onToggleGroupByWorkspace(!groupByWorkspace) }
                HeaderIcon(
                    Icons.Outlined.Archive,
                    if (showArchived) L.drawer.chatHistoryDrawer.showChats else L.drawer.chatHistoryDrawer.showArchivedChats,
                    showArchived
                ) { onToggleShowArchived(!showArchived) }

                // Exit app: quiet until pressed (it asks first)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { showExitConfirmDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = L.drawer.chatHistoryDrawer.exitApp,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // ---------------- search field: only after tapping the header's search icon ----------------
            val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(isSearchActive) {
                if (isSearchActive) runCatching { delay(120); searchFocus.requestFocus() }
            }
            AnimatedVisibility(
                visible = isSearchActive || searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (searchQuery.isNotBlank()) ClaudeTerracotta.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (searchQuery.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                onSearchQueryChange(it)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 18.sp
                            ),
                            cursorBrush = SolidColor(ClaudeTerracotta),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocus),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = L.drawer.chatHistoryDrawer.searchChats,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    onSearchQueryChange("")
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = L.common.clear,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // Deep search (message contents, filters)
                        IconButton(
                            onClick = { showFullSearchDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = L.drawer.chatHistoryDrawer.advancedSearch,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // result count + shortcut to the deep search while filtering
            AnimatedVisibility(
                visible = searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 2.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = L.drawer.chatHistoryDrawer.filteredcountChatValueFound(filtered.size, if (filtered.size != 1) "s" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = L.drawer.chatHistoryDrawer.advancedSearch2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTerracotta,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showFullSearchDialog = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // breathing room between the header and the list
            Spacer(modifier = Modifier.height(10.dp))

            if (showFilterBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allItems = buildList {
                        add(null to L.drawer.chatHistoryDrawer.all)
                        visibleAgents.forEach { agent ->
                            val label = when (agent) {
                                ai.antigem.domain.model.AgentKind.AGY -> L.common.antigravity
                                ai.antigem.domain.model.AgentKind.CLAUDE -> L.common.claude
                                ai.antigem.domain.model.AgentKind.OPENCODE -> L.drawer.chatHistoryDrawer.opencode
                                ai.antigem.domain.model.AgentKind.CODEX -> L.common.codex
                            }
                            add(agent to label)
                        }
                    }
                    allItems.forEach { (agent, label) ->
                        val selected = !jarvisFilter && agentFilter == agent
                        val accent = agent?.accent() ?: ClaudeTerracotta
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton) { jarvisFilter = false; agentFilter = agent }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (jarvisEnabled) {
                        val jarvisAccent = ai.antigem.ui.jarvis.Cyan
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (jarvisFilter) jarvisAccent.copy(alpha = 0.14f) else Color.Transparent)
                                .selectable(selected = jarvisFilter, role = Role.RadioButton) { agentFilter = null; jarvisFilter = true }
                                .padding(start = 7.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ai.antigem.ui.jarvis.JarvisBotIcon(15.dp, if (jarvisFilter) jarvisAccent else jarvisAccent.copy(alpha = 0.7f))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = L.common.jarvis,
                                fontSize = 11.5.sp,
                                fontWeight = if (jarvisFilter) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (jarvisFilter) jarvisAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // archive view: say so, or the list looks like chats went missing
            if (showArchived) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = L.drawer.chatHistoryDrawer.archivedChatsToplevelchatscount(topLevelChats.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTerracotta,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = L.drawer.chatHistoryDrawer.backToChats,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggleShowArchived(false) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // the Jarvis tab: start a Jarvis text chat (a command center that leads teams of agents)
            if (jarvisEnabled && jarvisFilter && onNewJarvisChat != null && !showArchived) {
                JarvisNewChatRow(agents = visibleAgents, onNew = onNewJarvisChat)
            }

            val isEngineConnecting = agyEnabled && (systemConnectionState.status == ai.antigem.data.remote.SystemStatus.STARTING ||
                    systemConnectionState.status == ai.antigem.data.remote.SystemStatus.ACQUIRING_CSRF ||
                    (systemConnectionState.status == ai.antigem.data.remote.SystemStatus.OFFLINE && isInitialGracePeriod)) && conversations.isEmpty()
            val showSkeleton =
                agyEnabled && (!hasReceivedInitialSync || isLoading) && conversations.isEmpty() && errorMessage.isNullOrBlank()

            Crossfade(
                targetState = when {
                    isEngineConnecting -> "engine_warming"
                    showSkeleton -> "loading"
                    !errorMessage.isNullOrBlank() && conversations.isEmpty() -> "error"
                    filtered.isEmpty() -> "empty"
                    else -> "content"
                },
                label = "drawerChatListFade",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { state ->
                when (state) {
                    "engine_warming" -> {
                        DrawerEngineWarmingUpView()
                    }

                    "loading" -> {
                        SidebarChatListSkeleton()
                    }

                    "error" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = L.drawer.chatHistoryDrawer.unableToLoadChats,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage ?: L.drawer.chatHistoryDrawer.connectionError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(L.common.retry, fontSize = 12.5.sp)
                            }
                        }
                    }

                    "empty" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) L.drawer.chatHistoryDrawer.noValueChatsFoundMatchingSearchquery(if (showArchived) "archived " else "", searchQuery)
                                    else if (showArchived) L.drawer.chatHistoryDrawer.noArchivedChatsArchiveAChat
                                    else if (jarvisFilter) L.drawer.chatHistoryDrawer.noJarvisChatsYetStartOne
                                    else L.drawer.chatHistoryDrawer.noRecentChats,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp),
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }

                    else -> {
                        // iOS-style bounce at both ends, also when the chats fit and the list cannot scroll
                        val chatListBounce = ai.antigem.ui.components.rememberBouncyLazyScroll(listState)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(chatListBounce.modifier),
                            userScrollEnabled = chatListBounce.userScrollEnabled,
                            overscrollEffect = chatListBounce.overscrollEffect
                        ) {
                            if (!groupByWorkspace) {
                                val visibleTree =
                                    buildVisibleChatTree(topLevelChats, subagentsByParent, expandedParentIds)
                                items(visibleTree, key = { it.conv.id }) { item ->
                                    val conv = item.conv
                                    val isSelected = conv.id == currentConversationId
                                    val activeInst = activeInstances.find { it.conversationId == conv.id }
                                    val isActivelyRunning = conv.isRunning && (systemConnectionState.isHubOnline || conv.agent != ai.antigem.domain.model.AgentKind.AGY)
                                    val isScheduledOrBackground =
                                        systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                    ChatHistoryItemRow(
                                        conv = conv,
                                        isJarvis = conv.id in jarvisIds,
                                        isSelected = isSelected,
                                        activeInst = activeInst,
                                        isActivelyRunning = isActivelyRunning,
                                        isScheduledOrBackground = isScheduledOrBackground,
                                        depth = item.depth,
                                        displayName = if (item.depth > 0) conv.subagentRole?.takeIf { it.isNotBlank() }
                                            ?: conv.title else conv.title,
                                        subagents = item.subagents,
                                        isSubagentsExpanded = item.isExpanded,
                                        onToggleSubagents = if (item.subagents.isNotEmpty()) {
                                            {
                                                expandedParentIds =
                                                    if (item.isExpanded) expandedParentIds - conv.id else expandedParentIds + conv.id
                                            }
                                        } else null,
                                        onSelectConversation = {
                                            Log.d(
                                                "CHAT_OPEN_DEBUG",
                                                "📂 [Drawer] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                            )
                                            onSelectConversation(conv.id)
                                        },
                                        onForkConversation = onForkConversation,
                                        onRequestDelete = { conversationToDelete = it },
                                        isPinned = item.depth == 0 && conv.id in pinnedIds,
                                        onTogglePin = if (item.depth == 0) {
                                            { ai.antigem.data.preferences.PinnedChats.setPinned(conv.id, conv.id !in pinnedIds) }
                                        } else null,
                                        claudeActions = claudeActions,
                                        onTerminateInstance = { inst, title ->
                                            instanceToTerminate = inst to title
                                        }
                                    )
                                }
                            } else {
                                grouped.forEach { (groupName, chats) ->
                                    val isExpanded = expandedGroups.contains(groupName)
                                    val displayChats = if (isExpanded) chats else chats.take(5)

                                    item(key = "hdr_$groupName") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (chats.size > 5) {
                                                        Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .clickable {
                                                                expandedGroups =
                                                                    if (isExpanded) expandedGroups - groupName else expandedGroups + groupName
                                                            }
                                                    } else Modifier
                                                )
                                                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = when (groupName) {
                                                    PINNED_GROUP -> Icons.Outlined.PushPin
                                                    "General" -> Icons.Outlined.Public
                                                    else -> Icons.Default.Folder
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(7.dp))
                                            Text(
                                                text = when (groupName) {
                                                    PINNED_GROUP -> L.drawer.chatHistoryDrawer.pinned
                                                    "General" -> L.drawer.chatHistoryDrawer.general // group key stays English
                                                    else -> groupName
                                                },
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                letterSpacing = 0.2.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            ) {
                                                Text(
                                                    text = "${chats.size}",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                            if (chats.size > 5) {
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    val groupTree =
                                        buildVisibleChatTree(displayChats, subagentsByParent, expandedParentIds)
                                    items(groupTree, key = { it.conv.id }) { item ->
                                        val conv = item.conv
                                        val isSelected = conv.id == currentConversationId
                                        val activeInst = activeInstances.find { it.conversationId == conv.id }
                                        val isActivelyRunning = conv.isRunning && (systemConnectionState.isHubOnline || conv.agent != ai.antigem.domain.model.AgentKind.AGY)
                                        val isScheduledOrBackground =
                                            systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                        ChatHistoryItemRow(
                                            conv = conv,
                                            isJarvis = conv.id in jarvisIds,
                                            isSelected = isSelected,
                                            activeInst = activeInst,
                                            isActivelyRunning = isActivelyRunning,
                                            isScheduledOrBackground = isScheduledOrBackground,
                                            depth = item.depth,
                                            displayName = if (item.depth > 0) conv.subagentRole?.takeIf { it.isNotBlank() }
                                                ?: conv.title else conv.title,
                                            subagents = item.subagents,
                                            isSubagentsExpanded = item.isExpanded,
                                            onToggleSubagents = if (item.subagents.isNotEmpty()) {
                                                {
                                                    expandedParentIds =
                                                        if (item.isExpanded) expandedParentIds - conv.id else expandedParentIds + conv.id
                                                }
                                            } else null,
                                            onSelectConversation = {
                                                Log.d(
                                                    "CHAT_OPEN_DEBUG",
                                                    "📂 [Drawer Compact] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                                )
                                                onSelectConversation(conv.id)
                                            },
                                            onForkConversation = onForkConversation,
                                            onRequestDelete = { conversationToDelete = it },
                                            isPinned = item.depth == 0 && conv.id in pinnedIds,
                                            onTogglePin = if (item.depth == 0) {
                                                { ai.antigem.data.preferences.PinnedChats.setPinned(conv.id, conv.id !in pinnedIds) }
                                            } else null,
                                            claudeActions = claudeActions,
                                            onTerminateInstance = { inst, title ->
                                                instanceToTerminate = inst to title
                                            }
                                        )
                                    }

                                    if (chats.size > 5) {
                                        item(key = "more_$groupName") {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        expandedGroups =
                                                            if (isExpanded) expandedGroups - groupName else expandedGroups + groupName
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (isExpanded) L.drawer.chatHistoryDrawer.showLess else L.drawer.chatHistoryDrawer.showValueMore(chats.size - 5),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ClaudeTerracotta
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = ClaudeTerracotta,
                                                    modifier = Modifier.size(14.dp)
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

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // Bottom Bar: Settings (Left) & Profile/Login (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Settings on left: icon only, same style as the Themes button next to it
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = L.common.settings,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }

                // Themes: a floating picker over the sidebar and chat, to try themes and see them live
                if (onOpenThemes != null) {
                    Surface(
                        onClick = onOpenThemes,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = L.common.themes,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(8.dp).size(20.dp)
                        )
                    }
                }

                // Mini Apps: the grid of on-device apps in ~/mini-apps
                val miniAppsContext = androidx.compose.ui.platform.LocalContext.current
                Surface(
                    onClick = { miniAppsContext.startActivity(android.content.Intent(miniAppsContext, ai.antigem.ui.miniapps.MiniAppsActivity::class.java)) },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Apps,
                        contentDescription = L.drawer.chatHistoryDrawer.miniApps,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }

                // Connect to PC: which PC is in use, switch, add one
                pcButton?.invoke()

                // keeps the profile / login button on the right
                Spacer(modifier = Modifier.weight(1f))

                // Profile / Login on right
                if (accountsButton != null) {
                    accountsButton()
                } else if (isAuthBusy) {
                    Surface(
                        onClick = { showSigningInProgressDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.12f),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(ClaudeTerracotta, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = L.drawer.chatHistoryDrawer.signingIn,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = ClaudeTerracotta
                            )
                        }
                    }
                } else {
                    when (systemConnectionState.status) {
                        ai.antigem.data.remote.SystemStatus.READY -> {
                            // Profile avatar button with real display name
                            Surface(
                                onClick = { showProfileDialog = true },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    ClaudeTerracotta.copy(alpha = 0.4f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(parseProfileAvatarModel(authInfo.profilePictureUrl))
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = L.drawer.chatHistoryDrawer.profilePicture,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        loading = {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(ClaudeTerracotta),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val initial =
                                                    authInfo.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                                                Text(
                                                    text = initial,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        },
                                        error = {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(ClaudeTerracotta),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val initial =
                                                    authInfo.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                                                Text(
                                                    text = initial,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    )
                                    val nameToShow = authInfo.displayName
                                    if (nameToShow.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = nameToShow,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 110.dp),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        ai.antigem.data.remote.SystemStatus.UNAUTHENTICATED -> {
                            // Not logged in (UNAUTHENTICATED) -> Sign In button
                            FilledTonalButton(
                                onClick = onLogin,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = ClaudeTerracotta.copy(alpha = 0.15f),
                                    contentColor = ClaudeTerracotta
                                ),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Login,
                                    contentDescription = L.drawer.chatHistoryDrawer.signIn,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = L.drawer.chatHistoryDrawer.signIn,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        else -> {
                            // All intermediate or offline states (OFFLINE, STARTING, ACQUIRING_CSRF, CHECKING_AUTH, ERROR)
                            val statusObj = systemConnectionState.status
                            Surface(
                                onClick = if (statusObj == ai.antigem.data.remote.SystemStatus.OFFLINE || statusObj == ai.antigem.data.remote.SystemStatus.ERROR) onRetry else onCheckAuth,
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(statusObj.dotColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = statusObj.label,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showProfileDialog) {
                ProfileDetailDialog(
                    authInfo = authInfo,
                    onDismiss = { showProfileDialog = false },
                    onLogout = onLogout
                )
            }

            if (showFullSearchDialog) {
                SearchChatsDialog(
                    conversations = conversations,
                    currentConversationId = currentConversationId,
                    onSelectConversation = onSelectConversation,
                    onDismiss = { showFullSearchDialog = false }
                )
            }
        }
    }
}

private fun formatRelativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = (now - timestampMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    val months = days / 30L
    val years = days / 365L

    return when {
        minutes < 1 -> "now"
        hours < 1 -> "${minutes}m"
        days < 1 -> "${hours}h"
        days < 30 -> "${days}d"
        days < 365 -> "${months.coerceAtLeast(1)}mo"
        else -> "${years.coerceAtLeast(1)}y"
    }
}

private data class VisibleChatTreeItem(
    val conv: Conversation,
    val depth: Int,
    val subagents: List<Conversation>,
    val isExpanded: Boolean
)

private fun buildVisibleChatTree(
    rootChats: List<Conversation>,
    subagentsByParent: Map<String, List<Conversation>>,
    expandedParentIds: Set<String>,
    depth: Int = 0
): List<VisibleChatTreeItem> {
    val result = mutableListOf<VisibleChatTreeItem>()
    for (conv in rootChats) {
        val children = subagentsByParent[conv.id] ?: emptyList()
        val isExpanded = expandedParentIds.contains(conv.id)
        result.add(
            VisibleChatTreeItem(
                conv = conv,
                depth = depth,
                subagents = children,
                isExpanded = isExpanded
            )
        )
        if (isExpanded && children.isNotEmpty()) {
            result.addAll(
                buildVisibleChatTree(
                    rootChats = children,
                    subagentsByParent = subagentsByParent,
                    expandedParentIds = expandedParentIds,
                    depth = depth + 1
                )
            )
        }
    }
    return result
}

@Composable
private fun ChatHistoryItemRow(
    conv: Conversation,
    isSelected: Boolean,
    /** A Jarvis chat: marked with Jarvis' robot face instead of the chat bubble. */
    isJarvis: Boolean = false,
    activeInst: ai.antigem.data.remote.AgyActiveInstance?,
    isActivelyRunning: Boolean,
    isScheduledOrBackground: Boolean,
    depth: Int = 0,
    displayName: String = conv.title,
    subagents: List<Conversation> = emptyList(),
    isSubagentsExpanded: Boolean = false,
    onToggleSubagents: (() -> Unit)? = null,
    onSelectConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit,
    onRequestDelete: (Conversation) -> Unit,
    onTerminateInstance: (ai.antigem.data.remote.AgyActiveInstance, String) -> Unit,
    /** Pinned to the top of the list: a small pin shows next to the time. */
    isPinned: Boolean = false,
    /** Pins / unpins from the ⋮ menu; null (sub-agent rows) hides the item. */
    onTogglePin: (() -> Unit)? = null,
    claudeActions: ClaudeDrawerActions? = null
) {
    val isSubagent = depth > 0
    val startPadding = if (isSubagent) (12 + (depth * 14)).dp else 0.dp
    val statusDotColor = when {
        isActivelyRunning -> ai.antigem.theme.AppColors.success
        isScheduledOrBackground -> ai.antigem.theme.AppColors.warning
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPadding,
                end = 0.dp,
                top = if (isSubagent) 1.dp else 2.dp,
                bottom = if (isSubagent) 1.dp else 2.dp
            )
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .clickable {
                Log.d("CHAT_OPEN_DEBUG", "📂 [Drawer Row] User clicked: id=${conv.id}, title='${conv.title}'")
                onSelectConversation(conv.id)
            }
            .padding(
                horizontal = if (isSubagent) 8.dp else 10.dp,
                vertical = if (isSubagent) 7.dp else 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSubagent) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = L.drawer.chatHistoryDrawer.subagent,
                    tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
                if (statusDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .background(statusDotColor, CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Box(contentAlignment = Alignment.Center) {
                // Claude chats are marked by an orange chat icon (instead of a "Claude" text badge)
                val isClaudeChat = conv.agent == ai.antigem.domain.model.AgentKind.CLAUDE
                val isOpenCodeChat = conv.agent == ai.antigem.domain.model.AgentKind.OPENCODE
                val isCodexChat = conv.agent == ai.antigem.domain.model.AgentKind.CODEX
                if (isJarvis) {
                    ai.antigem.ui.jarvis.JarvisBotIcon(19.dp, ai.antigem.ui.jarvis.Cyan.copy(alpha = if (isSelected) 1f else 0.85f))
                } else Icon(
                    // an Antigravity cloud chat (Remote Agents) shows a cloud
                    imageVector = if (conv.isCloud) Icons.Outlined.Cloud else Icons.Outlined.ChatBubbleOutline,
                    contentDescription = if (conv.isCloud) L.drawer.chatHistoryDrawer.cloudChat else if (isClaudeChat) L.drawer.chatHistoryDrawer.claudeCodeChat else if (isOpenCodeChat) L.drawer.chatHistoryDrawer.opencodeChat else if (isCodexChat) L.drawer.chatHistoryDrawer.codexChat else null,
                    tint = when {
                        conv.isCloud -> ai.antigem.theme.GeminiBlue.copy(alpha = if (isSelected) 1f else 0.85f)
                        isClaudeChat -> ai.antigem.ui.components.ClaudeAccent.copy(alpha = if (isSelected) 1f else 0.85f)
                        isOpenCodeChat -> ai.antigem.ui.components.OpenCodeAccent.copy(alpha = if (isSelected) 1f else 0.85f)
                        isCodexChat -> ai.antigem.ui.components.CodexAccent.copy(alpha = if (isSelected) 1f else 0.85f)
                        isSelected -> ClaudeTerracotta
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(17.dp)
                )
                if (statusDotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .background(statusDotColor, CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayName,
                fontSize = if (isSubagent) 12.8.sp else 13.5.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else if (isSubagent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSubagent && isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (subagents.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSubagentsExpanded) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.7f
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSubagentsExpanded) ClaudeTerracotta.copy(alpha = 0.4f) else Color.Transparent
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggleSubagents?.invoke() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${subagents.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSubagentsExpanded) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = if (isSubagentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isSubagentsExpanded) L.drawer.chatHistoryDrawer.collapseSubagents else L.drawer.chatHistoryDrawer.expandSubagents,
                                tint = if (isSubagentsExpanded) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                if (isPinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = L.drawer.chatHistoryDrawer.pinned,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                val timeStr = formatRelativeTime(conv.updatedAt)
                if (timeStr.isNotBlank()) {
                    Text(
                        text = timeStr,
                        fontSize = if (isSubagent) 10.5.sp else 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (activeInst != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = ai.antigem.theme.AppColors.success.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, ai.antigem.theme.AppColors.success.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onTerminateInstance(activeInst, conv.title)
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(ai.antigem.theme.AppColors.success, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = L.drawer.chatHistoryDrawer.running,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ai.antigem.theme.AppColors.success
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
        } else if (statusDotColor != null) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .background(statusDotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        var menuExpanded by remember { mutableStateOf(false) }
        var showRename by remember { mutableStateOf(false) }
        val isClaudeConv = conv.agent == ai.antigem.domain.model.AgentKind.CLAUDE
        // Claude Code and OpenCode chats: renamed, exported and shared through claudeActions (routed by agent)
        val isAgentConv = conv.agent != ai.antigem.domain.model.AgentKind.AGY
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        val scope = rememberCoroutineScope()
        val snackbarHostState = ai.antigem.ui.components.LocalSnackbarHostState.current

        if (showRename && claudeActions != null) {
            var newTitle by remember { mutableStateOf(conv.title) }
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text(L.drawer.chatHistoryDrawer.renameChat, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        claudeActions.rename(conv.id, newTitle)
                        showRename = false
                    }, enabled = newTitle.isNotBlank()) { Text(L.common.save) }
                },
                dismissButton = { TextButton(onClick = { showRename = false }) { Text(L.common.cancel) } }
            )
        }

        // a read-only chat (a Claude Code subagent) gets a shorter menu: copy id, rename, export, share and delete
        if (!conv.readOnly || isClaudeConv) Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = L.common.options,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(17.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.widthIn(min = 210.dp)
            ) {
                if (onTogglePin != null) {
                    DropdownMenuItem(
                        text = { Text(if (isPinned) L.drawer.chatHistoryDrawer.unpin else L.drawer.chatHistoryDrawer.pinToTop, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                            AppToastHelper.showToast(if (isPinned) L.drawer.chatHistoryDrawer.chatUnpinned else L.drawer.chatHistoryDrawer.chatPinned, ChatToastType.SUCCESS)
                        }
                    )
                }
                if (!conv.readOnly && conv.parentConversationId == null) {
                    // an icon on the home screen that opens this chat (Settings → Home screen has widgets too)
                    DropdownMenuItem(
                        text = { Text(L.common.addToHomeScreen, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.AppShortcut,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            ai.antigem.data.homescreen.HomeShortcuts.pinChat(context, conv.id, conv.agent, conv.title)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(L.drawer.chatHistoryDrawer.copyConversationId, fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        clipboardManager.setText(AnnotatedString(conv.id))
                        AppToastHelper.showToast(L.drawer.chatHistoryDrawer.conversationIdCopied, ChatToastType.SUCCESS)
                    }
                )
                if (!conv.readOnly) DropdownMenuItem(
                    text = { Text(L.drawer.chatHistoryDrawer.forkConversation, fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onForkConversation(conv.id)
                    }
                )
                if (claudeActions != null) {  // every agent: AGY renames go to the daemon annotations
                    DropdownMenuItem(
                        text = { Text(L.common.rename, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showRename = true
                        }
                    )
                }
                if (isClaudeConv && claudeActions != null && !conv.readOnly) {
                    DropdownMenuItem(
                        text = { Text(L.drawer.chatHistoryDrawer.restartChat, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            claudeActions.restart(conv.id) { msg ->
                                scope.launch { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = { Text(L.drawer.chatHistoryDrawer.exportMarkdownMd, fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = GeminiBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        ai.antigem.ui.components.AppToastHelper.showToast(L.drawer.chatHistoryDrawer.exportingMarkdown, ai.antigem.ui.components.ChatToastType.INFO)
                        scope.launch(Dispatchers.IO) {
                            ConversationExportHelper.exportConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                loadAgentMessages = claudeActions?.takeIf { isAgentConv }?.let { a -> suspend { a.loadMessages(conv.id) } },
                                format = ConversationExportHelper.ExportFormat.MARKDOWN,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(L.drawer.chatHistoryDrawer.exportStandaloneHtml, fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Code,
                            contentDescription = null,
                            tint = ai.antigem.theme.AppColors.success,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        ai.antigem.ui.components.AppToastHelper.showToast(L.drawer.chatHistoryDrawer.exportingHtml, ai.antigem.ui.components.ChatToastType.INFO)
                        scope.launch(Dispatchers.IO) {
                            ConversationExportHelper.exportConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                loadAgentMessages = claudeActions?.takeIf { isAgentConv }?.let { a -> suspend { a.loadMessages(conv.id) } },
                                format = ConversationExportHelper.ExportFormat.HTML,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(L.common.share, fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        ai.antigem.ui.components.AppToastHelper.showToast(L.drawer.chatHistoryDrawer.preparingAntigemShare, ai.antigem.ui.components.ChatToastType.INFO)
                        scope.launch(Dispatchers.IO) {
                            ConversationShareHelper.shareConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                loadAgentMessages = claudeActions?.takeIf { isAgentConv }?.let { a -> suspend { a.loadMessages(conv.id) } },
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                if (claudeActions != null && !conv.readOnly) {
                    DropdownMenuItem(
                        text = { Text(if (conv.archived) L.drawer.chatHistoryDrawer.unarchive else L.drawer.chatHistoryDrawer.archive, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (conv.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            claudeActions.archive(conv.id, !conv.archived)
                            AppToastHelper.showToast(if (conv.archived) L.drawer.chatHistoryDrawer.chatUnarchived else L.drawer.chatHistoryDrawer.chatArchived, ChatToastType.SUCCESS)
                        }
                    )
                }
                // a running subagent is still being written: the bridge refuses to delete it
                if (!conv.readOnly || !conv.isRunning) DropdownMenuItem(
                    text = {
                        Text(
                            L.drawer.chatHistoryDrawer.deleteConversation2,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRequestDelete(conv)
                    }
                )
            }
        }
    }
}

@Composable
fun SearchChatsDialog(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedTimeFilter by remember { mutableStateOf("All") }
    var selectedWorkspaceFilter by remember { mutableStateOf("All") }

    val workspaces = remember(conversations) {
        val list = mutableListOf("All")
        conversations.forEach { conv ->
            if (conv.workspaceUri.isNotBlank()) {
                val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                val name = java.io.File(clean).name.ifBlank { L.common.workspace }
                if (!list.contains(name)) list.add(name)
            }
        }
        list
    }

    val results = remember(conversations, query, selectedTimeFilter, selectedWorkspaceFilter) {
        val now = System.currentTimeMillis()
        conversations.filter { conv ->
            val matchesTime = when (selectedTimeFilter) {
                "Today" -> (now - conv.updatedAt) < 24 * 3600_000L
                "Past 7d" -> (now - conv.updatedAt) < 7 * 24 * 3600_000L
                "Past 30d" -> (now - conv.updatedAt) < 30L * 24 * 3600_000L
                else -> true
            }

            val matchesWorkspace = when (selectedWorkspaceFilter) {
                "All" -> true
                else -> {
                    val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                    val name = java.io.File(clean).name.ifBlank { L.common.workspace }
                    name.equals(selectedWorkspaceFilter, ignoreCase = true)
                }
            }

            val matchesQuery = if (query.isBlank()) true else {
                conv.title.contains(query, ignoreCase = true) ||
                        (conv.subagentRole?.contains(query, ignoreCase = true) == true) ||
                        (conv.summary?.contains(query, ignoreCase = true) == true) ||
                        conv.modelId.contains(query, ignoreCase = true)
            }

            matchesTime && matchesWorkspace && matchesQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = L.drawer.chatHistoryDrawer.searchConversations,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = L.drawer.chatHistoryDrawer.conversationscountTotalChatsIndexed(conversations.size),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = L.common.close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search input bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.5.dp,
                        if (query.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = 0.3f
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (query.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            ),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(ClaudeTerracotta),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = L.drawer.chatHistoryDrawer.searchByTitleTopicOrModel,
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = L.common.clear,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter chips (Time & Workspace)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time filters
                    listOf("All", "Today", "Past 7d", "Past 30d").forEach { timeLabel ->
                        val isSelected = selectedTimeFilter == timeLabel
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.35f
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.2f
                                )
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(selected = isSelected, role = Role.RadioButton) { selectedTimeFilter = timeLabel }
                        ) {
                            Text(
                                // the filter keys stay English (compared above); only the shown label is translated
                                text = when (timeLabel) {
                                    "Today" -> L.drawer.chatHistoryDrawer.today
                                    "Past 7d" -> L.drawer.chatHistoryDrawer.past7d
                                    "Past 30d" -> L.drawer.chatHistoryDrawer.past30d
                                    else -> L.drawer.chatHistoryDrawer.all
                                },
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.8f
                                ),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }

                    if (workspaces.size > 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier.size(width = 1.dp, height = 16.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        workspaces.forEach { wsName ->
                            val isSelected = selectedWorkspaceFilter == wsName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.35f
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(
                                        alpha = 0.2f
                                    )
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .selectable(selected = isSelected, role = Role.RadioButton) { selectedWorkspaceFilter = wsName }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (wsName != "All") {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = 0.6f
                                            ),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = if (wsName == "All") L.drawer.chatHistoryDrawer.all else wsName,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.8f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Results count
                Text(
                    text = "${results.size} match${if (results.size != 1) "es" else ""}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Results list
                if (results.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = L.drawer.chatHistoryDrawer.noConversationsFound,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (query.isNotBlank()) L.drawer.chatHistoryDrawer.noChatsMatchQueryTryDifferent(query) else L.drawer.chatHistoryDrawer.noChatsInThisCategory,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results, key = { it.id }) { conv ->
                            val isSelected = conv.id == currentConversationId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.35f
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) ClaudeTerracotta.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(
                                        alpha = 0.15f
                                    )
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        Log.d(
                                            "CHAT_OPEN_DEBUG",
                                            "📂 [Drawer Dialog] User clicked: id=${conv.id}, title='${conv.title}'"
                                        )
                                        onSelectConversation(conv.id)
                                        onDismiss()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = conv.title,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val rel = formatRelativeTime(conv.updatedAt)
                                        if (rel.isNotBlank()) {
                                            Text(
                                                text = rel,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                            )
                                        }
                                    }

                                    if (!conv.summary.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = conv.summary,
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (conv.workspaceUri.isNotBlank()) {
                                            val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                                            val name = java.io.File(clean).name.ifBlank { L.common.workspace }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(11.dp),
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                    )
                                                    Text(
                                                        text = name,
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }

                                        if (conv.modelId.isNotBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = ClaudeTerracotta.copy(alpha = 0.1f)
                                            ) {
                                                Text(
                                                    text = conv.modelId.substringAfterLast('/'),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ClaudeTerracotta,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (conv.stepCount > 0) {
                                            Text(
                                                text = "${conv.stepCount} turns",
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
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
    }
}

/** Claude-specific chat actions for the sidebar (Claude chats are loaded and renamed through the bridge). */
class ClaudeDrawerActions(
    val loadMessages: suspend (String) -> List<ai.antigem.domain.model.ChatMessage>,
    val rename: (String, String) -> Unit,
    /** Stops the chat's process (when idle) so its next message starts one with the current provider settings; the callback gets a message. */
    val restart: (String, (String) -> Unit) -> Unit,
    /** Archives (true) or unarchives (false) a chat of any agent. */
    val archive: (String, Boolean) -> Unit = { _, _ -> }
)


/**
 * The Jarvis tab's "New Jarvis chat" button. The chat opens like any new chat (pick the agent above the empty chat, the
 * model in the input bar); it only adds Jarvis' tools, guide and command strip.
 */
@Composable
private fun JarvisNewChatRow(
    @Suppress("UNUSED_PARAMETER") agents: List<ai.antigem.domain.model.AgentKind>,
    onNew: (ai.antigem.domain.model.AgentKind?) -> Unit
) {
    val accent = ai.antigem.ui.jarvis.Cyan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { onNew(null) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ai.antigem.ui.jarvis.JarvisBotIcon(26.dp, accent, animated = true)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(L.drawer.chatHistoryDrawer.newJarvisChat, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                L.drawer.chatHistoryDrawer.anyAgentAndModelGiveIt,
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2
            )
        }
        Icon(Icons.Filled.Add, contentDescription = L.drawer.chatHistoryDrawer.newJarvisChat, tint = accent)
    }
}
