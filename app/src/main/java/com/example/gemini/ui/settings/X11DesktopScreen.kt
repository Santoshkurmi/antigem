package com.example.gemini.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.BuildConfig
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.x11.X11DesktopManager
import com.example.gemini.data.x11.X11DesktopManager.Browser
import com.example.gemini.data.x11.X11DesktopManager.Desktop
import com.example.gemini.data.x11.X11DesktopManager.Task
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay

/** Settings → X11 Desktop: installs Termux:X11, its termux-x11 command and a desktop, then starts it. */
@Composable
fun X11DesktopScreen(cardBg: Color, cardBorder: BorderStroke, onOpenAdbConnect: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        X11DesktopManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        // The installer, the uninstaller and the terminal change things while the user is away; keep them live
        while (true) {
            if (X11DesktopManager.state.value.task == null) X11DesktopManager.refresh()
            delay(1500)
        }
    }
    val state by X11DesktopManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val shared = BuildConfig.IS_SHARED_USER_ID
    val busy = state.task != null

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IntroCard(shared, state.running, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = X11DesktopManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        if (!shared) {
            SharedUidRequiredCard("The X11 desktop", cardBg, cardBorder)
            return@Column
        }
        if (!state.ready) {
            Hint("Checking…")
            return@Column
        }
        if (!state.bootstrapInstalled) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Install the Linux environment first", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Hint("The desktop runs in AntiGem's terminal. Install it in Settings → Terminal & Shell, then come back here.")
                }
            }
            return@Column
        }

        // Step 1: the Termux:X11 app (shows the desktop)
        val app = state.app
        StepCard(
            1, "Termux:X11 app",
            when {
                app == null -> null
                state.appOk -> "Installed"
                else -> "Wrong build"
            },
            cardBg, cardBorder,
            badgeColor = if (state.appOk) QuotaGreen else Amber
        ) {
            when {
                app == null -> {
                    Hint("AntiGem's own build of Termux:X11, the app that shows the desktop. It runs inside AntiGem, so Android keeps the desktop running at full speed while you use it.")
                    Button(enabled = !busy, onClick = X11DesktopManager::installApp) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download & install")
                    }
                }
                state.appOk -> {
                    Mono(
                        "Termux:X11 ${app.versionName ?: ""}".trim() + "\n" + when {
                            app.sameProcess -> "AntiGem's build, runs inside AntiGem"
                            app.antigemBuild -> "AntiGem's build"
                            else -> "Official build"
                        }
                    )
                    OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installApp) { Text("Update") }
                }
                else -> {
                    Text(
                        if (app.antigemBuild) "This Termux:X11 doesn't share AntiGem's UID, so it can't run inside AntiGem. Uninstall it, then install it again."
                        else "The official Termux:X11 is installed. It can't run inside AntiGem, and Android won't install AntiGem's build over it (they are signed differently). Uninstall it first, then install AntiGem's build.",
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error
                    )
                    Button(enabled = !busy, onClick = { X11DesktopManager.uninstallApp(context) }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Uninstall Termux:X11")
                    }
                }
            }
            TextButton(
                onClick = { openUrl(context, X11DesktopManager.FORK_RELEASES_PAGE) },
                contentPadding = PaddingValues(0.dp)
            ) { Text("Open the download page", fontSize = 12.5.sp) }
            TaskOutput(state, Task.APP)
        }

        // Step 2: the companion package (the termux-x11 command)
        StepCard(
            2, "termux-x11 command",
            when {
                state.companionOk -> "Installed"
                state.companion != null -> "Wrong package"
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.companionOk) QuotaGreen else Amber
        ) {
            Hint("The terminal part: the termux-x11 command that starts the X server. ${state.neededCompanion} goes with ${if (app == null) "the Termux:X11 above" else "the installed Termux:X11"}.")
            if (state.companion != null && !state.companionOk) {
                Text(
                    "${state.companion} is installed, but this Termux:X11 needs ${state.neededCompanion}. Installing replaces it.",
                    fontSize = 12.5.sp, color = Amber
                )
            }
            if (!state.companionOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installCompanion) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install ${state.neededCompanion}")
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installCompanion) { Text("Reinstall") }
            }
            TaskOutput(state, Task.COMPANION)
        }

        // Step 3: a desktop
        StepCard(3, "Desktop", if (state.desktopOk) "Installed" else null, cardBg, cardBorder) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Desktop.entries.forEach { d ->
                    FilterChip(
                        selected = state.desktop == d,
                        enabled = !busy && !state.running,
                        onClick = { X11DesktopManager.setDesktop(d) },
                        label = { Text(if (d in state.installedDesktops) "${d.label} ✓" else d.label) }
                    )
                }
            }
            Hint(
                when (state.desktop) {
                    Desktop.XFCE -> "XFCE is a full, light desktop and a good place to start."
                    Desktop.LXQT -> "LXQt is a light Qt desktop."
                } + " It comes from x11-repo and is a large download."
            )
            if (!state.desktopOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installDesktop) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install ${state.desktop.label}")
                }
            }
            TaskOutput(state, Task.DESKTOP)
        }

        // Step 4: start / open / stop
        StepCard(4, "Start the desktop", if (state.running) "Running" else null, cardBg, cardBorder) {
            if (state.running) {
                Hint("The X server is running on display :0. Programs started from the terminal with DISPLAY=:0 show up on the desktop.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { X11DesktopManager.open(context) }) {
                        Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open desktop")
                    }
                    OutlinedButton(enabled = !busy, onClick = { X11DesktopManager.stop(context) }) { Text("Stop") }
                }
            } else {
                if (!state.canStart) Hint("Finish the steps above first.")
                Button(enabled = !busy && state.canStart, onClick = { X11DesktopManager.start(context) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Start ${state.desktop.label}")
                }
            }
            TaskOutput(state, Task.START)
        }

        // Web Browser: Chromium or Firefox from tur-repo
        val browserInstalled = state.browser in state.installedBrowsers
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Web Browser", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (browserInstalled) {
                        Pill("Installed", QuotaGreen)
                    } else {
                        Pill("Optional", MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Browser.entries.forEach { b ->
                        FilterChip(
                            selected = state.browser == b,
                            enabled = !busy,
                            onClick = { X11DesktopManager.setBrowser(b) },
                            label = { Text(if (b in state.installedBrowsers) "${b.label} ✓" else b.label) }
                        )
                    }
                }
                Hint("Install a browser from tur-repo to browse the web on your desktop.")
                if (!state.desktopOk) {
                    Hint("Install a desktop above first.")
                }
                if (!browserInstalled) {
                    Button(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install ${state.browser.label}")
                    }
                } else {
                    OutlinedButton(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Text("Reinstall ${state.browser.label}")
                    }
                }
                TaskOutput(state, Task.BROWSER)
            }
        }

        // Optional: Android 12+ kills apps' extra processes past a limit, and a desktop starts many
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("If the desktop closes by itself", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill("Optional", MaterialTheme.colorScheme.primary)
                    }
                    Hint("Android 12 and newer stop the extra processes of apps once there are more than 32 in total. A desktop starts many, so Android may close it. You can turn this limit off once, over ADB. It stays off until you turn it back on or reset the phone.")
                    val device = adb.connectedDevice
                    if (device != null) {
                        Button(enabled = !busy, onClick = { X11DesktopManager.disableChildProcessLimit(device.serial) }) { Text("Turn off the limit") }
                    } else {
                        Hint("Connect the terminal's adb to this phone first.")
                        OutlinedButton(onClick = onOpenAdbConnect) { Text("Set up ADB Connect") }
                    }
                    TaskOutput(state, Task.CHILD_LIMIT)
                }
            }
        }

        ManualSetupCard(state.desktop, cardBg, cardBorder)
    }
}

