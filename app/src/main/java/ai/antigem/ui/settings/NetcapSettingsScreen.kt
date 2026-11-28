package ai.antigem.ui.settings

import ai.antigem.data.netcap.NetcapCert
import ai.antigem.data.netcap.NetcapManager
import ai.antigem.data.netcap.NetcapVpnService
import ai.antigem.i18n.L
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NetcapApp(val pkg: String, val label: String)

private fun loadNetcapApps(context: Context, includeSystem: Boolean): List<NetcapApp> {
    val pm = context.packageManager
    val own = context.packageName
    val pkgs: Collection<String> = if (includeSystem) {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0).map { it.packageName }
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    return pkgs.filter { it != own }.map { pkg ->
        val label = runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        NetcapApp(pkg, label)
    }.sortedBy { it.label.lowercase() }
}

/** Settings → Network Interceptor: capture apps' traffic for AI agents (network_mcp) and the user. */
@Composable
internal fun NetcapSettingsScreen(cardBg: Color, cardBorder: BorderStroke) {
    val T = L.netcap.screen
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val st by NetcapManager.state.collectAsState()
    // a downloaded build fetches the engine only when asked (the button below, a capture, an agent)
    var installed by remember { mutableStateOf(NetcapManager.engineInstalled(context)) }
    LaunchedEffect(st.engine) { installed = NetcapManager.engineInstalled(context) }
    LaunchedEffect(Unit) { if (installed) NetcapManager.ensureStarted(context) }
    val mb = { bytes: Long -> "%.1f MB".format(bytes / 1_000_000.0) }

    val selected = remember { mutableStateListOf<String>().apply { addAll(NetcapManager.Prefs.lastApps(context)) } }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<NetcapApp>>(emptyList()) }
    LaunchedEffect(showSystem) { apps = withContext(Dispatchers.IO) { loadNetcapApps(context, showSystem) } }
    var captureBusy by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    var certRefresh by remember { mutableIntStateOf(0) }
    var certStatus by remember { mutableStateOf("") }
    LaunchedEffect(certRefresh, st.engine) { certStatus = withContext(Dispatchers.IO) { NetcapCert.statusText(context) } }
    var certBusy by remember { mutableStateOf(false) }
    var certMessage by remember { mutableStateOf<String?>(null) }

    var agentsMayStart by remember { mutableStateOf(NetcapManager.Prefs.agentsMayStart(context)) }
    var showSecrets by remember { mutableStateOf(NetcapManager.Prefs.showSecrets(context)) }
    var devServers by remember { mutableStateOf(NetcapManager.Prefs.devServers(context).joinToString(", ")) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // status
        val (tint, title) = when {
            st.engine == NetcapManager.Engine.DOWNLOADING -> MaterialTheme.colorScheme.primary to T.downloading(mb(st.downloaded), mb(st.downloadTotal))
            st.engine == NetcapManager.Engine.DOWNLOAD_FAILED -> MaterialTheme.colorScheme.error to T.downloadFailed(st.error ?: "?")
            !installed && st.engine == NetcapManager.Engine.STOPPED -> dim to T.notDownloaded
            st.engine == NetcapManager.Engine.STARTING -> MaterialTheme.colorScheme.primary to T.starting
            st.engine == NetcapManager.Engine.ERROR -> MaterialTheme.colorScheme.error to T.engineError(st.error ?: "?")
            st.capturing -> QuotaGreen to T.capturingApps(st.apps.joinToString(", "))
            else -> dim to T.notCapturing
        }
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.NetworkCheck, null, tint = tint, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (st.engine == NetcapManager.Engine.RUNNING && st.session.isNotEmpty()) {
                        Text(T.requestsRecorded(st.flows, st.session), fontSize = 12.sp, color = dim)
                    }
                    when {
                        st.engine == NetcapManager.Engine.DOWNLOADING -> {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (st.downloadTotal > 0) (st.downloaded.toFloat() / st.downloadTotal).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        !installed && st.engine == NetcapManager.Engine.STOPPED -> {
                            Text(T.downloadHint(mb(NetcapManager.downloadSize)), fontSize = 12.sp, color = dim)
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = { scope.launch { NetcapManager.ensureStarted(context) } }) {
                                Text(T.downloadButton(mb(NetcapManager.downloadSize)))
                            }
                        }
                        st.engine == NetcapManager.Engine.ERROR || st.engine == NetcapManager.Engine.DOWNLOAD_FAILED ->
                            TextButton(onClick = { scope.launch { NetcapManager.ensureStarted(context) } }) { Text(T.retry) }
                    }
                }
            }
        }

        // capture
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.appsToCapture, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(T.pickAppsHint, fontSize = 12.sp, color = dim)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(T.searchApps) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text(T.showSystemApps, fontSize = 13.sp)
                }
                val q = query.trim().lowercase()
                val shown = apps.filter { it.pkg in selected || q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                    .sortedByDescending { it.pkg in selected }
                    .take(if (q.isEmpty()) 12 else 60)
                shown.forEach { app ->
                    val on = app.pkg in selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { if (on) selected.remove(app.pkg) else selected.add(app.pkg) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = { if (it) selected.add(app.pkg) else selected.remove(app.pkg) })
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, fontSize = 11.sp, color = dim, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(T.vpnNote, fontSize = 12.sp, color = dim)
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
                    Row(Modifier.padding(10.dp)) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(T.bankingWarning, fontSize = 12.sp)
                    }
                }
                captureError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.capturing) {
                        Button(onClick = { NetcapVpnService.stop(context) }) { Text(T.stopCapture) }
                    }
                    Button(
                        enabled = selected.isNotEmpty() && !captureBusy,
                        onClick = {
                            captureBusy = true
                            captureError = null
                            scope.launch {
                                captureError = NetcapVpnService.startCapture(context, selected.toList()).exceptionOrNull()?.message
                                captureBusy = false
                            }
                        }
                    ) { Text(if (captureBusy) T.working else T.startCapture) }
                }
                if (selected.isEmpty()) Text(T.pickAtLeastOne, fontSize = 12.sp, color = dim)
            }
        }

        // certificate
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.certificate, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(T.certHint, fontSize = 12.sp, color = dim)
                Text(certStatus, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !certBusy, onClick = {
                        certBusy = true
                        scope.launch {
                            val r = NetcapCert.install(context)
                            certMessage = r.getOrNull() ?: r.exceptionOrNull()?.message
                            certBusy = false
                            certRefresh++
                        }
                    }) { Text(if (certBusy) T.working else T.installWithRoot) }
                    OutlinedButton(enabled = !certBusy, onClick = {
                        certBusy = true
                        scope.launch {
                            val r = NetcapCert.remove(context)
                            certMessage = r.getOrNull() ?: r.exceptionOrNull()?.message
                            certBusy = false
                            certRefresh++
                        }
                    }) { Text(L.common.remove) }
                }
                OutlinedButton(onClick = {
                    certMessage = runCatching { T.savedTo(NetcapCert.saveToDownloads(context).absolutePath) }.getOrElse { it.message }
                }) { Text(T.saveToDownloads) }
                certMessage?.let { Text(it, fontSize = 12.sp) }
                Text(T.restartApps, fontSize = 12.sp, color = dim)
                Text(T.noRootHint, fontSize = 12.sp, color = dim)
            }
        }

        // agents
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(T.agents, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T.allowAgentsStart, fontSize = 14.sp)
                        Text(T.allowAgentsStartHint, fontSize = 12.sp, color = dim)
                    }
                    Switch(checked = agentsMayStart, onCheckedChange = {
                        agentsMayStart = it
                        NetcapManager.Prefs.setAgentsMayStart(context, it)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T.showSecrets, fontSize = 14.sp)
                        Text(T.showSecretsHint, fontSize = 12.sp, color = dim)
                    }
                    Switch(checked = showSecrets, onCheckedChange = {
                        showSecrets = it
                        NetcapManager.Prefs.setShowSecrets(context, it)
                    })
                }
                Text(T.mcpHint, fontSize = 12.sp, color = dim)
            }
        }

        // the user's own test servers with self-signed certificates
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.devServers, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(T.devServersHint, fontSize = 12.sp, color = dim)
                OutlinedTextField(
                    value = devServers, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("192.168.1.10, dev.example.test", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    onValueChange = { devServers = it; NetcapManager.Prefs.setDevServers(context, it) }
                )
            }
        }

        // proxy port for terminal tools
        val proxyCmd = "export HTTP_PROXY=http://127.0.0.1:${NetcapManager.proxyPort} HTTPS_PROXY=http://127.0.0.1:${NetcapManager.proxyPort}"
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.proxyPort, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(T.proxyHint, fontSize = 12.sp, color = dim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(proxyCmd, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(proxyCmd)) }) {
                        Icon(Icons.Outlined.ContentCopy, L.common.copy)
                    }
                }
            }
        }
    }
}
