package ai.antigem.data.rea

import android.content.Context
import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import android.util.Log
import ai.antigem.ui.browser.BrowserIdentity
import ai.antigem.ui.browser.BrowserSessionManager
import ai.antigem.ui.browser.BrowserTabSession
import ai.antigem.ui.browser.mcp.BrowserCdp
import ai.antigem.ui.browser.mcp.CdpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import ai.antigem.i18n.L

/**
 * Settings → REA → Websites: lets REA's website tools (list_browser_targets, inspect_web_page, capture_web_screenshot…)
 * reach the app's own browser tabs over the Chrome DevTools Protocol.
 *
 * REA (in the proot Ubuntu container) only accepts a loopback TCP endpoint (`http://127.0.0.1:PORT`), while the WebView
 * serves DevTools on its abstract socket `webview_devtools_remote_<pid>`. This bridge listens on 127.0.0.1:[port] and
 * forwards to that socket, with these changes:
 * - `/json/list` shows only the browser's tabs (never the chat, mini apps or Google sign-in pages);
 * - `/json/version` names a page socket: WebView reports `/devtools/browser` without the id REA requires, and per-page
 *   sockets let the bridge refuse every page that is not a browser tab;
 * - `Page.captureScreenshot` of a tab that is not on screen (or that DevTools does not answer, e.g. the phone is locked)
 *   is answered with the WebView's own drawing, so REA's screenshots work for background tabs too;
 * - the browser socket, `/json/new`, `/json/close`, `/json/activate` and requests from web pages (Origin header,
 *   non-loopback Host) are refused.
 *
 * Off by default and never started on its own: any app on the phone can connect to a loopback port while it listens.
 */
object ReaWebBridge {
    private const val TAG = "ReaWebBridge"
    const val DEFAULT_PORT = 9222
    /** How long DevTools may take to draw an on-screen tab before the WebView's own drawing answers instead. */
    private const val SCREENSHOT_WAIT_MS = 5000L
    /** Client messages up to this size are read whole (to spot screenshot requests); bigger ones are streamed. */
    private const val INSPECT_LIMIT = 1 shl 20

