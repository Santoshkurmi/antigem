package com.example.gemini.data.agent.agy

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.data.remote.services.AgyLanguageService
import com.example.gemini.data.remote.services.executeSafely
import exa.language_server_pb.BackendType
import exa.language_server_pb.CreateProjectRequest
import exa.language_server_pb.DeleteProjectRequest
import exa.language_server_pb.DisconnectRemotePluginRequest
import exa.language_server_pb.FetchSandboxPortRequest
import exa.language_server_pb.FetchSandboxPortResponse
import exa.language_server_pb.ListAvailableResourcesRequest
import exa.language_server_pb.ListProjectsRequest
import exa.language_server_pb.ListRemotePluginsRequest
import exa.language_server_pb.Project
import exa.language_server_pb.ReadProjectRequest
import exa.language_server_pb.RemoteAgentProxyPluginconnection_State
import exa.language_server_pb.RemoteResource
import exa.language_server_pb.Resource
import exa.language_server_pb.Resources
import exa.language_server_pb.SandboxHttpHeader
import exa.language_server_pb.UpdateProjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

/**
 * Antigravity cloud agents (what Antigravity calls Remote Agents): chats that run in a Google-managed VM with the
 * project's GitHub repos cloned in, and keep working when the phone is off. Everything goes through the agy hub, the
 * same way the Antigravity app does it (requests marked with the remote backend); the app never calls Google itself.
 *
 * Needs Google AI Pro or Ultra on a personal account. The hub refuses the VM terminal for these chats, so commands
 * are run by asking the agent; a port the agent serves can be fetched for a preview ([fetchPort]).
 */
object AgyCloud {
    private val BACKEND = BackendType.BACKEND_TYPE_REMOTE_AICODE
    const val WEB_URL = "https://antigravity.google.com/remote"

    data class CloudProject(val id: String, val name: String, val repos: List<RemoteResource>, val raw: Project)

    data class CloudPlugin(
        val id: String,
        val name: String,
        val description: String,
        val logoUrl: String,
        val connected: Boolean,
        val expired: Boolean,
        val account: String
    )

    /** GitHub is not connected to the account's cloud agents yet (the repo list needs it). */
    class GitHubNotConnected : Exception("GitHub is not connected to your Antigravity cloud agents yet.")

    private var sp: SharedPreferences? = null

