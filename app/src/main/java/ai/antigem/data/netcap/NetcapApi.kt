package ai.antigem.data.netcap

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The engine's JSON API for the Network page (routes under /v1/ui/, see docs/network_interceptor/08-gui.md). The same
 * engine serves the agents' network_mcp, so what an agent changes (capture apps, rules, scripts, breakpoints) shows here
 * live, and the other way round.
 */
object NetcapApi {
    private val json = "application/json".toMediaType()

    class ApiError(message: String) : IOException(message)

    private fun base(): String = "http://127.0.0.1:${NetcapManager.apiPort}"

    /** GET [path] with [params]; the answer as JSON (throws [ApiError] with the engine's message). */
    suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val url = (base() + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) addQueryParameter(k, v.toString()) }
        }.build()
        call(Request.Builder().url(url).header("X-Netcap-Token", NetcapManager.apiToken).get().build())
    }

    /** GET [path] as raw bytes (a paused item's whole body). */
    suspend fun getBytes(path: String, params: Map<String, Any?> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        val url = (base() + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null && v.toString().isNotEmpty()) addQueryParameter(k, v.toString()) }
        }.build()
        NetcapManager.apiHttp.newCall(Request.Builder().url(url).header("X-Netcap-Token", NetcapManager.apiToken).get().build()).execute().use { r ->
            if (!r.isSuccessful) {
                val text = r.body?.string().orEmpty()
                throw ApiError(runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}")
            }
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** POST [body] as JSON to [path]. */
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(base() + path).header("X-Netcap-Token", NetcapManager.apiToken)
            .post(body.toString().toRequestBody(json)).build())
    }

    /** Runs an MCP tool and returns its text (decoder, diff, findings, exports: the same text the agents get). */
    suspend fun tool(name: String, args: JSONObject = JSONObject()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base() + "/v1/tool/$name").header("X-Netcap-Token", NetcapManager.apiToken)
            .post(args.toString().toRequestBody(json)).build()
        NetcapManager.apiHttp.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(text.removePrefix("error: ").trim().ifEmpty { "HTTP ${r.code}" })
            text
        }
    }

    private fun call(req: Request): JSONObject {
        NetcapManager.apiHttp.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: text.trim().ifEmpty { "HTTP ${r.code}" }
                throw ApiError(msg)
            }
            if (text.isBlank()) return JSONObject()
            return if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
        }
    }

    private val liveHttp by lazy {
        NetcapManager.apiHttp.newBuilder().readTimeout(70, TimeUnit.SECONDS).build() // the engine pings every 20 s
    }

    /**
     * The live stream (server-sent events): the state first, then batches of changed rows (in-progress flows too) and
     * the paused list. Ends when the connection drops; the caller reconnects.
     */
    fun live(session: String, filter: String): Flow<LiveEvent> = callbackFlow {
        val url = (base() + "/v1/ui/live").toHttpUrl().newBuilder().apply {
            if (session.isNotEmpty()) addQueryParameter("session", session)
            if (filter.isNotBlank()) addQueryParameter("q", filter)
        }.build()
        val call: Call = liveHttp.newCall(Request.Builder().url(url).header("X-Netcap-Token", NetcapManager.apiToken)
            .header("Accept", "text/event-stream").get().build())
        val reader = thread(name = "netcap-live", isDaemon = true) {
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw ApiError("live: HTTP ${r.code}")
                    val src = r.body?.source() ?: throw ApiError("live: no body")
                    var event = ""
                    val data = StringBuilder()
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    LiveEvent.parse(event, data.toString())?.let { trySend(it) }
                                }
                                event = ""; data.setLength(0)
                            }
                            line.startsWith(":") -> Unit
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trimStart())
                            }
                        }
                    }
                }
                close()
            } catch (e: Throwable) {
                close(if (call.isCanceled()) null else e)
            }
        }
        awaitClose { call.cancel(); reader.interrupt() }
    }.flowOn(Dispatchers.IO)
}

// ---- models ----

