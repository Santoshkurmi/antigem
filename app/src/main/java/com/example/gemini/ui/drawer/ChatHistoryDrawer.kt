package com.example.gemini.ui.drawer

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.Conversation
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.ui.components.SidebarChatListSkeleton
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.AgyHubClient
import kotlinx.coroutines.launch

@Composable
fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    activeInstances: List<com.example.gemini.data.remote.AgyActiveInstance> = emptyList(),
    isLoading: Boolean = false,
    errorMessage: String? = null,
    isStreaming: Boolean = false,
    authInfo: AgyHubClient.AgyAuthInfo = AgyHubClient.AgyAuthInfo(),
    isAuthBusy: Boolean = false,
    groupByWorkspace: Boolean = false,
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
    isOpen: Boolean = false,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var instanceToTerminate by remember { mutableStateOf<Pair<com.example.gemini.data.remote.AgyActiveInstance, String>?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var prevFirstConvId by remember { mutableStateOf<String?>(conversations.firstOrNull()?.id) }

    // Only scroll to top when a brand new conversation is created
    LaunchedEffect(conversations.firstOrNull()?.id) {
        val currentFirst = conversations.firstOrNull()?.id
        if (currentFirst != null && prevFirstConvId != null && currentFirst != prevFirstConvId) {
            listState.scrollToItem(0)
        }
        prevFirstConvId = currentFirst
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

    ModalDrawerSheet(
        modifier = modifier.fillMaxWidth(0.82f),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // New Chat Button
            Button(
                onClick = {
                    scope.launch { listState.scrollToItem(0) }
                    onNewChat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Chat",
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New Chat",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchQueryChange(it)
                },
                placeholder = { Text("Search chats...", fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            val showSkeleton = (isLoading || conversations.isEmpty()) && conversations.isEmpty() && errorMessage.isNullOrBlank()

            Crossfade(
                targetState = when {
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
                        val grouped = remember(filtered) {
                            filtered.groupBy { conv ->
                                if (conv.workspaceUri.isNotBlank()) {
                                    val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                                    java.io.File(clean).name.ifBlank { "Workspace" }
                                } else {
                                    "General"
                                }
                            }
                        }
                        var expandedGroups by remember { mutableStateOf(setOf<String>()) }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (!groupByWorkspace) {
                                items(filtered, key = { it.id }) { conv ->
                                    val isSelected = conv.id == currentConversationId
                                    val activeInst = activeInstances.find { it.conversationId == conv.id }
                                    val isConvRunning = conv.isRunning || (conv.id == currentConversationId && isStreaming) || activeInst != null

                                    ChatHistoryItemRow(
                                        conv = conv,
                                        isSelected = isSelected,
                                        activeInst = activeInst,
                                        isConvRunning = isConvRunning,
                                        onSelectConversation = onSelectConversation,
                                        onForkConversation = onForkConversation,
                                        onDeleteConversation = onDeleteConversation,
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
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    items(displayChats, key = { it.id }) { conv ->
                                        val isSelected = conv.id == currentConversationId
                                        val activeInst = activeInstances.find { it.conversationId == conv.id }
                                        val isConvRunning = conv.isRunning || (conv.id == currentConversationId && isStreaming) || activeInst != null

                                        ChatHistoryItemRow(
                                            conv = conv,
                                            isSelected = isSelected,
                                            activeInst = activeInst,
                                            isConvRunning = isConvRunning,
                                            onSelectConversation = onSelectConversation,
                                            onForkConversation = onForkConversation,
                                            onDeleteConversation = onDeleteConversation,
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
                                                        expandedGroups = if (isExpanded) expandedGroups - groupName else expandedGroups + groupName
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (isExpanded) "Show less" else "Show all",
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
                } else if (authInfo.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.CHECKING) {
                    // Non-animated checking / connecting indicator
                    Surface(
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
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Checking...",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else if (authInfo.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.OFFLINE) {
                    // Offline indicator (clickable to retry checking)
                    Surface(
                        onClick = onCheckAuth,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFFE57373), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Offline",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else if (authInfo.isLoggedIn || authInfo.status == com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus.AUTHENTICATED) {
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
                                    .data(authInfo.profilePictureUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Profile Picture",
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(ClaudeTerracotta),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val initial = authInfo.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
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
                                        val initial = authInfo.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
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
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 90.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
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
            }

            if (showProfileDialog) {
                ProfileDetailDialog(
                    authInfo = authInfo,
                    onDismiss = { showProfileDialog = false },
                    onLogout = onLogout
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

@Composable
private fun ChatHistoryItemRow(
    conv: Conversation,
    isSelected: Boolean,
    activeInst: com.example.gemini.data.remote.AgyActiveInstance?,
    isConvRunning: Boolean,
    onSelectConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onTerminateInstance: (com.example.gemini.data.remote.AgyActiveInstance, String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .clickable { onSelectConversation(conv.id) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(17.dp)
            )
            if (isConvRunning) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conv.title,
                fontSize = 13.5.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )

            val timeStr = formatRelativeTime(conv.updatedAt)
            if (timeStr.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
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
        } else if (isConvRunning) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .background(Color(0xFF4CAF50), CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        var menuExpanded by remember { mutableStateOf(false) }

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
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Fork Conversation", fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onForkConversation(conv.id)
                    }
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
                        onDeleteConversation(conv.id)
                    }
                )
            }
        }
    }
}
