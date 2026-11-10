package com.example.gemini.ui.browser.mcp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import com.example.gemini.ui.browser.BrowserIdentity
import com.example.gemini.ui.browser.BrowserSessionManager
import com.example.gemini.ui.browser.BrowserTabSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CdpException(message: String) : Exception(message)

/**
 * One DevTools connection to one WebView page, over the app's own WebView debugging socket
 * (`webview_devtools_remote_<pid>`; WebView accepts connections from the app's own uid, adb and root).
 * A small WebSocket client: one reader thread, writes serialized. Never call [send] on the main thread
 * while holding it: WebView needs that thread to answer some commands (e.g. screenshots).
 */
class CdpSession private constructor(private val socket: LocalSocket, val targetId: String) {
    private val input = BufferedInputStream(socket.inputStream, 64 * 1024)
    private val output: OutputStream = socket.outputStream
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val random = SecureRandom()

    @Volatile var closed = false
        private set
    @Volatile var onEvent: ((String, JSONObject) -> Unit)? = null
    @Volatile var onClosed: (() -> Unit)? = null

    companion object {
        private const val TAG = "WebViewCdp"
        private const val MAX_FRAME = 256L * 1024 * 1024

        fun socketName() = "webview_devtools_remote_${Process.myPid()}"

        private fun connectSocket(): LocalSocket = LocalSocket().apply {
            connect(LocalSocketAddress(socketName(), LocalSocketAddress.Namespace.ABSTRACT))
            soTimeout = 5000
        }

        private fun readHead(input: InputStream): String {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("DevTools closed the connection")
                buf.write(b)
                val n = buf.size()
                if (n >= 4) {
                    val a = buf.toByteArray()
                    if (a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
                }
                if (n > 64 * 1024) throw IOException("DevTools sent an oversized header")
            }
            return buf.toString("ISO-8859-1")
        }

        private fun readExactly(input: InputStream, size: Int): ByteArray {
            val out = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(out, read, size - read)
                if (n < 0) throw EOFException("DevTools closed the connection")
                read += n
            }
            return out
        }

