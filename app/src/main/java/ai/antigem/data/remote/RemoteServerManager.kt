package ai.antigem.data.remote

import android.content.Context
import android.util.Log
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalServerStatus
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.ssh.SshSessionFactory
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import ai.antigem.i18n.L

/**
 * Remote mode: the IDE bridge and the agents run on a server (VPS) and the app manages them over SSH.
 *
 * One long-lived SSH connection ("control session") is kept open and re-opened when it drops. Over it the app
 *  - checks that the bridge binary is on the server (the user copies it there; the app never uploads it),
 *  - starts/stops/restarts the bridge inside a tmux session (it survives the SSH connection),
 *  - reads the bridge log,
 *  - opens reverse port forwards so the agents on the server can reach the MCP servers that live in this app
 *    (browser, Android, notes, ...): `127.0.0.1:<port>` on the server is the phone's MCP server.
 *
 * It reports through [LocalServerManager.status] so every existing status badge and button keeps working.
 */
object RemoteServerManager {

    private const val TAG = "RemoteServerManager"
    private const val TMUX_SESSION = "antigem-bridge"

    data class SshState(val connected: Boolean, val message: String)

    private val _ssh = MutableStateFlow(SshState(false, L.remote.serverManager.notConnected))
    val sshState: StateFlow<SshState> = _ssh.asStateFlow()

    /** What the manager is doing right now ("Uploading bridge 45%"). Empty when idle. */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _tunnels = MutableStateFlow<List<String>>(emptyList())
    val tunnels: StateFlow<List<String>> = _tunnels.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opLock = Mutex()
    private val connectLock = Mutex()

    @Volatile private var control: Session? = null
    private var supervisor: Job? = null
    @Volatile private var appContext: Context? = null

    val isRemote: Boolean get() = AuthPreferences.currentRemoteMode

    // ---------------------------------------------------------------------------------------------------------
    // SSH control session
    // ---------------------------------------------------------------------------------------------------------

    private data class Target(val host: String, val port: Int, val user: String, val pass: String)

    private suspend fun target(context: Context): Target? {
        // Connect to PC: the paired PC at the address that answers now, logged in with this phone's key
        AuthPreferences.pcOverride?.let { pc ->
            val host = ai.antigem.data.pc.PcConnect.prepareConnect() ?: pc.host
            return Target(host, pc.sshPort, pc.user, "")
        }
        val prefs = AuthPreferences(context)
        val host = prefs.termuxSshHost.firstOrNull().orEmpty().trim()
        val port = prefs.termuxSshPort.firstOrNull() ?: 22
        val user = prefs.termuxSshUser.firstOrNull().orEmpty().trim()
        val pass = prefs.termuxSshPass.firstOrNull().orEmpty()
        if (host.isBlank() || user.isBlank()) return null
        if (pass.isBlank() && !SshSessionFactory.looksLikePrivateKey(AuthPreferences.currentSshPrivateKey)) return null
        return Target(host, port, user, pass)
    }

