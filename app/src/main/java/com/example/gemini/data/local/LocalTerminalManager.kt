package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class LocalPtySession(
    val id: String,
    var name: String,
    val context: Context,
    val isSsh: Boolean = false,
    val sshHost: String = "127.0.0.1",
    val sshPort: Int = 8022,
    val sshUser: String = "root",
    val sshPass: String = "root",
    initialWorkingDir: String? = null
) : TerminalSessionClient {
    private val TAG = "LocalPtySession-$id"

    var workingDirectory: String = initialWorkingDir ?: LocalEnvironmentManager.getHomeDir(context).absolutePath
        private set

    val terminalSession: TerminalSession

    private val _isExited = MutableStateFlow(false)
    val isExited: StateFlow<Boolean> = _isExited.asStateFlow()

    private val _title = MutableStateFlow(if (isSsh) "ssh: $sshHost" else "gemini")
    val title: StateFlow<String> = _title.asStateFlow()

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var jschSession: Session? = null
    private var sshChannel: ChannelShell? = null
    private var sshIn: InputStream? = null
    private var sshOut: OutputStream? = null

    init {
        if (isSsh) {
            terminalSession = TerminalSession(
                "/system/bin/sh",
                "/sdcard",
                emptyArray(),
                arrayOf("TERM=xterm-256color"),
                3000,
                this
            )

            sessionScope.launch {
                connectSsh()
            }
        } else {
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
                File(bin, "zsh").exists() && File(bin, "zsh").canExecute() -> File(bin, "zsh").absolutePath
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
    }

    private suspend fun connectSsh() = withContext(Dispatchers.IO) {
        try {
            writeToEmulator("[Connecting to SSH $sshUser@$sshHost:$sshPort...]\r\n")
            val jsch = JSch()
            val session = jsch.getSession(sshUser, sshHost, sshPort)
            session.setPassword(sshPass)

            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                put("ConnectTimeout", "10000")
            }
            session.setConfig(config)
            session.connect(10000)
            jschSession = session

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType("xterm-256color", 80, 24, 800, 480)
            channel.connect(10000)
            sshChannel = channel

            sshIn = channel.inputStream
            sshOut = channel.outputStream

            writeToEmulator("\r[Connected to Termux SSH server!]\r\n\n")

            val buffer = ByteArray(4096)
            val inputStream = channel.inputStream
            while (channel.isConnected && isActive) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                if (count > 0) {
                    terminalSession.emulator.append(buffer, count)
                    onTextChangedListener?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSH session error", e)
            writeToEmulator("\r\n[SSH Connection Error: ${e.localizedMessage}]\r\n")
        } finally {
            _isExited.value = true
        }
    }

    private fun writeToEmulator(text: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            terminalSession.emulator.append(bytes, bytes.size)
            onTextChangedListener?.invoke()
        } catch (_: Exception) {}
    }

    var onTextChangedListener: (() -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        onTextChangedListener?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (!isSsh) {
            _title.value = changedSession.title ?: "gemini"
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (!isSsh) {
            _isExited.value = true
            LocalTerminalManager.closeSession(id)
        }
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
        if (isSsh) {
            sessionScope.launch {
                try {
                    sshOut?.write(text.toByteArray(Charsets.UTF_8))
                    sshOut?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "SSH write error", e)
                }
            }
        } else {
            terminalSession.write(text)
        }
    }

    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (isSsh) {
            sessionScope.launch {
                try {
                    val out = sshOut ?: return@launch
                    if (prependEscape) {
                        out.write(27)
                    }
                    if (codePoint <= 127) {
                        out.write(codePoint)
                    } else {
                        val chars = Character.toChars(codePoint)
                        out.write(String(chars).toByteArray(Charsets.UTF_8))
                    }
                    out.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "SSH code point error", e)
                }
            }
        } else {
            terminalSession.writeCodePoint(prependEscape, codePoint)
        }
    }

    fun updateSize(cols: Int, rows: Int) {
        if (isSsh) {
            try {
                sshChannel?.setPtySize(cols, rows, cols * 10, rows * 20)
            } catch (_: Exception) {}
        }
    }

    fun close() {
        sessionScope.cancel()
        try {
            sshChannel?.disconnect()
            jschSession?.disconnect()
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

        val authPrefs = AuthPreferences(context)
        var useSsh = false
        var host = "127.0.0.1"
        var port = 8022
        var user = "root"
        var pass = "root"

        try {
            runBlocking {
                useSsh = authPrefs.useSshTerminal.firstOrNull() ?: false
                host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
                port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
                user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
                pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"
            }
        } catch (_: Exception) {}

        val newSession = LocalPtySession(
            id = "session-1",
            name = if (useSsh) "SSH 1" else "Session 1",
            context = context.applicationContext,
            isSsh = useSsh,
            sshHost = host,
            sshPort = port,
            sshUser = user,
            sshPass = pass
        )
        _sessions.value = listOf(newSession)
        _activeSessionId.value = newSession.id
        return newSession
    }

    fun createNewSession(context: Context, workingDir: String? = null): LocalPtySession {
        val newIndex = _sessions.value.size + 1
        val authPrefs = AuthPreferences(context)
        var useSsh = false
        var host = "127.0.0.1"
        var port = 8022
        var user = "root"
        var pass = "root"

        try {
            runBlocking {
                useSsh = authPrefs.useSshTerminal.firstOrNull() ?: false
                host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
                port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
                user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
                pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"
            }
        } catch (_: Exception) {}

        val newSession = LocalPtySession(
            id = "session-$newIndex-${System.currentTimeMillis() % 10000}",
            name = if (useSsh) "SSH $newIndex" else "Session $newIndex",
            context = context.applicationContext,
            isSsh = useSsh,
            sshHost = host,
            sshPort = port,
            sshUser = user,
            sshPass = pass,
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
