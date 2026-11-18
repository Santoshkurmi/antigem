package ai.antigem.data.memory

import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.AgyBridgeService
import ai.antigem.domain.model.AgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The IDE bridge's /api/memory (its pkg/memory): memory files and chat history, kept next to the agents. */
object MemoryBridgeClient {

    @Serializable
    data class Memory(
        val id: String = "",
        val file: String = "",
        /** global | project */
        val scope: String = "global",
        val project: String = "",
        @SerialName("project_dir") val projectDir: String = "",
        /** always | passive */
        val mode: String = "passive",
        val description: String = "",
        val text: String = "",
        /** unix ms */
        val updated: Long = 0,
        /** Who saved it: claude, codex, opencode, agy or user. */
        val agent: String = "",
        val source: String = "",
        val path: String = ""
    ) {
        val isAlways get() = mode == "always"
        val isProject get() = scope == "project"
        val name get() = file.removeSuffix(".md")
        /** The project's folder name ("" for a global memory). */
        val projectName get() = project.trimEnd('/').substringAfterLast('/').ifBlank { projectDir.substringBeforeLast('-') }
    }

    /** What happened to one ```antigem-memory block. */
    @Serializable
    data class Applied(
        val key: String = "",
        val found: Boolean = false,
        /** saved | updated | deleted | error */
        val action: String = "",
        val id: String = "",
        val description: String = "",
        val error: String = "",
        val undone: Boolean = false,
        val at: Long = 0
    )

    @Serializable
    data class HistoryHit(
        @SerialName("chat_id") val chatId: String = "",
        val title: String = "",
        val agent: String = "",
        val project: String = "",
        val file: String = "",
        val path: String = "",
        val updated: Long = 0,
        val snippet: String = "",
        val score: Int = 0
    )

    data class SearchResult(val memories: List<Memory>, val history: List<HistoryHit>)

