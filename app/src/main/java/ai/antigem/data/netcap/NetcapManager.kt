package ai.antigem.data.netcap

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.system.OsConstants
import android.util.Log
import ai.antigem.BuildConfig
import ai.antigem.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Runs the Network Interceptor engine (the Go binary `netcap`: packed into debug builds as assets/bin/netcap.xz,
 * downloaded by the others on first use from the GitHub release pinned in BuildConfig.NETCAP_*) and is its control
 * channel: the engine connects to an abstract socket this object listens on and gets the CA (kept encrypted by
 * [NetcapCert]), settings and the VPN tunnel ([NetcapVpnService]); it asks back what only Android knows (which app
 * owns a connection, starting the VPN, root certificate install). Agents reach the engine's MCP through the app's
 * local server at /network ([proxyMcp]), so the engine's token never leaves the app.
 */
object NetcapManager {
    private const val TAG = "Netcap"
    private const val ASSET = "netcap.xz"
    private const val PREFS = "netcap_prefs"
    /** "<version>/<sha256>" of the downloaded engine in bin/netcap, or "bundled". */
    private const val KEY_INSTALLED = "engine_installed"
    /** Debug builds only: download the engine from this URL instead (testing the download against a local server). */
    private const val KEY_DEV_URL = "dev_engine_url"

    /** Engine API (MCP) and proxy ports: release 8777/8787, debug 8778/8788 (next to the app's other ports). */
    val apiPort: Int get() = ai.antigem.BuildConfig.BROWSER_MCP_PORT + 10
    val proxyPort: Int get() = ai.antigem.BuildConfig.BROWSER_MCP_PORT + 20

    enum class Engine { STOPPED, DOWNLOADING, STARTING, RUNNING, ERROR, DOWNLOAD_FAILED }

    data class State(
        val engine: Engine = Engine.STOPPED,
        val error: String? = null,
        val capturing: Boolean = false,
        val apps: List<String> = emptyList(),
        val flows: Int = 0,
        val session: String = "",
        val downloaded: Long = 0,
        val downloadTotal: Long = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val token = randomHex(16)
    private val controlName = "antigem.netcap." + randomHex(8)
    private val startMutex = Mutex()
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1_000_000)

