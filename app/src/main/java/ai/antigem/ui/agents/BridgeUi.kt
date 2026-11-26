package ai.antigem.ui.agents

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.BridgeInfo
import ai.antigem.data.remote.RemoteServerManager
import ai.antigem.i18n.L
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.label

private val Ok: androidx.compose.ui.graphics.Color get() = ai.antigem.theme.AppColors.success
private val Warn: androidx.compose.ui.graphics.Color get() = ai.antigem.theme.AppColors.warning

/** Asks to update the IDE bridge when its version is not the app's. Shown once per version pair unless "Later". */
@Composable
fun BridgeVersionDialog() {
    val context = LocalContext.current
    val bridge by BridgeInfo.version.collectAsState()
    val snoozed by BridgeInfo.snoozedFor.collectAsState()
    val b = bridge ?: return
    // Connect to PC: the bridge is AntiGem's in VS Code, versioned with the extension, not this app
    if (AuthPreferences.pcOverride != null) return
    val cmp = BridgeInfo.compareBridgeToApp(b)
    // "Later" hides it until the app is opened again: an outdated bridge must not be forgotten
    if (cmp == 0 || snoozed == b) return

    val remote = AuthPreferences.currentRemoteMode
    val where = if (remote) L.agents.bridgeUi.onYourServer else L.agents.bridgeUi.onThisPhone
    val text = when {
        cmp > 0 -> "The IDE bridge $where is version $b, newer than this app (${BridgeInfo.appVersion}). Update the app so they match."
        remote -> L.agents.bridgeUi.theIdeBridgeWhereIsVersion(where, b, BridgeInfo.appVersion, RemoteServerManager.bridgeBinaryPath(context))
        else -> L.agents.bridgeUi.theIdeBridgeWhereIsVersion2(where, b, BridgeInfo.appVersion)
    }
    AlertDialog(
        onDismissRequest = { BridgeInfo.snooze(b) },
        title = { Text(L.agents.bridgeUi.updateTheIdeBridge, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.semantics { heading() }) },
        text = { Text(text, fontSize = 13.sp, lineHeight = 18.sp) },
        confirmButton = {
            if (cmp < 0) {
                Button(onClick = {
                    BridgeInfo.snooze(b)
                    BridgeInfo.reset()
                    LocalServerManager.restartServer(context)
                }) { Text(L.common.restartServer) }
            }
        },
        dismissButton = { TextButton(onClick = { BridgeInfo.snooze(b) }) { Text(L.common.later) } }
    )
}

/** One line for the Servers screen: the connected bridge's version and whether it matches the app. */
@Composable
fun BridgeVersionLine(modifier: Modifier = Modifier) {
    val bridge by BridgeInfo.version.collectAsState()
    val b = bridge
    val (text, color) = when {
        b == null -> L.agents.bridgeUi.bridgeVersionNotConnected to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        BridgeInfo.compareBridgeToApp(b) == 0 -> L.agents.bridgeUi.bridgeVersionBMatchesTheApp(b) to Ok
        BridgeInfo.compareBridgeToApp(b) < 0 -> L.agents.bridgeUi.bridgeVersionBTheAppIs(b, BridgeInfo.appVersion) to Warn
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
                Text(L.agents.bridgeUi.antigravityCliAgy, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f).semantics { heading() })
                if (checking) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = L.common.checking }, strokeWidth = 2.dp)
                else IconButton(onClick = { BridgeInfo.refreshAgyCli() }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
            }
            val running = job?.running == true
            when {
                error != null -> Text(error!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                cli == null -> Text(L.common.checking, fontSize = 12.5.sp, color = subtle)
                cli!!.installed -> {
                    Text(cli!!.version.ifBlank { L.agents.bridgeUi.installedVersionUnknown }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                    Button(onClick = { BridgeInfo.installAgy() }, enabled = !running) { Text(L.agents.bridgeUi.installAgy) }
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
                        Text(if (running && job?.kind == "update") L.common.checking else L.common.checkForUpdates)
                    }
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                // a newer agy was installed: the server was restarted so the hub runs it
                updatedTo?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(L.agents.bridgeUi.updatedToAgyValueTheServer(it), fontSize = 12.sp, color = Ok)
                }
            }
            job?.let { j ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "${j.kind}: " + if (j.running) L.agents.bridgeUi.running else L.agents.bridgeUi.finishedExitExitcode(j.exitCode),
                    fontSize = 12.sp,
                    color = if (!j.running && j.exitCode != 0) Color.Red else subtle,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
                // x86_64 build on this device: the bridge installs agy's Linux build behind a launcher (hub/agy_linux_build.go)
                if (!remote && ai.antigem.BuildConfig.TARGET_ABI == "x86_64")
                    L.agents.bridgeUi.googleHasNoAndroidBuildOf
                else
                    L.agents.bridgeUi.installedWithGooglesOfficialInstaller((if (remote) L.agents.bridgeUi.theServer else L.agents.bridgeUi.thisDevice)),
                fontSize = 11.sp, color = subtle
            )
        }
    }
}