    data class State(
        val running: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val error: String? = null,
        /** REA connections to a tab open right now. */
        val connections: Int = 0,
        /** Requests served since the bridge started. */
        val requests: Int = 0,
        val lastActivity: String? = null
    ) {
        val endpoint: String get() = "http://127.0.0.1:$port"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The bridge stops itself after this long without a request (and no open connection). */
    private const val IDLE_STOP_MS = 10 * 60_000L
    @Volatile private var lastUse = 0L
    private var idleWatch: kotlinx.coroutines.Job? = null
    private var server: ServerSocket? = null
    private val open = Collections.newSetFromMap(ConcurrentHashMap<java.io.Closeable, Boolean>())
    private lateinit var appContext: Context

    @Synchronized
    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (server != null) return
        appContext = context.applicationContext
        val socket = try {
            ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            _state.update { State(running = false, port = port, error = "Could not listen on 127.0.0.1:$port: ${e.message}. Another program may use this port.") }
            return
        }
        server = socket
        ai.antigem.data.security.LocalPorts.add(socket.localPort)
        // DevTools must be on for the WebView to serve its socket (the browser turns it on too)
        ai.antigem.ui.browser.WebDebug.hold("rea")
        _state.update { State(running = true, port = port) }
        // no secret on this port (REA's client may drop a path): it stops itself when nobody used it for a while
        lastUse = android.os.SystemClock.elapsedRealtime()
        idleWatch?.cancel()
        idleWatch = scope.launch {
            while (server === socket) {
                kotlinx.coroutines.delay(60_000)
                val idle = android.os.SystemClock.elapsedRealtime() - lastUse
                if (server === socket && _state.value.connections == 0 && idle >= IDLE_STOP_MS) {
                    stop()
                    _state.update { it.copy(error = L.rea.webBridge.stoppedAfterMinutesMinutesWithoutUse(IDLE_STOP_MS / 60_000)) }
                    break
                }
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$port")
        thread(name = "rea-web-bridge", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                thread(name = "rea-web-client", isDaemon = true) { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        ai.antigem.ui.browser.WebDebug.drop("rea")
        runCatching { socket.close() }
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
        _state.update { it.copy(running = false, connections = 0, error = null) }
        Log.i(TAG, "stopped")
    }

    val isRunning: Boolean get() = server != null

    private fun activity(text: String) = _state.update { it.copy(requests = it.requests + 1, lastActivity = text) }

    // ---------------- HTTP ----------------

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val head: String)

    private fun handle(client: Socket) {
        lastUse = android.os.SystemClock.elapsedRealtime()
        open.add(client)
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val request = readRequest(input) ?: return
            val host = request.headers["host"].orEmpty()
            // Web pages can reach loopback ports too: refuse anything a browser page would send
            if (request.headers.containsKey("origin") || !isLoopbackHost(host)) {
                respond(out, 403, JSONObject().put("error", "Only local DevTools clients (REA) may use this endpoint."))
                return
            }
            val path = request.path.substringBefore('?')
            val upgrade = request.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            when {
                upgrade && path.startsWith("/devtools/page/") -> pipePage(client, request, path.removePrefix("/devtools/page/"))
                request.method != "GET" -> respond(out, 405, JSONObject().put("error", "Method not allowed"))
                path == "/json/version" -> {
                    activity("GET /json/version")
                    val version = JSONObject(String(upstreamGet("/json/version", host), Charsets.UTF_8))
                    val first = runBlocking { browserTargets() }.keys.firstOrNull() ?: "none"
                    version.put("webSocketDebuggerUrl", "ws://$host/devtools/page/$first")
                    respond(out, 200, version)
                }
                path == "/json" || path == "/json/list" -> {
                    activity("GET /json/list")
                    val all = JSONArray(String(upstreamGet("/json/list", host), Charsets.UTF_8))
                    val allowed = runBlocking { browserTargets() }.keys
                    val list = JSONArray()
                    for (i in 0 until all.length()) {
                        val t = all.optJSONObject(i) ?: continue
                        if (t.optString("id") in allowed) list.put(t)
                    }
                    respond(out, 200, list)
                }
                else -> respond(out, 404, JSONObject().put("error", "Not available through AntiGem's REA bridge: $path"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
        } finally {
            open.remove(client)
            runCatching { client.close() }
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        return name == "127.0.0.1" || name == "localhost" || name == "[::1]"
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw EOFException("connection closed in the request head")
            buf.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buf.toString("ISO-8859-1")
            if (buf.size() > 32 * 1024) throw IOException("request head too large")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
        }.toMap()
        return Request(parts[0], parts[1], headers, head)
    }

    private fun respond(out: OutputStream, status: Int, body: Any) {
        val bytes = (if (body is JSONObject) body.toString(1) else (body as JSONArray).toString(1)).toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(bytes)
        out.flush()
    }

    private fun connectDevTools(): LocalSocket = LocalSocket().apply {
        connect(LocalSocketAddress(CdpSession.socketName(), LocalSocketAddress.Namespace.ABSTRACT))
    }

    /** A GET to the WebView's DevTools HTTP server, with REA's Host so the socket URLs it reports point at this bridge. */
    private fun upstreamGet(path: String, host: String): ByteArray {
        val socket = connectDevTools()
        try {
            socket.soTimeout = 10_000
            socket.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            socket.outputStream.flush()
            val input = socket.inputStream
            val head = readHead(input) ?: throw EOFException("DevTools closed the connection")
            if (!head.startsWith("HTTP/1.1 200")) throw IOException("DevTools answered ${head.lineSequence().first()}")
            val length = head.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            return if (length != null) readExactly(input, length) else input.readBytes()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) throw EOFException("connection closed")
            read += n
        }
        return out
    }

    // ---------------- tabs ----------------

    /** DevTools target id → browser tab, for every tab with a live WebView (Google sign-in pages are left out). */
    private suspend fun browserTargets(): Map<String, BrowserTabSession> {
        val manager = BrowserSessionManager.instance
        val tabs = withContext(Dispatchers.Main) {
            val active = manager.getActiveTab()?.id
            manager.tabs.filter { it.webView != null }.sortedBy { if (it.id == active) 0 else 1 }
        }
        val result = LinkedHashMap<String, BrowserTabSession>()
        for (tab in tabs) {
            if (BrowserIdentity.isGoogleSignInUrl(tab.url)) continue
            val session = withTimeoutOrNull(15_000) { BrowserCdp.session(tab) } ?: continue
            result[session.targetId] = tab
        }
        return result
    }

    // ---------------- WebSocket ----------------

    private class Frame(val fin: Boolean, val opcode: Int, val masked: Boolean, val length: Long, val mask: ByteArray?, val raw: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) throw EOFException("connection closed in a frame header")
        val raw = ByteArrayOutputStream()
        raw.write(b0); raw.write(b1)
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) {
            val ext = readExactly(input, 2); raw.write(ext)
            length = ((ext[0].toInt() and 0xff) shl 8 or (ext[1].toInt() and 0xff)).toLong()
        } else if (length == 127L) {
            val ext = readExactly(input, 8); raw.write(ext)
            length = 0
            for (b in ext) length = (length shl 8) or (b.toLong() and 0xff)
        }
        val masked = b1 and 0x80 != 0
        val mask = if (masked) readExactly(input, 4).also { raw.write(it) } else null
        return Frame(b0 and 0x80 != 0, b0 and 0x0f, masked, length, mask, raw.toByteArray())
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("connection closed in a frame")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("connection closed in a frame")
            left -= n
        }
    }

    /** One unmasked text frame from the server side (what the bridge sends REA itself). */
    private fun textFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write(0x81)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xffff -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        out.write(payload)
        return out.toByteArray()
    }

