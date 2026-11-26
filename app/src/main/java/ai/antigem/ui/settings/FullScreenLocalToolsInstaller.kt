package ai.antigem.ui.settings

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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.local.BootstrapSource
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalInstallerState
import ai.antigem.data.local.RootfsMirror
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.*
import ai.antigem.i18n.L

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

    // 0: Auto GitHub, 1: Base minimum, 2: Local ZIP File
    var selectedSourceType by remember { mutableStateOf(0) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    // the rooted Ubuntu chroot has its own full-page setup
    var showChrootSetup by remember { mutableStateOf(false) }
    if (showChrootSetup) {
        ai.antigem.ui.settings.chroot.ChrootSetupDialog(
            onDismiss = { showChrootSetup = false },
            onDone = {
                showChrootSetup = false
                onComplete()
            }
        )
    }
    var showLogs by remember { mutableStateOf(false) }
    var selectedMirror by remember { mutableStateOf(RootfsMirror.GITHUB) }
    // the site of the last full-rootfs download started here; null when the last install was something else
    var lastMirror by remember { mutableStateOf<RootfsMirror?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedSourceType = 2
        }
    }

    fun installFull() {
        lastMirror = selectedMirror
        LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.Auto(selectedMirror))
    }

    fun installSelected(fallbackToAuto: Boolean) {
        when (selectedSourceType) {
            0 -> installFull()
            1 -> {
                lastMirror = null
                LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.BaseMinimum)
            }
            2 -> {
                val uri = selectedFileUri
                when {
                    uri != null -> {
                        lastMirror = null
                        LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.LocalZipUri(uri))
                    }
                    fallbackToAuto -> installFull()
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
                        Text(L.common.skip, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                InstallerHero(installerState, mirrorChoice = lastMirror != null)
                Spacer(modifier = Modifier.height(28.dp))

                AnimatedContent(
                    targetState = installerState::class,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "installerBody"
                ) { _ ->
                    when (val state = installerState) {
                        is LocalInstallerState.Idle -> SourcePicker(
                            selected = selectedSourceType,
                            selectedFileName = selectedFileUri?.toString()?.substringAfterLast("/"),
                            selectedMirror = selectedMirror,
                            onSelectMirror = { selectedMirror = it },
                            onSelect = { selectedSourceType = it },
                            onPickZip = { zipPickerLauncher.launch("*/*") },
                            onOpenChroot = { showChrootSetup = true }
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
                                DetailRow(L.settings.fullScreenLocalToolsInstaller.release, info.releaseTag)
                                DetailRow(L.settings.fullScreenLocalToolsInstaller.architecture, info.arch, ClaudeTerracotta)
                                DetailRow(L.settings.fullScreenLocalToolsInstaller.downloadSize, info.sizeFormatted, QuotaGreen)
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
                                text = L.settings.fullScreenLocalToolsInstaller.theTerminalTheIdeBackendAnd,
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
                                color = AppColors.onBackground,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
                                0 -> "Install (${LocalEnvironmentManager.ROOTFS_SIZE_LABEL})"
                                1 -> L.settings.fullScreenLocalToolsInstaller.installMinimal33Mb
                                else -> if (selectedFileUri != null) L.settings.fullScreenLocalToolsInstaller.installFromZip else L.settings.fullScreenLocalToolsInstaller.chooseAZipFile
                            },
                            icon = Icons.Default.Download,
                            onClick = { installSelected(fallbackToAuto = false) }
                        )
                        SecondaryButton(L.settings.fullScreenLocalToolsInstaller.skipForNow, onClick = onSkip)
                    }

                    is LocalInstallerState.AwaitingConfirmation -> {
                        PrimaryButton(
                            text = L.settings.fullScreenLocalToolsInstaller.confirmInstall,
                            icon = Icons.Default.Download,
                            onClick = {
                                LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.DirectUrl(state.packageInfo.url))
                            }
                        )
                        SecondaryButton(L.settings.fullScreenLocalToolsInstaller.changeSource) { LocalEnvironmentManager.resetState() }
                    }

                    is LocalInstallerState.Discovering -> SecondaryButton(L.common.cancel) { LocalEnvironmentManager.resetState() }

                    is LocalInstallerState.Downloading,
                    is LocalInstallerState.Extracting,
                    is LocalInstallerState.Configuring,
                    is LocalInstallerState.Verifying -> SecondaryButton(L.settings.fullScreenLocalToolsInstaller.continueInBackground, onClick = onSkip)

                    is LocalInstallerState.Success -> PrimaryButton(L.settings.fullScreenLocalToolsInstaller.getStarted, Icons.Default.Check, onComplete)

                    is LocalInstallerState.Error -> {
                        val failedMirror = lastMirror
                        if (failedMirror != null) {
                            // the full rootfs download failed: try the same site again or switch to the other one
                            MirrorChooser(selected = selectedMirror, onSelect = { selectedMirror = it })
                            if (selectedMirror == failedMirror) {
                                PrimaryButton("Try again (${selectedMirror.label})", Icons.Default.Refresh) { installFull() }
                            } else {
                                PrimaryButton(L.settings.fullScreenLocalToolsInstaller.downloadFromLabel(selectedMirror.label), Icons.Default.Download) { installFull() }
                            }
                        } else {
                            PrimaryButton(L.common.tryAgain, Icons.Default.Refresh) { installSelected(fallbackToAuto = true) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(L.settings.fullScreenLocalToolsInstaller.changeSource, Modifier.weight(1f)) { LocalEnvironmentManager.resetState() }
                            SecondaryButton(L.common.skip, Modifier.weight(1f), onSkip)
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
                    text = L.settings.fullScreenLocalToolsInstaller.notAvailableOnThisPhone,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = L.settings.fullScreenLocalToolsInstaller.yourPhoneHasA32Bit,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = AppColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(28.dp))
                InstallerCard {
                    Text(L.settings.fullScreenLocalToolsInstaller.youCanStillUseAntigem, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    Spacer(modifier = Modifier.height(10.dp))
                    listOf(
                        Icons.Outlined.Dns to L.settings.fullScreenLocalToolsInstaller.serverModeRunTheAgentsOn,
                        Icons.Outlined.Terminal to L.settings.fullScreenLocalToolsInstaller.sshWorkInATerminalOn
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
                PrimaryButton(L.settings.fullScreenLocalToolsInstaller.continueToAntigem, icon = null, onClick = onContinue)
            }
        }
    }
}

@Composable
private fun InstallerHero(state: LocalInstallerState, mirrorChoice: Boolean = false) {
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
            is LocalInstallerState.Success -> L.settings.fullScreenLocalToolsInstaller.youreAllSet
            is LocalInstallerState.Error -> L.settings.fullScreenLocalToolsInstaller.setupDidntFinish
            is LocalInstallerState.Idle -> L.settings.fullScreenLocalToolsInstaller.setUpYourLinuxEnvironment
            is LocalInstallerState.Discovering -> L.settings.fullScreenLocalToolsInstaller.findingTheLatestRelease
            is LocalInstallerState.AwaitingConfirmation -> L.settings.fullScreenLocalToolsInstaller.readyToInstall
            is LocalInstallerState.Downloading,
            is LocalInstallerState.Extracting,
            is LocalInstallerState.Configuring,
            is LocalInstallerState.Verifying -> L.settings.fullScreenLocalToolsInstaller.installing
        },
        fontSize = 25.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.onBackground,
        textAlign = TextAlign.Center,
        // changes with each stage (finding, installing, all set, didn't finish): TalkBack reads it out
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = when (state) {
            is LocalInstallerState.Success -> L.settings.fullScreenLocalToolsInstaller.theShellCoreToolsAndAgy
            is LocalInstallerState.Error -> if (mirrorChoice && LocalEnvironmentManager.ROOTFS_MIRRORS.size > 1) {
                L.settings.fullScreenLocalToolsInstaller.tryAgainSwitchToTheOther
            } else if (mirrorChoice) {
                // one download site for this CPU (x86_64: GitHub)
                L.settings.fullScreenLocalToolsInstaller.tryAgainOrOpenTheDownload
            } else {
                L.settings.fullScreenLocalToolsInstaller.youCanTryAgainOrInstall
            }
            is LocalInstallerState.Idle -> L.settings.fullScreenLocalToolsInstaller.antigemRunsItsTerminalIdeAnd
            is LocalInstallerState.Discovering -> L.settings.fullScreenLocalToolsInstaller.checkingForTheLatestVerifiedArchive
            is LocalInstallerState.AwaitingConfirmation -> L.settings.fullScreenLocalToolsInstaller.reviewTheDetailsAndConfirm
            else -> L.settings.fullScreenLocalToolsInstaller.thisTakesAFewMinutesYou
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
    selected: Int,
    selectedFileName: String?,
    selectedMirror: RootfsMirror,
    onSelectMirror: (RootfsMirror) -> Unit,
    onSelect: (Int) -> Unit,
    onPickZip: () -> Unit,
    onOpenChroot: () -> Unit
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SourceOption(
            icon = Icons.Outlined.CloudDownload,
            title = L.settings.fullScreenLocalToolsInstaller.fullEnvironment,
            tag = "Recommended · ${LocalEnvironmentManager.ROOTFS_SIZE_LABEL}",
            description = L.settings.fullScreenLocalToolsInstaller.verifiedRootfsWithTheToolsAnd,
            selected = selected == 0,
            onClick = { onSelect(0) }
        ) {
            AnimatedVisibility(visible = selected == 0) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    MirrorChooser(selected = selectedMirror, onSelect = onSelectMirror)
                }
            }
        }
        SourceOption(
            icon = Icons.Outlined.Bolt,
            title = L.settings.fullScreenLocalToolsInstaller.minimal,
            tag = "~33 MB",
            description = L.settings.fullScreenLocalToolsInstaller.cleanTermuxBaseForGetbootstraparchCore(LocalEnvironmentManager.getBootstrapArch()),
            selected = selected == 1,
            onClick = { onSelect(1) }
        )
        SourceOption(
            icon = Icons.Outlined.Storage,
            title = L.common.ubuntuChrootRoot,
            tag = "Rooted phones · Magisk / KernelSU / APatch",
            description = L.settings.fullScreenLocalToolsInstaller.aRealUbuntuInsteadOfAntigems,
            selected = false,
            onClick = onOpenChroot,
            choice = false
        )
        SourceOption(
            icon = Icons.Outlined.FolderOpen,
            title = L.settings.fullScreenLocalToolsInstaller.fromAZipFile,
            tag = null,
            description = L.settings.fullScreenLocalToolsInstaller.aBootstrapBackupOrCustomRootfs,
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
                        Text(if (selectedFileName != null) L.settings.fullScreenLocalToolsInstaller.chooseAnotherFile else L.settings.fullScreenLocalToolsInstaller.chooseFile, fontSize = 13.5.sp)
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

/**
 * GitHub | Hugging Face: where the full rootfs is downloaded from (the same zip on both). The ↗ on each opens that
 * site's page, where the zip can be downloaded by hand and installed with "From a ZIP file".
 */
@Composable
private fun MirrorChooser(selected: RootfsMirror, onSelect: (RootfsMirror) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
        LocalEnvironmentManager.ROOTFS_MIRRORS.forEach { mirror ->
            val isSelected = mirror == selected
            Surface(
                onClick = { onSelect(mirror) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else AppColors.card,
                border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else AppColors.border.copy(alpha = 0.6f)),
                modifier = Modifier.weight(1f).semantics { this.selected = isSelected; role = Role.RadioButton }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = mirror.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) ClaudeTerracotta else AppColors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { runCatching { uriHandler.openUri(LocalEnvironmentManager.rootfsPage(mirror)) } },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = L.settings.fullScreenLocalToolsInstaller.openTheLabelDownloadPage(mirror.label),
                            tint = if (isSelected) ClaudeTerracotta else AppColors.textSecondary,
                            modifier = Modifier.size(16.dp)
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
    /** One of the choices (a radio button for TalkBack); false: it opens something (a button). */
    choice: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val borderColor by animateColorAsState(
        if (selected) ClaudeTerracotta else AppColors.border.copy(alpha = 0.6f),
        label = "optionBorder"
    )
    // inside semantics { } the name "selected" is the semantics property, not this parameter
    val isSelected = selected
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.06f) else AppColors.card,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().semantics {
            if (choice) {
                this.selected = isSelected
                role = Role.RadioButton
            } else role = Role.Button
        }
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
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) // the card says "selected"
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
    val titles = listOf(L.common.download, L.settings.fullScreenLocalToolsInstaller.unpackFiles, L.settings.fullScreenLocalToolsInstaller.configureShell, L.settings.fullScreenLocalToolsInstaller.verify)
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
                    is LocalInstallerState.Verifying -> StepDetail(L.settings.fullScreenLocalToolsInstaller.runningTestname(state.testName), mono = true)
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
            // the check / spinner / number: the step's title below says its state instead
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clearAndSetSemantics { }
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
                color = if (done || current) AppColors.onBackground else AppColors.textSecondary,
                modifier = Modifier.semantics {
                    stateDescription = when {
                        done -> L.common.done
                        current -> L.settings.fullScreenLocalToolsInstaller.stepInProgress
                        else -> L.settings.fullScreenLocalToolsInstaller.stepNotStarted
                    }
                }
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
        // the bar says it
        Text("${(fraction * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta, modifier = Modifier.clearAndSetSemantics { })
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { }) {
        Text(label, fontSize = 13.sp, color = AppColors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = valueColor)
    }
}

/** This device's CPU facts, in one quiet line (they decide which archive fits). */
@Composable
private fun DeviceInfoLine() {
    val hw = remember { LocalEnvironmentManager.getDeviceHardwareInfo() }
    val va = when (hw.vaBits) {
        48 -> L.settings.fullScreenLocalToolsInstaller.n48BitAddressSpace to QuotaGreen
        39 -> L.settings.fullScreenLocalToolsInstaller.n39BitAddressSpace to AppColors.warning
        else -> null
    }
    val atomics = when (hw.hasAtomics) {
        true -> L.settings.fullScreenLocalToolsInstaller.lseAtomics to QuotaGreen
        false -> L.settings.fullScreenLocalToolsInstaller.noLseAtomics to AppColors.danger
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
            Text("  ·  ", fontSize = 12.sp, color = AppColors.textSecondary, modifier = Modifier.clearAndSetSemantics { })
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
                .semantics { stateDescription = if (expanded) L.components.a11y.expanded else L.components.a11y.collapsed }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) L.settings.fullScreenLocalToolsInstaller.hideDetails else L.settings.fullScreenLocalToolsInstaller.showDetails, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColors.textSecondary)
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
