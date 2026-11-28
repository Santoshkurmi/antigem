package ai.antigem.netcaptest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** One test result. */
data class Result(val name: String, val ok: Boolean, val ms: Long, val detail: String, val body: String = "") {
    fun json(): JSONObject = JSONObject().put("name", name).put("ok", ok).put("ms", ms).put("detail", detail).put("body", body.take(2000))
}

/**
 * The traffic the Network Interceptor must handle, against netcap-testserver on [server] (and a few public hosts):
 * HTTP/1.1, HTTP/2, h2c, gzip, big bodies, streams, WebSocket, gRPC trailers, bursts, a steady request rate, raw
 * TCP and UDP, DNS, the QUIC refusal, IPv6, and a client that refuses the AntiGem certificate.
 */
class Suite(private val server: String, private val rps: Int, private val seconds: Int) {
    private val https = "https://$server:18443"
    private val http = "http://$server:18080"

    private val cookies = object : CookieJar {
        val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { synchronized(store) { store.addAll(cookies) } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) { store.filter { it.matches(url) } }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 300; maxRequestsPerHost = 300 })
        .cookieJar(cookies)
        .build()
    private val h1 = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private val h2c = client.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
    private val json = "application/json".toMediaType()

    val all = listOf(
        "dns", "public_h2", "public_h1", "https_get", "https_h1", "http_plain", "h2c", "post_json", "form_post", "gzip",
        "big_json", "download", "upload", "sse", "chunked", "slow", "status_500", "redirect", "cookie", "grpc",
        "websocket_tls", "websocket_plain", "burst_h2", "burst_h1", "sustained", "tcp", "udp", "quic", "ipv6",
        "login_me", "config", "untrusted"
    )

    suspend fun run(name: String): Result = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        val r = runCatching { test(name) }.getOrElse { Triple(false, "${it.javaClass.simpleName}: ${it.message}", "") }
        Result(name, r.first, (System.nanoTime() - t0) / 1_000_000, r.second, r.third)
    }

    private fun call(c: OkHttpClient, req: Request): Pair<Response, String> {
        val resp = c.newCall(req).execute()
        val body = resp.body?.string().orEmpty()
        return resp to body
    }

    private fun get(c: OkHttpClient, url: String) = call(c, Request.Builder().url(url).build())

    private suspend fun test(name: String): Triple<Boolean, String, String> = when (name) {
        "dns" -> {
            val a = InetAddress.getAllByName("example.com")
            Triple(a.isNotEmpty(), a.joinToString { it.hostAddress ?: "?" }, "")
        }
        "public_h2" -> {
            val (r, _) = get(client, "https://www.google.com/generate_204")
            Triple(r.code == 204 && r.protocol == Protocol.HTTP_2, "${r.code} ${r.protocol}", "")
        }
        "public_h1" -> {
            val (r, b) = get(h1, "https://example.com/")
            Triple(r.code == 200 && b.contains("Example Domain"), "${r.code} ${r.protocol}", b.take(200))
        }
        "https_get" -> {
            val (r, b) = get(client, "$https/get?x=1&token=secret-token-123")
            Triple(r.code == 200 && r.protocol == Protocol.HTTP_2, "${r.code} ${r.protocol} server saw ${JSONObject(b).optString("proto")}", b)
        }
        "https_h1" -> {
            val (r, b) = get(h1, "$https/get?h1=1")
            Triple(r.code == 200 && r.protocol == Protocol.HTTP_1_1, "${r.code} ${r.protocol}", b)
        }
        "http_plain" -> {
            val (r, b) = get(h1, "$http/get?plain=1")
            Triple(r.code == 200, "${r.code} ${r.protocol}", b)
        }
        "h2c" -> {
            val (r, b) = get(h2c, "$http/get?h2c=1")
            Triple(r.code == 200 && r.protocol == Protocol.H2_PRIOR_KNOWLEDGE, "${r.code} ${r.protocol}", b)
        }
        "post_json" -> {
            val payload = """{"email":"tester@example.com","password":"hunter2","token":"abc123","device":{"model":"Pixel","os":"android"}}"""
            val (r, b) = call(client, Request.Builder().url("$https/post").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlc2lnbmF0dXJl").post(payload.toRequestBody(json)).build())
            Triple(r.code == 200 && JSONObject(b).optInt("len") == payload.length, "${r.code} len ${JSONObject(b).optInt("len")}", b)
        }
        "form_post" -> {
            val (r, b) = call(client, Request.Builder().url("$https/post").post("user=bob&password=hunter2&remember=1".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build())
            Triple(r.code == 200, "${r.code}", b)
        }
        "gzip" -> {
            val (r, b) = get(client, "$https/gzip") // OkHttp asks for gzip and unpacks it
            Triple(r.code == 200 && b.contains("gzipped"), "${r.code} ${b.length} chars", b.take(100))
        }
        "big_json" -> {
            val (r, b) = get(client, "$https/json?n=500")
            Triple(r.code == 200 && JSONObject(b).optInt("total") == 500, "${r.code} ${b.length} bytes", "")
        }
        "download" -> {
            val size = 20L * 1024 * 1024
            val resp = client.newCall(Request.Builder().url("$https/download?size=$size").build()).execute()
            var n = 0L
            resp.body!!.byteStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val k = s.read(buf); if (k < 0) break; n += k } }
            Triple(resp.code == 200 && n == size, "${resp.code} got $n of $size bytes", "")
        }
        "upload" -> {
            val data = ByteArray(5 * 1024 * 1024) { (it * 31).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val (r, b) = call(client, Request.Builder().url("$https/upload").post(data.toRequestBody("application/octet-stream".toMediaType())).build())
            val o = JSONObject(b)
            Triple(r.code == 200 && o.optLong("len") == data.size.toLong() && o.optString("sha256") == sha, "${r.code} server got ${o.optLong("len")} bytes, sha ok ${o.optString("sha256") == sha}", "")
        }
        "sse" -> {
            val resp = client.newCall(Request.Builder().url("$https/sse?n=20&ms=30").build()).execute()
            val events = resp.body!!.source().let { src -> var c = 0; while (true) { val line = src.readUtf8Line() ?: break; if (line.startsWith("event: tick")) c++ }; c }
            Triple(events == 20, "$events events", "")
        }
        "chunked" -> {
            val (r, b) = get(h1, "$https/chunked?n=30&ms=10")
            Triple(r.code == 200 && b.lines().count { it.startsWith("line ") } == 30, "${r.code} ${b.lines().size - 1} lines", "")
        }
        "slow" -> {
            val (r, _) = get(client, "$https/slow?ms=1500")
            Triple(r.code == 200, "${r.code}", "")
        }
        "status_500" -> {
            val (r, b) = get(client, "$https/status?code=500")
            Triple(r.code == 500, "${r.code}", b)
        }
        "redirect" -> {
            val (r, b) = get(client, "$https/redirect")
            Triple(r.code == 200 && b.contains("redirect"), "${r.code} ${r.request.url.encodedPath}", b)
        }
        "cookie" -> {
            get(client, "$https/cookie")
            val (r, b) = get(client, "$https/cookie")
            Triple(r.code == 200 && b.contains("session=sess-"), "${r.code}", b)
        }
        "grpc" -> {
            val frame = byteArrayOf(0, 0, 0, 0, 2, 0x08, 0x01)
            val resp = client.newCall(Request.Builder().url("$https/grpc").header("TE", "trailers")
                .post(frame.toRequestBody("application/grpc".toMediaType())).build()).execute()
            val bytes = resp.body!!.bytes()
            val trailers = resp.trailers()
            Triple(resp.code == 200 && trailers["grpc-status"] == "0" && bytes.size == 19,
                "${resp.code} ${resp.protocol} ${bytes.size} bytes grpc-status=${trailers["grpc-status"]}", "")
        }
        "websocket_tls" -> websocket("wss://$server:18443/ws")
        "websocket_plain" -> websocket("ws://$server:18080/ws")
        "burst_h2" -> burst(client, 200)
        "burst_h1" -> burst(h1, 100)
        "sustained" -> sustained()
        "tcp" -> {
            Socket().use { s ->
                s.connect(InetSocketAddress(server, 18081), 10_000)
                s.soTimeout = 10_000
                val r = s.getInputStream().bufferedReader()
                val greet = r.readLine()
                s.getOutputStream().write("HELO netcaptest\r\n".toByteArray())
                val reply = r.readLine()
                Triple(greet.startsWith("220") && reply == "250 HELO netcaptest", "$greet / $reply", "")
            }
        }
        "udp" -> {
            DatagramSocket().use { s ->
                s.soTimeout = 5000
                val msg = "ping ${System.nanoTime()}".toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(server), 18053))
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                Triple(String(buf, 0, p.length) == String(msg), "echo ${p.length} bytes", "")
            }
        }
        "quic" -> {
            // UDP 443 is refused by the interceptor ("port unreachable"), so apps fall back to TCP at once
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                s.connect(InetSocketAddress("1.1.1.1", 443))
                val msg = ByteArray(1200) { 0x40 }
                val t0 = System.currentTimeMillis()
                try {
                    s.send(DatagramPacket(msg, msg.size))
                    s.receive(DatagramPacket(ByteArray(1500), 1500))
                    Triple(false, "got an answer: QUIC was not blocked", "")
                } catch (e: PortUnreachableException) {
                    Triple(true, "refused after ${System.currentTimeMillis() - t0} ms", "")
                } catch (e: SocketTimeoutException) {
                    Triple(false, "no answer and no refusal (dropped)", "")
                }
            }
        }
        "ipv6" -> {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(InetAddress.getByName("2001:4860:4860::8888"), 443), 5000) }
                Triple(true, "connected over IPv6 in ${System.currentTimeMillis() - t0} ms", "")
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - t0
                Triple(ms < 4000, "no IPv6: ${e.javaClass.simpleName} after $ms ms (fast failure = OK)", "")
            }
        }
        "login_me" -> {
            val (r1, b1) = call(client, Request.Builder().url("$https/login").post("""{"user":"bob","password":"hunter2"}""".toRequestBody(json)).build())
            val token = JSONObject(b1).optString("token")
            val (r2, b2) = call(client, Request.Builder().url("$https/me").header("Authorization", "Bearer $token").build())
            Triple(r1.code == 200 && r2.code == 200, "login ${r1.code}, me ${r2.code}", b2)
        }
        "config" -> {
            val (r, b) = get(client, "$https/config")
            Triple(r.code == 200, "${r.code}", b)
        }
        "edit" -> {
            val (r, b) = call(client, Request.Builder().url("$https/edit").header("X-Test", "original")
                .post("""{"amount":5,"currency":"EUR"}""".toRequestBody(json)).build())
            Triple(r.code == 200, "${r.code}", b)
        }
        "untrusted" -> untrusted()
        // these two need the phone-test script loaded (net_script), see docs/network_interceptor/NEXT.md
        "script_http" -> scriptHttp()
        "script_ws" -> scriptWs()
        else -> Triple(false, "unknown test", "")
    }

    /** A script changes requests and answers: a header and a JSON field each way, plus an answer of its own. */
    private fun scriptHttp(): Triple<Boolean, String, String> {
        val (r1, b1) = call(client, Request.Builder().url("$https/post?script=1").post("""{"a":1}""".toRequestBody(json)).build())
        val j1 = JSONObject(b1)
        val post = r1.header("X-Script") == "on" && j1.optBoolean("scripted") && j1.optJSONObject("json")?.optBoolean("added_by_script") == true
        val (r2, b2) = get(h1, "$https/get?script=1")
        val j2 = JSONObject(b2)
        val getOk = r2.header("X-Script") == "on" && j2.optBoolean("scripted") && j2.optString("auth") == "Bearer from-script"
        val (r3, b3) = get(client, "$https/get?script=1&mock=1")
        val mock = r3.code == 202 && JSONObject(b3).optString("mocked") == "by script"
        return Triple(post && getOk && mock, "post $post (${r1.protocol}), get $getOk (${r2.protocol}), mock $mock (${r3.code})", b1.take(300))
    }

    /** A script changes WebSocket messages both ways, drops one and answers it itself. */
    private fun scriptWs(): Triple<Boolean, String, String> {
        val got = mutableListOf<String>()
        val done = CountDownLatch(1)
        val ws = client.newWebSocket(Request.Builder().url("wss://$server:18443/ws?script=1").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { synchronized(got) { got.add(text); if (got.size >= 3) done.countDown() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { synchronized(got) { got.add("failure: ${t.message}") }; done.countDown() }
        })
        ws.send("hello")
        ws.send("drop-me")
        val finished = done.await(10, TimeUnit.SECONDS)
        Thread.sleep(300) // an "echo:drop-me" would arrive now if the drop failed
        ws.close(1000, "bye")
        val texts = synchronized(got) { got.toList() }
        val welcome = texts.any { it.contains("welcome") && JSONObject(it).optBoolean("seen_by_script") }
        val ok = finished && welcome && "echo:HELLO" in texts && "dropped by script" in texts && texts.none { it.contains("drop-me") }
        return Triple(ok, "${texts.size} messages: " + texts.joinToString(" | ").take(300), "")
    }

    private fun websocket(url: String): Triple<Boolean, String, String> {
        val got = mutableListOf<String>()
        val done = CountDownLatch(1)
        val want = 1 + 10 + 5
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { synchronized(got) { got.add(text); if (got.size >= want) done.countDown() } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { synchronized(got) { got.add("bin:" + bytes.size); if (got.size >= want) done.countDown() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { synchronized(got) { got.add("failure: ${t.message}") }; done.countDown() }
        })
        for (i in 0 until 10) ws.send("""{"type":"chat","n":$i,"text":"${"hello ".repeat(20)}"}""")
        for (i in 0 until 5) ws.send(ByteArray(300) { (it + i).toByte() }.toByteString())
        val finished = done.await(15, TimeUnit.SECONDS)
        ws.close(1000, "bye")
        val texts = synchronized(got) { got.toList() }
        val ok = finished && texts.size >= want && texts.count { it.startsWith("echo:") } == 10 && texts.count { it == "bin:300" } == 5
        return Triple(ok, "${texts.size} messages back", texts.take(3).joinToString(" | ").take(300))
    }

    private suspend fun burst(c: OkHttpClient, n: Int): Triple<Boolean, String, String> = coroutineScope {
        val t0 = System.currentTimeMillis()
        val codes = (0 until n).map { i ->
            async(Dispatchers.IO) { runCatching { c.newCall(Request.Builder().url("$https/get?burst=$i").build()).execute().use { it.code } }.getOrDefault(-1) }
        }.awaitAll()
        val ok = codes.count { it == 200 }
        Triple(ok == n, "$ok/$n ok in ${System.currentTimeMillis() - t0} ms", "")
    }

    private suspend fun sustained(): Triple<Boolean, String, String> = coroutineScope {
        val total = rps * seconds
        val fails = AtomicInteger()
        val errors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val latencies = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val t0 = System.currentTimeMillis()
        val jobs = (0 until total).map { i ->
            async(Dispatchers.IO) {
                delay((i * 1000L / rps) - (System.currentTimeMillis() - t0)) // paced: rps requests every second
                val s = System.nanoTime()
                val res = runCatching { client.newCall(Request.Builder().url("$https/get?steady=$i").build()).execute().use { it.code } }
                latencies.add((System.nanoTime() - s) / 1_000_000)
                if (res.getOrNull() != 200) {
                    fails.incrementAndGet()
                    val why = res.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "status ${res.getOrNull()}"
                    errors.getOrPut(why.take(120)) { AtomicInteger() }.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        val sorted = latencies.sorted()
        val p50 = sorted.getOrElse(sorted.size / 2) { 0 }
        val p95 = sorted.getOrElse(sorted.size * 95 / 100) { 0 }
        val why = errors.entries.sortedByDescending { it.value.get() }.take(4).joinToString(" | ") { "${it.value.get()}× ${it.key}" }
        Triple(fails.get() == 0, "$total requests at $rps/s: ${fails.get()} failed, p50 ${p50} ms, p95 ${p95} ms, max ${sorted.lastOrNull()} ms", why)
    }

    /** A client that does not trust the AntiGem certificate (like an app with its own trust): the first try must fail,
     *  later ones pass through without decryption and work. */
    private fun untrusted(): Triple<Boolean, String, String> {
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val own = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        var skipped = 0
        for (alias in system.aliases()) {
            val c = system.getCertificate(alias) as X509Certificate
            if (c.subjectX500Principal.name.contains("AntiGem")) { skipped++; continue }
            own.setCertificateEntry(alias, c)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(own) }
        val tm = tmf.trustManagers[0] as X509TrustManager
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val strict = client.newBuilder().sslSocketFactory(ctx.socketFactory, tm).connectionPool(okhttp3.ConnectionPool()).build()
        val first = runCatching { strict.newCall(Request.Builder().url("https://example.com/").build()).execute().use { it.code } }
        Thread.sleep(300)
        val second = runCatching { strict.newCall(Request.Builder().url("https://example.com/").build()).execute().use { it.code } }
        val firstFailed = first.exceptionOrNull() is SSLException
        // fresh: the first try fails, the next passes through; already known: both pass through
        val ok = (firstFailed || first.getOrNull() == 200) && second.getOrNull() == 200
        return Triple(ok, "AntiGem CA left out: $skipped; first: ${first.exceptionOrNull()?.javaClass?.simpleName ?: first.getOrNull()}, second: ${second.getOrNull() ?: second.exceptionOrNull()?.message}", "")
    }
}
