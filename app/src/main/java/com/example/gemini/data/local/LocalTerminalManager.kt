package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.example.gemini.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.util.UUID

data class StyledTerminalLine(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val annotatedString: AnnotatedString
)

class LocalInteractiveSession(
    val id: String,
    var name: String,
    val context: Context,
    initialWorkingDir: String? = null
) {
    private val TAG = "TerminalSession-$id"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var workingDirectory: String = initialWorkingDir ?: LocalEnvironmentManager.getHomeDir(context).absolutePath
        private set

    private var process: Process? = null
    private var outputWriter: OutputStreamWriter? = null
    private var isAlive = false

    private val _lines = MutableStateFlow<List<StyledTerminalLine>>(emptyList())
    val lines: StateFlow<List<StyledTerminalLine>> = _lines.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isExited = MutableStateFlow(false)
    val isExited: StateFlow<Boolean> = _isExited.asStateFlow()

    init {
        startShell()
    }

    private fun startShell() {
        scope.launch {
            try {
                val prefix = LocalEnvironmentManager.getPrefixDir(context)
                val bin = LocalEnvironmentManager.getBinDir(context)
                val lib = LocalEnvironmentManager.getLibDir(context)
                val home = LocalEnvironmentManager.getHomeDir(context)
                val tmp = LocalEnvironmentManager.getTmpDir(context)

                val env = mutableMapOf<String, String>()
                env["PREFIX"] = prefix.absolutePath
                env["HOME"] = home.absolutePath
                env["PATH"] = "${bin.absolutePath}:/system/bin:/system/xbin"
                env["TMPDIR"] = tmp.absolutePath
                env["LD_LIBRARY_PATH"] = "${lib.absolutePath}:/system/lib64:/system/lib"
                env["TERM"] = "xterm-256color"
                env["COLORTERM"] = "truecolor"
                env["LANG"] = "en_US.UTF-8"
                env["LC_ALL"] = "en_US.UTF-8"
                env["PS1"] = "\\[\\033[01;32m\\]gemini\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\$ "

                val shellBinary = when {
                    File(bin, "bash").exists() && File(bin, "bash").canExecute() -> File(bin, "bash").absolutePath
                    File(bin, "dash").exists() && File(bin, "dash").canExecute() -> File(bin, "dash").absolutePath
                    File(bin, "sh").exists() && File(bin, "sh").canExecute() -> File(bin, "sh").absolutePath
                    File(bin, "dash").exists() -> File(bin, "dash").absolutePath
                    File(bin, "bash").exists() -> File(bin, "bash").absolutePath
                    File(bin, "sh").exists() -> File(bin, "sh").absolutePath
                    else -> "/system/bin/sh"
                }

                val pb = ProcessBuilder(shellBinary)
                if (File(workingDirectory).exists()) {
                    pb.directory(File(workingDirectory))
                } else {
                    pb.directory(home)
                }

                val pbEnv = pb.environment()
                pbEnv.putAll(env)
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc
                outputWriter = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
                isAlive = true

                // Welcome message
                appendLine("\u001B[1;36m━━━ Gemini Local Terminal (Termux Shell) ━━━\u001B[0m")
                appendLine("\u001B[0;32m✓ Environment ready in ${home.absolutePath}\u001B[0m")
                appendLine("\u001B[0;90mType commands below. Use Quick-Keys for ESC, TAB, CTRL & arrows.\u001B[0m\n")

                // Initial command to load bashrc if available
                val bashrc = File(home, ".bashrc")
                if (bashrc.exists()) {
                    sendCommand(". \"${bashrc.absolutePath}\"\n")
                }

                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                var line: String? = null
                while (proc.isAlive && reader.readLine().also { line = it } != null) {
                    line?.let { raw ->
                        appendLine(raw)
                    }
                }

                val exitVal = try { proc.waitFor() } catch (_: Exception) { 0 }
                appendLine("\n\u001B[1;31m[Process completed with exit code $exitVal]\u001B[0m")
                _isExited.value = true
                isAlive = false

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start shell: ${e.message}", e)
                appendLine("\u001B[1;31m[Failed to start shell: ${e.message}]\u001B[0m")
                _isExited.value = true
                isAlive = false
            }
        }
    }

    fun executeCommand(cmd: String) {
        val home = LocalEnvironmentManager.getHomeDir(context).canonicalPath
        val dirName = if (workingDirectory.startsWith(home)) {
            val rel = workingDirectory.removePrefix(home)
            if (rel.isEmpty()) "~" else "~$rel"
        } else {
            workingDirectory.substringAfterLast("/").ifBlank { "/" }
        }

        if (cmd.isBlank()) {
            appendLine("\u001B[1;32m➜ \u001B[1;34m$dirName \u001B[0m")
            sendRawInput("\n")
            return
        }

        // Echo prompt + command into terminal history buffer
        appendLine("\u001B[1;32m➜ \u001B[1;34m$dirName \u001B[1;37m$cmd\u001B[0m")

        val trimmed = cmd.trim()
        if (trimmed == "clear" || trimmed == "cls") {
            clearLines()
            return
        }

        sendCommand(cmd)
    }

    fun sendCommand(cmd: String) {
        if (!isAlive || process == null) {
            startShell()
        }
        scope.launch {
            try {
                _isBusy.value = true
                val writer = outputWriter ?: return@launch
                
                // Track working directory changes
                val trimmed = cmd.trim()
                if (trimmed == "cd" || trimmed == "cd ~") {
                    workingDirectory = LocalEnvironmentManager.getHomeDir(context).canonicalPath
                } else if (trimmed.startsWith("cd ")) {
                    val rawTarget = trimmed.substring(3).trim().removeSurrounding("\"").removeSurrounding("'")
                    val home = LocalEnvironmentManager.getHomeDir(context)
                    val target = if (rawTarget == "~") {
                        home
                    } else if (rawTarget.startsWith("~/")) {
                        File(home, rawTarget.removePrefix("~/"))
                    } else if (rawTarget.startsWith("/")) {
                        File(rawTarget)
                    } else {
                        File(workingDirectory, rawTarget)
                    }
                    if (target.exists() && target.isDirectory) {
                        workingDirectory = target.canonicalPath
                    }
                }

                writer.write(if (cmd.endsWith("\n")) cmd else "$cmd\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to process: ${e.message}")
            } finally {
                delay(80)
                _isBusy.value = false
            }
        }
    }

    fun sendRawInput(text: String) {
        scope.launch {
            try {
                val writer = outputWriter ?: return@launch
                writer.write(text)
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending raw input: ${e.message}")
            }
        }
    }

    fun sendCtrlC() {
        sendRawInput("\u0003")
        appendLine("^C")
    }

    fun sendCtrlD() {
        sendRawInput("\u0004")
    }

    fun sendCtrlL() {
        clearLines()
        sendRawInput("\u000C")
    }

    fun sendTab() {
        sendRawInput("\t")
    }

    fun sendEsc() {
        sendRawInput("\u001B")
    }

    fun sendArrowUp() {
        sendRawInput("\u001B[A")
    }

    fun sendArrowDown() {
        sendRawInput("\u001B[B")
    }

    fun sendArrowRight() {
        sendRawInput("\u001B[C")
    }

    fun sendArrowLeft() {
        sendRawInput("\u001B[D")
    }

    fun sendHome() {
        sendRawInput("\u001B[H")
    }

    fun sendEnd() {
        sendRawInput("\u001B[F")
    }

    fun sendPgUp() {
        sendRawInput("\u001B[5~")
    }

    fun sendPgDn() {
        sendRawInput("\u001B[6~")
    }

    fun clearLines() {
        _lines.value = emptyList()
    }

    private fun appendLine(raw: String) {
        val styled = StyledTerminalLine(
            rawText = raw,
            annotatedString = parseAnsiToAnnotatedString(raw)
        )
        val current = _lines.value
        // Maintain a max buffer of 2,500 lines for optimal UI performance
        val updated = if (current.size > 2500) {
            current.drop(current.size - 2400) + styled
        } else {
            current + styled
        }
        _lines.value = updated
    }

    fun close() {
        try {
            scope.cancel()
            process?.destroyForcibly()
            isAlive = false
            _isExited.value = true
        } catch (_: Exception) {}
    }

    companion object {
        /**
         * Converts ANSI escape codes into Jetpack Compose AnnotatedString with rich colors
         */
        fun parseAnsiToAnnotatedString(text: String): AnnotatedString {
            if (!text.contains("\u001B[")) {
                return AnnotatedString(text)
            }

            return buildAnnotatedString {
                var currentIndex = 0
                val ansiRegex = Regex("\u001B\\[([0-9;]*)m")
                val matches = ansiRegex.findAll(text).toList()

                var currentColor = Color(0xFFD4D4D8)
                var currentWeight = FontWeight.Normal
                var isUnderline = false

                for (match in matches) {
                    val start = match.range.first
                    val end = match.range.last + 1

                    if (start > currentIndex) {
                        val chunk = text.substring(currentIndex, start)
                        val chunkStart = length
                        append(chunk)
                        addStyle(
                            SpanStyle(
                                color = currentColor,
                                fontWeight = currentWeight
                            ),
                            chunkStart,
                            length
                        )
                    }

                    val codeStr = match.groupValues[1]
                    val codes = if (codeStr.isEmpty()) listOf(0) else codeStr.split(";").mapNotNull { it.toIntOrNull() }

                    for (code in codes) {
                        when (code) {
                            0 -> {
                                currentColor = Color(0xFFD4D4D8)
                                currentWeight = FontWeight.Normal
                                isUnderline = false
                            }
                            1 -> currentWeight = FontWeight.Bold
                            2 -> currentColor = Color(0xFF8E8E93) // Dim
                            4 -> isUnderline = true
                            22 -> currentWeight = FontWeight.Normal
                            30 -> currentColor = Color(0xFF4B5563) // Black
                            31 -> currentColor = Color(0xFFEF4444) // Red
                            32 -> currentColor = Color(0xFF10B981) // Green
                            33 -> currentColor = Color(0xFFF59E0B) // Yellow
                            34 -> currentColor = Color(0xFF3B82F6) // Blue
                            35 -> currentColor = Color(0xFFA855F7) // Magenta
                            36 -> currentColor = Color(0xFF06B6D4) // Cyan
                            37 -> currentColor = Color(0xFFE5E7EB) // White
                            90 -> currentColor = Color(0xFF6B7280) // Bright Black (Gray)
                            91 -> currentColor = Color(0xFFF87171) // Bright Red
                            92 -> currentColor = Color(0xFF34D399) // Bright Green
                            93 -> currentColor = Color(0xFFFBBF24) // Bright Yellow
                            94 -> currentColor = Color(0xFF60A5FA) // Bright Blue
                            95 -> currentColor = Color(0xFFC084FC) // Bright Magenta
                            96 -> currentColor = Color(0xFF22D3EE) // Bright Cyan
                            97 -> currentColor = Color(0xFFFFFFFF) // Bright White
                        }
                    }
                    currentIndex = end
                }

                if (currentIndex < text.length) {
                    val remaining = text.substring(currentIndex)
                    val remStart = length
                    append(remaining)
                    addStyle(
                        SpanStyle(
                            color = currentColor,
                            fontWeight = currentWeight
                        ),
                        remStart,
                        length
                    )
                }
            }
        }
    }
}

object LocalTerminalManager {

    private val _sessions = MutableStateFlow<List<LocalInteractiveSession>>(emptyList())
    val sessions: StateFlow<List<LocalInteractiveSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    fun getOrCreatePrimarySession(context: Context): LocalInteractiveSession {
        val existing = _sessions.value.find { it.id == _activeSessionId.value }
            ?: _sessions.value.firstOrNull()

        if (existing != null) {
            return existing
        }

        val newSession = LocalInteractiveSession(
            id = "session-1",
            name = "Session 1",
            context = context
        )
        _sessions.value = listOf(newSession)
        _activeSessionId.value = newSession.id
        return newSession
    }

    fun createNewSession(context: Context): LocalInteractiveSession {
        val newIndex = _sessions.value.size + 1
        val newSession = LocalInteractiveSession(
            id = "session-$newIndex-${System.currentTimeMillis() % 10000}",
            name = "Session $newIndex",
            context = context
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
