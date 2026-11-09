package com.example.gemini.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.local.LocalServerManager
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.BridgeInfo
import com.example.gemini.data.remote.RemoteServerManager

private val Ok: androidx.compose.ui.graphics.Color get() = com.example.gemini.theme.AppColors.success
private val Warn: androidx.compose.ui.graphics.Color get() = com.example.gemini.theme.AppColors.warning

/** Asks to update the IDE bridge when its version is not the app's. Shown once per version pair unless "Later". */
@Composable
fun BridgeVersionDialog() {
    val context = LocalContext.current
    val bridge by BridgeInfo.version.collectAsState()
    val snoozed by BridgeInfo.snoozedFor.collectAsState()
    val b = bridge ?: return
    val cmp = BridgeInfo.compareBridgeToApp(b)
    // "Later" hides it until the app is opened again: an outdated bridge must not be forgotten
    if (cmp == 0 || snoozed == b) return

    val remote = AuthPreferences.currentRemoteMode
    val where = if (remote) "on your server" else "on this phone"
    val text = when {
        cmp > 0 -> "The IDE bridge $where is version $b, newer than this app (${BridgeInfo.appVersion}). Update the app so they match."
        remote -> "The IDE bridge $where is version $b, but this app is version ${BridgeInfo.appVersion}.\n\n" +
            "Update the bridge for better support: newer app features may not work with an older bridge.\n\n" +
            "Build the bridge from this app version (agy_ide_bridge), copy it to ${RemoteServerManager.bridgeBinaryPath(context)} on the server " +
            "and chmod +x it, then tap Restart server. Steps: docs/remote-server.md, section Updating."
        else -> "The IDE bridge $where is version $b, but this app is version ${BridgeInfo.appVersion}.\n\n" +
            "It is updated together with the app. Tap Restart server to start the bridge that came with this app. " +
            "If the version still differs, reinstall the app."
    }
    AlertDialog(
        onDismissRequest = { BridgeInfo.snooze(b) },
        title = { Text("Update the IDE bridge", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = { Text(text, fontSize = 13.sp, lineHeight = 18.sp) },
        confirmButton = {
            if (cmp < 0) {
                Button(onClick = {
                    BridgeInfo.snooze(b)
                    BridgeInfo.reset()
                    LocalServerManager.restartServer(context)
                }) { Text("Restart server") }
            }
        },
        dismissButton = { TextButton(onClick = { BridgeInfo.snooze(b) }) { Text("Later") } }
    )
}

/** One line for the Servers screen: the connected bridge's version and whether it matches the app. */
@Composable
fun BridgeVersionLine(modifier: Modifier = Modifier) {
    val bridge by BridgeInfo.version.collectAsState()
    val b = bridge
    val (text, color) = when {
        b == null -> "Bridge version: not connected" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        BridgeInfo.compareBridgeToApp(b) == 0 -> "Bridge version: $b (matches the app)" to Ok
        BridgeInfo.compareBridgeToApp(b) < 0 -> "Bridge version: $b, the app is ${BridgeInfo.appVersion}: update the bridge" to Warn
        else -> "Bridge version: $b, newer than the app (${BridgeInfo.appVersion}): update the app" to Warn
    }
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = color, modifier = modifier)
}

/** Settings > Antigravity > Antigravity CLI: the agy CLI on the bridge's machine (this phone or the server): version and install. */
@Composable
fun AgyCliCard(modifier: Modifier = Modifier) {
    val cli by BridgeInfo.agyCli.collectAsState()
    val error by BridgeInfo.agyError.collectAsState()
    val job by BridgeInfo.job.collectAsState()
    val checking by BridgeInfo.checking.collectAsState()
    val remote = AuthPreferences.currentRemoteMode
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    LaunchedEffect(Unit) { BridgeInfo.refreshAgyCli() }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Antigravity CLI (agy)", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { BridgeInfo.refreshAgyCli() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
            val running = job?.running == true
            when {
                error != null -> Text(error!!, fontSize = 12.sp, color = Color.Red)
                cli == null -> Text("Checking…", fontSize = 12.5.sp, color = subtle)
                cli!!.installed -> {
                    Text(cli!!.version.ifBlank { "Installed (version unknown)" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(cli!!.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = subtle)
                }
                else -> {
                    Text(
                        "agy is not installed on ${if (remote) "the server" else "this device"}.",
                        fontSize = 12.5.sp, color = Warn
                    )
                    if (cli!!.path.isNotBlank()) Text(cli!!.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = subtle)
                }
            }
            if (cli?.installed == false && error == null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { BridgeInfo.installAgy() }, enabled = !running) { Text("Install agy") }
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (cli?.installed == true) {
                val context = LocalContext.current
                val updatedTo by BridgeInfo.agyUpdatedTo.collectAsState()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { BridgeInfo.updateAgy(context) }, enabled = !running) {
                        Text(if (running && job?.kind == "update") "Checking…" else "Check for updates")
                    }
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                // a newer agy was installed: the server was restarted so the hub runs it
                updatedTo?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Updated to agy $it. The server restarted to use it.", fontSize = 12.sp, color = Ok)
                }
            }
            job?.let { j ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "${j.kind}: " + if (j.running) "running…" else "finished (exit ${j.exitCode})",
                    fontSize = 12.sp,
                    color = if (!j.running && j.exitCode != 0) Color.Red else subtle
                )
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        j.log.lines().takeLast(30).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Installed with Google's official installer (antigravity.google/cli/install.sh) into ~/.local/bin on " +
                    (if (remote) "the server" else "this device") + ". agy updates itself when you run it in a terminal; " +
                    "Check for updates runs `agy update` and, when a newer agy was installed, restarts the server so it is used " +
                    "(voice calls need agy 1.5 or newer).",
                fontSize = 11.sp, color = subtle
            )
        }
    }
}
