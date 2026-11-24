package ai.antigem.ui.settings.chroot

import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.local.chroot.ChrootBind
import ai.antigem.data.local.chroot.ChrootConfig
import ai.antigem.data.local.chroot.ChrootDnsMode
import ai.antigem.data.local.chroot.ChrootExitBehavior
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootInstaller
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootNsMode
import ai.antigem.data.local.chroot.ChrootPackageBundle
import ai.antigem.data.local.chroot.ChrootScripts
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

// ===================================================================== Where agents run: the phone's two runtimes

/** Where agents run → This phone · Ubuntu chroot: the installs (which one is used), Manage, Install or find. */
@Composable
fun ChrootRuntimeCard(
    cardBg: Color,
    cardBorder: BorderStroke,
    onManage: (String) -> Unit,
    /** A chroot was installed, adopted or picked in the setup page (it is the one in use now). */
    onInstalled: (ChrootInstall) -> Unit
) {
    val context = LocalContext.current
    val installs by ChrootManager.installs.collectAsState()
    val activePath by ChrootManager.activePath.collectAsState()
    val found by ChrootManager.found.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    // the state of each install (running, mounted, stopped): one quick root call
    LaunchedEffect(installs.size) { if (installs.isNotEmpty()) ChrootManager.scan(context) }
    if (showSetup) {
        ChrootSetupDialog(onDismiss = { showSetup = false }) { inst ->
            showSetup = false
            onInstalled(inst)
        }
    }
    ChrootSection(L.common.ubuntuChroot, when {
            // the normal build has no Linux bridge: the setup page offers the chroot build
            !ai.antigem.BuildConfig.HAS_CHROOT -> L.settings.chrootSettingsScreens.needsARootedPhoneAndThe
            installs.isEmpty() -> L.settings.chrootSettingsScreens.notInstalledYetNeedsARooted
            else -> L.settings.chrootSettingsScreens.aRealUbuntuAptGlibcSudo
        },
        Icons.Outlined.Storage, AppColors.success, cardBg, cardBorder) {
        installs.forEach { inst ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installs.size > 1) {
                    androidx.compose.material3.RadioButton(
                        selected = inst.path == activePath,
                        onClick = { ChrootManager.setSelected(context, inst.path) },
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                    )
                }
                Column(Modifier.weight(1f).clickable { onManage(inst.path) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(inst.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        val f = found?.firstOrNull { it.path == inst.path }
                        when {
                            f == null -> Unit
                            f.running -> { Spacer(Modifier.width(6.dp)); ChrootTag("running", AppColors.success) }
                            f.mounted -> { Spacer(Modifier.width(6.dp)); ChrootTag(L.settings.chrootSettingsScreens.mountedNothingRunning, AppColors.warning) }
                            else -> { Spacer(Modifier.width(6.dp)); ChrootTag("stopped", AppColors.textSecondary) }
                        }
                        if (inst.path == activePath && AuthPreferences(context).isChrootModeSync()) { Spacer(Modifier.width(6.dp)); ChrootTag(L.settings.chrootSettingsScreens.inUse, AppColors.info) }
                        if (inst.legacy) { Spacer(Modifier.width(6.dp)); ChrootTag(L.settings.chrootSettingsScreens.firstVersion, AppColors.warning) }
                    }
                    Text("${inst.os} · ${inst.user}", fontSize = 11.5.sp, color = AppColors.textSecondary)
                    Text(inst.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                }
                TextButton(onClick = { onManage(inst.path) }) { Text("Manage ›", color = ClaudeTerracotta, fontSize = 12.5.sp) }
            }
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f))
        }
        if (installs.size > 1) ChrootNote(L.settings.chrootSettingsScreens.theSelectedOneIsUsedA)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { showSetup = true }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
            Text(when {
                !ai.antigem.BuildConfig.HAS_CHROOT -> L.settings.chrootSettingsScreens.getTheChrootBuild
                installs.isEmpty() -> L.settings.chrootSettingsScreens.installOrFindAnUbuntuChroot
                else -> L.settings.chrootSettingsScreens.installAnotherFindExisting
            })
        }
    }
}

