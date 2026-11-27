package ai.antigem.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.AgyBridgeService
import ai.antigem.data.local.chroot.ChrootManager
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
    data class Stopped(val exitCode: Int? = null, val stopTimeMs: Long = System.currentTimeMillis()) :
        LocalServerStatus()

    data class Error(val message: String) : LocalServerStatus()
}

object LocalServerManager {

    private const val TAG = "LocalServerManager"

    private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Idle)
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    /** The SSH-managed remote server reports through the same status flow, so the existing UI needs no change. */
    internal fun publishRemoteStatus(status: LocalServerStatus) {
        _status.value = status
    }

    private val _serverSession = MutableStateFlow<LocalPtySession?>(null)
    val serverSession: StateFlow<LocalPtySession?> = _serverSession.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exitObserverJob: Job? = null
    private var activeJob: Job? = null

    // Track whether auto-start has already run during the lifetime of this app process
    private var hasInitialAutoStarted = false

    private const val PREFS_NAME = "local_server_prefs"
    private const val KEY_BRIDGE_LAST_UPDATE_TIME = "bridge_last_update_time"
    /** The command the Ubuntu chroot's bridge was last started with (a running one is reused only with the same). */
    private const val KEY_CHROOT_BRIDGE_COMMAND = "chroot_bridge_command"

    fun hasServerScript(context: Context): Boolean {
        // the chroot gets this app's Linux bridge at every start (ChrootManager.start)
        if (ChrootManager.isActive(context)) return true
        val binBridge = File(LocalEnvironmentManager.getBinDir(context), "agy_ide_bridge")
        if (binBridge.exists()) return true
        return try {
            context.assets.list("bin")?.contains(BRIDGE_ASSET) == true
        } catch (_: Exception) {
            false
        }
    }

    /** The IDE bridge is shipped xz-compressed (see compressAgyIdeBridge in app/build.gradle.kts). */
    private const val BRIDGE_ASSET = "agy_ide_bridge.xz"

    /** Opens assets/bin/[assetName], decompressing it on the fly when it is an .xz file. */
    private fun openBinAsset(context: Context, assetName: String): java.io.InputStream {
        val raw = context.assets.open("bin/$assetName")
        return if (assetName.endsWith(".xz")) org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(raw, 64 * 1024)) else raw
    }

    private fun extractAssetToBin(
        context: Context,
        binDir: File,
        assetName: String,
        targetFileName: String = assetName
    ) {
        val targetFile = File(binDir, targetFileName)
        openBinAsset(context, assetName).use { input ->
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

    /** Unpacks the IDE bridge (and the helper scripts) after an app update. Also called by TermuxRelocation: the bridge holds the fast .deb relocator its hooks run. */
    private val bridgeUnpackLock = Any()

    internal fun ensureBridgeBinary(context: Context) = synchronized(bridgeUnpackLock) { unpackBridgeIfNeeded(context) }

    private fun unpackBridgeIfNeeded(context: Context) {
        val binDir = LocalEnvironmentManager.getBinDir(context)
        val bridgeFile = File(binDir, "agy_ide_bridge")
        val backupFile = File(binDir, "backup_bootstrap")
        val backupChatsFile = File(binDir, "backup_chats")
        val restoreChatsFile = File(binDir, "restore_chats")
        val backupProjectsFile = File(binDir, "backup_projects")
        val restoreProjectsFile = File(binDir, "restore_projects")
        val gitCloneFile = File(binDir, "git_clone")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSavedUpdate = prefs.getLong(KEY_BRIDGE_LAST_UPDATE_TIME, 0L)
        val packageInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
        val currentAppUpdateTime = packageInfo?.lastUpdateTime ?: 0L

        val needsCopy =
            !bridgeFile.exists() || !backupFile.exists() || !backupChatsFile.exists() || !restoreChatsFile.exists() || !backupProjectsFile.exists() || !restoreProjectsFile.exists() || !gitCloneFile.exists() || (currentAppUpdateTime > 0L && currentAppUpdateTime != lastSavedUpdate)

        if (needsCopy) {
            try {
                if (!binDir.exists()) {
                    binDir.mkdirs()
                }
                extractAssetToBin(context, binDir, BRIDGE_ASSET, "agy_ide_bridge")
                try {
                    extractAssetToBin(context, binDir, "backup_bootstrap")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_bootstrap asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_chats")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_chats asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "restore_chats")
                } catch (e: Exception) {
                    Log.w(TAG, "restore_chats asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_projects")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_projects asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "restore_projects")
                } catch (e: Exception) {
                    Log.w(TAG, "restore_projects asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "git_clone")
                } catch (e: Exception) {
                    Log.w(TAG, "git_clone asset not extracted", e)
                }

                // Also copy agy_ide_bridge to public Downloads folder for user access
                saveBridgeToDownloads(context)

                if (currentAppUpdateTime > 0L) {
                    prefs.edit().putLong(KEY_BRIDGE_LAST_UPDATE_TIME, currentAppUpdateTime).apply()
                }
                Log.d(TAG, "[ServerManager] Successfully extracted assets to ${binDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "[ServerManager] Failed to extract assets from bin/", e)
            }
        }
    }

    /**
     * Copies agy_ide_bridge executable to the device's public Download folder.
     */
    fun saveBridgeToDownloads(context: Context): File? {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val destFile = File(downloadDir, "agy_ide_bridge")
            val binDir = LocalEnvironmentManager.getBinDir(context)
            val bridgeFile = File(binDir, "agy_ide_bridge")

            if (bridgeFile.exists() && bridgeFile.length() > 0) {
                bridgeFile.copyTo(destFile, overwrite = true)
            } else {
                openBinAsset(context, BRIDGE_ASSET).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            destFile.setExecutable(true, false)
            destFile.setReadable(true, false)
            Log.d(TAG, "[ServerManager] agy_ide_bridge saved to Downloads: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "[ServerManager] Failed to save agy_ide_bridge to Downloads", e)
            null
        }
    }

    /** The command that starts this phone's bridge (also shown, token hidden, on Settings → Troubleshoot). */
    internal fun resolveServerCommand(context: Context): String? {
        val authPrefs = AuthPreferences(context)
        // the chroot is a plain Linux system with its own agent paths (Where agents run → Agent paths); blank = where
        // each agent's own installer puts it in the chroot's home
        val isChroot = ChrootManager.isActive(context)
        val bridgeCmd = if (isChroot) {
            authPrefs.getChrootBridgeBinSync().trim().ifBlank { ChrootManager.BRIDGE_IN_CHROOT }
        } else {
            authPrefs.getAgyBridgeBinaryPathSync().trim().ifBlank { AuthPreferences.DEFAULT_BRIDGE_BINARY_PATH }
        }
        val agyCmd = if (isChroot) authPrefs.getChrootAgyBinSync().trim().ifBlank { ai.antigem.data.remote.RemoteServerManager.DEFAULT_AGY_BIN }
            else authPrefs.getAgyBinaryPathSync().trim().ifBlank { AuthPreferences.DEFAULT_AGY_BINARY_PATH }

        val token = authPrefs.getSecurityTokenSync()
        val bridgePort = try {
            java.net.URI(AuthPreferences.currentBridgeHttpUrl).port.takeIf { it > 0 } ?: ai.antigem.BuildConfig.BRIDGE_PORT
        } catch (_: Exception) {
            ai.antigem.BuildConfig.BRIDGE_PORT
        }
        val hubPort = try {
            java.net.URI(AuthPreferences.currentHubUrl).port.takeIf { it > 0 } ?: ai.antigem.BuildConfig.HUB_PORT
        } catch (_: Exception) {
            ai.antigem.BuildConfig.HUB_PORT
        }
        val tzOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

        val binParam = if (agyCmd.isNotBlank()) {
            " --bin \"$agyCmd\""
        } else {
            ""
        }
        val claudeCmd = if (isChroot) authPrefs.getChrootClaudeBinSync().trim().ifBlank { ai.antigem.data.remote.RemoteServerManager.DEFAULT_CLAUDE_BIN }
            else authPrefs.getClaudeBinaryPathSync().trim()
        val claudeBinParam = if (claudeCmd.isNotBlank() && ai.antigem.domain.model.AgentKind.CLAUDE in ai.antigem.data.agent.AgentPreferences.enabledSync(context)) {
            " --claude-bin \"$claudeCmd\""
        } else {
            ""
        }

        val openCodeCmd = if (isChroot) authPrefs.getChrootOpenCodeBinSync().trim().ifBlank { ai.antigem.data.remote.RemoteServerManager.DEFAULT_OPENCODE_BIN }
            else authPrefs.getOpenCodeBinaryPathSync().trim()
        val openCodeBinParam = if (openCodeCmd.isNotBlank() && ai.antigem.domain.model.AgentKind.OPENCODE in ai.antigem.data.agent.AgentPreferences.enabledSync(context)) {
            " --opencode-bin \"$openCodeCmd\""
        } else {
            ""
        }

        val codexCmd = if (isChroot) authPrefs.getChrootCodexBinSync().trim().ifBlank { ai.antigem.data.remote.RemoteServerManager.DEFAULT_CODEX_BIN }
            else authPrefs.getCodexBinaryPathSync().trim()
        val codexBinParam = if (codexCmd.isNotBlank() && ai.antigem.domain.model.AgentKind.CODEX in ai.antigem.data.agent.AgentPreferences.enabledSync(context)) {
            " --codex-bin \"$codexCmd\""
        } else {
            ""
        }

        // agents switched off in the app never run (no AGY hub / no Claude Code endpoints)
        val agentFlags = ai.antigem.data.agent.AgentPreferences.bridgeFlags(context)

        // --no-proxy-headers: on the phone every app reaches the bridge on loopback, so X-Forwarded-For can't be trusted
        // (it would let them get around the failure limit); an older bridge ignores the flag
        return "$bridgeCmd -f --token $token -p $bridgePort --hub-port $hubPort --tz-offset $tzOffset --no-proxy-headers$binParam$claudeBinParam$openCodeBinParam$codexBinParam$agentFlags"
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
        } catch (_: Exception) {
        }
    }

    /**
     * Immediate force kill for service notification exit (closes session and finishes processes)
     */
    fun forceKillAll() {
        if (AuthPreferences.currentRemoteMode) {
            // Leaving the app must not stop the server: only drop the SSH connection and its forwards
            ai.antigem.data.remote.RemoteServerManager.disconnect()
            return
        }
        exitObserverJob?.cancel()
        exitObserverJob = null
        activeJob?.cancel()
        activeJob = null

        val session = _serverSession.value
        if (session != null) {
            try {
                // in the chroot this session only follows the bridge's log (the bridge runs detached)
                if (!chrootBridgeMode) session.write("\u0003")
                session.terminalSession.finishIfRunning()
                session.close()
            } catch (_: Exception) {
            }
        }

        _serverSession.value = null
        chrootBridgeMode = false
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    @Synchronized
    fun resetAutoStartFlag() {
        hasInitialAutoStarted = false
    }

    @Synchronized
    fun autoStartOnAppLaunch(context: Context) {
        if (AuthPreferences.currentRemoteMode) {
            // The server is another machine managed over SSH: no local bootstrap needed
            if (hasInitialAutoStarted) return
            if (!ai.antigem.data.agent.AgentPreferences.hasChosenSync(context)) return
            hasInitialAutoStarted = true
            ai.antigem.data.remote.RemoteServerManager.autoStart(context)
            return
        }
        if (hasInitialAutoStarted) {
            Log.d(
                TAG,
                "[ServerManager] Initial auto-start already executed for this process, skipping autoStartOnAppLaunch"
            )
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
        if (!ai.antigem.data.agent.AgentPreferences.hasChosenSync(context)) {
            // the server starts with the agent flags: wait for the first-launch agent choice
            Log.d(TAG, "[ServerManager] Agents not chosen yet, deferring auto-start until the choice is made")
            return
        }
        hasInitialAutoStarted = true
        startServer(context, forceRestart = false)
    }

    fun startServer(context: Context, forceRestart: Boolean = false) {
        if (AuthPreferences.currentRemoteMode) {
            hasInitialAutoStarted = true
            ai.antigem.data.remote.RemoteServerManager.start(context, forceRestart)
            return
        }
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
        var existingSession = _serverSession.value

        if (!forceRestart && existingSession != null && !existingSession.isExited.value && (current is LocalServerStatus.Running || current is LocalServerStatus.Starting)) {
            Log.d(TAG, "[ServerManager] Server session already running, skipping start")
            return
        }

        // a restart always starts the bridge in a new session (see retireServerSession)
        if (forceRestart && existingSession != null) {
            retireServerSession(existingSession)
            existingSession = null
        }

        if (!LocalEnvironmentManager.isInstalled(context)) {
            Log.d(TAG, "[ServerManager] Bootstrap not installed, skipping server start")
            return
        }

        val appContext = context.applicationContext
        if (ChrootManager.isActive(appContext)) {
            startChrootServer(appContext, forceRestart, existingSession)
            return
        }

        ensureBridgeBinary(appContext)
        // the bridge starts Claude Code through the app's launcher (--claude-bin)
        LocalEnvironmentManager.ensureGlibcEnvironment(appContext)
        LocalEnvironmentManager.ensureClaudeWrapper(appContext)
        // `rish` (Shizuku) in the terminal, and the local server's port/key it reads
        ai.antigem.data.adb.DeviceAccess.ensureTerminalCommands(appContext)
        // after an app update the package hooks and the dpkg wrapper are refreshed here too, not only in a new terminal
        TermuxRelocation.ensurePackageHooks(appContext)
        // termux-* commands: AntiGem's dispatcher, and the termux-api package that provides the commands
        LocalEnvironmentManager.ensureTermuxApiDispatcher(appContext)
        LocalEnvironmentManager.ensureTermuxApiPackage(appContext)

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

        Log.d(
            TAG,
            "[ServerManager] Spawning dedicated persistent terminal session for server runner with initialCommand: $command"
        )

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

    /** In the Ubuntu chroot the bridge runs detached; the server session only follows its log. */
    @Volatile
    private var chrootBridgeMode = false
    private var chrootContext: Context? = null

    /**
     * The Ubuntu chroot: mounts it, puts this app's Linux bridge into it and starts the bridge there detached, so the
     * exit setting can keep it working while AntiGem is closed. A bridge still running from before (closed with
     * "Keep running") is kept when it is this app's bridge, so agents at work are not interrupted.
     * The Server Runner terminal follows the bridge's log.
     */
    private suspend fun startChrootServer(context: Context, forceRestart: Boolean, existingSession: LocalPtySession?) {
        _status.value = LocalServerStatus.Starting
        chrootContext = context
        val start = ChrootManager.start(context, withBridge = true)
        if (!start.ok) {
            Log.e(TAG, "[ServerManager] Ubuntu chroot start failed: ${start.message}")
            _status.value = LocalServerStatus.Error("Ubuntu chroot: ${start.message}")
            return
        }
        // `rish` and `android-su` in the chroot (and the port/key they read), off the start path
        managerScope.launch { runCatching { ai.antigem.data.adb.DeviceAccess.ensureChrootCommands(context) } }
        // the chroot's own ports (an install from before they existed still had the Termux defaults; set ones stay)
        AuthPreferences(context).applyLocalRuntimeUrls(chroot = true)
        val healthUrl = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/health"
        val command = resolveServerCommand(context)
        if (command == null) {
            _status.value = LocalServerStatus.Error("No bridge command")
            return
        }
        // the running bridge is kept only when it is this app's binary started with the same command (token, ports,
        // agents): a changed setting must reach the bridge
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val reuse = !forceRestart && !start.bridgeUpdated && start.bridgePid != null &&
            prefs.getString(KEY_CHROOT_BRIDGE_COMMAND, null) == command && bridgeHealthy(healthUrl)
        if (!reuse) {
            if (start.bridgePid != null) {
                // let it stop its agents cleanly; bridge-start ends whatever is left
                requestBridgeShutdown()
                withTimeoutOrNull(5_000L) { while (bridgeStillUp(healthUrl)) delay(250) }
            }
            val r = ChrootManager.startBridge(context, command)
            if (!r.ok) {
                Log.e(TAG, "[ServerManager] Ubuntu chroot bridge start failed: ${r.message()}")
                _status.value = LocalServerStatus.Error("Ubuntu chroot bridge: ${r.message()}")
                return
            }
            prefs.edit().putString(KEY_CHROOT_BRIDGE_COMMAND, command).apply()
        } else {
            Log.d(TAG, "[ServerManager] Reusing the bridge already running in the Ubuntu chroot (pid ${start.bridgePid})")
        }
        chrootBridgeMode = true
        existingSession?.let { runCatching { it.close() } }
        val session = withContext(Dispatchers.Main) {
            LocalPtySession(
                id = "server-runner-pty",
                initialTitle = "Server Runner",
                context = context,
                isSsh = false,
                initialCols = 80,
                initialRows = 24,
                rawShellArgs = ChrootManager.bridgeLogArgs(context)
            )
        }
        _serverSession.value = session
        _status.value = LocalServerStatus.Running()
        // the status follows the bridge (closing the log view does not stop it)
        exitObserverJob?.cancel()
        exitObserverJob = managerScope.launch {
            delay(15_000L)
            var misses = 0
            while (isActive && chrootBridgeMode) {
                misses = if (bridgeStillUp(healthUrl)) 0 else misses + 1
                if (misses >= 3) {
                    _status.value = LocalServerStatus.Stopped(exitCode = null)
                    break
                }
                delay(10_000L)
            }
        }
    }

    /**
     * The Ubuntu chroot was stopped on its page (its bridge went with it): the server shows stopped at once, without
     * the shutdown request and waits of a normal stop.
     */
    fun onChrootStopped() {
        if (!chrootBridgeMode) return
        exitObserverJob?.cancel()
        exitObserverJob = null
        AgyBridgeService.instance.notifyLocalStopped()
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    private suspend fun stopChrootServer() {
        exitObserverJob?.cancel()
        exitObserverJob = null
        val healthUrl = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/health"
        requestBridgeShutdown()
        withTimeoutOrNull(5_000L) { while (bridgeStillUp(healthUrl)) delay(250) }
        chrootContext?.let { ChrootManager.stopBridge(it) }
        AgyBridgeService.instance.notifyLocalStopped()
        // the log session stays, so its last lines remain readable
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    private suspend fun requestBridgeShutdown() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/shutdown")
                .post("{}".toRequestBody(null))
                .build()
            AgyBridgeService.instance.fastClient.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun bridgeHealthy(healthUrl: String): Boolean = try {
        AgyBridgeService.instance.fastClient.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /**
     * A restart starts the bridge in a new terminal session instead of typing the command into the old one. The old
     * session can still be running the bridge while it stops (it drops the app's connection first, then stops the
     * agents and the hub), and the Ctrl+C sent to stop it also ends zsh's `-c` line, so its `exec login` never ran:
     * the session closed and the typed start command was lost (the server stopped and did not come back).
     * Waits until the old bridge is gone (its session ended or its port refuses connections, 15 s at most), then
     * closes that session.
     */
    private suspend fun retireServerSession(session: LocalPtySession) {
        _status.value = LocalServerStatus.Starting
        // its exit must not mark the new server stopped
        exitObserverJob?.cancel()
        exitObserverJob = null
        val healthUrl = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/health"
        withTimeoutOrNull(15_000L) {
            while (!session.isExited.value && bridgeStillUp(healthUrl)) delay(250)
        }
        if (!session.isExited.value) session.close()
    }

    /** False once the bridge's port refuses connections; a slow answer while it stops still counts as up. */
    private fun bridgeStillUp(healthUrl: String): Boolean = try {
        AgyBridgeService.instance.fastClient.newCall(Request.Builder().url(healthUrl).get().build()).execute().close()
        true
    } catch (_: java.net.ConnectException) {
        false
    } catch (_: Exception) {
        true
    }

    fun stopServer() {
        if (AuthPreferences.currentRemoteMode) {
            ai.antigem.data.remote.RemoteServerManager.stop()
            return
        }
        activeJob?.cancel()
        activeJob = managerScope.launch {
            stopServerInternal()
        }
    }

    private suspend fun stopServerInternal() {
        _status.value = LocalServerStatus.Stopping
        if (chrootBridgeMode) {
            stopChrootServer()
            return
        }

        val bridgeUrl = AuthPreferences.currentBridgeHttpUrl
        val session = _serverSession.value

        // 1. Send graceful shutdown HTTP request to the Go IDE bridge
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$bridgeUrl/api/shutdown")
                    .post("{}".toRequestBody(null))
                    .build()
                AgyBridgeService.instance.fastClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }

        // 2. Also send Ctrl+C to persistent PTY terminal session
        if (session != null && !session.isExited.value) {
            try {
                session.write("\u0003")
            } catch (_: Exception) {
            }
        }

        // 3. Send pkill to terminate any child tree processes (agy daemon, child node processes)
        if (session != null && !session.isExited.value) {
            try {
                session.write("pkill -f gemini-server; pkill -f 'server -f'; pkill -f 'agy '\n")
            } catch (_: Exception) {
            }
        }

        // 4. Wait for SystemConnectionState to transition to Offline
        withTimeoutOrNull(2500L) {
            while (AgyBridgeService.instance.systemConnectionState.value !is ai.antigem.data.remote.SystemConnectionState.Offline) {
                delay(150)
            }
        }

        AgyBridgeService.instance.notifyLocalStopped()
        // Retain _serverSession.value so terminal logs and history remain fully visible in the dialog
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    /** Restarts the server and waits until it is starting again (agent selection changed). */
    suspend fun restartServerAndWait(context: Context) {
        if (AuthPreferences.currentRemoteMode) {
            hasInitialAutoStarted = true
            ai.antigem.data.remote.RemoteServerManager.restartAndWait(context)
            return
        }
        hasInitialAutoStarted = true
        activeJob?.cancel()
        _status.value = LocalServerStatus.Starting
        stopServerInternal()
        delay(200)
        startServerInternal(context, forceRestart = true)
    }

    /**
     * Stops the server and ends its terminal session for good (the app restarts next, e.g. after the agent
     * selection changed): nothing of the old server may keep running.
     */
    suspend fun stopServerCompletely() {
        if (AuthPreferences.currentRemoteMode) {
            hasInitialAutoStarted = true
            ai.antigem.data.remote.RemoteServerManager.stopCompletely()
            return
        }
        hasInitialAutoStarted = true
        activeJob?.cancel()
        stopServerInternal()
        // let the bridge finish stopping its children (Claude processes, the AGY hub) before the session is closed
        withTimeoutOrNull(5_000L) {
            while (AgyBridgeService.instance.systemConnectionState.value !is ai.antigem.data.remote.SystemConnectionState.Offline) {
                delay(150)
            }
        }
        forceKillAll()
    }

    fun restartServer(context: Context) {
        if (AuthPreferences.currentRemoteMode) {
            hasInitialAutoStarted = true
            ai.antigem.data.remote.RemoteServerManager.restart(context)
            return
        }
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
