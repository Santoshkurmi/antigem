package ai.antigem.data.agent.agy

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves a web app running in a cloud chat's VM on this phone (`http://127.0.0.1:<port>`), so the system browser can
 * show it: every request goes through the hub to the VM's port ([AgyCloud.fetchPort]). Hot reload (WebSocket) does not
 * work through it; reload the page to see changes.
 *
 * Loopback is not private on a phone: any app (by scanning ports) and any website (by DNS rebinding) could otherwise
 * send requests, any method and body, to the VM with the user's hub session. So the link AntiGem opens carries a
 * one-time token ([AUTH_PATH]); the answer sets a cookie (HttpOnly, SameSite=Strict) that every later request needs, and
 * a request whose Host is not this preview's own is refused.
 */
object CloudPreviewServer {
    private const val TAG = "AgyCloud"
    private const val MAX_BODY = 32 * 1024 * 1024

    private class Running(val server: ServerSocket, val conversationId: String, val vmPort: Int) {
        val token: String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val cookieName: String get() = "ag_preview_${server.localPort}"
    }

    private const val AUTH_PATH = "/__antigem_auth"

    private val servers = ConcurrentHashMap<String, Running>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cloud-preview").apply { isDaemon = true } }

    /**
     * The link that opens [path] of [vmPort] in the chat's VM (the server is started on first use, then kept while the
     * app runs). It carries the preview's token: only open it in the browser, never hand it to another app.
     */
    fun urlFor(conversationId: String, vmPort: Int, path: String = "/"): String {
        val key = "$conversationId:$vmPort"
        val running = servers[key]?.takeIf { !it.server.isClosed } ?: start(conversationId, vmPort).also { servers[key] = it }
        val to = "/" + path.trim().removePrefix("/")
        return "http://127.0.0.1:${running.server.localPort}$AUTH_PATH?t=${running.token}&to=${java.net.URLEncoder.encode(to, "UTF-8")}"
    }

    fun stopAll() {
        servers.values.forEach { runCatching { it.server.close() } }
        servers.clear()
    }

    private fun start(conversationId: String, vmPort: Int): Running {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        ai.antigem.data.security.LocalPorts.add(server.localPort)
        val running = Running(server, conversationId, vmPort)
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { handle(running, socket) }
            }
        }
        Log.i(TAG, "preview of port $vmPort on http://127.0.0.1:${server.localPort}/")
        return running
    }

    private fun handle(r: Running, socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 120_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim()] = line.substring(i + 1).trim()
                }
                // only this preview's own address (DNS rebinding: a website's name pointing at 127.0.0.1 is refused)
                val hostHeader = headers.entries.firstOrNull { it.key.equals("host", true) }?.value?.lowercase()
                val port = r.server.localPort
                if (hostHeader != "127.0.0.1:$port" && hostHeader != "localhost:$port") {
                    write(out, 403, "Forbidden", mapOf("Content-Type" to "text/plain"), "Forbidden".toByteArray())
                    return
                }
                // the link from AntiGem: token → cookie, then on to the page
                if (target.startsWith("$AUTH_PATH?")) {
                    val q = runCatching { URI("http://x$target").rawQuery }.getOrNull().orEmpty().split('&')
                        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                    val to = q["to"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
                    if (!java.security.MessageDigest.isEqual(q["t"].orEmpty().toByteArray(), r.token.toByteArray())) {
                        write(out, 403, "Forbidden", mapOf("Content-Type" to "text/plain"), ai.antigem.i18n.L.agent.cloudPreviewServer.openThePreviewFromAntigemAgain.toByteArray())
                        return
                    }
                    write(out, 302, "Found", mapOf(
                        "Set-Cookie" to "${r.cookieName}=${r.token}; Path=/; HttpOnly; SameSite=Strict",
                        "Location" to to, "Cache-Control" to "no-store"
                    ), ByteArray(0))
                    return
                }
                val cookieHeader = headers.entries.firstOrNull { it.key.equals("cookie", true) }?.value.orEmpty()
                val cookies = cookieHeader.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                val ours = cookies.firstOrNull { it.startsWith("${r.cookieName}=") }?.substringAfter('=').orEmpty()
                if (!java.security.MessageDigest.isEqual(ours.toByteArray(), r.token.toByteArray())) {
                    write(out, 403, "Forbidden", mapOf("Content-Type" to "text/plain"), ai.antigem.i18n.L.agent.cloudPreviewServer.openThePreviewFromAntigemAgain.toByteArray())
                    return
                }
                // the preview's own cookie never goes on to the VM
                val otherCookies = cookies.filterNot { it.startsWith("${r.cookieName}=") || it.startsWith("ag_preview_") }.joinToString("; ")
                headers.keys.filter { it.equals("cookie", true) }.forEach { headers.remove(it) }
                if (otherCookies.isNotEmpty()) headers["Cookie"] = otherCookies
                if (headers.keys.any { it.equals("upgrade", true) }) {
                    write(out, 404, "Not Found", mapOf("Content-Type" to "text/plain"), "Live reload is not available in the cloud preview; reload the page.".toByteArray())
                    return
                }
                val length = headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toIntOrNull() ?: 0
                val body = if (length in 1..MAX_BODY) readExactly(input, length) else null
                val skip = setOf("host", "origin", "referer", "accept-encoding", "connection", "content-length", "if-none-match", "if-modified-since")
                val forward = headers.filterKeys { it.lowercase() !in skip } + ("Accept-Encoding" to "identity")
                val result = runBlocking { AgyCloud.fetchPort(r.conversationId, r.vmPort, target, method, forward, body) }
                result.fold(
                    onSuccess = { resp ->
                        val hdrs = LinkedHashMap<String, String>()
                        resp.headers.forEach { h ->
                            val k = h.name
                            if (k.lowercase() in setOf("content-length", "content-encoding", "transfer-encoding", "connection")) return@forEach
                            var v = h.value_
                            // a redirect to the VM's own address stays on this preview
                            if (k.equals("location", true)) v = localLocation(v)
                            hdrs[k] = hdrs[k]?.let { "$it, $v" } ?: v
                        }
                        val code = resp.status_code.takeIf { it in 100..599 } ?: 200
                        write(out, code, reason(code), hdrs, resp.body.toByteArray())
                    },
                    onFailure = { e ->
                        val html = "<html><body style=\"font-family:sans-serif;padding:24px\"><h3>Could not reach port ${r.vmPort} in the cloud VM</h3>" +
                            "<p>${(e.message ?: "").replace("<", "&lt;")}</p><p>Is the server running (listening on 0.0.0.0)? Reload after starting it.</p></body></html>"
                        write(out, 502, "Bad Gateway", mapOf("Content-Type" to "text/html; charset=utf-8"), html.toByteArray())
                    }
                )
            }.onFailure { Log.w(TAG, "preview request: ${it.message}") }
        }
    }

    private fun localLocation(v: String): String = runCatching {
        val u = URI(v)
        if (u.host == null || u.host in setOf("localhost", "127.0.0.1", "0.0.0.0")) (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "") else v
    }.getOrDefault(v)

    private fun write(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, body: ByteArray) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", " ")).append("\r\n") }
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) return sb.toString()
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Status"
    }
}
