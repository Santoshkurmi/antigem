package ai.antigem.ui.settings

import ai.antigem.ui.components.bouncyVerticalScroll
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.adb.AdbConnectManager.Pairing
import ai.antigem.data.adb.DeviceAccess
import ai.antigem.data.adb.RootShell
import ai.antigem.data.adb.ShizukuShell
import ai.antigem.theme.QuotaGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

internal val Amber: androidx.compose.ui.graphics.Color get() = ai.antigem.theme.AppColors.warning

@Composable
fun AdbConnectScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AdbConnectManager.init(context); DeviceAccess.init(context) }
    val mode by DeviceAccess.mode.collectAsState()
    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModePicker(mode, cardBg, cardBorder)
        when (mode) {
            DeviceAccess.Mode.ADB -> AdbSection(cardBg, cardBorder)
            DeviceAccess.Mode.SHIZUKU -> ShizukuSection(cardBg, cardBorder)
            DeviceAccess.Mode.ROOT -> RootSection(cardBg, cardBorder)
        }
    }
}

/** ADB, Shizuku or root: what Android Automation uses. In chat, any of them that is set up can be asked for. */
@Composable
private fun ModePicker(mode: DeviceAccess.Mode, cardBg: Color, cardBorder: BorderStroke) {
    val modes = DeviceAccess.Mode.entries
    val su = if (AdbConnectManager.inChroot) "android-su -c" else "su -c"
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L.settings.adbConnect.howAntigemReachesThisPhone, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { DeviceAccess.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                    ) { Text(m.label) }
                }
            }
            Hint(
                when (mode) {
                    DeviceAccess.Mode.ADB -> "adb over Wireless debugging: pair once, then AntiGem connects by itself. Runs as the shell user."
                    DeviceAccess.Mode.SHIZUKU -> L.settings.adbConnect.throughTheShizukuAppAsThe
                    DeviceAccess.Mode.ROOT -> L.settings.adbConnect.throughThePhonesSuMagiskKernelsu
                }
            )
            Hint(L.settings.adbConnect.androidAutomationUsesTheOnePicked(su))
        }
    }
}