/** Where agents run → This phone · Termux: installed or not, install, and the full app wipe. */
@Composable
fun TermuxRuntimeCard(cardBg: Color, cardBorder: BorderStroke, onInstall: () -> Unit, onFactoryReset: () -> Unit,
                      /** The install finished (the phone switched to Termux). */
                      onInstalled: () -> Unit = {}) {
    val context = LocalContext.current
    // follows the installer: the card shows the result as soon as an install finishes
    val installerState by LocalEnvironmentManager.installerState.collectAsState()
    val installed = remember(installerState) { LocalEnvironmentManager.isTermuxInstalled(context) }
    LaunchedEffect(installerState) {
        if (installerState is ai.antigem.data.local.LocalInstallerState.Success) onInstalled()
    }
    ChrootSection("AntiGem", if (installed) L.settings.chrootSettingsScreens.antigemsBuiltInLinuxEnvironmentIn
        else L.settings.chrootSettingsScreens.notInstalledAntigemsBuiltInLinux,
        Icons.Outlined.Terminal, AppColors.accentIndigo, cardBg, cardBorder) {
        if (!installed) {
            Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                Text(L.settings.chrootSettingsScreens.installAntigemsEnvironment)
            }
        } else {
            OutlinedButton(onClick = onFactoryReset, border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.Delete, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(L.settings.chrootSettingsScreens.deleteAllAppDataFactoryReset, color = AppColors.danger)
            }
            ChrootNote(L.settings.chrootSettingsScreens.wipesEverythingOfAntigemItsBuilt(ChrootManager.BASE_DIR))
        }
    }
}

