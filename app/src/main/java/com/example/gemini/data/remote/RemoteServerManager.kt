package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalServerManager
import com.example.gemini.data.local.LocalServerStatus
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.ssh.SshSessionFactory
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
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

/**
 * Remote mode: the IDE bridge and the agents run on a server (VPS) and the app manages them over SSH.
 *
 * One long-lived SSH connection ("control session") is kept open and re-opened when it drops. Over it the app
 *  - uploads the right Linux build of the bridge (by `uname -m`) when the server has none or an older one,
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

    private val _ssh = MutableStateFlow(SshState(false, "Not connected"))
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
    private var opJob: Job? = null
    @Volatile private var appContext: Context? = null

    val isRemote: Boolean get() = AuthPreferences.currentRemoteMode

    // ---------------------------------------------------------------------------------------------------------
    // SSH control session
    // ---------------------------------------------------------------------------------------------------------

    private data class Target(val host: String, val port: Int, val user: String, val pass: String)

    private suspend fun target(context: Context): Target? {
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
            while (isActive && isRemote) {
                val s = control
                if (s != null && s.isConnected) {
                    appContext?.let { ctx -> runCatching { AdbTunnel.sync(ctx, s) } }
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

    // the supervisor and a start/stop request can both ask for the connection: open it only once
    private suspend fun connectControl(): Session = connectLock.withLock { withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("App not ready")
        control?.takeIf { it.isConnected }?.let { return@withContext it }
        val t = target(ctx) ?: throw IllegalStateException("Enter the SSH host, user and a password or key in Settings > Server")
        _ssh.value = SshState(false, "Connecting to ${t.user}@${t.host}:${t.port}…")
        val s = SshSessionFactory.open(t.host, t.port, t.user, t.pass, connectTimeoutMs = 12_000, keepAliveMs = 20_000)
        control = s
        _ssh.value = SshState(true, "Connected to ${t.user}@${t.host}")
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
        _ssh.value = SshState(false, "Not connected")
    }

    internal fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                "SSH login failed. Check the user and password, or the private key."
            m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) ->
                "The server's SSH host key changed. If you reinstalled the server, use \"Forget saved host key\" in Settings > Server."
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "SSH connection timed out. Check the host, the SSH port and the firewall."
            m.contains("refused", true) -> "SSH connection refused. Is sshd running on that port?"
            m.contains("UnknownHost", true) || m.contains("unresolved", true) -> "Cannot find that host name."
            m.contains("invalid privatekey", true) || m.contains("passphrase", true) ->
                "The SSH private key could not be read (wrong format or passphrase)."
            else -> m.ifBlank { e.javaClass.simpleName }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reverse port forwards: the agents on the server reach the MCP servers inside this app
    // ---------------------------------------------------------------------------------------------------------

    private fun syncTunnels(s: Session) {
        val active = mutableListOf<String>()

        // reverse: the agents/plugin host on the server reach the phone's MCP server (browser, Android, notes ...)
        val remotePort = AndroidLocalBridgeServer.DEFAULT_PORT
        val localPort = AndroidLocalBridgeServer.instance.boundPort
        try { s.delPortForwardingR("127.0.0.1", remotePort) } catch (_: Exception) {}
        try {
            s.setPortForwardingR("127.0.0.1", remotePort, "127.0.0.1", localPort)
            active += "server 127.0.0.1:$remotePort → phone :$localPort (phone tools)"
        } catch (e: Exception) {
            Log.w(TAG, "Reverse forward $remotePort failed: ${e.message}")
            active += "port $remotePort not forwarded (${e.message?.take(60)})"
        }

        // local: the app talks to the browser-plugin host that runs on the server
        val hostPort = com.example.gemini.ui.browser.BrowserExtensionManager.instance.hostPort()
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
        val bridgeDirect = isDirect(bu, com.example.gemini.BuildConfig.BRIDGE_PORT)
        val hubDirect = isDirect(hu, com.example.gemini.BuildConfig.HUB_PORT)
        val bridgePort = bp.toIntOrNull()
            ?: if (bridgeDirect) bu!!.port else com.example.gemini.BuildConfig.BRIDGE_PORT
        val hubPort = hp.toIntOrNull()
            ?: if (hubDirect) hu!!.port else com.example.gemini.BuildConfig.HUB_PORT
        return Plan(bridgePort, hubPort, bind.ifBlank { if (bridgeDirect) "0.0.0.0" else "127.0.0.1" })
    }

    private suspend fun bridgeReachable(): Boolean =
        AgyBridgeService.instance.checkServerHealth(AuthPreferences.currentBridgeHttpUrl)

    private fun serverLoopbackHealthy(s: Session, port: Int): Boolean {
        val cmd = "(curl -s -m 4 -o /dev/null -w '%{http_code}' http://127.0.0.1:$port/api/health 2>/dev/null) || " +
            "(bash -c 'exec 3<>/dev/tcp/127.0.0.1/$port' >/dev/null 2>&1 && echo 200) || echo 000"
        val (_, out) = exec(s, cmd, 12_000)
        return out.trim().endsWith("200")
    }

    private fun assetNameFor(arch: String): String? = when (arch.trim()) {
        "x86_64", "amd64" -> "agy_ide_bridge_linux_amd64"
        "aarch64", "arm64" -> "agy_ide_bridge_linux_arm64"
        else -> null
    }

    /** SHA-256 and size of a bundled file (read once; the server copy is compared against it). */
    private fun assetDigest(context: Context, asset: String): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open("bin/$asset").use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } to size
    }

    /** Makes sure a bridge binary exists on the server and returns its absolute path there. */
    private fun ensureBridgeBinary(context: Context, s: Session): String {
        val custom = AuthPreferences(context).getRemoteBridgeBinSync()
        if (custom.isNotBlank()) {
            val (code, _) = exec(s, "test -x ${q(custom)}")
            if (code != 0) throw IllegalStateException("The bridge binary on the server was not found or is not executable: $custom")
            return custom
        }

        _detail.value = "Checking the server…"
        val (unameCode, uname) = exec(s, "uname -sm")
        if (unameCode != 0 || !uname.startsWith("Linux")) {
            throw IllegalStateException("Only Linux servers are supported (found: ${uname.ifBlank { "unknown" }}).")
        }
        val arch = uname.substringAfter(' ')
        val asset = assetNameFor(arch)
            ?: throw IllegalStateException("Unsupported server CPU: $arch (supported: x86_64, aarch64).")

        val (_, home) = exec(s, "printf %s \"\$HOME\"")
        if (home.isBlank()) throw IllegalStateException("Could not read the server's home directory.")
        val dir = home.trimEnd('/') + "/" + AuthPreferences(context).getRemoteInstallDirSync().trim('/')
        val path = "$dir/agy_ide_bridge"

        val (localSha, total) = try {
            assetDigest(context, asset)
        } catch (e: java.io.FileNotFoundException) {
            throw IllegalStateException(
                "This app build has no Linux server bridge ($asset). Build it with scripts/build_server_bridge.sh, " +
                    "or put the bridge on the server yourself and set \"Bridge binary on the server\"."
            )
        }
        val (_, remoteSha) = exec(s, "sha256sum ${q(path)} 2>/dev/null | cut -d' ' -f1")
        if (remoteSha.trim() == localSha) return path

        _detail.value = "Uploading the bridge…"
        exec(s, "mkdir -p ${q(dir)}")
        val temp = "$path.new"
        val onProgress = { done: Long ->
            if (total > 0) _detail.value = "Uploading the bridge… ${(done * 100 / total).coerceAtMost(100)}%"
        }
        try {
            val sftp = s.openChannel("sftp") as ChannelSftp
            sftp.connect(15_000)
            try {
                context.assets.open("bin/$asset").use { input ->
                    sftp.put(input, temp, object : SftpProgressMonitor {
                        private var done = 0L
                        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                        override fun count(count: Long): Boolean {
                            done += count
                            onProgress(done)
                            return true
                        }
                        override fun end() {}
                    }, ChannelSftp.OVERWRITE)
                }
            } finally {
                sftp.disconnect()
            }
        } catch (e: Exception) {
            // sshd without the SFTP subsystem: stream the file through a plain `cat > file`
            Log.w(TAG, "SFTP upload failed (${e.message}), falling back to cat")
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand("cat > ${q(temp)}")
            val out = ch.outputStream
            ch.connect(15_000)
            try {
                context.assets.open("bin/$asset").use { input ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
                out.flush()
                out.close()
                var waited = 0
                while (!ch.isClosed && waited < 20_000) { Thread.sleep(50); waited += 50 }
            } finally {
                ch.disconnect()
            }
        }
        val (mvCode, mvOut) = exec(s, "chmod +x ${q("$path.new")} && mv -f ${q("$path.new")} ${q(path)}")
        if (mvCode != 0) throw IllegalStateException("Could not install the bridge on the server: $mvOut")
        return path
    }

    private fun launchScript(context: Context, bin: String, installDir: String): String {
        val prefs = AuthPreferences(context)
        val p = plan(context)
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val agy = prefs.getRemoteAgyBinSync()
        val claude = prefs.getRemoteClaudeBinSync()
        val claudeEnabled = com.example.gemini.domain.model.AgentKind.CLAUDE in
            com.example.gemini.data.agent.AgentPreferences.enabledSync(context)
        val core = buildString {
            append(q(bin)).append(" -f --token ").append(AuthPreferences.currentSecurityToken)
            append(" -p ").append(p.bridgePort)
            append(" --hub-port ").append(p.hubPort)
            append(" --bind ").append(q(p.bind))
            append(" --tz-offset ").append(tz)
            if (agy.isNotBlank()) append(" --bin ").append(q(agy))
            if (claude.isNotBlank() && claudeEnabled) append(" --claude-bin ").append(q(claude))
            append(com.example.gemini.data.agent.AgentPreferences.bridgeFlags(context))
        }
        val log = "$installDir/bridge.log"
        val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.gemini/bin:\$HOME/.antigravity/bin:/usr/local/bin:\$PATH\"; "
        return """
            export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.gemini/bin:${'$'}HOME/.antigravity/bin:/usr/local/bin:${'$'}PATH"
            mkdir -p ${q(installDir)}
            [ "${'$'}(wc -c < ${q(log)} 2>/dev/null || echo 0)" -gt 5000000 ] && : > ${q(log)}
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

    fun autoStart(context: Context) {
        if (!isRemote) return
        ensureSupervisor(context)
        start(context, force = false)
    }

    fun start(context: Context, force: Boolean = false) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        opJob?.cancel()
        opJob = scope.launch { startInternal(context.applicationContext, force) }
    }

    fun stop() {
        opJob?.cancel()
        opJob = scope.launch { stopInternal() }
    }

    fun restart(context: Context) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        opJob?.cancel()
        opJob = scope.launch { startInternal(context.applicationContext, force = true) }
    }

    suspend fun restartAndWait(context: Context) {
        appContext = context.applicationContext
        ensureSupervisor(context)
        startInternal(context.applicationContext, force = true)
    }

    suspend fun stopCompletely() {
        stopInternal()
    }

    private suspend fun startInternal(context: Context, force: Boolean) = opLock.withLock {
        withContext(Dispatchers.IO) {
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Starting)
            try {
                _detail.value = "Connecting over SSH…"
                val s = session()
                val p = plan(context)

                if (!force && bridgeReachable()) {
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
                    _detail.value = ""
                    refreshLogInternal(context, s)
                    return@withContext
                }
                if (force) stopServerProcesses(s, context, graceful = true)

                val bin = ensureBridgeBinary(context, s)
                _detail.value = "Starting the bridge…"
                val installDir = bin.substringBeforeLast('/')
                val (code, out) = exec(s, launchScript(context, bin, installDir), 30_000)
                if (code != 0) throw IllegalStateException("Could not start the bridge: $out")

                _detail.value = "Waiting for the bridge…"
                var up = false
                for (i in 0 until 80) {
                    if (bridgeReachable()) { up = true; break }
                    delay(500)
                }
                if (up) {
                    LocalServerManager.publishRemoteStatus(LocalServerStatus.Running())
                    syncTunnels(s)
                } else {
                    val onLoopback = runCatching { serverLoopbackHealthy(s, p.bridgePort) }.getOrDefault(false)
                    val msg = if (onLoopback) {
                        "The bridge is running on the server (port ${p.bridgePort}) but the app cannot reach " +
                            "${AuthPreferences.currentBridgeHttpUrl}. Check the reverse proxy, the firewall and the URL."
                    } else {
                        "The bridge did not start on the server. See the server log."
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

    private suspend fun stopInternal() = opLock.withLock {
        withContext(Dispatchers.IO) {
            LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopping)
            try {
                val ctx = appContext
                val s = control?.takeIf { it.isConnected }
                if (s != null && ctx != null) stopServerProcesses(s, ctx, graceful = true)
                else gracefulShutdownOverHttp()
                AgyBridgeService.instance.notifyLocalStopped()
                LocalServerManager.publishRemoteStatus(LocalServerStatus.Stopped(exitCode = 0))
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
        // The bracket in [a] keeps these patterns from matching the shell that runs them.
        exec(
            s,
            "tmux kill-session -t $TMUX_SESSION 2>/dev/null; tmux kill-session -t $EXT_TMUX 2>/dev/null; " +
                "pids=\$(pgrep -f '[a]gy_ide_bridge.* -f --token'); [ -n \"\$pids\" ] && kill \$pids 2>/dev/null; " +
                "sleep 1; " +
                "pids=\$(pgrep -f '[a]gy_ide_bridge.* -f --token'); [ -n \"\$pids\" ] && kill -9 \$pids 2>/dev/null; true",
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

    private fun extensionHostHealthy(s: Session, node: String, port: Int, deviceId: String): Boolean {
        val script = "fetch('http://127.0.0.1:$port/health',{method:'POST',headers:{'x-device-id':'$deviceId','content-type':'application/json'},body:'{}'})" +
            ".then(r=>r.text()).then(t=>console.log(t)).catch(()=>{})"
        val (_, out) = exec(s, "${q(node)} -e ${q(script)}", 10_000)
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
        appContext = context.applicationContext
        val hostPort = com.example.gemini.ui.browser.BrowserExtensionManager.instance.hostPort()
        val mcpPort = AndroidLocalBridgeServer.DEFAULT_PORT
        val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
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
        if (major < 18) throw IllegalStateException("Node.js $version on the server is too old for browser plugins (18 or newer is needed).")

        val home = exec(s, "printf %s \"\$HOME\"").second.trim()
        if (home.isBlank()) throw IllegalStateException("Could not read the server's home directory.")
        val base = "$home/.antigem"
        val runtimeDir = "$base/browser-runtime"
        val rootDir = "$base/browser-extensions"
        exec(s, "mkdir -p ${q(runtimeDir)} ${q(rootDir)}")

        // runtime files: upload what differs
        _detail.value = "Installing the browser-plugin host…"
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

        if (!changed && extensionHostHealthy(s, node, hostPort, deviceId)) {
            _detail.value = ""
            syncTunnels(s)
            extensionHostReady = true
            return@withContext
        }

        // plugin folders that exist now (the bridge puts installs in ~/extensions)
        val roots = exec(
            s,
            "for d in \"\$HOME/extensions\" \"\$HOME/projects/antiGem/extensions\" \"\$HOME/.antigem/extensions\"; do [ -d \"\$d\" ] && echo \"\$d\"; done; true"
        ).second.lines().filter { it.startsWith("/") }
        val log = "$base/extension-host.log"
        val core = buildString {
            append(q(node)).append(" --experimental-vm-modules ").append(q("$runtimeDir/extension-host.mjs"))
            append(" --serve --port ").append(hostPort)
            append(" --root ").append(q(rootDir))
            roots.forEach { append(" --extra-root ").append(q(it)) }
            append(" --device-id ").append(q(deviceId))
            append(" --bridge-url http://127.0.0.1:").append(mcpPort)
        }
        _detail.value = "Starting the browser-plugin host…"
        val launch = """
            tmux kill-session -t $EXT_TMUX 2>/dev/null
            pids=${'$'}(pgrep -f '[e]xtension-host.mjs --serve --port $hostPort'); [ -n "${'$'}pids" ] && kill ${'$'}pids 2>/dev/null
            [ "${'$'}(wc -c < ${q(log)} 2>/dev/null || echo 0)" -gt 2000000 ] && : > ${q(log)}
            cd "${'$'}HOME"
            if command -v tmux >/dev/null 2>&1; then
              tmux new-session -d -s $EXT_TMUX ${q("$core 2>&1 | tee -a ${q(log)}")}
            else
              nohup setsid $core >> ${q(log)} 2>&1 < /dev/null &
            fi
            true
        """.trimIndent()
        val (code, out) = exec(s, launch, 20_000)
        if (code != 0) throw IllegalStateException("Could not start the browser-plugin host: $out")

        var up = false
        for (i in 0 until 60) {
            if (extensionHostHealthy(s, node, hostPort, deviceId)) { up = true; break }
            delay(500)
        }
        _detail.value = ""
        if (!up) {
            val tail = exec(s, "tail -n 12 ${q(log)} 2>/dev/null", 8_000).second
            throw IllegalStateException("The browser-plugin host did not start on the server." + if (tail.isNotBlank()) "\n$tail" else "")
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
            val custom = prefs.getRemoteBridgeBinSync()
            val dir = if (custom.isNotBlank()) custom.substringBeforeLast('/')
            else "\$HOME/" + prefs.getRemoteInstallDirSync().trim('/')
            val (_, out) = exec(s, "tail -n 300 \"$dir/bridge.log\" 2>/dev/null | sed 's/\\x1b\\[[0-9;]*[A-Za-z]//g'", 10_000)
            _log.value = out
        } catch (e: Exception) {
            _log.value = "Could not read the server log: ${e.message}"
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
