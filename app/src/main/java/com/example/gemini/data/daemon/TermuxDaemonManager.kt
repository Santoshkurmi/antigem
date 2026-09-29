package com.example.gemini.data.daemon

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DaemonStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class OpenTab(
    val path: String,
    val name: String,
    val content: String,
    val originalContent: String,
    val isModified: Boolean = false,
    val isDiff: Boolean = false,
    val diffFile: String? = null,
    val isStagedDiff: Boolean = false,
    val commitHash: String? = null,
    val isReadOnly: Boolean = false,
    val originalHash: String = "",
    val diskConflict: Boolean = false,
    val diskContentOnConflict: String = "",
    val isExternal: Boolean = false
)

enum class TabDiskUpdateResult {
    NO_CHANGE,
    AUTO_UPDATED,
    CONFLICT_DETECTED
}

object TermuxDaemonManager {

    private const val TAG = "TermuxDaemonManager"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _status = MutableStateFlow(DaemonStatus.STOPPED)
    val status: StateFlow<DaemonStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow("Offline")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _autoStartEnabled = MutableStateFlow(true)
    val autoStartEnabled: StateFlow<Boolean> = _autoStartEnabled.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectItem>>(emptyList())
    val projects: StateFlow<List<ProjectItem>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<ProjectItem?>(null)
    val activeProject: StateFlow<ProjectItem?> = _activeProject.asStateFlow()

    private val _activeTabPath = MutableStateFlow<String?>(null)
    val activeTabPath: StateFlow<String?> = _activeTabPath.asStateFlow()

    private val _openTabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val openTabs: StateFlow<List<OpenTab>> = _openTabs.asStateFlow()

    suspend fun loadProjects(conversations: List<com.example.gemini.domain.model.Conversation> = emptyList()): List<ProjectItem> = withContext(Dispatchers.IO) {
        var daemonList = IdeApiClient.getProjects()
        if (daemonList.isEmpty()) {
            val httpUrl = AuthPreferences.currentBridgeHttpUrl
            val res = com.example.gemini.data.remote.AgyBridgeService().fetchProjects(httpUrl)
            if (res.isSuccess) {
                daemonList = res.getOrThrow().map { ProjectItem(it.name, it.path) }
            }
        }
        val merged = mergeProjects(conversations, daemonList, _activeProject.value)
        _projects.value = merged
        if (_activeProject.value == null && merged.isNotEmpty()) {
            _activeProject.value = merged.first()
        }
        merged
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoReconnectJob: Job? = null

    private val _serverReconnectedEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val serverReconnectedEvent: SharedFlow<Unit> = _serverReconnectedEvent.asSharedFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("termux_ide_prefs", Context.MODE_PRIVATE)
        val savedName = prefs?.getString("active_proj_name", null)
        val savedPath = prefs?.getString("active_proj_path", null)
        if (!savedName.isNullOrBlank() && !savedPath.isNullOrBlank()) {
            _activeProject.value = ProjectItem(savedName, savedPath)
        }

        // Reactively observe unified bridge system connection state (zero polling)
        scope.launch {
            com.example.gemini.data.remote.AgyBridgeService.instance.systemConnectionState.collect { state ->
                when (state) {
                    is com.example.gemini.data.remote.SystemConnectionState.Connected -> {
                        _status.value = DaemonStatus.RUNNING
                        val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
                        _statusMessage.value = "Running on $ep"
                    }
                    is com.example.gemini.data.remote.SystemConnectionState.Offline,
                    is com.example.gemini.data.remote.SystemConnectionState.Error -> {
                        _status.value = DaemonStatus.ERROR
                        val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
                        _statusMessage.value = "Server Offline ($ep)"
                    }
                }
            }
        }
    }

    fun setActiveProject(project: ProjectItem?) {
        _activeProject.value = project
        if (project != null) {
            prefs?.edit()
                ?.putString("active_proj_name", project.name)
                ?.putString("active_proj_path", project.path)
                ?.apply()
            scope.launch {
                IdeApiClient.addSavedProject(project.path, project.name)
            }
        } else {
            prefs?.edit()
                ?.remove("active_proj_name")
                ?.remove("active_proj_path")
                ?.apply()
        }
    }

    fun setActiveTabPath(path: String?) {
        _activeTabPath.value = path
    }

    fun setOpenTabs(tabs: List<OpenTab>) {
        _openTabs.value = tabs
    }

    fun updateTabContent(path: String, newContent: String) {
        _openTabs.value = _openTabs.value.map { tab ->
            if (tab.path == path) {
                tab.copy(content = newContent, isModified = newContent != tab.originalContent)
            } else tab
        }
    }

    fun markTabSaved(path: String, newHash: String = "") {
        _openTabs.value = _openTabs.value.map { tab ->
            if (tab.path == path) {
                val finalHash = newHash.ifBlank { computeSha256(tab.content) }
                tab.copy(
                    originalContent = tab.content,
                    originalHash = finalHash,
                    isModified = false,
                    diskConflict = false,
                    diskContentOnConflict = ""
                )
            } else tab
        }
    }