@Immutable
data class NetRow(
    val id: Long,
    val kind: String,
    val app: String,
    val start: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String,
    val http: String,
    val status: Int,
    val reqType: String,
    val resType: String,
    val reqSize: Long,
    val resSize: Long,
    val ms: Double,
    val tls: String,
    val error: String,
    val done: Boolean,
    val flags: List<String>,
    val tags: List<String>,
    val messages: Int,
    val pinned: Boolean,
    val color: String,
    val note: String,
    val resentFrom: Long,
    val paused: String
) {
    val url: String
        get() = if (scheme.isEmpty()) "$host:$port" else buildString {
            append(scheme).append("://").append(host)
            if (port != 0 && !((scheme == "https" && port == 443) || (scheme == "http" && port == 80))) append(':').append(port)
            append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    val pathAndQuery: String get() = if (query.isEmpty()) path else "$path?$query"
    val isHttp: Boolean get() = kind == "http" || kind == "grpc" || kind == "ws"

    companion object {
        fun parse(o: JSONObject) = NetRow(
            id = o.optLong("id"),
            kind = o.optString("kind"),
            app = o.optString("app"),
            start = o.optLong("start"),
            method = o.optString("method"),
            scheme = o.optString("scheme"),
            host = o.optString("host"),
            port = o.optInt("port"),
            path = o.optString("path"),
            query = o.optString("query"),
            http = o.optString("http"),
            status = o.optInt("status"),
            reqType = o.optString("req_type"),
            resType = o.optString("res_type"),
            reqSize = o.optLong("req_size"),
            resSize = o.optLong("res_size"),
            ms = o.optDouble("ms", 0.0).let { if (it.isNaN()) 0.0 else it },
            tls = o.optString("tls"),
            error = o.optString("error"),
            done = o.optBoolean("done"),
            flags = o.optJSONArray("flags").strings(),
            tags = o.optJSONArray("tags").strings(),
            messages = o.optInt("messages"),
            pinned = o.optBoolean("pinned"),
            color = o.optString("color"),
            note = o.optString("note"),
            resentFrom = o.optLong("resent_from"),
            paused = o.optString("paused")
        )
    }
}

@Immutable
data class NetCapture(
    val on: Boolean = false,
    val paused: Boolean = false,
    val apps: List<String> = emptyList(),
    val since: String = "",
    val byAgent: Boolean = false
) {
    companion object {
        fun parse(o: JSONObject?) = if (o == null) NetCapture() else NetCapture(
            on = o.optBoolean("on"), paused = o.optBoolean("paused"), apps = o.optJSONArray("apps").strings(),
            since = o.optString("since"), byAgent = o.optBoolean("by_agent")
        )
    }
}

@Immutable
data class NetState(
    val capture: NetCapture = NetCapture(),
    val sessionId: String = "",
    val sessionName: String = "",
    val sessionFlows: Int = 0,
    val diskBytes: Long = 0,
    val paused: Int = 0,
    val rulesOn: Int = 0,
    val scriptsOn: Int = 0,
    val findings: Int = 0
) {
    companion object {
        fun parse(o: JSONObject): NetState {
            val s = o.optJSONObject("session")
            return NetState(
                capture = NetCapture.parse(o.optJSONObject("capture")),
                sessionId = s?.optString("id").orEmpty(),
                sessionName = s?.optString("name").orEmpty(),
                sessionFlows = s?.optInt("flows") ?: 0,
                diskBytes = s?.optLong("disk_bytes") ?: 0,
                paused = o.optInt("paused"),
                rulesOn = o.optInt("rules_on"),
                scriptsOn = o.optInt("scripts_on"),
                findings = o.optInt("findings")
            )
        }
    }
}

@Immutable
data class NetPaused(
    val id: String,
    val rule: String,
    val phase: String,
    val flow: Long,
    val method: String,
    val url: String,
    val status: Int,
    val headers: List<Pair<String, String>>,
    val bodyText: String?,
    val bodyBase64: String?,
    /** Body size (decoded); -1 when not known yet (a streamed body). */
    val bodySize: Long,
    val bodyType: String,
    /** The body is not in the list (over 256 KB): GET /v1/ui/paused/body has it. */
    val bodyCut: Boolean,
    val since: Long,
    val timeoutS: Int,
    val remainingMs: Long,
    /** When this item was read (remainingMs counts from here). */
    val readAt: Long = System.currentTimeMillis()
) {
    val isResponse: Boolean get() = phase == "response"

    companion object {
        fun parse(o: JSONObject) = NetPaused(
            id = o.optString("id"), rule = o.optString("rule"), phase = o.optString("phase"), flow = o.optLong("flow"),
            method = o.optString("method"), url = o.optString("url"), status = o.optInt("status"),
            headers = parseHeaders(o.optJSONArray("headers")),
            bodyText = if (o.has("body_text")) o.optString("body_text") else null,
            bodyBase64 = if (o.has("body_base64")) o.optString("body_base64") else null,
            bodySize = o.optLong("body_size"), bodyType = o.optString("body_type"), bodyCut = o.optBoolean("body_cut"), since = o.optLong("since"),
            timeoutS = o.optInt("timeout_s"), remainingMs = o.optLong("remaining_ms")
        )
    }
}

/** One live message: the state, or a batch of changed rows. */
sealed class LiveEvent {
    data class State(val state: NetState) : LiveEvent()
    data class Batch(
        val session: String,
        val rows: List<NetRow>,
        val removed: List<Long>,
        val paused: List<NetPaused>?,
        val state: NetState?,
        val gap: Boolean
    ) : LiveEvent()

    companion object {
        fun parse(event: String, data: String): LiveEvent? = runCatching {
            val o = JSONObject(data)
            when (event) {
                "state" -> State(NetState.parse(o))
                "batch" -> Batch(
                    session = o.optString("session"),
                    rows = o.optJSONArray("rows").objects().map(NetRow::parse),
                    removed = o.optJSONArray("removed").let { a -> if (a == null) emptyList() else List(a.length()) { a.optLong(it) } },
                    paused = o.optJSONArray("paused")?.objects()?.map(NetPaused::parse),
                    state = o.optJSONObject("state")?.let(NetState::parse),
                    gap = o.optBoolean("gap")
                )
                else -> null
            }
        }.getOrNull()
    }
}

@Immutable
data class NetBodyInfo(
    val present: Boolean,
    val size: Long,
    val decodedSize: Long,
    val truncated: Boolean,
    val type: String,
    val contentType: String,
    val encoding: String,
    val text: Boolean,
    val image: Boolean,
    val lines: Int,
    val prettyLines: Int
) {
    val canPretty: Boolean get() = prettyLines >= 0
    val isJson: Boolean get() = type == "json" || contentType.contains("json")

    companion object {
        fun parse(o: JSONObject?) = NetBodyInfo(
            present = o?.optBoolean("present") == true, size = o?.optLong("size") ?: 0, decodedSize = o?.optLong("decoded_size") ?: 0,
            truncated = o?.optBoolean("truncated") == true, type = o?.optString("type").orEmpty(),
            contentType = o?.optString("content_type").orEmpty(), encoding = o?.optString("encoding").orEmpty(),
            text = o?.optBoolean("text") == true, image = o?.optBoolean("image") == true,
            lines = o?.optInt("lines", -1) ?: -1, prettyLines = o?.optInt("pretty_lines", -1) ?: -1
        )
    }
}

/** A flow with everything the detail screen shows. */
@Immutable
data class NetFlow(
    val row: NetRow,
    val client: String,
    val server: String,
    val reqHeaders: List<Pair<String, String>>,
    val resHeaders: List<Pair<String, String>>,
    val reqTrailers: List<Pair<String, String>>,
    val resTrailers: List<Pair<String, String>>,
    val timings: Map<String, Double>,
    val tlsInfo: Map<String, String>,
    val endTime: String,
    val startTime: String,
    val endpoint: String,
    val bytesIn: Long,
    val bytesOut: Long,
    val req: NetBodyInfo,
    val res: NetBodyInfo
) {
    companion object {
        fun parse(o: JSONObject): NetFlow {
            val f = o.optJSONObject("flow") ?: JSONObject()
            val rowJson = JSONObject(f.toString()).apply {
                put("req_size", f.optJSONObject("req")?.optLong("size") ?: 0)
                put("res_size", f.optJSONObject("res")?.optLong("size") ?: 0)
                put("req_type", f.optJSONObject("req")?.optString("type").orEmpty())
                put("res_type", f.optJSONObject("res")?.optString("type").orEmpty())
                put("ms", f.optJSONObject("timings")?.optDouble("total", 0.0) ?: 0.0)
                runCatching { put("start", java.time.Instant.parse(f.optString("start")).toEpochMilli()) }
            }
            val t = f.optJSONObject("timings")
            val tls = f.optJSONObject("tls_info")
            return NetFlow(
                row = NetRow.parse(rowJson),
                client = f.optString("client"),
                server = f.optString("server"),
                reqHeaders = parseHeaders(f.optJSONArray("req_headers")),
                resHeaders = parseHeaders(f.optJSONArray("res_headers")),
                reqTrailers = parseHeaders(f.optJSONArray("req_trailers")),
                resTrailers = parseHeaders(f.optJSONArray("res_trailers")),
                timings = buildMap { t?.keys()?.forEach { k -> put(k, t.optDouble(k, 0.0)) } },
                tlsInfo = buildMap {
                    tls?.keys()?.forEach { k ->
                        val v = tls.opt(k)
                        put(k, if (v is JSONArray) v.strings().joinToString(", ") else v.toString())
                    }
                },
                endTime = f.optString("end"),
                startTime = f.optString("start"),
                endpoint = f.optString("endpoint"),
                bytesIn = f.optLong("bytes_in"),
                bytesOut = f.optLong("bytes_out"),
                req = NetBodyInfo.parse(o.optJSONObject("req_body")),
                res = NetBodyInfo.parse(o.optJSONObject("res_body"))
            )
        }
    }
}

@Immutable
data class NetRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val match: String,
    val phase: String,
    val type: String,
    val hits: Long,
    val priority: Int,
    /** The whole rule as the engine has it (the editor changes a copy). */
    val raw: JSONObject
) {
    companion object {
        fun parse(o: JSONObject) = NetRule(
            id = o.optString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled"), match = o.optString("match"),
            phase = o.optString("phase"), type = o.optJSONObject("action")?.optString("type").orEmpty(), hits = o.optLong("hits"),
            priority = o.optInt("priority"), raw = o
        )
    }
}

