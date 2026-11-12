package com.example.gemini.data.miniapps

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import okhttp3.Dns
import javax.net.SocketFactory
import java.net.UnknownHostException
import java.net.SocketAddress
import java.net.Socket
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * All mini app networking happens here, natively (OkHttp), never in the WebView:
 *  - fetch / XHR / EventSource from JS → [fetch]; the body is streamed back through a one-time local URL
 *  - WebSocket → [wsOpen]
 *  - <img>, CSS, fonts and scripts from https → [proxy] (with an HTTP disk cache, so they work offline later)
 * No CORS, any header. Every request is checked against the app's network / localhost permissions, and a network
 * interceptor re-checks the address actually connected to, so a hostname that resolves to 127.0.0.1 cannot reach
 * local servers without the localhost permission.
 */
object MiniAppNet {
    /** Tags each OkHttp request with the app it is for (read by the guard). */
    class Owner(val context: Context, val app: MiniApp)
    /** Tag for a request that must not send or store cookies (fetch with credentials: "omit"). */
    object NoCookies
    /** Tag for AntiGem's own requests made for an app (the developer tools' scripts): not limited to the app's hosts. */
    object SystemRequest

    // ---------------- where an app may connect ----------------

    @Volatile private var ownAddresses: Pair<Long, Set<InetAddress>> = 0L to emptySet()

