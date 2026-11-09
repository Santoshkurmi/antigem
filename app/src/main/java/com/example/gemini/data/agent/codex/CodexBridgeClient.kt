package com.example.gemini.data.agent.codex

import com.example.gemini.data.agent.opencode.bool
import com.example.gemini.data.agent.opencode.long
import com.example.gemini.data.agent.opencode.o
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
import java.util.concurrent.TimeUnit

val CodexJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true }

/** Codex is turned off on the bridge, or the bridge has no Codex support yet. */
class CodexUnavailableException(message: String, val disabled: Boolean = false, val outdated: Boolean = false) : Exception(message)

/** An error Codex answered (a JSON-RPC error), or the bridge could not reach Codex at all ([unavailable]). */
class CodexRpcException(message: String, val code: Int? = null, val unavailable: Boolean = false) : Exception(message)

@Serializable
data class CodexStatus(
    val success: Boolean = false,
    val installed: Boolean = false,
    val running: Boolean = false,
    val starting: Boolean = false,
    val binary: String? = null,
    val version: String? = null,
    val error: String? = null,
    val logs: List<String>? = null,
    val uptime_seconds: Long? = null,
    /** Codex comes from the Termux package repository (`pkg install codex`). */
    val termux_package: Boolean = false
)

/** An install / update of Codex running on the bridge (its log grows while it runs). */
@Serializable
data class CodexCliJob(
    val kind: String = "",
    val running: Boolean = false,
    val exit_code: Int? = null,
    val log: String = ""
)

@Serializable
private data class CliJobResponse(val success: Boolean = false, val error: String? = null, val job: CodexCliJob? = null)

@Serializable
private data class SimpleResponse(val success: Boolean = false, val error: String? = null)

/** A turn's model and token use as Codex recorded them (history has neither). */
data class CodexTurnMeta(
    val model: String?,
    val effort: String?,
    val usage: com.example.gemini.domain.model.TokenUsage?,
    val durationMs: Long?
)

/** One frame of the bridge's Codex event stream (see the bridge's pkg/codex). */
sealed class CodexSocketEvent {
    data object Opened : CodexSocketEvent()
    /** `bridge_state` (attached / spawned / exited) or `bridge_line` (one Codex notification or request). */
    data class Frame(val frame: JsonObject) : CodexSocketEvent()
    data class Closed(val error: String?, val disabled: Boolean = false, val outdated: Boolean = false) : CodexSocketEvent()
}

/**
 * Talks to the bridge's `/api/codex/` endpoints. The bridge runs one `codex app-server` (OpenAI's JSON-RPC protocol,
 * the one the VSCode extension speaks) for every Codex chat: [rpc] calls one of its methods, [events] follows every
 * notification and request it sends, and [reply] answers a request (approval, question).
 */