/** ADB mode: the terminal's adb, connected to this phone over Wireless debugging. */
@Composable
private fun AdbSection(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val state by AdbConnectManager.state.collectAsState()

    // System settings can change while the user is away in Developer options; keep them live
    var devOptionsOn by remember { mutableStateOf(AdbConnectManager.isDeveloperOptionsOn(context)) }
    var wirelessOn by remember { mutableStateOf(AdbConnectManager.isWirelessDebuggingOn(context)) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LaunchedEffect(Unit) {
        // opening ADB (or switching back to it) connects a paired phone that isn't connected, like the Connect button
        AdbConnectManager.refreshAndConnect()
        var tick = 0
        while (true) {
            delay(1500)
            devOptionsOn = AdbConnectManager.isDeveloperOptionsOn(context)
            wirelessOn = AdbConnectManager.isWirelessDebuggingOn(context)
            notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            val s = AdbConnectManager.state.value
            if (++tick % 4 == 0 && s.adbInstalled == true && s.busy == null && !s.installing) AdbConnectManager.refresh(quiet = true)
        }
    }

    val installed = state.adbInstalled == true
    val connected = state.connectedDevice

    run {
        StatusCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = AdbConnectManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        // Step 1: adb in the terminal
        StepCard(1, L.settings.adbConnect.installAdb, if (installed) L.common.installed else if (state.adbBroken != null) L.settings.adbConnect.broken else null, cardBg, cardBorder,
            badgeColor = if (installed) QuotaGreen else MaterialTheme.colorScheme.error) {
            when {
                state.adbInstalled == null -> Hint(L.settings.adbConnect.checkingTheTerminalForAdb)
                installed && !state.installing -> Mono(state.adbVersion ?: "adb")
                state.adbBroken != null && !state.installing -> {
                    Text("adb is installed but can't start:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Mono(state.adbBroken!!)
                    Hint(L.settings.adbConnect.usuallyALibraryItNeedsSuch)
                    Button(onClick = AdbConnectManager::installAdb) {
                        Icon(Icons.Outlined.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.adbConnect.repairAdb)
                    }
                }
                !state.installing && AdbConnectManager.inChroot -> {
                    // the Ubuntu chroot is the phone's runtime: its agents use Ubuntu's adb
                    Hint("adb isn't installed in the Ubuntu chroot yet. This installs Ubuntu's adb and fastboot packages: they run as your user, no sudo needed (also for USB / OTG devices).")
                    Button(onClick = AdbConnectManager::installAdb) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.adbConnect.installAdbAndFastboot)
                    }
                }
                !state.installing -> {
                    Hint("adb isn't installed in the terminal yet. This installs the android-tools package (adb, fastboot).")
                    Button(onClick = AdbConnectManager::installAdb) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.adbConnect.installAndroidTools)
                    }
                }
            }
            if (state.installing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                Hint(state.installLog.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
            }
            if (state.installLog.isNotEmpty()) LogBox(state.installLog)
            state.installError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }

        // Step 2: Wireless debugging
        val wirelessReady = devOptionsOn && wirelessOn
        StepCard(2, L.settings.adbConnect.turnOnWirelessDebugging, if (wirelessReady) L.common.on else null, cardBg, cardBorder) {
            if (!AdbConnectManager.isWirelessDebuggingSupported()) {
                Hint(L.settings.adbConnect.wirelessDebuggingNeedsAndroid11Or)
                return@StepCard
            }
            CheckRow(L.settings.adbConnect.developerOptions, devOptionsOn)
            CheckRow(L.settings.adbConnect.wirelessDebugging, wirelessOn)
            Hint(
                when {
                    !devOptionsOn -> L.settings.adbConnect.openAboutPhoneAndTapBuild
                    !wirelessOn -> L.settings.adbConnect.inDeveloperOptionsTurnOnWireless
                    else -> L.settings.adbConnect.readyWirelessDebuggingUsesANew
                }
            )
            if (!wirelessReady) OutlinedButton(onClick = { AdbConnectManager.openWirelessDebuggingSettings(context) }) {
                Text(if (devOptionsOn) L.settings.adbConnect.openWirelessDebugging else L.settings.adbConnect.openAboutPhone)
            }
        }

        // Step 3: one-time pairing through the notification
        StepCard(3, L.settings.adbConnect.pairThisDevice, if (state.pairedOnce) L.settings.adbConnect.paired else L.settings.adbConnect.once, cardBg, cardBorder, badgeColor = if (state.pairedOnce) QuotaGreen else Amber) {
            PairingSteps()
            if (!notificationsOn) {
                Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.12f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(L.settings.adbConnect.notificationsAreOffForAntigemThe, fontSize = 12.sp)
                        TextButton(onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, contentPadding = PaddingValues(0.dp)) { Text(L.settings.adbConnect.allowNotifications) }
                    }
                }
            }
            PairingStatus(state.pairing, state.host)
            val pairingActive = state.pairing !is Pairing.Idle && state.pairing !is Pairing.Paired
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pairingActive) {
                    OutlinedButton(onClick = { AdbConnectManager.stopPairing(context) }) { Text(L.common.stop) }
                    Button(onClick = { AdbConnectManager.openWirelessDebuggingSettings(context) }) { Text(L.settings.adbConnect.openWirelessDebugging) }
                } else {
                    Button(
                        enabled = installed && wirelessReady,
                        onClick = {
                            AdbConnectManager.startPairing(context)
                            AdbConnectManager.openWirelessDebuggingSettings(context)
                        }
                    ) { Text(if (state.pairedOnce) L.settings.adbConnect.pairAgain else L.settings.adbConnect.startPairing) }
                }
            }
            if (!installed || !wirelessReady) Hint(L.settings.adbConnect.finishTheStepsAboveFirst)
            ManualPairing(enabled = installed && state.pairing !is Pairing.InProgress)
        }

        // Step 4: connect
        StepCard(4, L.common.connect, if (connected != null) L.settings.adbConnect.connected else null, cardBg, cardBorder) {
            var host by remember(state.host) { mutableStateOf(state.host) }
            var port by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() }, label = { Text(L.settings.adbConnect.ip) }, singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(L.common.port) },
                    placeholder = { Text(L.common.auto) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Hint(L.settings.adbConnect.leaveThePortEmptyToFind)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = installed && state.busy == null,
                    onClick = { AdbConnectManager.setHost(host); AdbConnectManager.connect(port.toIntOrNull()) }
                ) { Text(L.common.connect) }
                if (state.busy != null) {
                    OutlinedButton(onClick = AdbConnectManager::cancelConnect) { Text(L.common.cancel) }
                } else if (connected != null) {
                    OutlinedButton(onClick = AdbConnectManager::disconnectAll) { Text(L.common.disconnect) }
                }
                if (state.busy != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.busy!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.settings.adbConnect.reconnectAutomatically, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Hint(L.settings.adbConnect.whenWirelessDebuggingTurnsOnAgain)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.autoReconnect, onCheckedChange = AdbConnectManager::setAutoReconnect)
            }
            if (AdbConnectManager.isWirelessDebuggingSupported()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.adbConnect.turnWirelessDebuggingOnByItself, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Hint(L.settings.adbConnect.afterARebootOrWhenWi)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (state.grantingSecureSettings) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Switch(checked = state.autoWireless && state.secureSettings, onCheckedChange = { AdbConnectManager.setAutoWireless(it) })
                }
                if (state.autoWireless && !state.secureSettings) {
                    Text(L.settings.adbConnect.thePermissionIsGoneItIs,
                        fontSize = 12.sp, color = Amber)
                }
            }
            if (state.devices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Text(L.settings.adbConnect.devices, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                state.devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.serial, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        Pill(device.state, if (device.state == "device") QuotaGreen else Amber)
                        IconButton(onClick = { AdbConnectManager.disconnect(device.serial) }) {
                            Icon(Icons.Outlined.LinkOff, L.common.disconnect, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (connected != null) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L.settings.adbConnect.useItInTheTerminal, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Mono("adb devices\nadb shell\nadb install app.apk")
                }
            }
        }
    }
}

