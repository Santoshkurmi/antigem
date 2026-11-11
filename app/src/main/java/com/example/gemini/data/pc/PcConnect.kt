package com.example.gemini.data.pc

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.ssh.SshSessionFactory
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlin.coroutines.resume

/**
 * Connect to PC: the agents run on a PC (the AntiGem bridge VS Code started there) and this app is a second screen for
 * the same chats. Inside, it is the app's Server mode through an SSH tunnel; this file only adds what makes it one tap:
 *
 *  - pairing: find the PC on the Wi-Fi (mDNS "_antigem-pc._tcp", only announced while VS Code shows "Connect phone"),
 *    type the 6-digit code it shows, and the two sides exchange (protected by the code, see [PcPairingCrypto]) this
 *    phone's SSH public key for the PC's address, SSH user, host keys, bridge ports and bridge token;
 *  - the list of paired PCs, the one in use and switching (one app reopen, like any server change);
 *  - while a PC is in use, [AuthPreferences.pcOverride] makes the app's connection settings point at it, so the
 *    settings the user typed in Settings > Servers stay as they were and come back with "Use this phone".
 *
 * The PC's bridge belongs to VS Code: the app never starts, stops or installs anything there (RemoteServerManager's
 * "attach" path). Protocol for the PC side: docs/connect-to-pc-protocol.md.
 */
object PcConnect {

    private const val TAG = "PcConnect"
    const val SERVICE_TYPE = "_antigem-pc._tcp"

    // kept in AuthPreferences' own file: its init reads the PC in use before anything connects
    private const val PREFS = "anti_gem_sync_prefs"
    private const val KEY_ENABLED = "pc_connect_enabled"
    private const val KEY_PCS = "pc_paired_json"
    private const val KEY_ACTIVE = "pc_active_id"
    private const val KEY_CLIENT_PRIVATE = "pc_client_key"
    private const val KEY_CLIENT_PUBLIC = "pc_client_pub"
    private const val KEY_PHONE_ID = "pc_phone_id"

    data class HostKeyEntry(val type: String, val key: String)