@Composable
private fun IntroCard(shared: Boolean, running: Boolean, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Linux desktop", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (running) Pill("Running", QuotaGreen)
            }
            Hint("Run Linux desktop apps (a browser, an IDE, office apps) from AntiGem's terminal. Termux:X11 shows the desktop.")
            if (shared) {
                Text(
                    "The desktop runs inside AntiGem, so Android won't slow it down or stop it while you use it.",
                    fontSize = 12.sp, color = QuotaGreen
                )
            }
        }
    }
}

/** Progress and the log of [task], shown in its own step while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: X11DesktopManager.State, task: Task) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: "Starting…")
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

@Composable
private fun ManualSetupCard(desktop: Desktop, cardBg: Color, cardBorder: BorderStroke) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    val start = "termux-x11 :0 -xstartup \"${desktop.startCommand}\""
    val commands = listOf(
        "1. Install antigem-termux-x11-sharedUid.apk and download antigem-termux-x11-*-all.deb from ${X11DesktopManager.FORK_RELEASES_PAGE}" to null,
        "2. Enable x11-repo" to "pkg install x11-repo",
        "3. Install the termux-x11 command (in the folder with the .deb)" to "apt install ./antigem-termux-x11-*-all.deb",
        "4. Install a desktop" to "pkg install dbus ${desktop.packages}",
        "5. Start it, then open Termux:X11" to start
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set up manually", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show", fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint("Prefer the terminal? These are the same steps as commands.")
                return@Column
            }
            commands.forEach { (label, command) ->
                Hint(label)
                if (command != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Mono(command) }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                            Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp))
                        }
                    }
                }
            }
            Hint("Don't install termux-x11-nightly from x11-repo: it only works with the official Termux:X11, not AntiGem's build.")
        }
    }
}

private const val ANTIGEM_RELEASES = "https://github.com/Santoshkurmi/antigem/releases/latest"
private const val ANTIGEM_README_SHARED_UID = "https://github.com/Santoshkurmi/antigem#termux-compatibility"

/**
 * Shown on the standalone com.termux build in place of a feature that needs a companion app sharing
 * AntiGem's UID: what the Shared UID build is and how to switch to it.
 */
@Composable
internal fun SharedUidRequiredCard(feature: String, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = Amber, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Needs the Shared UID build", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("Standalone", Amber)
            }
            Text(
                "This is the standalone build of AntiGem. $feature needs the Shared UID build, which shares AntiGem's Linux user with its companion app so they can work together.",
                fontSize = 12.5.sp
            )
            Text("To switch:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "Back up what you want to keep (Settings → Backups & Restore). Uninstalling deletes AntiGem's data, including the terminal and its files.",
                "Uninstall this AntiGem. Android can't switch an installed app to a shared UID with an update.",
                "Download and install the Shared UID build (antiGem-termux-shared-….apk) from AntiGem's releases.",
                "Read the README's Termux compatibility section for the details."
            ).forEachIndexed { i, step -> Hint("${i + 1}. $step") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, ANTIGEM_RELEASES) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Releases")
                }
                OutlinedButton(onClick = { openUrl(context, ANTIGEM_README_SHARED_UID) }) { Text("Read the README") }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
