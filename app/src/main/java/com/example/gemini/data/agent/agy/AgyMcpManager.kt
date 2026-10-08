package com.example.gemini.data.agent.agy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import com.example.gemini.data.agent.ChatSessionStore
import kotlinx.coroutines.CoroutineScope

/** Antigravity MCP servers and the Cascade MCP plugin marketplace. Moved out of ChatViewModel unchanged. */
class AgyMcpManager(
    private val backendScope: CoroutineScope,
    private val agyHubClient: AgyHubClient
) {

    private val _mcpServers = MutableStateFlow<List<com.example.gemini.domain.model.McpServerState>>(emptyList())
    val mcpServers: StateFlow<List<com.example.gemini.domain.model.McpServerState>> = _mcpServers.asStateFlow()

    private val _isMcpLoading = MutableStateFlow(false)
    val isMcpLoading: StateFlow<Boolean> = _isMcpLoading.asStateFlow()

    private val _isMcpRefreshing = MutableStateFlow(false)
    val isMcpRefreshing: StateFlow<Boolean> = _isMcpRefreshing.asStateFlow()

    private val _refreshingMcpServer = MutableStateFlow<String?>(null)
    val refreshingMcpServer: StateFlow<String?> = _refreshingMcpServer.asStateFlow()

    private val _mcpErrorMessage = MutableStateFlow<String?>(null)
    val mcpErrorMessage: StateFlow<String?> = _mcpErrorMessage.asStateFlow()

    private val _mcpStatusMessage = MutableStateFlow<String?>(null)
    val mcpStatusMessage: StateFlow<String?> = _mcpStatusMessage.asStateFlow()

    fun clearMcpStatus() {
        _mcpStatusMessage.value = null
        _mcpErrorMessage.value = null
    }

    private val _availableCascadePlugins = MutableStateFlow<List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto>>(emptyList())
    val availableCascadePlugins: StateFlow<List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto>> = _availableCascadePlugins.asStateFlow()

    private val _isCascadePluginsLoading = MutableStateFlow(false)
    val isCascadePluginsLoading: StateFlow<Boolean> = _isCascadePluginsLoading.asStateFlow()

    private val _installingCascadePluginId = MutableStateFlow<String?>(null)
    val installingCascadePluginId: StateFlow<String?> = _installingCascadePluginId.asStateFlow()

    fun loadMcpServers() {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl

                // 1. Fetch live states from AGY daemon
                val liveResult = agyHubClient.getMcpServerStates(hubUrl)
                var fetchError: String? = null
                if (liveResult.isFailure) {
                    val err = liveResult.exceptionOrNull()?.message ?: "Failed to connect to Antigravity Hub"
                    Log.w("ChatViewModel", "loadMcpServers liveResult failed: $err")
                    fetchError = err
                }
                val liveList = liveResult.getOrDefault(emptyList()).toMutableList()

                // 2. Read persistent config from mcp_config.json to ensure any unstarted / disabled servers are also represented
                val configRaw = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig()
                if (!configRaw.isNullOrBlank()) {
                    try {
                        val cfgJson = org.json.JSONObject(configRaw)
                        val serversObj = cfgJson.optJSONObject("mcpServers")
                        if (serversObj != null) {
                            val keys = serversObj.keys()
                            while (keys.hasNext()) {
                                val serverName = keys.next()
                                val sObj = serversObj.optJSONObject(serverName) ?: continue
                                val existing = liveList.find { it.name.equals(serverName, ignoreCase = true) }
                                val disabled = sObj.optBoolean("disabled", false)

                                val command = sObj.optString("command", "")
                                val argsList = mutableListOf<String>()
                                sObj.optJSONArray("args")?.let { arr ->
                                    for (i in 0 until arr.length()) argsList.add(arr.optString(i))
                                }
                                val envMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("env")?.let { envObj ->
                                    val envKeys = envObj.keys()
                                    while (envKeys.hasNext()) {
                                        val k = envKeys.next()
                                        envMap[k] = envObj.optString(k, "")
                                    }
                                }
                                val serverUrl = sObj.optString("serverUrl", "")
                                val headersMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("headers")?.let { hObj ->
                                    val hKeys = hObj.keys()
                                    while (hKeys.hasNext()) {
                                        val k = hKeys.next()
                                        headersMap[k] = hObj.optString(k, "")
                                    }
                                }
                                val cwd = sObj.optString("cwd", "")

                                val parsedSpec = com.example.gemini.domain.model.McpServerSpec(
                                    serverName = serverName,
                                    command = command,
                                    args = argsList,
                                    env = envMap,
                                    serverUrl = serverUrl,
                                    headers = headersMap,
                                    disabled = disabled,
                                    cwd = cwd
                                )

                                if (existing == null) {
                                    liveList.add(
                                        com.example.gemini.domain.model.McpServerState(
                                            name = serverName,
                                            spec = parsedSpec,
                                            status = if (disabled) "DISABLED" else "MCP_SERVER_STATUS_STOPPED",
                                            isEnabled = !disabled
                                        )
                                    )
                                } else {
                                    val updatedSpec = existing.spec ?: parsedSpec
                                    val idx = liveList.indexOf(existing)
                                    liveList[idx] = existing.copy(
                                        spec = updatedSpec,
                                        isEnabled = !updatedSpec.disabled
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error parsing mcp_config.json: ${e.message}")
                    }
                }

                if (fetchError != null) {
                    _mcpErrorMessage.value = "Cannot reach Antigravity daemon ($hubUrl): $fetchError"
                }

                // If daemon RPC failed and no servers found, preserve existing cached servers if available
                if (liveList.isEmpty() && fetchError != null && _mcpServers.value.isNotEmpty()) {
                    Log.w("ChatViewModel", "Retaining ${_mcpServers.value.size} cached MCP servers due to fetch failure")
                } else {
                    // Stable alphabetical sorting by name (case-insensitive) to prevent jumping
                    _mcpServers.value = liveList.sortedBy { it.name.lowercase() }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadMcpServers error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun refreshMcpServers(targetServer: String? = null) {
        backendScope.launch {
            _isMcpRefreshing.value = true
            _refreshingMcpServer.value = targetServer
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl
                val res = agyHubClient.refreshMcpServers(hubUrl)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()?.message ?: "Refresh failed"
                    _mcpErrorMessage.value = "Daemon refresh error ($hubUrl): $err"
                }
                delay(600)
                loadMcpServers()
                if (targetServer != null) {
                    val s = _mcpServers.value.find { it.name.equals(targetServer, ignoreCase = true) }
                    if (s != null && !s.error.isNullOrBlank()) {
                        _mcpErrorMessage.value = "'$targetServer': ${s.error}"
                    } else if (s != null && s.tools.isNotEmpty()) {
                        _mcpStatusMessage.value = "Refreshed '$targetServer': ${s.tools.size} tool(s) available"
                    } else {
                        _mcpStatusMessage.value = "Refreshed '$targetServer'"
                    }
                } else {
                    val count = _mcpServers.value.size
                    val toolsCount = _mcpServers.value.sumOf { it.tools.size }
                    _mcpStatusMessage.value = "Refreshed: $count server(s) configured, $toolsCount tool(s) discovered"
                }
            } catch (e: Exception) {
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpRefreshing.value = false
                _refreshingMcpServer.value = null
            }
        }
    }

    fun toggleMcpServer(serverName: String, enabled: Boolean) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl

            // 1. Call daemon ToggleMcpServer
            agyHubClient.toggleMcpServer(serverName, enabled, hubUrl)

            // 2. Persist disabled state in mcp_config.json
            try {
                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val mcpServers = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }
                val targetServer = mcpServers.optJSONObject(serverName)
                if (targetServer != null) {
                    targetServer.put("disabled", !enabled)
                    com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to update disabled flag in mcp_config.json: ${e.message}")
            }

            // 3. Update local state with stable sort
            _mcpServers.value = _mcpServers.value.map {
                if (it.name.equals(serverName, ignoreCase = true)) {
                    val updatedSpec = it.spec?.copy(disabled = !enabled)
                    it.copy(
                        spec = updatedSpec,
                        isEnabled = enabled,
                        status = if (!enabled) "DISABLED" else it.status
                    )
                } else it
            }.sortedBy { it.name.lowercase() }
            _mcpStatusMessage.value = if (enabled) "Enabled '$serverName'" else "Disabled '$serverName'"
        }
    }

    fun saveMcpServer(spec: com.example.gemini.domain.model.McpServerSpec, rawJsonString: String? = null) {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }

                val serverObj: org.json.JSONObject = if (!rawJsonString.isNullOrBlank()) {
                    org.json.JSONObject(rawJsonString)
                } else {
                    org.json.JSONObject().apply {
                        if (spec.serverUrl.isNotBlank()) {
                            put("serverUrl", spec.serverUrl)
                            if (spec.headers.isNotEmpty()) {
                                val hObj = org.json.JSONObject()
                                spec.headers.forEach { (k, v) -> hObj.put(k, v) }
                                put("headers", hObj)
                            }
                        } else {
                            put("command", spec.command)
                            if (spec.args.isNotEmpty()) {
                                val argsArr = org.json.JSONArray()
                                spec.args.forEach { argsArr.put(it) }
                                put("args", argsArr)
                            }
                            if (spec.cwd.isNotBlank()) {
                                put("cwd", spec.cwd)
                            }
                            val envMap = spec.env.toMutableMap()
                            // Ensure Termux stdio binaries (npx, python3, etc.) have proper loader and PATH on Android
                            if (!envMap.containsKey("LD_PRELOAD")) {
                                envMap["LD_PRELOAD"] = "/data/data/com.termux/files/usr/lib/libtermux-exec.so"
                            }
                            if (!envMap.containsKey("PATH")) {
                                envMap["PATH"] = "/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin"
                            }
                            if (envMap.isNotEmpty()) {
                                val envObj = org.json.JSONObject()
                                envMap.forEach { (k, v) -> envObj.put(k, v) }
                                put("env", envObj)
                            }
                        }
                        if (spec.disabled) {
                            put("disabled", true)
                        }
                    }
                }
                serversObj.put(spec.serverName, serverObj)

                val success = com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                if (!success) {
                    _mcpErrorMessage.value = "Failed to save configuration to mcp_config.json"
                } else {
                    val hubUrl = AuthPreferences.currentHubUrl
                    agyHubClient.refreshMcpServers(hubUrl)
                    loadMcpServers()
                    _mcpStatusMessage.value = "Saved '${spec.serverName}'. Tap Refresh on the server to connect."
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "saveMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun deleteMcpServer(serverName: String) {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                // Instantly remove from local list for snappy UI
                _mcpServers.value = _mcpServers.value.filterNot { it.name.equals(serverName, ignoreCase = true) }

                val rawConfig = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers")
                if (serversObj != null && serversObj.has(serverName)) {
                    serversObj.remove(serverName)
                    com.example.gemini.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }

                val hubUrl = AuthPreferences.currentHubUrl
                agyHubClient.refreshMcpServers(hubUrl)
                loadMcpServers()
                _mcpStatusMessage.value = "Removed '$serverName'."
            } catch (e: Exception) {
                Log.e("ChatViewModel", "deleteMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun loadAvailableCascadePlugins(query: String = "") {
        backendScope.launch {
            _isCascadePluginsLoading.value = true
            try {
                val res = agyHubClient.getAvailableCascadePlugins(os = "linux", searchQuery = query)
                if (res.isSuccess) {
                    _availableCascadePlugins.value = res.getOrDefault(emptyList())
                } else {
                    Log.w("ChatViewModel", "loadAvailableCascadePlugins error: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadAvailableCascadePlugins failed: ${e.message}", e)
            } finally {
                _isCascadePluginsLoading.value = false
            }
        }
    }

    fun installCascadeMcpPlugin(plugin: com.example.gemini.data.remote.dto.AvailableCascadePluginDto) {
        backendScope.launch {
            _installingCascadePluginId.value = plugin.id
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val res = agyHubClient.installCascadePlugin(plugin)
                if (res.isSuccess) {
                    _mcpStatusMessage.value = "Installed and activated '${plugin.title}' MCP server"
                    delay(500)
                    loadMcpServers()
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Installation failed"
                    _mcpErrorMessage.value = "Failed to install ${plugin.title}: $err"
                }
            } catch (e: Exception) {
                _mcpErrorMessage.value = "Error installing ${plugin.title}: ${e.message}"
            } finally {
                _installingCascadePluginId.value = null
            }
        }
    }

}
