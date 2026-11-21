package ai.antigem.ui.settings.chroot

import ai.antigem.data.local.chroot.ChrootBind
import ai.antigem.data.local.chroot.ChrootConfig
import ai.antigem.data.local.chroot.ChrootDnsMode
import ai.antigem.data.local.chroot.ChrootFound
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootInstallRequest
import ai.antigem.data.local.chroot.ChrootInstallStep
import ai.antigem.data.local.chroot.ChrootInstaller
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootNsMode
import ai.antigem.data.local.chroot.ChrootPackageBundle
import ai.antigem.data.local.chroot.ChrootScripts
import ai.antigem.data.local.chroot.ChrootSource
import ai.antigem.data.local.chroot.UbuntuRelease
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** The full-page Ubuntu chroot setup, shown over whatever opened it (first-launch installer, Settings). */
@Composable
fun ChrootSetupDialog(onDismiss: () -> Unit, restore: Boolean = false, onDone: (ChrootInstall) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.background) {
            Box(Modifier.systemBarsPadding().imePadding()) {
                // the normal build has no Linux bridge for the chroot: every way in (first-launch installer, Settings)
                // lands on the page that installs the chroot build instead
                if (ai.antigem.BuildConfig.HAS_CHROOT) ChrootSetupScreen(onClose = onDismiss, restore = restore, onDone = onDone)
                else ChrootBuildScreen(onClose = onDismiss)
            }
        }
    }
}

private enum class SourceChoice { PREBUILT, OFFICIAL_2404, OFFICIAL_2604, OFFICIAL_2204, URL, FILE }
private enum class LocationChoice { DEFAULT, APP, CUSTOM }

