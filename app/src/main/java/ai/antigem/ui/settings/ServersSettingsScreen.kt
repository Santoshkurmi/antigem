package ai.antigem.ui.settings

import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.BuildConfig
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalServerStatus
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.RemoteServerManager
import ai.antigem.data.ssh.SshSessionFactory
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.QuotaGreen
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
import ai.antigem.i18n.L

// ==========================================
// SUB-SCREEN 2: SERVERS & CONNECTIVITY
// Local (the phone runs the bridge) or Remote (a VPS runs it and the app manages it over SSH)
// ==========================================

private fun describeNetworkError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> L.settings.serversSettings.unknownHostName
    is java.net.SocketTimeoutException -> L.settings.serversSettings.timedOutFirewallOrWrongPort
    is java.net.ConnectException -> L.settings.serversSettings.connectionRefusedIsTheServiceRunning
    is javax.net.ssl.SSLException -> L.settings.serversSettings.tlsProblemValue(e.message?.take(80))
    is java.net.SocketException, is java.io.EOFException ->
        L.settings.serversSettings.theConnectionWasClosedAProxy
    else -> {
        val m = e.message.orEmpty()
        if (m.contains("unexpected end of stream", true) || m.contains("connection reset", true))
            L.settings.serversSettings.theConnectionWasClosedWithNo
        else m.take(100).ifBlank { e.javaClass.simpleName }
    }
}