// ===================================================================== Where agents run → Ubuntu chroot (its own page)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChrootManageScreen(
    path: String?,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenTerminal: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installs by ChrootManager.installs.collectAsState()
    val activePath by ChrootManager.activePath.collectAsState()
    val install = installs.firstOrNull { it.path == (path ?: activePath) } ?: installs.firstOrNull()
    val status by ChrootManager.status.collectAsState()
    val task by ChrootManager.task.collectAsState()
    val probe by ChrootManager.probe.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showProcesses by remember { mutableStateOf(false) }
    var confirmUnmount by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteText by remember { mutableStateOf("") }
    var deleteMounts by remember { mutableStateOf<Result<List<Pair<Int, String>>>?>(null) }
    var exitBehavior by remember { mutableStateOf(ChrootManager.exitBehavior(context)) }

    if (install == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(L.settings.chrootSettingsScreens.noUbuntuChrootIsInstalled, color = AppColors.textSecondary)
        }
        return
    }
    val isActiveRuntime = AuthPreferences(context).isChrootModeSync() && install.path == activePath
    // this page's numbers (the shared ones may be of another install)
    val s = status?.takeIf { it.path == install.path }
    var usage by remember(install.path) { mutableStateOf<ai.antigem.data.local.chroot.ChrootUsage?>(null) }
    var usageBusy by remember(install.path) { mutableStateOf(false) }

    // backups: a file the user picks (one per format), a folder for the clean image, restore through the setup page
    var showRestore by remember { mutableStateOf(false) }
    var backupFormat by remember { mutableStateOf(ChrootManager.BackupFormat.GZIP) }
    fun startBackup(uri: android.net.Uri, format: ChrootManager.BackupFormat) {
        ChrootManager.runTask(context, L.settings.chrootSettingsScreens.backingUpName(install.name)) { log ->
            ChrootManager.backup(context, install, uri, format, clean = false, totalKbHint = usage?.totalKb, onLine = log)
        }
    }
    val gzLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ChrootManager.BackupFormat.GZIP.mime)
    ) { uri -> if (uri != null) startBackup(uri, ChrootManager.BackupFormat.GZIP) }
    val xzLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ChrootManager.BackupFormat.XZ.mime)
    ) { uri -> if (uri != null) startBackup(uri, ChrootManager.BackupFormat.XZ) }
    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) ChrootManager.runTask(context, L.settings.chrootSettingsScreens.exportingACleanImage) { log ->
            ChrootManager.exportCleanImage(context, install, tree, usage?.totalKb, log)
        }
    }
    if (showRestore) {
        ChrootSetupDialog(onDismiss = { showRestore = false }, restore = true) { inst ->
            showRestore = false
            message = L.settings.chrootSettingsScreens.restoredAsNameChooseItIn(inst.name) to false
        }
    }

    fun measure() {
        if (usageBusy) return
        usageBusy = true
        scope.launch { usage = ChrootManager.refreshUsage(context, install); usageBusy = false }
    }

    LaunchedEffect(install.path) {
        ChrootManager.refreshStatus(context, install)
        if (probe == null) ChrootManager.probe(context)
        measure()
    }

    fun action(label: String, block: suspend () -> ChrootManager.SuResult?) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            val r = try { block() } catch (e: Exception) { ChrootManager.SuResult(-1, emptyList(), e.message ?: "$e") }
            busy = null
            if (r != null) message = (if (r.ok) "$label: done" else "$label failed: ${r.message()}") to !r.ok
            ChrootManager.refreshStatus(context, install)
        }
    }

    /** After a start: the bridge comes up in the background, so the page follows it for a while. */
    suspend fun followBridge(): ChrootManager.SuResult {
        repeat(16) {
            kotlinx.coroutines.delay(1500)
            val st = ChrootManager.refreshStatus(context, install)
            if (st?.bridgePid != null) return ChrootManager.SuResult(0, emptyList(), "")
        }
        return ChrootManager.SuResult(1, emptyList(), L.settings.chrootSettingsScreens.theBridgeDidNotComeUp)
    }

    // ---------------- dialogs
    confirmUnmount?.let { mp ->
        AlertDialog(
            onDismissRequest = { confirmUnmount = null },
            title = { Text(L.settings.chrootSettingsScreens.unmount) },
            text = { Text(L.settings.chrootSettingsScreens.mpProgramsInTheChrootThat(mp)) },
            confirmButton = { Button(onClick = { confirmUnmount = null; action(L.settings.chrootSettingsScreens.unmount2) { ChrootManager.unmount(context, mp, install) } },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) { Text(L.settings.chrootSettingsScreens.unmount2) } },
            dismissButton = { TextButton(onClick = { confirmUnmount = null }) { Text(L.common.cancel) } }
        )
    }
    if (confirmDelete) {
        LaunchedEffect(Unit) {
            deleteMounts = null
            deleteMounts = ChrootManager.mountsEverywhere(context, install)
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${install.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(L.settings.chrootSettingsScreens.everythingInPathIsDeletedThe(install.path, (usage?.let { " (${formatKb(it.totalKb)})" } ?: "")), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(L.settings.chrootSettingsScreens.mountedInsideItRightNowEvery, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    when (val m = deleteMounts) {
                        null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                            Spacer(Modifier.width(8.dp))
                            Text(L.common.checking, fontSize = 12.sp, color = AppColors.textSecondary)
                        }
                        else -> m.fold(
                            onSuccess = { list ->
                                if (list.isEmpty()) Text(L.settings.chrootSettingsScreens.nothing, fontSize = 12.sp, color = AppColors.success)
                                list.forEach { (pid, mp) ->
                                    Text("• ${mp.removePrefix(install.path).ifEmpty { "/" }}" + if (pid == 1) L.settings.chrootSettingsScreens.androidsNamespace else L.settings.chrootSettingsScreens.namespaceOfProcessPid(pid),
                                        fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.warning)
                                }
                                if (list.isNotEmpty()) ChrootNote(L.settings.chrootSettingsScreens.theyAreUnmountedFirstWhatThey)
                            },
                            onFailure = { Text(L.settings.chrootSettingsScreens.couldNotCheckMessage(it.message), fontSize = 12.sp, color = AppColors.danger) }
                        )
                    }
                    if (isActiveRuntime) ChrootNote(L.settings.chrootSettingsScreens.itIsInUseTheBridge, AppColors.warning)
                    Spacer(Modifier.height(10.dp))
                    Text(L.settings.chrootSettingsScreens.typeNameToConfirm(install.name), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(deleteText, { deleteText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        val wasActive = isActiveRuntime
                        ChrootManager.runTask(context, L.settings.chrootSettingsScreens.deletingName(install.name)) { log ->
                            if (wasActive) LocalServerManager.stopServerCompletely()
                            ChrootManager.delete(context, install, log).also { r ->
                                // the phone is back on AntiGem's environment: reopen so everything starts there
                                if (r.ok && wasActive) ChrootManager.reopenApp(context)
                                else if (r.ok) scope.launch { onDeleted() }
                            }
                        }
                    },
                    enabled = deleteText.trim() == install.name && deleteMounts != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger)
                ) { Text(L.common.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L.common.cancel) } }
        )
    }
    task?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!t.running) ChrootManager.clearTask() },
            title = { Text(t.title) },
            text = {
                Column {
                    if (t.running) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                        Spacer(Modifier.width(8.dp))
                        Text(L.settings.chrootSettingsScreens.running, fontSize = 12.5.sp)
                    } else Text(if (t.exitCode == 0) L.settings.chrootSettingsScreens.finished else L.settings.chrootSettingsScreens.failedExitExitcode(t.exitCode),
                        color = if (t.exitCode == 0) AppColors.success else AppColors.danger, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    ChrootLog(t.lines, maxHeight = 360)
                }
            },
            confirmButton = { TextButton(onClick = { ChrootManager.clearTask() }, enabled = !t.running) { Text(L.common.close) } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = chrootColumnSpacing) {
        // ---------------- overview: state, size, the main actions, delete
        val procs = s?.processCount ?: 0
        val mounted = s != null && s.error == null && (s.holderPid != null || s.mounts.isNotEmpty())
        val (stateText, stateColor) = when {
            s == null -> L.common.checking to AppColors.textSecondary
            s.error != null -> s.error to AppColors.danger
            procs > 0 -> L.settings.chrootSettingsScreens.runningProcsProcessValueValue2(procs, if (procs == 1) "" else "es", (s.sinceEpochSec?.let { " · for ${formatAge(it)}" } ?: "")) to AppColors.success
            mounted -> L.settings.chrootSettingsScreens.mountedNothingRunning2 to AppColors.warning
            else -> L.common.stopped to AppColors.textSecondary
        }
        ChrootSection(install.name, "${install.os} · ${install.arch} · user ${install.user}" + if (install.uid >= 0) " (uid ${install.uid})" else "",
            Icons.Outlined.Memory, stateColor, cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stateText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = stateColor, modifier = Modifier.weight(1f, fill = false))
                if (isActiveRuntime) { Spacer(Modifier.width(8.dp)); ChrootTag(L.settings.chrootSettingsScreens.inUse, AppColors.success) }
                if (install.legacy) { Spacer(Modifier.width(8.dp)); ChrootTag(L.settings.chrootSettingsScreens.firstVersion, AppColors.warning) }
            }
            val free = runCatching { android.os.StatFs("/data").availableBytes }.getOrDefault(-1L)
            Text(
                (usage?.let { L.settings.chrootSettingsScreens.usesFormatkb(formatKb(it.totalKb)) } ?: if (usageBusy) L.settings.chrootSettingsScreens.measuringItsSize else L.settings.chrootSettingsScreens.sizeUnknown) +
                    (if (free >= 0) L.settings.chrootSettingsScreens.formatbytesFreeOnData(formatBytes(free)) else ""),
                fontSize = 12.5.sp, color = AppColors.onBackground, modifier = Modifier.padding(top = 2.dp)
            )
            if (isActiveRuntime && s != null && s.error == null) {
                Text(if (s.bridgePid != null) L.settings.chrootSettingsScreens.bridgeRunningPidBridgepid(s.bridgePid) else L.settings.chrootSettingsScreens.bridgeNotRunning, fontSize = 12.sp,
                    color = if (s.bridgePid != null) AppColors.success else AppColors.textSecondary)
            }
            Text(install.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
            if (install.legacy) ChrootNote(L.settings.chrootSettingsScreens.installedByTheFirstChrootVersion, AppColors.warning)
            message?.let { (text, isError) ->
                Text(text, fontSize = 12.sp, color = if (isError) AppColors.danger else AppColors.success, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (procs > 0 || mounted) {
                    Button(onClick = {
                        action(L.common.stop) {
                            // stops every process (the bridge too) and unmounts: quick, no waiting for the bridge
                            ChrootManager.stop(context, install).also { if (isActiveRuntime) LocalServerManager.onChrootStopped() }
                        }
                    }, enabled = busy == null, colors = ButtonDefaults.buttonColors(containerColor = AppColors.warning), shape = RoundedCornerShape(10.dp)) {
                        Text(L.common.stop)
                    }
                }
                if (procs == 0 || (isActiveRuntime && s?.bridgePid == null)) {
                    Button(onClick = {
                        action(L.common.start) {
                            if (isActiveRuntime) {
                                // the server starts the chroot and its bridge
                                LocalServerManager.startServer(context, forceRestart = true)
                                followBridge()
                            } else {
                                val r = ChrootManager.start(context, install)
                                ChrootManager.SuResult(if (r.ok) 0 else 1, emptyList(), r.message)
                            }
                        }
                    }, enabled = busy == null, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(L.common.start)
                    }
                }
                if (isActiveRuntime && s?.bridgePid != null) {
                    OutlinedButton(onClick = {
                        action(L.settings.chrootSettingsScreens.restartBridge) { LocalServerManager.restartServerAndWait(context); followBridge() }
                    }, enabled = busy == null, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.restartBridge) }
                }
                if (isActiveRuntime) {
                    OutlinedButton(onClick = { LocalTerminalManager.createNewSession(context, sessionTitle = install.user); onOpenTerminal() },
                        shape = RoundedCornerShape(10.dp)) { Text(L.common.terminal) }
                    OutlinedButton(onClick = {
                        LocalTerminalManager.createNewSession(context, initialCommand = "sudo -i", sessionTitle = "root"); onOpenTerminal()
                    }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.rootShell) }
                }
                TextButton(onClick = { action(L.common.refresh) { ChrootManager.refreshStatus(context, install); null } }, enabled = busy == null) {
                    Text(L.common.refresh, color = ClaudeTerracotta)
                }
            }
            if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                Spacer(Modifier.width(8.dp))
                Text("$busy…", fontSize = 12.sp, color = AppColors.textSecondary)
            }
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { deleteText = ""; confirmDelete = true }, enabled = busy == null,
                    border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(L.settings.chrootSettingsScreens.deleteThisUbuntu, color = AppColors.danger)
                }
                if (!isActiveRuntime) TextButton(onClick = { ChrootManager.forget(context, install.path); onDeleted() }) {
                    Text(L.settings.chrootSettingsScreens.forgetKeepFiles, color = AppColors.textSecondary, fontSize = 12.5.sp)
                }
            }
        }

        // ---------------- mounts
        ChrootSection(L.settings.chrootSettingsScreens.mounts, L.settings.chrootSettingsScreens.whatIsMountedInsideItRight((s?.let { if (it.holderPid != null) L.settings.chrootSettingsScreens.inItsPrivateNamespace else if (it.mounts.isNotEmpty()) L.settings.chrootSettingsScreens.inAndroidsNamespace else "" } ?: "")),
            Icons.Outlined.ViewList, AppColors.accentIndigo, cardBg, cardBorder) {
            val mounts = s?.mounts.orEmpty()
            if (mounts.isEmpty()) Text(L.settings.chrootSettingsScreens.nothingIsMounted, fontSize = 12.5.sp, color = AppColors.textSecondary)
            mounts.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.mountPoint.removePrefix(install.path).ifEmpty { "/ (rootfs bind)" }, fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        Text("${m.fsType} · ${m.source}${if (m.readOnly) " · read-only" else ""}", fontSize = 11.sp, color = AppColors.textSecondary)
                    }
                    if (m.mountPoint != install.path) TextButton(onClick = { confirmUnmount = m.mountPoint }, enabled = busy == null) {
                        Text(L.settings.chrootSettingsScreens.unmount2, color = AppColors.danger, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---------------- settings (chroot.conf), shared folders included
        val conf = s?.config
        if (conf != null && s.error == null) ChrootConfigSection(install, conf, cardBg, cardBorder, busy == null) { newConf ->
            action(L.settings.chrootSettingsScreens.saveSettings) { ChrootManager.saveConfig(context, install, newConf) }
        }

        // ---------------- storage details
        ChrootSection(L.settings.chrootSettingsScreens.storage, usage?.let { L.settings.chrootSettingsScreens.formatkbInAll(formatKb(it.totalKb)) } ?: L.settings.chrootSettingsScreens.whereItsSpaceGoes, Icons.Outlined.Storage, AppColors.info, cardBg, cardBorder) {
            val u = usage
            if (u != null && u.totalKb > 0) {
                val parts = listOf("home" to L.settings.chrootSettingsScreens.homeFolders, "usr" to L.settings.chrootSettingsScreens.programsUsr, "var/lib/apt/lists" to "apt package lists",
                    "var/cache/apt" to "apt download cache", "opt" to "/opt", "tmp" to "/tmp", "root" to L.settings.chrootSettingsScreens.rootsHome)
                    .mapNotNull { (k, label) -> u.partsKb[k]?.takeIf { it > 0 }?.let { label to it } }
                val other = (u.totalKb - parts.sumOf { it.second }).coerceAtLeast(0)
                (parts + (L.settings.chrootSettingsScreens.everythingElse to other)).sortedByDescending { it.second }.forEach { (label, kb) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row {
                            Text(label, fontSize = 12.sp, color = AppColors.onBackground, modifier = Modifier.weight(1f))
                            Text(formatKb(kb), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (kb.toFloat() / u.totalKb).coerceIn(0f, 1f) }, color = AppColors.info,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                    }
                }
            } else if (usageBusy) Text(L.settings.chrootSettingsScreens.measuring, fontSize = 12.5.sp, color = AppColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { measure() }, enabled = !usageBusy, shape = RoundedCornerShape(10.dp)) {
                    Text(if (usageBusy) L.settings.chrootSettingsScreens.measuring else L.settings.chrootSettingsScreens.measureAgain)
                }
                OutlinedButton(onClick = {
                    ChrootManager.runTask(context, L.settings.chrootSettingsScreens.cleaningTheAptCache) { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.CLEAN_SCRIPT, log) }
                }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.cleanAptCache) }
            }
        }

        // ---------------- backup & restore
        ChrootSection(L.settings.chrootSettingsScreens.backupRestore, L.settings.chrootSettingsScreens.oneFileWithEverythingOfThis, Icons.Outlined.Backup, AppColors.info, cardBg, cardBorder) {
            ChrootManager.BackupFormat.entries.forEach { f ->
                ChrootRadioRow(f.label, null, backupFormat == f) { backupFormat = f }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val fileName = "${install.name}-$date.${backupFormat.ext}"
                    if (backupFormat == ChrootManager.BackupFormat.GZIP) gzLauncher.launch(fileName) else xzLauncher.launch(fileName)
                }, enabled = task?.running != true, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                    Text(L.settings.chrootSettingsScreens.backUp)
                }
                OutlinedButton(onClick = { showRestore = true }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.restoreABackup) }
            }
            ChrootNote("Backing up works while it runs. Restoring installs the backup as a new copy (pick its name and folder); it keeps " +
                "its user, home, settings and packages.")
            if (mounted && s?.holderPid == null) ChrootNote(L.settings.chrootSettingsScreens.itsMountsAreInAndroidsNamespace, AppColors.warning)
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
            Text(L.settings.chrootSettingsScreens.cleanImageToShare, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
            ChrootNote(L.settings.chrootSettingsScreens.leavesOutTheHomeFoldersLogins(ai.antigem.data.local.chroot.ChrootInstaller.prebuiltAsset(), ai.antigem.data.local.chroot.ChrootInstaller.PREBUILT_TAG))
            OutlinedButton(onClick = { imageLauncher.launch(null) }, enabled = task?.running != true, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(top = 6.dp)) { Text(L.settings.chrootSettingsScreens.exportACleanImage) }
        }

        // ---------------- packages
        ChrootPackagesSection(install, cardBg, cardBorder)

        // ---------------- user
        ChrootUserSection(install, cardBg, cardBorder)

        // ---------------- exit
        ChrootSection(L.settings.chrootSettingsScreens.whenAntigemExits, L.settings.chrootSettingsScreens.exitFromTheMenuOrThe,
            Icons.Outlined.ExitToApp, AppColors.warning, cardBg, cardBorder) {
            ChrootExitBehavior.entries.forEach { b ->
                ChrootRadioRow(b.label, b.description, exitBehavior == b) {
                    exitBehavior = b
                    ChrootManager.setExitBehavior(context, b)
                }
            }
        }

        // ---------------- processes
        ChrootSection(L.settings.chrootSettingsScreens.processes, L.settings.chrootSettingsScreens.procsRunningInIt(procs), Icons.Outlined.Memory, AppColors.accentTeal, cardBg, cardBorder) {
            TextButton(onClick = { showProcesses = !showProcesses }) { Text(if (showProcesses) L.common.hide else L.common.show, color = ClaudeTerracotta) }
            if (showProcesses) s?.processes.orEmpty().forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.pid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary, modifier = Modifier.width(56.dp))
                    Text(p.command, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.onBackground, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { action(L.settings.chrootSettingsScreens.killPid(p.pid)) { ChrootManager.killProcess(context, p.pid, install) } }, enabled = busy == null) {
                        Text(L.settings.chrootSettingsScreens.kill, color = AppColors.danger, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // ---------------- repair, about
        ChrootSection(L.settings.chrootSettingsScreens.repair, L.settings.chrootSettingsScreens.setsUpTheNetworkGroupsThe, Icons.Outlined.Build, AppColors.accentAlt, cardBg, cardBorder) {
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, L.settings.chrootSettingsScreens.repairingName(install.name)) { log ->
                    val st = ChrootManager.start(context, install, withBridge = true, onLine = log)
                    if (!st.ok) return@runTask ChrootManager.SuResult(1, emptyList(), st.message)
                    log("== the bridge: ${if (st.bridgeUpdated) "updated" else "current"}")
                    ChrootManager.execAsRoot(context, install.path,
                        ChrootScripts.userScript(install.user, android.os.Process.myUid(), "", sudoNoPassword = true, keepSudoers = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.repair) }
        }
        ChrootSection(L.settings.chrootSettingsScreens.aboutThisPhone, null, Icons.Outlined.Info, AppColors.textSecondary, cardBg, cardBorder) {
            val p = probe
            ChrootKeyValue(L.common.root, p?.rootManager ?: "…")
            ChrootKeyValue(L.settings.chrootSettingsScreens.mountTools, p?.let { if (it.canPrivateNs) "unshare + nsenter (${it.busybox ?: "toybox"})" else L.settings.chrootSettingsScreens.noUnshareNsenter } ?: "…")
            ChrootKeyValue("SELinux", p?.selinux ?: "…")
            ChrootKeyValue(L.settings.chrootSettingsScreens.kernel, p?.kernel ?: "…")
            ChrootKeyValue(L.settings.chrootSettingsScreens.source, install.source)
            ChrootKeyValue(L.settings.chrootSettingsScreens.controlScript, ChrootManager.ctlFile(context).absolutePath, mono = true)
        }
    }
}

