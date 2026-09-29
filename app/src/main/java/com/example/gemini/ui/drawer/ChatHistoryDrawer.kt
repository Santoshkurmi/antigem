package com.example.gemini.ui.drawer

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Public
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import android.widget.Toast
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
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
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.model.Conversation
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import androidx.compose.material.icons.outlined.Share
import com.example.gemini.ui.components.ConversationExportHelper
import com.example.gemini.ui.components.ConversationShareHelper
import com.example.gemini.ui.components.DrawerEngineWarmingUpView
import com.example.gemini.ui.components.SidebarChatListSkeleton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    activeInstances: List<com.example.gemini.data.remote.AgyActiveInstance> = emptyList(),
    isLoading: Boolean = false,
    hasReceivedInitialSync: Boolean = false,
    systemConnectionState: com.example.gemini.data.remote.SystemConnectionState = com.example.gemini.data.remote.SystemConnectionState.Offline,
    errorMessage: String? = null,
    isStreaming: Boolean = false,
    authInfo: AgyHubClient.AgyAuthInfo = AgyHubClient.AgyAuthInfo(),
    isAuthBusy: Boolean = false,
    groupByWorkspace: Boolean = false,
    onToggleGroupByWorkspace: (Boolean) -> Unit = {},
    onRetry: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit = {},
    onTerminateInstance: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckAuth: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    isOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showFullSearchDialog by remember { mutableStateOf(false) }
    var instanceToTerminate by remember {
        mutableStateOf<Pair<com.example.gemini.data.remote.AgyActiveInstance, String>?>(
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

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter {
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
    val topLevelChats = remember(filtered, allFilteredIds) {
        filtered.filter { conv ->
            val pId = conv.parentConversationId
            pId == null || pId == conv.id || !allFilteredIds.contains(pId)
        }
    }

    var expandedParentIds by remember { mutableStateOf(setOf<String>()) }

    // Auto expand all ancestor parents if current conversation is a nested subagent or parent with subagents
    LaunchedEffect(currentConversationId) {
        if (!currentConversationId.isNullOrBlank()) {
            val toExpand = mutableSetOf<String>()
            val curr = conversations.find { it.id == currentConversationId }
            if (curr != null) {
                // If current has subagents, expand it
                if (conversations.any { it.parentConversationId == curr.id }) {
                    toExpand.add(curr.id)
                }
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

    val grouped = remember(topLevelChats) {
        topLevelChats.groupBy { conv ->
            if (conv.workspaceUri.isNotBlank()) {
                val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                java.io.File(clean).name.ifBlank { "Workspace" }
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
            title = { Text("Active CLI Process", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "A live Antigravity CLI process is running in the background for this chat:",
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
                            Text("Chat: $title", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Model: ${inst.model}", fontSize = 12.sp)
                            Text("Uptime: ${inst.uptimeSeconds}s", fontSize = 12.sp)
                            if (inst.pid > 0) {
                                Text("PID: ${inst.pid}", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Terminate this instance to immediately free RAM?",
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
                    Text("Terminate & Free RAM", fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToTerminate = null }) {
                    Text("Keep Running", fontSize = 13.sp)
                }
            }
        )
    }

    if (showSigningInProgressDialog) {
        AlertDialog(
            onDismissRequest = { showSigningInProgressDialog = false },
            title = {
                Text(
                    text = "Sign-In in Progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "A sign-in request is currently pending. If you already completed authentication in your browser, tap 'Check Status'. You can also restart sign-in if needed.",
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
                        Text("Check Status")
                    }
                    Button(
                        onClick = {
                            showSigningInProgressDialog = false
                            onLogin()
                        }
                    ) {
                        Text("Sign In Again")
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
                        Text("Later")
                    }
                    TextButton(onClick = { showSigningInProgressDialog = false }) {
                        Text("Cancel")
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
                    text = "Exit AntiGem?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Do you want to exit the app? This will stop all active background services, servers, and terminals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        try {
                            com.example.gemini.data.service.TermuxService.stop(context)
                        } catch (e: Exception) {
                            Log.e("ChatHistoryDrawer", "Error stopping service", e)
                            try {
                                com.example.gemini.data.local.LocalServerManager.forceKillAll()
                            } catch (_: Exception) {
                            }
                            try {
                                com.example.gemini.data.local.LocalTerminalManager.closeAll()
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
                    Text("Exit App", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExitConfirmDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", fontSize = 13.5.sp)
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
                    text = "Delete Conversation?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${targetConv.title.ifBlank { "this conversation" }}\"? This will permanently remove all messages and trajectory data.",
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
                    Text("Delete", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { conversationToDelete = null },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", fontSize = 13.5.sp)
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
                .padding(16.dp)
        ) {
            // Top Action Row: New Chat + Workspace Folders Toggle + Search Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // New Chat Button
                Button(
                    onClick = {
                        searchQuery = ""
                        isSearchActive = false
                        scope.launch {
                            try {
                                listState.scrollToItem(0)
                            } catch (_: Exception) {
                            }
                        }
                        onNewChat()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClaudeTerracotta,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "New Chat",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        fontSize = 13.5.sp
                    )
                }

                // Power / Exit App Button
                Surface(
                    onClick = { showExitConfirmDialog = true },
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Exit App",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Workspace Folder Grouping Toggle Button
                Surface(
                    onClick = { onToggleGroupByWorkspace(!groupByWorkspace) },
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (groupByWorkspace) ClaudeTerracotta.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.5f
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (groupByWorkspace) ClaudeTerracotta.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = if (groupByWorkspace) "Disable Workspace Grouping" else "Group by Workspace",
                            tint = if (groupByWorkspace) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.8f
                            ),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Search Toggle Button
                Surface(
                    onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) {
                            searchQuery = ""
                            onSearchQueryChange("")
                        }
                    },
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSearchActive || searchQuery.isNotBlank()) ClaudeTerracotta.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.5f
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSearchActive || searchQuery.isNotBlank()) ClaudeTerracotta.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isSearchActive || searchQuery.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.8f
                            ),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Inline Search Bar (Smooth, no text clipping, auto-expandable)
            AnimatedVisibility(
                visible = isSearchActive || searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (searchQuery.isNotBlank()) ClaudeTerracotta.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = if (searchQuery.isNotBlank()) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.7f
                                ),
                                modifier = Modifier.size(16.dp)
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
                                    fontSize = 13.5.sp,
                                    lineHeight = 18.sp
                                ),
                                cursorBrush = SolidColor(ClaudeTerracotta),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 1.dp),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search chat titles...",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        onSearchQueryChange("")
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            // Deep search modal button
                            IconButton(
                                onClick = { showFullSearchDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Advanced Search",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (searchQuery.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${filtered.size} chat${if (filtered.size != 1) "s" else ""} found",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "Advanced Search ›",
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
                }
            }

            val isEngineConnecting = (systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.STARTING ||
                    systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.ACQUIRING_CSRF ||
                    (systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.OFFLINE && isInitialGracePeriod)) && conversations.isEmpty()
            val showSkeleton =
                (!hasReceivedInitialSync || isLoading) && conversations.isEmpty() && errorMessage.isNullOrBlank()

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
                                text = "Unable to load chats",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage ?: "Connection error",
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
                                Text("Retry", fontSize = 12.5.sp)
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
                                text = if (searchQuery.isNotBlank()) "No chats found matching \"$searchQuery\"" else "No recent chats",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (!groupByWorkspace) {
                                val visibleTree =
                                    buildVisibleChatTree(topLevelChats, subagentsByParent, expandedParentIds)
                                items(visibleTree, key = { it.conv.id }) { item ->
                                    val conv = item.conv
                                    val isSelected = conv.id == currentConversationId
                                    val activeInst = activeInstances.find { it.conversationId == conv.id }
                                    val isActivelyRunning = conv.isRunning && systemConnectionState.isHubOnline
                                    val isScheduledOrBackground =
                                        systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                    ChatHistoryItemRow(
                                        conv = conv,
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
                                            if (item.subagents.isNotEmpty()) {
                                                expandedParentIds = expandedParentIds + conv.id
                                            }
                                            Log.d(
                                                "CHAT_OPEN_DEBUG",
                                                "📂 [Drawer] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                            )
                                            onSelectConversation(conv.id)
                                        },
                                        onForkConversation = onForkConversation,
                                        onRequestDelete = { conversationToDelete = it },
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
                                                imageVector = if (groupName == "General") Icons.Outlined.Public else Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(7.dp))
                                            Text(
                                                text = groupName,
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
                                        val isActivelyRunning = conv.isRunning && systemConnectionState.isHubOnline
                                        val isScheduledOrBackground =
                                            systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                        ChatHistoryItemRow(
                                            conv = conv,
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
                                                if (item.subagents.isNotEmpty()) {
                                                    expandedParentIds = expandedParentIds + conv.id
                                                }
                                                Log.d(
                                                    "CHAT_OPEN_DEBUG",
                                                    "📂 [Drawer Compact] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                                )
                                                onSelectConversation(conv.id)
                                            },
                                            onForkConversation = onForkConversation,
                                            onRequestDelete = { conversationToDelete = it },
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
                                                    text = if (isExpanded) "Show less" else "Show ${chats.size - 5} more",
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
                // Settings on left
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Profile / Login on right
                if (isAuthBusy) {
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
                                text = "Signing In...",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = ClaudeTerracotta
                            )
                        }
                    }
                } else {
                    when (systemConnectionState.status) {
                        com.example.gemini.data.remote.SystemStatus.READY -> {
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
                                        contentDescription = "Profile Picture",
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

                        com.example.gemini.data.remote.SystemStatus.UNAUTHENTICATED -> {
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
                                    contentDescription = "Sign In",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sign In",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        else -> {
                            // All intermediate or offline states (OFFLINE, STARTING, ACQUIRING_CSRF, CHECKING_AUTH, ERROR)
                            val statusObj = systemConnectionState.status
                            Surface(
                                onClick = if (statusObj == com.example.gemini.data.remote.SystemStatus.OFFLINE || statusObj == com.example.gemini.data.remote.SystemStatus.ERROR) onRetry else onCheckAuth,
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
    activeInst: com.example.gemini.data.remote.AgyActiveInstance?,
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
    onTerminateInstance: (com.example.gemini.data.remote.AgyActiveInstance, String) -> Unit
) {
    val isSubagent = depth > 0
    val startPadding = if (isSubagent) (12 + (depth * 14)).dp else 0.dp
    val statusDotColor = when {
        isActivelyRunning -> Color(0xFF4CAF50)
        isScheduledOrBackground -> Color(0xFFFFB300)
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
                    contentDescription = "Subagent",
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
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                                contentDescription = if (isSubagentsExpanded) "Collapse subagents" else "Expand subagents",
                                tint = if (isSubagentsExpanded) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
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
                color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
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
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "RUNNING",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
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
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        val scope = rememberCoroutineScope()
        val snackbarHostState = com.example.gemini.ui.components.LocalSnackbarHostState.current

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
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
                DropdownMenuItem(
                    text = { Text("Copy Conversation ID", fontSize = 13.5.sp) },
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
                        AppToastHelper.showToast("Conversation ID copied", ChatToastType.SUCCESS)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Fork Conversation", fontSize = 13.5.sp) },
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
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = { Text("Export Markdown (.md)", fontSize = 13.5.sp) },
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
                        scope.launch {
                            ConversationExportHelper.exportConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                format = ConversationExportHelper.ExportFormat.MARKDOWN,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export Standalone HTML", fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Code,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            ConversationExportHelper.exportConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                format = ConversationExportHelper.ExportFormat.HTML,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share (.antigem)", fontSize = 13.5.sp) },
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
                        scope.launch {
                            ConversationShareHelper.shareConversation(
                                context = context,
                                conversationId = conv.id,
                                title = conv.title,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "Delete Conversation",
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
                val name = java.io.File(clean).name.ifBlank { "Workspace" }
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
                    val name = java.io.File(clean).name.ifBlank { "Workspace" }
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
                                text = "Search Conversations",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${conversations.size} total chats indexed",
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
                            contentDescription = "Close",
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
                                        text = "Search by title, topic, or model...",
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
                                    contentDescription = "Clear",
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
                                .clickable { selectedTimeFilter = timeLabel }
                        ) {
                            Text(
                                text = timeLabel,
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
                                    .clickable { selectedWorkspaceFilter = wsName }
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
                                        text = wsName,
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
                                text = "No conversations found",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (query.isNotBlank()) "No chats match \"$query\". Try different keywords or reset filters." else "No chats in this category.",
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
                                            val name = java.io.File(clean).name.ifBlank { "Workspace" }
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