        /** `GET /json/list`: the WebView pages DevTools can attach to. Blocking. */
        fun listTargets(): JSONArray {
            val socket = connectSocket()
            try {
                socket.outputStream.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                socket.outputStream.flush()
                val input = BufferedInputStream(socket.inputStream)
                val head = readHead(input)
                require(head.startsWith("HTTP/1.1 200")) { "DevTools target list failed: ${head.lineSequence().first()}" }
                val headers = head.lineSequence().drop(1).mapNotNull { line ->
                    line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
                }.toMap()
                val body = when {
                    headers["content-length"] != null -> readExactly(input, headers.getValue("content-length").toInt())
                    headers["transfer-encoding"]?.contains("chunked") == true -> {
                        val out = ByteArrayOutputStream()
                        while (true) {
                            val sizeLine = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b < 0 || b == '\n'.code) break
                                if (b != '\r'.code) sizeLine.append(b.toChar())
                            }
                            val size = sizeLine.toString().substringBefore(';').trim().toInt(16)
                            if (size == 0) break
                            out.write(readExactly(input, size))
                            readExactly(input, 2)
                        }
                        out.toByteArray()
                    }
                    else -> input.readBytes()
                }
                return JSONArray(String(body, Charsets.UTF_8))
            } finally {
                runCatching { socket.close() }
            }
        }

        /** Opens a WebSocket to page [targetId]. Blocking. */
        fun open(targetId: String): CdpSession {
            val socket = connectSocket()
            try {
                val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                val request = "GET /devtools/page/$targetId HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                socket.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                socket.outputStream.flush()
                val session = CdpSession(socket, targetId)
                val head = readHead(session.input)
                val accept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)),
                    Base64.NO_WRAP
                )
                if (!head.startsWith("HTTP/1.1 101") || !head.contains("Sec-WebSocket-Accept: $accept", ignoreCase = true)) {
                    throw IOException("WebView refused the DevTools connection: ${head.lineSequence().first()}")
                }
                socket.soTimeout = 0
                session.start()
                return session
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }

    private fun start() {
        thread(name = "cdp-$targetId", isDaemon = true) { readLoop() }
    }

    private fun readLoop() {
        val message = ByteArrayOutputStream()
        try {
            while (!closed) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) {
                    val ext = readExactly(input, 2)
                    length = ((ext[0].toInt() and 0xFF) shl 8 or (ext[1].toInt() and 0xFF)).toLong()
                } else if (length == 127L) {
                    val ext = readExactly(input, 8)
                    length = 0
                    for (x in ext) length = (length shl 8) or (x.toLong() and 0xFF)
                }
                if (length > MAX_FRAME) throw IOException("DevTools frame too large ($length bytes)")
                val mask = if (b1 and 0x80 != 0) readExactly(input, 4) else null
                val payload = readExactly(input, length.toInt())
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (opcode) {
                    0x8 -> break
                    0x9 -> writeFrame(payload, 0xA)
                    0xA -> Unit
                    0x0, 0x1, 0x2 -> {
                        message.write(payload)
                        if (fin) {
                            val text = message.toString("UTF-8")
                            message.reset()
                            dispatch(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.d(TAG, "DevTools connection to $targetId ended: ${e.message}")
        } finally {
            close()
        }
    }

    private fun dispatch(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.has("id")) {
            pending.remove(json.optInt("id"))?.complete(json)
        } else {
            val method = json.optString("method")
            if (method.isNotEmpty()) runCatching { onEvent?.invoke(method, json.optJSONObject("params") ?: JSONObject()) }
                .onFailure { Log.w(TAG, "DevTools event handler failed for $method", it) }
        }
    }

    @Synchronized
    private fun writeFrame(payload: ByteArray, opcode: Int) {
        val head = ByteArrayOutputStream(14)
        head.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> head.write(0x80 or n)
            n <= 0xFFFF -> { head.write(0x80 or 126); head.write(n ushr 8 and 0xFF); head.write(n and 0xFF) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write((n.toLong() ushr (8 * i) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        head.write(mask)
        val masked = ByteArray(n) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        output.write(head.toByteArray())
        output.write(masked)
        output.flush()
    }

    /**
     * Sends a command without waiting for its answer (writes are ordered, so DevTools runs queued commands in order).
     * Blocking write: call off the main thread. Await the result with [await].
     */
    fun enqueue(method: String, params: JSONObject = JSONObject()): Pair<Int, CompletableDeferred<JSONObject>> {
        if (closed) throw CdpException("The DevTools connection is closed")
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            writeFrame(JSONObject().put("id", id).put("method", method).put("params", params).toString().toByteArray(Charsets.UTF_8), 0x1)
        } catch (e: Exception) {
            pending.remove(id)
            close()
            throw CdpException("Could not send $method: ${e.message}")
        }
        return id to deferred
    }

    suspend fun await(method: String, queued: Pair<Int, CompletableDeferred<JSONObject>>, timeoutMs: Long): JSONObject {
        val (id, deferred) = queued
        val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        reply ?: throw CdpException("$method got no answer within ${timeoutMs / 1000.0}s (the page may be busy or showing a dialog)")
        reply.optJSONObject("error")?.let { err ->
            val data = err.optString("data").takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            throw CdpException("$method failed: ${err.optString("message")}$data")
        }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    suspend fun send(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val queued = withContext(Dispatchers.IO) { enqueue(method, params) }
        return await(method, queued, timeoutMs)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
        val failure = CdpException("The DevTools connection closed")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        runCatching { onClosed?.invoke() }
    }
}

/** Requests seen on one tab while the browser MCP is attached (newest kept, at most [MAX]). */
class NetworkLog {
    class Entry(val url: String, val method: String, val type: String, val startedAt: Long, val monoStart: Double) {
        @Volatile var status = 0
        @Volatile var mime = ""
        @Volatile var bytes = -1L
        @Volatile var error: String? = null
        @Volatile var durationMs = -1L
        @Volatile var fromCache = false
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var redirects = 0

    companion object { const val MAX = 500 }

    @Synchronized
    fun onEvent(method: String, p: JSONObject) {
        when (method) {
            "Network.requestWillBeSent" -> {
                val req = p.optJSONObject("request") ?: return
                val url = req.optString("url")
                if (url.startsWith("data:") || url.startsWith("blob:")) return
                val id = p.optString("requestId")
                p.optJSONObject("redirectResponse")?.let { r ->
                    entries.remove(id)?.let { old ->
                        old.status = r.optInt("status")
                        entries["$id#r${++redirects}"] = old
                    }
                }
                entries[id] = Entry(url, req.optString("method", "GET"), p.optString("type", "Other"), System.currentTimeMillis(), p.optDouble("timestamp", 0.0))
                while (entries.size > MAX) entries.remove(entries.keys.first())
            }
            "Network.responseReceived" -> entries[p.optString("requestId")]?.let { e ->
                val r = p.optJSONObject("response") ?: return
                e.status = r.optInt("status")
                e.mime = r.optString("mimeType")
                e.fromCache = r.optBoolean("fromDiskCache") || r.optBoolean("fromServiceWorker") || r.optBoolean("fromPrefetchCache")
            }
            "Network.loadingFinished" -> entries[p.optString("requestId")]?.let { e ->
                e.bytes = p.optDouble("encodedDataLength", -1.0).toLong()
                if (e.monoStart > 0) e.durationMs = ((p.optDouble("timestamp", e.monoStart) - e.monoStart) * 1000).toLong()
            }
            "Network.loadingFailed" -> entries[p.optString("requestId")]?.let { e ->
                e.error = if (p.optBoolean("canceled")) "canceled" else p.optString("blockedReason").ifBlank { p.optString("errorText", "failed") }
                if (e.monoStart > 0) e.durationMs = ((p.optDouble("timestamp", e.monoStart) - e.monoStart) * 1000).toLong()
            }
        }
    }

    @Synchronized fun snapshot(): List<Entry> = entries.values.toList()
    @Synchronized fun clear() = entries.clear()
}

/**
 * The browser MCP's DevTools connections, one per tab, opened on first use and kept while the page lives.
 * Never attached on Google sign-in pages: Google's checks there must not see a debugger (see [releaseThen]).
 */
object BrowserCdp {
    private const val TAG = "BrowserCdp"
    private val sessions = ConcurrentHashMap<String, CdpSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val logs = ConcurrentHashMap<String, NetworkLog>()

    fun isAttached(tabId: String) = sessions[tabId]?.closed == false

    fun network(tabId: String): NetworkLog? = logs[tabId]

    /** This tab's DevTools connection, opening one if needed; null when unavailable (then callers use the fallbacks). */
    suspend fun session(tab: BrowserTabSession): CdpSession? {
        sessions[tab.id]?.let { if (!it.closed) return it else sessions.remove(tab.id, it) }
        if (BrowserIdentity.isGoogleSignInUrl(tab.url)) return null
        failedAt[tab.id]?.let { if (System.currentTimeMillis() - it < 15_000) return null }
        return locks.getOrPut(tab.id) { Mutex() }.withLock {
            sessions[tab.id]?.takeIf { !it.closed }?.let { return@withLock it }
            val opened = runCatching { connect(tab) }.onFailure { Log.w(TAG, "DevTools unavailable for tab ${tab.id}: ${it.message}") }.getOrNull()
            if (opened == null) failedAt[tab.id] = System.currentTimeMillis() else failedAt.remove(tab.id)
            opened
        }
    }

    private suspend fun connect(tab: BrowserTabSession): CdpSession? {
        val manager = BrowserSessionManager.instance
        repeat(2) {
            // The same tag the extension host uses to tell WebViews apart; a navigation can wipe it, hence the retry
            manager.evaluateJs(tab.id, "window.__antigemTabId=${JSONObject.quote(tab.id)};true").getOrElse { return null }
            val currentUrl = withContext(Dispatchers.Main) { tab.webView?.url.orEmpty() }
            val claimed = sessions.values.filter { !it.closed }.map { it.targetId }.toSet()
            val found = withContext(Dispatchers.IO) {
                val list = CdpSession.listTargets()
                val pages = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optString("type") == "page" && it.optString("id") !in claimed }
                    .sortedBy { if (it.optString("url") == currentUrl) 0 else 1 }
                for (target in pages) {
                    val session = runCatching { CdpSession.open(target.getString("id")) }.getOrNull() ?: continue
                    val marker = runCatching {
                        session.send("Runtime.evaluate", JSONObject().put("expression", "window.__antigemTabId").put("returnByValue", true), 3000)
                            .optJSONObject("result")?.optString("value")
                    }.getOrNull()
                    if (marker == tab.id) return@withContext session
                    session.close()
                }
                null
            }
            if (found != null) {
                attach(tab.id, found)
                return found
            }
        }
        return null
    }

    private suspend fun attach(tabId: String, session: CdpSession) {
        val log = logs.getOrPut(tabId) { NetworkLog() }
        session.onEvent = { method, params -> if (method.startsWith("Network.")) log.onEvent(method, params) }
        session.onClosed = { sessions.remove(tabId, session) }
        sessions[tabId] = session
        runCatching { session.send("Network.enable", JSONObject().put("maxPostDataSize", 0), 5000) }
        // Lets background tabs keep keyboard focus, so typing still lands while the user is elsewhere in the app
        runCatching { session.send("Emulation.setFocusEmulationEnabled", JSONObject().put("enabled", true), 5000) }
    }

    /** Closes this tab's connection (tab closed, WebView replaced, or a Google sign-in page about to load). */
    fun release(tabId: String) {
        sessions.remove(tabId)?.close()
        failedAt.remove(tabId)
    }

    /** Closes the tab's connection off the main thread, then runs [then] on the main thread. */
    fun releaseThen(tabId: String, then: () -> Unit) {
        val session = sessions.remove(tabId)
        if (session == null) {
            then()
            return
        }
        thread(name = "cdp-release", isDaemon = true) {
            session.close()
            Handler(Looper.getMainLooper()).post(then)
        }
    }

    fun forget(tabId: String) {
        release(tabId)
        logs.remove(tabId)
        locks.remove(tabId)
    }
}
