package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata info about a running terminal session.
 */
data class TerminalSessionInfo(
    val id: String,
    val title: String,
    val workingDirectory: String,
    val isSsh: Boolean,
    val tmuxWindowIndex: Int?,
    val assignedPaneId: String?,
    val isExited: Boolean,
    val ptyCols: Int,
    val ptyRows: Int,
    val lastCommand: String? = null,
    val isCommandRunning: Boolean = false
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("workingDirectory", workingDirectory)
        put("isSsh", isSsh)
        put("tmuxWindowIndex", tmuxWindowIndex ?: JSONObject.NULL)
        put("assignedPaneId", assignedPaneId ?: JSONObject.NULL)
        put("isExited", isExited)
        put("cols", ptyCols)
        put("rows", ptyRows)
        put("lastCommand", lastCommand ?: JSONObject.NULL)
        put("isCommandRunning", isCommandRunning)
    }
}

data class TerminalExecutionRecord(
    val command: String,
    val startedAtMs: Long = System.currentTimeMillis(),
    val startLineOffset: Int = 0,
    var isRunning: Boolean = true,
    var exitCode: Int? = null,
    var finishedAtMs: Long? = null
)

/**
 * High-level bridge to inspect and interact with live terminal sessions.
 */
class LocalTerminalBridge private constructor() {

