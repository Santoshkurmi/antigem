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

/** Antigravity global security settings, permission grants and project presets. Moved out of ChatViewModel unchanged. */
class AgySecuritySettingsManager(
    private val backendScope: CoroutineScope,
    private val agyHubClient: AgyHubClient,
    private val authPrefs: AuthPreferences,
    private val store: ChatSessionStore
) {

    private val _conversationError = store.conversationError

    private val _globalSecuritySettings = MutableStateFlow<AgyHubClient.GlobalUserSettings?>(null)
    val globalSecuritySettings: StateFlow<AgyHubClient.GlobalUserSettings?> = _globalSecuritySettings.asStateFlow()

    private val _globalSettingsError = MutableStateFlow<String?>(null)
    val globalSettingsError: StateFlow<String?> = _globalSettingsError.asStateFlow()

    private val _isGlobalSettingsLoading = MutableStateFlow(false)
    val isGlobalSettingsLoading: StateFlow<Boolean> = _isGlobalSettingsLoading.asStateFlow()

    private val _projectsList = MutableStateFlow<List<AgyHubClient.ProjectItem>>(emptyList())
    val projectsList: StateFlow<List<AgyHubClient.ProjectItem>> = _projectsList.asStateFlow()

    private val _isProjectsLoading = MutableStateFlow(false)
    val isProjectsLoading: StateFlow<Boolean> = _isProjectsLoading.asStateFlow()

    fun loadSecurityAndProjectSettings() {
        backendScope.launch {
            _isGlobalSettingsLoading.value = true
            _isProjectsLoading.value = true
            _globalSettingsError.value = null
            val hubUrl = AuthPreferences.currentHubUrl

            val globalRes = agyHubClient.fetchGlobalUserSettings(hubUrl)
            if (globalRes.isSuccess) {
                _globalSecuritySettings.value = globalRes.getOrNull()
                _globalSettingsError.value = null
            } else {
                val err = globalRes.exceptionOrNull()?.message ?: "Failed to connect to AGY Hub"
                _globalSettingsError.value = err
                _globalSecuritySettings.value = null
            }
            _isGlobalSettingsLoading.value = false

            val projRes = agyHubClient.fetchAllProjects(hubUrl)
            if (projRes.isSuccess) {
                _projectsList.value = projRes.getOrNull() ?: emptyList()
            }
            _isProjectsLoading.value = false
        }
    }

    fun addGlobalPermissionGrant(action: String, pattern: String, decision: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            val curGrants = cur.globalPermissionGrants

            val cleanAction = action.trim().lowercase()
            val cleanPattern = pattern.trim()
            if (cleanPattern.isBlank()) return@launch
            val ruleStr = "${cleanAction}($cleanPattern)"

            val newAllow = curGrants.allow.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()
            val newDeny = curGrants.deny.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()
            val newAsk = curGrants.ask.filterNot { it.equals(ruleStr, ignoreCase = true) }.toMutableList()

            when (decision.uppercase()) {
                "ALLOW" -> newAllow.add(ruleStr)
                "DENY" -> newDeny.add(ruleStr)
                "ASK" -> newAsk.add(ruleStr)
                else -> newAllow.add(ruleStr)
            }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to save permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun removeGlobalPermissionGrant(rawRule: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: return@launch
            val curGrants = cur.globalPermissionGrants

            val newAllow = curGrants.allow.filterNot { it.equals(rawRule, ignoreCase = true) }
            val newDeny = curGrants.deny.filterNot { it.equals(rawRule, ignoreCase = true) }
            val newAsk = curGrants.ask.filterNot { it.equals(rawRule, ignoreCase = true) }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to remove permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun changeGlobalPermissionGrantDecision(rawRule: String, newDecision: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: return@launch
            val curGrants = cur.globalPermissionGrants

            val newAllow = curGrants.allow.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()
            val newDeny = curGrants.deny.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()
            val newAsk = curGrants.ask.filterNot { it.equals(rawRule, ignoreCase = true) }.toMutableList()

            when (newDecision.uppercase()) {
                "ALLOW" -> newAllow.add(rawRule)
                "DENY" -> newDeny.add(rawRule)
                "ASK" -> newAsk.add(rawRule)
            }

            val updatedGrants = com.example.gemini.data.remote.AgyHubClient.GlobalPermissionGrants(
                allow = newAllow,
                deny = newDeny,
                ask = newAsk
            )
            _globalSecuritySettings.value = cur.copy(globalPermissionGrants = updatedGrants)

            val res = agyHubClient.writeGlobalUserSettings(
                globalPermissionGrants = updatedGrants,
                hubUrl = hubUrl
            )
            if (res.isFailure) {
                _conversationError.value = "Failed to update permission grant: ${res.exceptionOrNull()?.message}"
                loadSecurityAndProjectSettings()
            }
        }
    }

    fun updateGlobalArtifactReviewMode(mode: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(artifactReviewMode = mode)
            agyHubClient.writeGlobalUserSettings(
                artifactReviewMode = mode,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalSecurityPreset(
        autoExec: String,
        fileAccess: String
    ) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(
                autoExecutionPolicy = autoExec,
                nonWorkspaceFileAccessPolicy = fileAccess
            )
            authPrefs.setCommandAutoExecutionPolicy(autoExec)
            agyHubClient.writeGlobalUserSettings(
                autoExecutionPolicy = autoExec,
                nonWorkspaceFileAccessPolicy = fileAccess,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalCustomTerminalPolicy(policy: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(autoExecutionPolicy = policy)
            authPrefs.setCommandAutoExecutionPolicy(policy)
            agyHubClient.writeGlobalUserSettings(
                autoExecutionPolicy = policy,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalCustomFileAccessPolicy(policy: String) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(nonWorkspaceFileAccessPolicy = policy)
            agyHubClient.writeGlobalUserSettings(
                nonWorkspaceFileAccessPolicy = policy,
                hubUrl = hubUrl
            )
        }
    }

    fun updateGlobalTerminalSandbox(enabled: Boolean) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val cur = _globalSecuritySettings.value ?: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings()
            _globalSecuritySettings.value = cur.copy(enableTerminalSandbox = enabled)
            authPrefs.setCommandSandboxEnabled(enabled)
            agyHubClient.writeGlobalUserSettings(
                enableTerminalSandbox = enabled,
                hubUrl = hubUrl
            )
        }
    }

    fun setProjectInheritGlobal(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val res = agyHubClient.updateProjectSettings(
                projectId = project.id,
                projectName = project.name,
                inheritGlobal = true,
                hubUrl = hubUrl
            )
            if (res.isSuccess) {
                val refreshed = agyHubClient.fetchAllProjects(hubUrl)
                if (refreshed.isSuccess) {
                    _projectsList.value = refreshed.getOrNull() ?: emptyList()
                }
            }
        }
    }

    fun updateProjectPreset(
        project: com.example.gemini.data.remote.AgyHubClient.ProjectItem,
        autoExec: String,
        fileAccess: String,
        artifactReview: String? = null
    ) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl
            val res = agyHubClient.updateProjectSettings(
                projectId = project.id,
                projectName = project.name,
                autoExecutionPolicy = autoExec,
                fileAccessPolicy = fileAccess,
                artifactReviewMode = artifactReview ?: project.artifactReviewMode ?: "ARTIFACT_REVIEW_MODE_ALWAYS",
                sandboxMode = project.sandboxMode ?: false,
                inheritGlobal = false,
                hubUrl = hubUrl
            )
            if (res.isSuccess) {
                val refreshed = agyHubClient.fetchAllProjects(hubUrl)
                if (refreshed.isSuccess) {
                    _projectsList.value = refreshed.getOrNull() ?: emptyList()
                }
            }
        }
    }

    fun setCommandAutoExecutionPolicy(policy: String) {
        updateGlobalCustomTerminalPolicy(policy)
    }

    fun setCommandSandboxEnabled(enabled: Boolean) {
        updateGlobalTerminalSandbox(enabled)
    }

}
