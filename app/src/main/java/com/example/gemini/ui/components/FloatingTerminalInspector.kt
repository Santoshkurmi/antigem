package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.ssh.*
import com.example.gemini.theme.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Floating Dockable Terminal HUD & Multi-Tab Inspector for Termux SSH sessions.
 */
@Composable
fun FloatingTerminalInspector(
    authPreferences: AuthPreferences,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val tabs by TermuxSshManager.tabs.collectAsState()
    val activeTabId by TermuxSshManager.activeTabId.collectAsState()
    val connectionStatus by TermuxSshManager.connectionStatus.collectAsState()
    var isInspectorOpen by remember { mutableStateOf(false) }

    val isTerminalEnabled by authPreferences.isTerminalToolEnabled.collectAsState(initial = false)

    if (!isTerminalEnabled && tabs.all { it.commands.isEmpty() }) {
        return
    }

    val runningCommandsCount = tabs.flatMap { it.commands }.count { it.status == CommandStatus.RUNNING }
    val isAnyTabBusy = tabs.any { it.status == SessionTabStatus.BUSY }

    // Floating HUD Pill
    Box(modifier = modifier) {
        Surface(
            onClick = { isInspectorOpen = true },
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E1E2E).copy(alpha = 0.94f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (runningCommandsCount > 0) ClaudeTerracotta.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.15f)
            ),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Indicator
                if (runningCommandsCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionStatus) {
                                    SshConnectionStatus.CONNECTED -> QuotaGreen
                                    SshConnectionStatus.CONNECTING -> ClaudeTerracotta
                                    SshConnectionStatus.ERROR -> Color.Red
                                    else -> Color.Gray
                                }
                            )
                    )
                }

                Spacer(modifier = Modifier.width(7.dp))

                val hudLabel = when {
                    runningCommandsCount > 0 -> "$runningCommandsCount running"
                    tabs.size > 1 -> "${tabs.size} sessions"
                    else -> "Termux"
                }

                Text(
                    text = hudLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.width(5.dp))

                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Terminal",
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    // Fullscreen / Modal Terminal Inspector with Multi-Session Tabs
    if (isInspectorOpen) {
        TerminalInspectorDialog(
            authPreferences = authPreferences,
            onDismiss = { isInspectorOpen = false }
        )
    }
}

/**
 * Public Dialog for Terminal Inspector accessible directly from top app bar.
 */
