package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed class LocalServerStatus {
    object Idle : LocalServerStatus()
    object Starting : LocalServerStatus()
    data class Running(val startTimeMs: Long = System.currentTimeMillis()) : LocalServerStatus()
    data class Stopped(val exitCode: Int? = null, val stopTimeMs: Long = System.currentTimeMillis()) : LocalServerStatus()
    data class Error(val message: String) : LocalServerStatus()
}

object LocalServerManager {

    private const val TAG = "LocalServerManager"

    private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Idle)
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    private val _serverSession = MutableStateFlow<LocalPtySession?>(null)
    val serverSession: StateFlow<LocalPtySession?> = _serverSession.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exitObserverJob: Job? = null
    private var activeJob: Job? = null

    // Track whether auto-start has already run during the lifetime of this app process
    private var hasInitialAutoStarted = false

    fun hasServerScript(context: Context): Boolean {
        val homeDir = LocalEnvironmentManager.getHomeDir(context)
        val startFile = File(homeDir, "start")
        val startShFile = File(homeDir, "start.sh")
        val serverFile = File(homeDir, "server")
        return startFile.exists() || startShFile.exists() || serverFile.exists()
    }

    private fun resolveServerCommand(context: Context): String? {
        val homeDir = LocalEnvironmentManager.getHomeDir(context)
        val startFile = File(homeDir, "start")
        val startShFile = File(homeDir, "start.sh")
        val serverFile = File(homeDir, "server")

        return when {
            startFile.exists() -> {
                try {
                    startFile.setExecutable(true, false)
                    startFile.setReadable(true, false)
                } catch (_: Exception) {}
                "./start"
            }
            startShFile.exists() -> {
                try {
                    startShFile.setExecutable(true, false)
                    startShFile.setReadable(true, false)
                } catch (_: Exception) {}
                "sh ./start.sh"
            }
            serverFile.exists() -> {
                try {
                    serverFile.setExecutable(true, false)
                    serverFile.setReadable(true, false)
                } catch (_: Exception) {}
                "./server -f"
            }
            else -> null
        }
    }

    private fun getSessionPid(session: LocalPtySession): Int? {
        return try {
            val pidField = session.terminalSession.javaClass.getDeclaredField("mPid")
            pidField.isAccessible = true
            val pid = pidField.getInt(session.terminalSession)
            if (pid > 0) pid else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun getDescendantPids(rootPid: Int): List<Int> {
        val descendants = mutableListOf<Int>()
        try {
            val procDir = File("/proc")
            val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return emptyList()
            val ppidMap = mutableMapOf<Int, Int>()
            val myPid = android.os.Process.myPid()

            for (pDir in pidDirs) {
                val p = pDir.name.toIntOrNull() ?: continue
                if (p == myPid) continue
                try {
                    val statFile = File(pDir, "stat")
                    if (statFile.exists()) {
                        val stat = statFile.readText()
                        val lastParen = stat.lastIndexOf(')')
                        if (lastParen != -1 && lastParen + 2 < stat.length) {
                            val rest = stat.substring(lastParen + 2).trim().split(" ")
                            if (rest.size >= 2) {
                                val ppid = rest[1].toIntOrNull()
                                if (ppid != null) {
                                    ppidMap[p] = ppid
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            fun collect(parent: Int) {
                for ((child, parentId) in ppidMap) {
                    if (parentId == parent && !descendants.contains(child)) {
                        descendants.add(child)
                        collect(child)
                    }
                }
            }
            collect(rootPid)
        } catch (_: Exception) {}
        return descendants
    }

    private suspend fun killServerAndChildProcesses(session: LocalPtySession, maxWaitMs: Long = 10000L) {
        val rootPid = getSessionPid(session)
        Log.d(TAG, "[ServerManager] Gracefully terminating server child processes inside session (root PID: $rootPid)...")

        try {
            // Send Ctrl+C (0x03) character to the PTY interactive terminal session
            session.write("\u0003")
        } catch (_: Exception) {}

        if (rootPid != null) {
            // Send SIGINT (2) and SIGTERM (15) to descendants (server, agy, go, etc.)
            val descendants = getDescendantPids(rootPid)
            for (child in descendants) {
                try { android.system.Os.kill(child, 2) } catch (_: Exception) {}
                try { android.system.Os.kill(child, 15) } catch (_: Exception) {}
            }

            val pollInterval = 200L
            val maxIterations = (maxWaitMs / pollInterval).toInt().coerceAtLeast(1)

            for (i in 1..maxIterations) {
                delay(pollInterval)
                val aliveChildren = getDescendantPids(rootPid).filter { isProcessAlive(it) }
                if (aliveChildren.isEmpty()) {
                    Log.d(TAG, "[ServerManager] Server child processes exited gracefully after ${i * pollInterval}ms")
                    return
                }
            }

            // Fallback escalation to SIGKILL only for remaining stuck child processes
            val remaining = getDescendantPids(rootPid).filter { isProcessAlive(it) }
            for (child in remaining) {
                try { android.system.Os.kill(child, 9) } catch (_: Exception) {}
            }
        }
    }

    private fun killLingeringServerProcesses(force: Boolean = false) {
        try {
            val procDir = File("/proc")
            val myPid = android.os.Process.myPid()
            val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()
            val interactivePids = mutableSetOf<Int>()
            for (s in LocalTerminalManager.sessions.value) {
                val pid = getSessionPid(s)
                if (pid != null) {
                    interactivePids.add(pid)
                    interactivePids.addAll(getDescendantPids(pid))
                }
            }

            for (pDir in pidDirs) {
                val p = pDir.name.toIntOrNull() ?: continue
                if (p == myPid || interactivePids.contains(p)) continue
                try {
                    val cmdlineFile = File(pDir, "cmdline")
                    if (cmdlineFile.exists()) {
                        val cmdline = cmdlineFile.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                        val isServerOrAgy = cmdline.contains("server -f") || 
                                            cmdline.contains("agy") || 
                                            cmdline.contains("server") && !cmdline.contains("com.termux") && !cmdline.contains("gemini") ||
                                            cmdline.contains("start.sh") || 
                                            cmdline.contains("./start") ||
                                            cmdline.contains("ld-linux-aarch64") && cmdline.contains("agy")
                        if (isServerOrAgy) {
                            Log.d(TAG, "Killing lingering server process: PID $p ($cmdline)")
                            val signal = if (force) 9 else 15
                            try { android.system.Os.kill(-p, signal) } catch (_: Exception) {}
                            try { android.system.Os.kill(p, signal) } catch (_: Exception) {}
                            if (force) {
                                try { android.system.Os.kill(-p, 9) } catch (_: Exception) {}
                                try { android.system.Os.kill(p, 9) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Clear terminal transcript logs upon user request
     */
    fun clearLogs() {
        val session = _serverSession.value ?: return
        try {
            session.terminalSession.emulator?.screen?.clearTranscript()
            session.terminalSession.emulator?.reset()
            session.notifyTextChanged()
        } catch (_: Exception) {}
    }

    /**
     * Immediate force kill for service notification exit (kills all background processes)
     */
    fun forceKillAll() {
        exitObserverJob?.cancel()
        exitObserverJob = null
        activeJob?.cancel()
        activeJob = null

        val session = _serverSession.value
        if (session != null) {
            val pid = getSessionPid(session)
            if (pid != null) {
                try { android.system.Os.kill(-pid, 9) } catch (_: Exception) {}
                try { android.system.Os.kill(pid, 9) } catch (_: Exception) {}
                for (child in getDescendantPids(pid)) {
                    try { android.system.Os.kill(child, 9) } catch (_: Exception) {}
                }
            }
            try {
                session.terminalSession.finishIfRunning()
                session.close()
            } catch (_: Exception) {}
        }

        killLingeringServerProcesses(force = true)
        _serverSession.value = null
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    @Synchronized
    fun autoStartOnAppLaunch(context: Context) {
        if (hasInitialAutoStarted) {
            Log.d(TAG, "[ServerManager] Initial auto-start already executed for this process, skipping autoStartOnAppLaunch")
            return
        }
        hasInitialAutoStarted = true
        startServer(context, forceRestart = false)
    }

    fun startServer(context: Context, forceRestart: Boolean = false) {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        activeJob = managerScope.launch {
            startServerInternal(context, forceRestart)
        }
    }

    private suspend fun startServerInternal(context: Context, forceRestart: Boolean) {
        val current = _status.value
        val existingSession = _serverSession.value

        if (!forceRestart && existingSession != null && !existingSession.isExited.value && (current is LocalServerStatus.Running || current is LocalServerStatus.Starting)) {
            Log.d(TAG, "[ServerManager] Server session already running, skipping start")
            return
        }

        if (!LocalEnvironmentManager.isInstalled(context)) {
            Log.d(TAG, "[ServerManager] Bootstrap not installed, skipping server start")
            return
        }

        val appContext = context.applicationContext
        val command = resolveServerCommand(appContext)
        if (command == null) {
            Log.d(TAG, "[ServerManager] No start or server executable found in home directory")
            return
        }

        _status.value = LocalServerStatus.Starting

        // If a terminal session is already alive, keep it and simply send the start command inside it!
        if (existingSession != null && !existingSession.isExited.value) {
            Log.d(TAG, "[ServerManager] Reusing active terminal session to launch: $command")
            killServerAndChildProcesses(existingSession)
            delay(250)
            killLingeringServerProcesses(force = true)
            delay(100)
            existingSession.write("\r\n\u001b[1;36m>> Starting $command\u001b[0m\r\n")
            existingSession.write("$command\n")
            _status.value = LocalServerStatus.Running()
            return
        }

        stopServerInternal()
        delay(150)

        Log.d(TAG, "[ServerManager] Spawning dedicated Termux PTY session for server: $command")

        val session = withContext(Dispatchers.Main) {
            LocalPtySession(
                id = "server-runner-pty",
                initialTitle = "Server Runner",
                context = appContext,
                isSsh = false,
                initialCommand = command,
                initialCols = 80,
                initialRows = 24
            )
        }

        _serverSession.value = session
        _status.value = LocalServerStatus.Running()

        exitObserverJob?.cancel()
        exitObserverJob = managerScope.launch {
            session.isExited.collect { isExited ->
                if (isExited) {
                    _status.value = LocalServerStatus.Stopped(exitCode = 0)
                }
            }
        }
    }

    fun stopServer() {
        activeJob?.cancel()
        activeJob = managerScope.launch {
            stopServerInternal()
        }
    }

    private suspend fun stopServerInternal() {
        exitObserverJob?.cancel()
        exitObserverJob = null

        val session = _serverSession.value
        if (session != null) {
            // Kill only the server child processes inside the session (do NOT close the terminal session or wipe history!)
            killServerAndChildProcesses(session, maxWaitMs = 10000L)
        }

        killLingeringServerProcesses(force = false)

        // Retain _serverSession.value so terminal logs and history remain fully visible in the dialog
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    fun restartServer(context: Context) {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        activeJob = managerScope.launch {
            _status.value = LocalServerStatus.Starting
            stopServerInternal()
            // Graceful cooldown to ensure ports and sockets are completely freed
            delay(300)
            killLingeringServerProcesses(force = true)
            delay(100)
            startServerInternal(context, forceRestart = true)
        }
    }
}
