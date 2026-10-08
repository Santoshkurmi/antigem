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

/** Antigravity skills, installed plugins and the Google plugins catalog. Moved out of ChatViewModel unchanged. */
class AgyPluginsManager(
    private val backendScope: CoroutineScope,
    private val agyHubClient: AgyHubClient,
    private val store: ChatSessionStore,
    private val mcpManager: AgyMcpManager
) {

    private val _currentConversation = store.currentConversation

    private fun loadMcpServers() = mcpManager.loadMcpServers()

    private val _allSkills = MutableStateFlow<List<com.example.gemini.data.remote.dto.SkillDefinitionDto>>(emptyList())
    val allSkills: StateFlow<List<com.example.gemini.data.remote.dto.SkillDefinitionDto>> = _allSkills.asStateFlow()

    private val _isSkillsLoading = MutableStateFlow(false)
    val isSkillsLoading: StateFlow<Boolean> = _isSkillsLoading.asStateFlow()

    private val _skillsFilterScope = MutableStateFlow("GLOBAL") // "GLOBAL", "WORKSPACE", "ALL"
    val skillsFilterScope: StateFlow<String> = _skillsFilterScope.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<com.example.gemini.data.remote.dto.InstalledPluginDto>>(emptyList())
    val installedPlugins: StateFlow<List<com.example.gemini.data.remote.dto.InstalledPluginDto>> = _installedPlugins.asStateFlow()

    private val _isInstalledPluginsLoading = MutableStateFlow(false)
    val isInstalledPluginsLoading: StateFlow<Boolean> = _isInstalledPluginsLoading.asStateFlow()

    private val _googlePluginsCatalog = MutableStateFlow<List<com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto>>(emptyList())
    val googlePluginsCatalog: StateFlow<List<com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto>> = _googlePluginsCatalog.asStateFlow()

    private val _isGooglePluginsLoading = MutableStateFlow(false)
    val isGooglePluginsLoading: StateFlow<Boolean> = _isGooglePluginsLoading.asStateFlow()

    private val _installingGooglePluginId = MutableStateFlow<String?>(null)
    val installingGooglePluginId: StateFlow<String?> = _installingGooglePluginId.asStateFlow()

    private val _deletingPluginId = MutableStateFlow<String?>(null)
    val deletingPluginId: StateFlow<String?> = _deletingPluginId.asStateFlow()

    private val _pluginActionStatusMessage = MutableStateFlow<String?>(null)
    val pluginActionStatusMessage: StateFlow<String?> = _pluginActionStatusMessage.asStateFlow()

    private val _pluginActionErrorMessage = MutableStateFlow<String?>(null)
    val pluginActionErrorMessage: StateFlow<String?> = _pluginActionErrorMessage.asStateFlow()

    fun clearPluginActionStatus() {
        _pluginActionStatusMessage.value = null
        _pluginActionErrorMessage.value = null
    }

    fun setSkillsFilterScope(scope: String) {
        _skillsFilterScope.value = scope
        loadAllSkills(scope)
    }

    fun loadAllSkills(scope: String = _skillsFilterScope.value) {
        backendScope.launch {
            _isSkillsLoading.value = true
            try {
                val workspaceUris: List<String> = when (scope) {
                    "WORKSPACE", "ALL" -> {
                        val currentWs = com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path
                            ?: _currentConversation.value?.workspaceUri
                            ?: ""
                        if (currentWs.isNotBlank()) {
                            listOf(if (currentWs.startsWith("file://")) currentWs else "file://$currentWs")
                        } else emptyList()
                    }
                    else -> emptyList() // "GLOBAL" default
                }
                val res = agyHubClient.getAllSkills(workspaceUris)
                if (res.isSuccess) {
                    var skills = res.getOrDefault(emptyList())
                    if (scope == "GLOBAL") {
                        skills = skills.filter { it.isBuiltin || it.discoveryCategory == "DISCOVERY_CATEGORY_BUILTIN" || it.discoveryCategory == "DISCOVERY_CATEGORY_GLOBAL" || it.discoveryCategory == "DISCOVERY_CATEGORY_INSTALLED" }
                    } else if (scope == "WORKSPACE") {
                        skills = skills.filter { it.discoveryCategory == "DISCOVERY_CATEGORY_WORKSPACE" || it.scope?.workspaceScope != null }
                    }
                    _allSkills.value = skills
                } else {
                    _pluginActionErrorMessage.value = "Failed to load skills: ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _pluginActionErrorMessage.value = e.message
            } finally {
                _isSkillsLoading.value = false
            }
        }
    }

    fun loadAllInstalledPlugins() {
        backendScope.launch {
            _isInstalledPluginsLoading.value = true
            try {
                val res = agyHubClient.getAllPlugins()
                if (res.isSuccess) {
                    _installedPlugins.value = res.getOrDefault(emptyList())
                } else {
                    Log.w("ChatViewModel", "loadAllInstalledPlugins failed: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadAllInstalledPlugins error: ${e.message}", e)
            } finally {
                _isInstalledPluginsLoading.value = false
            }
        }
    }

    fun loadGooglePluginsCatalog() {
        backendScope.launch {
            _isGooglePluginsLoading.value = true
            try {
                val res = agyHubClient.getBuildWithGooglePlugins()
                if (res.isSuccess) {
                    _googlePluginsCatalog.value = res.getOrDefault(emptyList())
                } else {
                    _pluginActionErrorMessage.value = "Failed to load Google plugins: ${res.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _pluginActionErrorMessage.value = e.message
            } finally {
                _isGooglePluginsLoading.value = false
            }
        }
    }

    fun installGooglePlugin(pluginId: String, pluginName: String = pluginId) {
        backendScope.launch {
            _installingGooglePluginId.value = pluginId
            _pluginActionErrorMessage.value = null
            _pluginActionStatusMessage.value = null
            try {
                val res = agyHubClient.downloadBuildWithGooglePlugin(pluginId)
                if (res.isSuccess) {
                    _pluginActionStatusMessage.value = "Successfully installed $pluginName!"
                    delay(400)
                    loadAllInstalledPlugins()
                    loadAllSkills()
                    loadMcpServers()
                } else {
                    _pluginActionErrorMessage.value = res.exceptionOrNull()?.message ?: "Failed to install $pluginName"
                }
            } catch (e: Exception) {
                _pluginActionErrorMessage.value = e.message
            } finally {
                _installingGooglePluginId.value = null
            }
        }
    }

    fun deleteInstalledPlugin(pluginId: String, pluginName: String = pluginId) {
        backendScope.launch {
            _deletingPluginId.value = pluginId
            _pluginActionErrorMessage.value = null
            _pluginActionStatusMessage.value = null
            try {
                val res = agyHubClient.deletePlugin(pluginId)
                if (res.isSuccess) {
                    _pluginActionStatusMessage.value = "Uninstalled $pluginName successfully."
                    delay(400)
                    loadAllInstalledPlugins()
                    loadAllSkills()
                    loadMcpServers()
                } else {
                    _pluginActionErrorMessage.value = res.exceptionOrNull()?.message ?: "Failed to delete $pluginName"
                }
            } catch (e: Exception) {
                _pluginActionErrorMessage.value = e.message
            } finally {
                _deletingPluginId.value = null
            }
        }
    }

}