    /** Keeps the SSH control session (and the port forwards on it) alive for as long as Remote mode is on. */
    fun ensureSupervisor(context: Context) {
        appContext = context.applicationContext
        if (!isRemote) return
        if (supervisor?.isActive == true) return
        supervisor = scope.launch {
            var backoff = 3_000L
            var tick = 0
            while (isActive && isRemote) {
                val s = control
                if (s != null && s.isConnected) {
                    appContext?.let { ctx -> runCatching { AdbTunnel.sync(ctx, s) } }
                    if (++tick % 3 == 0) runCatching { reconcileStatus() }
                    delay(4_000)
                    backoff = 3_000L
                    continue
                }
                try {
                    connectControl()
                    backoff = 3_000L
                } catch (e: Exception) {
                    _ssh.value = SshState(false, friendly(e))
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private var downStrikes = 0

    /** Keeps the shown status true: a bridge that died shows an error, one that is up (started elsewhere) shows running. */
    private suspend fun reconcileStatus() {
        if (pendingOps.isNotEmpty() || opLock.isLocked) return
        val st = LocalServerManager.status.value
        if (st is LocalServerStatus.Starting || st is LocalServerStatus.Stopping) return
        if (bridgeReachable()) {
            downStrikes = 0
            if (st !is LocalServerStatus.Running) LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
        } else if (st is LocalServerStatus.Running && ++downStrikes >= 2) {
            downStrikes = 0
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(
                "The app cannot reach the bridge at ${AuthPreferences.currentBridgeHttpUrl}. It may have stopped or the network dropped. Tap Start to check."
            ))
        }
    }

    // the supervisor and a start/stop request can both ask for the connection: open it only once
    private suspend fun connectControl(): Session = connectLock.withLock { withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("App not ready")
        control?.takeIf { it.isConnected }?.let { return@withContext it }
        val t = target(ctx) ?: throw IllegalStateException(L.remote.serverManager.enterTheSshHostUserAnd)
        _ssh.value = SshState(false, L.remote.serverManager.connectingToUserHostPort(t.user, t.host, t.port))
        val s = SshSessionFactory.open(t.host, t.port, t.user, t.pass, connectTimeoutMs = 12_000, keepAliveMs = 20_000)
        control = s
        // a paired PC: its bridge token may have changed since (read over this login)
        if (AuthPreferences.pcOverride != null) ai.antigem.data.pc.PcConnect.afterConnect(s)
        _ssh.value = SshState(true, L.remote.serverManager.connectedToValue(AuthPreferences.pcOverride?.pcName ?: "${t.user}@${t.host}"))
        AdbTunnel.onNewSession()
        syncTunnels(s)
        s
    } }

    /** The live control session, or null. */
    internal fun controlSession(): Session? = control?.takeIf { it.isConnected }

    private suspend fun session(): Session = control?.takeIf { it.isConnected } ?: connectControl()

    fun disconnect() {
        supervisor?.cancel()
        supervisor = null
        try { control?.disconnect() } catch (_: Exception) {}
        control = null
        _tunnels.value = emptyList()
        _ssh.value = SshState(false, L.remote.serverManager.notConnected)
    }

    /** A readable SSH error. [pc]: Connect to PC (a changed host key means pairing again, not Settings > Server). */
    internal fun friendly(e: Throwable, pc: Boolean = AuthPreferences.pcOverride != null): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                L.remote.serverManager.sshLoginFailedCheckTheUser
            m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) -> {
                val fp = SshSessionFactory.lastChangedFingerprint
                when {
                    pc && fp != null -> L.remote.serverManager.thePcsSshHostKeyChanged(fp)
                    pc -> L.remote.serverManager.thePcsSshHostKeyChanged2
                    fp != null -> L.remote.serverManager.theServersSshHostKeyChanged2(fp)
                    else -> L.remote.serverManager.theServersSshHostKeyChanged
                }
            }
            m.contains("timeout", true) || m.contains("timed out", true) ->
                L.remote.serverManager.sshConnectionTimedOutCheckThe
            m.contains("refused", true) -> L.remote.serverManager.sshConnectionRefusedIsSshdRunning
            m.contains("UnknownHost", true) || m.contains("unresolved", true) -> L.remote.serverManager.cannotFindThatHostName
            m.contains("invalid privatekey", true) || m.contains("passphrase", true) ->
                L.remote.serverManager.theSshPrivateKeyCouldNot
            else -> m.ifBlank { e.javaClass.simpleName }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reverse port forwards: the agents on the server reach the MCP servers inside this app
    // ---------------------------------------------------------------------------------------------------------

    // local forwards opened for the SSH-tunnel mode and the user's own list: phone port -> server port, per session
    private val ownedLocal = mutableMapOf<Int, Int>()
    private var ownedSession: Session? = null

    /**
     * Phone 127.0.0.1:<local> -> server 127.0.0.1:<server> for the bridge and the hub (SSH-tunnel mode) and every enabled
     * user forward. A forward that already runs is left alone, so open connections through it are not cut.
     */
    private fun syncLocalForwards(s: Session, active: MutableList<String>) {
        if (ownedSession !== s) { ownedLocal.clear(); ownedSession = s }
        val wanted = linkedMapOf<Int, Pair<Int, String>>()
        if (AuthPreferences.currentRemoteTunnel) {
            val p = appContext?.let { plan(it) }
            val bp = p?.bridgePort ?: ai.antigem.BuildConfig.BRIDGE_PORT
            val hp = p?.hubPort ?: ai.antigem.BuildConfig.HUB_PORT
            wanted[bp] = bp to "bridge"
            wanted[hp] = hp to "hub"
        }
        appContext?.let { ctx ->
            AuthPreferences(ctx).getPortForwardsSync().filter { it.enabled }.forEach { f ->
                if (f.localPort !in wanted) wanted[f.localPort] = f.serverPort to f.name.ifBlank { "forward" }
            }
        }
        // close what is no longer wanted (or now points at another server port)
        ownedLocal.entries.toList().forEach { (lp, rp) ->
            if (wanted[lp]?.first != rp) {
                try { s.delPortForwardingL("127.0.0.1", lp) } catch (_: Exception) {}
                ownedLocal.remove(lp)
            }
        }
        wanted.forEach { (lp, v) ->
            val (rp, label) = v
            if (ownedLocal[lp] == rp) {
                active += "phone 127.0.0.1:$lp → server :$rp ($label)"
                return@forEach
            }
            try {
                s.setPortForwardingL("127.0.0.1", lp, "127.0.0.1", rp)
                ownedLocal[lp] = rp
                active += "phone 127.0.0.1:$lp → server :$rp ($label)"
            } catch (e: Exception) {
                Log.w(TAG, "Local forward $lp -> $rp failed: ${e.message}")
                active += "$label: phone port $lp not forwarded (${e.message?.take(60)})"
            }
        }
    }

    @Synchronized
    private fun syncTunnels(s: Session) {
        val active = mutableListOf<String>()
        syncLocalForwards(s, active)

        // reverse: the agents/plugin host on the server reach the phone's MCP server (browser, Android, notes ...)
        val remotePort = AndroidLocalBridgeServer.DEFAULT_PORT
        val localPort = AndroidLocalBridgeServer.instance.boundPort
        try { s.delPortForwardingR("127.0.0.1", remotePort) } catch (_: Exception) {}
        try {
            s.setPortForwardingR("127.0.0.1", remotePort, "127.0.0.1", localPort)
            active += "server 127.0.0.1:$remotePort → phone :$localPort (phone tools)"
            // sshd with "GatewayPorts yes" binds it on every interface anyway: say so (only the phone tools key is left)
            if (listensOnAllInterfaces(s, remotePort)) active += L.remote.serverManager.serverPortPortIsOpenTo(remotePort)
        } catch (e: Exception) {
            Log.w(TAG, "Reverse forward $remotePort failed: ${e.message}")
            active += "port $remotePort not forwarded (${e.message?.take(60)})"
        }

        // local: the app talks to the browser-plugin host that runs on the server
        val hostPort = ai.antigem.ui.browser.BrowserExtensionManager.instance.hostPort()
        try { s.delPortForwardingL("127.0.0.1", hostPort) } catch (_: Exception) {}
        try {
            s.setPortForwardingL("127.0.0.1", hostPort, "127.0.0.1", hostPort)
            active += "phone 127.0.0.1:$hostPort → server :$hostPort (plugin host)"
        } catch (e: Exception) {
            Log.w(TAG, "Local forward $hostPort failed: ${e.message}")
            active += "plugin host port $hostPort not forwarded (${e.message?.take(60)})"
        }
        _tunnels.value = active
    }

    /** Whether [port] on the server listens on 0.0.0.0 / [::] / * (not just loopback). False when it can't tell. */
    private fun listensOnAllInterfaces(s: Session, port: Int): Boolean = runCatching {
        val out = exec(s, "(ss -ltnH 2>/dev/null || netstat -ltn 2>/dev/null) | grep -E '[:.]$port[[:space:]]' ; true", 8_000).second
        out.lines().any { line ->
            val local = line.trim().split(Regex("\\s+")).firstOrNull { it.endsWith(":$port") || it.endsWith(".$port") } ?: return@any false
            local.startsWith("0.0.0.0:") || local.startsWith("*:") || local.startsWith("[::]:") || local.startsWith(":::") || local == "*.$port"
        }
    }.getOrDefault(false)

    /** Re-opens the forwards, e.g. after the phone's MCP server rebound to another port. */
    fun resyncTunnels() {
        val s = control ?: return
        if (s.isConnected) scope.launch { syncTunnels(s) }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Remote commands
    // ---------------------------------------------------------------------------------------------------------

    internal fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

    internal fun exec(s: Session, command: String, timeoutMs: Long = 30_000): Pair<Int, String> {
        val channel = s.openChannel("exec") as ChannelExec
        channel.setCommand("sh -c ${q(command)} 2>&1")
        val input = channel.inputStream
        channel.connect(10_000)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (channel.isClosed) {
                    while (input.available() > 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    return -1 to (out.toString(Charsets.UTF_8.name()) + "\n[timed out]").trim()
                }
                Thread.sleep(40)
            }
            return channel.exitStatus to out.toString(Charsets.UTF_8.name()).trim()
        } finally {
            channel.disconnect()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Where and how the bridge runs on the server
    // ---------------------------------------------------------------------------------------------------------

    private data class Plan(val bridgePort: Int, val hubPort: Int, val bind: String)

    /** `1.2.3.4` or `[::1]`: an address you reach the machine itself on, with no proxy in between. */
    private fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains(':')

    /**
     * Does this address point straight at the service's own port? Yes for an IP address with a port, or a plain-http
     * host name on exactly the app's default port. Everything else (https, no port, a host name on another port such
     * as http://hub.example.com:8081) is a reverse proxy that forwards to the service on the server's loopback.
     */
    private fun isDirect(uri: java.net.URI?, defaultPort: Int): Boolean {
        if (uri == null || uri.port <= 0 || uri.scheme.equals("https", true)) return false
        return isIpLiteral(uri.host.orEmpty()) || uri.port == defaultPort
    }

    /**
     * Bridge/hub listen ports and bind address on the server. Behind a reverse proxy the services listen on the app's
     * default ports (the proxy's own port in the address, like :8081, is never used for them) and the bridge only on
     * the server's loopback; with a direct address they listen on the typed port on every interface. Settings
     * (Advanced) can override each part.
     */
    private fun plan(context: Context): Plan {
        val prefs = AuthPreferences(context)
        val (bp, hp, bind) = prefs.getRemoteListenSync()
        val bu = runCatching { java.net.URI(AuthPreferences.currentBridgeHttpUrl) }.getOrNull()
        val hu = runCatching { java.net.URI(AuthPreferences.currentHubUrl) }.getOrNull()
        if (AuthPreferences.currentRemoteTunnel) {
            // SSH tunnel: the port in each 127.0.0.1 address is the server port, and nothing listens beyond loopback
            return Plan(
                bu?.port?.takeIf { it > 0 } ?: ai.antigem.BuildConfig.BRIDGE_PORT,
                hu?.port?.takeIf { it > 0 } ?: ai.antigem.BuildConfig.HUB_PORT,
                "127.0.0.1"
            )
        }
        val bridgeDirect = isDirect(bu, ai.antigem.BuildConfig.BRIDGE_PORT)
        val hubDirect = isDirect(hu, ai.antigem.BuildConfig.HUB_PORT)
        val bridgePort = bp.toIntOrNull()
            ?: if (bridgeDirect) bu!!.port else ai.antigem.BuildConfig.BRIDGE_PORT
        val hubPort = hp.toIntOrNull()
            ?: if (hubDirect) hu!!.port else ai.antigem.BuildConfig.HUB_PORT
        return Plan(bridgePort, hubPort, bind.ifBlank { if (bridgeDirect) "0.0.0.0" else "127.0.0.1" })
    }

    private fun unreachableHint(): String =
        if (AuthPreferences.currentRemoteTunnel) L.remote.serverManager.checkTheSshTunnelInSettings
        else L.remote.serverManager.checkTheReverseProxyTheFirewall

    private suspend fun bridgeReachable(): Boolean =
        AgyBridgeService.instance.checkServerHealth(AuthPreferences.currentBridgeHttpUrl)

    private fun serverLoopbackHealthy(s: Session, port: Int): Boolean {
        val cmd = "(curl -s -m 4 -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/api/health 2>/dev/null) || " +
            "(bash -c 'exec 3<>/dev/tcp/127.0.0.1/$port' >/dev/null 2>&1 && echo 200) || echo 000"
        val (_, out) = exec(s, cmd, 12_000)
        return out.trim().endsWith("200")
    }

    /** Defaults shown in Settings (and used when a field is left blank). `~` is the SSH user's home on the server. */
    const val DEFAULT_AGY_BIN = "~/.local/bin/agy"
    const val DEFAULT_CLAUDE_BIN = "~/.local/bin/claude"
    const val DEFAULT_OPENCODE_BIN = "~/.opencode/bin/opencode"
    const val DEFAULT_CODEX_BIN = "~/.local/bin/codex"
    fun defaultBridgeBin(installDir: String): String =
        "~/" + installDir.trim().trim('/').ifBlank { ".antigem" } + "/agy_ide_bridge"

    private fun effectiveBridgeBin(prefs: AuthPreferences): String =
        prefs.getRemoteBridgeBinSync().trim().ifBlank { defaultBridgeBin(prefs.getRemoteInstallDirSync()) }

    private fun effectiveAgyBin(prefs: AuthPreferences): String = prefs.getRemoteAgyBinSync().trim().ifBlank { DEFAULT_AGY_BIN }
    private fun effectiveClaudeBin(prefs: AuthPreferences): String = prefs.getRemoteClaudeBinSync().trim().ifBlank { DEFAULT_CLAUDE_BIN }
    private fun effectiveOpenCodeBin(prefs: AuthPreferences): String = prefs.getRemoteOpenCodeBinSync().trim().ifBlank { DEFAULT_OPENCODE_BIN }
    private fun effectiveCodexBin(prefs: AuthPreferences): String = prefs.getRemoteCodexBinSync().trim().ifBlank { DEFAULT_CODEX_BIN }

    private fun serverHome(s: Session): String {
        val (_, home) = exec(s, "printf %s \"\$HOME\"")
        if (home.isBlank()) throw IllegalStateException(L.remote.serverManager.couldNotReadTheServersHome)
        return home.trim().trimEnd('/')
    }

    private fun expandHome(path: String, home: String): String = when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }

    /**
     * The bridge binary is never uploaded by the app: the user puts it in the server folder. Returns its absolute path
     * or throws a message that says exactly what to do.
     */
    private fun ensureBridgeBinary(context: Context, s: Session, home: String): String {
        val path = expandHome(effectiveBridgeBin(AuthPreferences(context)), home)
        _detail.value = L.remote.serverManager.checkingTheBridgeOnTheServer
        val (_, state) = exec(
            s,
            "p=${q(path)}; if [ ! -e \"\$p\" ]; then echo missing; elif [ -d \"\$p\" ]; then echo dir; " +
                "elif [ ! -x \"\$p\" ]; then echo noexec; else echo ok; fi"
        )
        when (state.trim().lines().lastOrNull()) {
            "ok" -> return path
            "dir" -> throw IllegalStateException(L.remote.serverManager.theBridgePathOnTheServer(path))
            "noexec" -> throw IllegalStateException(L.remote.serverManager.theBridgeOnTheServerIs(path))
            else -> {
                val arch = exec(s, "uname -m").second.trim().ifBlank { L.remote.serverManager.unknownCpu }
                val build = when (arch) {
                    "x86_64", "amd64" -> "agy_ide_bridge_linux_amd64"
                    "aarch64", "arm64" -> "agy_ide_bridge_linux_arm64"
                    else -> L.remote.serverManager.aLinuxBuildForArch(arch)
                }
                throw IllegalStateException(
                    L.remote.serverManager.theBridgeIsNotOnThe(build, path, arch)
                )
            }
        }
    }

    /**
     * The path to hand to the bridge for an agent, or null to let the bridge find it itself. A missing agent must not stop
     * the bridge from starting (the app can install agy from Settings > Agents), so a path that does not exist is left
     * out; a bare name such as `claude` is always passed (the bridge looks it up in PATH).
     */
    private fun agentPathOrNull(s: Session, path: String): String? {
        if (!path.contains('/')) return path
        return if (exec(s, "test -x ${q(path)}").first == 0) path else null
    }

    /** Where the app expects the bridge binary on the server (with `~` for the home folder). */
    fun bridgeBinaryPath(context: Context): String = effectiveBridgeBin(AuthPreferences(context))

    private fun launchScript(context: Context, bin: String, agyPath: String?, claudePath: String?, openCodePath: String? = null, codexPath: String? = null): String {
        val prefs = AuthPreferences(context)
        val installDir = bin.substringBeforeLast('/')
        val p = plan(context)
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val enabledAgents = ai.antigem.data.agent.AgentPreferences.enabledSync(context)
        val claudeEnabled = ai.antigem.domain.model.AgentKind.CLAUDE in enabledAgents
        val agyEnabled = ai.antigem.domain.model.AgentKind.AGY in enabledAgents
        val core = buildString {
            // no --token: other users on the server would see it with `ps`. The bridge reads it from the 0600 file
            // startInternal writes first (~/.antigem_bridge/csrf_token.txt)
            append(q(bin)).append(" -f")
            append(" -p ").append(p.bridgePort)
            append(" --hub-port ").append(p.hubPort)
            append(" --bind ").append(q(p.bind))
            append(" --tz-offset ").append(tz)
            if (agyEnabled && agyPath != null) append(" --bin ").append(q(agyPath))
            if (claudeEnabled && claudePath != null) append(" --claude-bin ").append(q(claudePath))
            if (ai.antigem.domain.model.AgentKind.OPENCODE in enabledAgents && openCodePath != null) append(" --opencode-bin ").append(q(openCodePath))
            if (ai.antigem.domain.model.AgentKind.CODEX in enabledAgents && codexPath != null) append(" --codex-bin ").append(q(codexPath))
            append(ai.antigem.data.agent.AgentPreferences.bridgeFlags(context))
        }
        val log = "$installDir/bridge.log"
        val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.gemini/bin:\$HOME/.antigravity/bin:/usr/local/bin:\$PATH\"; "
        // Files with secrets (log: hub output; agent configs: X-Device-Id headers) readable only by the SSH user. Also
        // fixes the modes older versions left. Only these files: the bridge and the agents keep the server's umask.
        val privateFiles = listOf(
            ".gemini/config/mcp_config.json", ".gemini/antigravity/mcp_config.json", ".claude.json",
            ".codex/config.toml", ".config/opencode/opencode.json", ".config/opencode/opencode.jsonc",
            ".config/opencode/config.json"
        ).joinToString(" ") { "\"${'$'}HOME/$it\"" }
        return """
            export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.gemini/bin:${'$'}HOME/.antigravity/bin:/usr/local/bin:${'$'}PATH"
            mkdir -p ${q(installDir)}
            ( umask 077; : > ${q(log)} )
            chmod 600 ${q(log)}
            chmod 700 "${'$'}HOME/.antigem_bridge" 2>/dev/null
            for f in $privateFiles; do [ -f "${'$'}f" ] && chmod 600 "${'$'}f" 2>/dev/null; done
            cd "${'$'}HOME"
            tmux kill-session -t $TMUX_SESSION 2>/dev/null
            if command -v tmux >/dev/null 2>&1; then
              tmux new-session -d -s $TMUX_SESSION ${q(pathExport + "$core 2>&1 | tee -a ${q(log)}")}
            else
              nohup setsid $core >> ${q(log)} 2>&1 < /dev/null &
            fi
            true
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Public controls (same names LocalServerManager has, so the callers just forward here)
    // ---------------------------------------------------------------------------------------------------------

    // Operations are never cancelled half way (a cancelled SSH command keeps running on the server anyway): they run
    // one after the other, and asking for an operation that is already running or queued does nothing.
    private val pendingOps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun submit(kind: String, block: suspend () -> Unit) {
        if (!pendingOps.add(kind)) return
        scope.launch {
            try { block() } finally { pendingOps.remove(kind) }
        }
    }

    fun autoStart(context: Context) {
        if (!isRemote) return
        ensureSupervisor(context)
        start(context, force = false)
    }

    fun start(context: Context, force: Boolean = false) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        submit(if (force) "restart" else "start") { startInternal(context.applicationContext, force) }
    }

    fun stop() {
        submit("stop") { stopInternal() }
    }

    fun restart(context: Context) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        submit("restart") { startInternal(context.applicationContext, force = true) }
    }

    suspend fun restartAndWait(context: Context) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        startInternal(context.applicationContext, force = true)
    }

    suspend fun stopCompletely() {
        stopInternal()
    }

    /**
     * `[a]gy_ide_bridge.* -f( |$)`: matches the running bridge but not the shell that runs pgrep. Also a bridge an older
     * version started with `-f --token …`.
     */
    private fun bridgePattern(context: Context): String {
        val name = effectiveBridgeBin(AuthPreferences(context)).substringAfterLast('/').ifBlank { "agy_ide_bridge" }
        return "[" + name.first() + "]" + name.drop(1) + ".* -f( |$)"
    }

    private fun bridgeProcessAlive(s: Session, context: Context): Boolean =
        exec(s, "pgrep -f ${q(bridgePattern(context))} >/dev/null 2>&1 && echo yes || echo no", 8_000).second.trim().endsWith("yes")

    private fun logTail(s: Session, bin: String): String =
        runCatching { exec(s, "tail -n 12 ${q(bin.substringBeforeLast('/') + "/bridge.log")} 2>/dev/null | sed 's/\\x1b\\[[0-9;]*[A-Za-z]//g'", 8_000).second }.getOrDefault("")

    private suspend fun startInternal(context: Context, force: Boolean) = opLock.withLock {
        withContext(Dispatchers.IO) {
            if (AuthPreferences.pcOverride != null) {
                attachToPc()
                return@withContext
            }
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Starting)
            try {
                _detail.value = L.remote.serverManager.connectingOverSsh
                val s = session()
                val p = plan(context)
                val prefs = AuthPreferences(context)

                if (!force) {
                    if (bridgeReachable()) {
                        LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
                        _detail.value = ""
                        refreshLogInternal(context, s)
                        return@withContext
                    }
                    // Running on the server but the app cannot reach it: do not kill a healthy bridge, say what is wrong
                    if (runCatching { serverLoopbackHealthy(s, p.bridgePort) }.getOrDefault(false)) {
                        _detail.value = ""
                        LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(
                            "The bridge is running on the server (port ${p.bridgePort}) but the app cannot reach " +
                                "${AuthPreferences.currentBridgeHttpUrl}. ${unreachableHint()} " +
                                "(Restart relaunches the bridge)."
                        ))
                        refreshLogInternal(context, s)
                        return@withContext
                    }
                }

                // check everything first, so a Restart with a missing binary does not stop a working bridge
                val home = serverHome(s)
                val bin = ensureBridgeBinary(context, s, home)
                val enabledAgents = ai.antigem.data.agent.AgentPreferences.enabledSync(context)
                val agyPath = agentPathOrNull(s, expandHome(effectiveAgyBin(prefs), home))
                val claudePath = agentPathOrNull(s, expandHome(effectiveClaudeBin(prefs), home))
                val openCodePath = agentPathOrNull(s, expandHome(effectiveOpenCodeBin(prefs), home))
                val codexPath = agentPathOrNull(s, expandHome(effectiveCodexBin(prefs), home))
                // the token goes to the bridge in a 0600 file, not on its command line (launchScript)
                writePrivateFile(s, tokenDir(home), TOKEN_FILE, AuthPreferences.currentSecurityToken.toByteArray())

                // Never launch over an old copy: graceful on a restart, only leftovers on a plain start
                _detail.value = if (force) L.remote.serverManager.stoppingTheOldBridge else L.remote.serverManager.startingTheBridge
                stopServerProcesses(s, context, graceful = force)

                _detail.value = L.remote.serverManager.startingTheBridge
                val (code, out) = exec(s, launchScript(context, bin, agyPath, claudePath, openCodePath, codexPath), 30_000)
                if (code != 0) throw IllegalStateException(L.remote.serverManager.couldNotStartTheBridgeOut(out))

                _detail.value = L.remote.serverManager.waitingForTheBridge
                var up = false
                var died = false
                for (i in 0 until 80) {
                    if (bridgeReachable()) { up = true; break }
                    // after a few seconds, stop waiting if the process is gone: it crashed, no need to wait 40 s
                    if (i >= 6 && i % 4 == 2 && !bridgeProcessAlive(s, context)) { died = true; break }
                    delay(500)
                }
                if (up) {
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
                    syncTunnels(s)
                } else {
                    val onLoopback = runCatching { serverLoopbackHealthy(s, p.bridgePort) }.getOrDefault(false)
                    val msg = when {
                        onLoopback ->
                            L.remote.serverManager.theBridgeIsRunningOnThe(p.bridgePort, AuthPreferences.currentBridgeHttpUrl, unreachableHint())
                        else -> {
                            val tail = logTail(s, bin).trim()
                            (if (died) L.remote.serverManager.theBridgeStoppedRightAfterIt else L.remote.serverManager.theBridgeDidNotComeUp) +
                                if (tail.isNotBlank()) L.remote.serverManager.lastLogLinesTail(tail) else L.remote.serverManager.seeTheServerLog
                        }
                    }
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(msg))
                }
                _detail.value = ""
                refreshLogInternal(context, s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _detail.value = ""
                LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(friendly(e)))
            }
        }
    }

    /**
     * Connect to PC: the bridge on the PC belongs to AntiGem in VS Code. Only connect (SSH, tunnels, token) and check
     * that it answers; never install, start or stop anything there.
     */
    private suspend fun attachToPc() {
        val pc = AuthPreferences.pcOverride ?: return
        LocalServerManager.publishRemoteStatus(LocalServerStatus.Starting)
        try {
            _detail.value = L.remote.serverManager.connectingToPcname(pc.pcName)
            val s = session()
            syncTunnels(s)
            _detail.value = L.remote.serverManager.checkingAntigemOnPcname(pc.pcName)
            var up = false
            for (i in 0 until 12) {
                if (bridgeReachable()) { up = true; break }
                delay(500)
            }
            _detail.value = ""
            if (up) {
                LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
            } else {
                LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(
                    "Connected to ${pc.pcName}, but AntiGem's bridge is not running there. Open VS Code with AntiGem on the PC " +
                        "(if it was stopped, press ▶ in its side bar), then tap Retry."
                ))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _detail.value = ""
            Log.e(TAG, "connect to PC failed", e)
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(L.remote.serverManager.cannotConnectToPcnameFriendly(pc.pcName, friendly(e))))
        }
    }

    private suspend fun stopInternal() = opLock.withLock {
        // Connect to PC: "stop" only disconnects; the PC's bridge keeps running for VS Code
        if (AuthPreferences.pcOverride != null) {
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopping)
            disconnect()
            AgyBridgeService.instance.notifyLocalStopped()
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopped(exitCode = 0))
            return@withLock
        }
        withContext(Dispatchers.IO) {
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopping)
            try {
                val ctx = appContext
                // try to bring SSH back if it dropped: stopping through the tmux session is the reliable way
                val s = runCatching { session() }.getOrNull()
                if (s != null && ctx != null) {
                    stopServerProcesses(s, ctx, graceful = true)
                } else {
                    gracefulShutdownOverHttp()
                    for (i in 0 until 16) { if (!bridgeReachable()) break; delay(250) }
                }
                val stillUp = bridgeReachable() ||
                    (s != null && ctx != null && runCatching { serverLoopbackHealthy(s, plan(ctx).bridgePort) }.getOrDefault(false))
                if (stillUp) {
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(
                        if (s == null) "Could not stop the bridge: SSH is not connected and the bridge did not accept the shutdown request."
                        else "The bridge is still running on the server after the stop request."
                    ))
                } else {
                    AgyBridgeService.instance.notifyLocalStopped()
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopped(exitCode = 0))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
                LocalServerManager.publishRemoteStatus(LocalServerStatus.Error(friendly(e)))
            }
        }
    }

    private suspend fun gracefulShutdownOverHttp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${AuthPreferences.currentBridgeHttpUrl.trimEnd('/')}/api/shutdown")
                .post("{}".toRequestBody(null))
                .build()
            AgyBridgeService.instance.fastClient.newCall(req).execute().close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Graceful HTTP shutdown first (the bridge stops its agents cleanly), then the tmux session and leftovers. */
    private suspend fun stopServerProcesses(s: Session, context: Context, graceful: Boolean) {
        if (graceful) {
            gracefulShutdownOverHttp()
            for (i in 0 until 16) {
                if (!bridgeReachable()) break
                delay(250)
            }
        }
        extensionHostReady = false // the plugin host session is killed below too
        val pat = q(bridgePattern(context))
        // The bracket in [a] keeps these patterns from matching the shell that runs them.
        exec(
            s,
            "tmux kill-session -t $TMUX_SESSION 2>/dev/null; tmux kill-session -t $EXT_TMUX 2>/dev/null; " +
                "pids=\$(pgrep -f $pat); " +
                "if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null; sleep 1; " +
                "pids=\$(pgrep -f $pat); [ -n \"\$pids\" ] && kill -9 \$pids 2>/dev/null; fi; true",
            20_000
        )
        // an agent hub the bridge could not stop itself (only when it is still bound to the port)
        val hubPort = plan(context).hubPort
        exec(s, "pids=\$(pgrep -f '[a]gy --hub-port=$hubPort'); [ -n \"\$pids\" ] && kill \$pids 2>/dev/null; true", 10_000)
    }

    /** Ends a tmux session on the server (the one a finished Run configuration lived in). */
    fun killTmuxSession(name: String) {
        if (!name.matches(Regex("[A-Za-z0-9_-]+"))) return
        scope.launch {
            val s = control?.takeIf { it.isConnected } ?: return@launch
            runCatching { exec(s, "tmux kill-session -t $name 2>/dev/null; true", 10_000) }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Browser-plugin host (Node.js) on the server
    // ---------------------------------------------------------------------------------------------------------

    private const val EXT_TMUX = "antigem-exthost"
    private val extLock = Mutex()

    /** True once the plugin host answers on the server. */
    @Volatile
    var extensionHostReady: Boolean = false
        private set

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Writes a small file on the server: over SFTP, or `cat > file` when sshd has no SFTP subsystem. */
    private fun uploadBytes(s: Session, bytes: ByteArray, remotePath: String) {
        try {
            val sftp = s.openChannel("sftp") as ChannelSftp
            sftp.connect(15_000)
            try { sftp.put(java.io.ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE) } finally { sftp.disconnect() }
        } catch (e: Exception) {
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand("cat > ${q(remotePath)}")
            val out = ch.outputStream
            ch.connect(15_000)
            try {
                out.write(bytes); out.flush(); out.close()
                var waited = 0
                while (!ch.isClosed && waited < 20_000) { Thread.sleep(50); waited += 50 }
            } finally {
                ch.disconnect()
            }
        }
    }

    /**
     * Writes a secret file on the server that only the SSH user can read: folder 0700, file 0600. The content goes
     * through stdin, never on a command line (other users on the server see command lines with `ps`).
     */
    private fun writePrivateFile(s: Session, dir: String, name: String, bytes: ByteArray) {
        val path = "$dir/$name"
        val tmp = "$path.tmp"
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("sh -c ${q("umask 077 && mkdir -p ${q(dir)} && chmod 700 ${q(dir)} && cat > ${q(tmp)} && chmod 600 ${q(tmp)} && mv -f ${q(tmp)} ${q(path)}")} 2>&1")
        val output = ch.inputStream
        val stdin = ch.outputStream
        ch.connect(15_000)
        try {
            stdin.write(bytes); stdin.flush(); stdin.close()
            var waited = 0
            while (!ch.isClosed && waited < 20_000) { Thread.sleep(50); waited += 50 }
            if (!ch.isClosed) throw IllegalStateException("Could not write $path on the server (timed out).")
            if (ch.exitStatus != 0) {
                val msg = runCatching { output.readBytes().toString(Charsets.UTF_8).trim() }.getOrDefault("")
                throw IllegalStateException("Could not write $path on the server. $msg".trim())
            }
        } finally {
            ch.disconnect()
        }
    }

    /** The bridge reads its token from here when started without --token (`security.ResolveToken`). */
    private fun tokenDir(home: String) = "$home/.antigem_bridge"
    private const val TOKEN_FILE = "csrf_token.txt"
    /** The phone tools key for the plugin host on the server (next to the token, in the same 0700 folder). */
    private const val DEVICE_KEY_FILE = "phone_tools_key.txt"

    /** `AGY_DEVICE_ID="$(cat file)" `: the shell reads the key, so it never appears in a command line. */
    private fun deviceKeyEnv(keyFile: String) = "AGY_DEVICE_ID=\"\$(cat ${q(keyFile)})\" "

    private fun extensionHostHealthy(s: Session, node: String, port: Int, keyFile: String): Boolean {
        val script = "fetch('http://127.0.0.1:$port/health',{method:'POST',headers:{'x-device-id':process.env.AGY_DEVICE_ID||'','content-type':'application/json'},body:'{}'})" +
            ".then(r=>r.text()).then(t=>console.log(t)).catch(()=>{})"
        val (_, out) = exec(s, "${deviceKeyEnv(keyFile)}${q(node)} -e ${q(script)}", 10_000)
        return out.replace(" ", "").contains("\"ready\":true")
    }

    /**
     * Browser plugins (the Node.js "extension host") run on the server, next to the plugin folders the bridge manages
     * there. This installs the runtime (only Node's built-in modules), starts it in tmux and leaves the app talking to
     * it through the local forward; the host reaches the phone's browser/MCP server through the reverse tunnel.
     * Throws a readable message when Node.js is missing on the server.
     */
    suspend fun ensureExtensionHost(context: Context) = extLock.withLock { withContext(Dispatchers.IO) {
        extensionHostReady = false
        if (AuthPreferences.pcOverride != null) {
            throw IllegalStateException(L.remote.serverManager.browserPluginsDontRunOnA)
        }
        appContext = context.applicationContext
        val hostPort = ai.antigem.ui.browser.BrowserExtensionManager.instance.hostPort()
        val mcpPort = AndroidLocalBridgeServer.DEFAULT_PORT
        val s = session()

        // Node.js: PATH of a non-interactive SSH login is short, so look in the usual places too
        val findNode = "c=\$(command -v node 2>/dev/null); [ -x \"\$c\" ] && { echo \"\$c\"; exit 0; }; " +
            "for c in \"\$HOME/.local/bin/node\" /usr/local/bin/node /usr/bin/node; do [ -x \"\$c\" ] && { echo \"\$c\"; exit 0; }; done; " +
            "for c in \"\$HOME\"/.nvm/versions/node/*/bin/node; do [ -x \"\$c\" ] && last=\"\$c\"; done; [ -n \"\$last\" ] && echo \"\$last\"; true"
        val node = exec(s, findNode, 15_000).second.lines().lastOrNull { it.startsWith("/") }?.trim()
            ?: throw IllegalStateException(
                "Node.js is not installed on the server. Browser plugins need Node.js 18 or newer there " +
                    "(for example: sudo apt install nodejs, or install it with nvm for the SSH user)."
            )
        val version = exec(s, "${q(node)} --version", 10_000).second.trim()
        val major = version.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
        if (major < 18) throw IllegalStateException(L.remote.serverManager.nodeJsVersionOnTheServer(version))

        val home = exec(s, "printf %s \"\$HOME\"").second.trim()
        if (home.isBlank()) throw IllegalStateException(L.remote.serverManager.couldNotReadTheServersHome)
        val base = "$home/.antigem"
        val runtimeDir = "$base/browser-runtime"
        val rootDir = "$base/browser-extensions"
        exec(s, "mkdir -p ${q(runtimeDir)} ${q(rootDir)}")

        // the phone tools key goes to a 0600 file (written every time: it may have been renewed in Settings); the host
        // gets it in AGY_DEVICE_ID, so it is never in a command line other users on the server can see
        writePrivateFile(s, tokenDir(home), DEVICE_KEY_FILE, LocalServerAuth.key(context).toByteArray())
        val keyFile = "${tokenDir(home)}/$DEVICE_KEY_FILE"

        // runtime files: upload what differs
        _detail.value = L.remote.serverManager.installingTheBrowserPluginHost
        val remoteHashes = exec(s, "cd ${q(runtimeDir)} 2>/dev/null && sha256sum *.mjs 2>/dev/null").second.lines()
            .mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { it[1].removePrefix("*") to it[0] } }.toMap()
        var changed = false
        for (name in context.assets.list("browser-runtime").orEmpty().filter { it.endsWith(".mjs") }) {
            val bytes = context.assets.open("browser-runtime/$name").use { it.readBytes() }
            if (remoteHashes[name] != sha256Hex(bytes)) {
                uploadBytes(s, bytes, "$runtimeDir/$name")
                changed = true
            }
        }

        // a host started by an older version has the key in its command line (--device-id): start it again
        val oldArgv = exec(s, "pgrep -f ${q("[e]xtension-host.mjs --serve --port $hostPort .*--device-id")} >/dev/null 2>&1 && echo yes || echo no", 8_000)
            .second.trim().endsWith("yes")
        if (!changed && !oldArgv && extensionHostHealthy(s, node, hostPort, keyFile)) {
            _detail.value = ""
            syncTunnels(s)
            extensionHostReady = true
            return@withContext
        }

        // only the built-in plugins (--root) load as a whole at start; ~/extensions' plugins load one by one once the
        // bridge says the user approved them (refreshExtensions)
        val log = "$base/extension-host.log"
        val core = buildString {
            append(q(node)).append(" --experimental-vm-modules ").append(q("$runtimeDir/extension-host.mjs"))
            append(" --serve --port ").append(hostPort)
            append(" --root ").append(q(rootDir))
            append(" --bridge-url http://127.0.0.1:").append(mcpPort)
        }
        _detail.value = L.remote.serverManager.startingTheBrowserPluginHost
        val launch = """
            tmux kill-session -t $EXT_TMUX 2>/dev/null
            pids=${'$'}(pgrep -f '[e]xtension-host.mjs --serve --port $hostPort'); [ -n "${'$'}pids" ] && kill ${'$'}pids 2>/dev/null
            [ "${'$'}(wc -c < ${q(log)} 2>/dev/null || echo 0)" -gt 2000000 ] && : > ${q(log)}
            cd "${'$'}HOME"
            if command -v tmux >/dev/null 2>&1; then
              tmux new-session -d -s $EXT_TMUX ${q("sh -c " + q("${deviceKeyEnv(keyFile)}$core 2>&1 | tee -a ${q(log)}"))}
            else
              ${deviceKeyEnv(keyFile)}nohup setsid $core >> ${q(log)} 2>&1 < /dev/null &
            fi
            true
        """.trimIndent()
        val (code, out) = exec(s, launch, 20_000)
        if (code != 0) throw IllegalStateException(L.remote.serverManager.couldNotStartTheBrowserPlugin(out))

        var up = false
        for (i in 0 until 60) {
            if (extensionHostHealthy(s, node, hostPort, keyFile)) { up = true; break }
            delay(500)
        }
        _detail.value = ""
        if (!up) {
            val tail = exec(s, "tail -n 12 ${q(log)} 2>/dev/null", 8_000).second
            throw IllegalStateException(L.remote.serverManager.theBrowserPluginHostDidNot + if (tail.isNotBlank()) "\n$tail" else "")
        }
        syncTunnels(s)
        extensionHostReady = true
    } }

    // ---------------------------------------------------------------------------------------------------------
    // Log
    // ---------------------------------------------------------------------------------------------------------

    fun refreshLog(context: Context) {
        scope.launch {
            val s = control?.takeIf { it.isConnected } ?: return@launch
            refreshLogInternal(context, s)
        }
    }

    private fun refreshLogInternal(context: Context, s: Session) {
        try {
            val prefs = AuthPreferences(context)
            val dir = effectiveBridgeBin(prefs).substringBeforeLast('/').replaceFirst("~", "\$HOME")
            val (_, out) = exec(s, "tail -n 300 \"$dir/bridge.log\" 2>/dev/null | sed 's/\\x1b\\[[0-9;]*[A-Za-z]//g'", 10_000)
            _log.value = out
        } catch (e: Exception) {
            _log.value = L.remote.serverManager.couldNotReadTheServerLog(e.message)
        }
    }

    /** One-shot checks for the Settings "Test" buttons (works before the server is started). */
    suspend fun testSsh(host: String, port: Int, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val s = SshSessionFactory.open(host, port, user, pass, connectTimeoutMs = 10_000)
            try {
                val (_, out) = exec(s, "uname -srm; id -un; command -v tmux >/dev/null && echo 'tmux: yes' || echo 'tmux: no'", 10_000)
                Result.success(out)
            } finally {
                s.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendly(e), e))
        }
    }
}