class CodexBridgeClient(private val bridge: AgyBridgeService = AgyBridgeService.instance) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/codex" + path

    /** A cold app-server start can take a while on a phone (the bridge waits up to 45 s). */
    private val client by lazy { bridge.client.newBuilder().readTimeout(150, TimeUnit.SECONDS).build() }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val isJson = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            if (resp.code == 404 && !isJson) throw CodexUnavailableException("This server has no Codex support yet. Update and restart the server.", outdated = true)
            if (resp.code == 503 && text.contains("\"disabled\":true")) {
                throw CodexUnavailableException("The server was started with Codex turned off. Restart it to use Codex.", disabled = true)
            }
            if (!resp.isSuccessful && !isJson) error(text.trim().take(200).ifBlank { "HTTP ${resp.code}" })
            text
        }
    }

    private fun post(path: String, body: JsonElement) =
        Request.Builder().url(url(path)).post(CodexJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()

    // ---- Codex protocol (relayed)

    /** Calls one app-server method; its result (JsonNull when it has none). */
    suspend fun rpc(method: String, params: JsonElement? = null, timeoutMs: Long? = null): Result<JsonElement> = runCatching {
        val body = buildJsonObject {
            put("method", method)
            put("params", params ?: JsonObject(emptyMap()))
            timeoutMs?.let { put("timeout_ms", it) }
        }
        val o = CodexJson.parseToJsonElement(execute(post("/rpc", body))).obj() ?: error("bad answer from the server")
        if (o.bool("success") != true) {
            throw CodexRpcException(
                o.str("error")?.take(400) ?: "Codex did not answer",
                code = o.long("code")?.toInt(),
                unavailable = o.bool("codex_unavailable") == true
            )
        }
        o["result"] ?: JsonNull
    }

    /** Answers a waiting request (approval, question) with [result]. */
    suspend fun reply(key: String, result: JsonElement): Result<Unit> = runCatching {
        val o = CodexJson.parseToJsonElement(execute(post("/requests/$key/reply", buildJsonObject { put("result", result) }))).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "Codex did not take the answer")
    }

    /**
     * Codex's live events (every chat) from bridge sequence number [since] on (-1: only new ones). The flow ends when
     * the socket closes; the caller reconnects with the last sequence number it saw.
     */
    fun events(since: Long): Flow<CodexSocketEvent> = callbackFlow {
        val wsUrl = url("/ws?since=$since").replaceFirst("http", "ws")
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(CodexSocketEvent.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { CodexJson.parseToJsonElement(text).obj() }.getOrNull()?.let { trySend(CodexSocketEvent.Frame(it)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(CodexSocketEvent.Closed(null))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.peekBody(4096)?.string() }.getOrNull().orEmpty()
                trySend(
                    CodexSocketEvent.Closed(
                        t.message ?: "connection failed",
                        disabled = response?.code == 503 && body.contains("\"disabled\":true"),
                        outdated = response?.code == 404
                    )
                )
                close()
            }
        }
        val ws = bridge.wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        awaitClose { ws.close(1000, null) }
        // never drop a frame: a lost turn/completed would leave the chat running forever
    }.buffer(Channel.UNLIMITED)

    /** Each turn's model, effort, token use and duration (read by the bridge from the thread's record on disk). */
    suspend fun turnsMeta(threadId: String): Result<Map<String, CodexTurnMeta>> = runCatching {
        val o = CodexJson.parseToJsonElement(execute(Request.Builder().url(url("/turns-meta?thread=" + java.net.URLEncoder.encode(threadId, "UTF-8"))).get().build())).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "no turn data")
        parseTurnsMeta(o)
    }

    companion object {
        /** The bridge's turns-meta answer → turn id → model, effort, usage and duration. */
        fun parseTurnsMeta(o: JsonObject?): Map<String, CodexTurnMeta> =
            o.o("turns")?.mapValues { (_, v) ->
                val t = v.obj()
                val u = t.o("usage")
                val model = t.str("model")?.ifBlank { null }
                CodexTurnMeta(
                    model = model,
                    effort = t.str("effort")?.ifBlank { null },
                    usage = u?.let {
                        val input = it.long("input_tokens") ?: 0
                        val cached = it.long("cached_input_tokens") ?: 0
                        com.example.gemini.domain.model.TokenUsage(
                            promptTokens = (input - cached).coerceAtLeast(0).toInt(),
                            outputTokens = (it.long("output_tokens") ?: 0).toInt(),
                            cachedTokens = cached.toInt(),
                            cacheCreationTokens = (it.long("cache_write_input_tokens") ?: 0).toInt(),
                            totalTokens = (it.long("total_tokens") ?: 0).toInt(),
                            durationMs = t.long("duration_ms") ?: 0L,
                            model = model
                        )
                    },
                    durationMs = t.long("duration_ms")
                )
            }.orEmpty()
    }

    // ---- bridge endpoints

    suspend fun status(): Result<CodexStatus> = runCatching {
        CodexJson.decodeFromString<CodexStatus>(execute(Request.Builder().url(url("/status")).get().build()))
    }

    suspend fun restart(): Result<Unit> = runCatching {
        val r = CodexJson.decodeFromString<SimpleResponse>(execute(post("/restart", JsonObject(emptyMap()))))
        if (!r.success) error(r.error ?: "Codex did not restart")
    }

    /** Starts installing ("install") or updating ("update") Codex on the server. */
    suspend fun startCliJob(action: String): Result<CodexCliJob> = runCatching {
        val r = CodexJson.decodeFromString<CliJobResponse>(execute(post("/cli/$action", JsonObject(emptyMap()))))
        if (!r.success) error(r.error ?: "Could not start the $action")
        r.job ?: CodexCliJob(kind = action, running = true)
    }

    suspend fun cliJob(): Result<CodexCliJob?> = runCatching {
        CodexJson.decodeFromString<CliJobResponse>(execute(Request.Builder().url(url("/cli/job")).get().build())).job
    }

    /** Saves a file on the server (the bridge's `/api/upload`: `<project>/.gemini/attachments`); its path there. */
    suspend fun upload(fileName: String, base64: String, projectPath: String): Result<String> = runCatching {
        val body = buildJsonObject {
            put("filename", JsonPrimitive(fileName))
            put("base64Data", JsonPrimitive(base64))
            if (projectPath.isNotBlank()) put("projectPath", JsonPrimitive(projectPath))
        }
        val req = Request.Builder().url(AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/upload")
            .post(CodexJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()
        CodexJson.parseToJsonElement(execute(req)).obj().str("path") ?: error("the server did not save the file")
    }
}