    /** One message of a chat for the bridge: user | assistant | summary. */
    data class Turn(val role: String, val text: String, val at: Long = 0)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl get() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/memory"
    private val client: OkHttpClient get() = AgyBridgeService.instance.client
    private val syncClient: OkHttpClient by lazy {
        AgyBridgeService.instance.client.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private suspend fun call(request: Request, http: OkHttpClient = client): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!text.trimStart().startsWith("{")) {
                    if (resp.code == 404) error("The bridge is too old for Agent memory: update it (Settings → About).")
                    error("HTTP ${resp.code}")
                }
                val obj = json.parseToJsonElement(text).jsonObject
                if (obj["success"]?.jsonPrimitive?.booleanOrNull == false) {
                    error(obj["error"]?.jsonPrimitive?.contentOrNull ?: "The bridge refused")
                }
                obj
            }
        }
    }

    private suspend fun get(path: String) = call(Request.Builder().url(baseUrl + path).get().build())

    private suspend fun post(path: String, body: JSONObject, http: OkHttpClient = client) =
        call(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(jsonType)).build(), http)

    private inline fun <reified T> JsonObject.field(name: String): T? =
        this[name]?.let { el: JsonElement -> runCatching { json.decodeFromJsonElement<T>(el) }.getOrNull() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ chats

    /** The memory text for a chat's first message; the call gives up after [timeoutMs]. */
    suspend fun context(project: String?, agent: AgentKind, message: String, limit: Int, history: Boolean, timeoutMs: Long): Result<String> {
        val http = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val body = JSONObject()
            .put("project", project.orEmpty()).put("agent", agent.name.lowercase()).put("message", message)
            .put("limit", limit).put("history", history)
        return post("/context", body, http).mapCatching { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    /** A chat after a turn: the bridge applies its new memory blocks and (with [history]) rewrites its history file. */
    suspend fun sync(project: String?, agent: AgentKind, chatId: String, title: String, history: Boolean, turns: List<Turn>): Result<List<Applied>> {
        val arr = JSONArray()
        turns.forEach { t -> arr.put(JSONObject().put("role", t.role).put("text", t.text).put("at", t.at)) }
        val body = JSONObject()
            .put("project", project.orEmpty()).put("agent", agent.name.lowercase()).put("chat_id", chatId)
            .put("title", title).put("history", history).put("turns", arr)
        return post("/sync", body, syncClient).mapCatching { it.field<List<Applied>>("applied").orEmpty() }
    }

    /** Removes a chat's history file; that chat is not saved again (new chats are). */
    suspend fun deleteHistory(chatId: String): Result<Unit> =
        post("/history/delete", JSONObject().put("chat_id", chatId)).map { }

    // ------------------------------------------------------------------ the history page

    /** One saved chat. */
    @Serializable
    data class HistoryChat(
        @SerialName("chat_id") val chatId: String = "",
        val title: String = "",
        /** claude, codex, opencode or agy */
        val agent: String = "",
        val project: String = "",
        val file: String = "",
        val path: String = "",
        val started: Long = 0,
        val updated: Long = 0,
        /** The user edited it: kept as is, the chat's new messages are added at the end. */
        val edited: Boolean = false,
        val size: Long = 0
    ) {
        val projectName get() = project.trimEnd('/').substringAfterLast('/')
    }

    /** Every saved chat, newest first. */
    suspend fun historyList(): Result<List<HistoryChat>> =
        get("/history/list").mapCatching { it.field<List<HistoryChat>>("chats").orEmpty() }

    /** A saved chat and its file's text. */
    suspend fun historyGet(chatId: String): Result<Pair<HistoryChat, String>> =
        get("/history/get?chat_id=${enc(chatId)}").mapCatching {
            (it.field<HistoryChat>("chat") ?: HistoryChat(chatId = chatId)) to it["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

    /** The user's edit of a saved chat (kept from now on). */
    suspend fun historySave(chatId: String, text: String): Result<HistoryChat> =
        post("/history/save", JSONObject().put("chat_id", chatId).put("text", text), syncClient)
            .mapCatching { it.field<HistoryChat>("chat") ?: HistoryChat(chatId = chatId) }

    /** Removes every saved chat (they are not saved again; new chats are). Returns how many. */
    suspend fun historyClear(): Result<Int> =
        post("/history/clear", JSONObject()).mapCatching { it["removed"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }

    // ------------------------------------------------------------------ the chip

    suspend fun applied(key: String): Result<Applied> =
        get("/applied?key=${enc(key)}").mapCatching { it.field<Applied>("applied") ?: Applied(key = key) }

    suspend fun undo(key: String): Result<Applied> =
        post("/undo", JSONObject().put("key", key)).mapCatching { it.field<Applied>("applied") ?: Applied(key = key) }

    /** The key the bridge files a memory block under: sha1 of its text without \r, trimmed. */
    fun blockKey(body: String): String =
        MessageDigest.getInstance("SHA-1").digest(body.replace("\r", "").trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ settings

    /** Every memory and the folder they are in (on the bridge's machine). */
    suspend fun list(): Result<Pair<String, List<Memory>>> = get("/list").mapCatching {
        (it["root"]?.jsonPrimitive?.contentOrNull.orEmpty()) to it.field<List<Memory>>("memories").orEmpty()
    }

    suspend fun save(id: String?, file: String, scope: String, project: String?, mode: String, description: String, text: String): Result<Memory> {
        val body = JSONObject()
            .put("id", id.orEmpty()).put("file", file).put("scope", scope).put("project", project.orEmpty())
            .put("mode", mode).put("description", description).put("text", text)
        return post("/save", body).mapCatching { it.field<Memory>("memory") ?: error("The bridge sent no memory back") }
    }

    suspend fun delete(id: String): Result<Unit> = post("/delete", JSONObject().put("id", id)).map { }

    suspend fun search(query: String, project: String? = null): Result<SearchResult> =
        get("/search?q=${enc(query)}" + (project?.let { "&project=${enc(it)}" } ?: "")).mapCatching {
            SearchResult(it.field<List<Memory>>("memories").orEmpty(), it.field<List<HistoryHit>>("history").orEmpty())
        }
}