/** HTTP reachability of the hub address (any answer below 500 means something answers; 502-504 is a proxy that cannot reach the hub). */
private suspend fun probeHub(url: String, edge: Pair<String, String>? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        edge?.let { conn.setRequestProperty(it.first, it.second) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = try { conn.responseCode } finally { conn.disconnect() }
        when {
            code in 502..504 -> false to L.settings.serversSettings.theProxyAnsweredCodeItCannot(code)
            code in 200..499 -> true to L.settings.serversSettings.answeredHttpCode(code)
            else -> false to L.settings.serversSettings.answeredHttpCode(code)
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

/** Health + token check of the bridge, using the token typed in the field (even before it is saved). */
private suspend fun probeBridge(base: String, token: String, edge: Pair<String, String>? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()
    try {
        val health = client.newCall(Request.Builder().url("${base.trimEnd('/')}/api/health").apply { edge?.let { header(it.first, it.second) } }.build()).execute()
        health.use {
            if (!it.isSuccessful) return@withContext false to L.settings.serversSettings.healthCheckAnsweredHttpCode(it.code)
        }
        val header = AuthPreferences.buildFramedHeader(token)
        val info = client.newCall(
            Request.Builder().url("${base.trimEnd('/')}/api/server/info").header(header, "true").apply { edge?.let { header(it.first, it.second) } }.build()
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
                404 -> true to L.settings.serversSettings.reachableOlderBridgeWithoutServerInfo
                429 -> false to L.settings.serversSettings.blockedForAWhileAfterToo
                else -> false to L.settings.serversSettings.infoAnsweredHttpCode(it.code)
            }
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun ServersSubScreen(
    currentHubUrl: String,
    currentBridgeUrl: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSaveServerUrls: (String, String) -> Unit,
    /** Opens the Termux environment's installer. */
    onInstallTermux: () -> Unit = {},
    /** Opens the page of the Ubuntu chroot at this path. */
    onManageChroot: (String) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }

    val initSecurityToken = remember { authPrefs.getSecurityTokenSync() }
    var securityToken by remember { mutableStateOf(initSecurityToken) }

    var remote by remember { mutableStateOf(AuthPreferences.currentRemoteMode) }
    // only with remote: reach the server's bridge/hub through the SSH connection instead of a domain or open ports
    var tunnel by remember { mutableStateOf(AuthPreferences.currentRemoteMode && AuthPreferences.currentRemoteTunnel) }
    // a direct http:// address on the network: (hub, bridge, host) waiting for the user's typed OK, and the host they OK'd
    var clearTextAsk by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var clearHostAccepted by remember { mutableStateOf<String?>(AuthPreferences.hostOf(AuthPreferences.currentBridgeHttpUrl).takeIf {
        AuthPreferences.currentRemoteMode && !AuthPreferences.currentRemoteTunnel && AuthPreferences.currentBridgeHttpUrl.startsWith("http://") }) }
    // on this phone: the Termux environment or the rooted Ubuntu chroot
    var phoneChroot by remember { mutableStateOf(authPrefs.isChrootModeSync()) }
    var showFactoryReset by remember { mutableStateOf(false) }
    var confirmNewPhoneKey by remember { mutableStateOf(false) }
    var hubText by remember { mutableStateOf(currentHubUrl.ifBlank { AuthPreferences.DEFAULT_HUB_URL }) }
    var bridgeText by remember { mutableStateOf(currentBridgeUrl.ifBlank { AuthPreferences.DEFAULT_BRIDGE_HTTP_URL }) }

    // local binaries
    // every path field is empty unless the user set a path: empty = the default (shown as the field's hint)
    fun custom(value: String, default: String) = value.trim().takeIf { it != default.trim() }.orEmpty()
    var bridgeBinPath by remember { mutableStateOf(custom(authPrefs.getAgyBridgeBinaryPathSync(), authPrefs.getDefaultBridgeBinaryPath())) }
    var agyBinPath by remember { mutableStateOf(custom(authPrefs.getAgyBinaryPathSync(), authPrefs.getDefaultAgyBinaryPath())) }
    var claudeBinPath by remember { mutableStateOf(custom(authPrefs.getClaudeBinaryPathSync(), authPrefs.getDefaultClaudeBinaryPath())) }
    var openCodeBinPath by remember { mutableStateOf(custom(authPrefs.getOpenCodeBinaryPathSync(), authPrefs.getDefaultOpenCodeBinaryPath())) }
    var codexBinPath by remember { mutableStateOf(custom(authPrefs.getCodexBinaryPathSync(), authPrefs.getDefaultCodexBinaryPath())) }
    // the Ubuntu chroot's own agent paths (blank = the default, where the agent's installer puts it)
    var chrootBridgeBin by remember { mutableStateOf(authPrefs.getChrootBridgeBinSync()) }
    var chrootAgyBin by remember { mutableStateOf(authPrefs.getChrootAgyBinSync()) }
    var chrootClaudeBin by remember { mutableStateOf(authPrefs.getChrootClaudeBinSync()) }
    var chrootOpenCodeBin by remember { mutableStateOf(authPrefs.getChrootOpenCodeBinSync()) }
    var chrootCodexBin by remember { mutableStateOf(authPrefs.getChrootCodexBinSync()) }

    // SSH (loaded from the saved settings)
    var sshHost by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("") }
    var sshPass by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf(AuthPreferences.currentSshPrivateKey) }
    var sshPassphrase by remember { mutableStateOf(AuthPreferences.currentSshKeyPassphrase) }

    // server side paths and ports (blank = automatic)
    // a blank saved value means "the default", so the default itself is shown (and saved) instead of an empty box
    fun storedBridgeBin() = custom(authPrefs.getRemoteBridgeBinSync(), RemoteServerManager.defaultBridgeBin(authPrefs.getRemoteInstallDirSync()))
    fun storedAgyBin() = custom(authPrefs.getRemoteAgyBinSync(), RemoteServerManager.DEFAULT_AGY_BIN)
    fun storedClaudeBin() = custom(authPrefs.getRemoteClaudeBinSync(), RemoteServerManager.DEFAULT_CLAUDE_BIN)
    fun storedOpenCodeBin() = custom(authPrefs.getRemoteOpenCodeBinSync(), RemoteServerManager.DEFAULT_OPENCODE_BIN)
    fun storedCodexBin() = custom(authPrefs.getRemoteCodexBinSync(), RemoteServerManager.DEFAULT_CODEX_BIN)
    var remoteBridgeBin by remember { mutableStateOf(storedBridgeBin()) }
    var remoteAgyBin by remember { mutableStateOf(storedAgyBin()) }
    var remoteClaudeBin by remember { mutableStateOf(storedClaudeBin()) }
    var remoteOpenCodeBin by remember { mutableStateOf(storedOpenCodeBin()) }
    var remoteCodexBin by remember { mutableStateOf(storedCodexBin()) }
    var remoteInstallDir by remember { mutableStateOf(authPrefs.getRemoteInstallDirSync()) }
    val (initListenBridge, initListenHub, initListenBind) = remember { authPrefs.getRemoteListenSync() }
    var listenBridge by remember { mutableStateOf(initListenBridge) }
    var listenHub by remember { mutableStateOf(initListenHub) }
    var listenBind by remember { mutableStateOf(initListenBind) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Server mode: an extra header the reverse proxy checks before it lets a request through to the bridge or the hub
    val (initEdgeName, initEdgeValue) = remember { authPrefs.getEdgeHeaderSync() }
    var edgeName by remember { mutableStateOf(initEdgeName) }
    var edgeValue by remember { mutableStateOf(initEdgeValue) }
    fun edgeOrNull(): Pair<String, String>? =
        if (remote && !tunnel && edgeName.trim().isNotEmpty() && edgeValue.trim().isNotEmpty()) edgeName.trim() to edgeValue.trim() else null

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
    var showProfileMenu by remember { mutableStateOf(false) }
    var showSaveProfile by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var profileWithSecrets by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var profileToUpdate by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var loadedProfile by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var profileTick by remember { mutableIntStateOf(0) }

    fun snapshot() = listOf(
        remote, tunnel, phoneChroot, hubText.trim(), bridgeText.trim(), bridgeBinPath.trim(), agyBinPath.trim(), claudeBinPath.trim(), openCodeBinPath.trim(), codexBinPath.trim(),
        chrootBridgeBin.trim(), chrootAgyBin.trim(), chrootClaudeBin.trim(), chrootOpenCodeBin.trim(), chrootCodexBin.trim(),
        securityToken.trim(), sshHost.trim(), sshPort.trim(), sshUser.trim(), sshPass, sshKey.trim(), sshPassphrase,
        remoteBridgeBin.trim(), remoteAgyBin.trim(), remoteClaudeBin.trim(), remoteOpenCodeBin.trim(), remoteCodexBin.trim(), remoteInstallDir.trim(),
        listenBridge.trim(), listenHub.trim(), listenBind.trim(), edgeName.trim(), edgeValue.trim()
    ).joinToString("\u0001")

    // Each mode keeps its own saved settings (JSON), so switching back and forth does not make you type everything again.
    fun profileJson(forRemote: Boolean, withSecrets: Boolean): org.json.JSONObject = org.json.JSONObject().apply {
        put("hub", hubText.trim()); put("bridge", bridgeText.trim())
        if (forRemote) {
            put("tunnel", tunnel)
            put("sshHost", sshHost.trim()); put("sshPort", sshPort.trim()); put("sshUser", sshUser.trim())
            put("edgeName", edgeName.trim())
            if (withSecrets) { put("sshPass", sshPass); put("sshKey", sshKey.trim()); put("sshPassphrase", sshPassphrase); put("edgeValue", edgeValue.trim()) }
            put("bridgeBin", remoteBridgeBin.trim()); put("agyBin", remoteAgyBin.trim()); put("claudeBin", remoteClaudeBin.trim())
            put("openCodeBin", remoteOpenCodeBin.trim())
            put("codexBin", remoteCodexBin.trim())
            put("installDir", remoteInstallDir.trim())
            put("listenBridge", listenBridge.trim()); put("listenHub", listenHub.trim()); put("listenBind", listenBind.trim())
        } else {
            put("bridgeBin", bridgeBinPath.trim()); put("agyBin", agyBinPath.trim()); put("claudeBin", claudeBinPath.trim())
            put("openCodeBin", openCodeBinPath.trim())
            put("codexBin", codexBinPath.trim())
        }
    }

    fun applyProfile(forRemote: Boolean, o: org.json.JSONObject) {
        hubText = o.optString("hub", hubText); bridgeText = o.optString("bridge", bridgeText)
        tunnel = forRemote && o.optBoolean("tunnel", false)
        if (forRemote) {
            sshHost = o.optString("sshHost"); sshPort = o.optString("sshPort", "22").ifBlank { "22" }
            sshUser = o.optString("sshUser")
            if (o.has("sshPass")) { sshPass = o.optString("sshPass"); sshKey = o.optString("sshKey"); sshPassphrase = o.optString("sshPassphrase") }
            edgeName = o.optString("edgeName").ifBlank { AuthPreferences.DEFAULT_EDGE_HEADER_NAME }
            if (o.has("edgeValue")) edgeValue = o.optString("edgeValue")
            remoteInstallDir = o.optString("installDir").ifBlank { ".antigem" }
            remoteBridgeBin = custom(o.optString("bridgeBin"), RemoteServerManager.defaultBridgeBin(remoteInstallDir))
            remoteAgyBin = custom(o.optString("agyBin"), RemoteServerManager.DEFAULT_AGY_BIN)
            remoteClaudeBin = custom(o.optString("claudeBin"), RemoteServerManager.DEFAULT_CLAUDE_BIN)
            remoteOpenCodeBin = custom(o.optString("openCodeBin"), RemoteServerManager.DEFAULT_OPENCODE_BIN)
            remoteCodexBin = custom(o.optString("codexBin"), RemoteServerManager.DEFAULT_CODEX_BIN)
            listenBridge = o.optString("listenBridge"); listenHub = o.optString("listenHub"); listenBind = o.optString("listenBind")
        } else {
            bridgeBinPath = custom(o.optString("bridgeBin", bridgeBinPath), authPrefs.getDefaultBridgeBinaryPath())
            agyBinPath = custom(o.optString("agyBin", agyBinPath), authPrefs.getDefaultAgyBinaryPath())
            claudeBinPath = custom(o.optString("claudeBin", claudeBinPath), authPrefs.getDefaultClaudeBinaryPath())
            openCodeBinPath = custom(o.optString("openCodeBin", openCodeBinPath), authPrefs.getDefaultOpenCodeBinaryPath())
            codexBinPath = custom(o.optString("codexBin", codexBinPath), authPrefs.getDefaultCodexBinaryPath())
        }
    }

    /** Local (phone), Server (domain or open ports) or Server through an SSH tunnel. */
    fun selectMode(r: Boolean, t: Boolean) {
        if (r == remote && t == tunnel) return
        saveFeedback = null; hubTestStatus = null; bridgeTestStatus = null
        val bridgeLoopback = AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT).orEmpty()))
        val hubLoopback = AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT).orEmpty()))
        when {
            !r -> { hubText = AuthPreferences.DEFAULT_HUB_URL; bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL }
            // the tunnel needs 127.0.0.1 addresses: start from the default ports (= what the server listens on)
            t -> {
                if (!bridgeLoopback) bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL
                if (!hubLoopback) hubText = AuthPreferences.DEFAULT_HUB_URL
            }
            // a phone address makes no sense for a server reached directly: start from empty boxes
            bridgeLoopback -> { hubText = ""; bridgeText = "" }
        }
        remote = r; tunnel = r && t
    }

    /** This phone, with the Termux environment or the Ubuntu chroot: each has its own default ports (an address set by hand stays). */
    fun selectPhone(chroot: Boolean) {
        if (!remote && chroot == phoneChroot) return
        val wasRemote = remote
        selectMode(false, false)
        phoneChroot = chroot
        val h = hubText.trim().trimEnd('/')
        val b = bridgeText.trim().trimEnd('/')
        if (wasRemote || h.isEmpty() || h == AuthPreferences.defaultLocalHubUrl(!chroot)) hubText = AuthPreferences.defaultLocalHubUrl(chroot)
        if (wasRemote || b.isEmpty() || b == AuthPreferences.defaultLocalBridgeUrl(!chroot)) bridgeText = AuthPreferences.defaultLocalBridgeUrl(chroot)
    }

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
                Toast.makeText(context, L.settings.serversSettings.thatFileIsNotAPrivate, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            icon = { Icon(Icons.Outlined.Info, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text(L.settings.serversSettings.unsavedServerSettings, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    L.settings.serversSettings.youHaveUnsavedChangesInThe,
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showUnsavedDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(L.settings.serversSettings.keepEditing, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    remote = AuthPreferences.currentRemoteMode
                    tunnel = AuthPreferences.currentRemoteMode && AuthPreferences.currentRemoteTunnel
                    hubText = currentHubUrl
                    bridgeText = currentBridgeUrl
                    securityToken = initSecurityToken
                    bridgeBinPath = custom(authPrefs.getAgyBridgeBinaryPathSync(), authPrefs.getDefaultBridgeBinaryPath())
                    agyBinPath = custom(authPrefs.getAgyBinaryPathSync(), authPrefs.getDefaultAgyBinaryPath())
                    claudeBinPath = custom(authPrefs.getClaudeBinaryPathSync(), authPrefs.getDefaultClaudeBinaryPath())
                    openCodeBinPath = custom(authPrefs.getOpenCodeBinaryPathSync(), authPrefs.getDefaultOpenCodeBinaryPath())
                    codexBinPath = custom(authPrefs.getCodexBinaryPathSync(), authPrefs.getDefaultCodexBinaryPath())
                    sshKey = AuthPreferences.currentSshPrivateKey
                    sshPassphrase = AuthPreferences.currentSshKeyPassphrase
                    remoteBridgeBin = storedBridgeBin()
                    remoteAgyBin = storedAgyBin()
                    remoteClaudeBin = storedClaudeBin()
                    remoteOpenCodeBin = storedOpenCodeBin()
                    remoteCodexBin = storedCodexBin()
                    remoteInstallDir = authPrefs.getRemoteInstallDirSync()
                    listenBridge = initListenBridge; listenHub = initListenHub; listenBind = initListenBind
                    edgeName = initEdgeName; edgeValue = initEdgeValue
                    coroutineScope.launch {
                        sshHost = authPrefs.termuxSshHost.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPort = (authPrefs.termuxSshPort.first().takeIf { AuthPreferences.currentRemoteMode } ?: 22).toString()
                        sshUser = authPrefs.termuxSshUser.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPass = authPrefs.termuxSshPass.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                    }
                }) { Text(L.settings.serversSettings.discardChanges, color = MaterialTheme.colorScheme.error) }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = { Icon(Icons.Outlined.RestartAlt, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text(L.settings.serversSettings.reopenAntigem, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    if (remote) L.settings.serversSettings.settingsSavedReopeningTheAppConnects
                    else L.settings.serversSettings.settingsSavedReopeningTheAppRestarts,
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
                ) { Text(L.settings.serversSettings.reopenNow, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }, shape = RoundedCornerShape(8.dp)) {
                    Text(L.common.later, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showProfileMenu) {
        val list = remember(profileTick) { authPrefs.listProfiles(true) + authPrefs.listProfiles(false) }
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showProfileMenu = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(L.settings.serversSettings.savedProfiles, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(L.settings.serversSettings.tapOneToLoadItIts, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                if (list.isEmpty()) {
                    Text(L.settings.serversSettings.nothingSavedYetFillInThe, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 24.dp))
                }
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { o ->
                        val r = o.optBoolean("remote")
                        val accent = if (r) QuotaGreen else ClaudeTerracotta
                        val where = AuthPreferences.hostOf(o.optString("bridge")).ifBlank { AuthPreferences.hostOf(o.optString("hub")) }
                            .let { h -> if (r && o.optString("sshUser").isNotBlank()) "${o.optString("sshUser")}@${o.optString("sshHost").ifBlank { h }}" else h }
                        Surface(
                            onClick = {
                                remote = r
                                applyProfile(r, o)
                                loadedProfile = r to o.optString("name")
                                showProfileMenu = false
                                saveFeedback = L.settings.serversSettings.loadedProfileOptstringTapSaveBelow(o.optString("name"))
                                saveIsError = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = cardBg,
                            border = cardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (r) Icons.Outlined.Dns else Icons.Outlined.Terminal, null, tint = accent, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(o.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(8.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.15f)) {
                                            Text(if (r && o.optBoolean("tunnel")) "SSH TUNNEL" else if (r) L.settings.serversSettings.server else L.settings.serversSettings.local, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = accent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                        }
                                    }
                                    if (where.isNotBlank()) Text(where, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                                }
                                TextButton(enabled = r == remote, onClick = { profileToUpdate = o }) {
                                    Text(L.common.update, fontSize = 12.sp)
                                }
                                TextButton(onClick = { profileToDelete = r to o.optString("name") }) {
                                    Text(L.common.delete, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveProfile) {
        AlertDialog(
            onDismissRequest = { showSaveProfile = false },
            title = { Text(if (remote) L.settings.serversSettings.saveServerProfile else L.settings.serversSettings.saveLocalProfile, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(profileName, { profileName = it }, label = { Text(L.settings.serversSettings.profileName) }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                    if (remote) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(profileWithSecrets, { profileWithSecrets = it }, role = Role.Checkbox)) {
                            androidx.compose.material3.Checkbox(checked = profileWithSecrets, onCheckedChange = { profileWithSecrets = it }, modifier = Modifier.mergedIntoRow())
                            Column {
                                Text(L.settings.serversSettings.includeSshPasswordAndKey, fontSize = 13.sp)
                                Text(L.settings.serversSettings.offByDefaultAnyoneUsingThis, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (authPrefs.listProfiles(remote).any { it.optString("name") == profileName.trim() } && profileName.isNotBlank()) {
                        Text(L.settings.serversSettings.aProfileWithThisNameWill, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        authPrefs.saveProfile(remote, profileName.trim(), profileJson(remote, remote && profileWithSecrets))
                        showSaveProfile = false; profileTick++
                        saveFeedback = L.settings.serversSettings.profileProfilenameSavedOnThisPhone(profileName.trim()); saveIsError = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(L.common.save, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSaveProfile = false }) { Text(L.common.cancel) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    profileToUpdate?.let { o ->
        val uRemote = o.optBoolean("remote")
        val uName = o.optString("name")
        val hadSecrets = o.has("sshPass")
        AlertDialog(
            onDismissRequest = { profileToUpdate = null },
            title = { Text(L.settings.serversSettings.updateProfile, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    L.settings.serversSettings.unameWillBeOverwrittenWithThe(uName, (if (uRemote) (if (hadSecrets) L.settings.serversSettings.includingTheSshPasswordAndKey else L.settings.serversSettings.withoutTheSshPasswordAndKey) else ".")),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        authPrefs.saveProfile(uRemote, uName, profileJson(uRemote, uRemote && hadSecrets))
                        profileToUpdate = null; profileTick++
                        saveFeedback = L.settings.serversSettings.profileUnameUpdated(uName); saveIsError = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(L.common.update, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { profileToUpdate = null }) { Text(L.common.cancel) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    profileToDelete?.let { (delRemote, name) ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text(L.settings.serversSettings.deleteProfile, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text("\"$name\" (${if (delRemote) "server" else "local"}) will be removed from this phone.", fontSize = 13.5.sp) },
            confirmButton = {
                Button(
                    onClick = { authPrefs.deleteProfile(delRemote, name); profileToDelete = null; profileTick++ },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), shape = RoundedCornerShape(8.dp)
                ) { Text(L.common.delete, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { profileToDelete = null }) { Text(L.common.cancel) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (confirmNewPhoneKey) {
        AlertDialog(
            onDismissRequest = { confirmNewPhoneKey = false },
            title = { Text(L.settings.serversSettings.makeANewKey, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(L.settings.serversSettings.theAntigemMcpServersYouAdded, fontSize = 13.5.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmNewPhoneKey = false
                        ai.antigem.data.remote.LocalServerAuth.rotate(context)
                        // what the app hands the key to itself follows; the agents' MCP servers are left for the user to add again
                        coroutineScope.launch(Dispatchers.IO) { runCatching { ai.antigem.data.adb.DeviceAccess.ensureTerminalCommands(context) } }
                        ai.antigem.ui.browser.BrowserExtensionManager.instance.restartHost()
                        Toast.makeText(context, L.settings.serversSettings.newKeyMadeRemoveTheAntigem, Toast.LENGTH_LONG).show()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) { Text(L.settings.serversSettings.makeNewKey, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmNewPhoneKey = false }) { Text(L.common.cancel) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showServerLog) ServerLogDialog(context) { showServerLog = false }

    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier = Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ----- Where does the server run? -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.serversSettings.agentsRunOn, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            when {
                                !remote && phoneChroot -> L.settings.serversSettings.thisPhoneInTheRootedUbuntu
                                !remote -> L.settings.serversSettings.thisPhoneInAntigemsBuiltIn
                                tunnel -> L.settings.serversSettings.onYourServerReachedThroughThe
                                else -> L.settings.serversSettings.theBridgeAndTheAgentsRun
                            },
                            fontSize = 11.5.sp, color = subtle
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(L.common.thisPhone, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = subtle)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeOption("AntiGem", !remote && !phoneChroot, Modifier.weight(1f)) { selectPhone(false) }
                    ModeOption(L.common.ubuntuChroot, !remote && phoneChroot, Modifier.weight(1f)) { selectPhone(true) }
                }
                Spacer(Modifier.height(8.dp))
                Text(L.settings.serversSettings.yourServer, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = subtle)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeOption(L.common.server, remote && !tunnel, Modifier.weight(1f)) { selectMode(true, false) }
                    ModeOption(L.settings.serversSettings.sshTunnel, remote && tunnel, Modifier.weight(1f)) { selectMode(true, true) }
                }

                // ----- Saved profiles (saved only when the user taps "Save as profile") -----
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { profileTick++; showProfileMenu = true },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        val n = remember(profileTick) { authPrefs.listProfiles(true).size + authPrefs.listProfiles(false).size }
                        Text("Saved profiles ($n)", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { profileName = loadedProfile?.takeIf { it.first == remote }?.second.orEmpty(); showSaveProfile = true }, shape = RoundedCornerShape(8.dp)) {
                        Text(L.settings.serversSettings.saveAsProfile, fontSize = 12.sp)
                    }
                }
                Text(L.settings.serversSettings.pickingAProfileSwitchesToIts, fontSize = 10.5.sp, color = subtle, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ----- the chosen runtime of this phone -----
        if (!remote) {
            if (phoneChroot) {
                ai.antigem.ui.settings.chroot.ChrootRuntimeCard(cardBg, cardBorder, onManage = onManageChroot, onInstalled = {
                    // installed and made the runtime: the saved addresses moved to the chroot's ports
                    hubText = AuthPreferences.currentHubUrl
                    bridgeText = AuthPreferences.currentBridgeHttpUrl
                    savedSnapshot = snapshot()
                    if (!AuthPreferences.currentRemoteMode) showRestartDialog = true
                })
            } else {
                ai.antigem.ui.settings.chroot.TermuxRuntimeCard(cardBg, cardBorder, onInstall = onInstallTermux, onFactoryReset = { showFactoryReset = true },
                    onInstalled = {
                        // the install switched the phone to Termux (and its addresses) already
                        phoneChroot = false
                        hubText = AuthPreferences.currentHubUrl
                        bridgeText = AuthPreferences.currentBridgeHttpUrl
                        savedSnapshot = snapshot()
                    })
            }
        }
        FactoryResetDialogs(showFactoryReset) { showFactoryReset = false }

        // ----- The two addresses -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Text(L.settings.serversSettings.serverAddresses, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        remote && tunnel -> L.settings.serversSettings.use127001With(BuildConfig.BRIDGE_PORT, BuildConfig.HUB_PORT)
                        remote -> "Two addresses, e.g. https://bridge.example.com and https://hub.example.com behind nginx/Apache, or http://203.0.113.5:1234 and http://203.0.113.5:1235 straight to the ports."
                        else -> L.settings.serversSettings.whereThisPhonesBridgeAndHub
                    },
                    fontSize = 11.5.sp, color = subtle
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it; saveFeedback = null; bridgeTestStatus = null },
                    label = { Text(L.settings.serversSettings.ideBridgeAddress) },
                    placeholder = { Text(if (remote && !tunnel) "https://bridge.example.com" else AuthPreferences.DEFAULT_BRIDGE_HTTP_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hubText,
                    onValueChange = { hubText = it; saveFeedback = null; hubTestStatus = null },
                    label = { Text(L.settings.serversSettings.agyHubAddress) },
                    placeholder = { Text(if (remote && !tunnel) "https://hub.example.com" else AuthPreferences.DEFAULT_HUB_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (remote && tunnel) L.settings.serversSettings.testHubAndTestBridgeWork
                    else "https:// needs no port. Without https://, a missing port becomes ${BuildConfig.BRIDGE_PORT} (bridge) / ${BuildConfig.HUB_PORT} (hub).",
                    fontSize = 11.sp, color = subtle
                )

                if (remote && !tunnel) {
                    Spacer(Modifier.height(14.dp))
                    Text(L.settings.serversSettings.portsOnTheServer, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        L.settings.serversSettings.theAddressesAboveAreWhatThe(BuildConfig.BRIDGE_PORT, BuildConfig.HUB_PORT),
                        fontSize = 11.sp, color = subtle
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(listenBridge, { listenBridge = it.filter(Char::isDigit); saveFeedback = null }, label = { Text(L.settings.serversSettings.bridgeListensOn) },
                            placeholder = { Text(BuildConfig.BRIDGE_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(listenHub, { listenHub = it.filter(Char::isDigit); saveFeedback = null }, label = { Text(L.settings.serversSettings.hubListensOn) },
                            placeholder = { Text(BuildConfig.HUB_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(listenBind, { listenBind = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.bridgeBindAddress) },
                        placeholder = { Text(L.settings.serversSettings.n127001BehindA) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                }

                Spacer(Modifier.height(12.dp))
                StatusLine(isServerOnline || hubTestStatus?.startsWith("✓") == true, L.settings.serversSettings.hub, AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT) ?: L.settings.serversSettings.invalidAddress)
                hubTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(6.dp))
                StatusLine(isBridgeOnline || bridgeTestStatus?.startsWith("✓") == true, L.settings.serversSettings.bridge, AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT) ?: L.settings.serversSettings.invalidAddress)
                bridgeTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }
                ai.antigem.ui.agents.BridgeVersionLine(Modifier.padding(top = 6.dp))

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                            if (url == null) { hubTestStatus = L.settings.serversSettings.notAValidAddress; return@OutlinedButton }
                            isTestingHub = true
                            hubTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeHub(url, edgeOrNull())
                                isTestingHub = false
                                hubTestStatus = if (ok) "✓ Hub reachable ($msg)" else "✗ Hub: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingHub
                    ) {
                        if (isTestingHub) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(L.settings.serversSettings.testHub, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                            if (url == null) { bridgeTestStatus = L.settings.serversSettings.notAValidAddress; return@OutlinedButton }
                            isTestingBridge = true
                            bridgeTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeBridge(url, securityToken, edgeOrNull())
                                isTestingBridge = false
                                bridgeTestStatus = if (ok) "✓ Bridge reachable ($msg)" else "✗ Bridge: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingBridge
                    ) {
                        if (isTestingBridge) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(L.settings.serversSettings.testBridge, fontSize = 12.sp)
                    }
                }
            }
        }

        if (remote) {
            // ----- Proxy access header: a second lock in front of the bridge and the hub (no proxy in tunnel mode) -----
            if (!tunnel) Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L.settings.serversSettings.proxyAccessHeaderSecondLayer, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                L.settings.serversSettings.optionalTheAppAddsThisHeader,
                                fontSize = 11.5.sp, color = subtle
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = edgeName, onValueChange = { edgeName = it.trim(); saveFeedback = null },
                        label = { Text(L.settings.serversSettings.headerName) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = edgeValue, onValueChange = { edgeValue = it.trim(); saveFeedback = null },
                        label = { Text(L.settings.serversSettings.headerValueSecret) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                        placeholder = { Text(L.settings.serversSettings.emptyOff) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { edgeValue = AuthPreferences.generateEdgeHeaderValue(); saveFeedback = null },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.serversSettings.generate, fontSize = 12.sp) }
                        OutlinedButton(
                            enabled = edgeValue.isNotBlank(),
                            onClick = {
                                val nginxVar = "http_" + edgeName.trim().lowercase().replace('-', '_')
                                val snippet = L.settings.serversSettings.mapNginxvarAntigemKeyOkDefault(nginxVar, edgeValue.trim())
                                ai.antigem.data.security.SecretClipboard.copy(context, "nginx rule", snippet)
                                Toast.makeText(context, L.settings.serversSettings.nginxRuleCopied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.serversSettings.copyNginxRule, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "If you set a value, the proxy MUST check the same one, or every request is refused. Test Hub and Test Bridge use it too. " +
                            "It only protects traffic that goes through the proxy, and only over https://.",
                        fontSize = 11.sp, color = subtle
                    )
                }
            }

            // ----- SSH -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L.settings.serversSettings.sshAccess, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                L.settings.serversSettings.usedToInstallStartAndStop,
                                fontSize = 11.5.sp, color = subtle
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sshHost, onValueChange = { sshHost = it; sshTestStatus = null; saveFeedback = null },
                            label = { Text(L.settings.serversSettings.sshHost) }, placeholder = { Text(L.settings.serversSettings.serverHostOrIp) },
                            modifier = Modifier.weight(2f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            value = sshPort, onValueChange = { sshPort = it.filter(Char::isDigit); sshTestStatus = null; saveFeedback = null },
                            label = { Text(L.common.port) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshUser, onValueChange = { sshUser = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(L.common.user) }, placeholder = { Text(L.settings.serversSettings.notRootIfYouCanAvoid) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshPass, onValueChange = { sshPass = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(L.settings.serversSettings.passwordOptionalWhenAKeyIs) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshKey, onValueChange = { sshKey = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(L.settings.serversSettings.privateKeyRecommended) },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                        modifier = Modifier.fillMaxWidth().height(110.dp), minLines = 3, maxLines = 4, shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                            Text(L.settings.serversSettings.chooseKeyFile, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                if (SshSessionFactory.looksLikePrivateKey(text)) { sshKey = text.trim(); saveFeedback = null }
                                else Toast.makeText(context, L.settings.serversSettings.theClipboardDoesNotHoldA, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text(L.settings.serversSettings.pasteKey, fontSize = 12.sp) }
                    }
                    if (sshKey.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sshPassphrase, onValueChange = { sshPassphrase = it; saveFeedback = null },
                            label = { Text(L.settings.serversSettings.keyPassphraseIfItHasOne) },
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
                                if (host.isBlank() || sshUser.isBlank()) { sshTestStatus = L.settings.serversSettings.enterTheHostAndTheUser; return@OutlinedButton }
                                isTestingSsh = true
                                sshTestStatus = L.settings.serversSettings.connectingToHost(host)
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
                                        { L.settings.serversSettings.sshWorksReplace(it.replace('\n', ' ')) },
                                        { "✗ ${it.message}" }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingSsh
                        ) {
                            if (isTestingSsh) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            Text(L.settings.serversSettings.testSsh, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                SshSessionFactory.forgetHostKey(sshHost.trim(), sshPort.toIntOrNull() ?: 22)
                                Toast.makeText(context, L.settings.serversSettings.savedHostKeyForgottenTheNext, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text(L.settings.serversSettings.forgetHostKey, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        L.settings.serversSettings.theServersSshKeyIsPinned,
                        fontSize = 11.sp, color = subtle
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(L.settings.serversSettings.advancedServerPathsAndPorts, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) L.common.hide else L.common.show, fontSize = 12.sp) }
                    }
                    if (showAdvanced) {
                        Text(
                            L.settings.serversSettings.theseAreThePathsOnThe,
                            fontSize = 11.sp, color = subtle
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteBridgeBin, { remoteBridgeBin = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.bridgeBinaryOnTheServer) },
                            placeholder = { Text(RemoteServerManager.defaultBridgeBin(remoteInstallDir)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteAgyBin, { remoteAgyBin = it; saveFeedback = null }, label = { Text("agy binary on the server") },
                            placeholder = { Text(RemoteServerManager.DEFAULT_AGY_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteClaudeBin, { remoteClaudeBin = it; saveFeedback = null }, label = { Text("claude binary on the server") },
                            placeholder = { Text(RemoteServerManager.DEFAULT_CLAUDE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteOpenCodeBin, { remoteOpenCodeBin = it; saveFeedback = null }, label = { Text("opencode binary on the server") },
                            placeholder = { Text(RemoteServerManager.DEFAULT_OPENCODE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteCodexBin, { remoteCodexBin = it; saveFeedback = null }, label = { Text("codex binary on the server") },
                            placeholder = { Text(RemoteServerManager.DEFAULT_CODEX_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteInstallDir, {
                            // while the bridge path is still the default of the old folder, it follows the folder
                            if (remoteBridgeBin == RemoteServerManager.defaultBridgeBin(remoteInstallDir)) remoteBridgeBin = RemoteServerManager.defaultBridgeBin(it)
                            remoteInstallDir = it; saveFeedback = null
                        }, label = { Text(L.settings.serversSettings.bridgeFolderInTheServerHome) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    }
                }
            }

            // ----- user port forwards: server port -> this phone's 127.0.0.1 (dev servers and the like) -----
            val reservedPorts = buildSet {
                if (tunnel) {
                    AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)?.let { runCatching { java.net.URI(it).port }.getOrNull() }?.takeIf { it > 0 }?.let(::add)
                    AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)?.let { runCatching { java.net.URI(it).port }.getOrNull() }?.takeIf { it > 0 }?.let(::add)
                }
                add(ai.antigem.ui.browser.BrowserExtensionManager.instance.hostPort())
                add(ai.antigem.data.remote.AndroidLocalBridgeServer.instance.boundPort)
            }
            PortForwardsCard(cardBg, cardBorder, subtle, reservedPorts)

            // ----- live server control (only once Remote mode is active) -----
            if (AuthPreferences.currentRemoteMode) RemoteServerCard(cardBg, cardBorder, subtle, onShowLog = {
                RemoteServerManager.refreshLog(context)
                showServerLog = true
            })
        } else if (phoneChroot) {
            // ----- the Ubuntu chroot's agent paths (paths inside the chroot) -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L.settings.serversSettings.agentPathsInTheUbuntuChroot, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(L.settings.serversSettings.forAdvancedUseOnlyLeaveThem, fontSize = 11.5.sp, color = subtle)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootBridgeBin, { chrootBridgeBin = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.ideBridge) },
                        placeholder = { Text(ai.antigem.data.local.chroot.ChrootManager.BRIDGE_IN_CHROOT) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootAgyBin, { chrootAgyBin = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.agyHubAgy) },
                        placeholder = { Text(RemoteServerManager.DEFAULT_AGY_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootClaudeBin, { chrootClaudeBin = it; saveFeedback = null }, label = { Text(L.common.claudeCode) },
                        placeholder = { Text(RemoteServerManager.DEFAULT_CLAUDE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootOpenCodeBin, { chrootOpenCodeBin = it; saveFeedback = null }, label = { Text("OpenCode") },
                        placeholder = { Text(RemoteServerManager.DEFAULT_OPENCODE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootCodexBin, { chrootCodexBin = it; saveFeedback = null }, label = { Text(L.common.codex) },
                        placeholder = { Text(RemoteServerManager.DEFAULT_CODEX_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(L.settings.serversSettings.passedToTheBridgeOnLaunch,
                        fontSize = 11.sp, color = subtle)
                    Text(L.settings.serversSettings.installingFromTheAppSettingsEach,
                        fontSize = 11.sp, color = subtle)
                    Text(L.settings.serversSettings.ideBridgeAntigemPutsThisApp(ai.antigem.data.local.chroot.ChrootManager.BRIDGE_IN_CHROOT),
                        fontSize = 11.sp, color = subtle)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            chrootBridgeBin = ""; chrootAgyBin = ""; chrootClaudeBin = ""; chrootOpenCodeBin = ""; chrootCodexBin = ""
                            saveFeedback = L.settings.serversSettings.agentPathsResetToTheDefaults
                            saveIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.settings.serversSettings.resetToDefaults, fontSize = 12.sp)
                    }
                }
            }
        } else {
            // ----- local binaries (AntiGem's built-in environment) -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(L.settings.serversSettings.agentPathsInAntigem, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(L.settings.serversSettings.forAdvancedUseOnlyLeaveThem, fontSize = 11.5.sp, color = subtle)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(bridgeBinPath, { bridgeBinPath = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.ideBridgeBinaryPath) },
                        placeholder = { Text(authPrefs.getDefaultBridgeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(agyBinPath, { agyBinPath = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.agyHubBinaryPath) },
                        placeholder = { Text(authPrefs.getDefaultAgyBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(claudeBinPath, { claudeBinPath = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.claudeCodeBinaryPath) },
                        placeholder = { Text(authPrefs.getDefaultClaudeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(openCodeBinPath, { openCodeBinPath = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.opencodeBinaryPath) },
                        placeholder = { Text(authPrefs.getDefaultOpenCodeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(codexBinPath, { codexBinPath = it; saveFeedback = null }, label = { Text(L.settings.serversSettings.codexBinaryPath) },
                        placeholder = { Text(authPrefs.getDefaultCodexBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.settings.serversSettings.passedToTheBridgeOnLaunch2,
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        L.settings.serversSettings.claudeCodeTheDefaultIsAntigems,
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        L.settings.serversSettings.opencodeTheDefaultIsWhereIts,
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        L.settings.serversSettings.codexTheDefaultIsTheTermux,
                        fontSize = 11.sp, color = subtle
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            hubText = AuthPreferences.defaultLocalHubUrl(phoneChroot)
                            bridgeText = AuthPreferences.defaultLocalBridgeUrl(phoneChroot)
                            bridgeBinPath = ""; agyBinPath = ""; claudeBinPath = ""; openCodeBinPath = ""; codexBinPath = ""
                            saveFeedback = L.settings.serversSettings.settingsResetToDefaultsClickSave
                            saveIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.settings.serversSettings.resetAllToDefaults, fontSize = 12.sp)
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
                        Text(L.settings.serversSettings.securityTokenHeaderObfuscation, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (remote && tunnel) L.settings.serversSettings.theSecretThatProtectsTheBridge
                            else if (remote) "The secret that protects the bridge. The app starts the server with it. Use https:// on the internet: over plain http it is visible on the network."
                            else L.settings.serversSettings.protectsTheIdeBridgeAndThe,
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
                    label = { Text(L.settings.serversSettings.n12CharacterSecretToken) },
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
                Text(L.settings.serversSettings.httpFramedHeaderFramedheader(framedHeader), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { securityToken = AuthPreferences.generateRandomToken(); saveFeedback = null },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.serversSettings.randomize, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            ai.antigem.data.security.SecretClipboard.copy(context, "Framed Security Header", framedHeader)
                            Toast.makeText(context, L.settings.serversSettings.copiedFramedheaderToClipboard(framedHeader), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.serversSettings.copyHeader, fontSize = 12.sp)
                    }
                }
            }
        }

        // ----- Phone tools key (LocalServerAuth): the other direction, agents -> this phone's MCP / REST server -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.serversSettings.phoneToolsKey, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(L.settings.serversSettings.protectsThisPhonesToolsAgentsSend, fontSize = 11.5.sp, color = subtle)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { confirmNewPhoneKey = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.serversSettings.newKey, fontSize = 12.sp)
                }
            }
        }

        saveFeedback?.let {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = (if (saveIsError) Color.Red else QuotaGreen).copy(alpha = 0.12f)) {
                Text(it, fontSize = 12.sp, color = if (saveIsError) Color.Red else QuotaGreen, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            }
        }

        fun saveAll(hub: String, bridge: String) {
            hubText = hub
            bridgeText = bridge
            onSaveServerUrls(hub, bridge)
            val tokenToSave = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) securityToken else AuthPreferences.generateRandomToken()
            securityToken = tokenToSave
            coroutineScope.launch {
                authPrefs.saveSecurityToken(tokenToSave)
                val wasChroot = authPrefs.isChrootModeSync()
                authPrefs.saveRemoteModeSync(remote)
                authPrefs.saveRemoteTunnelSync(remote && tunnel)
                if (!remote) {
                    if (phoneChroot) ai.antigem.data.local.chroot.ChrootManager.activeInstall(context)?.let {
                        ai.antigem.data.local.chroot.ChrootManager.activate(context, it)
                    } else ai.antigem.data.local.chroot.ChrootManager.useTermux(context)
                }
                // the chroot's bridge outlives the app: stop it when the agents no longer run there
                if (wasChroot && (remote || !phoneChroot)) ai.antigem.data.local.chroot.ChrootManager.stopBridge(context)
                if (remote) {
                    authPrefs.saveSshSettings(sshHost.trim(), sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass)
                    authPrefs.saveSshKeySync(sshKey, sshPassphrase)
                    authPrefs.saveRemoteBinariesSync(remoteBridgeBin, remoteAgyBin, remoteClaudeBin, remoteOpenCodeBin, remoteCodexBin)
                    authPrefs.saveRemoteInstallDirSync(remoteInstallDir)
                    authPrefs.saveRemoteListenSync(listenBridge, listenHub, listenBind)
                    authPrefs.saveEdgeHeaderSync(edgeName, edgeValue)
                } else {
                    authPrefs.saveAgyBridgeBinaryPath(bridgeBinPath.trim().ifBlank { authPrefs.getDefaultBridgeBinaryPath() })
                    authPrefs.saveAgyBinaryPath(agyBinPath.trim().ifBlank { authPrefs.getDefaultAgyBinaryPath() })
                    authPrefs.saveClaudeBinaryPath(claudeBinPath.trim().ifBlank { authPrefs.getDefaultClaudeBinaryPath() })
                    authPrefs.saveOpenCodeBinaryPath(openCodeBinPath.trim().ifBlank { authPrefs.getDefaultOpenCodeBinaryPath() })
                    authPrefs.saveCodexBinaryPath(codexBinPath.trim().ifBlank { authPrefs.getDefaultCodexBinaryPath() })
                    authPrefs.saveChrootBinariesSync(chrootBridgeBin, chrootAgyBin, chrootClaudeBin, chrootOpenCodeBin, chrootCodexBin)
                }
            }
            savedSnapshot = snapshot()
            saveFeedback = L.settings.serversSettings.serverConnectionAndSecuritySettingsSaved
            saveIsError = false
            showRestartDialog = true
        }

        clearTextAsk?.let { (askHub, askBridge, host) ->
            val tailscale = Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(host)
            var typed by remember(host) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { clearTextAsk = null },
                title = { ai.antigem.ui.components.FilterObscuredTouches(); Text(L.settings.serversSettings.sendTheBridgeSecretUnencrypted) },
                text = {
                    Column {
                        Text(if (tailscale) L.settings.serversSettings.addressIsInTailscalesRangeThe(host) else L.settings.serversSettings.addressIsAPlainHttpAddress(host))
                        if (!tailscale) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = tailscale || typed.trim().equals("unencrypted", ignoreCase = true),
                        onClick = {
                            clearTextAsk = null
                            clearHostAccepted = host
                            saveAll(askHub, askBridge)
                        }
                    ) { Text(L.settings.serversSettings.keepPlainHttp) }
                },
                dismissButton = { TextButton(onClick = { clearTextAsk = null }) { Text(L.common.cancel) } }
            )
        }

        Button(
            onClick = {
                val hub = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                val bridge = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                val problem = when {
                    !remote && phoneChroot && ai.antigem.data.local.chroot.ChrootManager.activeInstall(context) == null ->
                        L.settings.serversSettings.installTheUbuntuChrootFirstInstall
                    bridge == null -> L.settings.serversSettings.theIdeBridgeAddressIsNot
                    hub == null -> L.settings.serversSettings.theAgyHubAddressIsNot
                    remote && tunnel && (!AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridge)) || !AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(hub))) ->
                        "In SSH tunnel mode both addresses are 127.0.0.1 with the port the service listens on at the server, e.g. http://127.0.0.1:${BuildConfig.BRIDGE_PORT}."
                    remote && tunnel && (bridge.startsWith("https://") || hub.startsWith("https://")) ->
                        "In SSH tunnel mode use http:// (SSH already encrypts the connection)."
                    remote && tunnel && runCatching { java.net.URI(bridge).port == java.net.URI(hub).port }.getOrDefault(false) ->
                        L.settings.serversSettings.theBridgeAndTheHubNeed
                    remote && AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridge)) && sshHost.isBlank() ->
                        L.settings.serversSettings.aRemoteServerNeedsItsAddress
                    remote && sshHost.isBlank() -> L.settings.serversSettings.enterTheSshHost
                    remote && sshUser.isBlank() -> L.settings.serversSettings.enterTheSshUser
                    remote && sshPass.isBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> L.settings.serversSettings.enterAnSshPasswordOrA
                    remote && sshKey.isNotBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> L.settings.serversSettings.theSshKeyIsNotA
                    remote && !tunnel && edgeValue.isNotBlank() && !AuthPreferences.isValidEdgeName(edgeName.trim()) ->
                        L.settings.serversSettings.theAccessHeaderNameMayOnly
                    remote && !tunnel && edgeValue.isNotBlank() && !AuthPreferences.isValidEdgeValue(edgeValue.trim()) ->
                        L.settings.serversSettings.theAccessHeaderValueMustBe
                    else -> null
                }
                if (problem != null || hub == null || bridge == null) {
                    saveFeedback = problem ?: L.settings.serversSettings.checkTheAddresses
                    saveIsError = true
                    return@Button
                }
                // a direct http:// address on the network sends the bridge's secret in clear text: the user confirms first
                val clearHost = listOf(bridge, hub).firstOrNull { u ->
                    u.startsWith("http://", ignoreCase = true) && !AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(u))
                }?.let { AuthPreferences.hostOf(it) }
                if (remote && !tunnel && clearHost != null && clearHostAccepted != clearHost) {
                    clearTextAsk = Triple(hub, bridge, clearHost)
                    return@Button
                }
                saveAll(hub, bridge)
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(L.settings.serversSettings.saveServerSecuritySettings, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ModeOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) QuotaGreen.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = 12.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * The user's own forwards: a port on the server (a dev server from `npm run dev`, a database admin page ...) made
 * reachable on this phone's 127.0.0.1 over the SSH connection. Saved and applied right away, no Save button needed.
 */
@Composable
private fun PortForwardsCard(cardBg: Color, cardBorder: BorderStroke, subtle: Color, reservedPorts: Set<Int>) {
    val context = LocalContext.current
    val prefs = remember(context) { AuthPreferences(context) }
    var list by remember { mutableStateOf(prefs.getPortForwardsSync()) }
    var serverPort by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val tunnels by RemoteServerManager.tunnels.collectAsState()
    val ssh by RemoteServerManager.sshState.collectAsState()
    val live = AuthPreferences.currentRemoteMode

    fun persist(next: List<AuthPreferences.PortForward>) {
        list = next
        prefs.savePortForwardsSync(next)
        RemoteServerManager.resyncTunnels()
    }

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.settings.serversSettings.portForwardsServerThisPhone, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Reach a port of the server on this phone, e.g. a dev server started with npm run dev on port 5173: " +
                            "then open http://127.0.0.1:5173 here. Runs over the SSH connection; changes apply right away.",
                        fontSize = 11.5.sp, color = subtle
                    )
                }
            }

            if (list.isNotEmpty()) Spacer(Modifier.height(10.dp))
            list.forEachIndexed { index, f ->
                val active = live && tunnels.any { it.startsWith("phone 127.0.0.1:${f.localPort} ") }
                val failed = live && tunnels.any { it.contains("phone port ${f.localPort} not forwarded") }
                val dot = when {
                    !f.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    active -> QuotaGreen
                    failed -> Color.Red
                    else -> ai.antigem.theme.AppColors.warning
                }
                val state = when {
                    !f.enabled -> "off"
                    active -> "active"
                    failed -> L.settings.serversSettings.couldNotOpenThePhonePort
                    !live -> L.settings.serversSettings.startsOnceServerModeIsSaved
                    !ssh.connected -> L.settings.serversSettings.waitingForSsh
                    else -> "opening…"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(8.dp), shape = CircleShape, color = dot) {}
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifBlank { L.settings.serversSettings.portServerport(f.serverPort) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text("server :${f.serverPort} → http://127.0.0.1:${f.localPort} · $state", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = subtle)
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Forwarded address", "http://127.0.0.1:${f.localPort}"))
                        Toast.makeText(context, "Copied http://127.0.0.1:${f.localPort}", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.ContentCopy, L.settings.serversSettings.copyAddress, modifier = Modifier.size(17.dp)) }
                    Switch(
                        checked = f.enabled,
                        onCheckedChange = { on -> persist(list.toMutableList().also { it[index] = f.copy(enabled = on) }) },
                        colors = SwitchDefaults.colors(checkedTrackColor = QuotaGreen)
                    )
                    androidx.compose.material3.IconButton(onClick = { persist(list.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Outlined.DeleteOutline, L.common.remove, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(serverPort, { serverPort = it.filter(Char::isDigit).take(5); error = null }, label = { Text(L.settings.serversSettings.serverPort) },
                    placeholder = { Text("5173") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(localPort, { localPort = it.filter(Char::isDigit).take(5); error = null }, label = { Text(L.settings.serversSettings.phonePort) },
                    placeholder = { Text(serverPort.ifBlank { "same" }) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40); error = null }, label = { Text(L.settings.serversSettings.nameOptional) },
                    placeholder = { Text(L.settings.serversSettings.viteDevServer) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp))
                Button(
                    onClick = {
                        val sp = serverPort.toIntOrNull()
                        val lp = localPort.toIntOrNull() ?: sp
                        error = when {
                            sp == null || sp !in 1..65535 -> L.settings.serversSettings.enterTheServerPort165535
                            lp == null || lp !in 1024..65535 -> L.settings.serversSettings.thePhonePortMustBe1024
                            lp in reservedPorts -> L.settings.serversSettings.phonePortLpIsAlreadyUsed(lp)
                            list.any { it.localPort == lp } -> L.settings.serversSettings.phonePortLpIsAlreadyIn(lp)
                            else -> null
                        }
                        if (error == null && sp != null && lp != null) {
                            persist(list + AuthPreferences.PortForward(sp, lp, name.trim(), true))
                            serverPort = ""; localPort = ""; name = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(L.common.add, fontWeight = FontWeight.Bold) }
            }
            error?.let { Text(it, fontSize = 11.5.sp, color = Color.Red, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(6.dp))
            Text(
                L.settings.serversSettings.theProgramOnTheServerHas,
                fontSize = 11.sp, color = subtle
            )
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
        is LocalServerStatus.Running -> L.settings.serversSettings.bridgeRunningOnTheServer to QuotaGreen
        is LocalServerStatus.Starting -> L.common.starting to ai.antigem.theme.AppColors.warning
        is LocalServerStatus.Stopping -> L.settings.serversSettings.stopping to ai.antigem.theme.AppColors.warning
        is LocalServerStatus.Stopped -> L.settings.serversSettings.bridgeStopped to ai.antigem.theme.AppColors.textSecondary
        is LocalServerStatus.Error -> s.message to Color.Red
        is LocalServerStatus.Idle -> L.settings.serversSettings.notStarted to ai.antigem.theme.AppColors.textSecondary
    }

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp)) {
            Text(L.settings.serversSettings.serverControl, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
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
                L.settings.serversSettings.sshMessage(ssh.message),
                fontSize = 11.5.sp, color = if (ssh.connected) QuotaGreen else subtle
            )
            if (tunnels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(L.settings.serversSettings.sshTunnels, fontSize = 11.sp, color = subtle)
                tunnels.forEach { Text("• $it", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = subtle) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { LocalServerManager.startServer(context, forceRestart = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(L.common.start, fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.restartServer(context) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(L.common.restart, fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.stopServer() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(L.common.stop, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onShowLog, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text(L.settings.serversSettings.serverLog, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ServerLogDialog(context: Context, onDismiss: () -> Unit) {
    val log by RemoteServerManager.log.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.settings.serversSettings.serverLog, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                Text(
                    log.ifBlank { L.settings.serversSettings.emptyTheBridgeHasNotWritten },
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } },
        dismissButton = { TextButton(onClick = { RemoteServerManager.refreshLog(context) }) { Text(L.common.refresh) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
