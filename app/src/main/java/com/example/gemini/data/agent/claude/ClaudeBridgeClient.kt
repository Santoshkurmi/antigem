package com.example.gemini.data.agent.claude

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Talks to the bridge's `/api/claude/` endpoints. The bridge runs the `claude` processes; this client never touches
 * Claude's files or runs commands itself.
 */
class ClaudeBridgeClient(
    private val bridge: AgyBridgeService = AgyBridgeService.instance
) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/claude" + path

    private suspend inline fun <reified T> get(path: String): Result<T> = call(Request.Builder().url(url(path)).get().build())

    private suspend inline fun <reified T> post(path: String, body: String = "{}"): Result<T> =
        call(Request.Builder().url(url(path)).post(body.toRequestBody(jsonType)).build())

    /** For calls that run the CLI on the bridge and can outlast the default 30 s read timeout. */
    private val slowClient by lazy { bridge.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }

    private suspend inline fun <reified T> call(request: Request, http: OkHttpClient = bridge.client): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val isJson = text.trimStart().startsWith("{")
                // the bridge's Claude routes always answer JSON; a plain 404 means a bridge without them, and a 503
                // "disabled" one that was started with --no-claude (Claude was off when the server started)
                if (resp.code == 404 && !isJson) throw ClaudeBridgeOutdatedException()
                if (resp.code == 503 && text.contains("\"disabled\":true")) {
                    throw ClaudeBridgeOutdatedException("The local server was started with Claude Code turned off. Restart it to use Claude.")
                }
                if (!resp.isSuccessful && !isJson) error("HTTP ${resp.code}" + text.trim().take(120).let { if (it.isBlank()) "" else ": $it" })
                ClaudeJson.decodeFromString<T>(text)
            }
        }
    }

    suspend fun status(): Result<ClaudeCliStatus> = call(Request.Builder().url(url("/status")).get().build(), slowClient)

    suspend fun info(refresh: Boolean = false): Result<ClaudeInfoResponse> = get(if (refresh) "/info?refresh=1" else "/info")

    suspend fun logout(): Result<BridgeSimpleResponse> = post("/auth/logout")

    suspend fun listSessions(): Result<ClaudeSessionListResponse> = get("/sessions")

    suspend fun history(sessionId: String): Result<ClaudeHistoryResponse> = get("/sessions/$sessionId/history")

    suspend fun rename(sessionId: String, title: String): Result<BridgeSimpleResponse> =
        post("/sessions/$sessionId/title", ClaudeJson.encodeToString(ClaudeTitleRequest(title)))

    suspend fun kill(sessionId: String): Result<BridgeSimpleResponse> = post("/sessions/$sessionId/kill")

    suspend fun delete(sessionId: String): Result<BridgeSimpleResponse> =
        call(Request.Builder().url(url("/sessions/$sessionId")).delete().build())

    suspend fun usage(refresh: Boolean = false): Result<ClaudeUsageResponse> = get(if (refresh) "/usage?refresh=1" else "/usage")

    // ---- login
    suspend fun startLogin(method: String): Result<ClaudeLoginResponse> =
        post("/auth/login", ClaudeJson.encodeToString(ClaudeLoginStartRequest(method)))

    suspend fun loginState(loginId: String): Result<ClaudeLoginResponse> = get("/auth/login/$loginId")

    suspend fun submitLoginCode(loginId: String, code: String): Result<ClaudeLoginResponse> =
        post("/auth/login/$loginId/code", ClaudeJson.encodeToString(ClaudeLoginCodeRequest(code)))

    suspend fun cancelLogin(loginId: String): Result<BridgeSimpleResponse> =
        call(Request.Builder().url(url("/auth/login/$loginId")).delete().build())

    // ---- configuration
    suspend fun settings(): Result<ClaudeSettingsResponse> = get("/settings")

    suspend fun saveSettings(settings: kotlinx.serialization.json.JsonObject): Result<BridgeSimpleResponse> =
        call(Request.Builder().url(url("/settings")).put(ClaudeJson.encodeToString(ClaudeSettingsPut(settings)).toRequestBody(jsonType)).build())

    private fun memoryQuery(scope: String, cwd: String?) =
        "/memory?scope=$scope" + (cwd?.let { "&cwd=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")

    suspend fun memory(scope: String, cwd: String? = null): Result<ClaudeMemoryResponse> = get(memoryQuery(scope, cwd))

    suspend fun saveMemory(scope: String, cwd: String?, content: String): Result<BridgeSimpleResponse> =
        call(Request.Builder().url(url(memoryQuery(scope, cwd))).put(ClaudeJson.encodeToString(ClaudeMemoryPut(content)).toRequestBody(jsonType)).build())

    suspend fun mcpServers(cwd: String? = null): Result<ClaudeMcpResponse> =
        get("/mcp" + (cwd?.let { "?cwd=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))

    suspend fun addMcpServer(req: ClaudeMcpAddRequest): Result<ClaudeCommandOutput> = post("/mcp", ClaudeJson.encodeToString(req))

    suspend fun removeMcpServer(name: String, scope: String?, cwd: String?): Result<ClaudeCommandOutput> {
        val q = buildString {
            append("/mcp?name=").append(java.net.URLEncoder.encode(name, "UTF-8"))
            scope?.let { append("&scope=").append(it) }
            cwd?.let { append("&cwd=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
        return call(Request.Builder().url(url(q)).delete().build())
    }

    suspend fun plugins(): Result<ClaudePluginsResponse> = get("/plugins")

    /** action: install | uninstall | enable | disable | update | marketplace-add | marketplace-remove | marketplace-update */
    suspend fun pluginAction(action: String, body: ClaudePluginAction): Result<ClaudeCommandOutput> =
        post("/plugins/$action", ClaudeJson.encodeToString(body))

    suspend fun cliJob(): Result<ClaudeCliJobResponse> = get("/cli/job")

    /** Saves an attachment of chat [sessionId] on the bridge; Claude is given its path. */
    suspend fun saveAttachment(sessionId: String, name: String, mimeType: String, base64: String): Result<ClaudeSavedAttachment> =
        call(Request.Builder().url(url("/attachments")).post(ClaudeJson.encodeToString(ClaudeAttachmentSave(sessionId, name, mimeType, base64)).toRequestBody(jsonType)).build(), slowClient)

    suspend fun sandbox(): Result<ClaudeSandboxInfo> = call(Request.Builder().url(url("/sandbox")).get().build(), slowClient)

    /** action: install | update */
    suspend fun startCliJob(action: String): Result<ClaudeCliJobResponse> = post("/cli/$action")

    /** Dictation relay: send 16 kHz mono s16le PCM as binary frames; transcript frames come back as text. */
    fun openVoice(language: String, listener: WebSocketListener): WebSocket {
        val httpUrl = url("/voice").toHttpUrl().newBuilder().addQueryParameter("language", language).build()
        val request = Request.Builder().url(httpUrl.toString().replaceFirst("http", "ws")).build()
        return bridge.wsClient.newWebSocket(request, listener)
    }

    /**
     * Attaches to a session's WebSocket. Frames with `seq >= since` still in the bridge buffer are replayed first
     * (`since = -1`: no replay). Spawn options apply when the bridge starts the process for the first message.
     */
    fun connect(sessionId: String, since: Long, spawn: BridgeConfigFrame): Flow<ClaudeSocketEvent> = callbackFlow {
        val httpUrl = url("/session").toHttpUrl().newBuilder()
            .addQueryParameter("id", sessionId)
            .addQueryParameter("since", since.toString())
            .apply {
                spawn.cwd?.let { addQueryParameter("cwd", it) }
                spawn.model?.let { addQueryParameter("model", it) }
                spawn.permission_mode?.let { addQueryParameter("permission_mode", it) }
                spawn.effort?.let { addQueryParameter("effort", it) }
                spawn.fork_from?.let { addQueryParameter("fork_from", it) }
                spawn.resume_session_at?.let { addQueryParameter("resume_session_at", it) }
                spawn.thinking?.let { addQueryParameter("thinking", it) }
            }
            .build()
        val request = Request.Builder().url(httpUrl.toString().replaceFirst("http", "ws")).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(ClaudeSocketEvent.Opened(ClaudeSocket(webSocket)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { ClaudeJson.decodeFromString<BridgeFrame>(text) }
                    .onSuccess { trySend(ClaudeSocketEvent.Frame(it)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(ClaudeSocketEvent.Closed(null))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(ClaudeSocketEvent.Closed(t.message ?: "connection failed"))
                close()
            }
        }
        val ws = bridge.wsClient.newWebSocket(request, listener)
        awaitClose { ws.close(1000, null) }
    }
}

/** Nothing answered at the bridge address (not running yet, stopped, or unreachable). */
fun Throwable.isBridgeUnreachable(): Boolean =
    this is java.net.ConnectException || this is java.net.NoRouteToHostException || this is java.net.UnknownHostException ||
        // OkHttp reports a connect timeout as SocketTimeoutException("failed to connect …")
        (this is java.net.SocketTimeoutException && message.orEmpty().contains("connect", ignoreCase = true))

/**
 * The app has just started (or the local server is starting): an unreachable bridge is expected for a few seconds,
 * so callers wait and retry instead of reporting it offline. Like AGY's start-up grace period.
 */
object BridgeStartup {
    private val appStart = System.currentTimeMillis()
    @Volatile private var reached = false

    fun markReached() {
        reached = true
    }

    fun isStartingUp(): Boolean {
        if (reached) return false
        val elapsed = System.currentTimeMillis() - appStart
        val serverStarting = com.example.gemini.data.local.LocalServerManager.status.value is com.example.gemini.data.local.LocalServerStatus.Starting
        return elapsed < 20_000 || (serverStarting && elapsed < 60_000)
    }
}

/** The bridge answered but has no `/api/claude` routes: an older bridge binary is still running. */
class ClaudeBridgeOutdatedException(
    message: String = "The running bridge has no Claude Code support. Restart the local server to load the new version."
) : IllegalStateException(message)

class ClaudeSocket internal constructor(private val ws: WebSocket) {
    fun send(text: String): Boolean = ws.send(text)
    fun close() {
        ws.close(1000, null)
    }
}

sealed interface ClaudeSocketEvent {
    data class Opened(val socket: ClaudeSocket) : ClaudeSocketEvent
    data class Frame(val frame: BridgeFrame) : ClaudeSocketEvent
    data class Closed(val error: String?) : ClaudeSocketEvent
}