/** Shizuku mode: commands go through the Shizuku app, as the shell user (or root when Shizuku runs as root). */
@Composable
private fun ShizukuSection(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val status by ShizukuShell.status.collectAsState()
    // Shizuku can be started, stopped or allowed while the user is away in its app
    LaunchedEffect(Unit) {
        while (true) {
            ShizukuShell.refresh()
            delay(1500)
        }
    }
    val (icon, tint, title, subtitle) = when {
        !status.installed -> Quad(Icons.Outlined.Download, Amber, L.settings.adbConnect.shizukuNotInstalled, L.settings.adbConnect.installItBelowToGetStarted)
        !status.running -> Quad(Icons.Outlined.PowerSettingsNew, Amber, L.settings.adbConnect.shizukuNotRunning, L.settings.adbConnect.startItInTheShizukuApp)
        status.tooOld -> Quad(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, L.settings.adbConnect.shizukuTooOld, L.settings.adbConnect.updateShizukuV11OrNewer)
        !status.granted -> Quad(Icons.Outlined.Lock, Amber, L.settings.adbConnect.permissionNeeded, L.settings.adbConnect.allowAntigemBelow)
        else -> Quad(Icons.Outlined.PhonelinkSetup, QuotaGreen, L.common.ready, L.settings.adbConnect.commandsRunAsUserlabelShizukuVersion(status.userLabel, status.version))
    }
    HeaderCard(icon, tint, title, subtitle, cardBg, cardBorder) { ShizukuShell.refresh() }

    StepCard(1, L.settings.adbConnect.installShizuku, if (status.installed) L.common.installed else null, cardBg, cardBorder) {
        Hint(L.settings.adbConnect.shizukuIsAFreeAppThat)
        if (!status.installed) Button(onClick = { ShizukuShell.openShizuku(context) }) {
            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.adbConnect.getShizuku)
        }
    }
    StepCard(2, L.settings.adbConnect.startShizuku, if (status.running) L.common.running else null, cardBg, cardBorder) {
        Hint(L.settings.adbConnect.inTheShizukuAppStartIt)
        if (status.installed && !status.running) OutlinedButton(onClick = { ShizukuShell.openShizuku(context) }) { Text(L.settings.adbConnect.openShizuku) }
    }
    StepCard(3, L.settings.adbConnect.allowAntigem, if (status.granted) L.common.allowed else null, cardBg, cardBorder) {
        Hint(L.settings.adbConnect.shizukuAsksYouOnceWhetherAntigem)
        if (status.denied) Text(L.settings.adbConnect.deniedTapAllowAgainOrAllow, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error)
        if (!status.granted) Button(enabled = status.running && !status.tooOld, onClick = { ShizukuShell.requestPermission() }) {
            Text(L.settings.adbConnect.allowAntigem)
        }
    }
    if (status.ready) TestCard(DeviceAccess.Mode.SHIZUKU, cardBg, cardBorder)
    TerminalCard(
        "rish -c 'pm list packages'\nrish dumpsys battery\nrish            # interactive",
        "rish runs commands on the phone as ${if (status.uid == 0) "root" else "the shell user"}, through AntiGem (it must be running). " +
            if (AdbConnectManager.inChroot) L.settings.adbConnect.itWorksTheSameInThe else L.settings.adbConnect.itsAlsoInstalledInTheUbuntu,
        cardBg, cardBorder
    )
}

