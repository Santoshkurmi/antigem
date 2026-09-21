package com.example.gemini.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.TermuxActivity
import com.example.gemini.data.local.BootstrapSource
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalInstallerState
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenLocalToolsInstaller(
    authPreferences: AuthPreferences,
    onSkip: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installerState by LocalEnvironmentManager.installerState.collectAsState()
    val installerLogs by LocalEnvironmentManager.installerLogs.collectAsState()

    var selectedSourceType by remember { mutableStateOf(0) } // 0: Auto GitHub, 1: Direct URL, 2: Local ZIP File
    var directUrlText by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedSourceType = 2
        }
    }

    val logsListState = rememberLazyListState()
    LaunchedEffect(installerLogs.size) {
        if (installerLogs.isNotEmpty()) {
            logsListState.animateScrollToItem(installerLogs.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AntiGem Environment",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Local Linux & Terminal Setup",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (installerState !is LocalInstallerState.Downloading && installerState !is LocalInstallerState.Extracting) {
                        TextButton(
                            onClick = onSkip,
                            colors = ButtonDefaults.textButtonColors(contentColor = ClaudeTerracotta)
                        ) {
                            Text("Skip for Now", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Hero Icon with Gradient Glow
            Box(
                modifier = Modifier
                    .size(80.dp)
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
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (installerState) {
                    is LocalInstallerState.Success -> "Local Linux Tools Ready!"
                    is LocalInstallerState.Error -> "Setup Encountered an Issue"
                    is LocalInstallerState.Idle -> "Initialize Linux Environment"
                    is LocalInstallerState.Discovering -> "Locating Latest Bootstrap..."
                    is LocalInstallerState.AwaitingConfirmation -> "Confirm Bootstrap Installation"
                    is LocalInstallerState.Downloading -> "Downloading Toolchain..."
                    is LocalInstallerState.Extracting -> "Extracting & Linking Files..."
                    is LocalInstallerState.Configuring -> "Finalizing Permissions..."
                    is LocalInstallerState.Verifying -> "Verifying Environment..."
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = when (installerState) {
                    is LocalInstallerState.Success -> "Termux-compatible shell, coreutils & AGY tools are installed and ready."
                    is LocalInstallerState.Error -> "Failed to unpack rootfs. You can retry or pick a local ZIP archive."
                    is LocalInstallerState.Idle -> "Select how you would like to set up the local Termux environment for AntiGem:"
                    is LocalInstallerState.Discovering -> "Checking GitHub releases for the latest verified rootfs archive."
                    is LocalInstallerState.AwaitingConfirmation -> "Review details below and confirm to begin installation."
                    is LocalInstallerState.Downloading -> "Fetching rootfs archive into secure application storage."
                    is LocalInstallerState.Extracting -> "Decompressing packages, setting Unix permissions, and mapping symlinks."
                    is LocalInstallerState.Configuring -> "Configuring startup scripts, environment variables, and shell binaries."
                    is LocalInstallerState.Verifying -> "Running verification checks on installed binaries and symlinks."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                lineHeight = 18.sp
            )

            // Dynamic Step / Configuration Content
            when (val state = installerState) {
                is LocalInstallerState.Idle -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Option 1: Auto Download (GitHub)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
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
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedSourceType == 0,
                                    onClick = { selectedSourceType = 0 },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Auto Download from GitHub",
                                            fontSize = 14.sp,
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
                                            Text("Official", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = QuotaGreen)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Downloads verified bootstrap for ${LocalEnvironmentManager.getBootstrapArch()} (~33MB) directly from official repository.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Option 2: Local ZIP File from Storage
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (selectedSourceType == 2) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSourceType = 2 }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedSourceType == 2,
                                        onClick = { selectedSourceType = 2 },
                                        colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Select Local ZIP Archive",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Pick an existing bootstrap backup or custom rootfs from phone storage.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (selectedSourceType == 2) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(
                                        onClick = { zipPickerLauncher.launch("*/*") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (selectedFileUri != null) "Change Selected ZIP" else "Browse & Select ZIP File",
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (selectedFileUri != null) {
                                        Text(
                                            text = "Selected: ${selectedFileUri.toString().substringAfterLast("/")}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = QuotaGreen,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Option 3: Direct URL
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (selectedSourceType == 1) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSourceType = 1 }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedSourceType == 1,
                                        onClick = { selectedSourceType = 1 },
                                        colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Direct Download URL",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Paste a direct HTTP/HTTPS link to a bootstrap zip.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (selectedSourceType == 1) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = directUrlText,
                                        onValueChange = { directUrlText = it },
                                        label = { Text("Bootstrap URL (.zip)", fontSize = 12.sp) },
                                        placeholder = { Text("https://example.com/bootstrap.zip", fontSize = 11.sp) },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                is LocalInstallerState.Discovering -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = ClaudeTerracotta,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.message,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is LocalInstallerState.AwaitingConfirmation -> {
                    val info = state.packageInfo
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Release Tag:", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(info.releaseTag, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Architecture:", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(info.arch, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Archive Size:", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(info.sizeFormatted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = QuotaGreen)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            Text(
                                text = info.url,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }

                is LocalInstallerState.Downloading -> {
                    val downloadedStr = LocalEnvironmentManager.formatFileSize(state.bytesDownloaded)
                    val totalStr = LocalEnvironmentManager.formatFileSize(state.totalBytes)
                    val pct = (state.progressFraction * 100).toInt()

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Downloading Bootstrap", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("$pct%", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progressFraction,
                                animationSpec = tween(300, easing = LinearEasing),
                                label = "progress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$downloadedStr / $totalStr", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(state.speedText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                is LocalInstallerState.Extracting -> {
                    val pct = (state.progressFraction * 100).toInt()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Unpacking & Extracting", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("$pct%", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = state.progressFraction,
                                animationSpec = tween(200, easing = LinearEasing),
                                label = "extract_progress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = state.currentFileName,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                is LocalInstallerState.Configuring -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Configuring Shell & Symlinks", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ClaudeTerracotta, strokeWidth = 2.dp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = ClaudeTerracotta,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = state.stepDescription,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is LocalInstallerState.Verifying -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Verifying Installation", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ClaudeTerracotta, strokeWidth = 2.dp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Running test: ${state.testName}",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is LocalInstallerState.Success -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = QuotaGreen.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "✓ Installation Complete",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = QuotaGreen
                            )
                            Text(
                                text = "Bootstrap rootfs successfully configured. The interactive terminal, Go IDE backend, and AGY AI language server are now ready to run.",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                is LocalInstallerState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "⚠ Error Occurred",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = state.errorMessage,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Live Terminal Log Expander
            if (installerState !is LocalInstallerState.Idle) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogs = !showLogs },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Installation Log Output", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showLogs) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            state = logsListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 200.dp)
                                .background(Color(0xFF08090C), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            items(installerLogs) { logLine ->
                                Text(
                                    text = logLine,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp,
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

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            when (val state = installerState) {
                is LocalInstallerState.Idle -> {
                    Button(
                        onClick = {
                            when (selectedSourceType) {
                                0 -> LocalEnvironmentManager.launchDiscover(context)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(
                            imageVector = if (selectedSourceType == 0) Icons.Default.Search else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedSourceType == 0) "Find Bootstrap Package" else "Begin Installation",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Skip Setup & Open AntiGem Chat", fontSize = 14.sp)
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
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Confirm & Install", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = { LocalEnvironmentManager.resetState() },
                            modifier = Modifier
                                .weight(0.6f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Change")
                        }
                    }
                }

                is LocalInstallerState.Discovering -> {
                    OutlinedButton(
                        onClick = { LocalEnvironmentManager.resetState() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel Search")
                    }
                }

                is LocalInstallerState.Downloading,
                is LocalInstallerState.Extracting,
                is LocalInstallerState.Configuring,
                is LocalInstallerState.Verifying -> {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Run in Background & Open AntiGem")
                    }
                }

                is LocalInstallerState.Success -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                onComplete()
                                context.startActivity(Intent(context, TermuxActivity::class.java))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Launch Terminal", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = onComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Start AntiGem Chat", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                is LocalInstallerState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Installation", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { LocalEnvironmentManager.resetState() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Change Source")
                            }

                            OutlinedButton(
                                onClick = onSkip,
                                modifier = Modifier
                                    .weight(0.7f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Skip")
                            }
                        }
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Run in Background & Open Chat", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
