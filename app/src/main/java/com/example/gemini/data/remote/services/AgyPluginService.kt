package com.example.gemini.data.remote.services

import android.util.Base64
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.dto.*
import exa.language_server_pb.DeletePluginRequest
import exa.language_server_pb.DownloadBuildWithGooglePluginRequest
import exa.language_server_pb.GetAllPluginsRequest
import exa.language_server_pb.GetAllSkillsRequest
import exa.language_server_pb.GetAvailableCascadePluginsRequest
import exa.language_server_pb.GetBuildWithGooglePluginsRequest
import exa.language_server_pb.Metadata
import exa.language_server_pb.WriteFileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service for Google Plugins, Cascade Marketplace, Skills, and MCP auto-configuration.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyPluginService(
    private val projectService: AgyProjectService = AgyProjectService.instance,
    private val mcpService: AgyMcpService = AgyMcpService.instance
) {
    companion object {
        private const val TAG = "AgyPluginService"
        val instance by lazy { AgyPluginService() }
    }

    /**
     * Queries available Cascade plugins & MCP packages.
     */
    suspend fun getAvailableCascadePlugins(
        os: String = "linux",
        searchQuery: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<AvailableCascadePluginDto>> = withContext(Dispatchers.IO) {
        val req = GetAvailableCascadePluginsRequest(
            os = os,
            search_query = searchQuery,
            metadata = Metadata()
        )
        AgyLanguageService.GetAvailableCascadePlugins().executeSafely(req).map { res ->
            res.plugins.map { p ->
                val localDto = p.local?.let { loc ->
                    CascadePluginLocalDto(
                        commands = loc.commands.mapNotNull { (cmdKey, cmdVal) ->
                            if (cmdVal == null) return@mapNotNull null
                            cmdKey to CascadePluginCommandWrapperDto(
                                template = cmdVal.template?.let { t ->
                                    CascadePluginCommandTemplateDto(
                                        command = t.command,
                                        args = t.args,
                                        env = t.env
                                    )
                                }
                            )
                        }.toMap()
                    )
                }

                val remoteDto = p.remote?.let { rem ->
                    CascadePluginRemoteDto(
                        template = rem.template?.let { t ->
                            CascadePluginRemoteTemplateDto(
                                serverUrl = t.server_url,
                                authProviderType = t.auth_provider_type
                            )
                        }
                    )
                }

                AvailableCascadePluginDto(
                    id = p.id,
                    title = p.title,
                    description = p.description,
                    link = p.link,
                    readme = p.readme,
                    trustLevel = p.trust_level,
                    local = localDto,
                    remote = remoteDto
                )
            }
        }
    }

    // Cached dynamically discovered global config URI (e.g. from GetAllPlugins or GetAllSkills)
    @Volatile
    private var cachedGlobalConfigUri: String? = null

    /**
     * Resolves potential mcp_config.json URIs dynamically across Android/Termux and Desktop/Linux.
     */
    private fun getCandidateConfigUris(): List<String> {
        val list = mutableListOf<String>()

        // 1. If discovered from AGY Language Server
        cachedGlobalConfigUri?.let { list.add(it) }

        // 2. Termux / Android environment HOME
        val envHome = System.getenv("HOME") ?: System.getenv("TERMUX_HOME")
        if (!envHome.isNullOrBlank()) {
            val h = envHome.trimEnd('/')
            list.add("file://$h/.gemini/config/mcp_config.json")
            list.add("file://$h/.gemini/antigravity/mcp_config.json")
        }

        // 3. Android / Termux standard sandboxes
        list.add("file:///data/data/com.termux/files/home/.gemini/config/mcp_config.json")
        list.add("file:///data/user/0/com.termux/files/home/.gemini/config/mcp_config.json")

        // 4. Java user.home
        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            val uh = userHome.trimEnd('/')
            list.add("file://$uh/.gemini/config/mcp_config.json")
        }

        return list.distinct()
    }

    /**
     * Installs an MCP / Cascade plugin by reading mcp_config.json, merging the server spec,
     * writing via dynamic WriteFile RPC or IdeApiClient, and triggering RefreshMcpServers.
     */
    suspend fun installCascadePlugin(
        plugin: AvailableCascadePluginDto,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val candidateUris = getCandidateConfigUris()
            var currentJsonStr = ""
            var targetWriteUri: String? = null

            // 1. Try reading via IdeApiClient (works on local Termux / daemon)
            try {
                val ideConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig()
                if (!ideConfig.isNullOrBlank() && ideConfig.trim().startsWith("{")) {
                    currentJsonStr = ideConfig
                }
            } catch (e: Exception) {
                Log.w(TAG, "IdeApiClient.getMcpConfig failed: ${e.message}")
            }

            // 2. If not read yet, try reading from candidate URIs via LanguageServer ReadFile RPC
            if (currentJsonStr.isBlank()) {
                for (uri in candidateUris) {
                    val readRes = projectService.readFileAsBase64(uri, hubUrl)
                    if (readRes.isSuccess) {
                        val b64 = readRes.getOrThrow()
                        if (b64.isNotBlank()) {
                            try {
                                val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                                if (decoded.trim().startsWith("{")) {
                                    currentJsonStr = decoded
                                    targetWriteUri = uri
                                    cachedGlobalConfigUri = uri
                                    break
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Decode error for $uri: ${e.message}")
                            }
                        }
                    }
                }
            }

            val rootObj = if (currentJsonStr.trim().startsWith("{")) JSONObject(currentJsonStr) else JSONObject()
            val mcpServersObj = rootObj.optJSONObject("mcpServers") ?: JSONObject().also { rootObj.put("mcpServers", it) }

            val serverKey = plugin.id.ifBlank { plugin.title.lowercase().replace("\\s+".toRegex(), "-") }

            // 3. Build server config object
            val serverSpecObj = JSONObject()
            if (plugin.local != null && plugin.local.commands.isNotEmpty()) {
                val cmdEntry = plugin.local.commands.values.firstOrNull()?.template
                if (cmdEntry != null) {
                    serverSpecObj.put("command", cmdEntry.command)
                    val argsArr = JSONArray()
                    cmdEntry.args.forEach { argsArr.put(it) }
                    serverSpecObj.put("args", argsArr)

                    val envMap = cmdEntry.env.toMutableMap()
                    // Android / Termux environment loader injection
                    val isAndroidTermux = java.io.File("/data/data/com.termux/files/usr/lib/libtermux-exec.so").exists() ||
                                          System.getenv("PREFIX") != null ||
                                          (System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true)
                    if (isAndroidTermux) {
                        if (!envMap.containsKey("LD_PRELOAD") && java.io.File("/data/data/com.termux/files/usr/lib/libtermux-exec.so").exists()) {
                            envMap["LD_PRELOAD"] = "/data/data/com.termux/files/usr/lib/libtermux-exec.so"
                        }
                        if (!envMap.containsKey("PATH")) {
                            envMap["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin"
                        }
                    }

                    if (envMap.isNotEmpty()) {
                        val envObj = JSONObject()
                        envMap.forEach { (k, v) -> envObj.put(k, v) }
                        serverSpecObj.put("env", envObj)
                    }
                }
            } else if (plugin.remote != null && plugin.remote.template != null) {
                serverSpecObj.put("serverUrl", plugin.remote.template.serverUrl)
                if (plugin.remote.template.authProviderType.isNotBlank()) {
                    serverSpecObj.put("authProviderType", plugin.remote.template.authProviderType)
                }
            }

            mcpServersObj.put(serverKey, serverSpecObj)

            // 4. Save updated JSON (multi-strategy: IdeApiClient + WriteFile RPC to discovered URIs)
            val updatedJsonStr = rootObj.toString(2)
            var saveSucceeded = false

            // Try IdeApiClient save
            try {
                saveSucceeded = com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(updatedJsonStr)
            } catch (e: Exception) {
                Log.w(TAG, "IdeApiClient.saveMcpConfig error: ${e.message}")
            }

            // Also try WriteFile RPC to candidate URIs
            val updatedB64 = Base64.encodeToString(updatedJsonStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val writeUris = if (!targetWriteUri.isNullOrBlank()) listOf(targetWriteUri) else candidateUris

            for (wUri in writeUris) {
                val writeRes = writeFile(wUri, updatedB64, overwrite = true, hubUrl = hubUrl)
                if (writeRes.isSuccess) {
                    saveSucceeded = true
                    cachedGlobalConfigUri = wUri
                    break
                }
            }

            if (!saveSucceeded) {
                return@withContext Result.failure(Exception("Failed to save mcp_config.json to phone/system paths."))
            }

            // 5. Trigger RefreshMcpServers
            mcpService.refreshMcpServers(hubUrl)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "installCascadePlugin failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Discovers official Google Plugins catalog.
     */
    suspend fun getBuildWithGooglePlugins(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<BuildWithGooglePluginItemDto>> = withContext(Dispatchers.IO) {
        val req = GetBuildWithGooglePluginsRequest()
        AgyLanguageService.GetBuildWithGooglePlugins().executeSafely(req).map { res ->
            res.plugins.map { item ->
                val pluginDto = item.plugin?.let { p ->
                    val localDto = p.local?.let { loc ->
                        PluginLocalConfigDto(
                            commands = loc.commands.mapNotNull { (cmdKey, cmdVal) ->
                                if (cmdVal == null) return@mapNotNull null
                                cmdKey to PluginCommandSpecDto(
                                    commandTemplate = cmdVal.command_template?.let { t ->
                                        PluginCommandTemplateDto(
                                            command = t.command,
                                            args = t.args,
                                            env = t.env
                                        )
                                    },
                                    variables = cmdVal.variables.map { v ->
                                        PluginConfigVariableDto(
                                            name = v.name,
                                            title = v.title,
                                            description = v.description
                                        )
                                    }
                                )
                            }.toMap()
                        )
                    }

                    val remoteDto = p.remote?.let { rem ->
                        PluginRemoteConfigDto(
                            remoteTemplate = rem.remote_template?.let { t ->
                                PluginRemoteTemplateDto(serverUrl = t.server_url)
                            }
                        )
                    }

                    BuildWithGooglePluginDto(
                        name = p.name,
                        uid = p.uid,
                        description = p.description,
                        trustLevel = p.trust_level,
                        local = localDto,
                        remote = remoteDto
                    )
                }

                BuildWithGooglePluginItemDto(
                    plugin = pluginDto,
                    gstatic = item.gstatic?.let { GstaticLinkDto(link = it.link) },
                    versionShas = item.version_shas,
                    visibility = item.visibility.name
                )
            }
        }
    }

    /**
     * Downloads and installs an official Google Plugin bundle.
     */
    suspend fun downloadBuildWithGooglePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val req = DownloadBuildWithGooglePluginRequest(plugin_id = pluginId)
        AgyLanguageService.DownloadBuildWithGooglePlugin().executeSafely(req).map { res ->
            if (res.success) {
                mcpService.refreshMcpServers(hubUrl)
                res.message.ifBlank { "Plugin installed successfully" }
            } else {
                throw Exception(res.message.ifBlank { "Download failed" })
            }
        }
    }

    /**
     * Deletes / uninstalls a plugin bundle by its pluginId.
     */
    suspend fun deletePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val req = DeletePluginRequest(plugin_id = pluginId)
        AgyLanguageService.DeletePlugin().executeSafely(req).map { res ->
            if (res.success) {
                mcpService.refreshMcpServers(hubUrl)
                res.message.ifBlank { "Plugin deleted successfully" }
            } else {
                throw Exception(res.message.ifBlank { "Delete failed" })
            }
        }
    }

    /**
     * Lists all locally installed plugins.
     */
    suspend fun getAllPlugins(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<InstalledPluginDto>> = withContext(Dispatchers.IO) {
        val req = GetAllPluginsRequest()
        AgyLanguageService.GetAllPlugins().executeSafely(req).map { res ->
            val list = res.plugins.map { p ->
                val skillsList = p.skills.map { s ->
                    InstalledSkillDto(
                        name = s.name,
                        description = s.description,
                        path = s.path,
                        content = s.content,
                        baseDir = s.base_dir
                    )
                }

                InstalledPluginDto(
                    name = p.name,
                    displayName = p.name,
                    description = p.description,
                    path = p.path,
                    isGlobal = p.is_global,
                    skills = skillsList
                )
            }

            val globalPlugin = list.firstOrNull { it.isGlobal && it.path.isNotBlank() }
            if (globalPlugin != null) {
                val p = globalPlugin.path
                val idx = p.indexOf("/plugins/")
                if (idx != -1) {
                    val root = p.substring(0, idx)
                    val formatted = if (root.startsWith("file://")) root else "file://$root"
                    cachedGlobalConfigUri = "$formatted/mcp_config.json"
                }
            }

            list
        }
    }

    /**
     * Lists all skills (global or workspace-scoped).
     */
    suspend fun getAllSkills(
        workspaceUris: List<String> = emptyList(),
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<SkillDefinitionDto>> = withContext(Dispatchers.IO) {
        val req = GetAllSkillsRequest(workspace_uris = workspaceUris)
        AgyLanguageService.GetAllSkills().executeSafely(req).map { res ->
            val list = res.skills.map { s ->
                SkillDefinitionDto(
                    path = s.path,
                    name = s.name,
                    displayName = s.display_name.ifBlank { s.name },
                    description = s.description,
                    content = s.content,
                    isBuiltin = s.is_builtin,
                    pluginName = s.plugin_name.takeIf { it.isNotBlank() },
                    logo = s.logo.takeIf { it.isNotBlank() },
                    baseDir = s.base_dir,
                    discoveredIn = s.discovered_in,
                    discoveryCategory = s.discovery_category.name
                )
            }

            val globalSkill = list.firstOrNull { it.path.contains("/.gemini/") }
            if (globalSkill != null && cachedGlobalConfigUri == null) {
                val p = globalSkill.path
                val idx = p.indexOf("/.gemini/")
                if (idx != -1) {
                    val root = p.substring(0, idx + "/.gemini".length)
                    val formatted = if (root.startsWith("file://")) root else "file://$root"
                    cachedGlobalConfigUri = "$formatted/config/mcp_config.json"
                }
            }

            list
        }
    }

    /**
     * Writes Base64 encoded content to file via WriteFile RPC.
     */
    suspend fun writeFile(
        uri: String,
        base64Content: String,
        overwrite: Boolean = true,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val formattedUri = if (uri.startsWith("file://") || uri.startsWith("http://") || uri.startsWith("https://")) {
            uri
        } else {
            "file://$uri"
        }
        val bytes = base64Content.decodeBase64() ?: ByteString.EMPTY
        val req = WriteFileRequest(
            uri = formattedUri,
            content = bytes,
            overwrite = overwrite
        )
        AgyLanguageService.WriteFile().executeSafely(req).map { }
    }
}