/**
 * Settings > Antigravity > Account & CLI: the Google account Antigravity uses, under the CLI card (like Claude Code's
 * Account & CLI). [entry] is Antigravity's status (sign-in, signing in, errors) with its action; null: not known yet.
 */
@Composable
fun AgyAccountCard(
    entry: AgentStatusEntry?,
    auth: ai.antigem.data.remote.AgyHubClient.AgyAuthInfo,
    onSignOut: () -> Unit,
    onCancelSignIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cli by BridgeInfo.agyCli.collectAsState()
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(L.common.account, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            when {
                entry == null -> Text(L.common.checking, fontSize = 12.5.sp, color = subtle)
                cli?.installed == false -> Text(L.agents.bridgeUi.installAgyAboveThenSignIn, fontSize = 12.5.sp, color = subtle)
                entry.isReady -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(auth.displayName.ifBlank { auth.email.ifBlank { L.common.signedIn } }, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        listOf(auth.email.takeIf { it != auth.displayName }.orEmpty(), auth.userTier.ifBlank { auth.planName })
                            .filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }
                            ?.let { Text(it, fontSize = 12.sp, color = subtle) }
                    }
                    Spacer(Modifier.width(8.dp))
                    // asks first: this button takes the place of Check the moment the sign-in lands, so a repeated tap hit it
                    var confirmSignOut by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { confirmSignOut = true }) { Text(L.common.signOut, color = MaterialTheme.colorScheme.error) }
                    if (confirmSignOut) ConfirmSignOutDialog(AgentKind.AGY.label(), auth.email, onConfirm = onSignOut, onDismiss = { confirmSignOut = false })
                }
                entry.isSignedOut -> {
                    Text(L.agents.bridgeUi.signInWithGoogleToChat, fontSize = 12.5.sp, color = subtle)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { entry.action?.onClick?.invoke() }) { Text(L.agents.bridgeUi.signInWithGoogle) }
                }
                // signing in: Check (the browser sign-in finished) or Cancel
                entry.pending && entry.action != null -> {
                    entry.detail?.let { Text(it, fontSize = 12.5.sp, color = subtle, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // tapped: shows "Checking…" until the sign-in state changes (agy needs a few seconds after the browser)
                        var checking by remember(entry.label) { mutableStateOf(false) }
                        Button(enabled = !checking, onClick = { checking = true; entry.action.onClick() }) {
                            Text(if (checking) L.common.checking else entry.action.label)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancelSignIn) { Text(L.common.cancel) }
                    }
                }
                else -> {
                    Text(entry.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = entry.color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    entry.detail?.let { Text(it, fontSize = 12.sp, color = subtle, maxLines = 4) }
                    Text(L.agents.bridgeUi.signInIsAvailableOnceAntigravity, fontSize = 11.5.sp, color = subtle, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