@Composable
private fun ChrootConfigSection(install: ChrootInstall, conf: ChrootConfig, cardBg: Color, cardBorder: BorderStroke, enabled: Boolean, onSave: (ChrootConfig) -> Unit) {
    val context = LocalContext.current
    var sdcard by remember(conf) { mutableStateOf(conf.mountSdcard) }
    var sdcardRo by remember(conf) { mutableStateOf(conf.sdcardReadOnly) }
    var storage by remember(conf) { mutableStateOf(conf.mountStorage) }
    var dnsAuto by remember(conf) { mutableStateOf(conf.dnsMode == ChrootDnsMode.AUTO) }
    var dns by remember(conf) { mutableStateOf(conf.customDns.joinToString(", ").ifBlank { "1.1.1.1, 8.8.8.8" }) }
    var shm by remember(conf) { mutableStateOf(conf.shmMb.toString()) }
    var ns by remember(conf) { mutableStateOf(conf.nsMode) }
    var binds by remember(conf) { mutableStateOf(conf.binds) }
    val inApp = ChrootManager.isInAppStorage(context, install.path)
    val dnsList = dns.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    ChrootSection(L.common.settings, "Saved into the rootfs (.antigem/chroot.conf); mounts change at the next start of the chroot",
        Icons.Outlined.Settings, ClaudeTerracotta, cardBg, cardBorder) {
        ChrootSwitchRow(L.settings.chrootSettingsScreens.phoneStorageAtSdcard, null, sdcard) { sdcard = it }
        if (sdcard) ChrootSwitchRow(L.settings.chrootSettingsScreens.readOnlySdcard, L.settings.chrootSettingsScreens.programsInTheChrootCannotChange, sdcardRo) { sdcardRo = it }
        if (sdcard) ChrootSwitchRow(L.settings.chrootSettingsScreens.usbDrivesAndSdCardsStorage,
            if (sdcardRo) L.settings.chrootSettingsScreens.offWithAReadOnlySdcard
            else L.settings.chrootSettingsScreens.everyVolumeAtStorageAlsoDrives,
            storage && !sdcardRo, enabled = !sdcardRo) { storage = it }
        ChrootNote(L.settings.chrootSettingsScreens.usbDevicesForFastbootAdbSerial(install.user))
        ChrootSwitchRow(L.settings.chrootSettingsScreens.automaticDns, L.settings.chrootSettingsScreens.thePhonesDnsServersAtEvery, dnsAuto) { dnsAuto = it }
        if (!dnsAuto) OutlinedTextField(dns, { dns = it }, label = { Text(L.settings.chrootSettingsScreens.dnsServers) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(shm, { shm = it.filter(Char::isDigit).take(5) }, label = { Text("/dev/shm size (MB)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        Text(L.settings.chrootSettingsScreens.mountNamespace, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
        ChrootRadioRow(ChrootNsMode.PRIVATE.label, L.settings.chrootSettingsScreens.mountsOnlyInsideTheChrootsOwn,
            ns == ChrootNsMode.PRIVATE) { ns = ChrootNsMode.PRIVATE }
        ChrootRadioRow(ChrootNsMode.GLOBAL.label, L.settings.chrootSettingsScreens.mountsVisibleToTheWholePhone,
            ns == ChrootNsMode.GLOBAL, enabled = !inApp) { ns = ChrootNsMode.GLOBAL }
        Spacer(Modifier.height(6.dp))
        ChrootFoldersEditor(user = install.user, binds = binds) { binds = it }
        val valid = dnsAuto || (dnsList.isNotEmpty() && dnsList.all { ChrootManager.isDnsServer(it) })
        Button(
            onClick = {
                onSave(conf.copy(
                    nsMode = ns, mountSdcard = sdcard, sdcardReadOnly = sdcardRo, mountStorage = storage,
                    shmMb = shm.toIntOrNull()?.coerceIn(16, 8192) ?: conf.shmMb,
                    dnsMode = if (dnsAuto) ChrootDnsMode.AUTO else ChrootDnsMode.CUSTOM,
                    customDns = if (dnsAuto) conf.customDns else dnsList, binds = binds
                ))
            },
            enabled = enabled && valid, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 8.dp)
        ) { Text(L.settings.chrootSettingsScreens.saveSettings) }
        if (ns != conf.nsMode) ChrootNote(L.settings.chrootSettingsScreens.theNamespaceChangesAfterStopEverything, AppColors.warning)
        if (binds != conf.binds) ChrootNote(L.settings.chrootSettingsScreens.saveThenStopEverythingStartShared, AppColors.warning)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootPackagesSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var bundles by remember { mutableStateOf(emptySet<ChrootPackageBundle>()) }
    var extra by remember { mutableStateOf("") }
    val names = extra.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    ChrootSection(L.settings.chrootSettingsScreens.packages, "apt inside the chroot (as root)", Icons.Outlined.Inventory2, AppColors.success, cardBg, cardBorder) {
        ChrootPackageBundle.entries.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(b in bundles, { on -> bundles = if (on) bundles + b else bundles - b },
                    colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta))
                Column(Modifier.weight(1f)) {
                    Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    Text(b.description, fontSize = 11.sp, color = AppColors.textSecondary)
                }
            }
        }
        OutlinedTextField(extra, { extra = it }, label = { Text(L.settings.chrootSettingsScreens.packagesSpaceSeparated) }, modifier = Modifier.fillMaxWidth())
        val bad = names.filter { !ChrootScripts.isPackageName(it) }
        if (bad.isNotEmpty()) ChrootNote(L.settings.chrootSettingsScreens.notPackageNamesValue(bad.joinToString(" ")), AppColors.danger)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val pkgs = bundles.flatMap { it.packages } + names
                    val node = bundles.any { it.nodeJs }
                    ChrootManager.runTask(context, L.settings.chrootSettingsScreens.installingPackages) { log ->
                        ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(pkgs, node, upgrade = false), log)
                    }
                    bundles = emptySet(); extra = ""
                },
                enabled = (bundles.isNotEmpty() || names.isNotEmpty()) && bad.isEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
            ) { Text(L.common.install) }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, L.settings.chrootSettingsScreens.updatingUbuntu) { log ->
                    ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(emptyList(), nodeJs = false, upgrade = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.updateUpgradeAll) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootUserSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    ChrootSection(L.settings.chrootSettingsScreens.userUser(install.user), "Its uid is AntiGem's own (${android.os.Process.myUid()}), so files it shares with the app belong to both",
        Icons.Outlined.Person, AppColors.accentPink, cardBg, cardBorder) {
        OutlinedTextField(password, { password = it.replace("\n", "") }, label = { Text(L.settings.chrootSettingsScreens.newPassword) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val p = password
                password = ""
                ChrootManager.runTask(context, L.settings.chrootSettingsScreens.changingThePassword) { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.passwordScript(install.user, p), log) }
            }, enabled = password.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.setPassword) }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "sudo without a password") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, true), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.sudoNoPassword) }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "sudo asks for the password") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, false), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.sudoAskPassword) }
        }
        ChrootNote(L.settings.chrootSettingsScreens.sudoAskPasswordNeedsAPassword)
    }
}

private fun formatAge(sinceEpochSec: Long): String {
    val secs = (System.currentTimeMillis() / 1000 - sinceEpochSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60} min"
        secs < 86400 -> L.settings.chrootSettingsScreens.valueHValue2Min(secs / 3600, (secs % 3600) / 60)
        else -> L.settings.chrootSettingsScreens.valueDValue2H(secs / 86400, (secs % 86400) / 3600)
    }
}

/** Shown instead of a Termux-only page while the Ubuntu chroot is the phone's runtime. */
@Composable
fun TermuxOnlyNotice(title: String, cardBg: Color, cardBorder: BorderStroke, note: String? = null, onOpenEnvironment: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ChrootSection(L.settings.chrootSettingsScreens.titleWorksWithAntigemsBuiltIn(title), L.settings.chrootSettingsScreens.thisPhoneRunsTheUbuntuChroot(title), Icons.Outlined.Info, AppColors.warning, cardBg, cardBorder) {
            if (note != null) ChrootNote(note, AppColors.info)
            OutlinedButton(onClick = onOpenEnvironment, shape = RoundedCornerShape(10.dp)) { Text(L.settings.chrootSettingsScreens.whereAgentsRun) }
        }
    }
}