    /**
     * This phone: loopback, the any-address, and every address of its own interfaces (a server listening on 0.0.0.0
     * answers on the Wi-Fi address too). Connecting to any of them needs the localhost permission.
     */
    fun isLocalAddress(ip: InetAddress): Boolean {
        if (ip.isLoopbackAddress || ip.isAnyLocalAddress) return true
        val now = System.currentTimeMillis()
        if (now - ownAddresses.first > 10_000) {
            ownAddresses = now to runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }.toSet()
            }.getOrDefault(emptySet())
        }
        return ip in ownAddresses.second
    }

    private fun currentApp(context: Context, app: MiniApp) = MiniApps.find(context, app.id) ?: app

    /**
     * Checked when a socket really connects, for every connection of the app's client: HTTP, WebSocket handshakes
     * (OkHttp runs no network interceptors for those), redirects, and a hostname that resolves to this phone.
     */
    private class GuardedSockets(private val context: Context, private val app: MiniApp) : SocketFactory() {
        private fun check(endpoint: SocketAddress?) {
            val ep = endpoint as? InetSocketAddress ?: return
            val ip = ep.address ?: return
            if (isLocalAddress(ip)) MiniAppPermissions.checkLoopback(context, currentApp(context, app), ep.port)?.let { throw IOException("Blocked: $it") }
        }
        private fun socket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) { check(endpoint); super.connect(endpoint, timeout) }
        }
        override fun createSocket(): Socket = socket()
        override fun createSocket(host: String?, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress?, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            socket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    /** A host outside the manifest's network hosts isn't even looked up: covers redirects and WebSockets too. */
    private class CheckedDns(private val context: Context, private val app: MiniApp) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // names of this phone are left to the socket check, which knows the port
            if (!MiniAppPermissions.isLoopbackHost(hostname)) {
                MiniAppPermissions.checkNetwork(context, currentApp(context, app), hostname, 443)?.let { throw UnknownHostException("Blocked: $it") }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Each hop (a redirect too) must go to a host the app may reach. */
    private val hostGuard = Interceptor { chain ->
        val req = chain.request()
        val owner = req.tag(Owner::class.java)
        if (owner != null && req.tag(SystemRequest::class.java) == null) {
            MiniAppPermissions.checkNetwork(owner.context, currentApp(owner.context, owner.app), req.url.host, req.url.port)?.let { throw IOException("Blocked: ${req.url.host}: $it") }
        }
        chain.proceed(req)
    }

    private class PendingBody(val response: Response, val pageId: String, val created: Long = System.currentTimeMillis())

    private val streams = ConcurrentHashMap<String, PendingBody>()
    private val calls = ConcurrentHashMap<String, Pair<String, Call>>() // rid → (pageId, call)
    private val sockets = ConcurrentHashMap<String, Pair<String, WebSocket>>() // sid → (pageId, socket)
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private var userAgent: String? = null

    private val guard = Interceptor { chain ->
        val owner = chain.request().tag(Owner::class.java)
        val address = chain.connection()?.route()?.socketAddress
        if (owner != null && address != null) {
            val ip = address.address
            if (ip != null && isLocalAddress(ip)) {
                MiniAppPermissions.checkLoopback(owner.context, owner.app, address.port)?.let { throw IOException("Blocked: $it") }
            }
        }
        chain.proceed(chain.request())
    }

    private val jars = ConcurrentHashMap<String, MiniAppCookies>()

    fun cookies(context: Context, appId: String): MiniAppCookies =
        jars.getOrPut(appId) { MiniAppCookies(File(MiniApps.dataDir(context, appId), "cookies.db")) }

    /**
     * Cookies on every hop (redirects too): the app's saved cookies are added to the request, merged with a
     * Cookie header the app set itself (its own values win), and Set-Cookie from each response is stored.
     */
    private val cookieInterceptor = Interceptor { chain ->
        val req = chain.request()
        val owner = req.tag(Owner::class.java)
        if (owner == null || req.tag(NoCookies::class.java) != null) return@Interceptor chain.proceed(req)
        val jar = cookies(owner.context, owner.app.id)
        val saved = jar.forRequest(req.url)
        val out = if (saved.isEmpty()) req else {
            val manual = req.header("Cookie")?.takeIf { it.isNotBlank() }
            val manualNames = manual?.split(';')?.map { it.substringBefore('=').trim() }?.toSet().orEmpty()
            val merged = listOfNotNull(manual) + saved.filter { it.name !in manualNames }.map { "${it.name}=${it.value}" }
            req.newBuilder().header("Cookie", merged.joinToString("; ")).build()
        }
        val response = chain.proceed(out)
        Cookie.parseAll(req.url, response.headers).takeIf { it.isNotEmpty() }?.let { jar.save(it) }
        response
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .addNetworkInterceptor(guard)
            .addNetworkInterceptor(hostGuard)
            .addNetworkInterceptor(cookieInterceptor)
            .build()
    }

    /** One client per app: its own cookies and HTTP cache, its hosts checked before lookup and every socket checked on connect. */
    private fun client(context: Context, app: MiniApp): OkHttpClient = clients.getOrPut(app.id) {
        base.newBuilder()
            .cache(Cache(File(MiniApps.dataDir(context, app.id), "net-cache"), 50L * 1024 * 1024))
            .dns(CheckedDns(context.applicationContext, app))
            .socketFactory(GuardedSockets(context.applicationContext, app))
            .build()
    }

    /** AntiGem's own requests for an app (developer tools): any host, but still never this phone's servers. */
    private fun systemClient(context: Context, app: MiniApp): OkHttpClient = clients.getOrPut("\u0000system:" + app.id) {
        base.newBuilder().socketFactory(GuardedSockets(context.applicationContext, app)).build()
    }

    /**
     * Bytes of a URL fetched as the app (its permissions, hosts, cookies), e.g. a notification's image. Null when the
     * app may not reach it or it fails. Max [maxBytes].
     */
    fun fetchBytes(context: Context, app: MiniApp, url: String, maxBytes: Long = 5L * 1024 * 1024): ByteArray? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (MiniAppPermissions.checkNetwork(context, app, u.host, u.port) != null) return null
        return runCatching {
            client(context, app).newCall(Request.Builder().url(u).tag(Owner::class.java, Owner(context, app)).header("User-Agent", ua(context)).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null || body.contentLength() > maxBytes) null
                else body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > maxBytes) return@use null }
                    out.toByteArray()
                }
            }
        }.getOrNull()
    }

    private fun ua(context: Context): String = userAgent ?: runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)")
        .plus(" AntiGemMiniApp/${MiniApps.SDK_VERSION}").also { userAgent = it }

    private fun check(context: Context, app: MiniApp, url: HttpUrl) {
        MiniAppPermissions.checkNetwork(context, app, url.host, url.port)?.let { throw MiniAppError("NotAllowedError", "Network request to ${url.host} blocked: $it") }
    }

    // ---------------- fetch ----------------

    /**
     * p: {rid, url, method, headers: [[k, v]], body?: base64, redirect?: "follow"|"manual"|"error"}
     * → {status, statusText, headers: [[k, v]], url, redirected, sid?}. The body is read from /_antigem/net/<sid>.
     */
    fun fetch(context: Context, app: MiniApp, pageId: String, p: JSONObject, reply: (Result<Any?>) -> Unit) {
        val url = p.optString("url").toHttpUrlOrNull() ?: return reply(Result.failure(MiniAppError("TypeError", "Invalid URL: ${p.optString("url")}")))
        try { check(context, app, url) } catch (e: MiniAppError) { return reply(Result.failure(e)) }
        val method = p.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url).tag(Owner::class.java, Owner(context, app))
        if (p.optString("credentials") == "omit") builder.tag(NoCookies::class.java, NoCookies)
        var contentType: String? = null
        var hasUa = false
        p.optJSONArray("headers")?.let { h ->
            for (i in 0 until h.length()) {
                val pair = h.optJSONArray(i) ?: continue
                val k = pair.optString(0); val v = pair.optString(1)
                when (k.lowercase()) {
                    // OkHttp only un-gzips when it asked for gzip itself
                    "accept-encoding", "content-length", "host", "connection" -> {}
                    "content-type" -> { contentType = v; builder.header(k, v) }
                    else -> { if (k.equals("user-agent", true)) hasUa = true; runCatching { builder.addHeader(k, v) } }
                }
            }
        }
        if (!hasUa) builder.header("User-Agent", ua(context))
        val bytes = p.optString("body").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }
        val needsBody = method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        val body = when {
            method == "GET" || method == "HEAD" -> null
            bytes != null -> bytes.toRequestBody(contentType?.toMediaTypeOrNull())
            needsBody -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        runCatching { builder.method(method, body) }.onFailure { return reply(Result.failure(MiniAppError("TypeError", it.message ?: "Bad method"))) }
        val redirect = p.optString("redirect", "follow")
        val c = client(context, app).let { if (redirect == "follow") it else it.newBuilder().followRedirects(false).followSslRedirects(false).build() }
        val rid = p.optString("rid").ifBlank { UUID.randomUUID().toString() }
        val call = c.newCall(builder.build())
        calls[rid] = pageId to call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(rid)
                reply(Result.failure(if (call.isCanceled()) MiniAppError("AbortError", "The request was aborted") else MiniAppError("TypeError", "Failed to fetch: ${e.message}")))
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(rid)
                if (redirect == "error" && response.priorResponse != null) {
                    response.close(); reply(Result.failure(MiniAppError("TypeError", "Redirect not allowed"))); return
                }
                val headers = JSONArray()
                // when OkHttp un-gzipped the body it already dropped Content-Encoding / Content-Length
                response.headers.forEach { (k, v) -> headers.put(JSONArray().put(k).put(v)) }
                val noBody = method == "HEAD" || response.code in setOf(101, 204, 205, 304)
                val out = JSONObject()
                    .put("status", response.code)
                    .put("statusText", response.message)
                    .put("headers", headers)
                    .put("url", response.request.url.toString())
                    .put("redirected", response.priorResponse != null)
                if (noBody) response.close() else {
                    val sid = UUID.randomUUID().toString()
                    streams[sid] = PendingBody(response, pageId)
                    out.put("sid", sid)
                }
                reply(Result.success(out))
            }
        })
        sweep()
    }

    fun abort(rid: String) {
        calls.remove(rid)?.second?.cancel()
    }

    /** The response body for /_antigem/net/<sid>, streamed as it arrives. One use only. */
    fun openStream(sid: String): WebResourceResponse? {
        val pending = streams.remove(sid) ?: return null
        val body = pending.response.body ?: return null
        return WebResourceResponse("application/octet-stream", null, 200, "OK", mapOf("Cache-Control" to "no-store"), body.byteStream())
    }

    fun cancelStream(sid: String) { streams.remove(sid)?.response?.close() }

    /** Bodies nobody read within a minute are closed. */
    private fun sweep() {
        val now = System.currentTimeMillis()
        streams.entries.removeIf { (_, v) -> (now - v.created > 60_000).also { old -> if (old) runCatching { v.response.close() } } }
    }

    // ---------------- subresources (<img>, CSS, fonts, scripts) ----------------

    private val ERUDA = Regex("^https://cdn\\.jsdelivr\\.net/npm/eruda(@[^/]+)?(/.*)?$")

    /** A GET for a https subresource of the page, fetched natively. [devtools]: the user opened DevTools, so Eruda may load. */
    fun proxy(context: Context, app: MiniApp, request: WebResourceRequest, devtools: Boolean, log: (String) -> Unit): WebResourceResponse {
        val raw = request.url.toString()
        val url = raw.toHttpUrlOrNull() ?: return error(400, "Bad URL")
        if (!request.method.equals("GET", true)) return error(405, "Only GET subresources")
        val system = devtools && ERUDA.matches(raw)
        if (!system) {
            val reason = MiniAppPermissions.checkNetwork(context, app, url.host, url.port)
            if (reason != null) {
                log("Blocked $raw: $reason")
                return error(403, "Blocked")
            }
        }
        return try {
            val b = Request.Builder().url(url).tag(Owner::class.java, Owner(context, app))
            if (system) b.tag(SystemRequest::class.java, SystemRequest)
            request.requestHeaders?.forEach { (k, v) -> if (!k.equals("accept-encoding", true)) runCatching { b.header(k, v) } }
            if (request.requestHeaders?.keys?.none { it.equals("user-agent", true) } != false) b.header("User-Agent", ua(context))
            val response = (if (system) systemClient(context, app) else client(context, app)).newCall(b.build()).execute()
            val type = response.header("Content-Type").orEmpty()
            val mime = type.substringBefore(';').trim().ifBlank { guessMime(url.encodedPath) }
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
            val headers = LinkedHashMap<String, String>()
            response.headers.names().forEach { n ->
                if (n.equals("content-encoding", true) || n.equals("content-length", true) || n.equals("set-cookie", true)) return@forEach
                headers[n] = response.headers(n).joinToString(", ")
            }
            headers["Access-Control-Allow-Origin"] = app.origin
            // anything that came through here may be embedded by the app
            headers["Cross-Origin-Resource-Policy"] = "cross-origin"
            val code = response.code.let { if (it in 300..399 || it !in 100..599) 502 else it }
            WebResourceResponse(mime, charset, code, response.message.ifBlank { "OK" }, headers, response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)))
        } catch (e: Exception) {
            log("Failed to load $raw: ${e.message}")
            error(502, "Network error")
        }
    }

    private fun error(code: Int, reason: String) = WebResourceResponse("text/plain", "utf-8", code, reason,
        mapOf("Cache-Control" to "no-store", "Cross-Origin-Resource-Policy" to "cross-origin"), ByteArrayInputStream(reason.toByteArray()))

    fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"; "js", "mjs", "cjs" -> "text/javascript"; "css" -> "text/css"; "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "avif" -> "image/avif"; "ico" -> "image/x-icon"
        "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"; "mkv" -> "video/x-matroska"
        "heic", "heics" -> "image/heic"; "heif" -> "image/heif"; "dng" -> "image/x-adobe-dng"
        "wasm" -> "application/wasm"; "txt", "md" -> "text/plain"; "xml" -> "application/xml"; "pdf" -> "application/pdf"; "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    // ---------------- WebSocket ----------------

    /** p: {sid, url, protocols: []}. Events go to [emit] as ("ws", {sid, t: open|msg|close|error, ...}). */
    fun wsOpen(context: Context, app: MiniApp, pageId: String, p: JSONObject, emit: (JSONObject) -> Unit) {
        val sid = p.getString("sid")
        val raw = p.optString("url")
        val httpUrl = raw.replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://").replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://").toHttpUrlOrNull()
            ?: throw MiniAppError("SyntaxError", "Invalid WebSocket URL: $raw")
        check(context, app, httpUrl)
        val b = Request.Builder().url(httpUrl).tag(Owner::class.java, Owner(context, app)).header("User-Agent", ua(context)).header("Origin", app.origin)
        val protocols = p.optJSONArray("protocols")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (protocols.isNotEmpty()) b.header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
        // OkHttp runs no network interceptors for the handshake: the app's cookies are added and stored here
        val jar = cookies(context, app.id)
        jar.forRequest(httpUrl).takeIf { it.isNotEmpty() }?.let { saved -> b.header("Cookie", saved.joinToString("; ") { "${it.name}=${it.value}" }) }
        val ws = client(context, app).newWebSocket(b.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Cookie.parseAll(httpUrl, response.headers).takeIf { it.isNotEmpty() }?.let { jar.save(it) }
                emit(JSONObject().put("sid", sid).put("t", "open").put("protocol", response.header("Sec-WebSocket-Protocol").orEmpty()))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = emit(JSONObject().put("sid", sid).put("t", "msg").put("text", text))
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = emit(JSONObject().put("sid", sid).put("t", "msg").put("b64", bytes.base64()))
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(sid); emit(JSONObject().put("sid", sid).put("t", "close").put("code", code).put("reason", reason).put("clean", true))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(sid)
                emit(JSONObject().put("sid", sid).put("t", "error").put("message", t.message.orEmpty()))
                emit(JSONObject().put("sid", sid).put("t", "close").put("code", 1006).put("reason", "").put("clean", false))
            }
        })
        sockets[sid] = pageId to ws
    }

    fun wsSend(p: JSONObject) {
        val ws = sockets[p.optString("sid")]?.second ?: throw MiniAppError("InvalidStateError", "WebSocket is not open")
        if (p.has("b64")) ws.send(Base64.decode(p.getString("b64"), Base64.DEFAULT).toByteString()) else ws.send(p.optString("text"))
    }

    fun wsClose(p: JSONObject) {
        val code = p.optInt("code", 1000)
        sockets[p.optString("sid")]?.second?.close(if (code == 1000 || code in 3000..4999) code else 1000, p.optString("reason").take(120).ifEmpty { null })
    }

    /** A page closed: cancel its requests, unread bodies and sockets. */
    fun pageClosed(pageId: String) {
        calls.entries.removeIf { (_, v) -> (v.first == pageId).also { if (it) v.second.cancel() } }
        streams.entries.removeIf { (_, v) -> (v.pageId == pageId).also { if (it) runCatching { v.response.close() } } }
        sockets.entries.removeIf { (_, v) -> (v.first == pageId).also { if (it) v.second.cancel() } }
    }

    fun forgetApp(appId: String) {
        clients.remove(appId)
        clients.remove("\u0000system:$appId")
        jars.remove(appId)?.close()
    }
}
