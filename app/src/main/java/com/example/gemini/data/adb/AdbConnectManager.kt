package com.example.gemini.data.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.gemini.R
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Connects the terminal's adb to this device over Wireless debugging, the way Shizuku does:
 * the pairing and connect ports are found over mDNS (adbd advertises them), the pairing code
 * is typed into a notification so the Settings pairing dialog stays open, and the connection
 * is re-made by itself whenever Wireless debugging turns back on (its port changes every time).
 */
object AdbConnectManager {
    private const val TAG = "AdbConnect"
    private const val PREFS = "adb_connect"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val CHANNEL_ID = "adb_pairing"
    const val PAIRING_NOTIFICATION_ID = 4310
    private const val RESULT_NOTIFICATION_ID = 4311
    const val KEY_PAIRING_CODE = "pairing_code"
    const val DEFAULT_HOST = "localhost"

    sealed interface Pairing {
        data object Idle : Pairing
        data object Searching : Pairing
        data class Ready(val port: Int) : Pairing
        data class InProgress(val port: Int) : Pairing
        data object Paired : Pairing
        data class Failed(val message: String, val port: Int?) : Pairing
    }

    data class Device(val serial: String, val state: String)

    data class State(
        val adbInstalled: Boolean? = null,
        val adbVersion: String? = null,
        /** Set when adb is installed but won't run (e.g. a library it needs is out of date); [adbInstalled] is then false. */
        val adbBroken: String? = null,
        val refreshing: Boolean = false,
        val installing: Boolean = false,
        val installLog: List<String> = emptyList(),
        val installError: String? = null,
        val devices: List<Device> = emptyList(),
        val busy: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val pairing: Pairing = Pairing.Idle,
        val host: String = DEFAULT_HOST,
        val autoReconnect: Boolean = true,
        val pairedOnce: Boolean = false
    ) {
        val connectedDevice: Device? get() = devices.firstOrNull { it.state == "device" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var pairingJob: Job? = null
    private var autoConnectJob: Job? = null
    private var installJob: Job? = null
    @Volatile private var pairingServiceRunning = false

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _state.update {
            it.copy(
                host = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST,
                autoReconnect = prefs.getBoolean("auto_reconnect", true),
                pairedOnce = prefs.getBoolean("paired_once", false)
            )
        }
        // adbd picks a new port each time Wireless debugging turns on (also after Wi-Fi drops)
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(ADB_WIFI_ENABLED), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (isWirelessDebuggingOn(appContext)) maybeAutoConnect()
                    }
                }
            )
        }.onFailure { Log.w(TAG, "Cannot watch Wireless debugging: ${it.message}") }
        if (isWirelessDebuggingOn(appContext)) maybeAutoConnect()
    }

    fun isDeveloperOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun isWirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    fun isWirelessDebuggingSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Opens Developer options scrolled to Wireless debugging, or About phone when Developer options are off. */
    fun openWirelessDebuggingSettings(context: Context) {
        val intent = if (isDeveloperOptionsOn(context)) {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
            }
        } else {
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun setHost(host: String) {
        val value = host.trim().ifBlank { DEFAULT_HOST }
        prefs.edit().putString("host", value).apply()
        _state.update { it.copy(host = value) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reconnect", enabled).apply()
        _state.update { it.copy(autoReconnect = enabled) }
        if (enabled && isWirelessDebuggingOn(appContext)) maybeAutoConnect()
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) =
        _state.update { it.copy(message = message, isError = isError) }

    // ---------------- adb ----------------

    private suspend fun shell(command: String, timeoutSeconds: Long = 30, onLine: ((String) -> Unit)? = null) =
        LocalEnvironmentManager.executeCommand(
            "( $command ) < /dev/null", appContext, timeoutSeconds = timeoutSeconds,
            customEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), onOutputLine = onLine
        )

    // `timeout` keeps a stuck adb from blocking the output reader forever
    private suspend fun adb(args: String, seconds: Int = 20) = shell("timeout $seconds adb $args", seconds + 5L)

    /** [quiet] skips the spinner, for background re-checks. */
    fun refresh(quiet: Boolean = false) = scope.launch {
        if (!quiet) _state.update { it.copy(refreshing = true) }
        try { refreshNow() } finally { _state.update { it.copy(refreshing = false) } }
    }

    private suspend fun refreshNow() {
        // Run adb itself: a present binary can still fail to start ("CANNOT LINK EXECUTABLE")
        val found = shell("command -v adb >/dev/null 2>&1 || exit 127; adb version", 15)
        val output = found.output.trim()
        val works = found.exitCode == 0 && output.startsWith("Android Debug Bridge")
        val broken = if (!works && found.exitCode != 127) output.ifBlank { "adb exited with code ${found.exitCode}" }.take(600) else null
        _state.update { it.copy(adbInstalled = works, adbBroken = broken, adbVersion = output.lineSequence().firstOrNull()?.takeIf { works }) }
        if (!works) {
            _state.update { it.copy(devices = emptyList()) }
            return
        }
        // The server forks and lives on; its own stdio goes to /dev/null so it never holds our pipe open
        shell("adb start-server >/dev/null 2>&1; true", 20)
        _state.update { it.copy(devices = listDevices()) }
    }

    private suspend fun listDevices(): List<Device> =
        adb("devices").output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line -> line.split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { Device(it[0], it[1]) } }
            .toList()

    fun installAdb() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.update { it.copy(installing = true, installLog = emptyList(), installError = null) }
            // Termux is rolling-release: installing only android-tools can leave older libraries (libprotobuf…) that the new
            // adb can't link against, so refresh the package lists and bring its direct dependencies up to date too
            val keepConfigs = "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
            val pkg = if (LocalEnvironmentManager.isTermuxPackage(appContext)) "android-tools" else "adb"
            val command = "apt-get update && apt-get install -y $keepConfigs $pkg && " +
                "apt-get install -y $keepConfigs ${'$'}(apt-cache depends $pkg | awk '/Depends:/ && ${'$'}2 !~ /^</ {print ${'$'}2}')"
            appendInstallLog("$ $command")
            val result = shell(command, 900) { line -> if (line.isNotBlank()) appendInstallLog(line) }
            refreshNow()
            val ok = _state.value.adbInstalled == true
            _state.update {
                it.copy(installing = false, installError = if (ok) null else "Install failed (exit ${result.exitCode}). Check the log above, or run \"pkg upgrade\" in the terminal and try again.")
            }
            if (ok) appendInstallLog("✓ ${_state.value.adbVersion ?: "adb installed"}")
        }
    }

    private fun appendInstallLog(line: String) =
        _state.update { it.copy(installLog = (it.installLog + line).takeLast(400)) }

    /** Connects to [port], or finds this device's Wireless debugging port over mDNS when [port] is null. */
    fun connect(port: Int?) = scope.launch {
        if (_state.value.busy != null) return@launch
        connectNow(port, quiet = false)
    }

    private suspend fun connectNow(port: Int?, quiet: Boolean): Boolean {
        val host = _state.value.host
        try {
            val target = port ?: run {
                _state.update { it.copy(busy = "Looking for the Wireless debugging port…") }
                withTimeoutOrNull(25_000) { localServicePorts(CONNECT_SERVICE).first() }
            }
            if (target == null) {
                if (!quiet) report("Couldn't find the Wireless debugging port. Make sure Wireless debugging is on, or enter the port shown under \"IP address & Port\".", true)
                return false
            }
            _state.update { it.copy(busy = "Connecting to $host:$target…") }
            // Old ports from an earlier Wireless debugging session show up as offline; drop them first
            listDevices().filter { it.state == "offline" }.forEach { adb("disconnect ${it.serial}", 10) }
            val out = adb("connect $host:$target").output.trim()
            val ok = out.contains("connected to") && !out.contains("failed") && !out.contains("cannot")
            if (ok) report("Connected to $host:$target") else if (!quiet) report(out.ifBlank { "adb connect failed" }, true)
            _state.update { it.copy(devices = listDevices()) }
            return ok
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    fun disconnect(serial: String) = scope.launch {
        adb("disconnect $serial", 10)
        _state.update { it.copy(devices = listDevices()) }
        report("Disconnected $serial")
    }

    // ---------------- for other features (android_automation_mcp) ----------------

    /** The connected device, connecting first when it was paired and Wireless debugging is on. Null when adb isn't usable. */
    suspend fun ensureConnected(): Device? {
        if (!::appContext.isInitialized) return null
        refreshNow()
        _state.value.connectedDevice?.let { return it }
        if (_state.value.adbInstalled != true || !_state.value.pairedOnce || !isWirelessDebuggingOn(appContext)) return null
        connectNow(null, quiet = true)
        return _state.value.connectedDevice
    }

    /** Runs `adb <args>` against [device] (args are passed to bash as-is). */
    suspend fun runAdb(device: Device, args: String, seconds: Int = 30) = adb("-s ${device.serial} $args", seconds)

    /** Runs [command] in `adb shell` on [device], as the shell user. */
    suspend fun deviceShell(device: Device, command: String, seconds: Int = 30) =
        adb("-s ${device.serial} shell ${shellQuote(command)}", seconds)

    fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun maybeAutoConnect() {
        val s = _state.value
        if (!s.autoReconnect || !s.pairedOnce || autoConnectJob?.isActive == true || pairingJob?.isActive == true) return
        autoConnectJob = scope.launch {
            delay(1500) // let adbd come up and advertise itself
            refreshNow()
            if (_state.value.adbInstalled != true || _state.value.connectedDevice != null) return@launch
            Log.d(TAG, "Wireless debugging is on, reconnecting")
            connectNow(null, quiet = true)
        }
    }

    // ---------------- pairing ----------------

    fun startPairing(context: Context) {
        if (pairingJob?.isActive == true) return
        _state.update { it.copy(pairing = Pairing.Searching, message = null) }
        pairingServiceRunning = true
        ContextCompat.startForegroundService(context, Intent(context, AdbPairingService::class.java))
        pairingJob = scope.launch {
            launch {
                delay(10 * 60_000L)
                if (_state.value.pairing !is Pairing.InProgress) stopPairing(context)
            }
            // The pairing dialog advertises a fresh port each time it opens; always use the newest
            runCatching {
                localServicePorts(PAIRING_SERVICE).collect { port ->
                    if (_state.value.pairing !is Pairing.InProgress) setPairing(Pairing.Ready(port))
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) setPairing(Pairing.Failed(it.message ?: "mDNS discovery failed", null)) }
        }
    }

    fun stopPairing(context: Context) {
        pairingServiceRunning = false
        pairingJob?.cancel()
        pairingJob = null
        _state.update { if (it.pairing is Pairing.Paired) it else it.copy(pairing = Pairing.Idle) }
        context.stopService(Intent(context, AdbPairingService::class.java))
    }

    /** Takes what was typed in the notification: the 6-digit code, or "port code" when no port was found. */
    fun submitPairingCode(context: Context, input: String) {
        val numbers = Regex("\\d+").findAll(input).map { it.value }.toList()
        val current = _state.value.pairing
        val knownPort = (current as? Pairing.Ready)?.port ?: (current as? Pairing.Failed)?.port
        val (port, code) = when {
            numbers.size >= 2 -> numbers[0].toIntOrNull() to numbers[1]
            numbers.size == 1 -> knownPort to numbers[0]
            else -> knownPort to ""
        }
        if (code.length != 6) {
            setPairing(Pairing.Failed("The pairing code has 6 digits.", knownPort))
            return
        }
        if (port == null) {
            setPairing(Pairing.Failed("Pairing port not found yet. Tap \"Pair device with pairing code\" in Wireless debugging, or type the port and code.", null))
            return
        }
        pair(context, port, code)
    }

    /** Pairs with a port and code typed in the app (the manual fallback). */
    fun pairManually(context: Context, port: Int, code: String) {
        if (_state.value.pairing is Pairing.InProgress) return
        pair(context, port, code.trim())
    }

    private fun pair(context: Context, port: Int, code: String) = scope.launch {
        setPairing(Pairing.InProgress(port))
        val host = _state.value.host
        val out = adb("pair $host:$port $code", 25).output.trim()
        if (!out.contains("Successfully paired")) {
            val reason = out.lineSequence().lastOrNull { it.isNotBlank() }?.removePrefix("error: ") ?: "No response from adb"
            setPairing(Pairing.Failed("$reason. Check the code and try again.", port))
            return@launch
        }
        prefs.edit().putBoolean("paired_once", true).apply()
        _state.update { it.copy(pairedOnce = true) }
        setPairing(Pairing.Paired)
        pairingJob?.cancel()
        pairingJob = null
        val connected = connectNow(null, quiet = true)
        val device = _state.value.connectedDevice?.serial
        postResult(
            if (connected) "ADB connected" else "ADB paired",
            if (connected) "Connected to $device. Use adb in the terminal." else "Paired. Open AntiGem → Settings → Utilities → ADB Connect to connect."
        )
        if (!connected) report("Paired, but couldn't find the Wireless debugging port yet. Tap Connect.", true)
        if (pairingServiceRunning) stopPairing(context)
    }

    private fun setPairing(pairing: Pairing) {
        _state.update { it.copy(pairing = pairing) }
        if (pairingServiceRunning) {
            runCatching { notificationManager().notify(PAIRING_NOTIFICATION_ID, pairingNotification(appContext)) }
        }
    }

    // ---------------- mDNS ----------------

    /** Ports of [type] services advertised by this device itself (not other phones on the Wi-Fi). */
    private fun localServicePorts(type: String): Flow<Int> = callbackFlow {
        val nsd = appContext.getSystemService(NsdManager::class.java)
        val resolving = Mutex() // older NsdManager rejects concurrent resolves
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val resolved = resolving.withLock { resolve(nsd, info) } ?: return@launch
                    @Suppress("DEPRECATION")
                    if (isLocalAddress(resolved.host)) trySend(resolved.port)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IllegalStateException("mDNS discovery failed ($errorCode)")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
            })
        }
    }

    private fun isLocalAddress(address: InetAddress?): Boolean =
        address != null && (address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false))

    // ---------------- notifications ----------------

    private fun notificationManager() = appContext.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Enter the Wireless debugging pairing code without leaving Settings"
                setShowBadge(false)
            }
        )
    }

    fun pairingNotification(context: Context): android.app.Notification {
        ensureChannel(context)
        val pairing = _state.value.pairing
        val (title, text) = when (pairing) {
            is Pairing.Ready -> "Pairing service found" to "Enter the 6-digit Wi-Fi pairing code shown in Wireless debugging."
            is Pairing.InProgress -> "Pairing…" to "Pairing with ${_state.value.host}:${pairing.port}"
            is Pairing.Failed -> "Pairing failed" to pairing.message
            is Pairing.Paired -> "Paired" to "Connecting…"
            else -> "Waiting for pairing code" to "Open Wireless debugging → Pair device with pairing code. Then enter the code here."
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(pairing is Pairing.Searching || pairing is Pairing.InProgress || pairing is Pairing.Paired)
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop", servicePendingIntent(context, AdbPairingService.ACTION_STOP, 1, mutable = false))
        if (pairing is Pairing.InProgress || pairing is Pairing.Paired) {
            builder.setProgress(0, 0, true)
        } else {
            val hasPort = pairing is Pairing.Ready || (pairing as? Pairing.Failed)?.port != null
            val label = if (hasPort) "Enter pairing code" else "Enter port and code"
            val input = RemoteInput.Builder(KEY_PAIRING_CODE)
                .setLabel(if (hasPort) "6-digit pairing code" else "Port and code, e.g. 37123 482916")
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, label, servicePendingIntent(context, AdbPairingService.ACTION_CODE, 2, mutable = true))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(context: Context, action: String, requestCode: Int, mutable: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or when {
            mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            mutable -> 0
            else -> PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getService(context, requestCode, Intent(context, AdbPairingService::class.java).setAction(action), flags)
    }

    private fun postResult(title: String, text: String) = runCatching {
        ensureChannel(appContext)
        notificationManager().notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setTimeoutAfter(30_000)
                .build()
        )
    }
}
