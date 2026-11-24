package ai.antigem.ui.settings

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
import ai.antigem.BuildConfig
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.x11.X11DesktopManager
import ai.antigem.data.x11.X11DesktopManager.Browser
import ai.antigem.data.x11.X11DesktopManager.Desktop
import ai.antigem.data.x11.X11DesktopManager.Task
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import ai.antigem.i18n.L

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
    val busy = state.task != null

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IntroCard(state.running, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = X11DesktopManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(L.common.checking)
            return@Column
        }
        if (!state.bootstrapInstalled) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L.settings.x11Desktop.installTheLinuxEnvironmentFirst, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Hint(L.settings.x11Desktop.theDesktopRunsInAntigemsTerminal)
                }
            }
            return@Column
        }

        // Step 1: the Termux:X11 app (shows the desktop)
        val app = state.app
        StepCard(
            1, L.settings.x11Desktop.termuxX11App,
            when {
                app == null -> null
                state.appOk -> L.common.installed
                else -> L.settings.x11Desktop.wrongBuild
            },
            cardBg, cardBorder,
            badgeColor = if (state.appOk) QuotaGreen else Amber
        ) {
            when {
                app == null -> {
                    Hint(L.settings.x11Desktop.antigemsOwnBuildOfTermuxX11)
                    Button(enabled = !busy, onClick = X11DesktopManager::installApp) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.downloadInstall)
                    }
                }
                state.appOk -> {
                    Mono(
                        "Termux:X11 ${app.versionName ?: ""}".trim() + "\n" + when {
                            app.sameProcess -> L.settings.x11Desktop.antigemsBuildRunsInsideAntigem
                            app.antigemBuild -> L.settings.x11Desktop.antigemsBuild
                            else -> L.settings.x11Desktop.officialBuild
                        }
                    )
                    OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installApp) { Text(L.common.update) }
                }
                else -> {
                    Text(
                        if (app.antigemBuild) L.settings.x11Desktop.thisTermuxX11DoesntShareAntigems
                        else L.settings.x11Desktop.theOfficialTermuxX11IsInstalled,
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error
                    )
                    Button(enabled = !busy, onClick = { X11DesktopManager.uninstallApp(context) }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.uninstallTermuxX11)
                    }
                }
            }
            TextButton(
                onClick = { openUrl(context, X11DesktopManager.FORK_RELEASES_PAGE) },
                contentPadding = PaddingValues(0.dp)
            ) { Text(L.settings.x11Desktop.openTheDownloadPage, fontSize = 12.5.sp) }
            TaskOutput(state, Task.APP)
        }

        // Step 2: the companion package (the termux-x11 command)
        StepCard(
            2, "termux-x11 command",
            when {
                state.chroot -> L.settings.x11Desktop.notNeeded
                state.companionOk -> L.common.installed
                state.companion != null -> L.settings.x11Desktop.wrongPackage
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.companionOk) QuotaGreen else Amber
        ) {
            if (state.chroot) {
                // the app starts the X server itself and shares its display with the chroot
                Hint(L.settings.x11Desktop.notNeededInTheUbuntuChroot)
                return@StepCard
            }
            Hint(L.settings.x11Desktop.theTerminalPartTheTermuxX11(state.neededCompanion, if (app == null) L.settings.x11Desktop.theTermuxX11Above else L.settings.x11Desktop.theInstalledTermuxX11))
            if (state.companion != null && !state.companionOk) {
                Text(
                    L.settings.x11Desktop.companionIsInstalledButThisTermux(state.companion, state.neededCompanion),
                    fontSize = 12.5.sp, color = Amber
                )
            }
            if (!state.companionOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installCompanion) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.installNeededcompanion(state.neededCompanion))
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installCompanion) { Text(L.common.reinstall) }
            }
            TaskOutput(state, Task.COMPANION)
        }

        // Step 3: a desktop
        StepCard(3, L.settings.x11Desktop.desktop, if (state.desktopOk) L.common.installed else null, cardBg, cardBorder) {
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
                    Desktop.XFCE -> L.settings.x11Desktop.xfceIsAFullLightDesktop
                    Desktop.LXQT -> L.settings.x11Desktop.lxqtIsALightQtDesktop
                } + if (state.chroot) L.settings.x11Desktop.itComesFromUbuntuAptAnd else L.settings.x11Desktop.itComesFromX11RepoAnd
            )
            if (!state.desktopOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installDesktop) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.installLabel(state.desktop.label))
                }
            }
            TaskOutput(state, Task.DESKTOP)
        }

        // Step 4: start / open / stop
        StepCard(4, L.settings.x11Desktop.startTheDesktop, if (state.running) L.common.running else null, cardBg, cardBorder) {
            if (state.running) {
                Hint(if (state.chroot) "The X server is running on display :0, shared with the Ubuntu chroot. Programs started in the chroot's terminal with DISPLAY=:0 show up on the desktop."
                    else "The X server is running on display :0. Programs started from the terminal with DISPLAY=:0 show up on the desktop.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { X11DesktopManager.open(context) }) {
                        Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.openDesktop)
                    }
                    OutlinedButton(enabled = !busy, onClick = { X11DesktopManager.stop(context) }) { Text(L.common.stop) }
                }
            } else {
                if (!state.canStart) Hint(L.settings.x11Desktop.finishTheStepsAboveFirst)
                Button(enabled = !busy && state.canStart, onClick = { X11DesktopManager.start(context) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.x11Desktop.startLabel(state.desktop.label))
                }
            }
            TaskOutput(state, Task.START)
        }

        // Web Browser: Chromium or Firefox from tur-repo
        val browserInstalled = state.browser in state.installedBrowsers
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.settings.x11Desktop.webBrowser, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (browserInstalled) {
                        Pill(L.common.installed, QuotaGreen)
                    } else {
                        Pill(L.common.optional, MaterialTheme.colorScheme.primary)
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
                Hint(if (state.chroot) L.settings.x11Desktop.installsChromiumOrFirefoxInThe
                    else L.settings.x11Desktop.installABrowserFromTurRepo)
                if (!state.desktopOk) {
                    Hint(L.settings.x11Desktop.installADesktopAboveFirst)
                }
                if (!browserInstalled) {
                    Button(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(L.settings.x11Desktop.installLabel(state.browser.label))
                    }
                } else {
                    OutlinedButton(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Text(L.settings.x11Desktop.reinstallLabel(state.browser.label))
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
                        Text(L.settings.x11Desktop.ifTheDesktopClosesByItself, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill(L.common.optional, MaterialTheme.colorScheme.primary)
                    }
                    Hint(L.settings.x11Desktop.android12AndNewerStopThe)
                    val device = adb.connectedDevice
                    if (device != null) {
                        Button(enabled = !busy, onClick = { X11DesktopManager.disableChildProcessLimit(device.serial) }) { Text(L.settings.x11Desktop.turnOffTheLimit) }
                    } else {
                        Hint(L.settings.x11Desktop.connectTheTerminalsAdbToThis)
                        OutlinedButton(onClick = onOpenAdbConnect) { Text(L.common.setUpAdbConnect) }
                    }
                    TaskOutput(state, Task.CHILD_LIMIT)
                }
            }
        }

        ManualSetupCard(state.desktop, cardBg, cardBorder)
    }
}