    data class PairedPc(
        val id: String,
        val name: String,
        val os: String,
        /** Every address the PC reported (and the one pairing used); tried in order, the last one that worked first. */
        val addresses: List<String>,
        val lastAddress: String,
        val sshPort: Int,
        val user: String,
        val bridgePort: Int,
        val hubPort: Int,
        /** The PC's SSH host key this phone trusts (the type JSch is told to ask for, and the key blob in base64). */
        val hostKeyType: String,
        val hostKey: String,
        /** The PC bridge's token (refreshed over SFTP at every connect). */
        val token: String,
        val pairedAt: Long,
        val lastUsedAt: Long,
        /** At the last connect the PC let this phone open a shell ("Allow terminal from phone" in VS Code). */
        val shellAllowed: Boolean = false,
    ) {
        fun orderedAddresses(): List<String> = (listOf(lastAddress) + addresses).filter { it.isNotBlank() }.distinct()

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("os", os)
            .put("addresses", JSONArray(addresses)).put("lastAddress", lastAddress)
            .put("sshPort", sshPort).put("user", user)
            .put("bridgePort", bridgePort).put("hubPort", hubPort)
            .put("hostKeyType", hostKeyType).put("hostKey", hostKey)
            .put("token", token).put("pairedAt", pairedAt).put("lastUsedAt", lastUsedAt).put("shellAllowed", shellAllowed)

        companion object {
            fun fromJson(o: JSONObject): PairedPc? = runCatching {
                val addrs = o.optJSONArray("addresses")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                PairedPc(
                    id = o.getString("id"),
                    name = o.optString("name").ifBlank { "PC" },
                    os = o.optString("os"),
                    addresses = addrs,
                    lastAddress = o.optString("lastAddress").ifBlank { addrs.firstOrNull().orEmpty() },
                    sshPort = o.optInt("sshPort", 22),
                    user = o.getString("user"),
                    bridgePort = o.optInt("bridgePort", 1244),
                    hubPort = o.optInt("hubPort", 1245),
                    hostKeyType = o.getString("hostKeyType"),
                    hostKey = o.getString("hostKey"),
                    token = o.optString("token"),
                    pairedAt = o.optLong("pairedAt"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                    shellAllowed = o.optBoolean("shellAllowed", false),
                )
            }.getOrNull()
        }
    }

    @Volatile private var appContext: Context? = null
    private fun prefs(context: Context? = appContext): SharedPreferences? = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _pcs = MutableStateFlow<List<PairedPc>>(emptyList())
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()
    /** The PC in use (id), or null for this phone. */
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    private fun reload() {
        val p = prefs() ?: return
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _pcs.value = readPcs(p)
        _activeId.value = p.getString(KEY_ACTIVE, null)?.takeIf { id -> id.isNotBlank() && _pcs.value.any { it.id == id } }
    }

    private fun readPcs(p: SharedPreferences): List<PairedPc> = runCatching {
        val arr = JSONArray(p.getString(KEY_PCS, "[]"))
        (0 until arr.length()).mapNotNull { PairedPc.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writePcs(list: List<PairedPc>) {
        prefs()?.edit()?.putString(KEY_PCS, JSONArray(list.map { it.toJson() }).toString())?.commit()
        _pcs.value = list
    }

    /**
     * The connection settings for the PC in use, read straight from the preferences file (AuthPreferences' init calls
     * this, before anything connects). Null when the feature is off or no PC is chosen.
     */
    fun loadOverride(p: SharedPreferences): AuthPreferences.PcOverride? {
        if (!p.getBoolean(KEY_ENABLED, false)) return null
        val id = p.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() } ?: return null
        val pc = readPcs(p).firstOrNull { it.id == id } ?: return null
        val key = p.getString(KEY_CLIENT_PRIVATE, null)?.takeIf { it.isNotBlank() } ?: return null
        return overrideFor(pc, key)
    }

    private fun overrideFor(pc: PairedPc, privateKey: String) = AuthPreferences.PcOverride(
        pcId = pc.id,
        pcName = pc.name,
        host = pc.orderedAddresses().firstOrNull().orEmpty(),
        sshPort = pc.sshPort,
        user = pc.user,
        bridgePort = pc.bridgePort,
        hubPort = pc.hubPort,
        token = pc.token,
        privateKey = privateKey,
        hostKeyAlgorithms = hostKeyAlgorithms(pc.hostKeyType),
        shellAllowed = pc.shellAllowed,
    )

    fun isEnabled(): Boolean = _enabled.value
    fun list(): List<PairedPc> = _pcs.value
    fun get(id: String): PairedPc? = _pcs.value.firstOrNull { it.id == id }
    fun active(): PairedPc? = if (_enabled.value) _activeId.value?.let(::get) else null

    /** Turning the feature off keeps the paired PCs (turning it on again brings them back); the caller leaves a PC first. */
    fun setEnabled(on: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, on)?.commit()
        _enabled.value = on
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(40).ifBlank { return }
        writePcs(_pcs.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Forgets a PC on this phone (its key stays on the PC until removed there, in VS Code → Connect phone). */
    fun remove(id: String) {
        val pc = get(id) ?: return
        pc.orderedAddresses().forEach { SshSessionFactory.forgetHostKey(it, pc.sshPort) }
        writePcs(_pcs.value.filterNot { it.id == id })
    }

    /** The address that answered, the token and whether a shell is allowed, from the last connect (kept for next time). */
    internal fun noteConnected(id: String, address: String, token: String?, shellAllowed: Boolean? = null) {
        val now = System.currentTimeMillis()
        val goodToken = token?.takeIf { t -> t.length == AuthPreferences.TOKEN_LENGTH }
        writePcs(_pcs.value.map {
            if (it.id != id) it else it.copy(
                lastAddress = address,
                addresses = (listOf(address) + it.addresses).distinct().take(8),
                token = goodToken ?: it.token,
                lastUsedAt = now,
                shellAllowed = shellAllowed ?: it.shellAllowed,
            )
        })
        AuthPreferences.pcOverride?.let { o ->
            if (o.pcId == id) AuthPreferences.pcOverride = o.copy(
                host = address, token = goodToken ?: o.token, shellAllowed = shellAllowed ?: o.shellAllowed,
            )
        }
    }

    /**
     * Whether the PC lets this phone's key run commands: VS Code adds the key limited to file reads and the tunnel unless
     * "Allow terminal from phone" is on there, and such a key answers any command with "This service allows sftp
     * connections only".
     */
    fun probeShell(s: Session): Boolean = runCatching { execOut(s, "echo antigem-shell-ok").contains("antigem-shell-ok") }.getOrDefault(false)

    // ------------------------------------------------------------------------------------------------ this phone

    fun phoneId(): String {
        val p = prefs() ?: return "phone"
        p.getString(KEY_PHONE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_PHONE_ID, id).commit()
        return id
    }

    fun phoneName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifBlank { "Android phone" }
    }

    /** This phone's SSH key for PCs (made once; ECDSA P-256, which every OpenSSH server accepts). */
    @Synchronized
    fun clientKey(): Pair<String, String> {
        val p = prefs() ?: throw IllegalStateException("App not ready")
        val priv = p.getString(KEY_CLIENT_PRIVATE, null)
        val pub = p.getString(KEY_CLIENT_PUBLIC, null)
        if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) return priv to pub
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val privOut = ByteArrayOutputStream().also { kp.writePrivateKey(it) }
            val pubOut = ByteArrayOutputStream().also { kp.writePublicKey(it, "antigem-phone:${phoneId()}") }
            val newPriv = privOut.toString("UTF-8").trim()
            val newPub = pubOut.toString("UTF-8").trim()
            p.edit().putString(KEY_CLIENT_PRIVATE, newPriv).putString(KEY_CLIENT_PUBLIC, newPub).commit()
            return newPriv to newPub
        } finally {
            kp.dispose()
        }
    }