/** Root mode: commands go through the phone's su. */
@Composable
private fun RootSection(cardBg: Color, cardBorder: BorderStroke) {
    val scope = rememberCoroutineScope()
    val state by RootShell.state.collectAsState()
    val (icon, tint, title, subtitle) = when (state.check) {
        RootShell.Check.GRANTED -> Quad(Icons.Outlined.PhonelinkSetup, QuotaGreen, L.settings.adbConnect.rootGranted, L.settings.adbConnect.commandsRunAsRootThroughSu)
        RootShell.Check.CHECKING -> Quad(Icons.Outlined.Sync, MaterialTheme.colorScheme.primary, L.common.checking, L.settings.adbConnect.answerYourRootManagersPrompt)
        RootShell.Check.DENIED -> Quad(Icons.Outlined.Block, MaterialTheme.colorScheme.error, L.settings.adbConnect.rootDenied, L.settings.adbConnect.allowAntigemInYourRootManager)
        RootShell.Check.NO_SU -> Quad(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, L.settings.adbConnect.noSu, L.settings.adbConnect.thisPhoneIsntRooted)
        RootShell.Check.UNKNOWN -> Quad(Icons.Outlined.Lock, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), L.settings.adbConnect.notChecked, L.settings.adbConnect.checkRootBelow)
    }
    HeaderCard(icon, tint, title, subtitle, cardBg, cardBorder, refreshing = state.check == RootShell.Check.CHECKING) {
        scope.launch { RootShell.check() }
    }

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(L.settings.adbConnect.rootGivesTheAgentsFullControl,
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
    }

    StepCard(1, L.settings.adbConnect.allowAntigemRoot, if (state.check == RootShell.Check.GRANTED) L.settings.adbConnect.granted else null, cardBg, cardBorder) {
        Hint(L.settings.adbConnect.tapCheckRootYourRootManager)
        state.message?.let { Text(it, fontSize = 12.sp, color = if (state.check == RootShell.Check.GRANTED) QuotaGreen else MaterialTheme.colorScheme.error) }
        Button(enabled = state.check != RootShell.Check.CHECKING, onClick = { scope.launch { RootShell.check() } }) {
            Text(if (state.check == RootShell.Check.GRANTED) L.common.checkAgain else L.settings.adbConnect.checkRoot)
        }
    }
    if (state.check == RootShell.Check.GRANTED) TestCard(DeviceAccess.Mode.ROOT, cardBg, cardBorder)
    if (AdbConnectManager.inChroot) {
        TerminalCard(
            "android-su -c 'pm list packages'\nandroid-su            # interactive",
            L.settings.adbConnect.inTheUbuntuChrootSuAnd,
            cardBg, cardBorder
        )
    } else {
        TerminalCard("su -c 'pm list packages'\nsu", L.settings.adbConnect.theTerminalUsesThePhonesOwn, cardBg, cardBorder)
    }
}

/** Runs `id` through [mode], to see it work. */
@Composable
private fun TestCard(mode: DeviceAccess.Mode, cardBg: Color, cardBorder: BorderStroke) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.settings.adbConnect.test, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        val r = DeviceAccess.shell("id", 20, mode)
                        result = r.output.trim().ifBlank { "exit code ${r.exitCode}" }
                        running = false
                    }
                }) { Text(if (running) L.settings.adbConnect.running else L.settings.adbConnect.runId) }
            }
            result?.let { Mono(it) }
        }
    }
}

@Composable
private fun TerminalCard(commands: String, hint: String, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L.settings.adbConnect.useItInTheTerminal, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Mono(commands)
            Hint(hint)
        }
    }
}

