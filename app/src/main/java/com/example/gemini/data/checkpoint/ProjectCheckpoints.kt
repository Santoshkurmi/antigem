package com.example.gemini.data.checkpoint

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class CheckpointSettings(
    val mode: String = MODE_OFF,
    @SerialName("retention_days") val retentionDays: Int = 30,
    @SerialName("max_per_chat") val maxPerChat: Int = 100,
    @SerialName("max_project_mb") val maxProjectMb: Int = 500,
    @SerialName("max_file_mb") val maxFileMb: Int = 20,
    val projects: Map<String, String> = emptyMap()
) {
    val isOn: Boolean get() = mode != MODE_OFF

    companion object {
        const val MODE_OFF = "off"
        const val MODE_SELECTED = "selected"
        const val MODE_ALL = "all"
    }
}

@Serializable
data class CheckpointProjectState(
    val path: String = "",
    val exists: Boolean = false,
    @SerialName("is_git") val isGit: Boolean = false,
    val mode: String = CheckpointSettings.MODE_OFF,
    val decision: String = "",
    val tracked: Boolean = false,
    @SerialName("should_ask") val shouldAsk: Boolean = false,
    val reason: String? = null
)

@Serializable
data class CheckpointProjectInfo(
    val path: String = "",
    val exists: Boolean = false,
    @SerialName("is_git") val isGit: Boolean = false,
    val mode: String = CheckpointSettings.MODE_OFF,
    val decision: String = "",
    val tracked: Boolean = false,
    val reason: String? = null,
    val count: Int = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("last_time") val lastTime: Long = 0,
    @SerialName("has_data") val hasData: Boolean = false
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

@Serializable
data class CheckpointSummary(
    val id: String = "",
    val time: Long = 0,
    val kind: String = "turn",
    val agent: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val title: String? = null,
    @SerialName("mid_turn") val midTurn: Boolean = false,
    val late: Boolean = false,
    @SerialName("took_ms") val tookMs: Long = 0,
    @SerialName("restored_from") val restoredFrom: String? = null,
    val repos: Int = 1,
    val skipped: Int = 0
) {
    val isPreRestore: Boolean get() = kind == "pre-restore"
}

@Serializable
data class CheckpointFileChange(
    val path: String = "",
    /** modified | deleted (made after the checkpoint) | recreated (deleted after it) */
    val change: String = "modified",
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false
)

@Serializable
data class CheckpointWarning(val kind: String = "", val text: String = "")

@Serializable
data class CheckpointLaterChat(
    val agent: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val title: String? = null,
    val time: Long = 0,
    val turns: Int = 0
)

@Serializable
data class CheckpointPreview(
    val checkpoint: CheckpointSummary = CheckpointSummary(),
    val files: List<CheckpointFileChange> = emptyList(),
    val modified: Int = 0,
    val deleted: Int = 0,
    val recreated: Int = 0,
    val kept: List<String> = emptyList(),
    val warnings: List<CheckpointWarning> = emptyList(),
    @SerialName("later_chats") val laterChats: List<CheckpointLaterChat> = emptyList(),
    @SerialName("later_in_chat") val laterInChat: Int = 0,
    /** restore only */
    val errors: List<String> = emptyList(),
    @SerialName("undo_id") val undoId: String? = null
)

@Serializable
data class CheckpointDeleteResult(
    val deleted: Int = 0,
    @SerialName("size_before") val sizeBefore: Long = 0,
    @SerialName("size_after") val sizeAfter: Long = 0
)

@Serializable
private data class TurnResult(
    val tracked: Boolean = false,
    val reason: String? = null,
    val checkpoint: CheckpointSummary? = null,
    val skipped: List<String> = emptyList()
)

/**
 * Project checkpoints: the bridge keeps a hidden copy of a project's files right before each chat message (any
 * agent), and puts them back with a preview first. This is the app's side: the settings, the call each agent makes
 * before sending, and the lookups the chat uses to show "restore" on its messages.
 */
object ProjectCheckpoints {
    private const val TAG = "ProjectCheckpoints"

    /** How long a message waits for its checkpoint before going to the agent anyway (the copy is then marked late). */
    private const val TURN_DEADLINE_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    private val _settings = MutableStateFlow<CheckpointSettings?>(null)
    /** The bridge's checkpoint settings (null until read once). */
    val settings: StateFlow<CheckpointSettings?> = _settings.asStateFlow()

    private val _byKey = MutableStateFlow<Map<String, CheckpointSummary>>(emptyMap())
    /** Checkpoints of messages, by the agent's id of the message (see AgentChatBackend.checkpointKey). */
    val byKey: StateFlow<Map<String, CheckpointSummary>> = _byKey.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /** Bumps when checkpoints were made, restored or deleted (lists reload). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The folder of a chat's project ("file:///x" → "/x"), or null when it has none. */
    fun pathOf(workspaceUri: String?): String? =
        workspaceUri?.removePrefix("file://")?.trim()?.takeIf { it.startsWith("/") }?.trimEnd('/')?.ifBlank { "/" }

    // ------------------------------------------------------------------ http

    private val baseUrl get() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/checkpoints"
    private val client: OkHttpClient get() = AgyBridgeService.instance.client
    private val slowClient: OkHttpClient by lazy {
        AgyBridgeService.instance.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    }
    private val turnClient: OkHttpClient by lazy {
        AgyBridgeService.instance.client.newBuilder().readTimeout(TURN_DEADLINE_MS + 2_000, TimeUnit.MILLISECONDS).build()
    }

    private suspend fun call(request: Request, http: OkHttpClient = client): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!text.trimStart().startsWith("{")) {
                    if (resp.code == 404) error("The bridge is too old for project checkpoints: update it (Settings → About).")
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

    private suspend fun get(path: String, http: OkHttpClient = client) = call(Request.Builder().url(baseUrl + path).get().build(), http)

    private suspend fun post(path: String, body: Map<String, Any?>, http: OkHttpClient = client) =
        call(Request.Builder().url(baseUrl + path).post(encode(body).toRequestBody(jsonType)).build(), http)

    private fun encode(body: Map<String, Any?>): String = org.json.JSONObject().apply {
        body.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Collection<*> -> put(k, org.json.JSONArray(v))
                else -> put(k, v)
            }
        }
    }.toString()

    private inline fun <reified T> JsonObject.field(name: String): T? =
        this[name]?.let { el: JsonElement -> runCatching { json.decodeFromJsonElement<T>(el) }.getOrNull() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ settings & projects

    @Volatile private var settingsFailedAt = 0L

    suspend fun refreshSettings(): Result<CheckpointSettings> =
        get("/settings").mapCatching { it.field<CheckpointSettings>("settings") ?: CheckpointSettings() }
            .onSuccess { _settings.value = it; settingsFailedAt = 0L }
            .onFailure { settingsFailedAt = System.currentTimeMillis() }

    /** The settings, read once (a failed read is not retried for a minute, so sends never wait on a missing bridge). */
    private suspend fun knownSettings(): CheckpointSettings? =
        _settings.value ?: if (System.currentTimeMillis() - settingsFailedAt < 60_000) null else refreshSettings().getOrNull()

    /** Whether a message in this project may get a checkpoint (false: skip the call entirely). */
    fun mayCheckpoint(workspaceUri: String?): Boolean = pathOf(workspaceUri) != null && _settings.value?.isOn != false

    suspend fun updateSettings(
        mode: String? = null, retentionDays: Int? = null, maxPerChat: Int? = null, maxProjectMb: Int? = null, maxFileMb: Int? = null
    ): Result<CheckpointSettings> =
        post("/settings", mapOf("mode" to mode, "retention_days" to retentionDays, "max_per_chat" to maxPerChat,
            "max_project_mb" to maxProjectMb, "max_file_mb" to maxFileMb))
            .mapCatching { it.field<CheckpointSettings>("settings") ?: CheckpointSettings() }
            .onSuccess { _settings.value = it; _changes.update { n -> n + 1 } }

    suspend fun projectState(path: String): Result<CheckpointProjectState> =
        get("/project?path=${enc(path)}").mapCatching { it.field<CheckpointProjectState>("project") ?: CheckpointProjectState(path = path) }

    /** "on", "off", or "" (ask again). */
    suspend fun setDecision(path: String, decision: String): Result<CheckpointProjectState> =
        post("/project", mapOf("path" to path, "decision" to decision))
            .mapCatching { it.field<CheckpointProjectState>("project") ?: CheckpointProjectState(path = path) }
            .onSuccess { _changes.update { n -> n + 1 }; refreshSettings() }

    suspend fun projects(): Result<List<CheckpointProjectInfo>> =
        get("/projects").mapCatching { o ->
            o.field<CheckpointSettings>("settings")?.let { _settings.value = it }
            o.field<List<CheckpointProjectInfo>>("projects").orEmpty()
        }

    suspend fun history(path: String, limit: Int = 0): Result<List<CheckpointSummary>> =
        get("/history?project=${enc(path)}&limit=$limit").mapCatching { it.field<List<CheckpointSummary>>("checkpoints").orEmpty() }

    // ------------------------------------------------------------------ turns

    /**
     * Called by every agent right before a message goes out: the bridge copies the project when it is tracked (else
     * answers at once). Never throws and never blocks longer than the deadline; null when no checkpoint was made.
     */
    suspend fun beforeTurn(
        agent: AgentKind, chatId: String, workspaceUri: String?, key: String, title: String, midTurn: Boolean = false
    ): CheckpointSummary? {
        val path = pathOf(workspaceUri) ?: return null
        val s = knownSettings() ?: return null
        if (!s.isOn) return null
        val started = System.currentTimeMillis()
        val r = post("/turn", mapOf(
            "project" to path, "agent" to agent.name.lowercase(), "chat_id" to chatId, "key" to key,
            "title" to com.example.gemini.data.remote.PromptExpansion.collapse(title).lines().firstOrNull { it.isNotBlank() }.orEmpty(),
            "mid_turn" to midTurn, "deadline_ms" to TURN_DEADLINE_MS
        ), turnClient).mapCatching { it.field<TurnResult>("result") }
        r.onFailure { Log.w(TAG, "checkpoint before the message failed after ${System.currentTimeMillis() - started} ms: ${it.message}") }
        val cp = r.getOrNull()?.checkpoint ?: return null
        if (key.isNotBlank()) _byKey.update { it + (key to cp) }
        _changes.update { it + 1 }
        return cp
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * [beforeTurn] without waiting for it: for a message sent while the agent is still working (its reply keeps
     * streaming meanwhile; such a checkpoint is marked mid-turn).
     */
    fun beforeTurnInBackground(agent: AgentKind, chatId: String, workspaceUri: String?, key: String, title: String) {
        if (!mayCheckpoint(workspaceUri)) return
        background.launch { beforeTurn(agent, chatId, workspaceUri, key, title, midTurn = true) }
    }

    /** Another id of the same message (Codex's turn id, known once the turn started). */
    suspend fun bind(workspaceUri: String?, checkpoint: CheckpointSummary, key: String) {
        val path = pathOf(workspaceUri) ?: return
        if (key.isBlank()) return
        _byKey.update { it + (key to checkpoint) }
        post("/bind", mapOf("project" to path, "id" to checkpoint.id, "key" to key))
            .onFailure { Log.w(TAG, "bind failed: ${it.message}") }
    }

    private val warmed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A chat opened in this project: the bridge makes the first (slow) copy of a tracked project in the background
     * now, so the first message does not wait for it. Once per project per app run.
     */
    suspend fun warm(workspaceUri: String?) {
        val path = pathOf(workspaceUri) ?: return
        if (knownSettings()?.isOn != true || !warmed.add(path)) return
        projectState(path).onFailure { warmed.remove(path) }
    }

    /** Loads the checkpoints of these message ids (the chat on screen). */
    suspend fun lookup(workspaceUri: String?, keys: Collection<String>) {
        val path = pathOf(workspaceUri) ?: return
        val s = knownSettings() ?: return
        if (!s.isOn || keys.isEmpty()) return
        post("/lookup", mapOf("project" to path, "keys" to keys.toList()))
            .mapCatching { it.field<Map<String, CheckpointSummary>>("checkpoints").orEmpty() }
            .onSuccess { found -> _byKey.update { cur -> cur - keys.toSet() + found } }
    }

    // ------------------------------------------------------------------ restore

    suspend fun preview(path: String, id: String): Result<CheckpointPreview> =
        post("/preview", mapOf("project" to path, "id" to id), slowClient).mapCatching { it.field<CheckpointPreview>("preview") ?: CheckpointPreview() }

    suspend fun restore(path: String, id: String): Result<CheckpointPreview> =
        post("/restore", mapOf("project" to path, "id" to id), slowClient)
            .mapCatching { it.field<CheckpointPreview>("result") ?: CheckpointPreview() }
            .onSuccess { _changes.update { n -> n + 1 } }

    suspend fun diff(path: String, id: String, file: String): Result<String> =
        post("/diff", mapOf("project" to path, "id" to id, "path" to file), slowClient)
            .mapCatching { it["diff"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    suspend fun delete(
        path: String, ids: List<String>? = null, olderThanDays: Int? = null, all: Boolean = false, forget: Boolean = false
    ): Result<CheckpointDeleteResult> =
        post("/delete", mapOf("project" to path, "ids" to ids, "older_than_days" to olderThanDays, "all" to all, "forget" to forget), slowClient)
            .mapCatching { it.field<CheckpointDeleteResult>("result") ?: CheckpointDeleteResult() }
            .onSuccess {
                val gone = ids?.toSet()
                _byKey.update { cur -> if (all || olderThanDays != null) emptyMap() else cur.filterValues { it.id !in gone.orEmpty() } }
                _changes.update { n -> n + 1 }
            }
}
