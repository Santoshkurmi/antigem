package ai.antigem.data.debug

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import ai.antigem.BuildConfig
import ai.antigem.data.agent.AgentPreferences
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalServerStatus
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.AgyBridgeService
import ai.antigem.data.remote.SystemStatus
import ai.antigem.data.remote.core.AgyCsrfManager
import ai.antigem.data.remote.core.LocalDirectProxySelector
import ai.antigem.domain.model.AgentKind
import ai.antigem.i18n.L
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Settings → Troubleshoot: checks the bridge, the AGY hub, the agents, the network and the logs, says in plain words
 * what is wrong, and builds a report the user can share (secrets hidden). Runs only when the page asks: nothing here
 * runs in the background. It reads and tests; the only things it changes are the page's own actions (restart the
 * server, stop an agy hub the bridge did not start), and only after the user confirms them.
 */
object Troubleshoot {
    enum class Level { OK, INFO, WARN, BAD }
    enum class Action { RESTART_SERVER, STOP_AGY_PROCESSES, OPEN_SERVERS, OPEN_AGY_CLI, OPEN_AGENTS }
    enum class Kind { SETUP, NETWORK, BRIDGE, AGY, CLAUDE, OPENCODE, CODEX, INTERNET, PROCESSES }

    data class Finding(
        val level: Level,
        val title: String,
        val detail: String = "",
        val fix: String = "",
        val action: Action? = null,
        val pids: List<Int> = emptyList()
    )

    data class Row(val label: String, val value: String, val level: Level? = null)
    data class Lines(val title: String, val lines: List<String>)

    data class Section(val kind: Kind, val title: String, val rows: List<Row>, val lines: List<Lines> = emptyList()) {
        val level: Level? get() = rows.mapNotNull { it.level }.maxByOrNull { it.ordinal }
    }

    data class Log(val id: String, val title: String, val lines: List<String>)

    data class Result(
        val startedMs: Long,
        val tookMs: Long,
        val findings: List<Finding>,
        val sections: List<Section>,
        val logs: List<Log>,
        val bridgeJson: String?
    )

    // ---------------------------------------------------------------------------------------------------------------