    // ------------------------------------------------------------------------------------------------ discovery

    data class NearbyPc(val id: String, val name: String, val os: String, val host: String, val port: Int)

    /** PCs on this Wi-Fi that show "Connect phone" right now (updates while collected). */
    fun discover(context: Context): Flow<List<NearbyPc>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            trySend(emptyList()); close(); return@callbackFlow
        }
        val found = linkedMapOf<String, NearbyPc>()
        val resolving = Mutex() // older NsdManager rejects concurrent resolves
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val r = resolving.withLock { resolve(nsd, info) } ?: return@launch
                    val host = r.host?.hostAddress?.substringBefore('%') ?: return@launch
                    val attrs = r.attributes.orEmpty()
                    fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8).orEmpty()
                    val pc = NearbyPc(
                        id = attr("id").ifBlank { r.serviceName },
                        name = attr("name").ifBlank { r.serviceName },
                        os = attr("os"),
                        host = host,
                        port = r.port,
                    )
                    synchronized(found) { found[info.serviceName] = pc; trySend(found.values.toList()) }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName); trySend(found.values.toList()) }
            }
            override fun onDiscoveryStarted(serviceType: String) { trySend(emptyList()) }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(IllegalStateException("Searching the Wi-Fi failed ($errorCode)")) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
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

    // ------------------------------------------------------------------------------------------------ pairing

    class PairingException(message: String) : Exception(message)

    /** What pairing reports along the way ("Checking the code…"). */
    fun interface Progress { fun step(text: String) }

    /**
     * Pairs with the PC at [address]:[port] (the pairing port VS Code announces) using the [code] it shows. Saves the
     * PC (or updates it, when paired before) and returns it. Throws [PairingException] with a message for the user.
     */
    suspend fun pair(address: String, port: Int, code: String, progress: Progress = Progress {}): PairedPc = withContext(Dispatchers.IO) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) throw PairingException("Enter the 6-digit code shown on the PC.")
        val base = "http://${if (address.contains(':')) "[$address]" else address}:$port"
        progress.step("Preparing this phone's key…")
        val (_, publicKey) = clientKey()
        val phoneId = phoneId()
        val phoneName = phoneName()

        progress.step("Checking the code…")
        val side = PcPairingCrypto.PhoneSide(digits, phoneId)
        val start = postJson("$base/antigem/pair/start", JSONObject()
            .put("v", 1).put("phoneId", phoneId).put("phoneName", phoneName).put("x", side.xHex))
        val session = start.optString("session").ifBlank { throw PairingException("The PC answered something unexpected. Update AntiGem in VS Code.") }
        val keys = try {
            side.finish(start.getString("y"))
        } catch (e: Exception) {
            throw PairingException("The PC answered something unexpected. Update AntiGem in VS Code.")
        }
        val pcConfirm = runCatching { PcPairingCrypto.fromHex(start.getString("confirm")) }.getOrNull()
        if (pcConfirm == null || !PcPairingCrypto.same(pcConfirm, keys.pcConfirm)) {
            throw PairingException("Wrong code. Check the 6 digits on the PC (a new code appears after 3 wrong tries).")
        }

        progress.step("Exchanging keys… (on a Windows PC: approve the admin prompt there)")
        val box = PcPairingCrypto.seal(keys.phoneToPc, JSONObject()
            .put("phoneId", phoneId).put("phoneName", phoneName).put("publicKey", publicKey)
            .toString().toByteArray(Charsets.UTF_8))
        // the PC may wait for its user here (Windows asks an admin to allow the key)
        val done = postJson("$base/antigem/pair/finish", JSONObject()
            .put("v", 1).put("session", session).put("confirm", PcPairingCrypto.toHex(keys.phoneConfirm)).put("box", box),
            readTimeoutMs = 150_000)
        val info = try {
            JSONObject(String(PcPairingCrypto.open(keys.pcToPhone, done.getString("box")), Charsets.UTF_8))
        } catch (e: Exception) {
            throw PairingException("The PC's answer could not be verified. Try again with a new code.")
        }

        val hostKeys = info.optJSONArray("hostKeys")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { HostKeyEntry(it.optString("type"), it.optString("key")) } }
        }.orEmpty().filter { it.type.isNotBlank() && it.key.isNotBlank() }
        val chosen = chooseHostKey(hostKeys) ?: throw PairingException("The PC's SSH server sent no key this app can use.")
        val reported = info.optJSONArray("addresses")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val now = System.currentTimeMillis()
        val pcId = info.optString("pcId").ifBlank { throw PairingException("The PC's answer is incomplete. Update AntiGem in VS Code.") }
        val pc = PairedPc(
            id = pcId,
            name = info.optString("pcName").ifBlank { "PC" },
            os = info.optString("os"),
            addresses = (listOf(address) + reported).distinct(),
            lastAddress = address,
            sshPort = info.optInt("sshPort", 22),
            user = info.optString("user").ifBlank { throw PairingException("The PC did not say which user to log in as.") },
            bridgePort = info.optInt("bridgePort", 1244),
            hubPort = info.optInt("hubPort", 1245),
            hostKeyType = chosen.type,
            hostKey = chosen.key,
            token = info.optString("token"),
            pairedAt = now,
            lastUsedAt = get(pcId)?.lastUsedAt ?: 0L,
        )
        // trust exactly this host key for every address of the PC (replaces anything pinned there before)
        pc.orderedAddresses().forEach { SshSessionFactory.pinHostKey(it, pc.sshPort, pc.hostKey) }
        writePcs(_pcs.value.filterNot { it.id == pc.id } + pc)

        progress.step("Testing the connection…")
        testConnection(pc)?.let { problem ->
            throw PairingException("Paired, but the SSH login to the PC failed: $problem")
        }
        pc
    }

    /** Logs in with this phone's key and reads the bridge token. Null when it works, else the reason. */
    suspend fun testConnection(pc: PairedPc): String? = withContext(Dispatchers.IO) {
        val (privateKey, _) = clientKey()
        val address = reachableAddress(pc) ?: return@withContext "${pc.lastAddress}:${pc.sshPort} cannot be reached (same Wi-Fi? SSH server on?)"
        var s: Session? = null
        try {
            s = SshSessionFactory.open(address, pc.sshPort, pc.user, "", connectTimeoutMs = 10_000,
                privateKey = privateKey, hostKeyAlgorithms = hostKeyAlgorithms(pc.hostKeyType))
            val token = readToken(s)
            noteConnected(pc.id, address, token, probeShell(s))
            null
        } catch (e: Exception) {
            com.example.gemini.data.remote.RemoteServerManager.friendly(e)
        } finally {
            runCatching { s?.disconnect() }
        }
    }

    /** ECDSA first (always verifiable on Android), then RSA, then Ed25519. */
    private fun chooseHostKey(keys: List<HostKeyEntry>): HostKeyEntry? =
        keys.firstOrNull { it.type == "ecdsa-sha2-nistp256" }
            ?: keys.firstOrNull { it.type == "ssh-rsa" }
            ?: keys.firstOrNull { it.type.startsWith("ecdsa-sha2-") }
            ?: keys.firstOrNull { it.type == "ssh-ed25519" }

    /** JSch's server_host_key list that makes the PC present the pinned key. */
    fun hostKeyAlgorithms(type: String): String = when (type) {
        "ssh-rsa" -> "rsa-sha2-512,rsa-sha2-256,ssh-rsa"
        else -> type
    }

    private fun postJson(url: String, body: JSONObject, readTimeoutMs: Int = 15_000): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) {
                val msg = json?.optString("error")?.takeIf { it.isNotBlank() }
                throw PairingException(msg ?: when (code) {
                    404 -> "This PC is not pairing right now. Open \"Connect phone\" in AntiGem on the PC."
                    410 -> "The code expired. Use the new code shown on the PC."
                    423 -> "Too many wrong codes. Use the new code shown on the PC."
                    else -> "The PC refused the pairing (HTTP $code)."
                })
            }
            return json ?: throw PairingException("The PC answered something unexpected. Update AntiGem in VS Code.")
        } catch (e: PairingException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw PairingException("The PC did not answer. Are both on the same Wi-Fi?")
        } catch (e: java.io.IOException) {
            throw PairingException("Cannot reach the PC (${e.message ?: "network error"}). Is \"Connect phone\" open in AntiGem on the PC?")
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------------------------------------ connecting

    /** The first address of [pc] whose SSH port answers (the last one that worked is tried first). */
    suspend fun reachableAddress(pc: PairedPc, timeoutMs: Int = 1_500): String? = withContext(Dispatchers.IO) {
        pc.orderedAddresses().firstOrNull { addr ->
            runCatching {
                java.net.Socket().use { it.connect(InetSocketAddress(addr, pc.sshPort), timeoutMs) }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * The PC bridge's token, read over the SSH login: SFTP first (works the same on Linux, macOS and Windows), then a
     * shell command. Null when it cannot be read (the token from pairing is used then).
     */
    fun readToken(s: Session): String? {
        val rel = ".antigem_bridge/csrf_token.txt"
        val viaSftp = runCatching {
            val sftp = s.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            try {
                val out = ByteArrayOutputStream()
                sftp.get(rel, out)
                out.toString("UTF-8").trim()
            } finally {
                sftp.disconnect()
            }
        }.getOrNull()
        if (viaSftp != null && viaSftp.length == AuthPreferences.TOKEN_LENGTH) return viaSftp
        for (cmd in listOf("cat ~/$rel", "type %USERPROFILE%\\.antigem_bridge\\csrf_token.txt")) {
            val out = runCatching { execOut(s, cmd) }.getOrNull()?.trim()
            if (out != null && out.length == AuthPreferences.TOKEN_LENGTH) return out
        }
        return null
    }

    private fun execOut(s: Session, command: String): String {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val input = ch.inputStream
        ch.connect(10_000)
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                if (ch.isClosed && input.available() <= 0) break
                Thread.sleep(50)
            }
            return out.toString("UTF-8")
        } finally {
            ch.disconnect()
        }
    }

    /**
     * Called by RemoteServerManager when it connects while a PC is in use: picks the address that answers (so a PC
     * reached at home and at the office both work) and returns it.
     */
    suspend fun prepareConnect(): String? {
        val o = AuthPreferences.pcOverride ?: return null
        val pc = get(o.pcId) ?: return o.host
        val addr = reachableAddress(pc) ?: return o.host
        if (addr != o.host) AuthPreferences.pcOverride = o.copy(host = addr)
        return addr
    }

    /** After the SSH login to the PC in use: keeps the address and the bridge's current token. */
    fun afterConnect(s: Session) {
        val o = AuthPreferences.pcOverride ?: return
        val token = runCatching { readToken(s) }.getOrNull()
        if (token == null) Log.w(TAG, "Could not read the PC bridge token; using the saved one")
        noteConnected(o.pcId, o.host, token, probeShell(s))
    }

    // ------------------------------------------------------------------------------------------------ switching

    /** Label of the way back: this phone's own agents, or the server set in Settings > Servers. */
    fun ownModeLabel(): String = if (AuthPreferences.storedRemoteMode) "Use my server" else "Use this phone"

    /**
     * Switches to [pcId] (null = back to this phone / the user's own server) and reopens the app, which then connects
     * with the new settings. What runs now is let go first: this phone's own bridge is stopped (it must not keep
     * running in the background), a server's or a PC's bridge is only disconnected (never stopped).
     */
    suspend fun switchTo(context: Context, pcId: String?) {
        val app = context.applicationContext
        if (pcId != null && get(pcId) == null) return
        val wasLocal = !AuthPreferences.currentRemoteMode
        if (wasLocal) {
            runCatching { com.example.gemini.data.local.LocalServerManager.stopServerCompletely() }
        } else {
            com.example.gemini.data.remote.RemoteServerManager.disconnect()
        }
        prefs(app)?.edit()?.apply {
            if (pcId != null) putBoolean(KEY_ENABLED, true)
            putString(KEY_ACTIVE, pcId ?: "")
        }?.commit()
        reopen(app)
    }

    fun reopen(context: Context) {
        val app = context.applicationContext
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.let { component ->
            app.startActivity(Intent.makeRestartActivityTask(component))
        }
        Runtime.getRuntime().exit(0)
    }
}