    private val _projects = MutableStateFlow<List<CloudProject>>(emptyList())
    val projects: StateFlow<List<CloudProject>> = _projects.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Why cloud projects could not be loaded (not signed in, not eligible, hub offline), or null. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The cloud project new Antigravity chats start in; null = they run on the hub's machine (as before). */
    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    /**
     * Settings → Antigravity → Cloud agents. Off (the default): the app never asks the hub for cloud projects or
     * cloud chats, and new chats run on the hub's machine.
     */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Called when cloud agents are switched on or off: the chat list adds or drops its cloud part. */
    @Volatile var onEnabledChanged: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("agy_cloud", Context.MODE_PRIVATE)
        sp = p
        _enabled.value = p.getBoolean("enabled", false)
        // a cloud project picked earlier counts only while cloud agents are on
        _selectedProjectId.value = if (_enabled.value) p.getString("selected_project", null) else null
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        sp?.edit()?.putBoolean("enabled", on)?.apply()
        if (!on) {
            // new chats run on the hub's machine again; nothing cloud is shown
            select(null)
            _projects.value = emptyList()
            _error.value = null
        }
        onEnabledChanged?.invoke(on)
    }

    fun select(projectId: String?) {
        _selectedProjectId.value = projectId
        sp?.edit()?.apply { if (projectId == null) remove("selected_project") else putString("selected_project", projectId) }?.apply()
    }

    fun projectName(id: String?): String? = id?.let { pid -> _projects.value.firstOrNull { it.id == pid }?.name }

    // ------------------------------------------------------------------------------------------------ projects

    /**
     * The hub refuses cloud calls with FAILED_PRECONDITION ("cloud conversations are not enabled") for about a minute
     * after it starts: its account feature switch for cloud agents (`enable-remote-ai`) only turns on with its first
     * feature-flag poll, ~60 s after start (tested with agy 1.5.3; the hub's own web UI waits the same way).
     */
    fun isWarmingUp(message: String?): Boolean {
        val m = message.orEmpty()
        return m.contains("FAILED_PRECONDITION", true) || m.contains("not enabled", true) || m.contains("Precondition", true)
    }

    /** Called when the cloud project list loaded: the chat list may (re)start its cloud part. */
    @Volatile var onAvailable: (() -> Unit)? = null

    suspend fun refreshProjects(): Result<List<CloudProject>> = withContext(Dispatchers.IO) {
        if (!_enabled.value) return@withContext Result.success(emptyList())
        _loading.value = true
        try {
            // while the hub warms up (about a minute after it starts), try again quietly before showing an error
            var tries = 0
            var res = AgyLanguageService.ListProjects().executeSafely(ListProjectsRequest(backend = BACKEND))
            while (res.isFailure && isWarmingUp(res.exceptionOrNull()?.message) && tries < 30 && _enabled.value) {
                kotlinx.coroutines.delay(3_000L)
                tries++
                res = AgyLanguageService.ListProjects().executeSafely(ListProjectsRequest(backend = BACKEND))
            }
            if (!_enabled.value) return@withContext Result.success(emptyList())
            val ids = res
                .getOrElse { e -> _error.value = friendlyError(e.message); return@withContext Result.failure(e) }
                .project_ids
            val list = coroutineScope {
                ids.map { id -> async { read(id) } }.awaitAll().filterNotNull()
            }.sortedByDescending { it.raw.updated_at?.seconds ?: 0L }
            _projects.value = list
            val wasFailing = _error.value != null
            _error.value = null
            if (wasFailing || list.isNotEmpty()) onAvailable?.invoke()
            // a project deleted elsewhere is no longer where new chats start
            if (_selectedProjectId.value != null && list.none { it.id == _selectedProjectId.value }) select(null)
            Result.success(list)
        } finally {
            _loading.value = false
        }
    }

    private suspend fun read(id: String): CloudProject? {
        val p = AgyLanguageService.ReadProject().executeSafely(ReadProjectRequest(id = id)).getOrNull()?.project ?: return null
        return CloudProject(p.id.ifBlank { id }, p.name.ifBlank { "Cloud project" }, repos(p), p)
    }

    private fun repos(p: Project): List<RemoteResource> =
        p.project_resources?.resources.orEmpty().mapNotNull { it.remote_resource }

    /**
     * Creates a cloud project with [repos]; returns its id. The id is made here: the hub refuses a project without one
     * ("project id "" is not a canonical lowercase UUID").
     */
    suspend fun createProject(name: String, repos: List<RemoteResource>): Result<String> = withContext(Dispatchers.IO) {
        val project = Project(
            id = java.util.UUID.randomUUID().toString().lowercase(),
            name = name.trim().ifBlank { "Cloud project" },
            backend_type = BACKEND,
            project_resources = Resources(resources = repos.map { Resource(remote_resource = it) })
        )
        AgyLanguageService.CreateProject().executeSafely(CreateProjectRequest(project = project, backend = BACKEND))
            .getOrElse { return@withContext Result.failure(Exception(friendlyError(it.message))) }
        refreshProjects()
        Result.success(project.id)
    }

    /** Replaces the project's repos (other resources, like Drive files, stay). */
    suspend fun setRepos(projectId: String, repos: List<RemoteResource>): Result<Unit> = updateProject(projectId) { p ->
        val others = p.project_resources?.resources.orEmpty().filter { it.remote_resource?.type?.startsWith("github") != true }
        p.copy(project_resources = Resources(resources = others + repos.map { Resource(remote_resource = it) }))
    }

    suspend fun rename(projectId: String, name: String): Result<Unit> = updateProject(projectId, rename = true) { it.copy(name = name.trim()) }

    private suspend fun updateProject(projectId: String, rename: Boolean = false, change: (Project) -> Project): Result<Unit> = withContext(Dispatchers.IO) {
        val current = AgyLanguageService.ReadProject().executeSafely(ReadProjectRequest(id = projectId)).getOrNull()?.project
            ?: return@withContext Result.failure(Exception("The project was not found."))
        AgyLanguageService.UpdateProject().executeSafely(UpdateProjectRequest(project = change(current), rename = rename))
            .getOrElse { return@withContext Result.failure(Exception(friendlyError(it.message))) }
        refreshProjects()
        Result.success(Unit)
    }

    /** Deletes the project with its cloud chats (in Google's cloud). */
    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.DeleteProject().executeSafely(DeleteProjectRequest(id = projectId))
            .getOrElse { return@withContext Result.failure(Exception(friendlyError(it.message))) }
        if (_selectedProjectId.value == projectId) select(null)
        refreshProjects()
        Result.success(Unit)
    }

    // ------------------------------------------------------------------------------------------------ GitHub and plugins

    /** The GitHub repos the account connected (on antigravity.google.com); [GitHubNotConnected] before that. */
    suspend fun githubRepos(): Result<List<RemoteResource>> = withContext(Dispatchers.IO) {
        AgyLanguageService.ListAvailableResources().executeSafely(ListAvailableResourcesRequest(backend = BACKEND, type = "github"))
            .map { r -> r.resources.sortedBy { it.display_name.lowercase() } }
            .recoverCatching { e ->
                val m = e.message.orEmpty()
                // "cloud conversations are not enabled" = the hub is still starting, not a missing GitHub connection
                if (!m.contains("not enabled", true) && (m.contains("FAILED_PRECONDITION", true) || m.contains("Precondition", true))) throw GitHubNotConnected()
                throw Exception(friendlyError(m))
            }
    }

    suspend fun plugins(): Result<List<CloudPlugin>> = withContext(Dispatchers.IO) {
        AgyLanguageService.ListRemotePlugins().executeSafely(ListRemotePluginsRequest(backend = BACKEND))
            .map { r ->
                r.plugins.map { p ->
                    val live = p.connections.firstOrNull { it.state == RemoteAgentProxyPluginconnection_State.REMOTEAGENTPROXYPLUGINCONNECTION_STATE_CONNECTED }
                    CloudPlugin(
                        id = p.plugin_id,
                        name = p.display_name.ifBlank { p.plugin_id },
                        description = p.description,
                        logoUrl = p.logo_uri,
                        connected = live != null,
                        expired = live == null && p.connections.any { it.state == RemoteAgentProxyPluginconnection_State.EXPIRED },
                        account = (live ?: p.connections.firstOrNull())?.account.orEmpty()
                    )
                }
            }
            .recoverCatching { throw Exception(friendlyError(it.message)) }
    }

    /**
     * Where to connect [pluginId] (e.g. "github"), as the Antigravity app opens it: the plugin's page on
     * antigravity.google.com (the hub's `connect_uri`) through Google's account chooser with the email agy is signed in
     * with, so the browser shows the page as that account (another account gets the site's Remote Control setup
     * instead). Without a page, the service's own sign-in (`authorize_uri`), as the app falls back to.
     */
    suspend fun connectUrl(pluginId: String): Result<String> = withContext(Dispatchers.IO) {
        AgyLanguageService.ListRemotePlugins().executeSafely(ListRemotePluginsRequest(backend = BACKEND))
            .map { r ->
                val p = r.plugins.firstOrNull { it.plugin_id == pluginId } ?: error("The hub doesn't list $pluginId for cloud agents.")
                val page = p.connect_uri.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                if (page == null) {
                    p.authorize_uri.ifBlank { error("The hub gave no link to connect ${p.display_name.ifBlank { pluginId }}.") }
                } else {
                    val email = AgyLanguageService.GetUserStatus()
                        .executeSafely(exa.language_server_pb.GetUserStatusRequest(metadata = exa.language_server_pb.Metadata()))
                        .getOrNull()?.user_status?.email?.takeIf { it.isNotBlank() && it != "null" }
                    if (email == null) page
                    else android.net.Uri.parse("https://accounts.google.com/AccountChooser").buildUpon()
                        .appendQueryParameter("Email", email)
                        .appendQueryParameter("continue", page)
                        .build().toString()
                }
            }
            .recoverCatching { throw Exception(friendlyError(it.message)) }
    }

    suspend fun disconnectPlugin(plugin: CloudPlugin): Result<Unit> = withContext(Dispatchers.IO) {
        AgyLanguageService.DisconnectRemotePlugin()
            .executeSafely(DisconnectRemotePluginRequest(backend = BACKEND, plugin_id = plugin.id, account = plugin.account))
            .map { }
            .recoverCatching { throw Exception(friendlyError(it.message)) }
    }

    // ------------------------------------------------------------------------------------------------ preview

    /** One HTTP request to [port] inside the chat's cloud VM (for the web preview). */
    suspend fun fetchPort(
        conversationId: String,
        port: Int,
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): Result<FetchSandboxPortResponse> = withContext(Dispatchers.IO) {
        AgyLanguageService.FetchSandboxPort().executeSafely(
            FetchSandboxPortRequest(
                conversation_id = conversationId,
                port = port,
                method = method,
                path = path.ifBlank { "/" },
                headers = headers.map { (k, v) -> SandboxHttpHeader(name = k, value_ = v) },
                body = body?.toByteString() ?: okio.ByteString.EMPTY
            )
        ).recoverCatching { throw Exception(friendlyError(it.message)) }
    }

    // ------------------------------------------------------------------------------------------------ errors

    /** Google's limits and refusals in plain words (the hub passes their reason codes through). */
    fun friendlyError(raw: String?): String {
        val m = raw.orEmpty()
        return when {
            isWarmingUp(m) && m.contains("not enabled", true) ->
                "Cloud agents start about a minute after the Antigravity server starts. Try again in a moment."
            m.contains("REMOTE_AGENT_AT_CAPACITY") -> "Cloud agents are busy right now. Try again in a few minutes."
            m.contains("REMOTE_AGENT_ENVIRONMENT_TIME_EXHAUSTED") -> "Today's cloud VM time is used up. It resets tomorrow."
            m.contains("REMOTE_AGENT_ENVIRONMENT_LIMIT") -> "Too many cloud environments are running. Let one finish, then try again."
            m.contains("REMOTE_AGENT_ACTIVE_CONVERSATION_LIMIT") -> "Today's limit of cloud chats is reached. It resets tomorrow."
            m.contains("REMOTE_AGENT_CONVERSATION_LIMIT") -> "The limit of cloud chats is reached. Delete old cloud chats to start new ones."
            m.contains("PERMISSION_DENIED", true) && m.contains("terminal", true) -> "The cloud VM has no terminal for apps. Ask the agent to run the command."
            m.contains("PERMISSION_DENIED", true) || m.contains("not eligible", true) ->
                "Cloud agents need Google AI Pro or Ultra on a personal Google account (and agy 1.5 or newer)."
            m.contains("unimplemented", true) -> "This agy does not support cloud agents yet. Update agy in Settings → Antigravity CLI."
            m.isBlank() -> "Something went wrong with the cloud agent."
            else -> m.substringAfterLast("): ").ifBlank { m }.take(300)
        }
    }
}
