package com.example.gemini.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.local.BootstrapSource
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalInstallerState
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.*
import kotlinx.coroutines.launch

@Composable
fun LocalToolsInstallDialog(
    authPreferences: AuthPreferences,
    onOpenTerminal: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installerState by LocalEnvironmentManager.installerState.collectAsState()

    var selectedSourceType by remember { mutableStateOf(0) } // 0: Auto GitHub, 1: Direct URL, 2: Local ZIP File
    var directUrlText by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
        }
    }

    Dialog(
        onDismissRequest = {
            if (installerState !is LocalInstallerState.Downloading && installerState !is LocalInstallerState.Extracting) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = installerState !is LocalInstallerState.Downloading && installerState !is LocalInstallerState.Extracting,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header with Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            when (installerState) {
                                is LocalInstallerState.Success -> QuotaGreen.copy(alpha = 0.15f)
                                is LocalInstallerState.Error -> Color.Red.copy(alpha = 0.15f)
                                else -> ClaudeTerracotta.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (installerState) {
                            is LocalInstallerState.Success -> Icons.Default.CheckCircle
                            is LocalInstallerState.Error -> Icons.Default.ErrorOutline
                            else -> Icons.Outlined.Terminal
                        },
                        contentDescription = null,
                        tint = when (installerState) {
                            is LocalInstallerState.Success -> QuotaGreen
                            is LocalInstallerState.Error -> Color.Red
                            else -> ClaudeTerracotta
                        },
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = when (installerState) {
                        is LocalInstallerState.Success -> "Local Tools Ready!"
                        is LocalInstallerState.Error -> "Installation Error"
                        is LocalInstallerState.Idle -> "Install Local Linux Tools"
                        else -> "Setting Up Local Tools"
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = when (installerState) {
                        is LocalInstallerState.Success -> "Termux-compatible shell, coreutils & toolchain are now active."
                        is LocalInstallerState.Error -> "Failed to configure local environment. You can retry below."
                        is LocalInstallerState.Idle -> "Select how you would like to download and install the Termux rootfs:"
                        else -> "Downloading and unpacking native Linux toolchain inside your app sandbox."
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                // Dynamic Step / Progress Content
                when (val state = installerState) {
                    is LocalInstallerState.Idle -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!LocalEnvironmentManager.isTermuxPackage(context)) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Running as '${context.packageName}'",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "Native Termux bootstrap binaries require package name 'com.termux' (install via './gradlew installTermuxDebug'). For this standard build, please use SSH Terminal Mode to connect to your Termux app or server.",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Option 1: Auto Download from GitHub (Recommended)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedSourceType == 0) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSourceType = 0 }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedSourceType == 0,
                                        onClick = { selectedSourceType = 0 },
                                        colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Auto Download (GitHub)",
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(QuotaGreen.copy(alpha = 0.15f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text("Recommended", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = QuotaGreen)
                                            }
                                        }
                                        Text(
                                            text = "Auto-finds latest verified Termux bootstrap (${LocalEnvironmentManager.getBootstrapArch()}, ~33MB) with confirmation prompt",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Option 2: Direct URL
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedSourceType == 1) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSourceType = 1 }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = selectedSourceType == 1,
                                            onClick = { selectedSourceType = 1 },
                                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Direct Download URL",
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Download bootstrap from a direct .zip link",
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (selectedSourceType == 1) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = directUrlText,
                                            onValueChange = { directUrlText = it },
                                            label = { Text("Direct URL (.zip)", fontSize = 12.sp) },
                                            placeholder = { Text("https://.../bootstrap-aarch64.zip", fontSize = 11.sp) },
                                            singleLine = true,
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // Option 3: Local ZIP File from Storage
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedSourceType == 2) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSourceType = 2 }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = selectedSourceType == 2,
                                            onClick = { selectedSourceType = 2 },
                                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Local ZIP Archive",
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Pick an existing bootstrap .zip file from device (Offline)",
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (selectedSourceType == 2) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { zipPickerLauncher.launch("*/*") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (selectedFileUri != null) "Change Selected ZIP" else "Browse & Select ZIP File",
                                                fontSize = 12.sp
                                            )
                                        }
                                        if (selectedFileUri != null) {
                                            Text(
                                                text = "Selected: ${selectedFileUri.toString().substringAfterLast("/")}",
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = QuotaGreen,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is LocalInstallerState.Discovering -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = ClaudeTerracotta,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = state.message,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is LocalInstallerState.AwaitingConfirmation -> {
                        val info = state.packageInfo
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Found Termux Bootstrap Package",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Release Tag:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.releaseTag, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Architecture:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.arch, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Package Size:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.sizeFormatted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = QuotaGreen)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Target Sandbox:", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.packageName, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                    Text(
                                        text = info.url,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }

                    is LocalInstallerState.Downloading -> {
                        val downloadedStr = LocalEnvironmentManager.formatFileSize(state.bytesDownloaded)
                        val totalStr = LocalEnvironmentManager.formatFileSize(state.totalBytes)
                        val pct = (state.progressFraction * 100).toInt()

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Step 1: Downloading / Importing",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$pct%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ClaudeTerracotta
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progressFraction,
                                animationSpec = tween(300, easing = LinearEasing),
                                label = "progress"
                            )

                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$downloadedStr / $totalStr",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = state.speedText,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is LocalInstallerState.Extracting -> {
                        val pct = (state.progressFraction * 100).toInt()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Step 2: Unzipping & Extracting",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$pct%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ClaudeTerracotta
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progressFraction,
                                animationSpec = tween(200, easing = LinearEasing),
                                label = "extract_progress"
                            )

                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = state.currentFileName,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    is LocalInstallerState.Configuring -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Step 3: Configuring Shell & Permissions",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = state.stepDescription,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is LocalInstallerState.Verifying -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Step 4: Verifying Local Environment...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = QuotaGreen,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }

                    is LocalInstallerState.Success -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = QuotaGreen.copy(alpha = 0.12f)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = QuotaGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = state.message,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = QuotaGreen
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Installed at: ${state.prefixPath}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "Disk space: ${state.totalDiskUsageFormatted}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    is LocalInstallerState.Error -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Red.copy(alpha = 0.12f)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = state.errorMessage,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red
                                )
                            }
                        }
                    }
                }

                // LIVE INSTALLATION LOG CONSOLE
                val installerLogs by LocalEnvironmentManager.installerLogs.collectAsState()
                var showLogs by remember { mutableStateOf(true) }
                val logsListState = rememberLazyListState()

                LaunchedEffect(installerLogs.size) {
                    if (installerLogs.isNotEmpty()) {
                        logsListState.animateScrollToItem(installerLogs.size - 1)
                    }
                }

                if (installerState !is LocalInstallerState.Idle) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F1115),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLogs = !showLogs },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Installation Logs (${installerLogs.size})",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                Icon(
                                    imageVector = if (showLogs) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            if (showLogs) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyColumn(
                                    state = logsListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp, max = 150.dp)
                                        .background(Color(0xFF08090C), RoundedCornerShape(6.dp))
                                        .padding(6.dp)
                                ) {
                                    if (installerLogs.isEmpty()) {
                                        item {
                                            Text(
                                                text = "Logs will appear here once installation begins...",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color.Gray
                                            )
                                        }
                                    } else {
                                        items(installerLogs) { logLine ->
                                            Text(
                                                text = logLine,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 13.sp,
                                                color = if (logLine.contains("⚠") || logLine.contains("Fail") || logLine.contains("Error")) {
                                                    Color(0xFFF87171)
                                                } else if (logLine.contains("✓") || logLine.contains("Success") || logLine.contains("ready") || logLine.contains("Extracted")) {
                                                    QuotaGreen
                                                } else {
                                                    Color(0xFFD1D5DB)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom Buttons
                when (val state = installerState) {
                    is LocalInstallerState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    when (selectedSourceType) {
                                        0 -> {
                                            LocalEnvironmentManager.launchDiscover(context)
                                        }
                                        1 -> {
                                            val source = BootstrapSource.DirectUrl(directUrlText.trim())
                                            LocalEnvironmentManager.launchInstall(context, authPreferences, source)
                                        }
                                        2 -> {
                                            if (selectedFileUri != null) {
                                                val source = BootstrapSource.LocalZipUri(selectedFileUri!!)
                                                LocalEnvironmentManager.launchInstall(context, authPreferences, source)
                                            } else {
                                                zipPickerLauncher.launch("*/*")
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Icon(
                                    imageVector = if (selectedSourceType == 0) Icons.Default.Search else Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedSourceType == 0) "Find Package" else "Install Now",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    is LocalInstallerState.AwaitingConfirmation -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    LocalEnvironmentManager.launchInstall(
                                        context,
                                        authPreferences,
                                        BootstrapSource.DirectUrl(state.packageInfo.url)
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Confirm & Download", color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    LocalEnvironmentManager.resetState()
                                },
                                modifier = Modifier.weight(0.6f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Change")
                            }
                        }
                    }

                    is LocalInstallerState.Discovering -> {
                        OutlinedButton(
                            onClick = { LocalEnvironmentManager.resetState() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel Search")
                        }
                    }

                    is LocalInstallerState.Success -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        onOpenTerminal()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                                ) {
                                    Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open Terminal", color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(0.6f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Done", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    LocalEnvironmentManager.launchReset(context) {
                                        scope.launch { authPreferences.setLocalToolsInstalled(false) }
                                        LocalEnvironmentManager.resetState()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reinstall / Change Source", fontSize = 12.sp)
                            }
                        }
                    }

                    is LocalInstallerState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        when (selectedSourceType) {
                                            0 -> LocalEnvironmentManager.launchDiscover(context)
                                            1 -> {
                                                val source = BootstrapSource.DirectUrl(directUrlText.trim())
                                                LocalEnvironmentManager.launchInstall(context, authPreferences, source)
                                            }
                                            2 -> {
                                                val source = selectedFileUri?.let { BootstrapSource.LocalZipUri(it) } ?: BootstrapSource.Auto
                                                LocalEnvironmentManager.launchInstall(context, authPreferences, source)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry", color = Color.White, fontSize = 12.5.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        LocalEnvironmentManager.resetState()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Change Source", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        LocalEnvironmentManager.launchReset(context) {
                                            scope.launch { authPreferences.setLocalToolsInstalled(false) }
                                        }
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                                ) {
                                    Icon(imageVector = Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.Red)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear All", fontSize = 12.sp, color = Color.Red)
                                }

                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Close")
                                }
                            }
                        }
                    }

                    else -> {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Run in Background", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

