package ai.antigem.ui.settings

import ai.antigem.ui.components.bouncyVerticalScroll
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.adb.DeviceAccess
import ai.antigem.data.adb.RootShell
import ai.antigem.data.adb.ShizukuShell
import ai.antigem.data.automation.AndroidAutomation
import ai.antigem.theme.QuotaGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android Automation has no on/off switch: it is "on" once the user grants the Accessibility service here,
 * and the android_automation_mcp server refuses to connect until then.
 */
@Composable
fun AndroidAutomationScreen(cardBg: Color, cardBorder: BorderStroke, onOpenAdbConnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { AdbConnectManager.init(context); DeviceAccess.init(context); AdbConnectManager.refresh() }
    val adb by AdbConnectManager.state.collectAsState()
    val accessMode by DeviceAccess.mode.collectAsState()
    val shizuku by ShizukuShell.status.collectAsState()
    val root by RootShell.state.collectAsState()

    var running by remember { mutableStateOf(AndroidAutomation.isServiceRunning()) }
    var enabledInSettings by remember { mutableStateOf(AndroidAutomation.isServiceEnabledInSettings(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            running = AndroidAutomation.isServiceRunning()
            enabledInSettings = AndroidAutomation.isServiceEnabledInSettings(context)
            delay(1000)
        }
    }
    var enablingViaAdb by remember { mutableStateOf(false) }
    var adbError by remember { mutableStateOf<String?>(null) }
    val adbDevice = adb.connectedDevice
    LaunchedEffect(Unit) {
        while (true) {
            ShizukuShell.refresh()
            delay(2000)
        }
    }
    // the picked way in (Settings → ADB Connect) is usable now; ADB may also connect by itself when it's needed
    val accessReady = when (accessMode) {
        DeviceAccess.Mode.ADB -> adbDevice != null
        DeviceAccess.Mode.SHIZUKU -> shizuku.ready
        DeviceAccess.Mode.ROOT -> root.check == RootShell.Check.GRANTED
    }
    val accessDetail = when (accessMode) {
        DeviceAccess.Mode.ADB -> adbDevice?.serial
        DeviceAccess.Mode.SHIZUKU -> if (shizuku.ready) "as ${shizuku.userLabel}" else null
        DeviceAccess.Mode.ROOT -> if (accessReady) "su" else null
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status
        val (tint, title, subtitle) = when {
            running -> Triple(QuotaGreen, "Allowed", "Agents with the Android Automation MCP can control this phone")
            enabledInSettings -> Triple(Amber, "Not running", "The service is on but Android hasn't started it. Turn it off and on again.")
            else -> Triple(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), "Not allowed", "Grant the permissions below to use it")
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.TouchApp, null, tint = tint, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
            Row(Modifier.padding(14.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "This gives AI agents full control of your phone: they can read everything on screen (including messages and passwords " +
                        "you can see), tap, type and open or uninstall apps. Only allow it if you trust the agents you connect, and review what they do.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }

        // 1. Accessibility service (required)
        StepCard(1, "Accessibility service", if (running) "Allowed" else "Required", cardBg, cardBorder, badgeColor = if (running) QuotaGreen else Amber) {
            Hint("Reads the screen, taps, swipes, types and takes screenshots. In Accessibility settings, open \"AntiGem Android Automation\" (it may be under Installed apps or Downloaded apps) and turn it on.")
            CheckRow("AntiGem Android Automation", running)
            if (!running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AndroidAutomation.openAccessibilitySettings(context) }) { Text("Open Accessibility") }
                    if (accessReady) OutlinedButton(
                        enabled = !enablingViaAdb,
                        onClick = {
                            enablingViaAdb = true; adbError = null
                            scope.launch {
                                AndroidAutomation.enableViaShell(context).onFailure { adbError = it.message }
                                enablingViaAdb = false
                            }
                        }
                    ) { Text(if (enablingViaAdb) "Turning on…" else "Turn on with ${accessMode.label}") }
                }
                adbError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.1f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Switch greyed out, or \"Restricted setting\"?", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android 13+ blocks this for apps installed from a file. Open App info, tap ⋮ (top right) → Allow restricted settings, " +
                                "then turn the service on. Or set up ADB, Shizuku or root (Settings → ADB Connect) and use the Turn on with… button.",
                            fontSize = 12.sp
                        )
                        TextButton(contentPadding = PaddingValues(0.dp), onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Open App info") }
                    }
                }
            } else {
                OutlinedButton(onClick = { AndroidAutomation.openAccessibilitySettings(context) }) { Text("Turn off in Accessibility") }
            }
        }

        // 2. Screenshots come with the service on Android 11+
        val screenshots = AndroidAutomation.canTakeScreenshots()
        StepCard(2, "Screenshots", if (screenshots && running) "Allowed" else null, cardBg, cardBorder) {
            Hint(
                if (screenshots) "Included with the accessibility service. Apps that block screenshots (banking, DRM video) show up black."
                else "Android 10 and older can't take screenshots through accessibility; connect ADB below to allow them."
            )
        }

        // 3. Shell access (optional): ADB, Shizuku or root, picked in ADB Connect
        StepCard(3, "Shell access (optional)", if (accessReady) "Ready" else "Optional", cardBg, cardBorder,
            badgeColor = if (accessReady) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) {
            Hint("Adds what accessibility can't do: pressing any key, installing, uninstalling and force-stopping apps, and running shell commands. " +
                "It goes through the way picked in ADB Connect: ADB, Shizuku or root.")
            CheckRow("${accessMode.label} ready", accessReady)
            accessDetail?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (!accessReady) OutlinedButton(onClick = onOpenAdbConnect) { Text("Open ADB Connect") }
        }

        // Next step
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Then add the MCP", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Hint("In Settings → MCP Servers (Antigravity) or Claude Code → MCP servers, tap the \"Android Automation\" preset. " +
                    "Until the accessibility service is allowed here, the server refuses to connect and the agent is told what's missing.")
                Text(AndroidAutomation.tools().let { t -> (0 until t.length()).joinToString(" · ") { t.getJSONObject(it).getString("name").removePrefix("android_") } },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}