    companion object {
        private const val TAG = "LocalTerminalBridge"
        val instance: LocalTerminalBridge by lazy { LocalTerminalBridge() }

        private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Z0-9]")
    }

    private var appContext: Context? = null
    private val sessionExecutions = java.util.concurrent.ConcurrentHashMap<String, MutableList<TerminalExecutionRecord>>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Lists all active terminal sessions with live command state.
     */
    fun listSessions(): List<TerminalSessionInfo> {
        val sessions = LocalTerminalManager.sessions.value
        return sessions.map { s ->
            val lastExec = sessionExecutions[s.id]?.lastOrNull()
            TerminalSessionInfo(
                id = s.id,
                title = s.name,
                workingDirectory = s.workingDirectory,
                isSsh = s.isSsh,
                tmuxWindowIndex = s.tmuxWindowIndex,
                assignedPaneId = s.assignedPaneId,
                isExited = s.isExited.value,
                ptyCols = s.ptyCols,
                ptyRows = s.ptyRows,
                lastCommand = lastExec?.command,
                isCommandRunning = lastExec?.isRunning ?: false
            )
        }
    }

    fun listSessionsJson(): JSONArray {
        val array = JSONArray()
        listSessions().forEach { array.put(it.toJsonObject()) }
        return array
    }

    /**
     * Finds a session by id or returns the active/first session.
     */
    fun getSession(sessionId: String? = null): LocalPtySession? {
        val sessions = LocalTerminalManager.sessions.value
        if (sessionId.isNullOrBlank()) {
            val activeId = LocalTerminalManager.activeSessionId.value
            return sessions.find { it.id == activeId } ?: sessions.firstOrNull()
        }
        return sessions.find { it.id == sessionId } ?: sessions.find { it.name.equals(sessionId, ignoreCase = true) }
    }

    /**
     * Sends a command or keystrokes to a session.
     */
    suspend fun sendCommand(
        sessionId: String? = null,
        command: String,
        appendEnter: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            val transcriptLines = session.terminalSession.emulator?.screen?.getTranscriptText()?.lines()?.size ?: 0
            val record = TerminalExecutionRecord(
                command = command.trim(),
                startedAtMs = System.currentTimeMillis(),
                startLineOffset = transcriptLines,
                isRunning = true
            )
            sessionExecutions.computeIfAbsent(session.id) { mutableListOf() }.add(record)

            val payload = if (appendEnter && !command.endsWith("\n") && !command.endsWith("\r")) {
                "$command\n"
            } else {
                command
            }
            session.write(payload)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command to terminal", e)
            Result.failure(e)
        }
    }

    /**
     * Sends raw control bytes (e.g. 0x03 for Ctrl+C, 0x04 for Ctrl+D, 0x1A for Ctrl+Z)
     */
    suspend fun sendControlKey(
        sessionId: String? = null,
        key: String // "SIGINT" (Ctrl+C), "EOF" (Ctrl+D), "SIGTSTP" (Ctrl+Z), etc.
    ): Result<Boolean> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            val bytes = when (key.uppercase()) {
                "CTRL+C", "SIGINT" -> {
                    sessionExecutions[session.id]?.lastOrNull()?.apply {
                        isRunning = false
                        finishedAtMs = System.currentTimeMillis()
                        exitCode = 130
                    }
                    byteArrayOf(0x03)
                }
                "CTRL+D", "EOF" -> byteArrayOf(0x04)
                "CTRL+Z", "SIGTSTP" -> byteArrayOf(0x1A)
                "CTRL+\\", "SIGQUIT" -> byteArrayOf(0x1C)
                "ENTER" -> byteArrayOf(0x0D)
                "TAB" -> byteArrayOf(0x09)
                "ESCAPE", "ESC" -> byteArrayOf(0x1B)
                else -> byteArrayOf(0x03)
            }
            session.writeBytes(bytes)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads terminal transcript text buffer.
     */
    suspend fun readTranscript(
        sessionId: String? = null,
        maxLines: Int = 100
    ): Result<String> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            val em = session.terminalSession.emulator
            val transcript = em?.screen?.getTranscriptText() ?: ""
            val clean = ANSI_REGEX.replace(transcript, "")
            val lines = clean.lines()
            val limited = if (lines.size > maxLines) {
                lines.takeLast(maxLines).joinToString("\n")
            } else {
                clean
            }
            Result.success(limited)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read terminal transcript", e)
            Result.failure(e)
        }
    }

    /**
     * Reads structured execution data (last command and its direct output only).
     */
    suspend fun getLastExecution(
        sessionId: String? = null,
        maxLines: Int = 100
    ): Result<JSONObject> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            val em = session.terminalSession.emulator
            val fullTranscript = em?.screen?.getTranscriptText() ?: ""
            val allLines = fullTranscript.lines()
            val lastExec = sessionExecutions[session.id]?.lastOrNull()

            val outputText = if (lastExec != null && lastExec.startLineOffset in allLines.indices) {
                allLines.drop(lastExec.startLineOffset).takeLast(maxLines).joinToString("\n")
            } else {
                allLines.takeLast(maxLines).joinToString("\n")
            }

            val cleanOutput = ANSI_REGEX.replace(outputText, "").trim()

            val json = JSONObject().apply {
                put("session_id", session.id)
                put("session_title", session.name)
                put("working_directory", session.workingDirectory)
                put("last_command", lastExec?.command ?: "")
                put("is_running", lastExec?.isRunning ?: false)
                put("started_at_ms", lastExec?.startedAtMs ?: 0L)
                put("exit_code", lastExec?.exitCode ?: JSONObject.NULL)
                put("output", cleanOutput)
            }
            Result.success(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last execution", e)
            Result.failure(e)
        }
    }

    /**
     * Kills a foreground process running in the terminal session (SIGINT/Ctrl+C, SIGTERM, or SIGKILL).
     */
    suspend fun killProcess(
        sessionId: String? = null,
        signal: String = "SIGINT"
    ): Result<Boolean> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            when (signal.uppercase()) {
                "SIGINT", "CTRL+C", "INT" -> {
                    // Send standard Interrupt signal 0x03
                    session.writeBytes(byteArrayOf(0x03))
                }
                "SIGQUIT", "CTRL+\\", "QUIT" -> {
                    // Send Quit signal 0x1C
                    session.writeBytes(byteArrayOf(0x1C))
                }
                "SIGTSTP", "CTRL+Z", "SUSPEND" -> {
                    // Send Suspend signal 0x1A
                    session.writeBytes(byteArrayOf(0x1A))
                }
                "SIGKILL", "KILL", "9" -> {
                    // Send Ctrl+C first, followed by process termination command
                    session.writeBytes(byteArrayOf(0x03))
                    if (!session.isSsh) {
                        try {
                            val pid = session.terminalSession.pid
                            if (pid > 0) {
                                android.os.Process.sendSignal(pid, 9)
                            }
                        } catch (_: Exception) {}
                    }
                }
                "SIGTERM", "TERM", "15" -> {
                    session.writeBytes(byteArrayOf(0x03))
                    if (!session.isSsh) {
                        try {
                            val pid = session.terminalSession.pid
                            if (pid > 0) {
                                android.os.Process.sendSignal(pid, 15)
                            }
                        } catch (_: Exception) {}
                    }
                }
                else -> {
                    session.writeBytes(byteArrayOf(0x03))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill process in terminal session", e)
            Result.failure(e)
        }
    }

    /**
     * Closes and removes the terminal session tab.
     */
    suspend fun closeSession(sessionId: String): Result<Boolean> = withContext(Dispatchers.Main) {
        val session = getSession(sessionId)
            ?: return@withContext Result.failure(Exception("Terminal session not found: $sessionId"))

        try {
            LocalTerminalManager.closeSession(session.id)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close terminal session", e)
            Result.failure(e)
        }
    }

    /**
     * Spawns a new terminal tab/session.
     */
    suspend fun createSession(
        workingDir: String? = null,
        forceShell: String? = null
    ): Result<TerminalSessionInfo> = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext Result.failure(Exception("Context not initialized"))
        try {
            val initialCount = LocalTerminalManager.sessions.value.size
            LocalTerminalManager.createNewSession(ctx, workingDir, forceShell)

            // Wait a brief moment for session to be appended
            var retries = 10
            while (retries > 0 && LocalTerminalManager.sessions.value.size == initialCount) {
                kotlinx.coroutines.delay(100)
                retries--
            }

            val newSession = LocalTerminalManager.sessions.value.lastOrNull()
            if (newSession != null) {
                Result.success(
                    TerminalSessionInfo(
                        id = newSession.id,
                        title = newSession.name,
                        workingDirectory = newSession.workingDirectory,
                        isSsh = newSession.isSsh,
                        tmuxWindowIndex = newSession.tmuxWindowIndex,
                        assignedPaneId = newSession.assignedPaneId,
                        isExited = newSession.isExited.value,
                        ptyCols = newSession.ptyCols,
                        ptyRows = newSession.ptyRows
                    )
                )
            } else {
                Result.failure(Exception("Session creation timed out"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
