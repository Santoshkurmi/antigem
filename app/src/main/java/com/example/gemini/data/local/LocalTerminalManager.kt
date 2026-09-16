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
            val safeCwd = context.filesDir.absolutePath
            val dummyBinary = when {
                File("/system/bin/cat").exists() -> "/system/bin/cat"
                File("/system/bin/sh").exists() -> "/system/bin/sh"
                else -> "/system/bin/sh"
            }
            val dummyArgs = emptyArray<String>()

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
        if (isInitialHistoryRestored || !tmuxParser.isControlModeActive) return
        sessionScope.launch(Dispatchers.IO) {
            delay(100)
            synchronized(sshWriteLock) {
                try {
                    if (!tmuxParser.isControlModeActive) return@launch
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
        // Strip trailing newlines/carriage returns from the end of the capture buffer
        while (end > 0 && (cleaned[end - 1] == '\n'.code.toByte() || cleaned[end - 1] == '\r'.code.toByte())) {
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
        // Do NOT append trailing \r\n so the cursor stays immediately after the shell prompt
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

    private val tmuxParser: TmuxControlParser = TmuxControlParser(
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
            if (!tmuxParser.isControlModeActive) {
                val chunk = data.copyOfRange(offset, offset + length)
                Log.d("TerminalIO", "FALLBACK -> EMULATOR: len=${chunk.size}")
                sessionScope.launch(Dispatchers.Main) {
                    terminalSession.emulator?.append(chunk, chunk.size)
                    onTextChangedListener?.invoke()
                }
            }
        },
        onControlModeStarted = {
            sessionScope.launch(Dispatchers.Main) {
                try {
                    terminalSession.emulator?.screen?.clearTranscript()
                } catch (_: Exception) {}
            }
            sessionScope.launch(Dispatchers.IO) {
                synchronized(sshWriteLock) {
                    try {
                        val cmd = "refresh-client -C ${ptyCols},${ptyRows}\n"
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                        sshOut?.flush()
                    } catch (_: Exception) {}
                }
            }
        },
        onPaneExited = { paneId ->
            Log.d(TAG, "Tmux pane exited: $paneId (assigned: $assignedPaneId)")
            if (assignedPaneId != null && paneId.trim() == assignedPaneId?.trim()) {
                notifySessionClosed()
            }
        },
        onExit = { reason ->
            Log.d(TAG, "Tmux exited: $reason")
            notifySessionClosed(reason)
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
        if (count <= 0 || _isExited.value) return
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
            try {
                emulator.screen?.clearTranscript()
                emulator.reset()
            } catch (_: Exception) {}

            // Intercept and silence all internal Handler messages from the local dummy process
            // (prevents MSG_PROCESS_EXITED "[Process completed (code ...)]" and local process output)
            try {
                val handlerField = session.javaClass.getDeclaredField("mMainThreadHandler")
                handlerField.isAccessible = true
                val handler = handlerField.get(session) as? android.os.Handler
                if (handler != null) {
                    handler.removeCallbacksAndMessages(null)
                    val callbackField = android.os.Handler::class.java.getDeclaredField("mCallback")
                    callbackField.isAccessible = true
                    callbackField.set(handler, android.os.Handler.Callback { true })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hook mMainThreadHandler", e)
            }

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
                    "tmux start-server 2>/dev/null; " +
                    "tmux set-option -g base-index 1 2>/dev/null; " +
                    "tmux set-window-option -g pane-base-index 1 2>/dev/null; " +
                    "tmux set-option -g renumber-windows on 2>/dev/null; " +
                    "tmux set-option -g allow-rename on 2>/dev/null; " +
                    "tmux set-option -g set-titles off 2>/dev/null; " +
                    "if ! tmux has-session -t $tmuxSessionName 2>/dev/null; then " +
                    "tmux new-session -d -s $tmuxSessionName 2>/dev/null; " +
                    "tmux set-option -t $tmuxSessionName base-index 1 2>/dev/null; " +
                    "tmux move-window -r -t $tmuxSessionName 2>/dev/null; " +
                    "fi; " +
                    "if ! tmux list-windows -t $tmuxSessionName -F \"#{window_index}\" 2>/dev/null | grep -qx \"$winIdx\"; then " +
                    "tmux new-window -d -t $tmuxSessionName:$winIdx 2>/dev/null || tmux new-window -d -t $tmuxSessionName 2>/dev/null; " +
                    "fi; " +
                    "tmux -CC new-session -A -t $tmuxSessionName -s ${tmuxSessionName}_$winIdx \\; select-window -t $tmuxSessionName:$winIdx \\; display-message -p \"ANTIGEM_PANE_ID:#{pane_id}\"; " +
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
            notifySessionClosed("SSH Error: ${e.localizedMessage}")
        } finally {
            notifySessionClosed()
        }
    }

    fun notifySessionClosed(reason: String? = null) {
        if (_isExited.value) return
        _isExited.value = true
        val msg = if (reason != null && reason.isNotBlank()) {
            "\r\n\r\n[Session is closed: $reason - you can close this tab]\r\n"
        } else {
            "\r\n\r\n[Session is closed, user can close the tab]\r\n"
        }
        writeToEmulator(msg)
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
            notifySessionClosed()
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
        if (_isExited.value) return
        if (isSsh) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            sendRawToSsh(bytes, 0, bytes.size)
        } else {
            terminalSession.write(text)
        }
    }

    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (_isExited.value) return
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
        if (_isExited.value) return
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
                        if (tmuxParser.isControlModeActive) {
                            val cmd = "refresh-client -C ${cols},${rows}\n"
                            sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                            sshOut?.flush()
                        }
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
                val sortedWindows = discoveredWindows.sortedBy { it.index }
                val restoredList = sortedWindows.map { win ->
                    val isGenericOrNumeric = win.name.isBlank() ||
                        win.name.toIntOrNull() != null ||
                        win.name == win.index.toString() ||
                        win.name.lowercase() in listOf("bash", "zsh", "sh", "dash", "tmux", "screen")
                    val winTitle = if (!isGenericOrNumeric) {
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
                    id = "session-tmux-1",
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
                val probeCmd = UNIVERSAL_SSH_PATH +
                    "tmux set-option -g base-index 1 2>/dev/null; " +
                    "tmux set-option -t $TMUX_SESSION_NAME base-index 1 2>/dev/null; " +
                    "tmux move-window -r -t $TMUX_SESSION_NAME 2>/dev/null; " +
                    "tmux list-windows -t $TMUX_SESSION_NAME -F \"#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}\" 2>/dev/null || echo \"\""
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
                list.sortBy { it.index }
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

    fun createNewSession(context: Context, workingDir: String? = null) {
        val authPrefs = AuthPreferences(context)
        managerScope.launch {
            val useSsh = authPrefs.useSshTerminal.firstOrNull() ?: false
            val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
            val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
            val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
            val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

            if (!useSsh) {
                val existingIndices = _sessions.value.mapIndexed { idx, _ -> idx + 1 }
                val nextWinIndex = (existingIndices.maxOrNull() ?: _sessions.value.size) + 1
                val newSession = LocalPtySession(
                    id = "session-$nextWinIndex-${System.currentTimeMillis() % 10000}",
                    name = "Session $nextWinIndex",
                    context = context.applicationContext,
                    isSsh = false,
                    initialWorkingDir = workingDir
                )
                _sessions.value = _sessions.value + newSession
                _activeSessionId.value = newSession.id
                return@launch
            }

            _isSyncingTmux.value = true
            val remoteWindows = withContext(Dispatchers.IO) {
                probeRemoteTmuxWindows(host, port, user, pass)
            }
            _isSyncingTmux.value = false

            val remoteIndices = remoteWindows.map { it.index }
            val localIndices = _sessions.value.mapNotNull { it.tmuxWindowIndex }
            val allExistingIndices = (remoteIndices + localIndices).toSet()

            val nextWinIndex = if (allExistingIndices.isEmpty()) {
                1
            } else {
                (allExistingIndices.maxOrNull() ?: 0) + 1
            }

            val newSession = LocalPtySession(
                id = "session-tmux-$nextWinIndex",
                name = "SSH $nextWinIndex",
                context = context.applicationContext,
                isSsh = true,
                sshHost = host,
                sshPort = port,
                sshUser = user,
                sshPass = pass,
                tmuxWindowIndex = nextWinIndex,
                tmuxSessionName = TMUX_SESSION_NAME,
                initialWorkingDir = workingDir
            )
            _sessions.value = _sessions.value + newSession
            _activeSessionId.value = newSession.id
        }
    }

    fun selectSession(id: String) {
        _activeSessionId.value = id
    }

    fun closeSession(id: String) {
        val current = _sessions.value
        val toClose = current.find { it.id == id }
        val remaining = current.filterNot { it.id == id }

        toClose?.close()
        _sessions.value = remaining

        if (_activeSessionId.value == id) {
            _activeSessionId.value = remaining.firstOrNull()?.id
        }

        if (toClose != null && toClose.isSsh && toClose.tmuxWindowIndex != null) {
            val winIdx = toClose.tmuxWindowIndex
            val host = toClose.sshHost
            val port = toClose.sshPort
            val user = toClose.sshUser
            val pass = toClose.sshPass

            // Asynchronously kill remote tmux window and client session without killing the master session
            managerScope.launch(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    val session = jsch.getSession(user, host, port)
                    session.setPassword(pass)
                    session.setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "no")
                        put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                        put("ConnectTimeout", "4000")
                    })
                    session.connect(4000)
                    try {
                        val channel = session.openChannel("exec") as ChannelExec
                        val cmd = UNIVERSAL_SSH_PATH +
                            "tmux kill-window -t $TMUX_SESSION_NAME:$winIdx 2>/dev/null; " +
                            "tmux kill-session -t ${TMUX_SESSION_NAME}_$winIdx 2>/dev/null"
                        Log.d(TAG, "Closing remote session with: $cmd")
                        channel.setCommand(cmd)
                        val input = channel.inputStream
                        channel.connect(4000)
                        input.bufferedReader().readText() // Wait for command execution to complete on remote server
                        channel.disconnect()
                    } finally {
                        session.disconnect()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error killing remote tmux window $winIdx", e)
                }
            }
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
            val windowIndices = current.mapNotNull { it.tmuxWindowIndex }
            managerScope.launch(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    val session = jsch.getSession(user, host, port)
                    session.setPassword(pass)
                    session.setConfig(Properties().apply {
                        put("StrictHostKeyChecking", "no")
                        put("PreferredAuthentications", "password,keyboard-interactive,publickey")
                        put("ConnectTimeout", "4000")
                    })
                    session.connect(4000)
                    try {
                        val channel = session.openChannel("exec") as ChannelExec
                        val clientKills = windowIndices.joinToString("; ") { "tmux kill-session -t ${TMUX_SESSION_NAME}_$it 2>/dev/null" }
                        val cmd = UNIVERSAL_SSH_PATH + clientKills
                        Log.d(TAG, "Disconnecting client attachments with: $cmd")
                        channel.setCommand(cmd)
                        val input = channel.inputStream
                        channel.connect(4000)
                        input.bufferedReader().readText()
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