@Composable
fun ChrootSetupScreen(
    onClose: () -> Unit,
    /** Restoring a backup: a file from the phone, installed as a new copy next to the others (not switched to). */
    restore: Boolean = false,
    onDone: (ChrootInstall) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ChrootInstaller.state.collectAsState()
    val logs by ChrootInstaller.logs.collectAsState()
    val probe by ChrootManager.probe.collectAsState()
    val found by ChrootManager.found.collectAsState()
    val installs by ChrootManager.installs.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var prebuiltAvailable by remember { mutableStateOf<Boolean?>(null) }

    var source by remember { mutableStateOf(if (restore) SourceChoice.FILE else SourceChoice.OFFICIAL_2404) }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }

    var location by remember { mutableStateOf(LocationChoice.DEFAULT) }
    var name by remember { mutableStateOf(if (restore) "ubuntu-restored" else "ubuntu-24.04") }
    var nameEdited by remember { mutableStateOf(restore) }
    var customPath by remember { mutableStateOf("${ChrootManager.BASE_DIR}/ubuntu") }

    var user by remember { mutableStateOf("ubuntu") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var sudoNoPassword by remember { mutableStateOf(true) }

    var mountSdcard by remember { mutableStateOf(true) }
    var sdcardRo by remember { mutableStateOf(false) }
    var mountStorage by remember { mutableStateOf(true) }
    var dnsAuto by remember { mutableStateOf(true) }
    var dnsCustom by remember { mutableStateOf("1.1.1.1, 8.8.8.8") }
    var nsMode by remember { mutableStateOf(ChrootNsMode.PRIVATE) }
    var sharedFolders by remember { mutableStateOf(emptyList<ChrootBind>()) }

    var bundles by remember { mutableStateOf(setOf(ChrootPackageBundle.PYTHON, ChrootPackageBundle.NODE)) }
    var extraPackages by remember { mutableStateOf("") }

    var adoptTarget by remember { mutableStateOf<ChrootFound?>(null) }
    var adoptBusy by remember { mutableStateOf(false) }
    var adoptError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri
            fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            source = SourceChoice.FILE
        }
    }

    fun runCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val p = ChrootManager.probe(context)
            if (p.rootOk) {
                ChrootManager.scan(context)
                if (!p.canPrivateNs) nsMode = ChrootNsMode.GLOBAL
            }
            checking = false
        }
    }

    // restoring: the backup file is the first thing to pick
    LaunchedEffect(Unit) { if (restore && fileUri == null) filePicker.launch(arrayOf("*/*")) }
    LaunchedEffect(Unit) {
        if (!state.running && state.result == null && state.error == null) ChrootInstaller.reset()
        runCheck()
        prebuiltAvailable = ChrootInstaller.prebuiltAvailable()
    }

    // the release decides the default name
    LaunchedEffect(source) {
        if (!nameEdited) {
            name = when (source) {
                SourceChoice.OFFICIAL_2604 -> "ubuntu-26.04"
                SourceChoice.OFFICIAL_2204 -> "ubuntu-22.04"
                SourceChoice.PREBUILT, SourceChoice.OFFICIAL_2404 -> "ubuntu-24.04"
                else -> "linux"
            }
        }
    }

    val path = when (location) {
        LocationChoice.DEFAULT -> "${ChrootManager.BASE_DIR}/$name"
        LocationChoice.APP -> ChrootManager.appStorageDir(context, name)
        LocationChoice.CUSTOM -> customPath.trim().trimEnd('/')
    }
    val inAppStorage = ChrootManager.isInAppStorage(context, path)

    // the screen stays on while installing
    // inside a Dialog the context is a wrapper: find the Activity behind it
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    }
    DisposableEffect(state.running) {
        val w = activity?.window
        if (state.running) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val installing = state.running || state.result != null || state.error != null
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppColors.onBackground) }
            Column(Modifier.weight(1f)) {
                Text(if (restore) "Restore an Ubuntu backup" else "Ubuntu chroot (root)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.onBackground)
                Text("A real Ubuntu on this phone for the bridge, the agents and the terminal", fontSize = 11.5.sp, color = AppColors.textSecondary)
            }
        }
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = chrootColumnSpacing
        ) {
            if (installing) {
                InstallProgress(state.step, state.progress, state.detail, state.done, state.error, state.result != null, logs)
            } else {
                // ---------------- the phone
                ChrootSection("This phone", "Root, the mount tools and free space", Icons.Outlined.Search) {
                    when {
                        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                            Spacer(Modifier.width(10.dp))
                            Text("Checking (accept the root manager's prompt)…", fontSize = 12.5.sp, color = AppColors.onBackground)
                        }
                        probe == null -> Text("Not checked yet", fontSize = 12.5.sp, color = AppColors.textSecondary)
                        probe?.rootOk != true -> {
                            Text(probe?.error ?: "No root access", fontSize = 12.5.sp, color = AppColors.danger)
                        }
                        else -> {
                            val p = probe!!
                            ChrootKeyValue("Root", p.rootManager ?: "granted", AppColors.success)
                            ChrootKeyValue("CPU", "${p.machine ?: "?"} · kernel ${p.kernel ?: "?"}")
                            ChrootKeyValue("SELinux", p.selinux ?: "?")
                            ChrootKeyValue("Mount tools", if (p.canPrivateNs) "unshare + nsenter (${p.busybox ?: "toybox"})" else "no unshare/nsenter: global namespace only",
                                if (p.canPrivateNs) AppColors.success else AppColors.warning)
                            val free = runCatching { android.os.StatFs("/data").availableBytes }.getOrDefault(-1L)
                            if (free >= 0) ChrootKeyValue("Free on /data", formatBytes(free), if (free < (2L shl 30)) AppColors.warning else AppColors.onBackground)
                        }
                    }
                    if (!checking) {
                        TextButton(onClick = { runCheck() }) { Text(if (probe == null) "Check" else "Check again", color = ClaudeTerracotta) }
                    }
                }

                // ---------------- installs already on the phone
                val foundList = found.orEmpty()
                if (foundList.isNotEmpty()) {
                    ChrootSection("Found on this phone", "Use an install that is already here instead of downloading a new one", Icons.Outlined.Storage) {
                        foundList.forEach { f ->
                            val known = installs.firstOrNull { it.path == f.path }
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.os.ifBlank { "Linux" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground, modifier = Modifier.weight(1f))
                                    if (f.running) ChrootTag("running", AppColors.success)
                                    else if (f.mounted) ChrootTag("mounted, nothing running", AppColors.warning)
                                    if (!f.hasManifest) { Spacer(Modifier.width(4.dp)); ChrootTag("not AntiGem's yet", AppColors.warning) }
                                }
                                Text("${f.path} · ${f.arch}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    if (f.hasManifest) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val inst = known ?: f.toInstall()
                                                    ChrootManager.addOrUpdate(context, inst)
                                                    ChrootManager.activate(context, inst)
                                                    onDone(inst)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
                                        ) { Text("Use this", fontSize = 12.5.sp) }
                                    } else {
                                        Button(
                                            onClick = { adoptTarget = f; adoptError = null },
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
                                        ) { Text("Adopt…", fontSize = 12.5.sp) }
                                    }
                                }
                                if (adoptTarget?.path == f.path) {
                                    AdoptPanel(f, adoptBusy, adoptError) { u ->
                                        adoptBusy = true
                                        adoptError = null
                                        scope.launch {
                                            val r = ChrootManager.adopt(context, f, u)
                                            adoptBusy = false
                                            r.onSuccess { inst ->
                                                ChrootManager.activate(context, inst)
                                                adoptTarget = null
                                                onDone(inst)
                                            }.onFailure { adoptError = it.message }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f))
                        }
                    }
                }

                // ---------------- source
                ChrootSection("Source", "Where the Ubuntu rootfs comes from", Icons.Outlined.Cloud) {
                    ChrootRadioRow(
                        "AntiGem Ubuntu (prebuilt)",
                        "Ubuntu 24.04 with sudo, git, curl, Node.js, Python and the Android fixes already set up: the quickest start.",
                        selected = source == SourceChoice.PREBUILT,
                        enabled = prebuiltAvailable == true,
                        tag = when (prebuiltAvailable) { true -> "recommended"; false -> "not published yet"; null -> "checking…" },
                        tagColor = if (prebuiltAvailable == true) AppColors.success else AppColors.textSecondary
                    ) { source = SourceChoice.PREBUILT }
                    ChrootRadioRow(UbuntuRelease.NOBLE.label, "Official Ubuntu Base from cdimage.ubuntu.com (newest point release, checksum checked) · ~30 MB",
                        source == SourceChoice.OFFICIAL_2404, tag = "LTS") { source = SourceChoice.OFFICIAL_2404 }
                    ChrootRadioRow(UbuntuRelease.RESOLUTE.label, "Official Ubuntu Base, the newest LTS", source == SourceChoice.OFFICIAL_2604) { source = SourceChoice.OFFICIAL_2604 }
                    ChrootRadioRow(UbuntuRelease.JAMMY.label, "Official Ubuntu Base, the older LTS", source == SourceChoice.OFFICIAL_2204) { source = SourceChoice.OFFICIAL_2204 }
                    ChrootRadioRow("Another URL", "Any rootfs .tar.gz / .tar.xz (Debian, Kali, Arch ARM …)", source == SourceChoice.URL) { source = SourceChoice.URL }
                    if (source == SourceChoice.URL) {
                        OutlinedTextField(url, { url = it }, label = { Text("Rootfs URL") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(sha, { sha = it }, label = { Text("sha256 (optional)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    ChrootRadioRow("A file on this phone", "An AntiGem backup (keeps its user, home, settings and packages), or any rootfs .tar.gz / .tar.xz", source == SourceChoice.FILE) {
                        if (fileUri == null) filePicker.launch(arrayOf("*/*")) else source = SourceChoice.FILE
                    }
                    if (source == SourceChoice.FILE) {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (fileUri == null) "Choose a file" else fileName.ifBlank { "Chosen" }, fontSize = 12.5.sp)
                        }
                    }
                }

                // ---------------- location
                ChrootSection("Location", "The rootfs needs Linux permissions, so it lives on /data (never on /sdcard)", Icons.Outlined.Storage) {
                    ChrootRadioRow("${ChrootManager.BASE_DIR}/<name>", "Stays when AntiGem is uninstalled or its data is cleared, and is found again after a reinstall.",
                        location == LocationChoice.DEFAULT, tag = "recommended") { location = LocationChoice.DEFAULT }
                    ChrootRadioRow("AntiGem's app storage", "Removed together with the app (uninstall or Clear data). Private mount namespace only.",
                        location == LocationChoice.APP) { location = LocationChoice.APP; nsMode = ChrootNsMode.PRIVATE }
                    ChrootRadioRow("Another folder on /data", null, location == LocationChoice.CUSTOM) { location = LocationChoice.CUSTOM }
                    if (location == LocationChoice.CUSTOM) {
                        OutlinedTextField(customPath, { customPath = it }, label = { Text("Folder") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    } else {
                        OutlinedTextField(name, { name = it.trim(); nameEdited = true }, label = { Text("Name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    ChrootNote("Installs into: $path")
                    val pathError = ChrootManager.validateRootPath(path)
                        ?: if (location != LocationChoice.CUSTOM && !ChrootManager.isInstallName(name)) "The name may use letters, digits, . _ and -." else null
                    if (pathError != null) ChrootNote(pathError, AppColors.danger)
                    if (installs.any { it.path == path }) ChrootNote("AntiGem already has an install there: pick another name.", AppColors.danger)
                }

                // ---------------- user
                ChrootSection("User", "The normal user the bridge, the agents and the terminal run as (it gets AntiGem's own uid)", Icons.Outlined.Person) {
                    if (source == SourceChoice.FILE) ChrootNote("Restoring an AntiGem backup keeps the backup's own user (and its settings): these fields are used for other rootfs files.", AppColors.info)
                    OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("User name") }, singleLine = true,
                        isError = !ChrootManager.isUserName(user), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        password, { password = it.replace("\n", "") }, label = { Text("Password (optional)") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    )
                    ChrootSwitchRow("sudo without a password", "Agents can install packages without asking (recommended for agents)",
                        sudoNoPassword) { sudoNoPassword = it }
                    if (!sudoNoPassword && password.isBlank()) ChrootNote("Without a password sudo could never be used: set one, or keep this on.", AppColors.danger)
                }

                // ---------------- options
                ChrootSection("Options", "All of these can be changed later on the chroot's own page", Icons.Outlined.Settings) {
                    ChrootSwitchRow("Phone storage at /sdcard", "Your files (Download, DCIM …) inside the chroot", mountSdcard) { mountSdcard = it }
                    if (mountSdcard) ChrootSwitchRow("Read-only", "Programs in the chroot can read the phone's files but never change or delete them", sdcardRo) { sdcardRo = it }
                    if (mountSdcard) ChrootSwitchRow("USB drives and SD cards (/storage)",
                        if (sdcardRo) "Off with a read-only /sdcard (the phone's storage would be writable through /storage)"
                        else "Every volume at /storage/…, also drives plugged in after the chroot started",
                        mountStorage && !sdcardRo, enabled = !sdcardRo) { mountStorage = it }
                    ChrootNote("USB devices (fastboot, adb, serial adapters over OTG) are always reachable at /dev/bus/usb; the user is in Android's usb group.")
                    ChrootSwitchRow("Automatic DNS", "Uses the phone's DNS servers at every start (custom servers otherwise)", dnsAuto) { dnsAuto = it }
                    if (!dnsAuto) {
                        OutlinedTextField(dnsCustom, { dnsCustom = it }, label = { Text("DNS servers (comma separated)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Mount namespace", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    ChrootRadioRow(ChrootNsMode.PRIVATE.label,
                        "The chroot's mounts (/dev, /proc, /sdcard …) exist only for its own processes. Nothing outside can reach your files through the rootfs, even a root file manager deleting it. USB drives plugged in later still show up.",
                        nsMode == ChrootNsMode.PRIVATE, enabled = probe?.canPrivateNs != false) { nsMode = ChrootNsMode.PRIVATE }
                    ChrootRadioRow(ChrootNsMode.GLOBAL.label,
                        "Mounts visible to the whole phone until stopped (like Linux Deploy). For advanced users: deleting the rootfs folder with another tool while it runs would go into /sdcard.",
                        nsMode == ChrootNsMode.GLOBAL, enabled = !inAppStorage) { nsMode = ChrootNsMode.GLOBAL }
                    Spacer(Modifier.height(10.dp))
                    ChrootFoldersEditor(user = user, binds = sharedFolders) { sharedFolders = it }
                }

                // ---------------- packages
                ChrootSection("Packages", "Installed with apt right after the rootfs (more can be added later)", Icons.Outlined.Inventory2) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(true, null, enabled = false)
                        Column(Modifier.weight(1f)) {
                            Text(ChrootPackageBundle.ESSENTIALS.title + " (always)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                            Text(ChrootPackageBundle.ESSENTIALS.description, fontSize = 11.sp, color = AppColors.textSecondary)
                        }
                    }
                    ChrootPackageBundle.entries.filter { it != ChrootPackageBundle.ESSENTIALS }.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Checkbox(b in bundles, { on -> bundles = if (on) bundles + b else bundles - b },
                                colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                                Text(b.description, fontSize = 11.sp, color = AppColors.textSecondary)
                            }
                        }
                    }
                    OutlinedTextField(extraPackages, { extraPackages = it }, label = { Text("More packages (space separated)") },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    val bad = extraPackages.split(Regex("[\\s,]+")).filter { it.isNotBlank() && !ChrootScripts.isPackageName(it) }
                    if (bad.isNotEmpty()) ChrootNote("Not package names: ${bad.joinToString(" ")}", AppColors.danger)
                }
            }
        }

        // ---------------- actions
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.result != null -> Button(
                    onClick = { val r = state.result!!; ChrootInstaller.reset(); onDone(r) },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) { Text("Done", fontWeight = FontWeight.SemiBold) }
                state.running -> {
                    val canCancel = state.step == null || state.step!!.ordinal <= ChrootInstallStep.VERIFY.ordinal
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("Continue in background")
                        }
                        if (canCancel) OutlinedButton(onClick = { ChrootInstaller.cancel() }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("Cancel", color = AppColors.danger)
                        }
                    }
                }
                state.error != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ChrootInstaller.retry(context) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) { Text("Retry") }
                    OutlinedButton(onClick = { ChrootInstaller.reset() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("Change options")
                    }
                }
                else -> {
                    val extra = extraPackages.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                    val src: ChrootSource? = when (source) {
                        SourceChoice.PREBUILT -> ChrootSource.Prebuilt
                        SourceChoice.OFFICIAL_2404 -> ChrootSource.Official(UbuntuRelease.NOBLE)
                        SourceChoice.OFFICIAL_2604 -> ChrootSource.Official(UbuntuRelease.RESOLUTE)
                        SourceChoice.OFFICIAL_2204 -> ChrootSource.Official(UbuntuRelease.JAMMY)
                        SourceChoice.URL -> url.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { ChrootSource.Url(it, sha.trim().ifBlank { null }) }
                        SourceChoice.FILE -> fileUri?.let { ChrootSource.LocalFile(it, fileName) }
                    }
                    val dnsList = dnsCustom.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                    val problem = when {
                        probe?.rootOk != true -> "Root access is needed (Check above)."
                        src == null -> if (source == SourceChoice.FILE) "Choose a file." else "Enter an http(s) URL."
                        ChrootManager.validateRootPath(path) != null -> ChrootManager.validateRootPath(path)
                        location != LocationChoice.CUSTOM && !ChrootManager.isInstallName(name) -> "Check the name."
                        installs.any { it.path == path } -> "That folder is already an install."
                        !ChrootManager.isUserName(user) -> "Check the user name."
                        !sudoNoPassword && password.isBlank() -> "Set a password, or keep sudo without a password."
                        !dnsAuto && (dnsList.isEmpty() || dnsList.any { !ChrootManager.isDnsServer(it) }) -> "Check the DNS servers."
                        extra.any { !ChrootScripts.isPackageName(it) } -> "Check the package names."
                        else -> null
                    }
                    if (problem != null) Text(problem, fontSize = 12.sp, color = AppColors.warning)
                    Button(
                        onClick = {
                            // shared folders added for another user name follow the one chosen now
                            val binds = sharedFolders.map { b ->
                                val m = Regex("^/home/[^/]+(/.*)$").find(b.target)
                                if (m != null && !b.target.startsWith("/home/$user/")) b.copy(target = "/home/$user${m.groupValues[1]}") else b
                            }
                            ChrootInstaller.start(
                                context,
                                ChrootInstallRequest(
                                    source = src!!, path = path,
                                    name = if (location == LocationChoice.CUSTOM) path.substringAfterLast('/') else name,
                                    user = user, password = password, sudoNoPassword = sudoNoPassword,
                                    config = ChrootConfig(
                                        nsMode = nsMode, mountSdcard = mountSdcard, sdcardReadOnly = sdcardRo, mountStorage = mountStorage,
                                        dnsMode = if (dnsAuto) ChrootDnsMode.AUTO else ChrootDnsMode.CUSTOM,
                                        customDns = if (dnsAuto) emptyList() else dnsList, binds = binds
                                    ),
                                    bundles = bundles, extraPackages = extra,
                                    // a restored copy sits next to the others; switch to it in Where agents run
                                    activate = !restore
                                )
                            )
                        },
                        enabled = problem == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) { Text("Install Ubuntu chroot", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun AdoptPanel(found: ChrootFound, busy: Boolean, error: String?, onAdopt: (String) -> Unit) {
    var user by remember { mutableStateOf(found.legacyUser ?: "ubuntu") }
    Surface(shape = RoundedCornerShape(12.dp), color = AppColors.surface, border = BorderStroke(1.dp, AppColors.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("AntiGem writes its manifest and settings into this rootfs and gives its user AntiGem's uid " +
                "(files in that user's home move with it). Its running processes are stopped first.",
                fontSize = 11.5.sp, lineHeight = 15.sp, color = AppColors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("User") }, singleLine = true,
                isError = !ChrootManager.isUserName(user), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            if (error != null) ChrootNote(error, AppColors.danger)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onAdopt(user) }, enabled = !busy && ChrootManager.isUserName(user),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onBackground)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Adopting…" else "Adopt and use", fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun InstallProgress(
    step: ChrootInstallStep?,
    progress: Float?,
    detail: String,
    done: Set<ChrootInstallStep>,
    error: String?,
    finished: Boolean,
    logs: List<String>
) {
    ChrootSection(
        when { finished -> "Ubuntu chroot is ready"; error != null -> "Installation stopped"; else -> "Installing…" },
        if (finished) "The bridge and the terminal use it from now on (switch in Settings → Where agents run)" else null,
        if (finished) Icons.Filled.CheckCircle else if (error != null) Icons.Filled.Error else Icons.Outlined.Inventory2,
        tint = if (finished) AppColors.success else if (error != null) AppColors.danger else ClaudeTerracotta
    ) {
        ChrootInstallStep.entries.forEach { s ->
            val isCurrent = s == step && !finished && error == null
            val isDone = s in done
            val failed = error != null && s == step
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        isDone -> Icon(Icons.Filled.CheckCircle, null, tint = AppColors.success, modifier = Modifier.size(18.dp))
                        failed -> Icon(Icons.Filled.Error, null, tint = AppColors.danger, modifier = Modifier.size(18.dp))
                        isCurrent -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                        else -> Text("·", color = AppColors.textSecondary)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(s.label, fontSize = 13.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isDone || isCurrent) AppColors.onBackground else AppColors.textSecondary)
            }
            if (isCurrent) {
                if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = ClaudeTerracotta,
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp, bottom = 2.dp))
                if (detail.isNotBlank()) Text(detail, fontSize = 11.5.sp, color = AppColors.textSecondary, maxLines = 2,
                    modifier = Modifier.padding(start = 30.dp))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.5.sp, lineHeight = 17.sp, color = AppColors.danger)
        }
    }
    ChrootSection("Log", null) { ChrootLog(logs, maxHeight = 320) }
}
