package com.example.gemini.data.agent.opencode

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** OpenCode is turned off on the bridge, or the bridge has no OpenCode support yet. */
class OpenCodeUnavailableException(message: String, val disabled: Boolean = false, val outdated: Boolean = false) : Exception(message)

@Serializable
data class OpenCodeStatus(
    val success: Boolean = false,
    val installed: Boolean = false,
    val running: Boolean = false,
    val starting: Boolean = false,
    val binary: String? = null,
    val version: String? = null,
    val error: String? = null,
    val logs: List<String>? = null,
    val uptime_seconds: Long? = null
)

@Serializable
data class OpenCodeCustomModel(
    val id: String,
    val name: String? = null,
    val context_length: Long? = null,
    val max_output_tokens: Long? = null,
    val reasoning: Boolean = false,
    val no_tools: Boolean = false,
    val images: Boolean = false
)

@Serializable
data class OpenCodeCustomProvider(
    val id: String = "",
    val name: String = "",
    /** "openai" (chat/completions) or "anthropic" (messages). */
    val kind: String = "openai",
    val base_url: String = "",
    /** Only sent when saving (blank keeps the saved key, "-" clears it); the bridge never returns it. */
    val api_key: String? = null,
    val has_api_key: Boolean = false,
    val api_key_hint: String? = null,
    val headers: Map<String, String>? = null,
    val models: List<OpenCodeCustomModel> = emptyList()
)

@Serializable
private data class CustomProvidersResponse(
    val success: Boolean = false,
    val error: String? = null,
    val providers: List<OpenCodeCustomProvider> = emptyList()
)

@Serializable
private data class CustomProviderResponse(val success: Boolean = false, val error: String? = null, val provider: OpenCodeCustomProvider? = null)

@Serializable
private data class FetchModelsResponse(val success: Boolean = false, val error: String? = null, val models: List<OpenCodeCustomModel> = emptyList())

@Serializable
private data class FetchModelsRequest(val kind: String, val base_url: String, val api_key: String, val id: String)

/** An install / update of OpenCode running on the bridge (its log grows while it runs). */
@Serializable
data class OpenCodeCliJob(
    val kind: String = "",
    val running: Boolean = false,
    val exit_code: Int? = null,
    val log: String = ""
)

@Serializable
private data class CliJobResponse(val success: Boolean = false, val error: String? = null, val job: OpenCodeCliJob? = null)

@Serializable
private data class SimpleResponse(val success: Boolean = false, val error: String? = null)

/**
 * Talks to the bridge's `/api/opencode/` endpoints. `/x/...` is OpenCode's own v2 API relayed by the bridge (which
 * runs `opencode serve` and adds its password); the rest is the bridge's own (status, custom providers).
 */
