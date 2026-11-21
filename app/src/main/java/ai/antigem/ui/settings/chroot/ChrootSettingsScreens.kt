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
    ChrootSection("Ubuntu chroot", when {
            // the normal build has no Linux bridge: the setup page offers the chroot build
            !ai.antigem.BuildConfig.HAS_CHROOT -> "Needs a rooted phone and the chroot build of AntiGem (the same app with the chroot's bridge, installed over this one)"
            installs.isEmpty() -> "Not installed yet: needs a rooted phone (Magisk, KernelSU or APatch)"
            else -> "A real Ubuntu (apt, glibc, sudo) through root. Tap one to manage it: status, mounts, folders, packages"
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
                            f.mounted -> { Spacer(Modifier.width(6.dp)); ChrootTag("mounted, nothing running", AppColors.warning) }
                            else -> { Spacer(Modifier.width(6.dp)); ChrootTag("stopped", AppColors.textSecondary) }
                        }
                        if (inst.path == activePath && AuthPreferences(context).isChrootModeSync()) { Spacer(Modifier.width(6.dp)); ChrootTag("in use", AppColors.info) }
                        if (inst.legacy) { Spacer(Modifier.width(6.dp)); ChrootTag("first version", AppColors.warning) }
                    }
                    Text("${inst.os} · ${inst.user}", fontSize = 11.5.sp, color = AppColors.textSecondary)
                    Text(inst.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                }
                TextButton(onClick = { onManage(inst.path) }) { Text("Manage ›", color = ClaudeTerracotta, fontSize = 12.5.sp) }
            }
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f))
        }
        if (installs.size > 1) ChrootNote("The selected one is used. A change applies when the bridge restarts (Save, or Restart below).")
        Spacer(Modifier.height(6.dp))
        Button(onClick = { showSetup = true }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
            Text(when {
                !ai.antigem.BuildConfig.HAS_CHROOT -> "Get the chroot build"
                installs.isEmpty() -> "Install or find an Ubuntu chroot"
                else -> "Install another / find existing"
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
    ChrootSection("AntiGem", if (installed) "AntiGem's built-in Linux environment, in its own files: no root needed, packages with pkg install"
        else "Not installed: AntiGem's built-in Linux environment (no root needed)",
        Icons.Outlined.Terminal, AppColors.accentIndigo, cardBg, cardBorder) {
        if (!installed) {
            Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                Text("Install AntiGem's environment")
            }
        } else {
            OutlinedButton(onClick = onFactoryReset, border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.Delete, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete all app data (factory reset)…", color = AppColors.danger)
            }
            ChrootNote("Wipes everything of AntiGem: its built-in environment, its home, chats and settings. An Ubuntu chroot in " +
                "${ChrootManager.BASE_DIR} stays (delete it on its own page).")
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
            Text("No Ubuntu chroot is installed.", color = AppColors.textSecondary)
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
        ChrootManager.runTask(context, "Backing up ${install.name}") { log ->
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
        if (tree != null) ChrootManager.runTask(context, "Exporting a clean image") { log ->
            ChrootManager.exportCleanImage(context, install, tree, usage?.totalKb, log)
        }
    }
    if (showRestore) {
        ChrootSetupDialog(onDismiss = { showRestore = false }, restore = true) { inst ->
            showRestore = false
            message = "Restored as ${inst.name}: choose it in Where agents run to use it." to false
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
        return ChrootManager.SuResult(1, emptyList(), "the bridge did not come up: see Settings → Where agents run → Server control, or its log")
    }

    // ---------------- dialogs
    confirmUnmount?.let { mp ->
        AlertDialog(
            onDismissRequest = { confirmUnmount = null },
            title = { Text("Unmount?") },
            text = { Text("$mp\n\nPrograms in the chroot that use it may stop working until the chroot is restarted.") },
            confirmButton = { Button(onClick = { confirmUnmount = null; action("Unmount") { ChrootManager.unmount(context, mp, install) } },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) { Text("Unmount") } },
            dismissButton = { TextButton(onClick = { confirmUnmount = null }) { Text("Cancel") } }
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
                    Text("Everything in ${install.path} is deleted: the user's home, installed packages, agent logins and chats made in it" +
                        (usage?.let { " (${formatKb(it.totalKb)})" } ?: "") + ".", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Mounted inside it right now (every namespace of the phone):", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    when (val m = deleteMounts) {
                        null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                            Spacer(Modifier.width(8.dp))
                            Text("Checking…", fontSize = 12.sp, color = AppColors.textSecondary)
                        }
                        else -> m.fold(
                            onSuccess = { list ->
                                if (list.isEmpty()) Text("Nothing.", fontSize = 12.sp, color = AppColors.success)
                                list.forEach { (pid, mp) ->
                                    Text("• ${mp.removePrefix(install.path).ifEmpty { "/" }}" + if (pid == 1) "  (Android's namespace)" else "  (namespace of process $pid)",
                                        fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.warning)
                                }
                                if (list.isNotEmpty()) ChrootNote("They are unmounted first. What they show (the phone's storage, /system, shared folders) is " +
                                    "never deleted: the delete refuses while anything is still mounted, and never crosses into another filesystem.")
                            },
                            onFailure = { Text("Could not check: ${it.message}", fontSize = 12.sp, color = AppColors.danger) }
                        )
                    }
                    if (isActiveRuntime) ChrootNote("It is in use: the bridge and the agents stop, and the phone goes back to AntiGem's built-in environment. AntiGem reopens when the delete is done.", AppColors.warning)
                    Spacer(Modifier.height(10.dp))
                    Text("Type ${install.name} to confirm:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(deleteText, { deleteText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        val wasActive = isActiveRuntime
                        ChrootManager.runTask(context, "Deleting ${install.name}") { log ->
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
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
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
                        Text("Running…", fontSize = 12.5.sp)
                    } else Text(if (t.exitCode == 0) "Finished" else "Failed (exit ${t.exitCode})",
                        color = if (t.exitCode == 0) AppColors.success else AppColors.danger, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    ChrootLog(t.lines, maxHeight = 360)
                }
            },
            confirmButton = { TextButton(onClick = { ChrootManager.clearTask() }, enabled = !t.running) { Text("Close") } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = chrootColumnSpacing) {
        // ---------------- overview: state, size, the main actions, delete
        val procs = s?.processCount ?: 0
        val mounted = s != null && s.error == null && (s.holderPid != null || s.mounts.isNotEmpty())
        val (stateText, stateColor) = when {
            s == null -> "Checking…" to AppColors.textSecondary
            s.error != null -> s.error to AppColors.danger
            procs > 0 -> "Running · $procs process${if (procs == 1) "" else "es"}" + (s.sinceEpochSec?.let { " · for ${formatAge(it)}" } ?: "") to AppColors.success
            mounted -> "Mounted, nothing running" to AppColors.warning
            else -> "Stopped" to AppColors.textSecondary
        }
        ChrootSection(install.name, "${install.os} · ${install.arch} · user ${install.user}" + if (install.uid >= 0) " (uid ${install.uid})" else "",
            Icons.Outlined.Memory, stateColor, cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stateText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = stateColor, modifier = Modifier.weight(1f, fill = false))
                if (isActiveRuntime) { Spacer(Modifier.width(8.dp)); ChrootTag("in use", AppColors.success) }
                if (install.legacy) { Spacer(Modifier.width(8.dp)); ChrootTag("first version", AppColors.warning) }
            }
            val free = runCatching { android.os.StatFs("/data").availableBytes }.getOrDefault(-1L)
            Text(
                (usage?.let { "Uses ${formatKb(it.totalKb)}" } ?: if (usageBusy) "Measuring its size…" else "Size unknown") +
                    (if (free >= 0) " · ${formatBytes(free)} free on /data" else ""),
                fontSize = 12.5.sp, color = AppColors.onBackground, modifier = Modifier.padding(top = 2.dp)
            )
            if (isActiveRuntime && s != null && s.error == null) {
                Text(if (s.bridgePid != null) "Bridge running (pid ${s.bridgePid})" else "Bridge not running", fontSize = 12.sp,
                    color = if (s.bridgePid != null) AppColors.success else AppColors.textSecondary)
            }
            Text(install.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
            if (install.legacy) ChrootNote("Installed by the first chroot version. Where agents run → Install another / find existing → Adopt makes " +
                "AntiGem manage it fully (settings, AntiGem's uid), or delete it below.", AppColors.warning)
            message?.let { (text, isError) ->
                Text(text, fontSize = 12.sp, color = if (isError) AppColors.danger else AppColors.success, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (procs > 0 || mounted) {
                    Button(onClick = {
                        action("Stop") {
                            // stops every process (the bridge too) and unmounts: quick, no waiting for the bridge
                            ChrootManager.stop(context, install).also { if (isActiveRuntime) LocalServerManager.onChrootStopped() }
                        }
                    }, enabled = busy == null, colors = ButtonDefaults.buttonColors(containerColor = AppColors.warning), shape = RoundedCornerShape(10.dp)) {
                        Text("Stop")
                    }
                }
                if (procs == 0 || (isActiveRuntime && s?.bridgePid == null)) {
                    Button(onClick = {
                        action("Start") {
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
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Start")
                    }
                }
                if (isActiveRuntime && s?.bridgePid != null) {
                    OutlinedButton(onClick = {
                        action("Restart bridge") { LocalServerManager.restartServerAndWait(context); followBridge() }
                    }, enabled = busy == null, shape = RoundedCornerShape(10.dp)) { Text("Restart bridge") }
                }
                if (isActiveRuntime) {
                    OutlinedButton(onClick = { LocalTerminalManager.createNewSession(context, sessionTitle = install.user); onOpenTerminal() },
                        shape = RoundedCornerShape(10.dp)) { Text("Terminal") }
                    OutlinedButton(onClick = {
                        LocalTerminalManager.createNewSession(context, initialCommand = "sudo -i", sessionTitle = "root"); onOpenTerminal()
                    }, shape = RoundedCornerShape(10.dp)) { Text("Root shell") }
                }
                TextButton(onClick = { action("Refresh") { ChrootManager.refreshStatus(context, install); null } }, enabled = busy == null) {
                    Text("Refresh", color = ClaudeTerracotta)
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
                    Text("Delete this Ubuntu…", color = AppColors.danger)
                }
                if (!isActiveRuntime) TextButton(onClick = { ChrootManager.forget(context, install.path); onDeleted() }) {
                    Text("Forget (keep files)", color = AppColors.textSecondary, fontSize = 12.5.sp)
                }
            }
        }

        // ---------------- mounts
        ChrootSection("Mounts", "What is mounted inside it right now" + (s?.let { if (it.holderPid != null) " (in its private namespace)" else if (it.mounts.isNotEmpty()) " (in Android's namespace)" else "" } ?: ""),
            Icons.Outlined.ViewList, AppColors.accentIndigo, cardBg, cardBorder) {
            val mounts = s?.mounts.orEmpty()
            if (mounts.isEmpty()) Text("Nothing is mounted.", fontSize = 12.5.sp, color = AppColors.textSecondary)
            mounts.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.mountPoint.removePrefix(install.path).ifEmpty { "/ (rootfs bind)" }, fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        Text("${m.fsType} · ${m.source}${if (m.readOnly) " · read-only" else ""}", fontSize = 11.sp, color = AppColors.textSecondary)
                    }
                    if (m.mountPoint != install.path) TextButton(onClick = { confirmUnmount = m.mountPoint }, enabled = busy == null) {
                        Text("Unmount", color = AppColors.danger, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---------------- settings (chroot.conf), shared folders included
        val conf = s?.config
        if (conf != null && s.error == null) ChrootConfigSection(install, conf, cardBg, cardBorder, busy == null) { newConf ->
            action("Save settings") { ChrootManager.saveConfig(context, install, newConf) }
        }

        // ---------------- storage details
        ChrootSection("Storage", usage?.let { "${formatKb(it.totalKb)} in all" } ?: "Where its space goes", Icons.Outlined.Storage, AppColors.info, cardBg, cardBorder) {
            val u = usage
            if (u != null && u.totalKb > 0) {
                val parts = listOf("home" to "Home folders", "usr" to "Programs (/usr)", "var/lib/apt/lists" to "apt package lists",
                    "var/cache/apt" to "apt download cache", "opt" to "/opt", "tmp" to "/tmp", "root" to "Root's home")
                    .mapNotNull { (k, label) -> u.partsKb[k]?.takeIf { it > 0 }?.let { label to it } }
                val other = (u.totalKb - parts.sumOf { it.second }).coerceAtLeast(0)
                (parts + ("Everything else" to other)).sortedByDescending { it.second }.forEach { (label, kb) ->
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
            } else if (usageBusy) Text("Measuring…", fontSize = 12.5.sp, color = AppColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { measure() }, enabled = !usageBusy, shape = RoundedCornerShape(10.dp)) {
                    Text(if (usageBusy) "Measuring…" else "Measure again")
                }
                OutlinedButton(onClick = {
                    ChrootManager.runTask(context, "Cleaning the apt cache") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.CLEAN_SCRIPT, log) }
                }, shape = RoundedCornerShape(10.dp)) { Text("Clean apt cache") }
            }
        }

        // ---------------- backup & restore
        ChrootSection("Backup & restore", "One file with everything of this Ubuntu (owners and permissions kept). It never contains " +
            "/sdcard, USB drives, /proc or the shared folders.", Icons.Outlined.Backup, AppColors.info, cardBg, cardBorder) {
            ChrootManager.BackupFormat.entries.forEach { f ->
                ChrootRadioRow(f.label, null, backupFormat == f) { backupFormat = f }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val fileName = "${install.name}-$date.${backupFormat.ext}"
                    if (backupFormat == ChrootManager.BackupFormat.GZIP) gzLauncher.launch(fileName) else xzLauncher.launch(fileName)
                }, enabled = task?.running != true, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                    Text("Back up…")
                }
                OutlinedButton(onClick = { showRestore = true }, shape = RoundedCornerShape(10.dp)) { Text("Restore a backup…") }
            }
            ChrootNote("Backing up works while it runs. Restoring installs the backup as a new copy (pick its name and folder); it keeps " +
                "its user, home, settings and packages.")
            if (mounted && s?.holderPid == null) ChrootNote("Its mounts are in Android's namespace (global mode or left by an older launcher): Stop it before backing up.", AppColors.warning)
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
            Text("Clean image (to share)", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
            ChrootNote("Leaves out the home folders, logins and history, AntiGem's files, logs and the apt lists. Pick a folder: " +
                "${ai.antigem.data.local.chroot.ChrootInstaller.prebuiltAsset()} and its .sha256 are written there, ready for the GitHub release " +
                "${ai.antigem.data.local.chroot.ChrootInstaller.PREBUILT_TAG} that the \"AntiGem Ubuntu (prebuilt)\" source downloads. Slow (xz): " +
                "several minutes.")
            OutlinedButton(onClick = { imageLauncher.launch(null) }, enabled = task?.running != true, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(top = 6.dp)) { Text("Export a clean image…") }
        }

        // ---------------- packages
        ChrootPackagesSection(install, cardBg, cardBorder)

        // ---------------- user
        ChrootUserSection(install, cardBg, cardBorder)

        // ---------------- exit
        ChrootSection("When AntiGem exits", "Exit from the menu or the notification (the Exit dialog can override it once). Swiping the app away keeps everything running.",
            Icons.Outlined.ExitToApp, AppColors.warning, cardBg, cardBorder) {
            ChrootExitBehavior.entries.forEach { b ->
                ChrootRadioRow(b.label, b.description, exitBehavior == b) {
                    exitBehavior = b
                    ChrootManager.setExitBehavior(context, b)
                }
            }
        }

        // ---------------- processes
        ChrootSection("Processes", "$procs running in it", Icons.Outlined.Memory, AppColors.accentTeal, cardBg, cardBorder) {
            TextButton(onClick = { showProcesses = !showProcesses }) { Text(if (showProcesses) "Hide" else "Show", color = ClaudeTerracotta) }
            if (showProcesses) s?.processes.orEmpty().forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.pid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary, modifier = Modifier.width(56.dp))
                    Text(p.command, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.onBackground, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { action("Kill ${p.pid}") { ChrootManager.killProcess(context, p.pid, install) } }, enabled = busy == null) {
                        Text("Kill", color = AppColors.danger, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // ---------------- repair, about
        ChrootSection("Repair", "Sets up the network groups, the user (AntiGem's uid), DNS and the bridge again", Icons.Outlined.Build, AppColors.accentAlt, cardBg, cardBorder) {
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "Repairing ${install.name}") { log ->
                    val st = ChrootManager.start(context, install, withBridge = true, onLine = log)
                    if (!st.ok) return@runTask ChrootManager.SuResult(1, emptyList(), st.message)
                    log("== the bridge: ${if (st.bridgeUpdated) "updated" else "current"}")
                    ChrootManager.execAsRoot(context, install.path,
                        ChrootScripts.userScript(install.user, android.os.Process.myUid(), "", sudoNoPassword = true, keepSudoers = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text("Repair") }
        }
        ChrootSection("About this phone", null, Icons.Outlined.Info, AppColors.textSecondary, cardBg, cardBorder) {
            val p = probe
            ChrootKeyValue("Root", p?.rootManager ?: "…")
            ChrootKeyValue("Mount tools", p?.let { if (it.canPrivateNs) "unshare + nsenter (${it.busybox ?: "toybox"})" else "no unshare/nsenter" } ?: "…")
            ChrootKeyValue("SELinux", p?.selinux ?: "…")
            ChrootKeyValue("Kernel", p?.kernel ?: "…")
            ChrootKeyValue("Source", install.source)
            ChrootKeyValue("Control script", ChrootManager.ctlFile(context).absolutePath, mono = true)
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

    ChrootSection("Settings", "Saved into the rootfs (.antigem/chroot.conf); mounts change at the next start of the chroot",
        Icons.Outlined.Settings, ClaudeTerracotta, cardBg, cardBorder) {
        ChrootSwitchRow("Phone storage at /sdcard", null, sdcard) { sdcard = it }
        if (sdcard) ChrootSwitchRow("Read-only /sdcard", "Programs in the chroot cannot change or delete the phone's files", sdcardRo) { sdcardRo = it }
        if (sdcard) ChrootSwitchRow("USB drives and SD cards (/storage)",
            if (sdcardRo) "Off with a read-only /sdcard (the phone's storage would be writable through /storage)"
            else "Every volume at /storage/…, also drives plugged in while the chroot runs",
            storage && !sdcardRo, enabled = !sdcardRo) { storage = it }
        ChrootNote("USB devices for fastboot / adb / serial over OTG: /dev/bus/usb, usable by ${install.user} (Android's usb group; run Repair once on an older install).")
        ChrootSwitchRow("Automatic DNS", "The phone's DNS servers at every start", dnsAuto) { dnsAuto = it }
        if (!dnsAuto) OutlinedTextField(dns, { dns = it }, label = { Text("DNS servers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(shm, { shm = it.filter(Char::isDigit).take(5) }, label = { Text("/dev/shm size (MB)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        Text("Mount namespace", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
        ChrootRadioRow(ChrootNsMode.PRIVATE.label, "Mounts only inside the chroot's own namespace: nothing outside can reach /sdcard through the rootfs. USB drives plugged in later still show up.",
            ns == ChrootNsMode.PRIVATE) { ns = ChrootNsMode.PRIVATE }
        ChrootRadioRow(ChrootNsMode.GLOBAL.label, "Mounts visible to the whole phone until stopped. Advanced; not for installs in the app's storage.",
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
        ) { Text("Save settings") }
        if (ns != conf.nsMode) ChrootNote("The namespace changes after Stop everything → Start.", AppColors.warning)
        if (binds != conf.binds) ChrootNote("Save, then Stop everything → Start: shared folders are mounted when the chroot starts.", AppColors.warning)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootPackagesSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var bundles by remember { mutableStateOf(emptySet<ChrootPackageBundle>()) }
    var extra by remember { mutableStateOf("") }
    val names = extra.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    ChrootSection("Packages", "apt inside the chroot (as root)", Icons.Outlined.Inventory2, AppColors.success, cardBg, cardBorder) {
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
        OutlinedTextField(extra, { extra = it }, label = { Text("Packages (space separated)") }, modifier = Modifier.fillMaxWidth())
        val bad = names.filter { !ChrootScripts.isPackageName(it) }
        if (bad.isNotEmpty()) ChrootNote("Not package names: ${bad.joinToString(" ")}", AppColors.danger)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val pkgs = bundles.flatMap { it.packages } + names
                    val node = bundles.any { it.nodeJs }
                    ChrootManager.runTask(context, "Installing packages") { log ->
                        ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(pkgs, node, upgrade = false), log)
                    }
                    bundles = emptySet(); extra = ""
                },
                enabled = (bundles.isNotEmpty() || names.isNotEmpty()) && bad.isEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
            ) { Text("Install") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "Updating Ubuntu") { log ->
                    ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(emptyList(), nodeJs = false, upgrade = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text("Update & upgrade all") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootUserSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    ChrootSection("User ${install.user}", "Its uid is AntiGem's own (${android.os.Process.myUid()}), so files it shares with the app belong to both",
        Icons.Outlined.Person, AppColors.accentPink, cardBg, cardBorder) {
        OutlinedTextField(password, { password = it.replace("\n", "") }, label = { Text("New password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val p = password
                password = ""
                ChrootManager.runTask(context, "Changing the password") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.passwordScript(install.user, p), log) }
            }, enabled = password.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("Set password") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "sudo without a password") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, true), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text("sudo: no password") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "sudo asks for the password") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, false), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text("sudo: ask password") }
        }
        ChrootNote("\"sudo: ask password\" needs a password set first.")
    }
}

private fun formatAge(sinceEpochSec: Long): String {
    val secs = (System.currentTimeMillis() / 1000 - sinceEpochSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60} min"
        secs < 86400 -> "${secs / 3600} h ${(secs % 3600) / 60} min"
        else -> "${secs / 86400} d ${(secs % 86400) / 3600} h"
    }
}

/** Shown instead of a Termux-only page while the Ubuntu chroot is the phone's runtime. */
@Composable
fun TermuxOnlyNotice(title: String, cardBg: Color, cardBorder: BorderStroke, note: String? = null, onOpenEnvironment: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ChrootSection("$title works with AntiGem's built-in environment", "This phone runs the Ubuntu chroot right now. " +
            "$title still uses the tools of AntiGem's built-in environment, so it is off here for now.", Icons.Outlined.Info, AppColors.warning, cardBg, cardBorder) {
            if (note != null) ChrootNote(note, AppColors.info)
            OutlinedButton(onClick = onOpenEnvironment, shape = RoundedCornerShape(10.dp)) { Text("Where agents run") }
        }
    }
}
