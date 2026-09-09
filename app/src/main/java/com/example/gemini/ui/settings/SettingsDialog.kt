package com.example.gemini.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.gemini.data.automation.AndroidAutomationService
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel> = AiModel.DEFAULT_MODELS,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    isServerListening: Boolean = false,
    isServerLoading: Boolean = false,
    contextWindowLimit: Int = 10,
    summaryModelId: String = "always_ask",
    isDevModeEnabled: Boolean = false,
    chatFontScale: Float = 1.0f,
    useSshTerminal: Boolean = false,
    sshHost: String = "127.0.0.1",
    sshPort: Int = 8022,
    sshUser: String = "root",
    sshPass: String = "root",
    isLocalToolsEnabled: Boolean = false,
    isLocalToolsInstalled: Boolean = false,
    onLoginWithGoogle: () -> Unit,
    onToggleServer: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onManualTokenEntered: (String) -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit = {},
    onSetSummaryModelId: (String) -> Unit = {},
    onSetChatFontScale: (Float) -> Unit = {},
    onToggleDevMode: (Boolean) -> Unit = {},
    onToggleUseSshTerminal: (Boolean) -> Unit = {},
    onSaveSshSettings: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
    onToggleLocalTools: (Boolean) -> Unit = {},
    onInstallLocalTools: () -> Unit = {},
    onOpenLocalTerminal: () -> Unit = {},
    onResetLocalTools: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isAutomationActive by AndroidAutomationService.isServiceActive.collectAsState()

    var isStorageGranted by remember {
        mutableStateOf(isStoragePermissionGranted(context))
    }

    // Refresh permission state on resume/interaction
    DisposableEffect(Unit) {
        isStorageGranted = isStoragePermissionGranted(context)
        onDispose { }
    }

    val coroutineScope = rememberCoroutineScope()
    var hostState by remember(sshHost) { mutableStateOf(sshHost) }
    var portState by remember(sshPort) { mutableStateOf(sshPort.toString()) }
    var userState by remember(sshUser) { mutableStateOf(sshUser) }
    var passState by remember(sshPass) { mutableStateOf(sshPass) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingSsh by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val isDark = isSystemInDarkTheme()
        val cardBg = if (isDark) ClaudeDarkSurface else Color.White
        val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Settings & Preferences",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 1. LOCAL ENVIRONMENT & TERMINAL TOOLS
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // SSH Terminal Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Terminal,
                                    contentDescription = null,
                                    tint = if (useSshTerminal) QuotaGreen else ClaudeTerracotta,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Use Terminal via SSH",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (useSshTerminal) QuotaGreen.copy(alpha = 0.15f)
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = if (useSshTerminal) "SSH Mode" else "Local Mode",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (useSshTerminal) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (useSshTerminal) "Connect to Termux sshd without local installation" else "Run shell using local app environment",
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = useSshTerminal,
                                    onCheckedChange = { checked ->
                                        onToggleUseSshTerminal(checked)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = QuotaGreen
                                    )
                                )
                            }

                            if (useSshTerminal) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = hostState,
                                        onValueChange = {
                                            hostState = it
                                            onSaveSshSettings(it, portState.toIntOrNull() ?: 8022, userState, passState)
                                        },
                                        label = { Text("Host", fontSize = 11.sp) },
                                        modifier = Modifier.weight(2f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    OutlinedTextField(
                                        value = portState,
                                        onValueChange = {
                                            portState = it
                                            onSaveSshSettings(hostState, it.toIntOrNull() ?: 8022, userState, passState)
                                        },
                                        label = { Text("Port", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = userState,
                                        onValueChange = {
                                            userState = it
                                            onSaveSshSettings(hostState, portState.toIntOrNull() ?: 8022, it, passState)
                                        },
                                        label = { Text("Username", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    OutlinedTextField(
                                        value = passState,
                                        onValueChange = {
                                            passState = it
                                            onSaveSshSettings(hostState, portState.toIntOrNull() ?: 8022, userState, it)
                                        },
                                        label = { Text("Password", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }

                                if (sshTestStatus != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = sshTestStatus ?: "",
                                        fontSize = 11.5.sp,
                                        color = if (sshTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onOpenLocalTerminal,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open SSH Terminal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            isTestingSsh = true
                                            sshTestStatus = "Connecting to SSH server..."
                                            coroutineScope.launch {
                                                val res = com.example.gemini.data.ssh.TermuxSshManager.testConnection(
                                                    host = hostState.trim(),
                                                    port = portState.toIntOrNull() ?: 8022,
                                                    user = userState.trim(),
                                                    pass = passState
                                                )
                                                isTestingSsh = false
                                                if (res.isSuccess) {
                                                    sshTestStatus = "✓ SSH connection successful!"
                                                } else {
                                                    sshTestStatus = "✗ Failed: ${res.exceptionOrNull()?.localizedMessage ?: "Connection refused"}"
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = !isTestingSsh,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        if (isTestingSsh) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test Connection", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                // Existing Local Tools UI
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Local Linux Environment",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(
                                                        if (isLocalToolsInstalled) QuotaGreen.copy(alpha = 0.15f)
                                                        else ClaudeTerracotta.copy(alpha = 0.15f)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = if (isLocalToolsInstalled) "Installed" else "Not Installed",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isLocalToolsInstalled) QuotaGreen else ClaudeTerracotta
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Embedded bootstrap filesystem for local shell execution",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Switch(
                                        checked = isLocalToolsEnabled,
                                        onCheckedChange = { checked ->
                                            if (checked && !isLocalToolsInstalled) {
                                                onInstallLocalTools()
                                            } else {
                                                onToggleLocalTools(checked)
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = if (isLocalToolsInstalled) QuotaGreen else ClaudeTerracotta
                                        )
                                    )
                                }

                                if (isLocalToolsInstalled) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = onOpenLocalTerminal,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Open Terminal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }

                                        OutlinedButton(
                                            onClick = onInstallLocalTools,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Reinstall", fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    OutlinedButton(
                                        onClick = onResetLocalTools,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Red)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Clear & Reset Environment", fontSize = 12.sp, color = Color.Red, fontWeight = FontWeight.Medium)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = onInstallLocalTools,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(imageVector = Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(15.dp), tint = ClaudeTerracotta)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Download & Setup Tools Locally (>30 MB)", fontSize = 12.sp, color = ClaudeTerracotta, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. PERMISSIONS SECTION
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "App Permissions",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "System permissions for terminal file access and UI automation",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Storage / SDCard Permission
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        requestStoragePermission(context)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = if (isStorageGranted) QuotaGreen else ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "All Files & Storage (/sdcard)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Read & write files in /sdcard, Downloads, and internal storage",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isStorageGranted) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isStorageGranted) "Granted ✓" else "Grant",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isStorageGranted) QuotaGreen else ClaudeTerracotta,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Accessibility & Automation Permission
                            val isAccessibilityEnabled = isAutomationActive || AndroidAutomationService.isRunning()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.TouchApp,
                                    contentDescription = null,
                                    tint = if (isAccessibilityEnabled) QuotaGreen else ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Automation Service",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Allows AI to control apps, tap screens, and run workflows",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isAccessibilityEnabled) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isAccessibilityEnabled) "Enabled ✓" else "Enable",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAccessibilityEnabled) QuotaGreen else ClaudeTerracotta,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. CHAT FONT SIZE SECTION
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Chat Font Size (${(chatFontScale * 100).toInt()}%)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Scale all chat text, code blocks, and markdown",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        if (chatFontScale != 1.0f) {
                            TextButton(
                                onClick = { onSetChatFontScale(1.0f) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reset (1.0x)", fontSize = 12.sp, color = ClaudeTerracotta)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Slider(
                                    value = chatFontScale,
                                    onValueChange = { onSetChatFontScale(it) },
                                    valueRange = 0.75f..1.60f,
                                    steps = 16,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = ClaudeTerracotta,
                                        activeTrackColor = ClaudeTerracotta
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Preset Buttons
                            val scalePresets = listOf(0.85f to "Small", 1.00f to "Normal", 1.15f to "Medium", 1.30f to "Large", 1.50f to "Huge")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                scalePresets.forEach { (presetVal, label) ->
                                    val isSelected = kotlin.math.abs(chatFontScale - presetVal) < 0.04f
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSetChatFontScale(presetVal) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun isStoragePermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requestStoragePermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    } else {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