    fun updateTabFromDisk(path: String, diskContent: String, diskHash: String = ""): TabDiskUpdateResult {
        val finalHash = diskHash.ifBlank { computeSha256(diskContent) }
        var result = TabDiskUpdateResult.NO_CHANGE
        _openTabs.value = _openTabs.value.map { tab ->
            if (tab.path == path) {
                if (!tab.isModified) {
                    if (tab.content != diskContent || tab.originalHash != finalHash) {
                        result = TabDiskUpdateResult.AUTO_UPDATED
                        tab.copy(
                            content = diskContent,
                            originalContent = diskContent,
                            originalHash = finalHash,
                            isModified = false,
                            diskConflict = false,
                            diskContentOnConflict = ""
                        )
                    } else tab
                } else {
                    if (finalHash != tab.originalHash) {
                        result = TabDiskUpdateResult.CONFLICT_DETECTED
                        tab.copy(
                            diskConflict = true,
                            diskContentOnConflict = diskContent
                        )
                    } else tab
                }
            } else tab
        }
        return result
    }

    fun resolveTabConflict(path: String, keepMine: Boolean) {
        _openTabs.value = _openTabs.value.map { tab ->
            if (tab.path == path) {
                if (keepMine) {
                    tab.copy(diskConflict = false)
                } else {
                    val diskContent = tab.diskContentOnConflict
                    val diskHash = computeSha256(diskContent)
                    tab.copy(
                        content = diskContent,
                        originalContent = diskContent,
                        originalHash = diskHash,
                        isModified = false,
                        diskConflict = false,
                        diskContentOnConflict = ""
                    )
                }
            } else tab
        }
    }

    fun closeTab(path: String) {
        val updated = _openTabs.value.filterNot { it.path == path }
        _openTabs.value = updated
        if (_activeTabPath.value == path) {
            _activeTabPath.value = updated.firstOrNull()?.path
        }
    }

    fun openOrSelectTab(path: String, name: String, content: String, hash: String = "", isReadOnly: Boolean = false, isExternal: Boolean = false) {
        val existing = _openTabs.value.find { it.path == path }
        val finalHash = hash.ifBlank { computeSha256(content) }
        val resolvedIsExternal = isExternal || path.startsWith("content://") || path.startsWith("android.resource://") || path.startsWith("file://")
        if (existing == null) {
            _openTabs.value = _openTabs.value + OpenTab(
                path = path,
                name = name,
                content = content,
                originalContent = content,
                originalHash = finalHash,
                isReadOnly = isReadOnly,
                isExternal = resolvedIsExternal
            )
        }
        _activeTabPath.value = path
    }


    fun openDiffTab(filePath: String, diffContent: String, isStaged: Boolean, commitHash: String? = null) {
        val diffPath = if (commitHash != null) "diff:commit:${commitHash.take(7)}:$filePath" else "diff:${if (isStaged) "staged:" else ""}$filePath"
        val tabName = if (commitHash != null) "Diff: ${java.io.File(filePath).name} (${commitHash.take(7)})" else "Diff: ${java.io.File(filePath).name}${if (isStaged) " (Staged)" else ""}"
        val existing = _openTabs.value.find { it.path == diffPath }
        if (existing != null) {
            _openTabs.value = _openTabs.value.map {
                if (it.path == diffPath) it.copy(content = diffContent, originalContent = diffContent) else it
            }
            _activeTabPath.value = diffPath
        } else {
            val newTab = OpenTab(
                path = diffPath,
                name = tabName,
                content = diffContent,
                originalContent = diffContent,
                isModified = false,
                isDiff = true,
                diffFile = filePath,
                isStagedDiff = isStaged,
                commitHash = commitHash
            )
            _openTabs.value = _openTabs.value + newTab
            _activeTabPath.value = diffPath
        }
    }


    fun setAutoStart(enabled: Boolean) {
        _autoStartEnabled.value = enabled
    }

    private fun log(message: String) {
        val timestamped = "[${timeFormat.format(Date())}] $message"
        Log.d(TAG, message)
        _logs.value = _logs.value + timestamped
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun startAutoReconnectMonitor() {
        // No-op: connection state is reactively managed by AgyBridgeService WebSocket and systemConnectionState
    }

    suspend fun checkHealthAndReconnect(isSilent: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val previousStatus = _status.value
        val isHealthy = IdeApiClient.checkHealth()
        if (isHealthy) {
            _status.value = DaemonStatus.RUNNING
            val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
            _statusMessage.value = "Running on $ep"
            if (previousStatus != DaemonStatus.RUNNING) {
                log("✅ Unified Bridge & IDE Daemon is online on ${AuthPreferences.currentBridgeHttpUrl}!")
                _serverReconnectedEvent.tryEmit(Unit)
            }
            true
        } else {
            _status.value = DaemonStatus.ERROR
            val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
            _statusMessage.value = "Server Offline ($ep)"
            if (!isSilent || previousStatus == DaemonStatus.RUNNING) {
                log("❌ Server is offline on ${AuthPreferences.currentBridgeHttpUrl}/api/health")
            }
            false
        }
    }

    suspend fun ensureDaemonStarted(
        context: Context? = null,
        host: String = "127.0.0.1",
        port: Int = 8022,
        user: String = "",
        pass: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        _status.value = DaemonStatus.STARTING
        val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
        _statusMessage.value = "Checking $ep..."
        log("Checking HTTP healthcheck at ${AuthPreferences.currentBridgeHttpUrl}/api/health...")
        checkHealthAndReconnect(isSilent = false)
    }
}