@Immutable
data class NetScript(
    val name: String,
    val file: String,
    val session: Boolean,
    val enabled: Boolean,
    val error: String,
    val match: String,
    val hooks: List<String>,
    val calls: Long,
    val fails: Long,
    val skipped: Long,
    val commands: List<String>
) {
    companion object {
        fun parse(o: JSONObject) = NetScript(
            name = o.optString("name"), file = o.optString("file"), session = o.optBoolean("session"), enabled = o.optBoolean("enabled"),
            error = o.optString("error"), match = o.optString("match"), hooks = o.optJSONArray("hooks").strings(),
            calls = o.optLong("calls"), fails = o.optLong("fails"), skipped = o.optLong("skipped"),
            commands = o.optJSONArray("commands").let { a ->
                if (a == null) emptyList() else List(a.length()) { i -> a.optJSONObject(i)?.optString("name") ?: a.optString(i) }
            }
        )
    }
}

@Immutable
data class NetSession(val id: String, val name: String, val active: Boolean, val flows: Int, val disk: Long, val created: String) {
    companion object {
        fun parse(o: JSONObject) = NetSession(
            id = o.optString("id"), name = o.optString("name"), active = o.optBoolean("active"), flows = o.optInt("flows"),
            disk = o.optLong("disk_bytes"), created = o.optString("created")
        )
    }
}

@Immutable
data class NetGroup(val key: String, val count: Int, val errors: Int, val last: Long, val avgMs: Double) {
    companion object {
        fun parse(o: JSONObject) = NetGroup(
            key = o.optString("key"), count = o.optInt("count"), errors = o.optInt("errors"), last = o.optLong("last"),
            avgMs = o.optDouble("avg_ms", 0.0)
        )
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseHeaders(a: JSONArray?): List<Pair<String, String>> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val kv = a.optJSONArray(i) ?: return@mapNotNull null
        kv.optString(0) to kv.optString(1)
    }

internal fun headersJson(list: List<Pair<String, String>>): JSONArray =
    JSONArray().apply { list.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } }
