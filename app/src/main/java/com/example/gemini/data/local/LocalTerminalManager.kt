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

const val UNIVERSAL_SSH_PATH =
    "export PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/data/data/com.termux/files/usr/bin:\$PATH\"; "

data class TmuxWindowInfo(
    val index: Int,
    val name: String,
    val path: String? = null,
    val paneId: String? = null,
    val windowId: String? = null
)

fun formatTmuxTitle(winIndex: Int, winName: String): String {
    val displayIndex = winIndex + 1
    val cleanName = winName.trim()
    val isGeneric = cleanName.isBlank() ||
            cleanName.toIntOrNull() != null ||
            cleanName == winIndex.toString() ||
            cleanName == displayIndex.toString() ||
            cleanName.lowercase() in listOf("bash", "zsh", "sh", "dash", "tmux", "screen")

    return if (!isGeneric) {
        "T $displayIndex: $cleanName"
    } else {
        "T $displayIndex"
    }
}

class LocalPtySession(
    val id: String,
    initialTitle: String,
    val context: Context,
    val isSsh: Boolean = false,
    val sshHost: String = "127.0.0.1",
    val sshPort: Int = 8022,
    val sshUser: String = "root",
    val sshPass: String = "root",
    var tmuxWindowIndex: Int? = null,
    val tmuxSessionName: String = "antigem",
    initialWorkingDir: String? = null,
    initialPaneId: String? = null,
    initialWindowId: String? = null,
    val initialCommand: String? = null,
    val forceShell: String? = null,
    initialCols: Int = 80,
    initialRows: Int = 24,
    initialWidthPx: Int = 800,
    initialHeightPx: Int = 480
) : TerminalSessionClient {
    private val TAG = "AntiGemTerminal"

    var assignedPaneId: String? = initialPaneId
    var assignedWindowId: String? = initialWindowId

    var workingDirectory: String = initialWorkingDir ?: LocalEnvironmentManager.getHomeDir(context).absolutePath
        private set

    val terminalSession: TerminalSession

    private val _isExited = MutableStateFlow(false)
    val isExited: StateFlow<Boolean> = _isExited.asStateFlow()

    private val _title = MutableStateFlow(initialTitle)
    val title: StateFlow<String> = _title.asStateFlow()

    var name: String
        get() = _title.value
        set(value) {
            _title.value = value
        }

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var jschSession: Session? = null
    private var sshChannel: ChannelExec? = null
    private var sshIn: InputStream? = null
    private var sshOut: OutputStream? = null

    var ptyCols: Int = initialCols
        private set
    var ptyRows: Int = initialRows
        private set
    var ptyWidthPx: Int = initialWidthPx
        private set
    var ptyHeightPx: Int = initialHeightPx
        private set

    private val sshWriteLock = Any()
    private var lastInfoRequestTime = 0L
    private var debouncedInfoJob: Job? = null

    init {
        Log.d(
            TAG,
            "[$id] Initializing session: name=$name, isSsh=$isSsh, winIdx=$tmuxWindowIndex, paneId=$initialPaneId, dir=$workingDirectory"
        )
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
                LocalTerminalManager.currentBufferSize,
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

            LocalEnvironmentManager.ensureGlibcEnvironment(context)
            LocalEnvironmentManager.ensureTermuxApiDispatcher(context)

            val loginShellBinaries = arrayOf("login", "bash", "zsh", "fish", "sh")

            val shellBinary = when {
                forceShell != null && File(bin, forceShell).exists() && File(bin, forceShell).canExecute() -> File(bin, forceShell).absolutePath
                forceShell != null && File(bin, forceShell).exists() -> File(bin, forceShell).absolutePath
                forceShell != null && File(forceShell).exists() -> File(forceShell).absolutePath
                else -> {
                    var found: String? = null
                    for (name in loginShellBinaries) {
                        val file = File(bin, name)
                        if (file.exists() && file.canExecute()) {
                            found = file.absolutePath
                            break
                        }
                    }
                    if (found == null) {
                        for (name in loginShellBinaries) {
                            val file = File(bin, name)
                            if (file.exists()) {
                                found = file.absolutePath
                                break
                            }
                        }
                    }
                    found ?: "/system/bin/sh"
                }
            }

            val safeCols = if (ptyCols > 0) ptyCols else if (LocalTerminalManager.lastKnownCols > 0) LocalTerminalManager.lastKnownCols else 80
            val safeRows = if (ptyRows > 0) ptyRows else if (LocalTerminalManager.lastKnownRows > 0) LocalTerminalManager.lastKnownRows else 24

            val envMap = LinkedHashMap<String, String>()
            try {
                System.getenv().forEach { (k, v) ->
                    if (v != null) envMap[k] = v
                }
            } catch (_: Exception) {}

            envMap["PREFIX"] = prefix.absolutePath
            envMap["HOME"] = home.absolutePath
            envMap["PATH"] = "${bin.absolutePath}:${bin.absolutePath}/applets:/system/bin:/system/xbin"
            envMap["TMPDIR"] = tmp.absolutePath
            envMap["TERM"] = "xterm-256color"
            envMap["COLORTERM"] = "truecolor"
            envMap["TERMUX_VERSION"] = "0.118.0"
            envMap["TERMUX_MAIN_PACKAGE_NAME"] = context.packageName
            envMap["TERMUX_APK_RELEASE"] = "GITHUB"
            envMap["TERMUX_APP_PID"] = "${android.os.Process.myPid()}"
            envMap["TERMUX__USER_ID"] = "0"
            envMap["SHELL"] = shellBinary
            envMap["ANDROID_DATA"] = "/data"
            envMap["ANDROID_ROOT"] = "/system"
            envMap["LANG"] = "en_US.UTF-8"
            envMap["LC_ALL"] = "en_US.UTF-8"
            envMap["COLUMNS"] = "$safeCols"
            envMap["LINES"] = "$safeRows"
            envMap["PS1"] = "$ "

            val envList = envMap.map { "${it.key}=${it.value}" }.toTypedArray()

            val isLoginShell = shellBinary != "/system/bin/sh"
            val processName = (if (isLoginShell) "-" else "") + File(shellBinary).name
            val cwd = if (File(workingDirectory).exists()) workingDirectory else home.absolutePath
            val shellArgs = if (!initialCommand.isNullOrBlank()) {
                arrayOf(processName, "-c", "$initialCommand; exec $shellBinary")
            } else {
                arrayOf(processName)
            }

            terminalSession = TerminalSession(
                shellBinary,
                cwd,
                shellArgs,
                envList,
                LocalTerminalManager.currentBufferSize,
                this
            )
            try {
                terminalSession.updateSize(safeCols, safeRows)
            } catch (_: Exception) {
            }
        }
    }

    private var isInitialHistoryRestored = false
    private var isHistoryRequested = false
    private val earlyOutputBuffer = java.io.ByteArrayOutputStream()

    private fun requestInitialHistory() {
        if (isHistoryRequested || !tmuxParser.isControlModeActive) return
        isHistoryRequested = true
        sessionScope.launch(Dispatchers.IO) {
            delay(50)
            synchronized(sshWriteLock) {
                try {
                    if (!tmuxParser.isControlModeActive) return@launch
                    val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                    val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                    val cmd = "capture-pane ${targetArg}-e -p -J -S -500\n"
                    Log.d(TAG, "[$id] Requesting initial history ($target): $cmd")
                    sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                    sshOut?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "[$id] Failed to write capture-pane command", e)
                }
            }
            // Fallback: if capture-pane response doesn't arrive within 1.5s, unblock live stream
            delay(1500)
            if (!isInitialHistoryRestored) {
                Log.w(TAG, "[$id] Fallback: capture-pane response timed out, unblocking early buffer")
                isInitialHistoryRestored = true
                val buffered = synchronized(earlyOutputBuffer) {
                    val b = earlyOutputBuffer.toByteArray()
                    earlyOutputBuffer.reset()
                    b
                }
                if (buffered.isNotEmpty()) {
                    Log.d(TAG, "[$id] Flushing early buffered output: ${buffered.size} bytes")
                    sessionScope.launch(Dispatchers.Main) {
                        terminalSession.emulator?.append(buffered, buffered.size)
                        notifyTextChanged()
                    }
                }
            }
        }
    }

    private val ANSI_ESCAPE_REGEX =
        Regex("\u001B\\[[0-9;]*[a-zA-Z]|\u001B\\([a-zA-Z]|\u001BP[0-9]*[a-zA-Z]?|\u001B\\\\")

    private fun normalizeHistoryNewlines(data: ByteArray): ByteArray {
        val cleaned = TmuxControlParser.stripScreenTitle(data)
        if (cleaned.isEmpty()) return cleaned

        val text = String(cleaned, Charsets.UTF_8)
        val lines = text.lines()

        // 1. Strip trailing blank lines (lines containing only spaces, tabs, carriage returns, or ANSI escape codes)
        var lastContentIndex = lines.size - 1
        while (lastContentIndex >= 0) {
            val lineWithoutAnsi = ANSI_ESCAPE_REGEX.replace(lines[lastContentIndex], "").trim()
            if (lineWithoutAnsi.isNotEmpty()) {
                break
            }
            lastContentIndex--
        }

        if (lastContentIndex < 0) return ByteArray(0)

        val contentLines = lines.subList(0, lastContentIndex + 1).toMutableList()

        // 2. Deduplicate consecutive trailing identical prompt lines (created by SIGWINCH resize events while idle)
        if (contentLines.isNotEmpty()) {
            val lastLineClean = ANSI_ESCAPE_REGEX.replace(contentLines.last(), "").trim()
            if (lastLineClean.isNotEmpty()) {
                var prevIndex = contentLines.size - 2
                while (prevIndex >= 0) {
                    val prevLineClean = ANSI_ESCAPE_REGEX.replace(contentLines[prevIndex], "").trim()
                    if (prevLineClean == lastLineClean) {
                        contentLines.removeAt(prevIndex)
                        prevIndex--
                    } else {
                        break
                    }
                }
            }
        }

        // 3. Format lines with \r\n and ensure no trailing newline after the prompt
        val out = StringBuilder()
        for (i in contentLines.indices) {
            val line = contentLines[i].replace("\r", "")
            out.append(line)
            if (i < contentLines.size - 1) {
                out.append("\r\n")
            }
        }

        return out.toString().toByteArray(Charsets.UTF_8)
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
            val preview =
                String(data.take(60).toByteArray(), Charsets.ISO_8859_1).replace("\n", "\\n").replace("\r", "\\r")
            Log.d(
                TAG,
                "[$id] onPaneOutput: paneId=$paneId (assigned=$assignedPaneId, histRestored=$isInitialHistoryRestored, bytes=${data.size}) -> \"$preview\""
            )

            val targetPane = assignedPaneId
            if (targetPane != null && paneId.trim() != targetPane.trim()) {
                // Strict Isolation: Ignore output belonging to other tmux tabs/windows
                return@TmuxControlParser
            }
            if (assignedPaneId == null && (tmuxWindowIndex == null || tmuxWindowIndex == 1)) {
                assignedPaneId = paneId
                Log.d(TAG, "[$id] Auto-assigned initial primary paneId=$assignedPaneId from output")
            }
            if (paneId == assignedPaneId && data.isNotEmpty()) {
                if (!isInitialHistoryRestored) {
                    Log.d(TAG, "[$id] Buffering ${data.size} bytes into earlyOutputBuffer")
                    synchronized(earlyOutputBuffer) {
                        earlyOutputBuffer.write(data)
                    }
                    return@TmuxControlParser
                }
                val hasClear = containsClearScreenSequence(data)
                sessionScope.launch(Dispatchers.Main) {
                    if (hasClear) {
                        try {
                            Log.d(TAG, "[$id] Clear screen sequence detected, clearing transcript")
                            terminalSession.emulator?.screen?.clearTranscript()
                        } catch (_: Exception) {
                        }
                    }
                    terminalSession.emulator?.append(data, data.size)
                    notifyTextChanged()
                }
                if (hasClear && isSsh) {
                    sessionScope.launch(Dispatchers.IO) {
                        synchronized(sshWriteLock) {
                            try {
                                val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                                val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                                val cmd = "clear-history ${targetArg}\n"
                                Log.d(TAG, "[$id] Sending clear-history: $cmd")
                                sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                                sshOut?.flush()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        },
        onRawFallbackOutput = { data, offset, length ->
            if (!tmuxParser.isControlModeActive) {
                val chunk = data.copyOfRange(offset, offset + length)
                val preview =
                    String(chunk.take(60).toByteArray(), Charsets.ISO_8859_1).replace("\n", "\\n").replace("\r", "\\r")
                Log.d(TAG, "[$id] FALLBACK -> EMULATOR: len=${chunk.size} -> \"$preview\"")
                sessionScope.launch(Dispatchers.Main) {
                    terminalSession.emulator?.append(chunk, chunk.size)
                    notifyTextChanged()
                }
            }
        },
        onControlModeStarted = {
            Log.d(TAG, "[$id] Tmux Control Mode (-CC) activated!")
            requestInitialHistory()
            requestInfoUpdate()
            sessionScope.launch(Dispatchers.IO) {
                synchronized(sshWriteLock) {
                    try {
                        val cmd = "refresh-client -C ${ptyCols},${ptyRows}\n"
                        Log.d(TAG, "[$id] Sending initial refresh-client: $cmd")
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                        sshOut?.flush()
                    } catch (_: Exception) {
                    }
                }
            }
        },
        onWindowClose = { windowId ->
            val closedWinId = windowId.trim().substringBefore(" ")
            Log.d(TAG, "[$id] Tmux window closed event: $closedWinId (assignedWin=$assignedWindowId, assignedPane=$assignedPaneId)")
            if (!assignedWindowId.isNullOrBlank() && (closedWinId == assignedWindowId || closedWinId.removePrefix("@") == assignedWindowId?.removePrefix("@"))) {
                notifySessionClosed("Process exited")
            } else {
                scheduleDebouncedInfoUpdate(50)
            }
        },
        onWindowRenamed = { event ->
            Log.d(TAG, "[$id] Window renamed event from tmux: $event")
            requestInfoUpdate()
        },
        onPaneExited = { paneId ->
            val cleanPane = paneId.trim().substringBefore(" ")
            val myPane = assignedPaneId?.trim()
            Log.d(TAG, "[$id] Tmux pane exited event: $cleanPane (assigned: $myPane)")
            if (!myPane.isNullOrBlank() && (cleanPane == myPane || cleanPane.removePrefix("%") == myPane.removePrefix("%"))) {
                notifySessionClosed("Process exited")
            } else {
                scheduleDebouncedInfoUpdate(50)
            }
        },
        onExit = { reason ->
            Log.d(TAG, "[$id] Tmux exited: $reason")
            notifySessionClosed(reason ?: "Process exited")
        },
        onCommandResponse = { cmdNum, data, isError ->
            val preview =
                String(data.take(80).toByteArray(), Charsets.ISO_8859_1).replace("\n", "\\n").replace("\r", "\\r")
            Log.d(TAG, "[$id] CMD_RESP: num=$cmdNum, err=$isError, len=${data.size} -> \"$preview\"")
            if (isError) {
                val str = String(data, Charsets.UTF_8).trim()
                val target = assignedPaneId ?: assignedWindowId
                if (target != null && (str.contains("can't find") || str.contains("no such")) && str.contains(target)) {
                    Log.d(TAG, "[$id] Target $target no longer exists on remote ($str), notifying session closed")
                    notifySessionClosed("Process exited")
                }
            } else if (data.isNotEmpty()) {
                val str = String(data, Charsets.UTF_8)
                if (str.contains("ANTIGEM_INFO:")) {
                    val line =
                        str.lines().find { it.contains("ANTIGEM_INFO:") }?.substringAfter("ANTIGEM_INFO:")?.trim()
                    if (!line.isNullOrEmpty()) {
                        handleAntigemInfo(line)
                    }
                } else if (str.contains("ANTIGEM_PANE_ID:")) {
                    val extracted =
                        str.lines().find { it.contains("ANTIGEM_PANE_ID:") }?.substringAfter("ANTIGEM_PANE_ID:")?.trim()
                    if (!extracted.isNullOrEmpty()) {
                        assignedPaneId = extracted
                        Log.d(TAG, "[$id] Bound session to pane $assignedPaneId from command response")
                    }
                } else if (!isInitialHistoryRestored) {
                    isInitialHistoryRestored = true
                    val formatted = normalizeHistoryNewlines(data)
                    val formattedPreview =
                        String(formatted.take(80).toByteArray(), Charsets.ISO_8859_1).replace("\n", "\\n")
                            .replace("\r", "\\r")
                    Log.d(TAG, "[$id] Restoring initial history (${formatted.size} bytes): \"$formattedPreview\"")
                    // capture-pane is the authoritative snapshot of the pane; discard earlyOutputBuffer
                    synchronized(earlyOutputBuffer) {
                        earlyOutputBuffer.reset()
                    }
                    sessionScope.launch(Dispatchers.Main) {
                        try {
                            terminalSession.emulator?.screen?.clearTranscript()
                        } catch (_: Exception) {
                        }
                        if (isAlternateScreenActive) {
                            val enableAlt = "\u001b[?1049h".toByteArray(Charsets.UTF_8)
                            terminalSession.emulator?.append(enableAlt, enableAlt.size)
                        }
                        if (formatted.isNotEmpty()) {
                            terminalSession.emulator?.append(formatted, formatted.size)
                        }
                        notifyTextChanged()
                    }
                }
            }
        },
        onUnhandledEvent = { event ->
            if (event.contains("ANTIGEM_INFO:")) {
                val line = event.substringAfter("ANTIGEM_INFO:").trim()
                if (line.isNotEmpty()) {
                    handleAntigemInfo(line)
                }
            } else if (event.contains("ANTIGEM_PANE_ID:")) {
                val extracted = event.substringAfter("ANTIGEM_PANE_ID:").trim()
                if (extracted.isNotEmpty()) {
                    assignedPaneId = extracted
                    Log.d(TAG, "[$id] Bound session to pane $assignedPaneId from unhandled event")
                }
            }
            Log.d(TAG, "[$id] Tmux -CC event: $event")
        }
    )

    private var isAlternateScreenActive: Boolean = false

    private fun handleAntigemInfo(infoStr: String) {
        val parts = infoStr.split("|")
        val winIdx = parts.getOrNull(0)?.toIntOrNull() ?: tmuxWindowIndex ?: 1
        val winName = parts.getOrNull(1) ?: ""
        val panePath = parts.getOrNull(2)
        val pId = parts.getOrNull(3)?.trim()
        val altVal = parts.getOrNull(4)?.trim()
        val mouseAny = parts.getOrNull(5)?.trim() == "1"
        val mouseSgr = parts.getOrNull(6)?.trim() == "1"
        val winId = parts.getOrNull(7)?.trim()

        if (assignedPaneId == null && !pId.isNullOrBlank()) {
            assignedPaneId = pId
        } else if (!assignedPaneId.isNullOrBlank() && !pId.isNullOrBlank() && pId != assignedPaneId) {
            Log.d(TAG, "[$id] Current pane $assignedPaneId is dead (fallback to $pId), notifying session closed")
            notifySessionClosed("Process exited")
            return
        }

        if (assignedWindowId == null && !winId.isNullOrBlank()) {
            assignedWindowId = winId
        }

        val isAlt = altVal == "1"

        sessionScope.launch(Dispatchers.Main) {
            val em = terminalSession.emulator ?: return@launch
            var changed = false

            if (isAlt && !em.isAlternateBufferActive) {
                isAlternateScreenActive = true
                val seq = "\u001b[?1049h".toByteArray(Charsets.UTF_8)
                em.append(seq, seq.size)
                changed = true
            } else if (!isAlt && em.isAlternateBufferActive) {
                isAlternateScreenActive = false
                val seq = "\u001b[?1049l".toByteArray(Charsets.UTF_8)
                em.append(seq, seq.size)
                changed = true
            }

            if (mouseAny && !em.isMouseTrackingActive) {
                val seq = (if (mouseSgr) "\u001b[?1000h\u001b[?1002h\u001b[?1006h" else "\u001b[?1000h").toByteArray(Charsets.UTF_8)
                em.append(seq, seq.size)
                changed = true
                Log.d(TAG, "[$id] Synchronized mouse tracking ON (sgr=$mouseSgr)")
            } else if (!mouseAny && em.isMouseTrackingActive) {
                val seq = "\u001b[?1000l\u001b[?1002l\u001b[?1006l".toByteArray(Charsets.UTF_8)
                em.append(seq, seq.size)
                changed = true
                Log.d(TAG, "[$id] Synchronized mouse tracking OFF")
            }

            if (changed) {
                notifyTextChanged()
            }
        }

        if (tmuxWindowIndex == null || tmuxWindowIndex == winIdx) {
            tmuxWindowIndex = winIdx
        }
        if (!pId.isNullOrBlank() && (assignedPaneId == null || assignedPaneId == pId)) {
            assignedPaneId = pId
        }
        if (!panePath.isNullOrBlank()) {
            workingDirectory = panePath
        }
        val formattedTitle = formatTmuxTitle(winIdx, winName)
        if (_title.value != formattedTitle) {
            _title.value = formattedTitle
            Log.d(
                TAG,
                "[$id] Realtime title updated: $formattedTitle (win=$winIdx, name=$winName, path=$panePath, paneId=$pId, alt=$isAlt, mouse=$mouseAny)"
            )
        }
    }

    fun requestInfoUpdate() {
        if (!isSsh || !tmuxParser.isControlModeActive || _isExited.value) return
        val now = System.currentTimeMillis()
        if (now - lastInfoRequestTime < 250) return
        lastInfoRequestTime = now

        sessionScope.launch(Dispatchers.IO) {
            synchronized(sshWriteLock) {
                try {
                    val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                    val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                    val cmd =
                        "display-message ${targetArg}-p \"ANTIGEM_INFO:#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}|#{alternate_on}|#{mouse_any_flag}|#{mouse_sgr_flag}|#{window_id}\"\n"
                    sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                    sshOut?.flush()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun scheduleDebouncedInfoUpdate(delayMs: Long = 400) {
        if (!isSsh || _isExited.value) return
        debouncedInfoJob?.cancel()
        debouncedInfoJob = sessionScope.launch(Dispatchers.IO) {
            delay(delayMs)
            requestInfoUpdate()
        }
    }

    fun sendRawToSsh(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        if (count <= 0 || _isExited.value) return
        val copy = bytes.copyOfRange(offset, offset + count)
        val repr = String(copy, Charsets.UTF_8).replace("\n", "\\n").replace("\r", "\\r")
        val hex = TmuxControlParser.encodeToHex(copy)
        Log.d(TAG, "[$id] APP -> SSH: \"$repr\" (hex=$hex)")
        sessionScope.launch(Dispatchers.IO) {
            synchronized(sshWriteLock) {
                try {
                    if (tmuxParser.isControlModeActive) {
                        val target = assignedPaneId ?: (tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: "")
                        val cmd = if (target.isNotEmpty()) "send-keys -t $target -H $hex\n" else "send-keys -H $hex\n"
                        Log.d(TAG, "[$id] SSH write (control): $cmd")
                        sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                    } else {
                        Log.d(TAG, "[$id] SSH write (raw): $count bytes")
                        sshOut?.write(copy)
                    }
                    sshOut?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "[$id] SSH write error", e)
                }
            }
        }
        scheduleDebouncedInfoUpdate(if (copy.any { it == 0x0A.toByte() || it == 0x0D.toByte() }) 200 else 800)
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
            } catch (_: Exception) {
            }

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
                Log.w(TAG, "[$id] Failed to hook mMainThreadHandler", e)
            }

            val sessionField = emulator.javaClass.getDeclaredField("mSession")
            sessionField.isAccessible = true
            val sshOutput = object : com.termux.terminal.TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    val preview =
                        String(data.copyOfRange(offset, offset + count), Charsets.ISO_8859_1).replace("\n", "\\n")
                            .replace("\r", "\\r")
                    Log.d(TAG, "[$id] Emulator Output: count=$count -> \"$preview\"")
                    if (!tmuxParser.isControlModeActive) {
                        sendRawToSsh(data, offset, count)
                    } else {
                        val isMouseSequence = count >= 3 && data[offset] == 0x1B.toByte() && data[offset + 1] == '['.code.toByte() &&
                                (data[offset + 2] == '<'.code.toByte() || data[offset + 2] == 'M'.code.toByte())
                        val isDaReply = (data.any { it == 'c'.code.toByte() } && preview.contains("64;1;2")) ||
                                (preview.startsWith("\u001b[?") || preview.startsWith("\u001b[>"))
                        if (isMouseSequence || (!isDaReply && !preview.endsWith("c") && !preview.endsWith("R"))) {
                            sendRawToSsh(data, offset, count)
                        }
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
            Log.d(TAG, "[$id] Successfully hooked emulator mSession for SSH output redirection")
        } catch (e: Exception) {
            Log.e(TAG, "[$id] Failed to hook emulator for SSH output redirection", e)
        }
    }

    private suspend fun connectSsh() = withContext(Dispatchers.IO) {
        try {
            val winIdx = tmuxWindowIndex ?: 1
            Log.d(TAG, "[$id] Starting SSH connection to $sshUser@$sshHost:$sshPort (winIdx=$winIdx)...")
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
            Log.d(TAG, "[$id] SSH transport connected successfully")

            val tmuxCmd = UNIVERSAL_SSH_PATH +
                    "stty -echo 2>/dev/null; " +
                    "if command -v tmux >/dev/null 2>&1; then " +
                    "tmux start-server 2>/dev/null; " +
                    "tmux set-option -g base-index 1 2>/dev/null; " +
                    "tmux set-window-option -g pane-base-index 1 2>/dev/null; " +
                    "tmux set-option -g renumber-windows off 2>/dev/null; " +
                    "tmux set-option -g allow-rename on 2>/dev/null; " +
                    "tmux set-option -g set-titles off 2>/dev/null; " +
                    "tmux set-option -g window-size latest 2>/dev/null; " +
                    "tmux set-window-option -g window-size latest 2>/dev/null; " +
                    "tmux set-option -g mouse on 2>/dev/null; " +
                    "if ! tmux has-session -t $tmuxSessionName 2>/dev/null; then " +
                    "tmux new-session -d -s $tmuxSessionName 2>/dev/null; " +
                    "tmux set-option -t $tmuxSessionName base-index 1 2>/dev/null; " +
                    "tmux set-option -t $tmuxSessionName renumber-windows off 2>/dev/null; " +
                    "tmux set-option -t $tmuxSessionName window-size latest 2>/dev/null; " +
                    "tmux set-window-option -t $tmuxSessionName window-size latest 2>/dev/null; " +
                    "tmux set-option -t $tmuxSessionName mouse on 2>/dev/null; " +
                    "fi; " +
                    "targetWin=\$(if [ -n \"$winIdx\" ] && tmux list-windows -t $tmuxSessionName -F \"#{window_index}\" 2>/dev/null | grep -qx \"$winIdx\"; then echo \"$winIdx\"; else tmux list-windows -t $tmuxSessionName -F \"#{window_index}\" 2>/dev/null | head -n 1; fi); " +
                    "tmux -CC new-session -A -t $tmuxSessionName -s ${tmuxSessionName}_\${targetWin} \\; select-window -t $tmuxSessionName:\$targetWin \\; display-message -p -t $tmuxSessionName:\$targetWin \"ANTIGEM_INFO:#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}|#{alternate_on}|#{mouse_any_flag}|#{mouse_sgr_flag}|#{window_id}\"; " +
                    "else \${SHELL:-sh}; fi"

            Log.d(TAG, "[$id] Executing SSH command: $tmuxCmd")

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(tmuxCmd)
            channel.setPty(true)
            channel.setPtyType("xterm-256color", ptyCols, ptyRows, ptyWidthPx, ptyHeightPx)
            channel.connect(10000)
            sshChannel = channel
            Log.d(TAG, "[$id] SSH ChannelExec connected, initial PTY size: ${ptyCols}x${ptyRows}")

            try {
                channel.setPtySize(ptyCols, ptyRows, ptyWidthPx, ptyHeightPx)
            } catch (_: Exception) {
            }

            sshIn = channel.inputStream
            sshOut = channel.outputStream

            val buffer = ByteArray(4096)
            val inputStream = channel.inputStream
            while (channel.isConnected && isActive) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    Log.d(TAG, "[$id] SSH stream reached EOF (-1)")
                    break
                }
                if (count > 0) {
                    tmuxParser.feedData(buffer, 0, count)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$id] SSH session error", e)
            notifySessionClosed("SSH Error: ${e.localizedMessage}")
        } finally {
            Log.d(TAG, "[$id] SSH loop terminated")
            notifySessionClosed()
        }
    }

    fun notifySessionClosed(reason: String? = null) {
        if (_isExited.value) return
        _isExited.value = true
        Log.d(TAG, "[$id] notifySessionClosed: reason=$reason")
        val msg = "\r\n\r\n\u001b[1;33m[Process completed - Press Enter to close tab]\u001b[0m\r\n"
        writeToEmulator(msg)
    }

    private fun writeToEmulator(text: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            sessionScope.launch(Dispatchers.Main) {
                terminalSession.emulator?.append(bytes, bytes.size)
                notifyTextChanged()
            }
        } catch (_: Exception) {
        }
    }

    private val textChangedListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addTextChangedListener(listener: () -> Unit) {
        textChangedListeners.add(listener)
    }

    fun removeTextChangedListener(listener: () -> Unit) {
        textChangedListeners.remove(listener)
    }

    fun notifyTextChanged() {
        for (listener in textChangedListeners) {
            try {
                listener.invoke()
            } catch (_: Exception) {}
        }
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        notifyTextChanged()
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

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Terminal Text", text)
            clipboard?.setPrimaryClip(clip)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Copied text", android.widget.Toast.LENGTH_SHORT).show()
            }
            Log.d(TAG, "Copied ${text.length} chars to clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy text to clipboard", e)
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context)?.toString()
                if (!text.isNullOrEmpty()) {
                    write(text)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "Pasted text", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to paste text from clipboard", e)
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = LocalTerminalManager.currentCursorStyleInt

    override fun logError(tag: String, message: String) {
        Log.e(TAG, "[$id][TermuxError] $message")
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(TAG, "[$id][TermuxWarn] $message")
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(TAG, "[$id][TermuxInfo] $message")
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(TAG, "[$id][TermuxDebug] $message")
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(TAG, "[$id][TermuxVerbose] $message")
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(TAG, "[$id][TermuxStack] $message", e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(TAG, "[$id][TermuxStack]", e)
    }

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
        Log.d(TAG, "[$id] writeCodePoint: prependEscape=$prependEscape, codePoint=$codePoint")
        val str = if (prependEscape) {
            "\u001B" + String(Character.toChars(codePoint))
        } else {
            String(Character.toChars(codePoint))
        }
        write(str)
    }

    fun writeBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        if (_isExited.value) return
        if (isSsh) {
            sendRawToSsh(bytes, offset, count)
        } else {
            terminalSession.write(bytes, offset, count)
        }
    }

    private var resizeJob: kotlinx.coroutines.Job? = null

    fun updateSize(cols: Int, rows: Int, widthPx: Int = cols * 10, heightPx: Int = rows * 20) {
        if (cols <= 0 || rows <= 0) return
        if (cols == ptyCols && rows == ptyRows) return
        Log.d(
            TAG,
            "[$id] updateSize: cols=$cols, rows=$rows (prev: ${ptyCols}x${ptyRows}), widthPx=$widthPx, heightPx=$heightPx"
        )
        ptyCols = cols
        ptyRows = rows
        ptyWidthPx = widthPx
        ptyHeightPx = heightPx
        LocalTerminalManager.lastKnownCols = cols
        LocalTerminalManager.lastKnownRows = rows
        LocalTerminalManager.lastKnownWidthPx = widthPx
        LocalTerminalManager.lastKnownHeightPx = heightPx
        try {
            terminalSession.updateSize(cols, rows)
        } catch (_: Exception) {
        }
        if (isSsh) {
            resizeJob?.cancel()
            resizeJob = sessionScope.launch(Dispatchers.IO) {
                delay(30) // Swift resize dispatch
                try {
                    synchronized(sshWriteLock) {
                        if (tmuxParser.isControlModeActive) {
                            val target = tmuxWindowIndex?.let { "$tmuxSessionName:$it" } ?: ""
                            val targetArg = if (target.isNotEmpty()) "-t $target " else ""
                            val cmd = "refresh-client -C ${cols},${rows}\nresize-window ${targetArg}-x ${cols} -y ${rows}\n"
                            Log.d(TAG, "[$id] Sending debounced resize: $cmd")
                            sshOut?.write(cmd.toByteArray(Charsets.UTF_8))
                            sshOut?.flush()
                        }
                    }
                    sshChannel?.setPtySize(cols, rows, widthPx, heightPx)
                } catch (e: Exception) {
                    Log.w(TAG, "[$id] Failed to update SSH PTY size", e)
                }
            }
        }
    }

    fun close() {
        Log.d(TAG, "[$id] Closing session...")
        sessionScope.cancel()
        try {
            sshChannel?.disconnect()
            jschSession?.disconnect()
            terminalSession.finishIfRunning()
        } catch (_: Exception) {
        }
    }
}

object LocalTerminalManager {
    private const val TAG = "AntiGemTerminal"
    private const val TMUX_SESSION_NAME = "antigem"

    var lastKnownCols: Int = 60
    var lastKnownRows: Int = 30
    var lastKnownWidthPx: Int = 1200
    var lastKnownHeightPx: Int = 1500

    var currentCursorStyleInt: Int = 2 // 0 = BLOCK, 1 = UNDERLINE, 2 = BAR
    var currentBufferSize: Int = 20000

    fun updatePreferences(cursorStyle: String, bufferSize: Int) {
        currentCursorStyleInt = when (cursorStyle.uppercase()) {
            "UNDERLINE" -> 1
            "BAR" -> 2
            else -> 0
        }
        currentBufferSize = if (bufferSize <= 0 || bufferSize >= 100000) 100000 else bufferSize
        _sessions.value.forEach { session ->
            try {
                session.terminalSession.emulator?.setCursorStyle()
                session.notifyTextChanged()
            } catch (_: Exception) {}
        }
    }

    private val _sessions = MutableStateFlow<List<LocalPtySession>>(emptyList())
    val sessions: StateFlow<List<LocalPtySession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _isSyncingTmux = MutableStateFlow(false)
    val isSyncingTmux: StateFlow<Boolean> = _isSyncingTmux.asStateFlow()

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private const val PREFS_NAME = "anti_gem_terminal_prefs"
    private const val KEY_LAST_ACTIVE_TMUX_INDEX = "last_active_tmux_window_index"

    private fun saveLastActiveTmuxIndex(context: Context, winIndex: Int) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_LAST_ACTIVE_TMUX_INDEX, winIndex).apply()
            Log.d(TAG, "[Manager] Persisted last active tmux index: $winIndex")
        } catch (e: Exception) {
            Log.w(TAG, "[Manager] Failed to persist last active tmux index", e)
        }
    }

    private fun getLastActiveTmuxIndex(context: Context): Int? {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_LAST_ACTIVE_TMUX_INDEX)) {
                val idx = prefs.getInt(KEY_LAST_ACTIVE_TMUX_INDEX, -1)
                if (idx >= 0) return idx
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun autoLaunchServerIfReady(context: Context) {
        val pkg = context.packageName
        val isTermux = pkg == "com.termux" || pkg.contains("termux")
        if (!isTermux) return

        if (!LocalEnvironmentManager.isInstalled(context)) {
            Log.d(TAG, "[AutoLaunch] Bootstrap is not installed yet, skipping auto-launch")
            return
        }

        if (LocalServerManager.hasServerScript(context)) {
            Log.d(TAG, "[AutoLaunch] Server script found in home directory, launching local server...")
            LocalServerManager.startServer(context, forceRestart = true)
        } else {
            Log.d(TAG, "[AutoLaunch] No server executable in home directory, skipping auto-launch")
        }
    }

    suspend fun getOrCreateOrRestoreSessions(context: Context): List<LocalPtySession> {
        Log.d(TAG, "[Manager] getOrCreateOrRestoreSessions called, existing count=${_sessions.value.size}")
        if (_sessions.value.isNotEmpty()) {
            return _sessions.value
        }

        val authPrefs = AuthPreferences(context)
        val defaultUseSsh = !LocalEnvironmentManager.isTermuxPackage(context)
        val useSsh = authPrefs.useSshTerminal.firstOrNull() ?: defaultUseSsh
        val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
        val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
        val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
        val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"
        Log.d(TAG, "[Manager] Preferences: useSsh=$useSsh, host=$host, port=$port, user=$user")

        if (!useSsh) {
            return withContext(Dispatchers.Main) {
                val primary = LocalPtySession(
                    id = "session-1",
                    initialTitle = "Session 1",
                    context = context.applicationContext,
                    isSsh = false,
                    initialCommand = null,
                    initialCols = lastKnownCols,
                    initialRows = lastKnownRows,
                    initialWidthPx = lastKnownWidthPx,
                    initialHeightPx = lastKnownHeightPx
                )
                _sessions.value = listOf(primary)
                _activeSessionId.value = primary.id
                Log.d(TAG, "[Manager] Created local non-SSH primary interactive session")
                listOf(primary)
            }
        }

        _isSyncingTmux.value = true
        Log.d(TAG, "[Manager] Probing remote tmux windows...")
        val discoveredWindows = withContext(Dispatchers.IO) {
            probeRemoteTmuxWindows(host, port, user, pass)
        }
        _isSyncingTmux.value = false
        Log.d(TAG, "[Manager] Probing complete, discovered ${discoveredWindows.size} windows")

        return withContext(Dispatchers.Main) {
            if (discoveredWindows.isNotEmpty()) {
                val sortedWindows = discoveredWindows.sortedBy { it.index }
                val restoredList = sortedWindows.map { win ->
                    val winTitle = formatTmuxTitle(win.index, win.name)
                    Log.d(
                        TAG,
                        "[Manager] Restoring session for window ${win.index} (name=${win.name}, title=$winTitle, paneId=${win.paneId})"
                    )
                    LocalPtySession(
                        id = "session-tmux-${win.index}",
                        initialTitle = winTitle,
                        context = context.applicationContext,
                        isSsh = true,
                        sshHost = host,
                        sshPort = port,
                        sshUser = user,
                        sshPass = pass,
                        tmuxWindowIndex = win.index,
                        tmuxSessionName = TMUX_SESSION_NAME,
                        initialWorkingDir = win.path,
                        initialPaneId = win.paneId,
                        initialWindowId = win.windowId,
                        initialCols = lastKnownCols,
                        initialRows = lastKnownRows,
                        initialWidthPx = lastKnownWidthPx,
                        initialHeightPx = lastKnownHeightPx
                    )
                }
                val savedIndex = getLastActiveTmuxIndex(context)
                val matchingSession = if (savedIndex != null) restoredList.find { it.tmuxWindowIndex == savedIndex } else null
                val targetSession = matchingSession ?: restoredList.firstOrNull()

                _sessions.value = restoredList
                _activeSessionId.value = targetSession?.id
                Log.d(
                    TAG,
                    "[Manager] Restored ${restoredList.size} sessions, active=${_activeSessionId.value} (savedIndex=$savedIndex, matched=${matchingSession != null})"
                )
                restoredList
            } else {
                Log.d(TAG, "[Manager] No remote windows found, creating initial tmux session & window")
                val createdWin = withContext(Dispatchers.IO) {
                    createRemoteTmuxWindow(host, port, user, pass, null)
                }
                val winIndex = createdWin?.index ?: 0
                val winName = createdWin?.name ?: ""
                val winPath = createdWin?.path
                val paneId = createdWin?.paneId
                val winId = createdWin?.windowId
                val winTitle = formatTmuxTitle(winIndex, winName)

                val initialSsh = LocalPtySession(
                    id = "session-tmux-$winIndex",
                    initialTitle = winTitle,
                    context = context.applicationContext,
                    isSsh = true,
                    sshHost = host,
                    sshPort = port,
                    sshUser = user,
                    sshPass = pass,
                    tmuxWindowIndex = winIndex,
                    tmuxSessionName = TMUX_SESSION_NAME,
                    initialWorkingDir = winPath,
                    initialPaneId = paneId,
                    initialWindowId = winId,
                    initialCols = lastKnownCols,
                    initialRows = lastKnownRows,
                    initialWidthPx = lastKnownWidthPx,
                    initialHeightPx = lastKnownHeightPx
                )
                _sessions.value = listOf(initialSsh)
                _activeSessionId.value = initialSsh.id
                listOf(initialSsh)
            }
        }
    }

    private suspend fun probeRemoteTmuxWindows(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): List<TmuxWindowInfo> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[Probe] Connecting SSH to $user@$host:$port for window probe...")
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
                        "tmux set-option -g renumber-windows off 2>/dev/null; " +
                        "tmux set-option -g allow-rename on 2>/dev/null; " +
                        "tmux set-option -g set-titles off 2>/dev/null; " +
                        "tmux set-option -g mouse on 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME base-index 1 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME renumber-windows off 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME allow-rename on 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME set-titles off 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME mouse on 2>/dev/null; " +
                        "tmux list-windows -t $TMUX_SESSION_NAME -F \"#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}|#{window_id}\" 2>/dev/null || echo \"\""
                Log.d(TAG, "[Probe] Executing: $probeCmd")
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(probeCmd)
                val input = channel.inputStream
                channel.connect(4000)
                val output = input.bufferedReader().readText()
                channel.disconnect()
                Log.d(TAG, "[Probe] Raw output:\n$output")

                val list = mutableListOf<TmuxWindowInfo>()
                output.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        val parts = trimmed.split("|")
                        val idx = parts.getOrNull(0)?.toIntOrNull()
                        if (idx != null) {
                            val name = parts.getOrNull(1) ?: idx.toString()
                            val path = parts.getOrNull(2)
                            val paneId = parts.getOrNull(3)?.trim()?.takeIf { it.startsWith("%") }
                            val winId = parts.getOrNull(4)?.trim()?.takeIf { it.startsWith("@") }
                            list.add(TmuxWindowInfo(index = idx, name = name, path = path, paneId = paneId, windowId = winId))
                        }
                    }
                }
                list.sortBy { it.index }
                Log.d(TAG, "[Probe] Discovered windows parsed: $list")
                return@withContext list
            } finally {
                session.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Probe] Error probing tmux windows (may not exist yet)", e)
            return@withContext emptyList()
        }
    }

    private suspend fun createRemoteTmuxWindow(
        host: String,
        port: Int,
        user: String,
        pass: String,
        workingDir: String?
    ): TmuxWindowInfo? = withContext(Dispatchers.IO) {
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
                val dirArg = if (!workingDir.isNullOrBlank()) "-c \"$workingDir\" " else ""
                val cmd = UNIVERSAL_SSH_PATH +
                        "tmux set-option -g base-index 1 2>/dev/null; " +
                        "tmux set-option -g renumber-windows off 2>/dev/null; " +
                        "tmux set-option -g allow-rename on 2>/dev/null; " +
                        "tmux set-option -g set-titles off 2>/dev/null; " +
                        "tmux set-option -g mouse on 2>/dev/null; " +
                        "if ! tmux has-session -t $TMUX_SESSION_NAME 2>/dev/null; then " +
                        "tmux new-session -d -s $TMUX_SESSION_NAME $dirArg-P -F \"#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}|#{window_id}\"; " +
                        "tmux set-option -t $TMUX_SESSION_NAME base-index 1 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME renumber-windows off 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME allow-rename on 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME set-titles off 2>/dev/null; " +
                        "tmux set-option -t $TMUX_SESSION_NAME mouse on 2>/dev/null; " +
                        "else " +
                        "tmux new-window -d -t $TMUX_SESSION_NAME $dirArg-P -F \"#{window_index}|#{window_name}|#{pane_current_path}|#{pane_id}|#{window_id}\"; " +
                        "fi"
                Log.d(TAG, "[Manager] Pre-creating window: $cmd")
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(cmd)
                val input = channel.inputStream
                channel.connect(4000)
                val output = input.bufferedReader().readText().trim()
                channel.disconnect()
                Log.d(TAG, "[Manager] Created window output: $output")
                val validLine = output.lines().lastOrNull { it.contains("|") }
                if (validLine != null) {
                    val parts = validLine.split("|")
                    val idx = parts.getOrNull(0)?.toIntOrNull()
                    if (idx != null) {
                        val name = parts.getOrNull(1) ?: ""
                        val path = parts.getOrNull(2)
                        val paneId = parts.getOrNull(3)?.trim()?.takeIf { it.startsWith("%") }
                        val winId = parts.getOrNull(4)?.trim()?.takeIf { it.startsWith("@") }
                        return@withContext TmuxWindowInfo(index = idx, name = name, path = path, paneId = paneId, windowId = winId)
                    }
                }
                return@withContext null
            } finally {
                session.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Manager] Error pre-creating remote tmux window", e)
            return@withContext null
        }
    }

    fun createNewSession(context: Context, workingDir: String? = null, forceShell: String? = null) {
        val authPrefs = AuthPreferences(context)
        managerScope.launch {
            val defaultUseSsh = !LocalEnvironmentManager.isTermuxPackage(context)
            val useSsh = authPrefs.useSshTerminal.firstOrNull() ?: defaultUseSsh
            val host = authPrefs.termuxSshHost.firstOrNull() ?: "127.0.0.1"
            val port = authPrefs.termuxSshPort.firstOrNull() ?: 8022
            val user = authPrefs.termuxSshUser.firstOrNull() ?: "root"
            val pass = authPrefs.termuxSshPass.firstOrNull() ?: "root"

            if (!useSsh) {
                val existingIndices = _sessions.value.mapNotNull {
                    it.id.removePrefix("session-").substringBefore("-").toIntOrNull()
                }
                val nextWinIndex = (existingIndices.maxOrNull() ?: _sessions.value.size) + 1
                val sessionTitle = if (forceShell == "bash") "Bash $nextWinIndex" else "Session $nextWinIndex"
                Log.d(TAG, "[Manager] Creating local session $nextWinIndex (forceShell=$forceShell)")
                val newSession = LocalPtySession(
                    id = "session-$nextWinIndex-${System.currentTimeMillis() % 10000}",
                    initialTitle = sessionTitle,
                    context = context.applicationContext,
                    isSsh = false,
                    initialWorkingDir = workingDir,
                    forceShell = forceShell,
                    initialCols = lastKnownCols,
                    initialRows = lastKnownRows,
                    initialWidthPx = lastKnownWidthPx,
                    initialHeightPx = lastKnownHeightPx
                )
                _sessions.value = _sessions.value + newSession
                _activeSessionId.value = newSession.id
                return@launch
            }

            _isSyncingTmux.value = true
            Log.d(TAG, "[Manager] Creating new tmux window on server...")
            val createdWin = withContext(Dispatchers.IO) {
                createRemoteTmuxWindow(host, port, user, pass, workingDir)
            }
            _isSyncingTmux.value = false

            val nextWinIndex: Int
            val initialWinName: String
            val initialWinPath: String?
            val initialPaneId: String?

            if (createdWin != null) {
                nextWinIndex = createdWin.index
                initialWinName = createdWin.name
                initialWinPath = createdWin.path ?: workingDir
                initialPaneId = createdWin.paneId
            } else {
                val localIndices = _sessions.value.mapNotNull { it.tmuxWindowIndex }
                nextWinIndex = if (localIndices.isEmpty()) 1 else (localIndices.maxOrNull() ?: 0) + 1
                initialWinName = ""
                initialWinPath = workingDir
                initialPaneId = null
            }

            val winTitle = formatTmuxTitle(nextWinIndex, initialWinName)
            val sessionId = "session-tmux-$nextWinIndex"

            val existing = _sessions.value.find { it.id == sessionId }
            existing?.close()
            val filtered = _sessions.value.filterNot { it.id == sessionId }

            val newSession = LocalPtySession(
                id = sessionId,
                initialTitle = winTitle,
                context = context.applicationContext,
                isSsh = true,
                sshHost = host,
                sshPort = port,
                sshUser = user,
                sshPass = pass,
                tmuxWindowIndex = nextWinIndex,
                tmuxSessionName = TMUX_SESSION_NAME,
                initialWorkingDir = initialWinPath,
                initialPaneId = initialPaneId,
                initialWindowId = createdWin?.windowId,
                initialCols = lastKnownCols,
                initialRows = lastKnownRows,
                initialWidthPx = lastKnownWidthPx,
                initialHeightPx = lastKnownHeightPx
            )
            _sessions.value = filtered + newSession
            _activeSessionId.value = newSession.id
            saveLastActiveTmuxIndex(context, nextWinIndex)
            Log.d(
                TAG,
                "[Manager] Created and activated new tab session ${newSession.id} (winIdx=$nextWinIndex, paneId=$initialPaneId, title=$winTitle)"
            )
        }
    }

    fun selectSession(id: String, context: Context? = null) {
        Log.d(TAG, "[Manager] Selecting session $id (previous=${_activeSessionId.value})")
        _activeSessionId.value = id
        val session = _sessions.value.find { it.id == id }
        val winIdx = session?.tmuxWindowIndex
        val ctx = context ?: session?.context
        if (winIdx != null && ctx != null) {
            saveLastActiveTmuxIndex(ctx, winIdx)
        }
    }

    fun selectPreviousSession(context: Context? = null) {
        val current = _sessions.value
        if (current.size <= 1) return
        val currentIndex = current.indexOfFirst { it.id == _activeSessionId.value }
        if (currentIndex == -1) return
        val prevIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else current.size - 1
        selectSession(current[prevIndex].id, context)
    }

    fun selectNextSession(context: Context? = null) {
        val current = _sessions.value
        if (current.size <= 1) return
        val currentIndex = current.indexOfFirst { it.id == _activeSessionId.value }
        if (currentIndex == -1) return
        val nextIndex = (currentIndex + 1) % current.size
        selectSession(current[nextIndex].id, context)
    }

    fun closeSession(id: String) {
        Log.d(TAG, "[Manager] closeSession requested for $id")
        val current = _sessions.value
        val toClose = current.find { it.id == id }
        val remaining = current.filterNot { it.id == id }

        toClose?.close()
        _sessions.value = remaining

        if (_activeSessionId.value == id) {
            val newActive = remaining.firstOrNull()
            _activeSessionId.value = newActive?.id
            Log.d(TAG, "[Manager] Active session switched to ${_activeSessionId.value}")
            val winIdx = newActive?.tmuxWindowIndex
            val ctx = newActive?.context
            if (winIdx != null && ctx != null) {
                saveLastActiveTmuxIndex(ctx, winIdx)
            }
        }

        if (toClose != null && toClose.isSsh) {
            val paneId = toClose.assignedPaneId
            val winIdx = toClose.tmuxWindowIndex
            val host = toClose.sshHost
            val port = toClose.sshPort
            val user = toClose.sshUser
            val pass = toClose.sshPass

            // Asynchronously kill remote tmux window/pane without killing grouped session (which unlinks other windows)
            managerScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "[Manager] Connecting SSH to kill remote target (pane=$paneId, winIdx=$winIdx)...")
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
                        val targetCmd = if (!paneId.isNullOrBlank()) "tmux kill-pane -t $paneId 2>/dev/null; " else ""
                        val winCmd = if (winIdx != null) "tmux kill-window -t $TMUX_SESSION_NAME:$winIdx 2>/dev/null; " else ""
                        val cmd = UNIVERSAL_SSH_PATH + targetCmd + winCmd
                        Log.d(TAG, "[Manager] Executing remote kill: $cmd")
                        channel.setCommand(cmd)
                        val input = channel.inputStream
                        channel.connect(4000)
                        input.bufferedReader().readText() // Wait for command execution to complete on remote server
                        channel.disconnect()
                        Log.d(TAG, "[Manager] Remote target killed successfully")
                    } finally {
                        session.disconnect()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[Manager] Error killing remote target", e)
                }
            }
        }
    }

    fun closeAll() {
        Log.d(TAG, "[Manager] closeAll requested")
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
                        val clientKills =
                            windowIndices.joinToString("; ") { "tmux kill-session -t ${TMUX_SESSION_NAME}_$it 2>/dev/null" }
                        val cmd = UNIVERSAL_SSH_PATH + clientKills
                        Log.d(TAG, "[Manager] Disconnecting client attachments: $cmd")
                        channel.setCommand(cmd)
                        val input = channel.inputStream
                        channel.connect(4000)
                        input.bufferedReader().readText()
                        channel.disconnect()
                    } finally {
                        session.disconnect()
                    }
                } catch (_: Exception) {
                }
            }
        }

        current.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }
}