    /** A screenshot request waiting for DevTools: 0 waiting, 1 answered by DevTools, 2 answered by the bridge. */
    private class Pending(val state: AtomicInteger = AtomicInteger(0))

    /** REA's WebSocket to one browser tab, forwarded frame by frame to the WebView's page socket. */
    private fun pipePage(client: Socket, request: Request, targetId: String) {
        val tab = runBlocking { browserTargets() }[targetId]
        val out = client.getOutputStream()
        if (tab == null) {
            respond(out, 403, JSONObject().put("error", "Not a browser tab: only AntiGem's browser tabs are shown to REA."))
            return
        }
        // No permessage-deflate: the bridge reads REA's requests to answer screenshots itself
        val head = request.head.split("\r\n").filterNot { it.startsWith("Sec-WebSocket-Extensions:", ignoreCase = true) }.joinToString("\r\n")
        val upstream = connectDevTools()
        open.add(upstream)
        _state.update { it.copy(connections = it.connections + 1) }
        activity(L.rea.webBridge.reaAttachedToValue(tab.url.ifBlank { tab.title }))
        try {
            client.soTimeout = 0
            val upIn = upstream.inputStream
            val upOut = upstream.outputStream
            upOut.write(head.toByteArray(Charsets.ISO_8859_1)); upOut.flush()
            val reply = readHead(upIn) ?: throw EOFException("DevTools closed the connection")
            out.write(reply.toByteArray(Charsets.ISO_8859_1)); out.flush()
            if (!reply.startsWith("HTTP/1.1 101")) return

            val clientIn = client.getInputStream()
            val clientLock = Any()
            val pending = ConcurrentHashMap<Int, Pending>()
            fun send(bytes: ByteArray) = synchronized(clientLock) { out.write(bytes); out.flush() }

            fun answerWithDrawing(id: Int) = scope.launch {
                val response = runCatching { JSONObject().put("id", id).put("result", JSONObject().put("data", drawTab(tab))) }
                    .getOrElse { JSONObject().put("id", id).put("error", JSONObject().put("code", -32000).put("message", "Could not draw the tab: ${it.message}")) }
                runCatching { send(textFrame(response.toString())) }
            }

            // DevTools → REA: whole frames, so the bridge's own answers never land inside one
            val down = thread(name = "rea-web-down", isDaemon = true) {
                try {
                    while (true) {
                        val f = readFrame(upIn) ?: break
                        if (f.opcode == 1 && pending.isNotEmpty() && f.length > 0) {
                            val prefix = readExactly(upIn, minOf(f.length, 48L).toInt())
                            val id = Regex("^\\{\"id\":(\\d+)").find(String(prefix, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toIntOrNull()
                            val wait = id?.let { pending[it] }
                            if (wait != null && !wait.state.compareAndSet(0, 1)) {
                                // The bridge already answered this screenshot: drop DevTools' late answer
                                pending.remove(id)
                                skip(upIn, f.length - prefix.size)
                                continue
                            }
                            if (wait != null) pending.remove(id)
                            synchronized(clientLock) { out.write(f.raw); out.write(prefix); copy(upIn, out, f.length - prefix.size); out.flush() }
                        } else {
                            synchronized(clientLock) { out.write(f.raw); copy(upIn, out, f.length); out.flush() }
                        }
                        if (f.opcode == 8) break
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { client.shutdownInput() }
                    runCatching { client.close() }
                }
            }

            // REA → DevTools
            try {
                while (true) {
                    val f = readFrame(clientIn) ?: break
                    if (BrowserIdentity.isGoogleSignInUrl(tab.url)) break // never keep DevTools on a Google sign-in page
                    if (f.opcode == 1 && f.fin && f.length <= INSPECT_LIMIT) {
                        val payload = readExactly(clientIn, f.length.toInt())
                        val text = ByteArray(payload.size) { i -> if (f.mask != null) (payload[i].toInt() xor f.mask[i % 4].toInt()).toByte() else payload[i] }
                        val message = runCatching { JSONObject(String(text, Charsets.UTF_8)) }.getOrNull()
                        if (message?.optString("method") == "Page.captureScreenshot" && message.has("id")) {
                            val id = message.getInt("id")
                            activity(L.rea.webBridge.screenshotOfValue(tab.url.ifBlank { tab.title }))
                            if (!tab.isOnScreen) {
                                // DevTools only draws what is on screen: the WebView draws background tabs itself
                                answerWithDrawing(id)
                                continue
                            }
                            val wait = Pending()
                            pending[id] = wait
                            scope.launch {
                                kotlinx.coroutines.delay(SCREENSHOT_WAIT_MS)
                                if (wait.state.compareAndSet(0, 2)) answerWithDrawing(id)
                            }
                        }
                        upOut.write(f.raw); upOut.write(payload); upOut.flush()
                    } else {
                        upOut.write(f.raw); copy(clientIn, upOut, f.length); upOut.flush()
                    }
                    if (f.opcode == 8) break
                }
            } catch (_: IOException) {
            }
            runCatching { upstream.shutdownOutput() }
            down.join(5000)
        } catch (e: Exception) {
            Log.w(TAG, "page connection failed: ${e.message}")
        } finally {
            open.remove(upstream)
            runCatching { upstream.close() }
            _state.update { it.copy(connections = (it.connections - 1).coerceAtLeast(0)) }
            // Chromium can reset emulation when another DevTools client leaves: restore what the browser set
            scope.launch {
                runCatching {
                    BrowserCdp.session(tab)?.send("Emulation.setFocusEmulationEnabled", JSONObject().put("enabled", true), 5000)
                }
            }
        }
    }

    /** The tab's viewport drawn by its WebView, as base64 PNG (what Page.captureScreenshot returns). */
    private suspend fun drawTab(tab: BrowserTabSession): String {
        val bitmap = BrowserSessionManager.instance.captureScreenshot(tab.id, false, 100).getOrThrow()
        return withContext(Dispatchers.Default) {
            try {
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
