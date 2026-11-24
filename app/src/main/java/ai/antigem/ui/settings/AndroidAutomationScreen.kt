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
import ai.antigem.i18n.L

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
            running -> Triple(QuotaGreen, L.common.allowed, L.settings.androidAutomation.agentsWithTheAndroidAutomationMcp)
            enabledInSettings -> Triple(Amber, L.settings.androidAutomation.notRunning, L.settings.androidAutomation.theServiceIsOnButAndroid)
            else -> Triple(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), L.settings.androidAutomation.notAllowed, L.settings.androidAutomation.grantThePermissionsBelowToUse)
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
                    L.settings.androidAutomation.thisGivesAiAgentsFullControl,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }

        // 1. Accessibility service (required)
        StepCard(1, L.settings.androidAutomation.accessibilityService, if (running) L.common.allowed else L.common.required, cardBg, cardBorder, badgeColor = if (running) QuotaGreen else Amber) {
            Hint(L.settings.androidAutomation.readsTheScreenTapsSwipesTypes)
            CheckRow(L.settings.androidAutomation.antigemAndroidAutomation, running)
            if (!running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AndroidAutomation.openAccessibilitySettings(context) }) { Text(L.settings.androidAutomation.openAccessibility) }
                    if (accessReady) OutlinedButton(
                        enabled = !enablingViaAdb,
                        onClick = {
                            enablingViaAdb = true; adbError = null
                            scope.launch {
                                AndroidAutomation.enableViaShell(context).onFailure { adbError = it.message }
                                enablingViaAdb = false
                            }
                        }
                    ) { Text(if (enablingViaAdb) L.settings.androidAutomation.turningOn else L.settings.androidAutomation.turnOnWithLabel(accessMode.label)) }
                }
                adbError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.1f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(L.settings.androidAutomation.switchGreyedOutOrRestrictedSetting, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            L.settings.androidAutomation.android13BlocksThisForApps,
                            fontSize = 12.sp
                        )
                        TextButton(contentPadding = PaddingValues(0.dp), onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(L.settings.androidAutomation.openAppInfo) }
                    }
                }
            } else {
                OutlinedButton(onClick = { AndroidAutomation.openAccessibilitySettings(context) }) { Text(L.settings.androidAutomation.turnOffInAccessibility) }
            }
        }

        // 2. Screenshots come with the service on Android 11+
        val screenshots = AndroidAutomation.canTakeScreenshots()
        StepCard(2, L.settings.androidAutomation.screenshots, if (screenshots && running) L.common.allowed else null, cardBg, cardBorder) {
            Hint(
                if (screenshots) L.settings.androidAutomation.includedWithTheAccessibilityServiceApps
                else L.settings.androidAutomation.android10AndOlderCantTake
            )
        }

        // 3. Shell access (optional): ADB, Shizuku or root, picked in ADB Connect
        StepCard(3, L.settings.androidAutomation.shellAccessOptional, if (accessReady) L.common.ready else L.common.optional, cardBg, cardBorder,
            badgeColor = if (accessReady) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) {
            Hint(L.settings.androidAutomation.addsWhatAccessibilityCantDoPressing)
            CheckRow("${accessMode.label} ready", accessReady)
            accessDetail?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (!accessReady) OutlinedButton(onClick = onOpenAdbConnect) { Text(L.settings.androidAutomation.openAdbConnect) }
        }

        // Next step
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L.settings.androidAutomation.thenAddTheMcp, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Hint(L.settings.androidAutomation.inSettingsMcpServersAntigravityOr)
                Text(AndroidAutomation.tools().let { t -> (0 until t.length()).joinToString(" · ") { t.getJSONObject(it).getString("name").removePrefix("android_") } },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}
