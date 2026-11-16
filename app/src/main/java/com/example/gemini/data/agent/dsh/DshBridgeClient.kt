package com.example.gemini.data.agent.dsh

import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.bool
import com.example.gemini.data.agent.opencode.long
import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

val DshJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true }

/** DeepSeek Harness is turned off on the bridge, or the bridge has no dsh support yet. */
class DshUnavailableException(message: String, val disabled: Boolean = false, val outdated: Boolean = false) : Exception(message)

/** An error dsh answered (a JSON-RPC error), or the bridge could not reach dsh at all ([unavailable]). */
class DshRpcException(message: String, val code: Int? = null, val unavailable: Boolean = false) : Exception(message)

/** The bridge's `/api/dsh/status` answer. */
@Serializable
data class DshStatus(
    val success: Boolean = false,
    val installed: Boolean = false,
    val running: Boolean = false,
    val starting: Boolean = false,
    val version: String? = null,
    val node_version: String? = null,
    val error: String? = null,
    val logs: List<String>? = null,
    val uptime_seconds: Long? = null,
    /** "provider/model" the running dsh was started with. */
    val route: String? = null,
    val pending_requests: Int? = null,
    /** sessionId → "running" | "idle" (sessions this dsh process has seen). */
    val statuses: Map<String, String>? = null,
    /** App permission mode → dsh preset (Android: only "full"). */
    val presets: Map<String, String>? = null,
    val provider: String? = null,
    val model: String? = null,
    val permission: String? = null,
    /** The configured provider has its API key (null when the bridge did not say). */
    val has_key: Boolean? = null,
    val platform: String? = null,
    val glibc_ok: Boolean? = null,
    /** The bridge runs a dsh given with --dsh-bin (install / update are done outside the app). */
    val custom_bin: String? = null,
    val job: DshCliJob? = null
)

/** An install / update / remove of dsh running on the bridge (its log grows while it runs). */
@Serializable
data class DshCliJob(
    val kind: String = "",
    val running: Boolean = false,
    val exit_code: Int? = null,
    val log: String = ""
)

/** A model route dsh can use with an API key (the bridge's provider catalog). */
@Serializable
data class DshProvider(
    val id: String = "",
    val name: String = "",
    val env: String = "",
    val default_model: String? = null,
    val key_url: String? = null
)

/** One MCP server mounted into every dsh session. */
@Serializable
data class DshMcpServer(
    val name: String = "",
    /** "stdio" or "streamable-http". */
    val transport: String = "stdio",
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val disabled: Boolean = false
)

/** What the app set for dsh (`/api/dsh/config`); keys are only reported as set, never sent back. */
@Serializable
data class DshConfig(
    val provider: String = "",
    val model: String = "",
    val reasoning_effort: String = "",
    val max_tokens: Int = 0,
    val permission: String = "",
    val keys_set: List<String> = emptyList(),
    val mcp: List<DshMcpServer> = emptyList(),
    val providers: List<DshProvider> = emptyList(),
    val platform: String = ""
)

@Serializable
private data class ConfigResponse(
    val success: Boolean = false,
    val error: String? = null,
    val config: DshConfig? = null,
    val restart_required: Boolean = false
)

@Serializable
private data class CliJobResponse(val success: Boolean = false, val error: String? = null, val job: DshCliJob? = null)

@Serializable
private data class SimpleResponse(val success: Boolean = false, val error: String? = null)

/** A config change saved on the bridge: the config now, and whether the running dsh must restart to use it. */
data class DshConfigSaved(val config: DshConfig?, val restartRequired: Boolean)

/** One frame of the bridge's dsh event stream (see the bridge's pkg/dsh). */
sealed class DshSocketEvent {
    data object Opened : DshSocketEvent()
    /** `bridge_state` (attached / starting / spawned / exited / installed) or `bridge_line` (one dsh notification or request). */
    data class Frame(val frame: JsonObject) : DshSocketEvent()
    data class Closed(val error: String?, val disabled: Boolean = false, val outdated: Boolean = false) : DshSocketEvent()
}

