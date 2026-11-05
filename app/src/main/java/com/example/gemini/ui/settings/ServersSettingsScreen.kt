package com.example.gemini.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.BuildConfig
import com.example.gemini.data.local.LocalServerManager
import com.example.gemini.data.local.LocalServerStatus
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AdbTunnel
import com.example.gemini.data.remote.RemoteServerManager
import com.example.gemini.data.ssh.SshSessionFactory
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// ==========================================
// SUB-SCREEN 2: SERVERS & CONNECTIVITY
// Local (the phone runs the bridge) or Remote (a VPS runs it and the app manages it over SSH)
// ==========================================

private fun describeNetworkError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "unknown host name"
    is java.net.SocketTimeoutException -> "timed out (firewall or wrong port?)"
    is java.net.ConnectException -> "connection refused (is the service running and the port open?)"
    is javax.net.ssl.SSLException -> "TLS problem: ${e.message?.take(80)}"
    else -> e.message?.take(100) ?: e.javaClass.simpleName
}

/** HTTP reachability of the hub address (any answer below 500 means something answers; 502-504 is a proxy that cannot reach the hub). */
private suspend fun probeHub(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = try { conn.responseCode } finally { conn.disconnect() }
        when {
            code in 502..504 -> false to "the proxy answered $code: it cannot reach the hub (is agy --hub running on that port?)"
            code in 200..499 -> true to "answered HTTP $code"
            else -> false to "answered HTTP $code"
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

/** Health + token check of the bridge, using the token typed in the field (even before it is saved). */
private suspend fun probeBridge(base: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()
    try {
        val health = client.newCall(Request.Builder().url("${base.trimEnd('/')}/api/health").build()).execute()
        health.use {
            if (!it.isSuccessful) return@withContext false to "health check answered HTTP ${it.code}"
        }
        val header = AuthPreferences.buildFramedHeader(token)
        val info = client.newCall(
            Request.Builder().url("${base.trimEnd('/')}/api/server/info").header(header, "true").build()
        ).execute()
        info.use {
            when (it.code) {
                200 -> {
                    val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                    val os = json?.optString("os").orEmpty()
                    val arch = json?.optString("arch").orEmpty()
                    val home = json?.optString("home").orEmpty()
                    true to "$os/$arch" + if (home.isNotBlank()) ", home $home" else ""
                }
                401 -> false to "reachable, but the token was rejected: either it differs from the one the server was started with, or the proxy drops headers with an underscore (nginx needs underscores_in_headers on;)"
                404 -> true to "reachable (older bridge without server info)"
                429 -> false to "blocked for a while after too many wrong tokens, try again later"
                else -> false to "info answered HTTP ${it.code}"
            }
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

@Composable
internal fun ServersSubScreen(
    currentHubUrl: String,
    currentBridgeUrl: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSaveServerUrls: (String, String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }

    val initSecurityToken = remember { authPrefs.getSecurityTokenSync() }
    var securityToken by remember { mutableStateOf(initSecurityToken) }

    var remote by remember { mutableStateOf(AuthPreferences.currentRemoteMode) }
    var hubText by remember { mutableStateOf(currentHubUrl.ifBlank { AuthPreferences.DEFAULT_HUB_URL }) }
    var bridgeText by remember { mutableStateOf(currentBridgeUrl.ifBlank { AuthPreferences.DEFAULT_BRIDGE_HTTP_URL }) }

    // local binaries
    var bridgeBinPath by remember { mutableStateOf(authPrefs.getAgyBridgeBinaryPathSync()) }
    var agyBinPath by remember { mutableStateOf(authPrefs.getAgyBinaryPathSync()) }
    var claudeBinPath by remember { mutableStateOf(authPrefs.getClaudeBinaryPathSync()) }

    // SSH (loaded from the saved settings)
    var sshHost by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("") }
    var sshPass by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf(AuthPreferences.currentSshPrivateKey) }
    var sshPassphrase by remember { mutableStateOf(AuthPreferences.currentSshKeyPassphrase) }

    // server side paths and ports (blank = automatic)
    var remoteBridgeBin by remember { mutableStateOf(authPrefs.getRemoteBridgeBinSync()) }
    var remoteAgyBin by remember { mutableStateOf(authPrefs.getRemoteAgyBinSync()) }
    var remoteClaudeBin by remember { mutableStateOf(authPrefs.getRemoteClaudeBinSync()) }
    var remoteInstallDir by remember { mutableStateOf(authPrefs.getRemoteInstallDirSync()) }
    val (initListenBridge, initListenHub, initListenBind) = remember { authPrefs.getRemoteListenSync() }
    var listenBridge by remember { mutableStateOf(initListenBridge) }
    var listenHub by remember { mutableStateOf(initListenHub) }
    var listenBind by remember { mutableStateOf(initListenBind) }
    var showAdvanced by remember { mutableStateOf(false) }

    var hubTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingHub by remember { mutableStateOf(false) }
    var bridgeTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingBridge by remember { mutableStateOf(false) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingSsh by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var saveIsError by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showServerLog by remember { mutableStateOf(false) }

    fun snapshot() = listOf(
        remote, hubText.trim(), bridgeText.trim(), bridgeBinPath.trim(), agyBinPath.trim(), claudeBinPath.trim(),
        securityToken.trim(), sshHost.trim(), sshPort.trim(), sshUser.trim(), sshPass, sshKey.trim(), sshPassphrase,
        remoteBridgeBin.trim(), remoteAgyBin.trim(), remoteClaudeBin.trim(), remoteInstallDir.trim(),
        listenBridge.trim(), listenHub.trim(), listenBind.trim()
    ).joinToString("\u0001")

    var savedSnapshot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val bridgeHost = AuthPreferences.hostOf(currentBridgeUrl)
        val storedHost = authPrefs.termuxSshHost.first()
        sshHost = if (AuthPreferences.currentRemoteMode && AuthPreferences.isLoopbackHost(storedHost) && bridgeHost.isNotBlank() && !AuthPreferences.isLoopbackHost(bridgeHost)) bridgeHost
        else if (AuthPreferences.currentRemoteMode) storedHost else ""
        sshPort = (if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshPort.first() else 22).toString()
        sshUser = if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshUser.first() else ""
        sshPass = if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshPass.first() else ""
        savedSnapshot = snapshot()
    }
    val hasUnsavedChanges = savedSnapshot != null && snapshot() != savedSnapshot

    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges) { showUnsavedDialog = true }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            if (SshSessionFactory.looksLikePrivateKey(text)) {
                sshKey = text.trim()
                saveFeedback = null
            } else {
                Toast.makeText(context, "That file is not a private key (expected -----BEGIN … PRIVATE KEY-----)", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            icon = { Icon(Icons.Outlined.Info, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text("Unsaved Server Settings", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    "You have unsaved changes in the server settings. Save them with the button at the bottom or discard them.",
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showUnsavedDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Keep Editing", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    remote = AuthPreferences.currentRemoteMode
                    hubText = currentHubUrl
                    bridgeText = currentBridgeUrl
                    securityToken = initSecurityToken
                    bridgeBinPath = authPrefs.getAgyBridgeBinaryPathSync()
                    agyBinPath = authPrefs.getAgyBinaryPathSync()
                    claudeBinPath = authPrefs.getClaudeBinaryPathSync()
                    sshKey = AuthPreferences.currentSshPrivateKey
                    sshPassphrase = AuthPreferences.currentSshKeyPassphrase
                    remoteBridgeBin = authPrefs.getRemoteBridgeBinSync()
                    remoteAgyBin = authPrefs.getRemoteAgyBinSync()
                    remoteClaudeBin = authPrefs.getRemoteClaudeBinSync()
                    remoteInstallDir = authPrefs.getRemoteInstallDirSync()
                    listenBridge = initListenBridge; listenHub = initListenHub; listenBind = initListenBind
                    coroutineScope.launch {
                        sshHost = authPrefs.termuxSshHost.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPort = (authPrefs.termuxSshPort.first().takeIf { AuthPreferences.currentRemoteMode } ?: 22).toString()
                        sshUser = authPrefs.termuxSshUser.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPass = authPrefs.termuxSshPass.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                    }
                }) { Text("Discard Changes", color = MaterialTheme.colorScheme.error) }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = { Icon(Icons.Outlined.RestartAlt, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text("Reopen AntiGem?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    if (remote) "Settings saved. Reopening the app connects over SSH, installs or updates the bridge on the server if needed, starts it and opens the connections with the new settings."
                    else "Settings saved. Reopening the app restarts the local bridge and daemon so every connection uses the new settings.",
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartDialog = false
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            context.startActivity(Intent.makeRestartActivityTask(intent.component))
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Reopen Now", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }, shape = RoundedCornerShape(8.dp)) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showServerLog) ServerLogDialog(context) { showServerLog = false }

    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ----- Where does the server run? -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Remote server (SSH)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (remote) "The bridge and the agents run on your server. The app manages them over SSH."
                            else "The bridge and the agents run on this phone.",
                            fontSize = 11.5.sp, color = subtle
                        )
                    }
                    Switch(
                        checked = remote,
                        onCheckedChange = {
                            remote = it
                            saveFeedback = null
                            if (it && AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridgeText))) {
                                // a phone address makes no sense for a server: start from empty https boxes
                                hubText = ""
                                bridgeText = ""
                            } else if (!it) {
                                hubText = AuthPreferences.DEFAULT_HUB_URL
                                bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = QuotaGreen)
                    )
                }
            }
        }

        // ----- The two addresses -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Text("Server addresses", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (remote) "Two addresses, e.g. https://bridge.example.com and https://hub.example.com behind nginx/Apache, or http://203.0.113.5:1234 and http://203.0.113.5:1235 straight to the ports."
                    else "Where this phone's bridge and hub listen.",
                    fontSize = 11.5.sp, color = subtle
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it; saveFeedback = null; bridgeTestStatus = null },
                    label = { Text("IDE Bridge address") },
                    placeholder = { Text(if (remote) "https://bridge.example.com" else AuthPreferences.DEFAULT_BRIDGE_HTTP_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hubText,
                    onValueChange = { hubText = it; saveFeedback = null; hubTestStatus = null },
                    label = { Text("AGY Hub address") },
                    placeholder = { Text(if (remote) "https://hub.example.com" else AuthPreferences.DEFAULT_HUB_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "https:// needs no port. Without https://, a missing port becomes ${BuildConfig.BRIDGE_PORT} (bridge) / ${BuildConfig.HUB_PORT} (hub).",
                    fontSize = 11.sp, color = subtle
                )

                if (remote) {
                    Spacer(Modifier.height(14.dp))
                    Text("Ports on the server", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "The addresses above are what the phone connects to (the reverse proxy). These are the ports the bridge and the hub " +
                            "really listen on, on the server. Behind nginx or Apache use ${BuildConfig.BRIDGE_PORT} and ${BuildConfig.HUB_PORT} " +
                            "(blank = automatic).",
                        fontSize = 11.sp, color = subtle
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(listenBridge, { listenBridge = it.filter(Char::isDigit); saveFeedback = null }, label = { Text("Bridge listens on") },
                            placeholder = { Text(BuildConfig.BRIDGE_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(listenHub, { listenHub = it.filter(Char::isDigit); saveFeedback = null }, label = { Text("Hub listens on") },
                            placeholder = { Text(BuildConfig.HUB_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(listenBind, { listenBind = it; saveFeedback = null }, label = { Text("Bridge bind address") },
                        placeholder = { Text("127.0.0.1 behind a proxy, 0.0.0.0 for a direct address") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                }

                Spacer(Modifier.height(12.dp))
                StatusLine(isServerOnline || hubTestStatus?.startsWith("✓") == true, "Hub", AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT) ?: "invalid address")
                hubTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(6.dp))
                StatusLine(isBridgeOnline || bridgeTestStatus?.startsWith("✓") == true, "Bridge", AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT) ?: "invalid address")
                bridgeTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                            if (url == null) { hubTestStatus = "✗ Not a valid address"; return@OutlinedButton }
                            isTestingHub = true
                            hubTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeHub(url)
                                isTestingHub = false
                                hubTestStatus = if (ok) "✓ Hub reachable ($msg)" else "✗ Hub: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingHub
                    ) {
                        if (isTestingHub) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text("Test Hub", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                            if (url == null) { bridgeTestStatus = "✗ Not a valid address"; return@OutlinedButton }
                            isTestingBridge = true
                            bridgeTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeBridge(url, securityToken)
                                isTestingBridge = false
                                bridgeTestStatus = if (ok) "✓ Bridge reachable ($msg)" else "✗ Bridge: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingBridge
                    ) {
                        if (isTestingBridge) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text("Test Bridge", fontSize = 12.sp)
                    }
                }
            }
        }

        if (remote) {
            // ----- SSH -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SSH access", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Used to install, start and stop the bridge, for the terminal and to let the server's agents use this phone's MCP tools.",
                                fontSize = 11.5.sp, color = subtle
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sshHost, onValueChange = { sshHost = it; sshTestStatus = null; saveFeedback = null },
                            label = { Text("SSH host") }, placeholder = { Text("server host or IP") },
                            modifier = Modifier.weight(2f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            value = sshPort, onValueChange = { sshPort = it.filter(Char::isDigit); sshTestStatus = null; saveFeedback = null },
                            label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshUser, onValueChange = { sshUser = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text("User") }, placeholder = { Text("not root if you can avoid it") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshPass, onValueChange = { sshPass = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text("Password (optional when a key is set)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshKey, onValueChange = { sshKey = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text("Private key (recommended)") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                        modifier = Modifier.fillMaxWidth().height(110.dp), minLines = 3, maxLines = 4, shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                            Text("Choose key file", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                if (SshSessionFactory.looksLikePrivateKey(text)) { sshKey = text.trim(); saveFeedback = null }
                                else Toast.makeText(context, "The clipboard does not hold a private key", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text("Paste key", fontSize = 12.sp) }
                    }
                    if (sshKey.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sshPassphrase, onValueChange = { sshPassphrase = it; saveFeedback = null },
                            label = { Text("Key passphrase (if it has one)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    sshTestStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val host = sshHost.trim()
                                if (host.isBlank() || sshUser.isBlank()) { sshTestStatus = "✗ Enter the host and the user first"; return@OutlinedButton }
                                isTestingSsh = true
                                sshTestStatus = "Connecting to $host…"
                                val prevKey = AuthPreferences.currentSshPrivateKey
                                val prevPass = AuthPreferences.currentSshKeyPassphrase
                                AuthPreferences.currentSshPrivateKey = sshKey.trim()
                                AuthPreferences.currentSshKeyPassphrase = sshPassphrase
                                coroutineScope.launch {
                                    val res = RemoteServerManager.testSsh(host, sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass)
                                    AuthPreferences.currentSshPrivateKey = prevKey
                                    AuthPreferences.currentSshKeyPassphrase = prevPass
                                    isTestingSsh = false
                                    sshTestStatus = res.fold(
                                        { "✓ SSH works: " + it.replace('\n', ' ') },
                                        { "✗ ${it.message}" }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingSsh
                        ) {
                            if (isTestingSsh) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            Text("Test SSH", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                SshSessionFactory.forgetHostKey(sshHost.trim(), sshPort.toIntOrNull() ?: 22)
                                Toast.makeText(context, "Saved host key forgotten. The next connection trusts the server's current key.", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text("Forget host key", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The server's SSH key is pinned the first time you connect; a different key later is refused.",
                        fontSize = 11.sp, color = subtle
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Advanced (server paths and ports)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide" else "Show", fontSize = 12.sp) }
                    }
                    if (showAdvanced) {
                        Text("Leave blank for automatic.", fontSize = 11.sp, color = subtle)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteBridgeBin, { remoteBridgeBin = it; saveFeedback = null }, label = { Text("Bridge binary on the server") },
                            placeholder = { Text("~/.antigem/agy_ide_bridge (uploaded by the app)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteAgyBin, { remoteAgyBin = it; saveFeedback = null }, label = { Text("agy binary on the server") },
                            placeholder = { Text("found automatically (~/.local/bin/agy …)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteClaudeBin, { remoteClaudeBin = it; saveFeedback = null }, label = { Text("claude binary on the server") },
                            placeholder = { Text("claude from PATH") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteInstallDir, { remoteInstallDir = it; saveFeedback = null }, label = { Text("Install folder (in the server home)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    }
                }
            }

            // ----- live server control (only once Remote mode is active) -----
            if (AuthPreferences.currentRemoteMode) RemoteServerCard(cardBg, cardBorder, subtle, onShowLog = {
                RemoteServerManager.refreshLog(context)
                showServerLog = true
            })
        } else {
            // ----- local binaries -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Binary Executable Paths", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Custom paths used when auto-starting IDE Bridge, AGY Hub and Claude Code", fontSize = 11.5.sp, color = subtle)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(bridgeBinPath, { bridgeBinPath = it; saveFeedback = null }, label = { Text("IDE Bridge Binary Path") },
                        placeholder = { Text(authPrefs.getDefaultBridgeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(agyBinPath, { agyBinPath = it; saveFeedback = null }, label = { Text("AGY Hub Binary Path") },
                        placeholder = { Text(authPrefs.getDefaultAgyBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(claudeBinPath, { claudeBinPath = it; saveFeedback = null }, label = { Text("Claude Code Binary Path") },
                        placeholder = { Text(authPrefs.getDefaultClaudeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Passed to the bridge on launch (--bin for AGY, --claude-bin for Claude Code) so auto-start executes the designated binary directly without interactive prompts.",
                        fontSize = 11.sp, color = subtle
                    )
                    if (com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context)) {
                        Text(
                            "• Claude Code: the default is AntiGem's launcher, which runs the newest official Claude version through the bundled glibc. Claude updates never replace it.",
                            fontSize = 11.sp, color = subtle
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            hubText = AuthPreferences.DEFAULT_HUB_URL
                            bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL
                            bridgeBinPath = authPrefs.getDefaultBridgeBinaryPath()
                            agyBinPath = authPrefs.getDefaultAgyBinaryPath()
                            claudeBinPath = authPrefs.getDefaultClaudeBinaryPath()
                            saveFeedback = "Settings reset to defaults (click Save to apply)."
                            saveIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset All to Defaults", fontSize = 12.sp)
                    }
                }
            }
        }

        // ----- Security token -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Security Token (Header Obfuscation)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (remote) "The secret that protects the bridge. The app starts the server with it. Use https:// on the internet: over plain http it is visible on the network."
                            else "12-char token framing localhost requests to prevent unauthorized app access",
                            fontSize = 11.5.sp, color = subtle
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = securityToken,
                    onValueChange = {
                        securityToken = it.take(AuthPreferences.TOKEN_LENGTH).filter { c -> c.isLetterOrDigit() }
                        saveFeedback = null
                    },
                    label = { Text("12-Character Secret Token") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        Text(
                            "${securityToken.length}/12", fontSize = 11.sp,
                            color = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                val framedHeader = AuthPreferences.buildFramedHeader(securityToken)
                Text("HTTP Framed Header: $framedHeader", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { securityToken = AuthPreferences.generateRandomToken(); saveFeedback = null },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("Randomize", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Framed Security Header", framedHeader))
                            Toast.makeText(context, "Copied $framedHeader to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("Copy Header", fontSize = 12.sp)
                    }
                }
            }
        }

        saveFeedback?.let {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = (if (saveIsError) Color.Red else QuotaGreen).copy(alpha = 0.12f)) {
                Text(it, fontSize = 12.sp, color = if (saveIsError) Color.Red else QuotaGreen, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            }
        }

        Button(
            onClick = {
                val hub = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                val bridge = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                val problem = when {
                    bridge == null -> "The IDE Bridge address is not valid."
                    hub == null -> "The AGY Hub address is not valid."
                    remote && AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridge)) && sshHost.isBlank() ->
                        "A remote server needs its address (not 127.0.0.1) or an SSH host."
                    remote && sshHost.isBlank() -> "Enter the SSH host."
                    remote && sshUser.isBlank() -> "Enter the SSH user."
                    remote && sshPass.isBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> "Enter an SSH password or a private key."
                    remote && sshKey.isNotBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> "The SSH key is not a valid private key."
                    else -> null
                }
                if (problem != null || hub == null || bridge == null) {
                    saveFeedback = problem ?: "Check the addresses."
                    saveIsError = true
                    return@Button
                }
                hubText = hub
                bridgeText = bridge
                onSaveServerUrls(hub, bridge)
                val tokenToSave = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) securityToken else AuthPreferences.generateRandomToken()
                securityToken = tokenToSave
                coroutineScope.launch {
                    authPrefs.saveSecurityToken(tokenToSave)
                    authPrefs.saveRemoteModeSync(remote)
                    if (remote) {
                        authPrefs.saveSshSettings(sshHost.trim(), sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass)
                        authPrefs.setUseSshTerminal(true)
                        authPrefs.saveSshKeySync(sshKey, sshPassphrase)
                        authPrefs.saveRemoteBinariesSync(remoteBridgeBin, remoteAgyBin, remoteClaudeBin)
                        authPrefs.saveRemoteInstallDirSync(remoteInstallDir)
                        authPrefs.saveRemoteListenSync(listenBridge, listenHub, listenBind)
                    } else {
                        authPrefs.saveAgyBridgeBinaryPath(bridgeBinPath.trim().ifBlank { authPrefs.getDefaultBridgeBinaryPath() })
                        authPrefs.saveAgyBinaryPath(agyBinPath.trim().ifBlank { authPrefs.getDefaultAgyBinaryPath() })
                        authPrefs.saveClaudeBinaryPath(claudeBinPath.trim().ifBlank { authPrefs.getDefaultClaudeBinaryPath() })
                    }
                }
                savedSnapshot = snapshot()
                saveFeedback = "✓ Server, connection and security settings saved!"
                saveIsError = false
                showRestartDialog = true
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save Server & Security Settings", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusLine(online: Boolean, label: String, address: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = if (online) QuotaGreen else Color.Red) {}
        Spacer(Modifier.width(6.dp))
        Text("$label: $address", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

/** Live controls of the SSH-managed server: connection, progress, start/stop and the MCP tunnels. */
@Composable
private fun RemoteServerCard(cardBg: Color, cardBorder: BorderStroke, subtle: Color, onShowLog: () -> Unit) {
    val context = LocalContext.current
    val status by LocalServerManager.status.collectAsState()
    val ssh by RemoteServerManager.sshState.collectAsState()
    val detail by RemoteServerManager.detail.collectAsState()
    val tunnels by RemoteServerManager.tunnels.collectAsState()

    val (label, color) = when (val s = status) {
        is LocalServerStatus.Running -> "Bridge running on the server" to QuotaGreen
        is LocalServerStatus.Starting -> "Starting…" to Color(0xFFF59E0B)
        is LocalServerStatus.Stopping -> "Stopping…" to Color(0xFFF59E0B)
        is LocalServerStatus.Stopped -> "Bridge stopped" to Color(0xFF9CA3AF)
        is LocalServerStatus.Error -> s.message to Color.Red
        is LocalServerStatus.Idle -> "Not started" to Color(0xFF9CA3AF)
    }

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp)) {
            Text("Server control", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Surface(Modifier.padding(top = 4.dp).size(8.dp), shape = CircleShape, color = color) {}
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                    if (detail.isNotBlank()) Text(detail, fontSize = 11.5.sp, color = subtle)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "SSH: " + ssh.message,
                fontSize = 11.5.sp, color = if (ssh.connected) QuotaGreen else subtle
            )
            if (tunnels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Phone tools for the server's agents (reverse tunnels):", fontSize = 11.sp, color = subtle)
                tunnels.forEach { Text("• $it", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = subtle) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { LocalServerManager.startServer(context, forceRestart = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Start", fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.restartServer(context) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Restart", fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.stopServer() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Stop", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onShowLog, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Server log", fontSize = 12.sp) }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))
            AdbTunnelSection(subtle)
        }
    }
}

/** "Let the server use this phone's adb": a reverse tunnel for the server's own `adb`. */
@Composable
private fun AdbTunnelSection(subtle: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adb by AdbTunnel.state.collectAsState()
    LaunchedEffect(Unit) { AdbTunnel.load(context) }
    var showPair by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Let the server use this phone's adb", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Opens 127.0.0.1:${adb.serverPort} on the server to this phone's adb. Full control of the phone: only enable it for a server you trust.",
                fontSize = 11.sp, color = subtle
            )
        }
        Switch(
            checked = adb.enabled,
            onCheckedChange = { AdbTunnel.setEnabled(context, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = QuotaGreen)
        )
    }
    if (adb.enabled) {
        Spacer(Modifier.height(6.dp))
        val status = when {
            adb.phonePort == null -> "Phone adb: not found"
            adb.phoneMode == "tcpip" -> "Phone adb: fixed port ${adb.phonePort} (adb tcpip)"
            else -> "Phone adb: Wireless debugging, port ${adb.phonePort}"
        }
        Text(status, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (adb.phonePort != null) QuotaGreen else Color(0xFFF59E0B))
        if (adb.message.isNotBlank()) Text(adb.message, fontSize = 11.sp, color = subtle)
        Spacer(Modifier.height(6.dp))
        Text("On the server:", fontSize = 11.sp, color = subtle)
        Text("adb connect 127.0.0.1:${adb.serverPort}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        var installMsg by remember { mutableStateOf<String?>(null) }
        var installing by remember { mutableStateOf(false) }
        if (adb.message.contains("not installed on the server")) {
            OutlinedButton(
                enabled = !installing,
                onClick = {
                    installing = true; installMsg = "Installing adb on the server…"
                    scope.launch {
                        val r = AdbTunnel.installServerAdb()
                        installing = false
                        installMsg = r.fold({ it }, { it.message ?: "Install failed" })
                    }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
            ) { Text("Install adb on the server", fontSize = 12.sp) }
            installMsg?.let { Text(it, fontSize = 11.sp, color = subtle, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(6.dp))
        }
        if (adb.phoneMode != "tcpip") {
            OutlinedButton(onClick = { showPair = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Text("Pair the server (Wireless debugging)", fontSize = 12.sp)
            }
        }
    }
    if (showPair) AdbPairDialog(adb, onDismiss = {
        showPair = false
        scope.launch { AdbTunnel.stopPairingForward(context) }
    })
}

@Composable
private fun AdbPairDialog(adb: AdbTunnel.State, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair the server's adb", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "Wireless debugging only accepts a computer once it has been paired.\n\n" +
                        "1. On this phone open Developer options > Wireless debugging > \"Pair device with pairing code\". Keep that dialog open.\n" +
                        "2. Type the 6-digit code it shows here, and tap Pair. The app does the rest on the server.",
                    fontSize = 12.5.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6-digit pairing code") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = manualPort, onValueChange = { manualPort = it.filter(Char::isDigit) },
                    label = { Text("Port shown in the dialog (only if it is not found by itself)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it.second, fontSize = 12.sp, color = if (it.first) QuotaGreen else Color.Red)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true; result = null
                    scope.launch {
                        val r = AdbTunnel.pairServer(context, code, manualPort.toIntOrNull())
                        busy = false
                        result = r.fold({ true to it }, { false to (it.message ?: "Pairing failed") })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) { Text(if (busy) "Pairing…" else "Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun ServerLogDialog(context: Context, onDismiss: () -> Unit) {
    val log by RemoteServerManager.log.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server log", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                Text(
                    log.ifBlank { "(empty: the bridge has not written anything yet or SSH is not connected)" },
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { RemoteServerManager.refreshLog(context) }) { Text("Refresh") } },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
