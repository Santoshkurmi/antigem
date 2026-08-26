package com.example.gemini.ui.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.ssh.SshConnectionStatus
import com.example.gemini.data.ssh.TermuxSshManager
import com.example.gemini.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsBottomSheet(
    authPreferences: AuthPreferences,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isTerminalEnabled by authPreferences.isTerminalToolEnabled.collectAsState(initial = false)
    val isAutoExecute by authPreferences.isAutoExecuteTerminal.collectAsState(initial = true)
    val hostPref by authPreferences.termuxSshHost.collectAsState(initial = "127.0.0.1")
    val portPref by authPreferences.termuxSshPort.collectAsState(initial = 8022)
    val userPref by authPreferences.termuxSshUser.collectAsState(initial = "root")
    val passPref by authPreferences.termuxSshPass.collectAsState(initial = "root")

    var host by remember(hostPref) { mutableStateOf(hostPref) }
    var port by remember(portPref) { mutableStateOf(portPref.toString()) }
    var user by remember(userPref) { mutableStateOf(userPref) }
    var pass by remember(passPref) { mutableStateOf(passPref) }
    var isEnabled by remember(isTerminalEnabled) { mutableStateOf(isTerminalEnabled) }
    var autoExecute by remember(isAutoExecute) { mutableStateOf(isAutoExecute) }

    val connectionStatus by TermuxSshManager.connectionStatus.collectAsState()
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(scrollState)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Handyman,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "AI Agent Tools",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Enable external capabilities for Gemini & Claude",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tool Card: Termux Terminal Access
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isEnabled) ClaudeTerracotta.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEnabled) ClaudeTerracotta.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = if (isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Terminal Access (Termux SSH)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Execute shell commands directly in Termux",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = {
                                isEnabled = it
                                coroutineScope.launch {
                                    authPreferences.saveTermuxSshConfig(
                                        host = host,
                                        port = port.toIntOrNull() ?: 8022,
                                        user = user,
                                        pass = pass,
                                        enabled = isEnabled,
                                        autoExecute = autoExecute
                                    )
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClaudeTerracotta
                            )
                        )
                    }

                    // Configuration Section (Visible when enabled)
                    AnimatedVisibility(visible = isEnabled) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(14.dp))

                            // Connection Status Indicator
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                val (statusColor, statusText) = when (connectionStatus) {
                                    SshConnectionStatus.CONNECTED -> Pair(QuotaGreen, "SSH Connection Pool Active")
                                    SshConnectionStatus.CONNECTING -> Pair(ClaudeTerracotta, "Connecting to Termux...")
                                    SshConnectionStatus.ERROR -> Pair(Color.Red, "Connection Failed")
                                    SshConnectionStatus.DISCONNECTED -> Pair(Color.Gray, "Idle (Ready to Connect)")
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = statusText,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor
                                )
                            }

                            // Host & Port Row
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = {
                                        host = it
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    },
                                    label = { Text("Host") },
                                    placeholder = { Text("127.0.0.1") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.8f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = {
                                        port = it
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    },
                                    label = { Text("Port") },
                                    placeholder = { Text("8022") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.2f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Username & Password Row
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = user,
                                    onValueChange = {
                                        user = it
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    },
                                    label = { Text("User") },
                                    placeholder = { Text("root") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.5f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = pass,
                                    onValueChange = {
                                        pass = it
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    },
                                    label = { Text("Password") },
                                    placeholder = { Text("root") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.5f)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Auto execute toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        autoExecute = !autoExecute
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = autoExecute,
                                    onCheckedChange = {
                                        autoExecute = it
                                        coroutineScope.launch {
                                            authPreferences.saveTermuxSshConfig(host, port.toIntOrNull() ?: 8022, user, pass, isEnabled, autoExecute)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = "Auto-execute AI tool calls",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Run commands without requiring manual confirmation",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Test Connection Button
                            Button(
                                onClick = {
                                    isTesting = true
                                    testResult = null
                                    coroutineScope.launch {
                                        val res = TermuxSshManager.testConnection(
                                            host = host,
                                            port = port.toIntOrNull() ?: 8022,
                                            user = user,
                                            pass = pass
                                        )
                                        isTesting = false
                                        testResult = res.fold(
                                            onSuccess = { "✅ $it" },
                                            onFailure = { "❌ Connection failed: ${it.message}" }
                                        )
                                    }
                                },
                                enabled = !isTesting,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Testing SSH Handshake...")
                                } else {
                                    Icon(imageVector = Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Termux SSH Connection")
                                }
                            }

                            // Test Result output
                            testResult?.let { msg ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (msg.startsWith("✅")) QuotaGreen.copy(alpha = 0.12f) else Color.Red.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = msg,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (msg.startsWith("✅")) QuotaGreen else Color.Red,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick Setup Instructions for Termux
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "💡 How to start sshd in Termux:",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. Open Termux and install OpenSSH:\n   pkg install openssh\n2. Set password to 'root':\n   passwd\n3. Start the SSH daemon:\n   sshd",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