    private const val LOG_LINES = 400
    private val ansi = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007]*\u0007""")
    private val tokenArg = Regex("""(--token|-t)(\s+|=)\S+""")
    // a secret in a URL's query or fragment: agy prints its web page as http://localhost:<port>/#t=<token>
    private val urlSecret = Regex("""([#?&](?:t|token|key|auth|access_token|code)=)[^&\s#"']+""")

    /** Hides this app's token, its framed header name, the hub's CSRF value and the proxy key in any text. */
    fun mask(text: String): String {
        var out = ansi.replace(text, "")
        out = tokenArg.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<token>" }
        out = urlSecret.replace(out) { "${it.groupValues[1]}<hidden>" }
        val token = AuthPreferences.currentSecurityToken
        if (token.length >= 6) {
            out = out.replace(AuthPreferences.buildFramedHeader(token), "<framed-header>", ignoreCase = true)
            out = out.replace(token, "<token>")
        }
        AgyCsrfManager.instance.token.takeIf { it.length >= 6 }?.let { out = out.replace(it, "<csrf>") }
        AuthPreferences.currentEdgeHeaderValue.takeIf { it.length >= 6 }?.let { out = out.replace(it, "<proxy-key>") }
        return out
    }

    private fun maskLines(lines: List<String>, keep: Int = LOG_LINES): List<String> =
        lines.takeLast(keep).map { mask(it).take(2000) }

    /** The Server Runner terminal's text (the server log the chat's status dot opens), last [maxLines] lines. Main thread. */
    fun serverRunnerLog(maxLines: Int = LOG_LINES): List<String> {
        val text = runCatching {
            LocalServerManager.serverSession.value?.terminalSession?.emulator?.screen?.transcriptText
        }.getOrNull() ?: return emptyList()
        return maskLines(text.trimEnd().lines(), maxLines)
    }

    // ---- run ------------------------------------------------------------------------------------------------------

    private class Facts {
        var localPhone = false
        var chroot = false
        var remote = false
        var pc = false
        var bridgeUrl = ""
        var hubUrl = ""
        var bridgeLoopback = true
        var envInstalled = true
        var bridgeBinary: File? = null
        var bridgeBinaryExists = true
        var bridgeProcs = listOf<ProcInfo>()
        var localProcs = listOf<ProcInfo>()
        var serverState = ""
        var tcpOk: Boolean? = null
        var tcpError = ""
        var healthDirect: Probe? = null
        var healthProxy: Probe? = null
        var systemProxy: Proxy? = null
        var statusCode = 0
        var statusJson: JSONObject? = null
        var wsError: String? = null
        var appConnected = true
        var report: JSONObject? = null
        var reportUnsupported = false
        var agentsOn = setOf<AgentKind>()
        val agents = mutableMapOf<AgentKind, JSONObject?>()
        var dataSaver = false
        var batteryRestricted = false
    }

    class Probe(val ok: Boolean, val code: Int, val ms: Long, val error: String)
    class ProcInfo(val pid: Int, val ppid: Int, val args: String, val argv: List<String>)

    suspend fun run(context: Context, onStep: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val started = System.currentTimeMillis()
        val f = Facts()
        val sections = mutableListOf<Section>()
        val logs = mutableListOf<Log>()
        val T = L.settings.troubleshoot

        // opened right as the app starts: let the server's auto-start finish before judging it
        waitForServerStart { onStep(T.waitingForStart) }

        onStep(T.stepPhone)
        sections += setupSection(app, f)

        onStep(T.stepNetwork)
        sections += networkSection(app, f)

        if (AgentKind.AGY in runCatching { AgentPreferences.enabledSync(app) }.getOrDefault(emptySet()) &&
            AgyBridgeService.instance.systemConnectionState.value.isBridgeOnline
        ) waitForHubStart { onStep(T.waitingForHub) }

        onStep(T.stepBridge)
        sections += bridgeSection(app, f)

        if (f.healthDirect?.ok == true || f.tcpOk == true) {
            onStep(T.stepReport)
            f.report = fetchReport(f)
        }
        f.report?.let { rep ->
            val i = sections.indexOfFirst { it.kind == Kind.BRIDGE }
            if (i >= 0) {
                val (rows, lines) = bridgeReportDetails(rep)
                sections[i] = sections[i].copy(rows = sections[i].rows + rows, lines = sections[i].lines + lines)
            }
            if (AgentKind.AGY in f.agentsOn) sections += agySection(f, rep)
            sections += internetSection(rep)
        }

        onStep(T.stepAgents)
        sections += agentSections(f)

        onStep(T.stepLogs)
        sections += processSection(f)
        // the terminal writes its text on the main thread: read it there
        val runner = withContext(Dispatchers.Main) { serverRunnerLog() }
        logs += collectLogs(app, f, runner)

        val findings = findings(f).sortedByDescending { it.level.ordinal }
        Result(started, System.currentTimeMillis() - started, findings, sections, logs, f.report?.toString(1))
    }

    /**
     * The server was not started yet, is starting, or started moments ago and the app is not connected yet: waits for
     * the app's connection to the bridge (15 s at most; 6 s when nothing has started it yet, as it may never start).
     */
    private suspend fun waitForServerStart(onWait: () -> Unit) {
        val bridge = AgyBridgeService.instance
        if (bridge.systemConnectionState.value.isBridgeOnline) return
        val st = LocalServerManager.status.value
        val recent = st is LocalServerStatus.Running && System.currentTimeMillis() - st.startTimeMs < 15_000
        val limit = when {
            st is LocalServerStatus.Starting || recent -> 15_000L
            st is LocalServerStatus.Idle && !AuthPreferences.currentRemoteMode -> 6_000L
            else -> return
        }
        onWait()
        withTimeoutOrNull(limit) {
            while (!bridge.systemConnectionState.value.isBridgeOnline) {
                val now = LocalServerManager.status.value
                if (now is LocalServerStatus.Stopped || now is LocalServerStatus.Error) break
                delay(400)
            }
        }
    }

    /** Antigravity's hub is still starting (agy takes a few seconds to open its port): waits up to 12 s for it. */
    private suspend fun waitForHubStart(onWait: () -> Unit) {
        val hub = AgyBridgeService.instance.hubStatus
        if (hub.value.status != "starting" && hub.value.status != "idle") return
        onWait()
        withTimeoutOrNull(12_000) {
            while (hub.value.status == "starting" || hub.value.status == "idle") delay(400)
        }
    }

    // ---- this phone & app -----------------------------------------------------------------------------------------

    private fun setupSection(context: Context, f: Facts): Section {
        val T = L.settings.troubleshoot
        f.pc = AuthPreferences.pcOverride != null
        f.remote = AuthPreferences.currentRemoteMode
        f.chroot = !f.remote && runCatching { ChrootManager.isActiveNow() }.getOrDefault(false)
        f.localPhone = !f.remote && !f.chroot
        f.bridgeUrl = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        f.hubUrl = AuthPreferences.currentHubUrl.trimEnd('/')
        f.bridgeLoopback = AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(f.bridgeUrl))
        f.agentsOn = runCatching { AgentPreferences.enabledSync(context) }.getOrDefault(emptySet())

        val mode = when {
            AuthPreferences.pcOverride != null -> T.modePc(AuthPreferences.pcOverride?.pcName.orEmpty())
            f.remote -> if (AuthPreferences.currentRemoteTunnel) T.modeServerTunnel else T.modeServer
            f.chroot -> T.modeChroot
            else -> T.modePhone
        }
        val rows = mutableListOf(
            Row(T.appVersion, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                (if (BuildConfig.DEBUG) " debug" else "") + BuildConfig.APK_SUFFIX),
            Row(T.packageName, context.packageName),
            Row(T.android, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Row(T.device, "${Build.MANUFACTURER} ${Build.MODEL}"),
            Row(T.cpu, Build.SUPPORTED_ABIS.joinToString()),
            Row(T.kernel, System.getProperty("os.version").orEmpty()),
            Row(T.whereAgentsRun, mode),
            Row(T.bridgeAddress, f.bridgeUrl, if (f.localPhone && !f.bridgeLoopback) Level.WARN else null),
            Row(T.hubAddress, f.hubUrl)
        )
        val token = AuthPreferences.currentSecurityToken
        rows += Row(T.securityToken, if (token.length == AuthPreferences.TOKEN_LENGTH) T.tokenSet else T.tokenMissing,
            if (token.length == AuthPreferences.TOKEN_LENGTH) Level.OK else Level.BAD)
        rows += Row(T.agentsOn, f.agentsOn.joinToString { agentName(it) }.ifBlank { T.none })
        if (f.localPhone) {
            f.envInstalled = runCatching { LocalEnvironmentManager.isInstalled(context) }.getOrDefault(true)
            rows += Row(T.linuxEnvironment, if (f.envInstalled) T.installed else T.notInstalled, if (f.envInstalled) Level.OK else Level.BAD)
        }
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            f.batteryRestricted = pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)
            rows += Row(T.batteryOptimization, if (f.batteryRestricted) T.batteryOptimized else T.batteryUnrestricted,
                if (f.batteryRestricted) Level.INFO else Level.OK)
        }
        return Section(Kind.SETUP, T.sectionPhone, rows)
    }

    fun agentName(kind: AgentKind): String = when (kind) {
        AgentKind.AGY -> L.common.antigravity
        AgentKind.CLAUDE -> L.common.claudeCode
        AgentKind.OPENCODE -> "OpenCode"
        AgentKind.CODEX -> L.common.codex
    }

    // ---- network --------------------------------------------------------------------------------------------------

    private fun networkSection(context: Context, f: Facts): Section {
        val T = L.settings.troubleshoot
        val rows = mutableListOf<Row>()
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val kinds = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add(T.wifi)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add(T.mobileData)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add(T.ethernet)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            }
            rows += Row(T.activeNetwork, kinds.joinToString(" + ").ifBlank { T.noNetwork }, if (kinds.isEmpty()) Level.WARN else null)
            if (caps != null) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                rows += Row(T.internet, if (validated) T.internetWorks else T.internetNotConfirmed, if (validated) Level.OK else Level.WARN)
                rows += Row(T.metered, if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) T.no else T.yes)
            }
            val netProxy = lp?.httpProxy
            rows += Row(T.networkProxy, netProxy?.let { p ->
                if (p.pacFileUrl != null && p.pacFileUrl.toString().isNotBlank() && p.pacFileUrl.toString() != "null") "PAC ${p.pacFileUrl}"
                else "${p.host}:${p.port}"
            } ?: T.none)
            cm.defaultProxy?.let { rows += Row(T.globalProxy, "${it.host}:${it.port}") }
            if (Build.VERSION.SDK_INT >= 28 && lp != null) {
                rows += Row(T.privateDns, if (lp.isPrivateDnsActive) (lp.privateDnsServerName ?: T.automatic) else T.off)
            }
            val saver = cm.restrictBackgroundStatus
            f.dataSaver = saver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            rows += Row(T.dataSaver, when (saver) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> T.dataSaverLimits
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> T.dataSaverAllowed
                else -> T.off
            }, if (f.dataSaver) Level.INFO else null)
        }
        System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }?.let {
            rows += Row(T.javaProxy, "$it:${System.getProperty("http.proxyPort").orEmpty()}" +
                (System.getProperty("http.nonProxyHosts")?.takeIf { e -> e.isNotBlank() }?.let { e -> "  (${T.except} $e)" } ?: ""))
        }
        f.systemProxy = LocalDirectProxySelector.systemProxyFor(f.bridgeUrl)
        rows += Row(T.proxyForBridge, f.systemProxy?.let { "${proxyText(it)}  →  ${T.skippedNow}" } ?: T.none,
            if (f.systemProxy != null) Level.INFO else null)
        return Section(Kind.NETWORK, T.sectionNetwork, rows)
    }

    // ---- the bridge -----------------------------------------------------------------------------------------------

    private suspend fun bridgeSection(context: Context, f: Facts): Section {
        val T = L.settings.troubleshoot
        val rows = mutableListOf<Row>()
        val lines = mutableListOf<Lines>()

        if (f.localPhone) {
            val prefs = AuthPreferences(context)
            val configured = prefs.getAgyBridgeBinaryPathSync().trim().ifBlank { AuthPreferences.DEFAULT_BRIDGE_BINARY_PATH }
            val file = expandHome(context, configured)
            f.bridgeBinary = file
            f.bridgeBinaryExists = file.isFile
            rows += Row(T.bridgeProgram, if (file.isFile) "${file.path}  (${file.length() shr 20} MB, ${time(file.lastModified())})"
                else T.missingPath(file.path), if (file.isFile) Level.OK else Level.BAD)
            runCatching { LocalServerManager.resolveServerCommand(context) }.getOrNull()?.let {
                lines += Lines(T.startCommand, listOf(mask(it)))
            }
        }

        f.serverState = when (val s = LocalServerManager.status.value) {
            is LocalServerStatus.Running -> T.stateRunning(time(s.startTimeMs))
            is LocalServerStatus.Starting -> T.stateStarting
            is LocalServerStatus.Stopping -> T.stateStopping
            is LocalServerStatus.Stopped -> T.stateStopped(s.exitCode?.toString() ?: "-")
            is LocalServerStatus.Error -> T.stateError(s.message)
            else -> T.stateIdle
        }
        rows += Row(T.serverManager, f.serverState, when (LocalServerManager.status.value) {
            is LocalServerStatus.Error -> Level.BAD
            is LocalServerStatus.Stopped -> if (f.localPhone) Level.WARN else null
            else -> null
        })
        if (f.localPhone) {
            val session = LocalServerManager.serverSession.value
            rows += Row(T.serverTerminal, when {
                session == null -> T.none
                session.isExited.value -> T.sessionEnded(runCatching { session.terminalSession.exitStatus }.getOrDefault(-1).toString())
                else -> T.open
            })
            f.localProcs = readLocalProcesses()
            f.bridgeProcs = f.localProcs.filter { p -> p.argv.firstOrNull()?.substringAfterLast('/')?.startsWith("agy_ide_bridge") == true }
            rows += Row(T.bridgeProcess, if (f.bridgeProcs.isEmpty()) T.notRunning
                else f.bridgeProcs.joinToString { "pid ${it.pid}" }, if (f.bridgeProcs.isEmpty()) Level.BAD else if (f.bridgeProcs.size > 1) Level.WARN else Level.OK)
        }

        // reachability, as the app connects (direct for this phone and the LAN), and through the phone's proxy
        val uri = runCatching { URI(f.bridgeUrl) }.getOrNull()
        val host = uri?.host.orEmpty().removePrefix("[").removeSuffix("]")
        val port = uri?.port?.takeIf { it > 0 } ?: if (f.bridgeUrl.startsWith("https")) 443 else 80
        val t0 = System.currentTimeMillis()
        val tcpError = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 2500) } }.exceptionOrNull()
        f.tcpOk = tcpError == null
        f.tcpError = tcpError?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        rows += Row(T.portConnect(port.toString()), if (tcpError == null) T.opensIn(ms(System.currentTimeMillis() - t0)) else T.failed(f.tcpError),
            if (tcpError == null) Level.OK else Level.BAD)

        val client = AgyBridgeService.instance.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        f.healthDirect = probe(client, "${f.bridgeUrl}/api/health")
        rows += Row(T.healthCheck, probeText(f.healthDirect!!), if (f.healthDirect!!.ok) Level.OK else Level.BAD)
        f.systemProxy?.let { proxy ->
            val viaProxy = OkHttpClient.Builder().proxy(proxy)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
            f.healthProxy = probe(viaProxy, "${f.bridgeUrl}/api/health")
            rows += Row(T.throughProxy, probeText(f.healthProxy!!), if (f.healthProxy!!.ok) Level.OK else Level.WARN)
        }

        if (f.healthDirect?.ok == true) {
            // latency: five quick checks in a row
            val times = (1..5).mapNotNull { probe(client, "${f.bridgeUrl}/api/health").takeIf { it.ok }?.ms }
            if (times.isNotEmpty()) rows += Row(T.ping, "min ${times.min()} · avg ${times.average().toLong()} · max ${times.max()} ms")

            val (code, body) = get(client, "${f.bridgeUrl}/api/status")
            f.statusCode = code
            f.statusJson = runCatching { JSONObject(body) }.getOrNull()
            rows += Row(T.tokenCheck, when (code) {
                200 -> T.tokenAccepted
                401 -> T.tokenRefused
                else -> T.httpCode(code.toString())
            }, if (code == 200) Level.OK else Level.BAD)
            f.statusJson?.let { st ->
                rows += Row(T.bridgeVersion, st.str("version").ifBlank { "?" },
                    if (st.str("version").isNotBlank() && st.str("version") != BuildConfig.VERSION_NAME) Level.WARN else null)
                rows += Row(T.bridgeUptime, duration(st.optDouble("uptime", 0.0).toLong()))
            }
            f.wsError = wsCheck()
            rows += Row(T.liveConnection, f.wsError?.let { T.failed(it) } ?: T.works, if (f.wsError == null) Level.OK else Level.WARN)
        }

        // the app's own live connection to the bridge (what every agent's status dot needs first)
        val state = AgyBridgeService.instance.systemConnectionState.value
        f.appConnected = state.isBridgeOnline
        rows += Row(T.appConnected, if (f.appConnected) T.yes else T.no, if (f.appConnected) Level.OK else Level.WARN)
        if (AgentKind.AGY in f.agentsOn) {
            // Antigravity's own state (the bridge connection plus its hub, CSRF value and sign-in)
            rows += Row(T.agyStatusInApp, state.label, when (state.status) {
                SystemStatus.READY -> Level.OK
                SystemStatus.ERROR -> Level.WARN
                else -> null
            })
            rows += Row(T.hubCsrfInApp, if (AgyCsrfManager.instance.token.isNotBlank()) T.yes else T.no)
        }
        return Section(Kind.BRIDGE, T.sectionBridge, rows, lines)
    }

    private fun probe(client: OkHttpClient, url: String): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                Probe(r.isSuccessful, r.code, System.currentTimeMillis() - t0, if (r.isSuccessful) "" else "HTTP ${r.code}")
            }
        } catch (e: Exception) {
            Probe(false, 0, System.currentTimeMillis() - t0, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun probeText(p: Probe): String =
        if (p.ok) L.settings.troubleshoot.answersIn(ms(p.ms)) else L.settings.troubleshoot.failed(p.error)

    private fun get(client: OkHttpClient, url: String): Pair<Int, String> = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { r: Response -> r.code to (r.body?.string() ?: "") }
    } catch (e: Exception) {
        0 to (e.message ?: e.javaClass.simpleName)
    }

    /**
     * Opens the bridge's WebSocket with the client the app's live connection uses ([AgyBridgeService.wsClient], its
     * own dispatcher) and closes it again; null = it works.
     */
    private suspend fun wsCheck(): String? {
        val done = CompletableDeferred<String>() // "" = it opened, else why it failed
        // (not null for success: withTimeoutOrNull returns null on a timeout too)
        val ws = AgyBridgeService.instance.wsClient.newWebSocket(
            Request.Builder().url(AgyBridgeService.wsEndpoint(AuthPreferences.currentBridgeWsUrl)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { done.complete("") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.complete(response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
                }
            })
        val result = withTimeoutOrNull(5_000) { done.await() } ?: L.settings.troubleshoot.timedOut
        runCatching { ws.close(1000, null) }
        runCatching { ws.cancel() }
        return result.ifEmpty { null }
    }

    private fun fetchReport(f: Facts): JSONObject? {
        val client = AgyBridgeService.instance.client.newBuilder()
            .readTimeout(80, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
        val agy = if (AgentKind.AGY in f.agentsOn) 1 else 0
        val (code, body) = get(client, "${f.bridgeUrl}/api/debug/report?agy=$agy&net=1&log=$LOG_LINES")
        val json = if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null
        if (json == null || !json.has("report_version")) {
            f.reportUnsupported = code != 401 && code != 0
            return null
        }
        return json
    }

    // ---- Antigravity ----------------------------------------------------------------------------------------------

    /** The bridge's own view from its report: process, address, agents off, proxy settings, storage and memory. */
    private fun bridgeReportDetails(rep: JSONObject): Pair<List<Row>, List<Lines>> {
        val T = L.settings.troubleshoot
        val rows = mutableListOf<Row>()
        val lines = mutableListOf<Lines>()
        val bridge = rep.optJSONObject("bridge")
        val sys = rep.optJSONObject("system")
        bridge?.let { b ->
            rows += Row(T.bridgeOwnView, "pid ${b.optInt("pid")} (${T.parent} ${b.optInt("ppid")}), ${b.str("os")}/${b.str("arch")}")
            rows += Row(T.listensOn, "${b.str("bind")}:${b.str("port")}")
            b.optJSONArray("agents_off")?.strings()?.takeIf { it.isNotEmpty() }?.let { rows += Row(T.agentsOff, it.joinToString()) }
            b.optJSONObject("env")?.let { env ->
                env.keys().asSequence().filter { it.contains("proxy", true) }.forEach { k -> rows += Row(k, env.str(k), Level.INFO) }
            }
            b.optJSONArray("args")?.strings()?.let { lines += Lines(T.bridgeCommand, listOf(mask(it.joinToString(" ")))) }
        }
        sys?.let { s ->
            if (s.has("home_disk_free_mb")) rows += Row(T.freeStorage, "${s.optLong("home_disk_free_mb")} MB / ${s.optLong("home_disk_total_mb")} MB",
                if (s.optLong("home_disk_free_mb") in 0..499) Level.WARN else null)
            if (s.has("memavailable_mb")) rows += Row(T.freeMemory, "${s.optLong("memavailable_mb")} MB / ${s.optLong("memtotal_mb")} MB",
                if (s.optLong("memavailable_mb") in 0..299) Level.INFO else null)
        }
        return rows to lines
    }

    private fun agySection(f: Facts, rep: JSONObject): Section {
        val T = L.settings.troubleshoot
        val rows = mutableListOf<Row>()
        val lines = mutableListOf<Lines>()
        val hub = rep.optJSONObject("hub")
        val agy = rep.optJSONObject("agy")
        rep.optJSONObject("ports")?.optJSONObject("hub")?.let { p ->
            rows += Row(T.hubPort(p.str("port")), if (p.optBoolean("open")) T.open else T.closed,
                if (p.optBoolean("open")) Level.OK else if (hub?.optBoolean("disabled") == true) null else Level.BAD)
        }
        hub?.let { h ->
            if (h.optBoolean("disabled")) {
                rows += Row(T.hubStatus, T.turnedOff)
            } else {
                val status = h.str("status")
                rows += Row(T.hubStatus, status + (h.str("last_error").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    when (status) { "online" -> Level.OK; "error" -> Level.BAD; "starting" -> null; else -> Level.WARN })
                rows += Row(T.hubStartedBy, when {
                    h.optInt("own_pid") > 0 -> T.thisBridge(h.optInt("own_pid").toString())
                    h.optBoolean("external") -> T.foundRunning
                    else -> "-"
                }, if (h.optBoolean("external")) Level.WARN else null)
                rows += Row(T.hubCsrfInBridge, if (h.optBoolean("csrf_present")) T.yes else T.no,
                    if (h.optBoolean("csrf_present") || status == "starting") null else Level.WARN)
                if (h.optLong("last_exit_at_ms") > 0) rows += Row(T.lastExit, "${time(h.optLong("last_exit_at_ms"))}: ${h.str("last_exit_error")}")
                h.optJSONArray("crash_lines")?.strings()?.takeIf { it.isNotEmpty() }?.let { lines += Lines(T.hubCrash, maskLines(it)) }
                h.optJSONArray("recent_output")?.strings()?.takeIf { it.isNotEmpty() }?.let { lines += Lines(T.hubOutput, maskLines(it)) }
            }
        }
        agy?.let { a ->
            a.optJSONObject("file")?.let { file ->
                val target = file.str("target")
                rows += Row(T.agyFile, if (file.optBoolean("exists")) "$target  (${file.optLong("size") shr 20} MB, ${time(file.optLong("modified_ms"))})"
                    else T.missingPath(target.ifBlank { file.str("configured") }), if (file.optBoolean("exists")) null else Level.BAD)
                file.str("symlink_to").takeIf { it.isNotBlank() }?.let { rows += Row(T.linksTo, it) }
                file.optJSONObject("headers")?.let { hd -> rows += headerRow(T.agyFileHeader, hd) }
                file.optJSONArray("in_use_by")?.ints()?.takeIf { it.isNotEmpty() }?.let { rows += Row(T.agyFileInUse, it.joinToString { "pid $it" }) }
                file.optJSONArray("siblings")?.strings()?.takeIf { it.size > 1 }?.let { lines += Lines(T.agyFilesInFolder, it) }
            }
            a.optJSONArray("hub_processes")?.objects()?.forEach { p ->
                val label = T.hubProcess(p.optInt("pid").toString(), p.str("port"))
                val facts = buildList {
                    add(if (p.optBoolean("own_child")) T.startedByBridge else T.notStartedByBridge)
                    if (p.optBoolean("same_as_file")) add(T.runsAgyFile)
                    if (p.optBoolean("replaced_since_start")) add(T.fileReplacedSince)
                    add(T.age(duration(p.optLong("age_s"))))
                }
                rows += Row(label, facts.joinToString(" · "), if (p.optBoolean("on_our_port") && !p.optBoolean("own_child")) Level.WARN else null)
                p.optJSONObject("headers")?.let { hd -> rows += headerRow(T.runningHubHeader, hd) }
            }
            a.optJSONArray("other_agy_processes")?.objects()?.takeIf { it.isNotEmpty() }?.let { list ->
                lines += Lines(T.otherAgyProcesses, list.map { "pid ${it.optInt("pid")}: ${mask(it.str("args"))}" })
            }
            a.optJSONArray("other_bridges")?.objects()?.takeIf { it.isNotEmpty() }?.let { list ->
                rows += Row(T.otherBridges, list.joinToString { "pid ${it.optInt("pid")}" }, Level.WARN)
                lines += Lines(T.otherBridges, list.map { "pid ${it.optInt("pid")}: ${mask(it.str("args"))}" })
            }
            a.optJSONObject("discovery")?.let { d ->
                if (d.has("pid")) rows += Row(T.agyDiscovery, "pid ${d.str("pid")}" + (if (d.optBoolean("pid_alive")) "" else " (${T.notRunning})") +
                    (d.str("lsVersion").takeIf { it.isNotBlank() }?.let { ", LS $it" } ?: ""))
            }
            a.optJSONObject("probe")?.let { pr -> rows += Row(T.hubTest, verdictText(pr.str("verdict")), verdictLevel(pr.str("verdict"))) }
        }
        return Section(Kind.AGY, T.sectionAgy, rows, lines)
    }

    private fun headerRow(label: String, hd: JSONObject): Row {
        val T = L.settings.troubleshoot
        if (hd.str("error").isNotBlank()) return Row(label, T.failed(hd.str("error")), Level.WARN)
        val ours = hd.optInt("ours")
        val def = hd.optInt("default")
        val other = hd.optJSONArray("other")?.objects().orEmpty()
        val text = buildList {
            if (ours > 0) add(T.headerOurs(ours.toString()))
            if (def > 0) add(T.headerStandard(def.toString()))
            other.forEach { add(T.headerOther(it.str("token"), it.optInt("count").toString())) }
            if (isEmpty()) add(T.headerNone)
        }.joinToString(" · ") + "  (${hd.optLong("ms")} ms)"
        val level = when {
            ours > 0 -> Level.OK
            def > 0 || other.isNotEmpty() -> Level.INFO
            else -> Level.BAD
        }
        return Row(label, text, level)
    }

    private fun verdictText(v: String): String {
        val T = L.settings.troubleshoot
        return when (v) {
            "ok" -> T.verdictOk
            "ok_standard" -> T.verdictOkStandard
            "unpatched" -> T.verdictUnpatched
            "other_token" -> T.verdictOtherToken
            "stale_csrf" -> T.verdictStaleCsrf
            "unreachable" -> T.verdictUnreachable
            "hub_off" -> T.turnedOff
            else -> v
        }
    }

    private fun verdictLevel(v: String): Level? = when (v) {
        "ok", "ok_standard" -> Level.OK
        "unpatched", "other_token", "unreachable" -> Level.BAD
        "stale_csrf" -> Level.WARN
        else -> null
    }

    private fun internetSection(rep: JSONObject): Section {
        val T = L.settings.troubleshoot
        val rows = rep.optJSONArray("net")?.objects().orEmpty().map { n ->
            val ok = n.optBoolean("ok")
            Row(n.str("name"), if (ok) T.reachable(n.optInt("status").toString(), n.optLong("dns_ms").toString(), n.optLong("http_ms").toString())
                else T.failed(n.str("error")), if (ok) Level.OK else Level.WARN)
        }
        return Section(Kind.INTERNET, T.sectionInternet, rows)
    }

    // ---- agents ---------------------------------------------------------------------------------------------------

    private suspend fun agentSections(f: Facts): List<Section> = coroutineScope {
        val T = L.settings.troubleshoot
        val reachable = f.healthDirect?.ok == true && f.statusCode == 200
        val client = AgyBridgeService.instance.client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
        val kinds = listOf(AgentKind.AGY to "/api/agy/cli", AgentKind.CLAUDE to "/api/claude/status",
            AgentKind.OPENCODE to "/api/opencode/status", AgentKind.CODEX to "/api/codex/status")
        kinds.filter { it.first in f.agentsOn }.map { (kind, path) ->
            async {
                val on = kind in f.agentsOn
                val json = if (on && reachable) get(client, f.bridgeUrl + path).let { (code, body) ->
                    if (code == 200) runCatching { JSONObject(body) }.getOrNull() else JSONObject().put("error", if (code == 0) body else "HTTP $code")
                } else null
                kind to json
            }
        }.awaitAll().onEach { (kind, json) -> f.agents[kind] = json }.map { (kind, json) ->
            val rows = mutableListOf<Row>()
            if (kind !in f.agentsOn) {
                rows += Row(T.state, T.turnedOff)
            } else if (json == null) {
                rows += Row(T.state, T.bridgeNotReachable, Level.WARN)
            } else {
                val installed = json.optBoolean("installed", json.has("version"))
                rows += Row(T.installed, if (installed) T.yes else T.no, if (installed) Level.OK else Level.WARN)
                json.str("version").takeIf { it.isNotBlank() }?.let { rows += Row(T.version, it) }
                (json.str("bin_path").ifBlank { json.str("binary") }.ifBlank { json.str("path") }).takeIf { it.isNotBlank() }?.let { rows += Row(T.program, it) }
                if (json.has("running")) rows += Row(T.serverRunning, when {
                    json.optBoolean("starting") -> T.stateStarting
                    json.optBoolean("running") -> T.yes + (json.optLong("uptime_seconds").takeIf { it > 0 }?.let { " (${duration(it)})" } ?: "")
                    else -> T.no
                })
                json.optJSONObject("auth")?.let { auth ->
                    val loggedIn = auth.optBoolean("loggedIn", auth.optBoolean("logged_in", false))
                    rows += Row(T.signedIn, if (loggedIn) T.yes else T.no, if (loggedIn) Level.OK else Level.WARN)
                }
                json.str("error").takeIf { it.isNotBlank() }?.let { rows += Row(T.error, mask(it), Level.WARN) }
            }
            val logLines = json?.optJSONArray("logs")?.strings().orEmpty()
            val kindOf = when (kind) {
                AgentKind.AGY -> Kind.AGY
                AgentKind.CLAUDE -> Kind.CLAUDE
                AgentKind.OPENCODE -> Kind.OPENCODE
                AgentKind.CODEX -> Kind.CODEX
            }
            Section(kindOf, agentName(kind), rows, if (logLines.isNotEmpty()) listOf(Lines(T.recentOutput, maskLines(logLines, 60))) else emptyList())
        }
    }

    // ---- processes & logs -----------------------------------------------------------------------------------------

    private fun processSection(f: Facts): Section {
        val T = L.settings.troubleshoot
        val fromBridge = f.report?.optJSONArray("processes")?.objects()
        val lines = fromBridge?.map { p ->
            "pid ${p.optInt("pid")}  ppid ${p.optInt("ppid")}  ${duration(p.optLong("age_s"))}  ${p.optLong("rss_mb")} MB  ${mask(p.str("args"))}"
        } ?: f.localProcs.map { "pid ${it.pid}  ppid ${it.ppid}  ${mask(it.args)}" }
        val rows = listOf(Row(T.listedBy, if (fromBridge != null) T.listedByBridge else T.listedByApp))
        return Section(Kind.PROCESSES, T.sectionProcesses, rows, if (lines.isNotEmpty()) listOf(Lines(T.sectionProcesses, lines.map { it.take(400) })) else emptyList())
    }

    private fun collectLogs(context: Context, f: Facts, runner: List<String>): List<Log> {
        val T = L.settings.troubleshoot
        val out = mutableListOf<Log>()
        if (runner.isNotEmpty() || f.localPhone || f.chroot) out += Log("runner", T.logServerRunner, runner)
        val log = f.report?.optJSONObject("log")
        if (log != null) {
            log.optJSONArray("lines")?.strings()?.takeIf { it.isNotEmpty() }?.let { out += Log("bridge", T.logBridgeFile(log.str("file").substringAfterLast('/')), maskLines(it)) }
            log.optJSONArray("previous_lines")?.strings()?.takeIf { it.isNotEmpty() }?.let { out += Log("previous", T.logPreviousRun(log.str("previous_file")), maskLines(it)) }
            if (AgentKind.AGY in f.agentsOn) {
                log.optJSONArray("hub_lines")?.strings()?.takeIf { it.isNotEmpty() }?.let { out += Log("hub", T.logHub(log.str("hub_file")), maskLines(it)) }
            }
        } else if (f.localPhone) {
            // the bridge does not answer: read its newest log file directly
            val dir = File(LocalEnvironmentManager.getHomeDir(context), "logs")
            dir.listFiles { file -> file.name.startsWith("agy-ide-bridge_") && file.name.endsWith(".log") }
                ?.maxByOrNull { it.name }?.let { newest ->
                    val lines = runCatching { tail(newest, LOG_LINES) }.getOrDefault(emptyList())
                    if (lines.isNotEmpty()) out += Log("bridge", T.logBridgeFile(newest.name), maskLines(lines))
                }
        }
        appLogcat().takeIf { it.isNotEmpty() }?.let { out += Log("app", T.logApp, it) }
        return out
    }

    /** This app's own log lines (Android lets an app read its own), the last 300. */
    private fun appLogcat(): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "300", "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() } // logcat -d ends after the dump
        runCatching { p.waitFor() }
        maskLines(text.trimEnd().lines(), 300)
    }.getOrDefault(emptyList())

    private fun tail(file: File, n: Int): List<String> {
        val max = 1L shl 20
        java.io.RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - max).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes)
            if (start > 0) text = text.substringAfter('\n')
            return text.trimEnd().lines().takeLast(n)
        }
    }

    /** The app's own processes (Android lists only those): the bridge, agy and the agents in this phone's Linux. */
    private fun readLocalProcesses(): List<ProcInfo> {
        val dirs = File("/proc").listFiles { file -> file.name.all { it.isDigit() } } ?: return emptyList()
        val myPid = android.os.Process.myPid()
        return dirs.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val raw = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@mapNotNull null
            if (raw.isEmpty()) return@mapNotNull null
            val argv = String(raw).trimEnd('\u0000').split('\u0000')
            val ppid = runCatching {
                val stat = File(dir, "stat").readText()
                stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
            }.getOrNull() ?: 0
            ProcInfo(pid, ppid, argv.joinToString(" "), argv)
        }.sortedBy { it.pid }
    }

    // ---- findings -------------------------------------------------------------------------------------------------

    private fun findings(f: Facts): List<Finding> {
        val T = L.settings.troubleshoot
        val out = mutableListOf<Finding>()
        val rep = f.report
        val hub = rep?.optJSONObject("hub")
        val agy = rep?.optJSONObject("agy")

        if (f.localPhone && !f.bridgeLoopback) {
            out += Finding(Level.WARN, T.fBridgeUrlTitle, T.fBridgeUrlDetail(f.bridgeUrl), T.fBridgeUrlFix, Action.OPEN_SERVERS)
        }
        if (f.localPhone && !f.envInstalled) out += Finding(Level.BAD, T.fEnvTitle, T.fEnvDetail, T.fEnvFix)
        if (f.localPhone && !f.bridgeBinaryExists) {
            out += Finding(Level.BAD, T.fBinaryTitle, T.missingPath(f.bridgeBinary?.path.orEmpty()), T.fBinaryFix)
        }

        val reachable = f.healthDirect?.ok == true
        if (!reachable) {
            when {
                f.remote -> out += Finding(Level.BAD, T.fRemoteTitle, "${f.bridgeUrl}: ${f.healthDirect?.error ?: f.tcpError}", T.fRemoteFix, Action.OPEN_SERVERS)
                f.localPhone && f.bridgeProcs.isEmpty() ->
                    out += Finding(Level.BAD, T.fNotRunningTitle, T.fNotRunningDetail(f.serverState), T.fNotRunningFix, Action.RESTART_SERVER)
                else -> out += Finding(Level.BAD, T.fPortTitle, T.fPortDetail(f.bridgeUrl, f.healthDirect?.error ?: f.tcpError),
                    T.fPortFix, Action.RESTART_SERVER)
            }
        }
        f.systemProxy?.let { proxy ->
            val proxyBroke = f.healthProxy?.ok == false
            // this version already connects past it: worth knowing (it explains an offline bridge in older versions), not a problem
            out += if (proxyBroke && reachable) Finding(Level.INFO, T.fProxyCauseTitle, T.fProxyCauseDetail(proxyText(proxy), f.healthProxy?.error.orEmpty()), T.fProxyCauseFix)
            else Finding(Level.INFO, T.fProxyTitle(proxyText(proxy)), T.fProxyDetail)
        }
        if (reachable && f.statusCode == 401) out += Finding(Level.BAD, T.fTokenTitle, T.fTokenDetail, T.fTokenFix, Action.RESTART_SERVER)
        val bridgeVersion = rep?.optJSONObject("bridge")?.str("version") ?: f.statusJson?.str("version").orEmpty()
        if (bridgeVersion.isNotBlank() && bridgeVersion != BuildConfig.VERSION_NAME && !f.remote) {
            out += Finding(Level.WARN, T.fOldBridgeTitle, T.fOldBridgeDetail(bridgeVersion, BuildConfig.VERSION_NAME), T.fOldBridgeFix, Action.RESTART_SERVER)
        }
        if (reachable && f.statusCode == 200 && f.wsError != null) out += Finding(Level.WARN, T.fWsTitle, f.wsError.orEmpty(), T.fRestartFix, Action.RESTART_SERVER)
        if (reachable && f.statusCode == 200 && !f.appConnected) out += Finding(Level.WARN, T.fAppOfflineTitle, T.fAppOfflineDetail, T.fAppOfflineFix, Action.RESTART_SERVER)
        if (reachable && f.statusCode == 200 && rep == null && f.reportUnsupported) out += Finding(Level.INFO, T.fOldReportTitle, "", T.fOldReportFix, Action.RESTART_SERVER)
        if (f.localPhone && f.bridgeProcs.size > 1) out += Finding(Level.WARN, T.fTwoBridgesTitle, f.bridgeProcs.joinToString { "pid ${it.pid}" }, T.fTwoBridgesFix, Action.RESTART_SERVER)
        agy?.optJSONArray("other_bridges")?.objects()?.takeIf { it.isNotEmpty() && !(f.localPhone && f.bridgeProcs.size > 1) }?.let { list ->
            out += Finding(Level.WARN, T.fTwoBridgesTitle, list.joinToString { "pid ${it.optInt("pid")}" }, T.fTwoBridgesFix, Action.RESTART_SERVER)
        }

        // Antigravity
        if (hub != null && !hub.optBoolean("disabled")) {
            val status = hub.str("status")
            val err = hub.str("last_error")
            val file = agy?.optJSONObject("file")
            val targetPath = file?.str("target").orEmpty()
            val ownPid = hub.optInt("own_pid")
            val hubProcs = agy?.optJSONArray("hub_processes")?.objects().orEmpty()
            val strayHubs = hubProcs.filter { it.optBoolean("on_our_port") && !it.optBoolean("own_child") }.map { it.optInt("pid") }
            val busyPids = file?.optJSONArray("in_use_by")?.ints().orEmpty().filter { it != ownPid }
            if (status == "error") {
                when {
                    err.contains("text file busy", true) && busyPids.isEmpty() ->
                        out += Finding(Level.BAD, T.fHubErrorTitle, T.fBusyGoneDetail, T.fRestartFix, Action.RESTART_SERVER)
                    err.contains("text file busy", true) -> out += Finding(Level.BAD, T.fHubErrorTitle, T.fBusyDetail(busyPids.joinToString()),
                        T.fBusyFix, if (canStop(busyPids)) Action.STOP_AGY_PROCESSES else Action.RESTART_SERVER, busyPids)
                    err.contains("cannot locate CSRF header anchor", true) -> out += Finding(Level.BAD, T.fHubErrorTitle, T.fAnchorDetail(targetPath), T.fReinstallAgy, Action.OPEN_AGY_CLI)
                    err.contains("not installed", true) -> out += Finding(Level.BAD, T.fAgyMissingTitle, mask(err), T.fReinstallAgy, Action.OPEN_AGY_CLI)
                    else -> out += Finding(Level.BAD, T.fHubErrorTitle, mask(err), T.fHubErrorFix, Action.RESTART_SERVER)
                }
            } else if (status == "stopped" || status == "idle") {
                out += Finding(Level.WARN, T.fHubStoppedTitle, mask(err), T.fHubErrorFix, Action.RESTART_SERVER)
            }
            val verdict = agy?.optJSONObject("probe")?.str("verdict").orEmpty()
            val port = hub.str("port")
            val stopAction = if (strayHubs.isNotEmpty() && canStop(strayHubs)) Action.STOP_AGY_PROCESSES else Action.RESTART_SERVER
            when (verdict) {
                "ok" -> out += Finding(Level.OK, T.fHubOkTitle)
                "unpatched" -> out += Finding(Level.BAD, T.fUnpatchedTitle, T.fUnpatchedDetail(port), T.fUnpatchedFix, stopAction, strayHubs)
                "other_token" -> out += Finding(Level.BAD, T.fOtherTokenTitle, T.fOtherTokenDetail(port), T.fUnpatchedFix, stopAction, strayHubs)
                "stale_csrf" -> out += Finding(Level.WARN, T.fStaleCsrfTitle, T.fStaleCsrfDetail, T.fRestartFix, Action.RESTART_SERVER)
                "unreachable" -> if (status != "error" && status != "starting") out += Finding(Level.BAD, T.fHubPortTitle, T.fHubPortDetail(port), T.fHubErrorFix, Action.RESTART_SERVER)
            }
            if (hub.optBoolean("external") && verdict == "ok") out += Finding(Level.INFO, T.fExternalTitle(port), T.fExternalDetail)
            file?.optJSONObject("headers")?.let { hd ->
                if (hd.str("error").isBlank() && hd.optInt("ours") == 0 && hd.optInt("default") == 0 && (hd.optJSONArray("other")?.length() ?: 0) == 0) {
                    out += Finding(Level.BAD, T.fNoHeaderTitle, T.fNoHeaderDetail(targetPath), T.fReinstallAgy, Action.OPEN_AGY_CLI)
                }
            }
            if (status != "error" && busyPids.isNotEmpty() && hubProcs.none { it.optInt("pid") in busyPids }) {
                out += Finding(Level.INFO, T.fInUseTitle, T.fInUseDetail(busyPids.joinToString(), targetPath), T.fInUseFix,
                    if (canStop(busyPids)) Action.STOP_AGY_PROCESSES else null, busyPids)
            }
            if (hubProcs.any { it.optBoolean("on_our_port") && it.optBoolean("replaced_since_start") }) {
                out += Finding(Level.INFO, T.fReplacedTitle, T.fReplacedDetail, T.fRestartFix, Action.RESTART_SERVER)
            }
        }
        rep?.optJSONObject("system")?.let { s ->
            if (s.has("home_disk_free_mb") && s.optLong("home_disk_free_mb") < 500) out += Finding(Level.WARN, T.fLowDiskTitle(s.optLong("home_disk_free_mb").toString()), T.fLowDiskDetail)
            if (s.has("memavailable_mb") && s.optLong("memavailable_mb") < 300) out += Finding(Level.INFO, T.fLowMemTitle(s.optLong("memavailable_mb").toString()), T.fLowMemDetail)
        }
        rep?.optJSONArray("net")?.objects()?.filter { !it.optBoolean("ok") }?.forEach { n ->
            out += Finding(Level.WARN, T.fNetTitle(n.str("name")), n.str("error"), T.fNetFix)
        }
        for (kind in listOf(AgentKind.CLAUDE, AgentKind.OPENCODE, AgentKind.CODEX, AgentKind.AGY)) {
            if (kind !in f.agentsOn) continue
            val json = f.agents[kind] ?: continue
            val installed = json.optBoolean("installed", json.has("version"))
            if (!installed) out += Finding(Level.WARN, T.fAgentMissingTitle(agentName(kind)), json.str("error").let(::mask), T.fAgentMissingFix, Action.OPEN_AGENTS)
            else json.str("error").takeIf { it.isNotBlank() }?.let { out += Finding(Level.WARN, "${agentName(kind)}: ${mask(it).take(200)}") }
        }
        if (f.dataSaver) out += Finding(Level.INFO, T.fDataSaverTitle, T.fDataSaverDetail)
        if (f.batteryRestricted && f.localPhone) out += Finding(Level.INFO, T.fBatteryTitle, T.fBatteryDetail)
        if (out.none { it.level == Level.BAD || it.level == Level.WARN }) out.add(0, Finding(Level.OK, T.noProblems, T.fAllGoodDetail))
        return out
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    /** The processes are this app's own (same user) agy processes, which the app may stop. */
    fun canStop(pids: List<Int>): Boolean = pids.isNotEmpty() && pids.all { isOwnAgy(it) }

    private fun isOwnAgy(pid: Int): Boolean = runCatching {
        val status = File("/proc/$pid/status").readText()
        val uid = status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
        val cmd = String(File("/proc/$pid/cmdline").readBytes()).replace('\u0000', ' ')
        uid == android.os.Process.myUid() && Regex("""(^|/)agy(\.va39)?\s""").containsMatchIn("$cmd ") && !cmd.contains("agy_ide_bridge")
    }.getOrDefault(false)

    /** Stops the given agy processes (only this app's own), then restarts the server. Returns how many were stopped. */
    suspend fun stopAgyProcessesAndRestart(context: Context, pids: List<Int>): Int = withContext(Dispatchers.IO) {
        val targets = pids.filter { isOwnAgy(it) }
        targets.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
        delay(1500)
        targets.filter { File("/proc/$it").exists() && isOwnAgy(it) }.forEach { runCatching { android.os.Process.sendSignal(it, android.os.Process.SIGNAL_KILL) } }
        withContext(Dispatchers.Main) { LocalServerManager.restartServer(context.applicationContext) }
        targets.size
    }

    // ---- report ---------------------------------------------------------------------------------------------------

    private fun levelMark(level: Level?): String = when (level) {
        Level.OK -> "✅"
        Level.INFO -> "ℹ️"
        Level.WARN -> "⚠️"
        Level.BAD -> "❌"
        null -> ""
    }

    /**
     * The report as Markdown: findings as a list, each area as a table, command lines in code blocks, and the logs and
     * the bridge's raw report folded in <details> (GitHub and most Markdown viewers show them collapsed). Every piece is
     * scrubbed first and then made safe for where it goes, so the placeholders (‹token›, ‹ip›…) stay visible as text.
     */
    fun reportMarkdown(context: Context, r: Result): String {
        val T = L.settings.troubleshoot
        val sc = { text: String -> scrubForShare(context, text) }
        // text in a list or a heading: no HTML (‹app› reads the same rendered or as plain text), line breaks kept
        val inline = { text: String -> sc(text).replace("<", "‹").replace(">", "›").replace("\n", "  \n  ") }
        // text in a table cell: also no pipes or line breaks
        val cell = { text: String -> inline(text).replace("|", "\\|").replace("  \n  ", "<br>") }
        fun fenced(sb: StringBuilder, lang: String, text: String) {
            val body = sc(text).ifBlank { T.empty }
            val fence = if ("```" in body) "````" else "```"
            sb.append(fence).append(lang).append('\n').append(body.trimEnd()).append('\n').append(fence).append("\n\n")
        }

        val sb = StringBuilder()
        sb.append("# AntiGem troubleshoot report\n\n")
        val bad = r.findings.count { it.level == Level.BAD }
        val warn = r.findings.count { it.level == Level.WARN }
        val summary = if (bad == 0 && warn == 0) "✅ ${T.noProblems}" else "${if (bad > 0) "❌" else "⚠️"} ${T.summary(bad.toString(), warn.toString())}"
        sb.append("**").append(inline(summary)).append("**  \n")
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(r.startedMs)))
            .append(" · ${r.tookMs} ms · AntiGem ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .append(if (BuildConfig.DEBUG) " debug" else "").append(BuildConfig.APK_SUFFIX).append("\n\n")

        sb.append("## ").append(inline(T.findings)).append("\n\n")
        r.findings.forEach { f ->
            sb.append("- ").append(levelMark(f.level)).append(" **").append(inline(f.title)).append("**")
            if (f.detail.isNotBlank()) sb.append("  \n  ").append(inline(f.detail))
            if (f.fix.isNotBlank()) sb.append("  \n  ").append(inline(T.whatToDo(f.fix)))
            sb.append('\n')
        }
        sb.append('\n')

        r.sections.forEach { s ->
            sb.append("## ").append(levelMark(s.level).let { if (it.isEmpty()) "" else "$it " }).append(inline(s.title)).append("\n\n")
            if (s.rows.isNotEmpty()) {
                sb.append("| | |\n|---|---|\n")
                s.rows.forEach { row ->
                    sb.append("| ").append(cell(row.label)).append(" | ")
                        .append(levelMark(row.level).let { if (it.isEmpty()) "" else "$it " }).append(cell(row.value)).append(" |\n")
                }
                sb.append('\n')
            }
            s.lines.forEach { l ->
                sb.append("**").append(inline(l.title)).append("**\n\n")
                fenced(sb, "text", l.lines.joinToString("\n"))
            }
        }

        if (r.logs.isNotEmpty()) {
            sb.append("## ").append(inline(T.logs)).append("\n\n")
            r.logs.forEach { log ->
                sb.append("<details>\n<summary>").append(inline(log.title)).append(" — ")
                    .append(inline(T.lineCount(log.lines.size.toString()))).append("</summary>\n\n")
                fenced(sb, "text", log.lines.joinToString("\n"))
                sb.append("</details>\n\n")
            }
        }
        r.bridgeJson?.let { json ->
            sb.append("<details>\n<summary>Bridge report (JSON)</summary>\n\n")
            fenced(sb, "json", json)
            sb.append("</details>\n")
        }
        return sb.toString()
    }

    private val email = Regex("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")
    private val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val keyValue = Regex("""(?i)\b(access_token|refresh_token|api_key|apikey|secret|password|passwd|authorization|cookie)(\s*[=:]\s*)("?)[^\s&,;"']+""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val googleToken = Regex("""\bya29\.[A-Za-z0-9._-]+|\b1//[A-Za-z0-9._-]{20,}|\bsk-[A-Za-z0-9_-]{16,}""")

    /** What leaves the phone: secrets, e-mail addresses and public IP addresses hidden; the app's folder shortened. */
    fun scrubForShare(context: Context, text: String): String {
        var s = mask(text)
        val pkg = Regex.escape(context.packageName)
        s = s.replace(Regex("""/data/(?:data|user/\d+)/$pkg/files/home"""), "~")
        s = s.replace(Regex("""/data/(?:data|user/\d+|user_de/\d+)/$pkg"""), "<app>")
        s = s.replace(email, "<user@host>")
        s = s.replace(bearer) { "${it.groupValues[1]} <hidden>" }
        s = s.replace(keyValue) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}<hidden>" }
        s = s.replace(googleToken, "<hidden>")
        s = s.replace(ipv4) { m ->
            val host = m.value
            if (LocalDirectProxySelector.isLocalAddress(host) || host.startsWith("0.")) host else "<ip>"
        }
        return s
    }

    /** Shares the report as a Markdown file (whole, unlike the clipboard, which cuts long text without saying so). */
    fun share(context: Context, r: Result) {
        val dir = File(context.cacheDir, "troubleshoot").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "antigem-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(r.startedMs))}.md"
        val file = File(dir, name)
        file.writeText(reportMarkdown(context, r))
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val summary = r.findings.joinToString("\n") { "${levelMark(it.level)} ${it.title}" }
        val send = Intent(Intent.ACTION_SEND).apply {
            // text/plain: every chat and mail app takes it; the .md name lets viewers show it as Markdown
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AntiGem troubleshoot report")
            putExtra(Intent.EXTRA_TEXT, scrubForShare(context, summary).take(3000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, L.settings.troubleshoot.shareReport).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private fun expandHome(context: Context, path: String): File {
        val home = LocalEnvironmentManager.getHomeDir(context)
        val f = when {
            path == "~" -> home
            path.startsWith("~/") -> File(home, path.removePrefix("~/"))
            else -> File(path)
        }
        return runCatching { f.canonicalFile }.getOrDefault(f)
    }

    private fun proxyText(p: Proxy): String =
        (p.address() as? InetSocketAddress)?.let { "${it.hostString}:${it.port}" } ?: p.address().toString()

    private fun time(ms: Long): String = if (ms <= 0) "-" else SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(ms))
    private fun ms(v: Long) = "$v ms"
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    }

    private fun JSONObject.str(key: String): String = if (has(key) && !isNull(key)) optString(key) else ""
    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i -> if (isNull(i)) null else optString(i) }
    private fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
