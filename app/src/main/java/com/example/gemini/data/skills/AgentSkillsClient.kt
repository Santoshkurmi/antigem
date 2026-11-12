package com.example.gemini.data.skills

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import com.example.gemini.domain.model.AgentKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Where a skill folder came from (its `.antigem-source.json`), when it was added from GitHub. */
@Serializable
data class SkillSourceDto(
    val repo: String = "",
    val ref: String = "",
    val path: String = "",
    @SerialName("installed_at") val installedAt: String = ""
)

/** One skill in an agent's own skills folder. kind: skill, or plugin (a Claude Code plugin folder kept there). */
@Serializable
data class AgentSkillDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val path: String = "",
    val kind: String = "skill",
    val source: SkillSourceDto? = null
)

/** One skill found in a GitHub repository; [exists]: the agent already has a folder with this id. */
@Serializable
data class RepoSkillDto(
    val path: String = "",
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val exists: Boolean = false,
    @SerialName("same_source") val sameSource: Boolean = false
)

@Serializable
data class AgentSkillsListResponse(
    val success: Boolean = false,
    val error: String? = null,
    val dir: String = "",
    val skills: List<AgentSkillDto> = emptyList()
)

/** pluginKinds: claude-marketplace, claude-plugin, codex-plugin, agy-plugin (the plugin formats the repo holds). */
@Serializable
data class RepoSkillsInspectResponse(
    val success: Boolean = false,
    val error: String? = null,
    val repo: String = "",
    val ref: String = "",
    val subpath: String = "",
    val skills: List<RepoSkillDto> = emptyList(),
    @SerialName("plugin_kinds") val pluginKinds: List<String> = emptyList()
)

@Serializable
data class AgentSkillsInstallResponse(
    val success: Boolean = false,
    val error: String? = null,
    val installed: List<String> = emptyList(),
    val replaced: List<String> = emptyList()
)

@Serializable
private data class SimpleReply(val success: Boolean = false, val error: String? = null, val output: String? = null)

/**
 * The bridge's `/api/agent-skills/` endpoints: each agent's own skills folder (add from GitHub, update, remove)
 * and Antigravity plugins installed from GitHub with agy's own installer. The bridge does the downloads and file
 * work where the agents run (this phone, or the PC in server mode).
 */
object AgentSkillsClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _changes = kotlinx.coroutines.flow.MutableSharedFlow<AgentKind>(extraBufferCapacity = 8)
    /** An agent's skills or plugins were changed here: its "/" menu is read again (the app's view model listens). */
    val changes: kotlinx.coroutines.flow.SharedFlow<AgentKind> = _changes
    private val jsonType = "application/json".toMediaType()

    /** Downloads and agy's installer can take a while on a phone. */
    private val slowClient by lazy {
        AgyBridgeService.instance.client.newBuilder().readTimeout(6, TimeUnit.MINUTES).callTimeout(7, TimeUnit.MINUTES).build()
    }

    fun agentId(agent: AgentKind): String = when (agent) {
        AgentKind.CLAUDE -> "claude"
        AgentKind.CODEX -> "codex"
        AgentKind.AGY -> "agy"
        AgentKind.OPENCODE -> "opencode"
    }

    private fun url(path: String) = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/agent-skills" + path

    private suspend fun send(request: Request, slow: Boolean): String = withContext(Dispatchers.IO) {
        (if (slow) slowClient else AgyBridgeService.instance.client).newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!text.trimStart().startsWith("{")) {
                // a bridge from before this feature answers a plain 404
                if (resp.code == 404) error("The local server is older than the app. Restart the app (or update the server) to manage skills here.")
                error("HTTP ${resp.code}" + text.trim().take(120).let { if (it.isBlank()) "" else ": $it" })
            }
            text
        }
    }

    private suspend inline fun <reified T> post(path: String, body: kotlinx.serialization.json.JsonObject, slow: Boolean = true): T =
        json.decodeFromString(send(Request.Builder().url(url(path)).post(body.toString().toRequestBody(jsonType)).build(), slow))

    suspend fun list(agent: AgentKind): Result<AgentSkillsListResponse> = runCatching {
        val res: AgentSkillsListResponse = json.decodeFromString(send(Request.Builder().url(url("/?agent=" + agentId(agent))).get().build(), slow = false))
        if (!res.success) error(res.error ?: "Could not list the skills")
        res
    }

    suspend fun inspect(agent: AgentKind, source: String): Result<RepoSkillsInspectResponse> = runCatching {
        val res: RepoSkillsInspectResponse = post("/inspect", buildJsonObject { put("source", source); put("agent", agentId(agent)) })
        if (!res.success) error(res.error ?: "Could not read the repository")
        res
    }

    /** [paths]: repository folders of the skills to add (empty = all); [replace]: also replace different skills of the same name. */
    suspend fun install(agent: AgentKind, source: String, paths: List<String>, replace: Boolean): Result<AgentSkillsInstallResponse> = runCatching {
        val res: AgentSkillsInstallResponse = post("/install", buildJsonObject {
            put("agent", agentId(agent))
            put("source", source)
            put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
            put("replace", replace)
        })
        if (!res.success) error(res.error ?: "Could not add the skills")
        _changes.tryEmit(agent)
        res
    }

    suspend fun update(agent: AgentKind, id: String): Result<Unit> = runCatching {
        val res: SimpleReply = post("/update", buildJsonObject { put("agent", agentId(agent)); put("id", id) })
        if (!res.success) error(res.error ?: "Could not update $id")
        _changes.tryEmit(agent)
    }

    suspend fun remove(agent: AgentKind, id: String): Result<Unit> = runCatching {
        val res: SimpleReply = post("/remove", buildJsonObject { put("agent", agentId(agent)); put("id", id) }, slow = false)
        if (!res.success) error(res.error ?: "Could not remove $id")
        _changes.tryEmit(agent)
    }

    /** Downloads the repository and runs `agy plugin install <folder>`; returns agy's output. */
    suspend fun installAgyPlugin(source: String): Result<String> = runCatching {
        val res: SimpleReply = post("/agy-plugin", buildJsonObject { put("source", source) })
        if (!res.success) error(listOfNotNull(res.error, res.output?.takeIf { it.isNotBlank() && res.error?.contains(it) != true }).joinToString("\n"))
        _changes.tryEmit(AgentKind.AGY)
        res.output.orEmpty()
    }
}
