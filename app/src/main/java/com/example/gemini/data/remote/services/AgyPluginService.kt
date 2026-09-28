package com.example.gemini.data.remote.services

import android.util.Base64
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyGrpcClient
import com.example.gemini.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service for Google Plugins, Cascade Marketplace, Skills, and MCP auto-configuration.
 */
class AgyPluginService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance,
    private val projectService: AgyProjectService = AgyProjectService.instance,
    private val mcpService: AgyMcpService = AgyMcpService.instance
) {
    companion object {
        private const val TAG = "AgyPluginService"
        val instance by lazy { AgyPluginService() }

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    /**
     * Queries available Cascade plugins & MCP packages.
     */
    suspend fun getAvailableCascadePlugins(
        os: String = "linux",
        searchQuery: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<AvailableCascadePluginDto>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("os", os)
                if (searchQuery.isNotBlank()) {
                    put("searchQuery", searchQuery)
                }
            }.toString()

            val res = grpcClient.callUnary("GetAvailableCascadePlugins", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetAvailableCascadePlugins failed"))
            }
            val parsed = jsonParser.decodeFromString<GetAvailableCascadePluginsResponseDto>(res.getOrThrow())
            Result.success(parsed.plugins)
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableCascadePlugins failed: ${e.message}", e)
            Result.failure(e)
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
        try {
            val res = grpcClient.callUnary("GetBuildWithGooglePlugins", "{}", hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetBuildWithGooglePlugins failed"))
            }
            val parsed = jsonParser.decodeFromString<GetBuildWithGooglePluginsResponseDto>(res.getOrThrow())
            Result.success(parsed.plugins)
        } catch (e: Exception) {
            Log.e(TAG, "getBuildWithGooglePlugins failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads and installs an official Google Plugin bundle.
     */
    suspend fun downloadBuildWithGooglePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("pluginId", pluginId)
            }.toString()

            val res = grpcClient.callUnary("DownloadBuildWithGooglePlugin", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("DownloadBuildWithGooglePlugin failed"))
            }
            val parsed = jsonParser.decodeFromString<DownloadBuildWithGooglePluginResponseDto>(res.getOrThrow())
            if (parsed.success) {
                // Refresh MCP servers in case plugin bundle bundled MCP configurations
                mcpService.refreshMcpServers(hubUrl)
                Result.success(parsed.message.ifBlank { "Plugin installed successfully" })
            } else {
                Result.failure(Exception(parsed.message.ifBlank { "Download failed" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadBuildWithGooglePlugin failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes / uninstalls a plugin bundle by its pluginId.
     */
    suspend fun deletePlugin(
        pluginId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("pluginId", pluginId)
            }.toString()

            val res = grpcClient.callUnary("DeletePlugin", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("DeletePlugin failed"))
            }
            val parsed = jsonParser.decodeFromString<DeletePluginResponseDto>(res.getOrThrow())
            if (parsed.success) {
                mcpService.refreshMcpServers(hubUrl)
                Result.success(parsed.message.ifBlank { "Plugin deleted successfully" })
            } else {
                Result.failure(Exception(parsed.message.ifBlank { "Delete failed" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePlugin failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lists all locally installed plugins.
     */
    suspend fun getAllPlugins(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<InstalledPluginDto>> = withContext(Dispatchers.IO) {
        try {
            val res = grpcClient.callUnary("GetAllPlugins", "{}", hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetAllPlugins failed"))
            }
            val parsed = jsonParser.decodeFromString<GetAllPluginsResponseDto>(res.getOrThrow())
            val globalPlugin = parsed.plugins.firstOrNull { it.isGlobal && it.path.isNotBlank() }
            if (globalPlugin != null) {
                val p = globalPlugin.path
                val idx = p.indexOf("/plugins/")
                if (idx != -1) {
                    val root = p.substring(0, idx)
                    val formatted = if (root.startsWith("file://")) root else "file://$root"
                    cachedGlobalConfigUri = "$formatted/mcp_config.json"
                }
            }
            Result.success(parsed.plugins)
        } catch (e: Exception) {
            Log.e(TAG, "getAllPlugins failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lists all skills (global or workspace-scoped).
     */
    suspend fun getAllSkills(
        workspaceUris: List<String> = emptyList(),
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<SkillDefinitionDto>> = withContext(Dispatchers.IO) {
        try {
            val payload = if (workspaceUris.isNotEmpty()) {
                val arr = JSONArray()
                workspaceUris.forEach { arr.put(it) }
                JSONObject().put("workspaceUris", arr).toString()
            } else {
                "{}"
            }

            val res = grpcClient.callUnary("GetAllSkills", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("GetAllSkills failed"))
            }
            val parsed = jsonParser.decodeFromString<GetAllSkillsResponseDto>(res.getOrThrow())
            val globalSkill = parsed.skills.firstOrNull { it.path.contains("/.gemini/") }
            if (globalSkill != null && cachedGlobalConfigUri == null) {
                val p = globalSkill.path
                val idx = p.indexOf("/.gemini/")
                if (idx != -1) {
                    val root = p.substring(0, idx + "/.gemini".length)
                    val formatted = if (root.startsWith("file://")) root else "file://$root"
                    cachedGlobalConfigUri = "$formatted/config/mcp_config.json"
                }
            }
            Result.success(parsed.skills)
        } catch (e: Exception) {
            Log.e(TAG, "getAllSkills failed: ${e.message}", e)
            Result.failure(e)
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
        try {
            val formattedUri = if (uri.startsWith("file://") || uri.startsWith("http://") || uri.startsWith("https://")) {
                uri
            } else {
                "file://$uri"
            }
            val payload = JSONObject().apply {
                put("uri", formattedUri)
                put("content", base64Content)
                put("overwrite", overwrite)
            }.toString()

            val res = grpcClient.callUnary("WriteFile", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("WriteFile failed"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "writeFile failed for $uri: ${e.message}", e)
            Result.failure(e)
        }
    }
}
