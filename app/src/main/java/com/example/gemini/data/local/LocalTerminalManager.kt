package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.jcraft.jsch.ChannelExec
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

const val UNIVERSAL_SSH_PATH = "export PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/data/data/com.termux/files/usr/bin:\$PATH\"; "

data class TmuxWindowInfo(
    val index: Int,
    val name: String,
    val path: String? = null
)

class LocalPtySession(
    val id: String,
    var name: String,
    val context: Context,
    val isSsh: Boolean = false,
    val sshHost: String = "127.0.0.1",
    val sshPort: Int = 8022,
    val sshUser: String = "root",
    val sshPass: String = "root",
    val tmuxWindowIndex: Int? = null,
    val tmuxSessionName: String = "antigem",
    initialWorkingDir: String? = null
) : TerminalSessionClient {
    private val TAG = "LocalPtySession-$id"

    var workingDirectory: String = initialWorkingDir ?: LocalEnvironmentManager.getHomeDir(context).absolutePath
        private set

    val terminalSession: TerminalSession

    private val _isExited = MutableStateFlow(false)
    val isExited: StateFlow<Boolean> = _isExited.asStateFlow()

    private val _title = MutableStateFlow(if (isSsh) "ssh: $sshHost [win ${tmuxWindowIndex ?: 1}]" else "gemini")
    val title: StateFlow<String> = _title.asStateFlow()

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var jschSession: Session? = null
    private var sshChannel: ChannelExec? = null
    private var sshIn: InputStream? = null
    private var sshOut: OutputStream? = null

    var ptyCols: Int = 80
        private set
    var ptyRows: Int = 24
        private set
    var ptyWidthPx: Int = 800
        private set
    var ptyHeightPx: Int = 480
        private set

    private val sshWriteLock = Any()

    init {
        if (isSsh) {
            val dummyBinary = when {
                File("/system/bin/sleep").exists() -> "/system/bin/sleep"
                File("/system/bin/cat").exists() -> "/system/bin/cat"
                else -> "/system/bin/sh"
            }
            val dummyArgs = if (dummyBinary == "/system/bin/sleep") arrayOf("8640000") else emptyArray()
            val safeCwd = context.filesDir.absolutePath

            terminalSession = TerminalSession(
                dummyBinary,
                safeCwd,
                dummyArgs,
                arrayOf("TERM=xterm-256color"),
                3000,
                this
            )

            hookEmulatorForSsh(terminalSession)

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

    fun sendRawToSsh(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        if (count <= 0) return
        val copy = bytes.copyOfRange(offset, offset + count)
        sessionScope.launch(Dispatchers.IO) {
            synchronized(sshWriteLock) {
                try {
                    sshOut?.write(copy)
                    sshOut?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "SSH write error", e)
                }
            }
        }
    }

    private fun hookEmulatorForSsh(session: TerminalSession) {
        try {
            if (session.emulator == null) {
                session.initializeEmulator(ptyCols, ptyRows)
            }
            val emulator = session.emulator ?: return
            val sessionField = emulator.javaClass.getDeclaredField("mSession")
            sessionField.isAccessible = true
            val sshOutput = object : com.termux.terminal.TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    sendRawToSsh(data, offset, count)
                }

                override fun titleChanged(p0: String?, p1: String?) {
                    session.titleChanged(p0, p1)
                }

                override fun onCopyTextToClipboard(p0: String?) {
                    session.onCopyTextToClipboard(p0)
                }

                override fun onPasteTextFromClipboard() {
                    session.onPasteTextFromClipboard()
                }

                override fun onBell() {
                    session.onBell()
                }

                override fun onColorsChanged() {
                    session.onColorsChanged()
                }
            }
            sessionField.set(emulator, sshOutput)
            Log.d(TAG, "Successfully hooked emulator mSession for SSH output redirection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook emulator for SSH output redirection", e)
        }
    }

    private suspend fun connectSsh() = withContext(Dispatchers.IO) {
        try {
            val winIdx = tmuxWindowIndex ?: 1
            writeToEmulator("[Connecting to SSH $sshUser@$sshHost:$sshPort (Window $winIdx)...]\r\n")
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

            // Universal Tmux Multi-Window command with standard PATH injection
            val tmuxCmd = UNIVERSAL_SSH_PATH +
                    "if command -v tmux >/dev/null 2>&1; then " +
                    "tmux new-session -d -s $tmuxSessionName -n \"$winIdx\" 2>/dev/null; " +
                    "tmux new-window -d -t $tmuxSessionName:$winIdx -n \"$winIdx\" 2>/dev/null; " +
                    "tmux new-session -A -t $tmuxSessionName -s ${tmuxSessionName}_$winIdx \\; select-window -t $winIdx; " +
                    "else \${SHELL:-sh}; fi"

            Log.d(TAG, "Connecting SSH shell channel with command: $tmuxCmd")

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(tmuxCmd)
            channel.setPty(true)
            channel.setPtyType("xterm-256color", ptyCols, ptyRows, ptyWidthPx, ptyHeightPx)
            channel.connect(10000)
            sshChannel = channel

            // Ensure PTY dimensions are accurately applied post connect
            try {
                channel.setPtySize(ptyCols, ptyRows, ptyWidthPx, ptyHeightPx)
            } catch (_: Exception) {}

            sshIn = channel.inputStream
            sshOut = channel.outputStream

            writeToEmulator("\r[Connected to session '$tmuxSessionName' (win $winIdx)]\r\n\n")

            val buffer = ByteArray(4096)
            val inputStream = channel.inputStream
            while (channel.isConnected && isActive) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                if (count > 0) {
                    val chunk = buffer.copyOf(count)
                    withContext(Dispatchers.Main) {
                        terminalSession.emulator?.append(chunk, chunk.size)
                        onTextChangedListener?.invoke()
                    }
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
            sessionScope.launch(Dispatchers.Main) {
                terminalSession.emulator?.append(bytes, bytes.size)
                onTextChangedListener?.invoke()
            }
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
            val bytes = text.toByteArray(Charsets.UTF_8)
            sendRawToSsh(bytes, 0, bytes.size)
        } else {
            terminalSession.write(text)
        }
    }

    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (isSsh) {
            val bytes = if (prependEscape) {
                if (codePoint <= 127) {
                    byteArrayOf(27, codePoint.toByte())
                } else {
                    val chars = Character.toChars(codePoint)
                    val charBytes = String(chars).toByteArray(Charsets.UTF_8)
                    byteArrayOf(27) + charBytes
                }
            } else {
                if (codePoint <= 127) {
                    byteArrayOf(codePoint.toByte())
                } else {
                    val chars = Character.toChars(codePoint)
                    String(chars).toByteArray(Charsets.UTF_8)
                }
            }
            sendRawToSsh(bytes, 0, bytes.size)
        } else {
            terminalSession.writeCodePoint(prependEscape, codePoint)
        }
    }

    fun writeBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        if (isSsh) {
            sendRawToSsh(bytes, offset, count)
        } else {
            terminalSession.write(bytes, offset, count)
        }
    }

    fun updateSize(cols: Int, rows: Int, widthPx: Int = cols * 10, heightPx: Int = rows * 20) {
        ptyCols = cols
        ptyRows = rows
        ptyWidthPx = widthPx
        ptyHeightPx = heightPx
        if (isSsh) {
            sessionScope.launch {
                try {
                    sshChannel?.setPtySize(cols, rows, widthPx, heightPx)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update SSH PTY size", e)
                }
            }
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
    private const val TAG = "LocalTerminalManager"
    private const val TMUX_SESSION_NAME = "antigem"

    private val _sessions = MutableStateFlow<List<LocalPtySession>>(emptyList())
    val sessions: StateFlow<List<LocalPtySession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _isSyncingTmux = MutableStateFlow(false)
    val isSyncingTmux: StateFlow<Boolean> = _isSyncingTmux.asStateFlow()

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun getOrCreateOrRestoreSessions(context: Context): List<LocalPtySession> {
        if (_sessions.value.isNotEmpty()) {
            return _sessions.value
        }

        val authPrefs = AuthPreferences(context)
        val useSsh = authPrefs.useSshTerminal.firstOrNull() ?: false
        val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
        val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
        val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
        val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

        if (!useSsh) {
            return withContext(Dispatchers.Main) {
                val primary = LocalPtySession(
                    id = "session-1",
                    name = "Session 1",
                    context = context.applicationContext,
                    isSsh = false
                )
                _sessions.value = listOf(primary)
                _activeSessionId.value = primary.id
                listOf(primary)
            }
        }

        _isSyncingTmux.value = true
        val discoveredWindows = withContext(Dispatchers.IO) {
            probeRemoteTmuxWindows(host, port, user, pass)
        }
        _isSyncingTmux.value = false

        return withContext(Dispatchers.Main) {
            if (discoveredWindows.isNotEmpty()) {
                val restoredList = discoveredWindows.map { win ->
                    val winTitle = if (win.name.isNotBlank() && win.name != win.index.toString()) {
                        "SSH ${win.index}: ${win.name}"
                    } else {
                        "SSH ${win.index}"
                    }
                    LocalPtySession(
                        id = "session-tmux-${win.index}",
                        name = winTitle,
                        context = context.applicationContext,
                        isSsh = true,
                        sshHost = host,
                        sshPort = port,
                        sshUser = user,
                        sshPass = pass,
                        tmuxWindowIndex = win.index,
                        tmuxSessionName = TMUX_SESSION_NAME,
                        initialWorkingDir = win.path
                    )
                }
                _sessions.value = restoredList
                _activeSessionId.value = restoredList.firstOrNull()?.id
                restoredList
            } else {
                val initialSsh = LocalPtySession(
                    id = "session-1",
                    name = "SSH 1",
                    context = context.applicationContext,
                    isSsh = true,
                    sshHost = host,
                    sshPort = port,
                    sshUser = user,
                    sshPass = pass,
                    tmuxWindowIndex = 1,
                    tmuxSessionName = TMUX_SESSION_NAME
                )
                _sessions.value = listOf(initialSsh)
                _activeSessionId.value = initialSsh.id
                listOf(initialSsh)
            }
        }
    }

    fun getOrCreatePrimarySession(context: Context): LocalPtySession {
        val existing = _sessions.value.find { it.id == _activeSessionId.value }
            ?: _sessions.value.firstOrNull()

        if (existing != null) {
            return existing
        }

        // Trigger asynchronous full restore in background
        managerScope.launch {
            getOrCreateOrRestoreSessions(context)
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
            sshPass = pass,
            tmuxWindowIndex = if (useSsh) 1 else null,
            tmuxSessionName = TMUX_SESSION_NAME
        )
        _sessions.value = listOf(newSession)
        _activeSessionId.value = newSession.id
        return newSession
    }

    private suspend fun probeRemoteTmuxWindows(host: String, port: Int, user: String, pass: String): List<TmuxWindowInfo> = withContext(Dispatchers.IO) {
        try {
            val jsch = JSch()
            val session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                put("ConnectTimeout", "4000")
            }
            session.setConfig(config)
            session.connect(4000)

            try {
                val probeCmd = UNIVERSAL_SSH_PATH + "tmux list-windows -t $TMUX_SESSION_NAME -F \"#{window_index}|#{window_name}|#{pane_current_path}\" 2>/dev/null || echo \"\""
                Log.d(TAG, "Probing remote tmux windows with: $probeCmd")
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(probeCmd)
                val input = channel.inputStream
                channel.connect(4000)
                val output = input.bufferedReader().readText()
                channel.disconnect()
                Log.d(TAG, "Probe raw output:\n$output")

                val list = mutableListOf<TmuxWindowInfo>()
                output.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val parts = trimmed.split("|")
                        val idx = parts.getOrNull(0)?.toIntOrNull()
                        if (idx != null) {
                            val name = parts.getOrNull(1) ?: idx.toString()
                            val path = parts.getOrNull(2)
                            list.add(TmuxWindowInfo(index = idx, name = name, path = path))
                        }
                    }
                }
                Log.d(TAG, "Discovered active tmux windows: $list")
                return@withContext list
            } finally {
                session.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe tmux windows error (may not exist yet)", e)
            return@withContext emptyList()
        }
    }

    fun createNewSession(context: Context, workingDir: String? = null): LocalPtySession {
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

        val nextWinIndex = if (useSsh) {
            (_sessions.value.mapNotNull { it.tmuxWindowIndex }.maxOrNull() ?: _sessions.value.size) + 1
        } else {
            _sessions.value.size + 1
        }

        val newSession = LocalPtySession(
            id = "session-$nextWinIndex-${System.currentTimeMillis() % 10000}",
            name = if (useSsh) "SSH $nextWinIndex" else "Session $nextWinIndex",
            context = context.applicationContext,
            isSsh = useSsh,
            sshHost = host,
            sshPort = port,
            sshUser = user,
            sshPass = pass,
            tmuxWindowIndex = if (useSsh) nextWinIndex else null,
            tmuxSessionName = TMUX_SESSION_NAME,
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
        val remaining = current.filterNot { it.id == id }

        if (toClose != null && toClose.isSsh && toClose.tmuxWindowIndex != null) {
            val winIdx = toClose.tmuxWindowIndex
            val host = toClose.sshHost
            val port = toClose.sshPort
            val user = toClose.sshUser
            val pass = toClose.sshPass

            // Asynchronously kill remote tmux window and client session
            managerScope.launch(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    val session = jsch.getSession(user, host, port)
                    session.setPassword(pass)
                    session.setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "no")
                        put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                        put("ConnectTimeout", "3000")
                    })
                    session.connect(3000)
                    try {
                        val channel = session.openChannel("exec") as ChannelExec
                        val cmd = UNIVERSAL_SSH_PATH +
                            if (remaining.isEmpty()) {
                                "tmux kill-session -t $TMUX_SESSION_NAME 2>/dev/null"
                            } else {
                                "tmux kill-window -t $TMUX_SESSION_NAME:$winIdx 2>/dev/null; tmux kill-session -t ${TMUX_SESSION_NAME}_$winIdx 2>/dev/null"
                            }
                        Log.d(TAG, "Closing remote session with: $cmd")
                        channel.setCommand(cmd)
                        channel.connect(3000)
                        channel.disconnect()
                    } finally {
                        session.disconnect()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error killing remote tmux window $winIdx", e)
                }
            }
        }

        toClose?.close()
        _sessions.value = remaining

        if (_activeSessionId.value == id) {
            _activeSessionId.value = remaining.firstOrNull()?.id
        }
    }

    fun closeAll() {
        val current = _sessions.value
        val firstSsh = current.firstOrNull { it.isSsh }
        if (firstSsh != null) {
            val host = firstSsh.sshHost
            val port = firstSsh.sshPort
            val user = firstSsh.sshUser
            val pass = firstSsh.sshPass
            managerScope.launch(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    val session = jsch.getSession(user, host, port)
                    session.setPassword(pass)
                    session.setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "no")
                        put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                        put("ConnectTimeout", "3000")
                    })
                    session.connect(3000)
                    try {
                        val channel = session.openChannel("exec") as ChannelExec
                        val cmd = UNIVERSAL_SSH_PATH + "tmux kill-session -t $TMUX_SESSION_NAME 2>/dev/null"
                        Log.d(TAG, "Closing all remote sessions with: $cmd")
                        channel.setCommand(cmd)
                        channel.connect(3000)
                        channel.disconnect()
                    } finally {
                        session.disconnect()
                    }
                } catch (_: Exception) {}
            }
        }

        current.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }
}
