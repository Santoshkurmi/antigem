package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.preferences.AuthPreferences
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Lets the `adb` that runs on the server reach THIS phone's adb daemon, through the SSH connection the app already
 * keeps to the server (a reverse tunnel):
 *
 *   server 127.0.0.1:<connect port>  ->  phone adbd (fixed `adb tcpip` port 5555, or the Wireless debugging port)
 *   server 127.0.0.1:<pairing port>  ->  the phone's "Pair device with pairing code" dialog (only while pairing)
 *
 * The server then runs `adb connect 127.0.0.1:<connect port>`. Wireless debugging changes its port every time it turns
 * on; the tunnel follows it.
 */
object AdbTunnel {

    private const val TAG = "AdbTunnel"
    private const val TCPIP_PORT = 5555

    data class State(
        val enabled: Boolean = false,
        val serverPort: Int = 15555,
        val serverPairPort: Int = 15556,
        /** The adb port found on the phone, and how: "tcpip" or "wireless". */
        val phonePort: Int? = null,
        val phoneMode: String = "",
        /** Phone pairing port currently forwarded to [serverPairPort], while pairing. */
        val pairingForwardedTo: Int? = null,
        val message: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private var lastCheck = 0L
    private var forwardedPort: Int? = null
    private var forwardedPair: Int? = null
    private var cachedWirelessPort: Int? = null
    /** A phone adb port the user typed in Utilities > ADB Connect; tried before the automatic search. */
    @Volatile private var manualPhonePort: Int? = null

    /** A new SSH session has none of the forwards: forget what was forwarded so the next sync re-creates them. */
    fun onNewSession() {
        forwardedPort = null
        forwardedPair = null
        lastCheck = 0L
        _state.value = _state.value.copy(pairingForwardedTo = null)
    }

    fun load(context: Context) {
        val prefs = AuthPreferences(context)
        val (c, p) = prefs.getAdbTunnelPortsSync()
        _state.value = _state.value.copy(enabled = prefs.isAdbTunnelEnabledSync(), serverPort = c, serverPairPort = p)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = AuthPreferences(context)
        val (c, p) = prefs.getAdbTunnelPortsSync()
        prefs.saveAdbTunnelSync(enabled, c, p)
        _state.value = _state.value.copy(enabled = enabled, serverPort = c, serverPairPort = p, message = if (enabled) "Looking for the phone's adb…" else "")
        lastCheck = 0L
        // applied by the next sync (the SSH supervisor runs it every few seconds)
    }

    private fun reachable(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (_: Exception) {
        false
    }

    /** `adb tcpip <port>` sets these system properties; any app may read them with getprop. */
    private fun configuredTcpipPorts(): List<Int> {
        val ports = mutableListOf<Int>()
        for (prop in listOf("service.adb.tcp.port", "persist.adb.tcp.port")) {
            val value = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                p.inputStream.bufferedReader().use { it.readText() }.trim().also { p.waitFor() }
            }.getOrDefault("")
            value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { ports += it }
        }
        ports += TCPIP_PORT
        return ports.distinct()
    }

    private suspend fun findPhoneAdb(context: Context): Pair<Int, String>? {
        manualPhonePort?.takeIf { reachable(it) }?.let { return it to "wireless" }
        configuredTcpipPorts().firstOrNull { reachable(it) }?.let { return it to "tcpip" }
        cachedWirelessPort?.takeIf { reachable(it) }?.let { return it to "wireless" }
        cachedWirelessPort = null
        if (!AdbConnectManager.isWirelessDebuggingOn(context)) return null
        val found = withTimeoutOrNull(6_000) { AdbConnectManager.connectPorts().first() } ?: return null
        cachedWirelessPort = found
        return found to "wireless"
    }

    /** Called every few seconds while the SSH session is up (and right after the user changes something). */
    suspend fun sync(context: Context, s: Session) = lock.withLock {
        val prefs = AuthPreferences(context)
        val enabled = prefs.isAdbTunnelEnabledSync()
        val (serverPort, pairPort) = prefs.getAdbTunnelPortsSync()

        if (!enabled) {
            if (forwardedPort != null) {
                runCatching { s.delPortForwardingR("127.0.0.1", serverPort) }
                runCatching { RemoteServerManager.exec(s, "timeout 8 adb disconnect 127.0.0.1:$serverPort >/dev/null 2>&1; true", 12_000) }
                forwardedPort = null
            }
            if (forwardedPair != null) {
                runCatching { s.delPortForwardingR("127.0.0.1", pairPort) }
                forwardedPair = null
            }
            _state.value = _state.value.copy(enabled = false, serverPort = serverPort, serverPairPort = pairPort, phonePort = null, phoneMode = "", pairingForwardedTo = null, message = "")
            return@withLock
        }

        val now = System.currentTimeMillis()
        val fwd = forwardedPort
        if (fwd != null && now - lastCheck < 8_000) return@withLock
        lastCheck = now

        val found = findPhoneAdb(context)
        if (found == null) {
            if (forwardedPort != null) {
                runCatching { s.delPortForwardingR("127.0.0.1", serverPort) }
                forwardedPort = null
            }
            _state.value = _state.value.copy(
                enabled = true, serverPort = serverPort, serverPairPort = pairPort, phonePort = null, phoneMode = "",
                message = "No adb found on the phone. Turn on Wireless debugging, or run `adb tcpip 5555` once (root: setprop service.adb.tcp.port 5555; stop adbd; start adbd)."
            )
            return@withLock
        }

        val (port, mode) = found
        if (port != forwardedPort) {
            runCatching { s.delPortForwardingR("127.0.0.1", serverPort) }
            try {
                s.setPortForwardingR("127.0.0.1", serverPort, "127.0.0.1", port)
                forwardedPort = port
            } catch (e: Exception) {
                Log.w(TAG, "Reverse forward $serverPort failed: ${e.message}")
                _state.value = _state.value.copy(enabled = true, message = "Could not open port $serverPort on the server: ${e.message?.take(80)}")
                return@withLock
            }
            // connect the server's adb right away (needs adb installed there, and the key paired once for Wireless debugging)
            val msg = describeServerAdb(serverPort, mode, ensureServerAdb(s, serverPort))
            _state.value = _state.value.copy(enabled = true, serverPort = serverPort, serverPairPort = pairPort, phonePort = port, phoneMode = mode, message = msg)
        } else {
            // same port as before: the server's adb may still have lost the device (adb server restarted, phone toggled
            // Wireless debugging, key not authorized), so check what `adb devices` really says there and reconnect if needed
            val msg = describeServerAdb(serverPort, mode, ensureServerAdb(s, serverPort))
            _state.value = _state.value.copy(enabled = true, serverPort = serverPort, serverPairPort = pairPort, phonePort = port, phoneMode = mode, message = msg)
        }
    }

    /**
     * Makes sure the server's own `adb devices` lists 127.0.0.1:[serverPort] as `device`: reconnects when it is missing,
     * offline or unauthorized. Returns "STATE:<device|unauthorized|offline|none>" or "NO_ADB", plus adb's own words.
     */
    private fun ensureServerAdb(s: Session, serverPort: Int): String = runCatching {
        RemoteServerManager.exec(
            s,
            """
            command -v adb >/dev/null 2>&1 || { echo NO_ADB; exit 0; }
            t=127.0.0.1:$serverPort
            st() { timeout 8 adb devices 2>/dev/null | awk -v t="${'$'}t" '${'$'}1==t{print ${'$'}2}'; }
            cur=${'$'}(st)
            out=""
            if [ "${'$'}cur" != device ]; then
              timeout 5 adb disconnect "${'$'}t" >/dev/null 2>&1
              out=${'$'}(timeout 12 adb connect "${'$'}t" 2>&1 | tail -n 1)
              sleep 1
              cur=${'$'}(st)
            fi
            echo "ADBSAID:${'$'}out"
            echo "STATE:${'$'}{cur:-none}"
            """.trimIndent(),
            40_000
        ).second.trim()
    }.getOrDefault("")

    private fun describeServerAdb(serverPort: Int, mode: String, result: String): String {
        val state = Regex("STATE:(\\w+)").find(result)?.groupValues?.get(1)
        val said = Regex("ADBSAID:(.*)").find(result)?.groupValues?.get(1)?.trim().orEmpty()
        return when {
            result.contains("NO_ADB") -> "adb is not installed on the server (sudo apt install adb). The tunnel is open on 127.0.0.1:$serverPort."
            state == "device" -> "Connected: `adb devices` on the server lists the phone (127.0.0.1:$serverPort)."
            state == "unauthorized" -> "The tunnel works but the phone has not authorized the server's adb. " +
                (if (mode == "tcpip") "Accept the \"Allow USB debugging\" prompt on the phone." else "Use \"Pair the server (Wireless debugging)\" below.")
            state == "offline" -> "The server's adb sees the phone as offline. " +
                (if (mode == "wireless") "Wireless debugging needs the server paired once (\"Pair the server\" below) and an adb version 30 or newer on the server." else "Toggle USB debugging / restart adbd on the phone.")
            result.isBlank() -> "Tunnel open on 127.0.0.1:$serverPort, but the server's adb could not be checked."
            else -> "Tunnel open on 127.0.0.1:$serverPort but the phone is not in the server's `adb devices`." +
                if (said.isNotBlank()) " adb connect said: ${said.take(140)}" else ""
        }
    }

    private fun markEnabled(context: Context, enabled: Boolean) {
        val prefs = AuthPreferences(context)
        val (c, p) = prefs.getAdbTunnelPortsSync()
        prefs.saveAdbTunnelSync(enabled, c, p)
        _state.value = _state.value.copy(enabled = enabled, serverPort = c, serverPairPort = p)
    }

    /**
     * Utilities > ADB Connect in server mode: forwards this phone's adb to the server and connects the SERVER's adb to it.
     * [phonePort] = a port typed by the user, or null to find it. Success means the server's `adb devices` lists the phone.
     */
    suspend fun connectServer(context: Context, phonePort: Int?): Result<String> {
        val s = RemoteServerManager.controlSession()
            ?: return Result.failure(IllegalStateException("SSH to the server is not connected. Check Settings > Servers."))
        markEnabled(context, true)
        manualPhonePort = phonePort
        lastCheck = 0L
        forwardedPort = null
        sync(context, s)
        val msg = _state.value.message
        return if (msg.startsWith("Connected")) Result.success(msg) else Result.failure(IllegalStateException(msg.ifBlank { "Could not connect the server's adb." }))
    }

    /** Takes the phone away from the server's adb again (closes the forward, `adb disconnect` there). */
    suspend fun disconnectServer(context: Context) {
        markEnabled(context, false)
        manualPhonePort = null
        RemoteServerManager.controlSession()?.let { s ->
            sync(context, s)
            // also when this app run never opened the forward (app restarted): drop the server's stale entry
            val (serverPort, _) = AuthPreferences(context).getAdbTunnelPortsSync()
            runCatching { RemoteServerManager.exec(s, "timeout 8 adb disconnect 127.0.0.1:$serverPort >/dev/null 2>&1; true", 12_000) }
        }
    }

    /**
     * Forwards the phone's pairing port to the server so `adb pair 127.0.0.1:<serverPairPort> <code>` works there.
     * [manualPort] is the port Android shows in "Pair device with pairing code"; blank = find it automatically.
     */
    suspend fun forwardPairing(context: Context, manualPort: Int?): Result<Int> = lock.withLock {
        val s = RemoteServerManager.controlSession() ?: return@withLock Result.failure(IllegalStateException("SSH is not connected"))
        val (_, pairPort) = AuthPreferences(context).getAdbTunnelPortsSync()
        val phonePairPort = manualPort ?: withTimeoutOrNull(20_000) { AdbConnectManager.pairingPorts().first() }
            ?: return@withLock Result.failure(IllegalStateException("The pairing dialog was not found. Open Developer options > Wireless debugging > Pair device with pairing code, then try again (or type the port shown there)."))
        return@withLock try {
            runCatching { s.delPortForwardingR("127.0.0.1", pairPort) }
            s.setPortForwardingR("127.0.0.1", pairPort, "127.0.0.1", phonePairPort)
            forwardedPair = phonePairPort
            _state.value = _state.value.copy(pairingForwardedTo = phonePairPort, serverPairPort = pairPort)
            Result.success(pairPort)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The whole pairing in one step: forwards the phone's pairing port to the server, runs `adb pair` THERE with the
     * 6-digit [code] the phone shows, closes the forward and (re)connects. The user only types the code in the app.
     */
    suspend fun pairServer(context: Context, code: String, manualPort: Int?): Result<String> {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) return Result.failure(IllegalArgumentException("The pairing code has 6 digits."))
        markEnabled(context, true)
        val s = RemoteServerManager.controlSession() ?: return Result.failure(IllegalStateException("SSH is not connected"))
        val forwarded = forwardPairing(context, manualPort)
        val serverPairPort = forwarded.getOrElse { return Result.failure(it) }
        val out = try {
            RemoteServerManager.exec(
                s,
                "command -v adb >/dev/null 2>&1 || { echo NO_ADB; exit 0; }; timeout 25 adb pair 127.0.0.1:$serverPairPort $digits 2>&1",
                35_000
            ).second.trim()
        } finally {
            stopPairingForward(context)
        }
        if (out.contains("NO_ADB")) return Result.failure(IllegalStateException("adb is not installed on the server. Use \"Install adb on the server\", or install it yourself (sudo apt install adb)."))
        if (!out.contains("Successfully paired", ignoreCase = true)) {
            return Result.failure(IllegalStateException(out.ifBlank { "Pairing failed. Check the code, and that the pairing dialog is still open on the phone." }.take(200)))
        }
        // paired: let the next sync connect (the port may be new)
        lastCheck = 0L
        forwardedPort = null
        runCatching { sync(context, s) }
        return Result.success("Paired. " + _state.value.message)
    }

    /**
     * Installs adb on the server when the SSH user is root or has passwordless sudo (apt, dnf, pacman or apk).
     * Returns a short message for the user.
     */
    suspend fun installServerAdb(): Result<String> {
        val s = RemoteServerManager.controlSession() ?: return Result.failure(IllegalStateException("SSH is not connected"))
        val script = """
            command -v adb >/dev/null 2>&1 && { echo OK; exit 0; }
            if [ "${'$'}(id -u)" = 0 ]; then SUDO=""; elif sudo -n true 2>/dev/null; then SUDO="sudo -n"; else echo NEED_SUDO; exit 0; fi
            if command -v apt-get >/dev/null 2>&1; then
              ${'$'}SUDO apt-get install -y adb >/dev/null 2>&1 || { ${'$'}SUDO apt-get update >/dev/null 2>&1 && ${'$'}SUDO apt-get install -y adb >/dev/null 2>&1; }
            elif command -v dnf >/dev/null 2>&1; then ${'$'}SUDO dnf install -y android-tools >/dev/null 2>&1
            elif command -v pacman >/dev/null 2>&1; then ${'$'}SUDO pacman -S --noconfirm android-tools >/dev/null 2>&1
            elif command -v apk >/dev/null 2>&1; then ${'$'}SUDO apk add android-tools >/dev/null 2>&1
            else echo UNSUPPORTED; exit 0; fi
            command -v adb >/dev/null 2>&1 && echo OK || echo FAILED
        """.trimIndent()
        val out = runCatching { RemoteServerManager.exec(s, script, 300_000).second.trim() }.getOrElse { return Result.failure(it) }
        return when {
            out.endsWith("OK") -> {
                lastCheck = 0L
                forwardedPort = null
                Result.success("adb is installed on the server.")
            }
            out.contains("NEED_SUDO") -> Result.failure(IllegalStateException("The SSH user needs root or passwordless sudo to install adb. On the server run: sudo apt install adb"))
            out.contains("UNSUPPORTED") -> Result.failure(IllegalStateException("Unknown package manager. Install adb (android-tools) on the server yourself."))
            else -> Result.failure(IllegalStateException("The install did not finish. On the server run: sudo apt install adb"))
        }
    }

    suspend fun stopPairingForward(context: Context) = lock.withLock {
        val s = RemoteServerManager.controlSession() ?: return@withLock
        val (_, pairPort) = AuthPreferences(context).getAdbTunnelPortsSync()
        runCatching { s.delPortForwardingR("127.0.0.1", pairPort) }
        forwardedPair = null
        _state.value = _state.value.copy(pairingForwardedTo = null)
    }
}