class OpenCodeBridgeClient(private val bridge: AgyBridgeService = AgyBridgeService.instance) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/opencode" + path

    /** Cold starts of `opencode serve` can take a while on a phone. */
    private val client by lazy { bridge.client.newBuilder().readTimeout(75, TimeUnit.SECONDS).build() }

    /** The event stream: OpenCode sends a heartbeat every 15 s, so a minute of silence means the connection is dead. */
    private val streamClient by lazy { bridge.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build() }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val isJson = text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            if (resp.code == 404 && !isJson) throw OpenCodeUnavailableException("This server has no OpenCode support yet. Update and restart the server.", outdated = true)
            if (resp.code == 503 && text.contains("\"disabled\":true")) {
                throw OpenCodeUnavailableException("The server was started with OpenCode turned off. Restart it to use OpenCode.", disabled = true)
            }
            if (!resp.isSuccessful) {
                val msg = runCatching { OpenCodeJson.parseToJsonElement(text).obj() }.getOrNull()
                    ?.let { it.str("error") ?: it.str("message") }
                    ?: text.trim().take(200)
                error(msg.ifBlank { "HTTP ${resp.code}" })
            }
            text
        }
    }

    // ---- OpenCode API (relayed)

    /** One request to OpenCode's `/api{path}`; the JSON answer (JsonNull for an empty one). */
    suspend fun api(method: String, path: String, body: JsonElement? = null): Result<JsonElement> = runCatching {
        val rb = when {
            body != null -> OpenCodeJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
            method == "POST" || method == "PUT" || method == "PATCH" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        val text = execute(Request.Builder().url(url("/x$path")).method(method, rb).build())
        if (text.isBlank()) JsonNull else OpenCodeJson.parseToJsonElement(text)
    }

    suspend fun get(path: String) = api("GET", path)
    suspend fun post(path: String, body: JsonElement? = null) = api("POST", path, body)

    /**
     * OpenCode's live events (every session), reconnecting until the collector stops. [onConnected] runs after each
     * (re)connection so the caller can resync what it may have missed.
     */
    fun events(onConnected: suspend () -> Unit, onDisconnected: (Throwable?) -> Unit): Flow<JsonObject> = flow {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var failure: Throwable? = null
            try {
                val req = Request.Builder().url(url("/x/event")).header("Accept", "text/event-stream").get().build()
                streamClient.newCall(req).execute().use { resp ->
                    if (resp.code == 503 && resp.peekBody(4096).string().contains("\"disabled\":true")) {
                        throw OpenCodeUnavailableException("OpenCode is turned off on the server.", disabled = true)
                    }
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val source = resp.body?.source() ?: error("no body")
                    backoff = 1_000L
                    onConnected()
                    val data = StringBuilder()
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    runCatching { OpenCodeJson.parseToJsonElement(data.toString()).obj() }.getOrNull()?.let { emit(it) }
                                    data.setLength(0)
                                }
                            }
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                            // ": heartbeat" comments and other fields are ignored
                        }
                    }
                }
            } catch (e: OpenCodeUnavailableException) {
                onDisconnected(e)
                if (e.disabled || e.outdated) delay(60_000) else delay(backoff)
                continue
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                failure = e
            }
            onDisconnected(failure)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }.flowOn(Dispatchers.IO)

    // ---- bridge endpoints

    suspend fun status(): Result<OpenCodeStatus> = runCatching {
        OpenCodeJson.decodeFromString<OpenCodeStatus>(execute(Request.Builder().url(url("/status")).get().build()))
    }

    suspend fun restart(): Result<Unit> = runCatching {
        val r = OpenCodeJson.decodeFromString<SimpleResponse>(execute(Request.Builder().url(url("/restart")).post("{}".toRequestBody(jsonType)).build()))
        if (!r.success) error(r.error ?: "OpenCode did not restart")
    }

    /** Starts installing ("install") or updating ("update") OpenCode on the server. */
    suspend fun startCliJob(action: String): Result<OpenCodeCliJob> = runCatching {
        val r = OpenCodeJson.decodeFromString<CliJobResponse>(execute(Request.Builder().url(url("/cli/$action")).post("{}".toRequestBody(jsonType)).build()))
        if (!r.success) error(r.error ?: "Could not start the $action")
        r.job ?: OpenCodeCliJob(kind = action, running = true)
    }

    suspend fun cliJob(): Result<OpenCodeCliJob?> = runCatching {
        OpenCodeJson.decodeFromString<CliJobResponse>(execute(Request.Builder().url(url("/cli/job")).get().build())).job
    }

    // ---- provider sign-in

    suspend fun integrations(): Result<List<OpenCodeAuth.Integration>> =
        get("/integration").map { OpenCodeAuth.integrations(it.obj().a("data")) }

    /** Starts an OAuth sign-in ([answer]: the method's form). */
    suspend fun startOAuth(integrationId: String, methodId: String, answer: Map<String, JsonElement>): Result<OpenCodeAuth.Attempt> =
        post("/integration/$integrationId/connect/oauth", kotlinx.serialization.json.buildJsonObject {
            put("methodID", kotlinx.serialization.json.JsonPrimitive(methodId))
            if (answer.isNotEmpty()) put("answer", JsonObject(answer))
            put("label", kotlinx.serialization.json.JsonPrimitive("Added from AntiGem"))
        }).mapCatching { OpenCodeAuth.attempt(it.obj()) ?: error("OpenCode did not start the sign-in") }

    suspend fun oauthStatus(integrationId: String, attemptId: String): Result<OpenCodeAuth.AttemptStatus> =
        get("/integration/$integrationId/connect/oauth/$attemptId").map { OpenCodeAuth.status(it.obj()) }

    /** Finishes a sign-in that gave a code to paste. */
    suspend fun completeOAuth(integrationId: String, attemptId: String, code: String): Result<Unit> =
        post("/integration/$integrationId/connect/oauth/$attemptId/complete", kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
        }).map { }

    suspend fun cancelOAuth(integrationId: String, attemptId: String): Result<Unit> =
        api("DELETE", "/integration/$integrationId/connect/oauth/$attemptId").map { }

    /** Uses credentials already on the server (Claude Code's login, an AWS profile, gcloud). */
    suspend fun connectExternal(integrationId: String, methodId: String, answer: Map<String, JsonElement>): Result<Unit> =
        post("/integration/$integrationId/connect/external", kotlinx.serialization.json.buildJsonObject {
            put("methodID", kotlinx.serialization.json.JsonPrimitive(methodId))
            if (answer.isNotEmpty()) put("answer", JsonObject(answer))
            put("label", kotlinx.serialization.json.JsonPrimitive("Added from AntiGem"))
        }).map { }

    suspend fun connectKey(integrationId: String, key: String, label: String = "Added from AntiGem"): Result<Unit> =
        post("/integration/$integrationId/connect/key", kotlinx.serialization.json.buildJsonObject {
            put("key", kotlinx.serialization.json.JsonPrimitive(key))
            put("label", kotlinx.serialization.json.JsonPrimitive(label))
        }).map { }

    /** The saved accounts (the bridge leaves their secrets out). */
    suspend fun credentials(): Result<List<OpenCodeAuth.Credential>> = runCatching {
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/credentials")).get().build())).obj()
        if (o.bool("success") == false) error(o.str("error") ?: "Could not list the accounts")
        OpenCodeAuth.credentials(o.a("credentials"))
    }

    suspend fun activateCredential(id: String): Result<Unit> = post("/credential/$id/activate").map { }
    suspend fun removeCredential(id: String): Result<Unit> = api("DELETE", "/credential/$id").map { }

    /** Server mode: hands the 127.0.0.1 address a browser sign-in came back to over to the server (see the bridge). */
    suspend fun forwardOAuthCallback(callbackUrl: String): Result<Unit> = runCatching {
        val body = OpenCodeJson.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("url", kotlinx.serialization.json.JsonPrimitive(callbackUrl))
        }).toRequestBody(jsonType)
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/oauth-callback")).post(body).build())).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "The server could not finish the sign-in")
    }

    /** Names of the MCP servers the app added (kept in the bridge's OpenCode config). */
    suspend fun mcpServers(): Result<Set<String>> = runCatching {
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/mcp")).get().build())).obj()
        if (o.bool("success") == false) error(o.str("error") ?: "Could not list the MCP servers")
        o.o("servers")?.keys ?: emptySet()
    }

    /** One MCP server as Settings shows it. [saved]: added from the app (it can be removed there). */
    data class McpServer(val name: String, val saved: Boolean, val config: JsonObject?, val status: String?, val error: String?)

    /** The servers the app added plus every server OpenCode reports (the user's own config included), with their status. */
    suspend fun mcpList(): Result<List<McpServer>> = runCatching {
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/mcp")).get().build())).obj()
        if (o.bool("success") == false) error(o.str("error") ?: "Could not list the MCP servers")
        val saved = o.o("servers") ?: JsonObject(emptyMap())
        val live = o.o("status").a("data").orEmpty().mapNotNull { it.obj() }.associateBy { it.str("name").orEmpty() }
        (saved.keys + live.keys).filter { it.isNotBlank() }.distinct().sorted().map { name ->
            val st = live[name]?.o("status")
            McpServer(name, name in saved.keys, saved[name]?.obj(), st.str("status"), st.str("error"))
        }
    }

    /** Adds (or replaces) a local MCP server (a command OpenCode starts) for every OpenCode chat. */
    suspend fun addLocalMcp(name: String, command: List<String>, env: Map<String, String>): Result<Unit> = runCatching {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("local"))
            put("command", kotlinx.serialization.json.JsonArray(command.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            if (env.isNotEmpty()) put("environment", JsonObject(env.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        }
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/mcp/$name"))
            .put(OpenCodeJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build())).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "Could not add the MCP server")
    }

    suspend fun removeMcp(name: String): Result<Unit> = runCatching {
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/mcp/$name")).delete().build())).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "Could not remove the MCP server")
    }

    /** Adds (or replaces) a remote MCP server for every OpenCode chat. */
    suspend fun addRemoteMcp(name: String, url: String, headers: Map<String, String>): Result<Unit> = runCatching {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("remote"))
            put("url", kotlinx.serialization.json.JsonPrimitive(url))
            if (headers.isNotEmpty()) put("headers", kotlinx.serialization.json.JsonObject(headers.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        }
        val o = OpenCodeJson.parseToJsonElement(execute(Request.Builder().url(url("/mcp/$name"))
            .put(OpenCodeJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build())).obj()
        if (o.bool("success") != true) error(o.str("error") ?: "Could not add the MCP server")
    }

    suspend fun customProviders(): Result<List<OpenCodeCustomProvider>> = runCatching {
        val r = OpenCodeJson.decodeFromString<CustomProvidersResponse>(execute(Request.Builder().url(url("/custom-providers")).get().build()))
        if (!r.success) error(r.error ?: "Could not load the custom providers")
        r.providers
    }

    /** Adds [p] (blank id) or updates it. */
    suspend fun saveCustomProvider(p: OpenCodeCustomProvider): Result<OpenCodeCustomProvider> = runCatching {
        val body = OpenCodeJson.encodeToString(p).toRequestBody(jsonType)
        val req = if (p.id.isBlank()) Request.Builder().url(url("/custom-providers")).post(body).build()
        else Request.Builder().url(url("/custom-providers/${p.id}")).put(body).build()
        val r = OpenCodeJson.decodeFromString<CustomProviderResponse>(execute(req))
        if (!r.success) error(r.error ?: "Could not save the provider")
        r.provider ?: p
    }

    suspend fun deleteCustomProvider(id: String): Result<Unit> = runCatching {
        val r = OpenCodeJson.decodeFromString<SimpleResponse>(execute(Request.Builder().url(url("/custom-providers/$id")).delete().build()))
        if (!r.success) error(r.error ?: "Could not remove the provider")
    }

    /** Saves a file on the server (the bridge's `/api/upload`: `<project>/.gemini/attachments`); its path there. */
    suspend fun upload(fileName: String, base64: String, projectPath: String): Result<String> = runCatching {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("filename", kotlinx.serialization.json.JsonPrimitive(fileName))
            put("base64Data", kotlinx.serialization.json.JsonPrimitive(base64))
            if (projectPath.isNotBlank()) put("projectPath", kotlinx.serialization.json.JsonPrimitive(projectPath))
        }
        val req = Request.Builder().url(AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/upload")
            .post(OpenCodeJson.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()
        OpenCodeJson.parseToJsonElement(execute(req)).obj().str("path") ?: error("the server did not save the file")
    }

    /** The model ids an endpoint lists (`GET {base}/models`). [savedId]: use that provider's saved key when [apiKey] is blank. */
    suspend fun fetchModels(kind: String, baseUrl: String, apiKey: String, savedId: String = ""): Result<List<OpenCodeCustomModel>> = runCatching {
        val body = OpenCodeJson.encodeToString(FetchModelsRequest(kind, baseUrl, apiKey, savedId)).toRequestBody(jsonType)
        val r = OpenCodeJson.decodeFromString<FetchModelsResponse>(execute(Request.Builder().url(url("/custom-providers/fetch-models")).post(body).build()))
        if (!r.success) error(r.error ?: "Could not list the models")
        r.models
    }
}
