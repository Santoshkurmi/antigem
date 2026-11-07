package com.example.gemini.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.local.BootstrapSource
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalInstallerState
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.*

/** First-launch setup of the local Linux environment (bootstrap / rootfs): pick a source, then watch it install. */
@Composable
fun FullScreenLocalToolsInstaller(
    authPreferences: AuthPreferences,
    onSkip: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val installerState by LocalEnvironmentManager.installerState.collectAsState()
    val installerLogs by LocalEnvironmentManager.installerLogs.collectAsState()

    val isInstalling = installerState is LocalInstallerState.Downloading ||
        installerState is LocalInstallerState.Extracting ||
        installerState is LocalInstallerState.Configuring ||
        installerState is LocalInstallerState.Verifying

    // the screen stays on while installing
    val activity = (context as? android.app.Activity) ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
    DisposableEffect(isInstalling) {
        val window = activity?.window
        if (isInstalling) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var selectedSourceType by remember { mutableStateOf(0) } // 0: Auto GitHub, 1: Base minimum, 2: Local ZIP File
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    val isTermux = remember { LocalEnvironmentManager.isTermuxPackage(context) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedSourceType = 2
        }
    }

    fun installSelected(fallbackToAuto: Boolean) {
        when (selectedSourceType) {
            0 -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.Auto)
            1 -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.BaseMinimum)
            2 -> {
                val uri = selectedFileUri
                when {
                    uri != null -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.LocalZipUri(uri))
                    fallbackToAuto -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.Auto)
                    else -> zipPickerLauncher.launch("*/*")
                }
            }
        }
    }

    // a 32-bit phone cannot run the local environment at all: say so, nothing to install, only go on into the app
    val unsupported = remember { LocalEnvironmentManager.isUnsupported32BitDevice() }
    if (unsupported) {
        UnsupportedDeviceScreen(onContinue = onSkip)
        return
    }

    val canSkipTop = installerState !is LocalInstallerState.Downloading && installerState !is LocalInstallerState.Extracting &&
        installerState !is LocalInstallerState.Success

    Surface(modifier = Modifier.fillMaxSize(), color = AppColors.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // top: skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (canSkipTop) {
                    TextButton(onClick = onSkip, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.textSecondary)) {
                        Text("Skip", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                InstallerHero(installerState, isTermux)
                Spacer(modifier = Modifier.height(28.dp))

                AnimatedContent(
                    targetState = installerState::class,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "installerBody"
                ) { _ ->
                    when (val state = installerState) {
                        is LocalInstallerState.Idle -> SourcePicker(
                            isTermux = isTermux,
                            selected = selectedSourceType,
                            selectedFileName = selectedFileUri?.toString()?.substringAfterLast("/"),
                            onSelect = { selectedSourceType = it },
                            onPickZip = { zipPickerLauncher.launch("*/*") }
                        )

                        is LocalInstallerState.Discovering -> InstallerCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = ClaudeTerracotta, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(state.message, fontSize = 14.sp, color = AppColors.onBackground)
                            }
                        }

                        is LocalInstallerState.AwaitingConfirmation -> {
                            val info = state.packageInfo
                            InstallerCard {
                                DetailRow("Release", info.releaseTag)
                                DetailRow("Architecture", info.arch, ClaudeTerracotta)
                                DetailRow("Download size", info.sizeFormatted, QuotaGreen)
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    color = AppColors.border.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = info.url,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        is LocalInstallerState.Downloading,
                        is LocalInstallerState.Extracting,
                        is LocalInstallerState.Configuring,
                        is LocalInstallerState.Verifying -> InstallSteps(state)

                        is LocalInstallerState.Success -> InstallerCard(tint = QuotaGreen) {
                            Text(
                                text = "The terminal, the IDE backend and the Antigravity language server are ready to run.",
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                                color = AppColors.onBackground
                            )
                        }

                        is LocalInstallerState.Error -> InstallerCard(tint = AppColors.danger) {
                            Text(
                                text = state.errorMessage,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = AppColors.onBackground
                            )
                        }
                    }
                }

                if (installerState is LocalInstallerState.Idle) {
                    Spacer(modifier = Modifier.height(18.dp))
                    DeviceInfoLine()
                }

                // details (installer log)
                if (installerState !is LocalInstallerState.Idle && installerLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    InstallerLog(installerLogs, expanded = showLogs, onToggle = { showLogs = !showLogs })
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // actions, pinned at the bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (val state = installerState) {
                    is LocalInstallerState.Idle -> {
                        PrimaryButton(
                            text = when (selectedSourceType) {
                                0 -> if (isTermux) "Install (~350 MB)" else "Install Ubuntu base"
                                1 -> "Install minimal (~33 MB)"
                                else -> if (selectedFileUri != null) "Install from ZIP" else "Choose a ZIP file"
                            },
                            icon = Icons.Default.Download,
                            onClick = { installSelected(fallbackToAuto = false) }
                        )
                        SecondaryButton("Skip for now", onClick = onSkip)
                    }

                    is LocalInstallerState.AwaitingConfirmation -> {
                        PrimaryButton(
                            text = "Confirm & install",
                            icon = Icons.Default.Download,
                            onClick = {
                                LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.DirectUrl(state.packageInfo.url))
                            }
                        )
                        SecondaryButton("Change source") { LocalEnvironmentManager.resetState() }
                    }

                    is LocalInstallerState.Discovering -> SecondaryButton("Cancel") { LocalEnvironmentManager.resetState() }

                    is LocalInstallerState.Downloading,
                    is LocalInstallerState.Extracting,
                    is LocalInstallerState.Configuring,
                    is LocalInstallerState.Verifying -> SecondaryButton("Continue in background", onClick = onSkip)

                    is LocalInstallerState.Success -> PrimaryButton("Get started", Icons.Default.Check, onComplete)

                    is LocalInstallerState.Error -> {
                        PrimaryButton("Try again", Icons.Default.Refresh) { installSelected(fallbackToAuto = true) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Change source", Modifier.weight(1f)) { LocalEnvironmentManager.resetState() }
                            SecondaryButton("Skip", Modifier.weight(1f), onSkip)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

/** 32-bit phone: the local Linux environment is not available; the app still works with a server or over SSH. */
@Composable
private fun UnsupportedDeviceScreen(onContinue: () -> Unit) {
    val abis = remember { android.os.Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty() }
    Surface(modifier = Modifier.fillMaxSize(), color = AppColors.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(64.dp))
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(AppColors.warning, AppColors.warning.copy(alpha = 0.65f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = "Not available on this phone",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your phone has a 32-bit processor. The local Linux environment (terminal, IDE backend and agents running on the phone) needs a 64-bit one, so it can't be installed here.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = AppColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(28.dp))
                InstallerCard {
                    Text("You can still use AntiGem", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    Spacer(modifier = Modifier.height(10.dp))
                    listOf(
                        Icons.Outlined.Dns to "Server mode: run the agents on a computer or server and connect to it",
                        Icons.Outlined.Terminal to "SSH: work in a terminal on another machine"
                    ).forEach { (icon, text) ->
                        Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Icon(icon, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = AppColors.textSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = abis,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textSecondary.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp)) {
                PrimaryButton("Continue to AntiGem", icon = null, onClick = onContinue)
            }
        }
    }
}

@Composable
private fun InstallerHero(state: LocalInstallerState, isTermux: Boolean) {
    val (icon, tint) = when (state) {
        is LocalInstallerState.Success -> Icons.Default.Check to QuotaGreen
        is LocalInstallerState.Error -> Icons.Default.ErrorOutline to AppColors.danger
        else -> Icons.Outlined.Terminal to ClaudeTerracotta
    }
    val animatedTint by animateColorAsState(tint, label = "heroTint")
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(animatedTint, animatedTint.copy(alpha = 0.65f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
    }
    Spacer(modifier = Modifier.height(22.dp))
    Text(
        text = when (state) {
            is LocalInstallerState.Success -> "You're all set"
            is LocalInstallerState.Error -> "Setup didn't finish"
            is LocalInstallerState.Idle -> if (isTermux) "Set up your Linux environment" else "Set up the Ubuntu container"
            is LocalInstallerState.Discovering -> "Finding the latest release"
            is LocalInstallerState.AwaitingConfirmation -> "Ready to install"
            is LocalInstallerState.Downloading,
            is LocalInstallerState.Extracting,
            is LocalInstallerState.Configuring,
            is LocalInstallerState.Verifying -> "Installing"
        },
        fontSize = 25.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.onBackground,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = when (state) {
            is LocalInstallerState.Success -> if (isTermux) "The shell, core tools and AGY tools are installed." else "Ubuntu, bash, glibc and AGY tools are installed."
            is LocalInstallerState.Error -> "You can try again, or install from a ZIP file instead."
            is LocalInstallerState.Idle -> "AntiGem runs its terminal, IDE and agents on a local Linux system. Choose how to install it."
            is LocalInstallerState.Discovering -> "Checking for the latest verified archive."
            is LocalInstallerState.AwaitingConfirmation -> "Review the details and confirm."
            else -> "This takes a few minutes. You can keep using the app meanwhile."
        },
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = AppColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

@Composable
private fun SourcePicker(
    isTermux: Boolean,
    selected: Int,
    selectedFileName: String?,
    onSelect: (Int) -> Unit,
    onPickZip: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SourceOption(
            icon = Icons.Outlined.CloudDownload,
            title = if (isTermux) "Full environment" else "Ubuntu ARM64 base",
            tag = if (isTermux) "Recommended · ~350 MB" else "Recommended",
            description = if (isTermux) "Verified rootfs with the tools and environment preconfigured."
            else "Official minimal Ubuntu rootfs with glibc, bash and dev tools.",
            selected = selected == 0,
            onClick = { onSelect(0) }
        )
        if (isTermux) {
            SourceOption(
                icon = Icons.Outlined.Bolt,
                title = "Minimal",
                tag = "~33 MB",
                description = "Clean Termux base for ${LocalEnvironmentManager.getBootstrapArch()}: core utilities and the package manager.",
                selected = selected == 1,
                onClick = { onSelect(1) }
            )
        }
        SourceOption(
            icon = Icons.Outlined.FolderOpen,
            title = "From a ZIP file",
            tag = null,
            description = if (isTermux) "A bootstrap backup or custom rootfs on this phone."
            else "A container backup or custom rootfs (bootstraps.zip) on this phone.",
            selected = selected == 2,
            onClick = { onSelect(2) }
        ) {
            AnimatedVisibility(visible = selected == 2) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(
                        onClick = onPickZip,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedFileName != null) "Choose another file" else "Choose file", fontSize = 13.5.sp)
                    }
                    if (selectedFileName != null) {
                        Text(
                            text = selectedFileName,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = QuotaGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceOption(
    icon: ImageVector,
    title: String,
    tag: String?,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val borderColor by animateColorAsState(
        if (selected) ClaudeTerracotta else AppColors.border.copy(alpha = 0.6f),
        label = "optionBorder"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.06f) else AppColors.card,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background((if (selected) ClaudeTerracotta else AppColors.textSecondary).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) ClaudeTerracotta else AppColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    if (tag != null) {
                        Text(tag, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) ClaudeTerracotta else AppColors.textSecondary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(description, fontSize = 12.5.sp, lineHeight = 17.sp, color = AppColors.textSecondary)
                }
                Spacer(modifier = Modifier.width(10.dp))
                // selection mark
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) Modifier.background(ClaudeTerracotta)
                            else Modifier.border(1.5.dp, AppColors.border, CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            extra()
        }
    }
}

/** Download → Extract → Configure → Verify; the active step shows its progress and detail. */
@Composable
private fun InstallSteps(state: LocalInstallerState) {
    val active = when (state) {
        is LocalInstallerState.Downloading -> 0
        is LocalInstallerState.Extracting -> 1
        is LocalInstallerState.Configuring -> 2
        is LocalInstallerState.Verifying -> 3
        else -> 4
    }
    val titles = listOf("Download", "Unpack files", "Configure shell", "Verify")
    InstallerCard {
        titles.forEachIndexed { i, title ->
            StepRow(index = i, title = title, done = i < active, current = i == active, last = i == titles.lastIndex) {
                when (state) {
                    is LocalInstallerState.Downloading -> {
                        StepProgress(state.progressFraction)
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(
                                "${LocalEnvironmentManager.formatFileSize(state.bytesDownloaded)} of ${LocalEnvironmentManager.formatFileSize(state.totalBytes)}",
                                fontSize = 12.sp,
                                color = AppColors.textSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(state.speedText, fontSize = 12.sp, color = AppColors.textSecondary)
                        }
                    }
                    is LocalInstallerState.Extracting -> {
                        StepProgress(state.progressFraction)
                        StepDetail(state.currentFileName, mono = true)
                    }
                    is LocalInstallerState.Configuring -> {
                        StepProgress(state.progressFraction)
                        StepDetail(state.stepDescription)
                    }
                    is LocalInstallerState.Verifying -> StepDetail("Running: ${state.testName}", mono = true)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    title: String,
    done: Boolean,
    current: Boolean,
    last: Boolean,
    detail: @Composable ColumnScope.() -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> QuotaGreen
                            current -> ClaudeTerracotta
                            else -> AppColors.fill
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    done -> Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    current -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    else -> Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textSecondary)
                }
            }
            if (!last) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .padding(vertical = 3.dp)
                        .background(if (done) QuotaGreen.copy(alpha = 0.6f) else AppColors.border.copy(alpha = 0.6f))
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp, bottom = if (last) 0.dp else 18.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.5.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = if (done || current) AppColors.onBackground else AppColors.textSecondary
            )
            if (current) {
                Spacer(modifier = Modifier.height(8.dp))
                detail()
            }
        }
    }
}

@Composable
private fun StepProgress(fraction: Float) {
    val animated by animateFloatAsState(fraction, animationSpec = tween(300, easing = LinearEasing), label = "stepProgress")
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = ClaudeTerracotta,
            trackColor = AppColors.fill
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text("${(fraction * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta)
    }
}

@Composable
private fun StepDetail(text: String, mono: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        color = AppColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun InstallerCard(tint: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = tint?.copy(alpha = 0.08f) ?: AppColors.card,
        border = BorderStroke(1.dp, tint?.copy(alpha = 0.35f) ?: AppColors.border.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = AppColors.onBackground) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = AppColors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = valueColor)
    }
}

/** This device's CPU facts, in one quiet line (they decide which archive fits). */
@Composable
private fun DeviceInfoLine() {
    val hw = remember { LocalEnvironmentManager.getDeviceHardwareInfo() }
    val va = when (hw.vaBits) {
        48 -> "48-bit address space" to QuotaGreen
        39 -> "39-bit address space" to AppColors.warning
        else -> null
    }
    val atomics = when (hw.hasAtomics) {
        true -> "LSE atomics" to QuotaGreen
        false -> "no LSE atomics" to AppColors.danger
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Memory, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(hw.arch, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
        listOfNotNull(va, atomics).forEach { (label, color) ->
            Text("  ·  ", fontSize = 12.sp, color = AppColors.textSecondary)
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, color = AppColors.textSecondary)
        }
    }
}

@Composable
private fun InstallerLog(lines: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, expanded) {
        if (expanded && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "Hide details" else "Show details", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.textSecondary)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(min = 80.dp, max = 220.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.terminalBg)
                    .padding(12.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        color = when {
                            line.contains("⚠") || line.contains("Fail") || line.contains("Error") -> AppColors.danger
                            line.contains("✓") || line.contains("Success") || line.contains("ready") || line.contains("Extracted") -> QuotaGreen
                            else -> AppColors.editorText
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.onBackground)
    ) {
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}
