package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LocalPtySession(
    val id: String,
    var name: String,
    val context: Context,
    initialWorkingDir: String? = null
) : TerminalSessionClient {
    private val TAG = "LocalPtySession-$id"

    var workingDirectory: String = initialWorkingDir ?: LocalEnvironmentManager.getHomeDir(context).absolutePath
        private set

    val terminalSession: TerminalSession

    private val _isExited = MutableStateFlow(false)
    val isExited: StateFlow<Boolean> = _isExited.asStateFlow()

    private val _title = MutableStateFlow("gemini")
    val title: StateFlow<String> = _title.asStateFlow()

    init {
        val prefix = LocalEnvironmentManager.getPrefixDir(context)
        val bin = LocalEnvironmentManager.getBinDir(context)
        val lib = LocalEnvironmentManager.getLibDir(context)
        val home = LocalEnvironmentManager.getHomeDir(context)
        val tmp = LocalEnvironmentManager.getTmpDir(context)

        val envList = arrayOf(
            "PREFIX=${prefix.absolutePath}",
            "HOME=${home.absolutePath}",
            "PATH=${bin.absolutePath}:${bin.absolutePath}/applets:/system/bin:/system/xbin",
            "TMPDIR=${tmp.absolutePath}",
            "LD_LIBRARY_PATH=${lib.absolutePath}:/system/lib64:/system/lib",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "PS1=$ "
        )

        val shellBinary = when {
            File(bin, "bash").exists() && File(bin, "bash").canExecute() -> File(bin, "bash").absolutePath
            File(bin, "dash").exists() && File(bin, "dash").canExecute() -> File(bin, "dash").absolutePath
            File(bin, "sh").exists() && File(bin, "sh").canExecute() -> File(bin, "sh").absolutePath
            File(bin, "dash").exists() -> File(bin, "dash").absolutePath
            File(bin, "bash").exists() -> File(bin, "bash").absolutePath
            File(bin, "sh").exists() -> File(bin, "sh").absolutePath
            else -> "/system/bin/sh"
        }

        val cwd = if (File(workingDirectory).exists()) workingDirectory else home.absolutePath

        terminalSession = TerminalSession(
            shellBinary,
            cwd,
            emptyArray(),
            envList,
            3000,
            this
        )
    }

    var onTextChangedListener: (() -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        onTextChangedListener?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        _title.value = changedSession.title ?: "gemini"
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        _isExited.value = true
        LocalTerminalManager.closeSession(id)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stacktrace", e) }

    fun write(text: String) {
        terminalSession.write(text)
    }

    fun close() {
        try {
            terminalSession.finishIfRunning()
        } catch (_: Exception) {}
    }
}

object LocalTerminalManager {
    private val _sessions = MutableStateFlow<List<LocalPtySession>>(emptyList())
    val sessions: StateFlow<List<LocalPtySession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun getOrCreatePrimarySession(context: Context): LocalPtySession {
        val existing = _sessions.value.find { it.id == _activeSessionId.value }
            ?: _sessions.value.firstOrNull()

        if (existing != null) {
            return existing
        }

        val newSession = LocalPtySession(
            id = "session-1",
            name = "Session 1",
            context = context.applicationContext
        )
        _sessions.value = listOf(newSession)
        _activeSessionId.value = newSession.id
        return newSession
    }

    fun createNewSession(context: Context, workingDir: String? = null): LocalPtySession {
        val newIndex = _sessions.value.size + 1
        val newSession = LocalPtySession(
            id = "session-$newIndex-${System.currentTimeMillis() % 10000}",
            name = "Session $newIndex",
            context = context.applicationContext,
            initialWorkingDir = workingDir
        )
        _sessions.value = _sessions.value + newSession
        _activeSessionId.value = newSession.id
        return newSession
    }

    fun selectSession(id: String) {
        _activeSessionId.value = id
    }

    fun closeSession(id: String) {
        val current = _sessions.value
        val toClose = current.find { it.id == id }
        toClose?.close()

        val updated = current.filterNot { it.id == id }
        _sessions.value = updated

        if (_activeSessionId.value == id) {
            _activeSessionId.value = updated.firstOrNull()?.id
        }
    }

    fun closeAll() {
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }
}
