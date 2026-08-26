package com.example.gemini.data.daemon

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    val isModified: Boolean = false
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

    fun setActiveProject(project: ProjectItem?) {
        _activeProject.value = project
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

    suspend fun ensureDaemonStarted(
        context: Context? = null,
        host: String = "127.0.0.1",
        port: Int = 8022,
        user: String = "",
        pass: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        _statusMessage.value = "Checking port 9090..."
        log("Checking HTTP healthcheck at http://127.0.0.1:9090/api/health...")

        if (IdeApiClient.checkHealth()) {
            _status.value = DaemonStatus.RUNNING
            _statusMessage.value = "Running on 127.0.0.1:9090"
            log("✅ Go IDE Daemon is online on 127.0.0.1:9090!")
            return@withContext true
        }

        _status.value = DaemonStatus.ERROR
        _statusMessage.value = "Port 9090 Offline"
        log("❌ Server is offline on http://127.0.0.1:9090/api/health")
        return@withContext false
    }
}