    @Volatile private var process: Process? = null
    @Volatile private var control: LocalSocket? = null
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var connected = CompletableDeferred<Unit>()
    @Volatile private var starting: Deferred<Boolean>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS) // net_wait may wait up to 120 s
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun dataDir(context: Context): File = File(LocalEnvironmentManager.getHomeDir(context), ".antigem/netcap")

    private fun Process.alive(): Boolean = try {
        exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    val isRunning: Boolean get() = process?.alive() == true && control != null

    /**
     * Starts the engine when it isn't running (downloading it first when needed). False when it could not start
     * ([State.error] says why). The start runs on its own: a caller that stops waiting doesn't cancel a download.
     */
    suspend fun ensureStarted(context: Context): Boolean {
        init(context)
        if (isRunning) return true
        val job = synchronized(this) {
            starting?.takeIf { it.isActive } ?: scope.async { startMutex.withLock { if (isRunning) true else start() } }.also { starting = it }
        }
        return job.await()
    }

    /** True when the engine is on the phone (packed into this build, or downloaded for its pinned version). */
    fun engineInstalled(context: Context): Boolean {
        init(context)
        if (bundled(appContext)) return true
        val target = File(LocalEnvironmentManager.getBinDir(appContext), "netcap")
        return target.canExecute() && appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTALLED, null) == pinned
    }

    /** Size of the download, for the settings page. */
    val downloadSize: Long get() = BuildConfig.NETCAP_SIZE

    private val pinned get() = BuildConfig.NETCAP_VERSION + "/" + BuildConfig.NETCAP_SHA256

    private fun bundled(context: Context): Boolean =
        runCatching { context.assets.list("bin")?.contains(ASSET) == true }.getOrDefault(false)

    private suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        _state.update { it.copy(engine = Engine.STARTING, error = null) }
        try {
            val bin = ensureBinary(appContext)
            _state.update { it.copy(engine = Engine.STARTING, error = null) }
            val data = dataDir(appContext).apply { mkdirs() }
            runCatching { server?.close() }
            val srv = LocalServerSocket(controlName)
            server = srv
            connected = CompletableDeferred()
            val pb = ProcessBuilder(bin.absolutePath, "serve", "--data", data.absolutePath,
                "--api", "127.0.0.1:$apiPort", "--proxy", "127.0.0.1:$proxyPort")
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("NETCAP_TOKEN", token)
                put("NETCAP_CONTROL", controlName)
                put("HOME", LocalEnvironmentManager.getHomeDir(appContext).absolutePath)
                put("TMPDIR", appContext.cacheDir.absolutePath)
                // Mozilla's roots on top of Android's (Android 7.0 lacks Let's Encrypt's), like the bridge
                val bundle = File(LocalEnvironmentManager.getPrefixDir(appContext), "etc/tls/cert.pem")
                if (bundle.exists()) put("SSL_CERT_FILE", bundle.absolutePath)
            }
            val p = pb.start()
            process = p
            val log = File(data, "engine.log")
            thread(name = "netcap-log", isDaemon = true) {
                runCatching {
                    log.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = p.inputStream.read(buf)
                            if (n < 0) break
                            if (total < 1_000_000) out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
            thread(name = "netcap-accept", isDaemon = true) {
                try {
                    val s = srv.accept()
                    serve(s)
                } catch (e: IOException) {
                    if (!connected.isCompleted) connected.completeExceptionally(e)
                }
            }
            withTimeout(20_000) { connected.await() }
            // the API answers once the engine has opened its ports
            var ok = false
            for (i in 0 until 50) {
                if (apiGet("/v1/status") != null) { ok = true; break }
                delay(100)
            }
            if (!ok) throw IOException("the engine's API did not open (port $apiPort in use?)")
            _state.update { it.copy(engine = Engine.RUNNING, error = null) }
            thread(name = "netcap-wait", isDaemon = true) {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                onEngineExit(p, code)
            }
            true
        } catch (e: DownloadException) {
            Log.w(TAG, "engine download failed", e)
            _state.update { it.copy(engine = Engine.DOWNLOAD_FAILED, error = e.message) }
            false
        } catch (e: Exception) {
            Log.w(TAG, "engine start failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            stopProcess()
            _state.update { it.copy(engine = Engine.ERROR, error = msg + lastLogLines()) }
            false
        }
    }

    private fun lastLogLines(): String = runCatching {
        val lines = File(dataDir(appContext), "engine.log").readLines().takeLast(3).joinToString("\n")
        if (lines.isBlank()) "" else "\n$lines"
    }.getOrDefault("")

    private fun onEngineExit(p: Process, code: Int) {
        if (process !== p) return
        Log.w(TAG, "engine exited with $code")
        process = null
        runCatching { control?.close() }
        control = null
        _state.update { it.copy(engine = if (code == 0) Engine.STOPPED else Engine.ERROR,
            error = if (code == 0) null else "exit code $code" + lastLogLines(), capturing = false) }
        NetcapVpnService.stop(appContext)
    }

    private fun stopProcess() {
        runCatching { control?.close() }
        control = null
        process?.destroy()
        process = null
    }

    /** The engine in bin/netcap: unpacked from this build's assets (after an install or update), else downloaded. */
    private fun ensureBinary(context: Context): File {
        val binDir = LocalEnvironmentManager.getBinDir(context).apply { mkdirs() }
        val target = File(binDir, "netcap")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!bundled(context)) {
            if (target.canExecute() && prefs.getString(KEY_INSTALLED, null) == pinned) return target
            download(context, binDir, target)
            prefs.edit().putString(KEY_INSTALLED, pinned).apply()
            return target
        }
        val updated = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (target.exists() && target.canExecute() && prefs.getLong("binary_from", -1L) == updated &&
            prefs.getString(KEY_INSTALLED, null) == "bundled") return target
        unpack(context.assets.open("bin/$ASSET"), binDir, target)
        prefs.edit().putLong("binary_from", updated).putString(KEY_INSTALLED, "bundled").apply()
        return target
    }

    private class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val downloadHttp by lazy {
        http.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /**
     * Downloads the pinned engine (BuildConfig.NETCAP_URL, from scripts/netcap_release) and unpacks it only when its
     * SHA-256 is the one this build was made with: the engine holds the CA and sees decrypted traffic.
     */
    private fun download(context: Context, binDir: File, target: File) {
        val dev = if (BuildConfig.DEBUG) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEV_URL, null) else null
        val url = dev?.takeIf { it.isNotBlank() } ?: BuildConfig.NETCAP_URL
        val total = BuildConfig.NETCAP_SIZE
        val part = File(binDir, "netcap.xz.part")
        _state.update { it.copy(engine = Engine.DOWNLOADING, error = null, downloaded = 0, downloadTotal = total) }
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            downloadHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw DownloadException("HTTP ${r.code} from $url")
                val body = r.body ?: throw DownloadException("empty answer from $url")
                var done = 0L
                var shown = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > total + (1 shl 20)) throw DownloadException("the file is bigger than the engine this app expects")
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            if (done - shown >= 128 * 1024) {
                                shown = done
                                _state.update { it.copy(downloaded = done) }
                            }
                        }
                    }
                }
                _state.update { it.copy(downloaded = done) }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != BuildConfig.NETCAP_SHA256) {
                throw DownloadException("the downloaded file is not the engine ${BuildConfig.NETCAP_VERSION} this app was built for (SHA-256 $sha), so it was not used")
            }
            unpack(part.inputStream(), binDir, target)
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            throw DownloadException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            part.delete()
        }
    }

    private fun unpack(raw: java.io.InputStream, binDir: File, target: File) {
        val tmp = File(binDir, "netcap.tmp")
        org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(raw, 64 * 1024)).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        tmp.setExecutable(true, false)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("cannot write $target")
        }
        target.setExecutable(true, false)
    }

    // ---- control channel ----

    private fun serve(s: LocalSocket) {
        try {
            if (s.peerCredentials.uid != android.os.Process.myUid()) {
                s.close()
                throw IOException("control channel: wrong peer")
            }
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val hello = JSONObject(reader.readLine() ?: throw IOException("no hello"))
            if (hello.optString("type") != "hello" || hello.optString("token") != token) {
                s.close()
                throw IOException("control channel: bad hello")
            }
            control = s
            val ca = NetcapCert.load(appContext)
            if (ca != null) send(JSONObject().put("type", "ca").put("cert_pem", ca.first).put("key_pem", ca.second))
            else send(JSONObject().put("type", "ca_none"))
            sendSettings()
            sendDnsServers()
            connected.complete(Unit)
            while (true) {
                val line = reader.readLine() ?: break
                val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (m.optString("type") == "ca_new") {
                    NetcapCert.save(appContext, m.optString("cert_pem"), m.optString("key_pem"))
                    continue
                }
                scope.launch { runCatching { onMessage(m) }.onFailure { Log.w(TAG, "control message failed", it) } }
            }
        } catch (e: Exception) {
            if (!connected.isCompleted) connected.completeExceptionally(e)
            Log.w(TAG, "control channel ended", e)
        } finally {
            runCatching { s.close() }
            if (control === s) control = null
        }
    }

    /** Sends one message (and a file descriptor, which Android attaches to this write). */
    @Throws(IOException::class)
    fun send(m: JSONObject, fd: FileDescriptor? = null) {
        synchronized(writeLock) {
            val s = control ?: throw IOException("the engine is not connected")
            if (fd != null) s.setFileDescriptorsForSend(arrayOf(fd))
            try {
                s.outputStream.write((m.toString() + "\n").toByteArray(Charsets.UTF_8))
                s.outputStream.flush()
            } finally {
                if (fd != null) s.setFileDescriptorsForSend(null)
            }
        }
    }

    /** Sends a message with an id and waits for the engine's reply. */
    suspend fun request(m: JSONObject, timeoutMs: Long = 15_000, fd: FileDescriptor? = null): JSONObject {
        val id = nextId.incrementAndGet()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        try {
            withContext(Dispatchers.IO) { send(m.put("id", id), fd) }
            val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: throw IOException("the engine did not answer ${m.optString("type")}")
            r.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
            return r
        } finally {
            pending.remove(id)
        }
    }

    private fun reply(id: Any?, fill: JSONObject.() -> Unit = {}) {
        runCatching { send(JSONObject().put("type", "reply").put("id", id).apply(fill)) }
    }

    private suspend fun onMessage(m: JSONObject) {
        val id = m.opt("id")
        when (m.optString("type")) {
            "reply" -> pending[m.optLong("id")]?.complete(m)
            "stats" -> {
                val cap = m.optJSONObject("capture")
                _state.update {
                    // "capturing" is not taken from here: only the VPN service sets it (sendTun / tunStopped), so a
                    // stats message still on its way when the VPN stops can't turn it back on
                    it.copy(flows = m.optInt("flows"), session = m.optString("session"),
                        apps = cap?.optJSONArray("apps")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: it.apps)
                }
            }
            "start_capture" -> {
                val apps = m.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val err = startCaptureError(apps, m.optBoolean("by_agent"))
                if (err != null) reply(id) { put("error", err) } else reply(id)
            }
            "stop_capture" -> {
                NetcapVpnService.stop(appContext)
                _state.update { it.copy(capturing = false) }
                reply(id)
            }
            "owner" -> {
                val (uid, app) = owner(m.optString("proto"), m.optString("src"), m.optString("dst"))
                reply(id) { put("uid", uid); put("app", app) }
            }
            "cert_status" -> reply(id) { put("text", NetcapCert.statusText(appContext)) }
            // an agent asking: installing a system certificate needs the user's OK, like starting a capture
            "cert_install", "cert_remove" -> {
                if (!Prefs.agentsMayStart(appContext)) {
                    reply(id) { put("error", "The user has not allowed agents to change the certificate. Ask them to use the buttons in AntiGem: Settings → Network Interceptor → Certificate.") }
                    return
                }
                val r = if (m.optString("type") == "cert_install") NetcapCert.install(appContext) else NetcapCert.remove(appContext)
                reply(id) { if (r.isSuccess) put("text", r.getOrNull()) else put("error", r.exceptionOrNull()?.message ?: "failed") }
            }
        }
    }

    /** Starts the VPN for [apps]; null when it runs, else why not (sent back to the agent). */
    private suspend fun startCaptureError(apps: List<String>, byAgent: Boolean): String? {
        if (byAgent && !Prefs.agentsMayStart(appContext)) {
            return "The user has not allowed agents to start captures. Ask them to start the capture in AntiGem " +
                "(Settings → Network Interceptor) or to turn on \"Agents may start a capture\" there; you can read what they capture."
        }
        if (apps.isEmpty()) return "name the apps to capture (package names)"
        if (apps.contains(appContext.packageName)) return "AntiGem can't capture its own traffic (agents and the engine run as AntiGem); use the proxy port 127.0.0.1:$proxyPort for terminal tools"
        val missing = apps.filter { runCatching { appContext.packageManager.getPackageInfo(it, 0) }.isFailure }
        if (missing.isNotEmpty()) return "not installed: ${missing.joinToString()}"
        return NetcapVpnService.startCapture(appContext, apps).exceptionOrNull()?.message
    }

    /** Hands the VPN tunnel to the engine (called by [NetcapVpnService]). */
    suspend fun sendTun(fd: FileDescriptor, apps: List<String>, mtu: Int) {
        if (!ensureStarted(appContext)) throw IOException(state.value.error ?: "the engine did not start")
        val m = JSONObject().put("type", "tun").put("mtu", mtu).put("apps", JSONArray(apps)).put("dns", JSONArray(dnsServers()))
        request(m, 15_000, fd)
        _state.update { it.copy(capturing = true, apps = apps) }
    }

    /** The VPN stopped (by the user, another VPN, or [NetcapVpnService.stop]). */
    fun tunStopped() {
        _state.update { it.copy(capturing = false) }
        if (control != null) runCatching { send(JSONObject().put("type", "tun_stop")) }
    }

    fun sendSettings() {
        if (control == null) return
        val st = JSONObject()
            .put("body_cap_mb", 20).put("session_cap_mb", 500)
            .put("show_secrets_to_ai", Prefs.showSecrets(appContext))
            .put("agents_start_capture", Prefs.agentsMayStart(appContext))
            .put("never_decrypt", JSONArray()).put("insecure_upstream", JSONArray(Prefs.devServers(appContext)))
        runCatching { send(JSONObject().put("type", "settings").put("settings", st)) }
    }

    fun sendDnsServers() {
        if (control == null) return
        runCatching { send(JSONObject().put("type", "dns_servers").put("servers", JSONArray(dnsServers()))) }
    }

    /** The DNS servers of the phone's real network (this app is not inside its own VPN). */
    private fun dnsServers(): List<String> = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return emptyList()
        cm.getLinkProperties(net)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()
    }.getOrDefault(emptyList())

    /** Which app made a connection: Android 10+ asks the system (allowed for the active VPN app), older reads /proc. */
    private fun owner(proto: String, src: String, dst: String): Pair<Int, String> {
        val (sIp, sPort) = splitAddr(src) ?: return 0 to ""
        val (dIp, dPort) = splitAddr(dst) ?: return 0 to ""
        val uid = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val cm = appContext.getSystemService(ConnectivityManager::class.java)
                cm.getConnectionOwnerUid(if (proto == "udp") OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP,
                    InetSocketAddress(InetAddress.getByName(sIp), sPort), InetSocketAddress(InetAddress.getByName(dIp), dPort))
            }.getOrDefault(-1)
        } else procNetUid(proto, sPort)
        if (uid < 0) return 0 to ""
        val pkg = appContext.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        return uid to pkg
    }

    private fun splitAddr(s: String): Pair<String, Int>? {
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        val port = s.substring(i + 1).toIntOrNull() ?: return null
        return s.substring(0, i).trim('[', ']') to port
    }

    private fun procNetUid(proto: String, localPort: Int): Int {
        val files = if (proto == "udp") listOf("/proc/net/udp", "/proc/net/udp6") else listOf("/proc/net/tcp", "/proc/net/tcp6")
        val hexPort = String.format("%04X", localPort)
        for (f in files) {
            runCatching {
                File(f).readLines().drop(1).forEach { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size > 7 && cols[1].substringAfterLast(':').equals(hexPort, ignoreCase = true)) return cols[7].toInt()
                }
            }
        }
        return -1
    }

    // ---- MCP and API for the app ----

    /** Forwards an MCP request from the app's local server (/network) to the engine. */
    suspend fun proxyMcp(context: Context, method: String, body: String): Triple<Int, String, String> {
        if (method != "POST") return Triple(405, "application/json", """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Use POST for MCP requests"}}""")
        val started = withTimeoutOrNull(45_000) { ensureStarted(context) }
        if (started != true) {
            val id = runCatching { JSONObject(body).opt("id") }.getOrNull()
            val st = state.value
            val why = if (started == null) "the Network Interceptor engine is still downloading (${st.downloaded / 1_000_000} of " +
                "${st.downloadTotal / 1_000_000} MB, once): try again in a moment" else "the Network Interceptor engine did not start: ${st.error}"
            val err = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", -32603).put("message", why))
            return Triple(200, "application/json", err.toString())
        }
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("http://127.0.0.1:$apiPort/mcp")
                .header("X-Netcap-Token", token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                Triple(r.code, r.header("Content-Type") ?: "application/json", r.body?.string().orEmpty())
            }
        }
    }

    /** Runs an engine tool and returns its text (the settings page uses net_status). */
    suspend fun tool(context: Context, name: String, args: JSONObject = JSONObject()): String? {
        if (!ensureStarted(context)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("http://127.0.0.1:$apiPort/v1/tool/$name").header("X-Netcap-Token", token)
                    .post(args.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { it.body?.string() }
            }.getOrNull()
        }
    }

    /** The engine API's token and HTTP client, for the Network page ([NetcapApi]); never leaves the app. */
    internal val apiToken: String get() = token
    internal val apiHttp: OkHttpClient get() = http

    private fun apiGet(path: String): String? = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$apiPort$path").header("X-Netcap-Token", token).get().build()
        http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** The user's choices (Settings → Network Interceptor). */
    object Prefs {
        private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun agentsMayStart(c: Context) = p(c).getBoolean("agents_may_start", false)
        fun setAgentsMayStart(c: Context, v: Boolean) { p(c).edit().putBoolean("agents_may_start", v).apply(); sendSettings() }
        fun showSecrets(c: Context) = p(c).getBoolean("show_secrets", false)
        fun setShowSecrets(c: Context, v: Boolean) { p(c).edit().putBoolean("show_secrets", v).apply(); sendSettings() }
        fun devServers(c: Context): List<String> = p(c).getString("dev_servers", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        fun setDevServers(c: Context, text: String) { p(c).edit().putString("dev_servers", text).apply(); sendSettings() }
        fun lastApps(c: Context): List<String> = p(c).getString("last_apps", "").orEmpty().split(',').filter { it.isNotBlank() }
        fun setLastApps(c: Context, apps: List<String>) = p(c).edit().putString("last_apps", apps.joinToString(",")).apply()
    }

    /** True when [pkg] is installed (for the app picker). */
    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}
