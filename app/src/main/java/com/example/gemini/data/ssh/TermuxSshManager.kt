package com.example.gemini.data.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class SshConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class CommandStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TERMINATED
}

enum class SessionTabStatus {
    ACTIVE,
    BUSY,
    KILLED
}

data class TerminalCommand(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val output: String = "",
    val status: CommandStatus = CommandStatus.RUNNING,
    val exitCode: Int? = null,
    val startTime: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val error: String? = null
)

// Backwards-compatible alias for existing references
typealias TerminalSession = TerminalCommand

data class TerminalSessionTab(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Session 1",
    val isPrimary: Boolean = false,
    val status: SessionTabStatus = SessionTabStatus.ACTIVE,
    val workingDirectory: String = "~",
    val commands: List<TerminalCommand> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * High-performance Termux SSH Connection & Multi-Session Tab Manager.
 * Reuses a single persistent JSch SSH connection pool with working directory preservation across commands.
 */
object TermuxSshManager {

    private const val TAG = "TermuxSshManager"
    private val jsch = JSch()

    private var activeSshSession: Session? = null
    private var currentConfigKey: String = ""

    private val _connectionStatus = MutableStateFlow(SshConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<SshConnectionStatus> = _connectionStatus.asStateFlow()

    // Default primary session tab for AI agent & user
    private val initialTab = TerminalSessionTab(
        id = "primary",
        name = "AI Agent (Primary)",
        isPrimary = true,
        status = SessionTabStatus.ACTIVE
    )

    private val _tabs = MutableStateFlow<List<TerminalSessionTab>>(listOf(initialTab))
    val tabs: StateFlow<List<TerminalSessionTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow("primary")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    // Backwards-compatible sessions flow for HUD
    val sessions: StateFlow<List<TerminalCommand>>
        get() {
            val allCommands = _tabs.value.flatMap { it.commands }
            return MutableStateFlow(allCommands).asStateFlow()
        }

    private val runningChannels = ConcurrentHashMap<String, ChannelExec>()

    /**
     * Obtains an existing connected SSH session or establishes a new one.
     */
    @Synchronized
    private fun getOrCreateSession(host: String, port: Int, user: String, pass: String): Session {
        val configKey = "$user@$host:$port:$pass"

        val current = activeSshSession
        if (current != null && current.isConnected && currentConfigKey == configKey) {
            return current
        }

        try {
            current?.disconnect()
        } catch (_: Exception) {}

        _connectionStatus.value = SshConnectionStatus.CONNECTING
        Log.d(TAG, "[SSH] Connecting to $user@$host:$port...")

        val session = jsch.getSession(user, host, port)
        session.setPassword(pass)

        val config = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive,publickey")
            put("ConnectTimeout", "10000")
            put("compression.s2c", "zlib@openssh.com,zlib,none")
            put("compression.c2s", "zlib@openssh.com,zlib,none")
        }
        session.setConfig(config)
        session.timeout = 15000
        session.connect(10000)

        activeSshSession = session
        currentConfigKey = configKey
        _connectionStatus.value = SshConnectionStatus.CONNECTED
        Log.d(TAG, "[SSH] Successfully connected and pooled persistent SSH session to $user@$host:$port")

        return session
    }

    /**
     * Create a new terminal session tab.
     */
    fun createTab(customName: String? = null): TerminalSessionTab {
        val currentTabs = _tabs.value
        val tabNumber = currentTabs.size + 1
        val newTab = TerminalSessionTab(
            name = customName ?: "Session $tabNumber",
            isPrimary = false,
            status = SessionTabStatus.ACTIVE
        )
        _tabs.value = currentTabs + newTab
        _activeTabId.value = newTab.id
        return newTab
    }

    /**
     * Select active terminal tab.
     */
    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /**
     * Close / Kill a specific terminal session tab.
     */
    fun closeTab(tabId: String) {
        val currentTabs = _tabs.value
        val targetTab = currentTabs.find { it.id == tabId } ?: return

        // Kill any active commands in this tab
        targetTab.commands.filter { it.status == CommandStatus.RUNNING }.forEach { cmd ->
            killCommand(cmd.id)
        }

        if (currentTabs.size <= 1) {
            // Keep at least one tab, just reset it
            _tabs.value = listOf(
                TerminalSessionTab(
                    id = "primary",
                    name = "AI Agent (Primary)",
                    isPrimary = true,
                    status = SessionTabStatus.ACTIVE
                )
            )
            _activeTabId.value = "primary"
        } else {
            val remaining = currentTabs.filter { it.id != tabId }
            _tabs.value = remaining
            if (_activeTabId.value == tabId) {
                _activeTabId.value = remaining.first().id
            }
        }
    }

    /**
     * Clear command history for a specific tab.
     */
    fun clearTabCommands(tabId: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) tab.copy(commands = emptyList()) else tab
        }
    }

    /**
     * Kill all commands and reset all sessions.
     */
    fun clearAllSessions() {
        runningChannels.forEach { (cmdId, channel) ->
            try {
                channel.disconnect()
            } catch (_: Exception) {}
        }
        runningChannels.clear()

        _tabs.value = listOf(
            TerminalSessionTab(
                id = "primary",
                name = "AI Agent (Primary)",
                isPrimary = true,
                status = SessionTabStatus.ACTIVE
            )
        )
        _activeTabId.value = "primary"
    }

    /**
     * Executes a command inside the target session tab (or primary tab).
     * Preserves directory context across commands within each tab.
     */
    suspend fun executeCommand(
        command: String,
        host: String,
        port: Int,
        user: String,
        pass: String,
        targetTabId: String? = null,
        customCmdId: String? = null,
        onChunk: (String) -> Unit = {}
    ): TerminalCommand = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val effectiveTabId = targetTabId ?: _activeTabId.value.ifEmpty { "primary" }

        // Find or fallback to primary tab
        val currentTabs = _tabs.value
        val tab = currentTabs.find { it.id == effectiveTabId } ?: currentTabs.first()
        val workingDir = tab.workingDirectory

        val cmdId = customCmdId ?: UUID.randomUUID().toString()
        var currentCmd = TerminalCommand(
            id = cmdId,
            command = command,
            status = CommandStatus.RUNNING,
            startTime = startTime
        )

        // Append running command to tab and mark tab busy
        updateTabCommand(tab.id, currentCmd, isTabBusy = true)

        try {
            val session = getOrCreateSession(host, port, user, pass)
            val channel = session.openChannel("exec") as ChannelExec
            runningChannels[cmdId] = channel

            val pwdMarker = "__ANTIGRAVITY_PWD:"
            val wrappedCommand = buildString {
                if (workingDir.isNotBlank() && workingDir != "~") {
                    append("cd \"$workingDir\" 2>/dev/null || cd ~; ")
                }
                append(command)
            }

            channel.setCommand(wrappedCommand)
            channel.setPty(false)

            val inStream = channel.inputStream
            val errStream = channel.errStream

            channel.connect(10000)

            val outputBuilder = StringBuilder()
            val inReader = BufferedReader(InputStreamReader(inStream))
            val errReader = BufferedReader(InputStreamReader(errStream))

            val buffer = CharArray(1024)
            var read = 0

            withContext(Dispatchers.IO) {
                val stdoutJob = launch {
                    try {
                        while (inReader.read(buffer).also { read = it } != -1) {
                            val chunk = String(buffer, 0, read)
                            outputBuilder.append(chunk)
                            onChunk(chunk)
                        }
                    } catch (_: Exception) {}
                }
                val stderrJob = launch {
                    try {
                        var errRead = 0
                        val errBuffer = CharArray(1024)
                        while (errReader.read(errBuffer).also { errRead = it } != -1) {
                            val chunk = String(errBuffer, 0, errRead)
                            outputBuilder.append(chunk)
                            onChunk(chunk)
                        }
                    } catch (_: Exception) {}
                }

                while (!channel.isClosed) {
                    delay(50)
                }

                stdoutJob.join()
                stderrJob.join()
            }

            val exitCode = channel.exitStatus
            val duration = System.currentTimeMillis() - startTime
            val rawOutput = outputBuilder.toString()

            // Extract updated pwd if emitted
            val newPwd = extractNewPwd(rawOutput, pwdMarker) ?: workingDir
            val cleanOutput = sanitizeOutput(rawOutput, pwdMarker)

            currentCmd = currentCmd.copy(
                output = cleanOutput,
                status = if (exitCode == 0) CommandStatus.SUCCESS else CommandStatus.FAILED,
                exitCode = exitCode,
                durationMs = duration
            )

            updateTabCommand(tab.id, currentCmd, isTabBusy = false, updatedWorkingDir = newPwd)
            return@withContext currentCmd

        } catch (e: Exception) {
            Log.e(TAG, "[SSH] Execution error for command: $command", e)
            val duration = System.currentTimeMillis() - startTime
            currentCmd = currentCmd.copy(
                output = (currentCmd.output + "\n\nError: ${e.localizedMessage}").trim(),
                status = CommandStatus.FAILED,
                error = e.localizedMessage,
                durationMs = duration
            )
            updateTabCommand(tab.id, currentCmd, isTabBusy = false)
            return@withContext currentCmd
        } finally {
            runningChannels.remove(cmdId)
        }
    }

    /**
     * Terminate an active running command.
     */
    fun killCommand(cmdId: String) {
        val channel = runningChannels[cmdId]
        if (channel != null) {
            try {
                channel.disconnect()
            } catch (_: Exception) {}
            runningChannels.remove(cmdId)

            _tabs.value = _tabs.value.map { tab ->
                val updatedCommands = tab.commands.map { cmd ->
                    if (cmd.id == cmdId) {
                        cmd.copy(
                            status = CommandStatus.TERMINATED,
                            output = (cmd.output + "\n\n[Process terminated by user]").trim()
                        )
                    } else cmd
                }
                tab.copy(commands = updatedCommands, status = SessionTabStatus.KILLED)
            }
        }
    }

    /**
     * Backwards-compatible alias for killing by ID.
     */
    fun killSession(sessionId: String) = killCommand(sessionId)

    /**
     * Tests connectivity to Termux SSH server.
     */
    suspend fun testConnection(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = getOrCreateSession(host, port, user, pass)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("uname -a && uptime && whoami")
            val inStream = channel.inputStream
            channel.connect(5000)

            val reader = BufferedReader(InputStreamReader(inStream))
            val output = StringBuilder()
            val buffer = CharArray(512)
            var count = 0
            while (count < 20 && (!channel.isClosed || inStream.available() > 0)) {
                while (inStream.available() > 0) {
                    val read = reader.read(buffer)
                    if (read > 0) output.append(buffer, 0, read)
                }
                if (channel.isClosed) break
                delay(50)
                count++
            }
            channel.disconnect()
            Result.success(output.toString().ifEmpty { "Connected to Termux SSH successfully." })
        } catch (e: Exception) {
            Log.e(TAG, "[SSH] Connection test failed", e)
            _connectionStatus.value = SshConnectionStatus.ERROR
            Result.failure(e)
        }
    }

    private fun updateTabCommand(
        tabId: String,
        command: TerminalCommand,
        isTabBusy: Boolean,
        updatedWorkingDir: String? = null
    ) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                val existingCmds = tab.commands.toMutableList()
                val idx = existingCmds.indexOfFirst { it.id == command.id }
                if (idx >= 0) {
                    existingCmds[idx] = command
                } else {
                    existingCmds.add(command)
                }
                val newStatus = if (isTabBusy) SessionTabStatus.BUSY else if (tab.status == SessionTabStatus.KILLED) SessionTabStatus.ACTIVE else tab.status
                tab.copy(
                    commands = existingCmds,
                    status = newStatus,
                    workingDirectory = updatedWorkingDir ?: tab.workingDirectory
                )
            } else tab
        }
    }

    private fun sanitizeOutput(raw: String, pwdMarker: String): String {
        val idx = raw.lastIndexOf(pwdMarker)
        return if (idx >= 0) {
            raw.substring(0, idx).trimEnd()
        } else raw.trimEnd()
    }

    private fun extractNewPwd(raw: String, pwdMarker: String): String? {
        val idx = raw.lastIndexOf(pwdMarker)
        if (idx >= 0) {
            val after = raw.substring(idx + pwdMarker.length).trim()
            return after.lines().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
