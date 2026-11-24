package ai.antigem.ui.settings

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
import ai.antigem.data.local.BootstrapSource
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalInstallerState
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.*
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

@Composable
fun LocalToolsInstallDialog(
    authPreferences: AuthPreferences,
    onOpenTerminal: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installerState by LocalEnvironmentManager.installerState.collectAsState()

    val isInstalling = installerState is LocalInstallerState.Downloading ||
        installerState is LocalInstallerState.Extracting ||
        installerState is LocalInstallerState.Configuring ||
        installerState is LocalInstallerState.Verifying

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

    // 0: Auto GitHub, 1: Direct URL, 2: Local ZIP File
    var selectedSourceType by remember { mutableStateOf(0) }
    // the rooted Ubuntu chroot has its own full-page setup
    var showChrootSetup by remember { mutableStateOf(false) }
    if (showChrootSetup) {
        ai.antigem.ui.settings.chroot.ChrootSetupDialog(
            onDismiss = { showChrootSetup = false },
            onDone = {
                showChrootSetup = false
                // the bridge moves into the chroot (it was installed as this phone's runtime)
                ai.antigem.data.local.chroot.ChrootManager.scope.launch {
                    ai.antigem.data.local.LocalServerManager.stopServerCompletely()
                    ai.antigem.data.local.LocalTerminalManager.closeAll()
                    ai.antigem.data.local.LocalServerManager.startServer(context, forceRestart = true)
                }
                onDismiss()
            }
        )
    }
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
                        is LocalInstallerState.Success -> L.settings.localToolsInstall.localToolsReady
                        is LocalInstallerState.Error -> L.settings.localToolsInstall.installationError
                        is LocalInstallerState.Idle -> L.settings.localToolsInstall.installLocalLinuxTools
                        else -> L.settings.localToolsInstall.settingUpLocalTools
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = when (installerState) {
                        is LocalInstallerState.Success -> L.settings.localToolsInstall.termuxCompatibleShellCoreutilsToolchain
                        is LocalInstallerState.Error -> L.settings.localToolsInstall.failedToConfigureLocalEnvironmentYou
                        is LocalInstallerState.Idle -> L.settings.localToolsInstall.selectHowYouWouldLikeTo
                        else -> L.settings.localToolsInstall.downloadingAndUnpackingNativeLinux
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                // Device Hardware Capabilities Card
                val hardwareInfo = remember { LocalEnvironmentManager.getDeviceHardwareInfo() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = ClaudeTerracotta
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = L.settings.localToolsInstall.deviceHardwareCapabilities,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // CPU Architecture Pill
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(L.settings.localToolsInstall.arch, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        hardwareInfo.arch,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = ClaudeTerracotta
                                    )
                                }
                            }

                            // Virtual Address Space Pill
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when (hardwareInfo.vaBits) {
                                            48 -> QuotaGreen.copy(alpha = 0.12f)
                                            39 -> ai.antigem.theme.AppColors.warning.copy(alpha = 0.12f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(L.settings.localToolsInstall.addressSpace, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        when (hardwareInfo.vaBits) {
                                            48 -> "48-bit"
                                            39 -> "39-bit"
                                            else -> "N/A"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (hardwareInfo.vaBits) {
                                            48 -> QuotaGreen
                                            39 -> ai.antigem.theme.AppColors.warning
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            // LSE Atomics Pill
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when (hardwareInfo.hasAtomics) {
                                            true -> QuotaGreen.copy(alpha = 0.12f)
                                            false -> ai.antigem.theme.AppColors.danger.copy(alpha = 0.12f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(L.settings.localToolsInstall.atomics, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        when (hardwareInfo.hasAtomics) {
                                            true -> L.settings.localToolsInstall.supported
                                            false -> L.settings.localToolsInstall.none
                                            else -> "N/A"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (hardwareInfo.hasAtomics) {
                                            true -> QuotaGreen
                                            false -> ai.antigem.theme.AppColors.danger
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Dynamic Step / Progress Content
                when (val state = installerState) {
                    is LocalInstallerState.Idle -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
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
                                                text = L.settings.localToolsInstall.autoDownloadGithub,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(QuotaGreen.copy(alpha = 0.15f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(L.common.recommended, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = QuotaGreen)
                                            }
                                        }
                                        Text(
                                            text = "Auto-downloads verified AntiGem rootfs (${LocalEnvironmentManager.ROOTFS_SIZE_LABEL}) with preconfigured tools and environment",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // The rooted Ubuntu chroot: opens its own full-page setup
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showChrootSetup = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Storage, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = L.common.ubuntuChrootRoot,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = L.settings.localToolsInstall.aRealUbuntuForRootedPhones,
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Option 2: Local ZIP File from Storage
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
                                                text = L.settings.localToolsInstall.localZipArchive,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = L.settings.localToolsInstall.pickAnExistingBootstrapZipFile,
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
                                                text = if (selectedFileUri != null) L.settings.localToolsInstall.changeSelectedZip else L.settings.localToolsInstall.browseSelectZipFile,
                                                fontSize = 12.sp
                                            )
                                        }
                                        if (selectedFileUri != null) {
                                            Text(
                                                text = L.settings.localToolsInstall.selectedValue(selectedFileUri.toString().substringAfterLast("/")),
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = QuotaGreen,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Option 3: Base Minimum Termux
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
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedSourceType == 1,
                                        onClick = { selectedSourceType = 1 },
                                        colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = L.settings.localToolsInstall.baseMinimumTermux,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(ai.antigem.theme.AppColors.accentIndigo.copy(alpha = 0.15f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(L.settings.localToolsInstall.minimal33mb, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = ai.antigem.theme.AppColors.accentIndigo)
                                            }
                                        }
                                        Text(
                                            text = "Downloads clean official Termux base bootstrap (${LocalEnvironmentManager.getBootstrapArch()}) with essential coreutils",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = L.settings.localToolsInstall.foundTermuxBootstrapPackage,
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
                                        Text(L.settings.localToolsInstall.releaseTag, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.releaseTag, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(L.settings.localToolsInstall.architecture, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.arch, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(L.settings.localToolsInstall.packageSize, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(info.sizeFormatted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = QuotaGreen)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(L.settings.localToolsInstall.targetSandbox, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    text = L.settings.localToolsInstall.step1DownloadingImporting,
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
                                    text = L.settings.localToolsInstall.step2UnzippingExtracting,
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
                                    text = L.settings.localToolsInstall.step3ConfiguringShellPermissions,
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
                                text = L.settings.localToolsInstall.step4VerifyingLocalEnvironment,
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
                                    text = L.settings.localToolsInstall.installedAtPrefixpath(state.prefixPath),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = L.settings.localToolsInstall.diskSpaceTotaldiskusageformatted(state.totalDiskUsageFormatted),
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
                        color = ai.antigem.theme.AppColors.terminalBg,
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
                                        .background(ai.antigem.theme.AppColors.terminalBg, RoundedCornerShape(6.dp))
                                        .padding(6.dp)
                                ) {
                                    if (installerLogs.isEmpty()) {
                                        item {
                                            Text(
                                                text = L.settings.localToolsInstall.logsWillAppearHereOnceInstallation,
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
                                                    ai.antigem.theme.AppColors.danger
                                                } else if (logLine.contains("✓") || logLine.contains("Success") || logLine.contains("ready") || logLine.contains("Extracted")) {
                                                    QuotaGreen
                                                } else {
                                                    ai.antigem.theme.AppColors.editorText
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
                                            LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.Auto)
                                        }
                                        1 -> {
                                            LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.BaseMinimum)
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
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (selectedSourceType) {
                                        0 -> L.settings.localToolsInstall.downloadInstallAntigem350mb
                                        1 -> L.settings.localToolsInstall.downloadBaseTermux33mb
                                        else -> if (selectedFileUri != null) L.settings.localToolsInstall.installLocalZip else L.settings.localToolsInstall.chooseZip
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(L.common.cancel)
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
                                Text(L.settings.localToolsInstall.confirmDownload, color = Color.White, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    LocalEnvironmentManager.resetState()
                                },
                                modifier = Modifier.weight(0.6f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(L.common.change)
                            }
                        }
                    }

                    is LocalInstallerState.Discovering -> {
                        OutlinedButton(
                            onClick = { LocalEnvironmentManager.resetState() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(L.settings.localToolsInstall.cancelSearch)
                        }
                    }

                    is LocalInstallerState.Success -> {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(L.common.done, color = Color.White, fontWeight = FontWeight.Bold)
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
                                            0 -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.Auto)
                                            1 -> LocalEnvironmentManager.launchInstall(context, authPreferences, BootstrapSource.BaseMinimum)
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
                                    Text(L.common.retry, color = Color.White, fontSize = 12.5.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        LocalEnvironmentManager.resetState()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(L.settings.localToolsInstall.changeSource, fontSize = 12.sp)
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
                                    Text(L.settings.localToolsInstall.clearAll, fontSize = 12.sp, color = Color.Red)
                                }

                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(L.common.close)
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
                            Text(L.settings.localToolsInstall.runInBackground, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

