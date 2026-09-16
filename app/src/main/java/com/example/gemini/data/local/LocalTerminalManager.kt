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
    val path: String? = null,
    val paneId: String? = null
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
    initialWorkingDir: String? = null,
    initialPaneId: String? = null
) : TerminalSessionClient {
    private val TAG = "LocalPtySession-$id"

    var assignedPaneId: String? = initialPaneId

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

    private var isInitialHistoryRestored = false

    private fun requestInitialHistory() {
        if (isInitialHistoryRestored) return
        sessionScope.launch(Dispatchers.IO) {
            delay(150)
            synchronized(sshWriteLock) {
                try {
                    val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                    val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                    val cmd = "capture-pane ${targetArg}-e -p -J -S -1000\n"
                    Log.d(TAG, "Requesting initial history for $id ($target): $cmd")
                    sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                    sshOut?.flush()
                } catch (_: Exception) {}
            }
        }
    }

    private fun normalizeHistoryNewlines(data: ByteArray): ByteArray {
        val cleaned = TmuxControlParser.stripScreenTitle(data)
        if (cleaned.isEmpty()) return cleaned
        var end = cleaned.size
        while (end > 0 && (cleaned[end - 1] == '\n'.code.toByte() || cleaned[end - 1] == '\r'.code.toByte() || cleaned[end - 1] == ' '.code.toByte())) {
            end--
        }
        if (end <= 0) return ByteArray(0)

        val out = java.io.ByteArrayOutputStream(end + end / 4)
        var i = 0
        while (i < end) {
            val b = cleaned[i]
            if (b == '\n'.code.toByte()) {
                if (i == 0 || cleaned[i - 1] != '\r'.code.toByte()) {
                    out.write('\r'.code)
                }
                out.write('\n'.code)
            } else {
                out.write(b.toInt() and 0xFF)
            }
            i++
        }
        out.write('\r'.code)
        out.write('\n'.code)
        return out.toByteArray()
    }

    private fun containsClearScreenSequence(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val n = data.size
        for (i in 0 until n - 1) {
            if (data[i] == 0x1B.toByte()) {
                // \033c (Full Reset)
                if (data[i + 1] == 'c'.code.toByte()) return true
                // \033[...
                if (data[i + 1] == '['.code.toByte() && i + 2 < n) {
                    // \033[2J or \033[3J
                    if (data[i + 2] == '2'.code.toByte() || data[i + 2] == '3'.code.toByte()) {
                        if (i + 3 < n && (data[i + 3] == 'J'.code.toByte() || data[i + 3] == 'j'.code.toByte())) {
                            return true
                        }
                    }
                    // \033[H\033[J or \033[H\033[2J
                    if (data[i + 2] == 'H'.code.toByte() || data[i + 2] == 'f'.code.toByte()) {
                        if (i + 4 < n && data[i + 3] == 0x1B.toByte() && data[i + 4] == '['.code.toByte()) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private val tmuxParser = TmuxControlParser(
        onPaneOutput = { paneId, data ->
            if (assignedPaneId == null) {
                assignedPaneId = paneId
            }
            if (paneId == assignedPaneId && data.isNotEmpty()) {
                val hasClear = containsClearScreenSequence(data)
                sessionScope.launch(Dispatchers.Main) {
                    if (hasClear) {
                        try {
                            terminalSession.emulator?.screen?.clearTranscript()
                        } catch (_: Exception) {}
                    }
                    terminalSession.emulator?.append(data, data.size)
                    onTextChangedListener?.invoke()
                }
                if (hasClear && isSsh) {
                    sessionScope.launch(Dispatchers.IO) {
                        synchronized(sshWriteLock) {
                            try {
                                val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                                val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                                val cmd = "clear-history ${targetArg}\n"
                                sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                                sshOut?.flush()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        },
        onRawFallbackOutput = { data, offset, length ->
            val chunk = data.copyOfRange(offset, offset + length)
            Log.d("TerminalIO", "FALLBACK -> EMULATOR: len=${chunk.size}")
            sessionScope.launch(Dispatchers.Main) {
                terminalSession.emulator?.append(chunk, chunk.size)
                onTextChangedListener?.invoke()
            }
        },
        onCommandResponse = { cmdNum, data, isError ->
            Log.d("TerminalIO", "CMD_RESP: num=$cmdNum, err=$isError, len=${data.size}, restored=$isInitialHistoryRestored")
            if (!isError && data.isNotEmpty()) {
                val str = String(data, Charsets.UTF_8)
                if (str.contains("ANTIGEM_PANE_ID:")) {
                    val extracted = str.lines().find { it.contains("ANTIGEM_PANE_ID:") }?.substringAfter("ANTIGEM_PANE_ID:")?.trim()
                    if (!extracted.isNullOrEmpty()) {
                        assignedPaneId = extracted
                        Log.d(TAG, "Bound session $id to pane $assignedPaneId")
                        if (!isInitialHistoryRestored) {
                            requestInitialHistory()
                        }
                    }
                } else if (!isInitialHistoryRestored) {
                    isInitialHistoryRestored = true
                    val formatted = normalizeHistoryNewlines(data)
                    if (formatted.isNotEmpty()) {
                        sessionScope.launch(Dispatchers.Main) {
                            terminalSession.emulator?.append(formatted, formatted.size)
                            onTextChangedListener?.invoke()
                        }
                    }
                }
            }
        },
        onUnhandledEvent = { event ->
            if (event.contains("ANTIGEM_PANE_ID:")) {
                val extracted = event.substringAfter("ANTIGEM_PANE_ID:").trim()
                if (extracted.isNotEmpty()) {
                    assignedPaneId = extracted
                    Log.d(TAG, "Bound session $id to pane $assignedPaneId from unhandled event")
                    if (!isInitialHistoryRestored) {
                        requestInitialHistory()
                    }
                }
            }
            Log.d(TAG, "Tmux -CC event [$id]: $event")
        }
    )

    fun sendRawToSsh(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        if (count <= 0) return
        val copy = bytes.copyOfRange(offset, offset + count)
        val repr = String(copy, Charsets.UTF_8).replace("\n", "\\n").replace("\r", "\\r")
        Log.d("TerminalIO", "APP -> SSH ($id): \"$repr\" (hex=${TmuxControlParser.encodeToHex(copy)})")
        sessionScope.launch(Dispatchers.IO) {
            synchronized(sshWriteLock) {
                try {
                    if (tmuxParser.isControlModeActive) {
                        val hex = TmuxControlParser.encodeToHex(copy)
                        val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                        val cmd = if (target.isNotEmpty()) "send-keys -t $target -H $hex\n" else "send-keys -H $hex\n"
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                    } else {
                        sshOut?.write(copy)
                    }
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
                    // In tmux -CC mode, the remote tmux server handles pane DA/CPR/DSR queries directly.
                    // The client emulator must NOT feed auto-replies back into send-keys,
                    // which causes "64;1;2;6;9;15;128;21;22c" (Device Attributes) to be typed as literal text.
                    if (!tmuxParser.isControlModeActive) {
                        sendRawToSsh(data, offset, count)
                    }
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

            // Universal Tmux -CC (Control Mode) multi-window command
            val tmuxCmd = UNIVERSAL_SSH_PATH +
                    "stty -echo 2>/dev/null; " +
                    "if command -v tmux >/dev/null 2>&1; then " +
                    "tmux set-option -g allow-rename off 2>/dev/null; " +
                    "tmux set-option -g set-titles off 2>/dev/null; " +
                    "tmux new-session -d -s $tmuxSessionName -n \"$winIdx\" 2>/dev/null; " +
                    "tmux new-window -d -t $tmuxSessionName:$winIdx -n \"$winIdx\" 2>/dev/null; " +
                    "tmux -CC new-session -A -t $tmuxSessionName -s ${tmuxSessionName}_$winIdx \\; select-window -t $winIdx \\; display-message -p \"ANTIGEM_PANE_ID:#{pane_id}\"; " +
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

            // Request initial layout and history from tmux control mode
            sessionScope.launch(Dispatchers.IO) {
                delay(100)
                synchronized(sshWriteLock) {
                    try {
                        val cmd = "refresh-client -C ${ptyCols},${ptyRows}\n"
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                        sshOut?.flush()
                    } catch (_: Exception) {}
                }
                if (!isInitialHistoryRestored) {
                    requestInitialHistory()
                }
            }

            val buffer = ByteArray(4096)
            val inputStream = channel.inputStream
            while (channel.isConnected && isActive) {
                val count = inputStream.read(buffer)
                if (count == -1) break
                if (count > 0) {
                    tmuxParser.feedData(buffer, 0, count)
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
        try {
            terminalSession.updateSize(cols, rows)
        } catch (_: Exception) {}
        if (isSsh) {
            sessionScope.launch(Dispatchers.IO) {
                try {
                    synchronized(sshWriteLock) {
                        val cmd = "refresh-client -C ${cols},${rows}\n"
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                        sshOut?.flush()
                    }
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
                        initialWorkingDir = win.path,
                        initialPaneId = win.paneId
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
                val probeCmd = UNIVERSAL_SSH_PATH + "tmux list-windows -t $TMUX_SESSION_NAME -F \"#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}\" 2>/dev/null || echo \"\""
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
                            val paneId = parts.getOrNull(3)
                            list.add(TmuxWindowInfo(index = idx, name = name, path = path, paneId = paneId))
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