@Composable
private fun IntroCard(running: Boolean, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(L.settings.x11Desktop.linuxDesktop, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (running) Pill(L.common.running, QuotaGreen)
            }
            Hint(L.settings.x11Desktop.runLinuxDesktopAppsABrowser)
            Text(
                L.settings.x11Desktop.theDesktopRunsInsideAntigemSo,
                fontSize = 12.sp, color = QuotaGreen
            )
        }
    }
}

/** Progress and the log of [task], shown in its own step while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: X11DesktopManager.State, task: Task) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

@Composable
private fun ManualSetupCard(desktop: Desktop, cardBg: Color, cardBorder: BorderStroke) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    val start = "termux-x11 :0 -xstartup \"${desktop.startCommand}\""
    val commands = listOf(
        L.settings.x11Desktop.n1InstallAntigemTermuxX11Shareduid(X11DesktopManager.FORK_RELEASES_PAGE) to null,
        L.settings.x11Desktop.n2EnableX11Repo to "pkg install x11-repo",
        L.settings.x11Desktop.n3InstallTheTermuxX11Command to "apt install ./antigem-termux-x11-*-all.deb",
        L.settings.x11Desktop.n4UpdateYourPackagesThenInstall to "pkg upgrade -y && pkg install dbus ${desktop.packages}",
        L.settings.x11Desktop.n5StartItThenOpenTermux to start
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.settings.x11Desktop.setUpManually, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) L.common.hide else L.common.show, fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(L.settings.x11Desktop.preferTheTerminalTheseAreThe)
                return@Column
            }
            commands.forEach { (label, command) ->
                Hint(label)
                if (command != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Mono(command) }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                            Icon(Icons.Outlined.ContentCopy, L.common.copy, Modifier.size(18.dp))
                        }
                    }
                }
            }
            Hint(L.settings.x11Desktop.dontInstallTermuxX11NightlyFrom)
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