@Composable
fun TerminalInspectorDialog(
    authPreferences: AuthPreferences,
    onDismiss: () -> Unit
) {
    val tabs by TermuxSshManager.tabs.collectAsState()
    val activeTabId by TermuxSshManager.activeTabId.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TerminalInspectorDialogContent(
            tabs = tabs,
            activeTabId = activeTabId,
            authPreferences = authPreferences,
            onSelectTab = { TermuxSshManager.selectTab(it) },
            onCreateTab = { TermuxSshManager.createTab() },
            onCloseTab = { TermuxSshManager.closeTab(it) },
            onClearTab = { TermuxSshManager.clearTabCommands(it) },
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalInspectorDialogContent(
    tabs: List<TerminalSessionTab>,
    activeTabId: String,
    authPreferences: AuthPreferences,
    onSelectTab: (String) -> Unit,
    onCreateTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onClearTab: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var commandInput by remember { mutableStateOf("") }
    var isExecutingManual by remember { mutableStateOf(false) }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val listState = rememberLazyListState()

    // Auto scroll down when new output appears in active tab
    LaunchedEffect(activeTab?.commands?.size, activeTab?.commands?.lastOrNull()?.output) {
        if ((activeTab?.commands?.size ?: 0) > 0) {
            listState.animateScrollToItem(activeTab!!.commands.size - 1)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF0F1117),
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161822))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Termux SSH Terminal",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Tabs Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF13151F))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab.id == activeTabId
                    val isBusy = tab.status == SessionTabStatus.BUSY
                    val isKilled = tab.status == SessionTabStatus.KILLED

                    Surface(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectTab(tab.id) },
                        color = if (isSelected) Color(0xFF222536) else Color(0xFF181A26),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) ClaudeTerracotta.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status Dot
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isBusy -> ClaudeTerracotta
                                            isKilled -> Color.Red
                                            else -> QuotaGreen
                                        }
                                    )
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = tab.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color.Gray
                            )

                            if (isSelected && tabs.size > 1 && !tab.isPrimary) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable { onCloseTab(tab.id) }
                                )
                            }
                        }
                    }
                }

                // New Tab '+' Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCreateTab() },
                    color = Color(0xFF1C1E2C),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Tab Working Directory & Controls Header
            if (activeTab != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161822))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = activeTab.workingDirectory,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.LightGray,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    // Clear commands in active tab
                    if (activeTab.commands.isNotEmpty()) {
                        IconButton(
                            onClick = { onClearTab(activeTab.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear Tab History",
                                tint = Color.Gray,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // Command History Feed inside active tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF090A0F))
                    .padding(8.dp)
            ) {
                if (activeTab == null || activeTab.commands.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Session ready.",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = "AI tool commands and manual commands will output here.",
                            fontSize = 11.5.sp,
                            color = Color.DarkGray
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(activeTab.commands, key = { it.id }) { cmd ->
                            TerminalCommandCard(
                                command = cmd,
                                onKill = { TermuxSshManager.killCommand(cmd.id) }
                            )
                        }
                    }
                }
            }

            // Interactive Manual Command Execution Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF13151F),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$",
                        color = ClaudeTerracotta,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                    )

                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        placeholder = { Text("Run bash command in Termux...", fontSize = 12.5.sp, color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = ClaudeTerracotta,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = Color(0xFF0C0D13),
                            unfocusedContainerColor = Color(0xFF0C0D13)
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val cmd = commandInput.trim()
                            if (cmd.isNotEmpty() && !isExecutingManual) {
                                isExecutingManual = true
                                coroutineScope.launch {
                                    val host = authPreferences.termuxSshHost.firstOrNull() ?: "127.0.0.1"
                                    val port = authPreferences.termuxSshPort.firstOrNull() ?: 8022
                                    val user = authPreferences.termuxSshUser.firstOrNull() ?: "root"
                                    val pass = authPreferences.termuxSshPass.firstOrNull() ?: "root"

                                    commandInput = ""
                                    TermuxSshManager.executeCommand(
                                        command = cmd,
                                        host = host,
                                        port = port,
                                        user = user,
                                        pass = pass,
                                        targetTabId = activeTab?.id
                                    )
                                    isExecutingManual = false
                                }
                            }
                        },
                        enabled = commandInput.isNotBlank() && !isExecutingManual,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (commandInput.isNotBlank()) ClaudeTerracotta else Color.DarkGray)
                    ) {
                        if (isExecutingManual) {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = "Running",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalCommandCard(
    command: TerminalCommand,
    onKill: () -> Unit
) {
    val context = LocalContext.current
    val isRunning = command.status == CommandStatus.RUNNING
    val isSuccess = command.status == CommandStatus.SUCCESS || (command.status != CommandStatus.FAILED && command.exitCode == 0)
    val isFailed = command.status == CommandStatus.FAILED || (command.exitCode != null && command.exitCode != 0)
    val isTerminated = command.status == CommandStatus.TERMINATED

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF13151F),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                isRunning -> ClaudeTerracotta.copy(alpha = 0.5f)
                isSuccess -> QuotaGreen.copy(alpha = 0.3f)
                isFailed || isTerminated -> Color.Red.copy(alpha = 0.35f)
                else -> Color.White.copy(alpha = 0.08f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Command Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$ ${command.command}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = ClaudeTerracotta,
                    modifier = Modifier.weight(1f)
                )

                if (command.durationMs > 0) {
                    Text(
                        text = "${command.durationMs}ms",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                if (command.exitCode != null && !isRunning) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSuccess) QuotaGreen.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "exit ${command.exitCode}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) QuotaGreen else Color.Red
                        )
                    }
                }

                if (isRunning) {
                    IconButton(
                        onClick = onKill,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.StopCircle,
                            contentDescription = "Kill Process",
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Output", command.output)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Gray,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Output Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF08090D))
                    .padding(6.dp)
            ) {
                if (command.output.isEmpty()) {
                    Text(
                        text = if (isRunning) "Running in Termux..." else "(No output)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                } else {
                    Text(
                        text = command.output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color(0xFFD4D4D8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
