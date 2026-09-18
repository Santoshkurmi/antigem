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
    val isStagedDiff: Boolean = false
)

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

    private val _activeProject = MutableStateFlow<ProjectItem?>(null)
    val activeProject: StateFlow<ProjectItem?> = _activeProject.asStateFlow()

    private val _activeTabPath = MutableStateFlow<String?>(null)
    val activeTabPath: StateFlow<String?> = _activeTabPath.asStateFlow()

    private val _openTabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val openTabs: StateFlow<List<OpenTab>> = _openTabs.asStateFlow()

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

        // Immediately check daemon status and start continuous auto-reconnection monitor
        scope.launch {
            ensureDaemonStarted()
        }
        startAutoReconnectMonitor()
    }

    fun setActiveProject(project: ProjectItem?) {
        _activeProject.value = project
        if (project != null) {
            prefs?.edit()
                ?.putString("active_proj_name", project.name)
                ?.putString("active_proj_path", project.path)
                ?.apply()
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

    fun markTabSaved(path: String) {
        _openTabs.value = _openTabs.value.map { tab ->
            if (tab.path == path) {
                tab.copy(originalContent = tab.content, isModified = false)
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

    fun openOrSelectTab(path: String, name: String, content: String) {
        val existing = _openTabs.value.find { it.path == path }
        if (existing == null) {
            _openTabs.value = _openTabs.value + OpenTab(path = path, name = name, content = content, originalContent = content)
        }
        _activeTabPath.value = path
    }

    fun openDiffTab(filePath: String, diffContent: String, isStaged: Boolean) {
        val diffPath = "diff:${if (isStaged) "staged:" else ""}$filePath"
        val tabName = "Diff: ${java.io.File(filePath).name}${if (isStaged) " (Staged)" else ""}"
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
                isStagedDiff = isStaged
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
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = scope.launch {
            while (isActive) {
                delay(12_000)
                checkHealthAndReconnect(isSilent = true)
            }
        }
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
        val ok = checkHealthAndReconnect(isSilent = false)
        startAutoReconnectMonitor()
        ok
    }
}
