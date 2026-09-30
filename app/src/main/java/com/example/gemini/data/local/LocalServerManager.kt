package com.example.gemini.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

sealed class LocalServerStatus {
    object Idle : LocalServerStatus()
    object Starting : LocalServerStatus()
    data class Running(val startTimeMs: Long = System.currentTimeMillis()) : LocalServerStatus()
    object Stopping : LocalServerStatus()
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

    private const val PREFS_NAME = "local_server_prefs"
    private const val KEY_BRIDGE_LAST_UPDATE_TIME = "bridge_last_update_time"

    fun hasServerScript(context: Context): Boolean {
        val binBridge = File(LocalEnvironmentManager.getBinDir(context), "agy_ide_bridge")
        if (binBridge.exists()) return true
        return try {
            context.assets.list("bin")?.contains("agy_ide_bridge") == true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractAssetToBin(context: Context, binDir: File, assetName: String, targetFileName: String = assetName) {
        val targetFile = File(binDir, targetFileName)
        context.assets.open("bin/$assetName").use { input ->
            val tempFile = File(binDir, "$targetFileName.tmp")
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
            tempFile.setExecutable(true, false)
            tempFile.setReadable(true, false)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
            targetFile.setExecutable(true, false)
            targetFile.setReadable(true, false)
        }
    }

    private fun ensureBridgeBinary(context: Context) {
        val binDir = LocalEnvironmentManager.getBinDir(context)
        val bridgeFile = File(binDir, "agy_ide_bridge")
        val backupFile = File(binDir, "backup_bootstrap")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSavedUpdate = prefs.getLong(KEY_BRIDGE_LAST_UPDATE_TIME, 0L)
        val packageInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
        val currentAppUpdateTime = packageInfo?.lastUpdateTime ?: 0L

        val needsCopy = !bridgeFile.exists() || !backupFile.exists() || (currentAppUpdateTime > 0L && currentAppUpdateTime != lastSavedUpdate)

        if (needsCopy) {
            try {
                if (!binDir.exists()) {
                    binDir.mkdirs()
                }
                extractAssetToBin(context, binDir, "agy_ide_bridge")
                try {
                    extractAssetToBin(context, binDir, "backup_bootstrap")
                } catch (e: Exception) {
                    Log.w(TAG, "[ServerManager] Optional backup_bootstrap asset not found or failed to extract", e)
                }

                if (currentAppUpdateTime > 0L) {
                    prefs.edit().putLong(KEY_BRIDGE_LAST_UPDATE_TIME, currentAppUpdateTime).apply()
                }
                Log.d(TAG, "[ServerManager] Successfully extracted assets to ${binDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "[ServerManager] Failed to extract assets from bin/", e)
            }
        }
    }

    private fun resolveServerCommand(context: Context): String? {
        val binBridge = File(LocalEnvironmentManager.getBinDir(context), "agy_ide_bridge")
        if (binBridge.exists()) {
            try {
                binBridge.setExecutable(true, false)
                binBridge.setReadable(true, false)
            } catch (_: Exception) {}
            return "agy_ide_bridge -f"
        }
        return null
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
     * Immediate force kill for service notification exit (closes session and finishes processes)
     */
    fun forceKillAll() {
        exitObserverJob?.cancel()
        exitObserverJob = null
        activeJob?.cancel()
        activeJob = null

        val session = _serverSession.value
        if (session != null) {
            try {
                session.write("\u0003")
                session.terminalSession.finishIfRunning()
                session.close()
            } catch (_: Exception) {}
        }

        _serverSession.value = null
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    @Synchronized
    fun resetAutoStartFlag() {
        hasInitialAutoStarted = false
    }

    @Synchronized
    fun autoStartOnAppLaunch(context: Context) {
        if (hasInitialAutoStarted) {
            Log.d(TAG, "[ServerManager] Initial auto-start already executed for this process, skipping autoStartOnAppLaunch")
            return
        }
        if (!LocalEnvironmentManager.isInstalled(context)) {
            Log.d(TAG, "[ServerManager] Bootstrap not installed yet, deferring auto-start until installation completes")
            return
        }
        if (!hasServerScript(context)) {
            Log.d(TAG, "[ServerManager] No server executable found in home directory, skipping autoStartOnAppLaunch")
            return
        }
        hasInitialAutoStarted = true
        startServer(context, forceRestart = false)
    }

    fun startServer(context: Context, forceRestart: Boolean = false) {
        if (hasServerScript(context)) {
            hasInitialAutoStarted = true
        }
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
        ensureBridgeBinary(appContext)

        val command = resolveServerCommand(appContext)
        if (command == null) {
            Log.d(TAG, "[ServerManager] No start or server executable found in home directory")
            return
        }

        _status.value = LocalServerStatus.Starting

        // If a persistent terminal session is already alive, reuse it and execute command inside it
        if (existingSession != null && !existingSession.isExited.value) {
            Log.d(TAG, "[ServerManager] Reusing active persistent terminal session to launch: $command")
            existingSession.write("\u0003")
            delay(150)
            existingSession.write("cd \$HOME && $command\n")
            _status.value = LocalServerStatus.Running()
            return
        }

        Log.d(TAG, "[ServerManager] Spawning dedicated persistent terminal session for server runner with initialCommand: $command")

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
        _status.value = LocalServerStatus.Stopping

        val bridgeUrl = AuthPreferences.currentBridgeHttpUrl
        val session = _serverSession.value

        // 1. Send graceful shutdown HTTP request to the Go IDE bridge
        withContext(Dispatchers.IO) {
            try {
                val fastClient = OkHttpClient.Builder()
                    .connectTimeout(800, TimeUnit.MILLISECONDS)
                    .writeTimeout(800, TimeUnit.MILLISECONDS)
                    .readTimeout(800, TimeUnit.MILLISECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$bridgeUrl/api/shutdown")
                    .post("{}".toRequestBody(null))
                    .build()
                fastClient.newCall(req).execute().close()
            } catch (_: Exception) {}
        }

        // 2. Also send Ctrl+C to persistent PTY terminal session
        if (session != null && !session.isExited.value) {
            try {
                session.write("\u0003")
            } catch (_: Exception) {}
        }

        // 3. Send pkill to terminate any child tree processes (agy daemon, child node processes)
        if (session != null && !session.isExited.value) {
            try {
                session.write("pkill -f gemini-server; pkill -f 'server -f'; pkill -f 'agy '\n")
            } catch (_: Exception) {}
        }

        // 4. Wait for SystemConnectionState to transition to Offline
        withTimeoutOrNull(2500L) {
            while (AgyBridgeService.instance.systemConnectionState.value !is com.example.gemini.data.remote.SystemConnectionState.Offline) {
                delay(150)
            }
        }

        AgyBridgeService.instance.notifyLocalStopped()
        // Retain _serverSession.value so terminal logs and history remain fully visible in the dialog
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    fun restartServer(context: Context) {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        activeJob = managerScope.launch {
            _status.value = LocalServerStatus.Starting
            stopServerInternal()
            delay(200)
            startServerInternal(context, forceRestart = true)
        }
    }
}