/**
 * Talks to the bridge's `/api/dsh/` endpoints. The bridge runs one `dsh --profile sdk` (DeepSeek Harness' JSON-RPC
 * SDK protocol) for every dsh chat: [rpc] calls one of its methods, [events] follows every notification and request
 * it sends, and [reply] answers a request (approval, question). Chats are listed and read from dsh's own files, so
 * [sessions] and [transcript] work before dsh is started.
 */
class DshBridgeClient(private val bridge: AgyBridgeService = AgyBridgeService.instance) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/dsh" + path

    /** A cold dsh start can take a while on a phone (the bridge waits up to 4 min for an SDK call). */
    private val client by lazy { bridge.client.newBuilder().readTimeout(300, TimeUnit.SECONDS).build() }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val isJson = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            if (resp.code == 404 && !isJson) throw DshUnavailableException("This server has no DeepSeek Harness support yet. Update and restart the server.", outdated = true)
            if (resp.code == 503 && text.contains("\"disabled\":true")) {
                throw DshUnavailableException("The server was started with DeepSeek Harness turned off. Restart it to use DeepSeek Harness.", disabled = true)
            }
            if (!resp.isSuccessful && !isJson) error(text.trim().take(200).ifBlank { "HTTP ${resp.code}" })
            text
        }
    }

    private fun post(path: String, body: JsonElement) =
        Request.Builder().url(url(path)).post(DshJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()

    private fun get(path: String) = Request.Builder().url(url(path)).get().build()

    /** A `{success, error}` answer as an object; fails with its error when success is not true. */
    private fun checked(text: String, fallback: String): JsonObject {
        val o = DshJson.parseToJsonElement(text).obj() ?: error("bad answer from the server")
        if (o.bool("success") != true) error(o.str("error")?.take(400) ?: fallback)
        return o
    }

    // ---- SDK protocol (relayed)

    /** Calls one SDK method (starting dsh first when needed); its result (JsonNull when it has none). */
    suspend fun rpc(method: String, params: JsonElement? = null, timeoutMs: Long? = null): Result<JsonElement> = runCatching {
        val body = buildJsonObject {
            put("method", method)
            put("params", params ?: JsonObject(emptyMap()))
            timeoutMs?.let { put("timeout_ms", it) }
        }
        val o = DshJson.parseToJsonElement(execute(post("/rpc", body))).obj() ?: error("bad answer from the server")
        if (o.bool("success") != true) {
            throw DshRpcException(
                o.str("error")?.take(400) ?: "DeepSeek Harness did not answer",
                code = o.long("code")?.toInt(),
                unavailable = o.bool("dsh_unavailable") == true
            )
        }
        o["result"] ?: JsonNull
    }

    /** Answers a waiting request (approval, question) with [result]. */
    suspend fun reply(key: String, result: JsonElement): Result<Unit> = runCatching {
        checked(execute(post("/requests/$key/reply", buildJsonObject { put("result", result) })), "DeepSeek Harness did not take the answer")
    }

    /**
     * dsh's live events (every chat) from bridge sequence number [since] on (-1: only new ones). The flow ends when
     * the socket closes; the caller reconnects with the last sequence number it saw.
     */
    fun events(since: Long): Flow<DshSocketEvent> = callbackFlow {
        val wsUrl = url("/ws?since=$since").replaceFirst("http", "ws")
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(DshSocketEvent.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { DshJson.parseToJsonElement(text).obj() }.getOrNull()?.let { trySend(DshSocketEvent.Frame(it)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(DshSocketEvent.Closed(null))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.peekBody(4096)?.string() }.getOrNull().orEmpty()
                trySend(
                    DshSocketEvent.Closed(
                        t.message ?: "connection failed",
                        disabled = response?.code == 503 && body.contains("\"disabled\":true"),
                        outdated = response?.code == 404 && !body.trimStart().startsWith("{")
                    )
                )
                close()
            }
        }
        val ws = bridge.wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        awaitClose { ws.close(1000, null) }
        // never drop a frame: a lost turn/end would leave the chat running forever
    }.buffer(Channel.UNLIMITED)

    // ---- bridge endpoints

    suspend fun status(): Result<DshStatus> = runCatching {
        DshJson.decodeFromString<DshStatus>(execute(get("/status")))
    }

    /** Starts ("start"), restarts ("restart") or stops ("stop") the dsh process. */
    suspend fun control(action: String): Result<Unit> = runCatching {
        val r = DshJson.decodeFromString<SimpleResponse>(execute(post("/$action", JsonObject(emptyMap()))))
        if (!r.success) error(r.error ?: "DeepSeek Harness did not $action")
    }

    /** Starts installing ("install"), updating ("update") or removing ("remove") dsh on the server. */
    suspend fun startCliJob(action: String): Result<DshCliJob> = runCatching {
        val r = DshJson.decodeFromString<CliJobResponse>(execute(post("/cli/$action", JsonObject(emptyMap()))))
        if (!r.success) error(r.error ?: "Could not start the $action")
        r.job ?: DshCliJob(kind = action, running = true)
    }

    suspend fun cliJob(): Result<DshCliJob?> = runCatching {
        DshJson.decodeFromString<CliJobResponse>(execute(get("/cli/job"))).job
    }

    suspend fun config(): Result<DshConfig> = runCatching {
        val r = DshJson.decodeFromString<ConfigResponse>(execute(get("/config")))
        if (!r.success) error(r.error ?: "Could not read the settings")
        r.config ?: DshConfig()
    }

    /** Saves any subset of {provider, model, reasoning_effort, max_tokens, permission, keys, mcp}. */
    suspend fun saveConfig(body: JsonObject): Result<DshConfigSaved> = runCatching {
        val r = DshJson.decodeFromString<ConfigResponse>(execute(post("/config", body)))
        if (!r.success) error(r.error ?: "Could not save the settings")
        DshConfigSaved(r.config, r.restart_required)
    }

    /** Every model of every provider with a key (needs dsh running; [start] starts it first). */
    suspend fun models(start: Boolean = false): Result<List<JsonObject>> = runCatching {
        val o = checked(execute(get(if (start) "/models?start=1" else "/models")), "Could not load the models")
        o.a("models").orEmpty().mapNotNull { it.obj() }
    }

    /** Stops the session's running turn. */
    suspend fun cancel(sessionId: String): Result<Unit> = runCatching {
        checked(execute(post("/cancel", buildJsonObject { put("session_id", sessionId) })), "Could not stop")
    }

    /** Switches one chat's model (new ids too: dsh creates the session). */
    suspend fun setModel(sessionId: String, provider: String, model: String, effort: String?): Result<Unit> = runCatching {
        checked(execute(post("/model", buildJsonObject {
            put("session_id", sessionId); put("provider", provider); put("model", model)
            effort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
        })), "Could not switch the model")
    }

    /** Saved chats, newest first (works without dsh running). */
    suspend fun sessions(): Result<List<JsonObject>> = runCatching {
        checked(execute(get("/sessions")), "Could not list the chats").a("sessions").orEmpty().mapNotNull { it.obj() }
    }

    /** A chat's session events (the header line first). */
    suspend fun transcript(sessionId: String): Result<List<JsonObject>> = runCatching {
        checked(execute(get("/transcript?session=" + URLEncoder.encode(sessionId, "UTF-8"))), "Could not load this chat")
            .a("events").orEmpty().mapNotNull { it.obj() }
    }

    /** rename {title} ("" resets), archive {archived}, delete. */
    suspend fun sessionAction(sessionId: String, action: String, body: JsonObject = JsonObject(emptyMap())): Result<Unit> = runCatching {
        checked(execute(post("/sessions/" + URLEncoder.encode(sessionId, "UTF-8") + "/$action", body)), "Could not $action the chat")
    }

    /** Saves a file on the server (the bridge's `/api/upload`: `<project>/.gemini/attachments`); its path there. */
    suspend fun upload(fileName: String, base64: String, projectPath: String): Result<String> = runCatching {
        val body = buildJsonObject {
            put("filename", JsonPrimitive(fileName))
            put("base64Data", JsonPrimitive(base64))
            if (projectPath.isNotBlank()) put("projectPath", JsonPrimitive(projectPath))
        }
        val req = Request.Builder().url(AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/upload")
            .post(DshJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()
        DshJson.parseToJsonElement(execute(req)).obj().str("path") ?: error("the server did not save the file")
    }
}
