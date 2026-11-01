package com.example.gemini.ui.settings

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
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.adb.AdbConnectManager.Pairing
import com.example.gemini.theme.QuotaGreen
import kotlinx.coroutines.delay

internal val Amber = Color(0xFFF59E0B)

@Composable
fun AdbConnectScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AdbConnectManager.init(context) }
    val state by AdbConnectManager.state.collectAsState()

    // System settings can change while the user is away in Developer options; keep them live
    var devOptionsOn by remember { mutableStateOf(AdbConnectManager.isDeveloperOptionsOn(context)) }
    var wirelessOn by remember { mutableStateOf(AdbConnectManager.isWirelessDebuggingOn(context)) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LaunchedEffect(Unit) {
        AdbConnectManager.refresh()
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = AdbConnectManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        // Step 1: adb in the terminal
        StepCard(1, "Install ADB", if (installed) "Installed" else if (state.adbBroken != null) "Broken" else null, cardBg, cardBorder,
            badgeColor = if (installed) QuotaGreen else MaterialTheme.colorScheme.error) {
            when {
                state.adbInstalled == null -> Hint("Checking the terminal for adb…")
                installed && !state.installing -> Mono(state.adbVersion ?: "adb")
                state.adbBroken != null && !state.installing -> {
                    Text("adb is installed but can't start:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Mono(state.adbBroken!!)
                    Hint("Usually a library it needs (such as libprotobuf) is older than adb itself. Repair updates the package lists and upgrades adb and the libraries it uses.")
                    Button(onClick = AdbConnectManager::installAdb) {
                        Icon(Icons.Outlined.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Repair ADB")
                    }
                }
                !state.installing -> {
                    Hint("adb isn't installed in the terminal yet. This installs the android-tools package (adb, fastboot).")
                    Button(onClick = AdbConnectManager::installAdb) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install android-tools")
                    }
                }
            }
            if (state.installing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                Hint(state.installLog.lastOrNull { !it.startsWith("$") } ?: "Starting…")
            }
            if (state.installLog.isNotEmpty()) LogBox(state.installLog)
            state.installError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }

        // Step 2: Wireless debugging
        val wirelessReady = devOptionsOn && wirelessOn
        StepCard(2, "Turn on Wireless debugging", if (wirelessReady) "On" else null, cardBg, cardBorder) {
            if (!AdbConnectManager.isWirelessDebuggingSupported()) {
                Hint("Wireless debugging needs Android 11 or newer. On this phone, run \"adb tcpip 5555\" from a computer once, then connect to localhost port 5555 below.")
                return@StepCard
            }
            CheckRow("Developer options", devOptionsOn)
            CheckRow("Wireless debugging", wirelessOn)
            Hint(
                when {
                    !devOptionsOn -> "Open About phone and tap Build number 7 times to unlock Developer options."
                    !wirelessOn -> "In Developer options, turn on Wireless debugging. Your phone must be on Wi-Fi."
                    else -> "Ready. Wireless debugging uses a new port each time it turns on; AntiGem finds it for you."
                }
            )
            if (!wirelessReady) OutlinedButton(onClick = { AdbConnectManager.openWirelessDebuggingSettings(context) }) {
                Text(if (devOptionsOn) "Open Wireless debugging" else "Open About phone")
            }
        }

        // Step 3: one-time pairing through the notification
        StepCard(3, "Pair this device", if (state.pairedOnce) "Paired" else "Once", cardBg, cardBorder, badgeColor = if (state.pairedOnce) QuotaGreen else Amber) {
            PairingSteps()
            if (!notificationsOn) {
                Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.12f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Notifications are off for AntiGem. The pairing code is entered in a notification, so allow them first.", fontSize = 12.sp)
                        TextButton(onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, contentPadding = PaddingValues(0.dp)) { Text("Allow notifications") }
                    }
                }
            }
            PairingStatus(state.pairing, state.host)
            val pairingActive = state.pairing !is Pairing.Idle && state.pairing !is Pairing.Paired
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pairingActive) {
                    OutlinedButton(onClick = { AdbConnectManager.stopPairing(context) }) { Text("Stop") }
                    Button(onClick = { AdbConnectManager.openWirelessDebuggingSettings(context) }) { Text("Open Wireless debugging") }
                } else {
                    Button(
                        enabled = installed && wirelessReady,
                        onClick = {
                            AdbConnectManager.startPairing(context)
                            AdbConnectManager.openWirelessDebuggingSettings(context)
                        }
                    ) { Text(if (state.pairedOnce) "Pair again" else "Start pairing") }
                }
            }
            if (!installed || !wirelessReady) Hint("Finish the steps above first.")
            ManualPairing(enabled = installed && state.pairing !is Pairing.InProgress)
        }

        // Step 4: connect
        StepCard(4, "Connect", if (connected != null) "Connected" else null, cardBg, cardBorder) {
            var host by remember(state.host) { mutableStateOf(state.host) }
            var port by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() }, label = { Text("IP") }, singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                    placeholder = { Text("Auto") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Hint("Leave the port empty to find it automatically (this phone only). For another device, enter its IP and port.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = installed && state.busy == null,
                    onClick = { AdbConnectManager.setHost(host); AdbConnectManager.connect(port.toIntOrNull()) }
                ) { Text("Connect") }
                if (state.busy != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.busy!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reconnect automatically", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Hint("When Wireless debugging turns on again, for example after Wi-Fi reconnects, AntiGem finds the new port and connects while it's running.")
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.autoReconnect, onCheckedChange = AdbConnectManager::setAutoReconnect)
            }
            if (state.devices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Text("Devices", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                state.devices.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.serial, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        Pill(device.state, if (device.state == "device") QuotaGreen else Amber)
                        IconButton(onClick = { AdbConnectManager.disconnect(device.serial) }) {
                            Icon(Icons.Outlined.LinkOff, "Disconnect", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (connected != null) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Use it in the terminal", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Mono("adb devices\nadb shell\nadb install app.apk")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: AdbConnectManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val connected = state.connectedDevice
    val (icon, tint, title, subtitle) = when {
        state.adbBroken != null -> Quad(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "ADB can't start", "Tap Repair ADB below")
        state.adbInstalled == false -> Quad(Icons.Outlined.Download, Amber, "ADB not installed", "Install it below to get started")
        connected != null -> Quad(Icons.Outlined.PhonelinkSetup, QuotaGreen, "Connected", connected.serial)
        state.busy != null -> Quad(Icons.Outlined.Sync, MaterialTheme.colorScheme.primary, "Connecting…", state.busy)
        else -> Quad(Icons.Outlined.PhonelinkErase, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), "Not connected",
            if (state.pairedOnce) "Turn on Wireless debugging to connect" else "Pair once, then connect")
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
            else IconButton(onClick = { AdbConnectManager.refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
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
            "Tap Start pairing. A notification appears and Wireless debugging opens.",
            "Tap \"Pair device with pairing code\".",
            "Pull down the notification shade and type the 6-digit code into the AntiGem notification.",
        ).forEachIndexed { i, text -> Hint("${i + 1}. $text") }
        Text(
            "Stay in Settings while you do this. Android closes the pairing dialog as soon as you leave it.",
            fontSize = 12.sp, color = Amber, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PairingStatus(pairing: Pairing, host: String) {
    val (text, color) = when (pairing) {
        Pairing.Idle -> return
        Pairing.Searching -> "Waiting for the pairing dialog…" to MaterialTheme.colorScheme.primary
        is Pairing.Ready -> "Pairing dialog found on port ${pairing.port}. Enter the code in the notification." to MaterialTheme.colorScheme.primary
        is Pairing.InProgress -> "Pairing with $host:${pairing.port}…" to MaterialTheme.colorScheme.primary
        Pairing.Paired -> "Paired. You won't need to do this again on this phone." to QuotaGreen
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
        Text(if (open) "Hide manual pairing" else "Pair manually instead", fontSize = 12.5.sp)
    }
    if (!open) return
    Hint("Use this on a split screen, or for another device: enter the port and code shown in the pairing dialog.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
    Button(
        enabled = enabled && port.isNotEmpty() && code.length == 6,
        onClick = { AdbConnectManager.pairManually(context, port.toInt(), code) }
    ) { Text("Pair") }
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
        Text(if (on) "On" else "Off", fontSize = 12.sp, color = if (on) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
private fun Mono(text: String) = Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
}

@Composable
private fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111418)).verticalScroll(scroll).padding(10.dp)
    ) {
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Color(0xFFD1D5DB), lineHeight = 14.sp)
    }
}