@Composable
private fun HeaderCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    refreshing: Boolean = false,
    onRefresh: () -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
        }
    }
}

@Composable
private fun StatusCard(state: AdbConnectManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val connected = state.connectedDevice
    val (icon, tint, title, subtitle) = when {
        state.adbBroken != null -> Quad(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, L.settings.adbConnect.adbCantStart, L.settings.adbConnect.tapRepairAdbBelow)
        state.adbInstalled == false -> Quad(Icons.Outlined.Download, Amber, L.settings.adbConnect.adbNotInstalled, L.settings.adbConnect.installItBelowToGetStarted)
        connected != null -> Quad(Icons.Outlined.PhonelinkSetup, QuotaGreen, L.settings.adbConnect.connected, connected.serial)
        state.busy != null -> Quad(Icons.Outlined.Sync, MaterialTheme.colorScheme.primary, L.common.connecting, state.busy)
        else -> Quad(Icons.Outlined.PhonelinkErase, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), L.settings.adbConnect.notConnected,
            if (state.pairedOnce) L.settings.adbConnect.turnOnWirelessDebuggingToConnect else L.settings.adbConnect.pairOnceThenConnect)
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 12.sp, fontFamily = if (connected != null) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (state.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { AdbConnectManager.refreshAndConnect(quiet = false) }) { Icon(Icons.Outlined.Refresh, L.settings.adbConnect.refreshAndConnect) }
        }
    }
}

private data class Quad(val icon: ImageVector, val tint: Color, val title: String, val subtitle: String)

@Composable
internal fun StepCard(
    number: Int,
    title: String,
    badge: String?,
    cardBg: Color,
    cardBorder: BorderStroke,
    badgeColor: Color = QuotaGreen,
    content: @Composable ColumnScope.() -> Unit
) {
    val done = badge != null && badgeColor == QuotaGreen
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (done) QuotaGreen else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                badge?.let { Pill(it, badgeColor) }
            }
            content()
        }
    }
}

@Composable
private fun PairingSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            L.settings.adbConnect.tapStartPairingANotificationAppears,
            L.settings.adbConnect.tapPairDeviceWithPairingCode,
            L.settings.adbConnect.pullDownTheNotificationShadeAnd,
        ).forEachIndexed { i, text -> Hint("${i + 1}. $text") }
        Text(
            L.settings.adbConnect.stayInSettingsWhileYouDo,
            fontSize = 12.sp, color = Amber, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PairingStatus(pairing: Pairing, host: String) {
    val (text, color) = when (pairing) {
        Pairing.Idle -> return
        Pairing.Searching -> L.settings.adbConnect.waitingForThePairingDialog to MaterialTheme.colorScheme.primary
        is Pairing.Ready -> L.settings.adbConnect.pairingDialogFoundOnPortPort(pairing.port) to MaterialTheme.colorScheme.primary
        is Pairing.InProgress -> L.settings.adbConnect.pairingWithHostPort(host, pairing.port) to MaterialTheme.colorScheme.primary
        Pairing.Paired -> L.settings.adbConnect.pairedYouWontNeedToDo to QuotaGreen
        is Pairing.Failed -> pairing.message to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.1f)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pairing is Pairing.Searching || pairing is Pairing.InProgress) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 12.5.sp, color = color)
        }
    }
}

@Composable
private fun ManualPairing(enabled: Boolean) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) L.settings.adbConnect.hideManualPairing else L.settings.adbConnect.pairManuallyInstead, fontSize = 12.5.sp)
    }
    if (!open) return
    Hint(L.settings.adbConnect.useThisOnASplitScreen)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(L.common.port) }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text(L.common.code) }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
    Button(
        enabled = enabled && port.isNotEmpty() && code.length == 6,
        onClick = { AdbConnectManager.pairManually(context, port.toInt(), code) }
    ) { Text(L.settings.adbConnect.pair) }
}

@Composable
internal fun CheckRow(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (on) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (on) L.common.on else L.common.off, fontSize = 12.sp, color = if (on) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
internal fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
internal fun Hint(text: String) =
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))

@Composable
internal fun Mono(text: String) = Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
}

@Composable
internal fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp))
            .background(ai.antigem.theme.AppColors.terminalBg).verticalScroll(scroll).padding(10.dp)
    ) {
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = ai.antigem.theme.AppColors.editorText, lineHeight = 14.sp)
    }
}
