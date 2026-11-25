package ai.antigem.data.ssh

import android.content.Context
import android.util.Log
import ai.antigem.data.preferences.AuthPreferences
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.util.Base64
import java.util.Properties

/**
 * The one place that builds SSH connections (terminal, file tools, server management, port forwards).
 *
 *  - Authenticates with the saved private key when there is one, otherwise with the password.
 *  - Pins the server's host key the first time it connects (trust on first use). A later key that differs
 *    is refused, so a man in the middle cannot silently take over the connection.
 */
object SshSessionFactory {

    private const val TAG = "SshSessionFactory"
    private const val PREFS = "anti_gem_ssh_hosts"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True for private keys JSch can read in this app (OpenSSH, PEM RSA/ECDSA/Ed25519). */
    fun looksLikePrivateKey(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("-----BEGIN") && t.contains("PRIVATE KEY-----")
    }

    fun open(
        host: String,
        port: Int,
        user: String,
        pass: String,
        connectTimeoutMs: Int = 10_000,
        keepAliveMs: Int = 0,
        /** Log in with this key instead of the saved one (Connect to PC pairing test). */
        privateKey: String? = null,
        /** Ask the server for this host key type only (JSch server_host_key): the one pinned for a paired PC. */
        hostKeyAlgorithms: String? = null
    ): Session {
        val jsch = JSch()
        val key = privateKey ?: AuthPreferences.currentSshPrivateKey
        val usingKey = looksLikePrivateKey(key)
        // a paired PC (Connect to PC) must present exactly the host key that pairing pinned
        val pc = AuthPreferences.pcOverride
        val hostKeyAlgos = hostKeyAlgorithms ?: pc?.takeIf { it.host == host && it.sshPort == port }?.hostKeyAlgorithms
        if (usingKey) {
            val passphrase = if (privateKey != null) "" else AuthPreferences.currentSshKeyPassphrase
            jsch.addIdentity(
                "antigem-key",
                (key.trim() + "\n").toByteArray(Charsets.UTF_8),
                null,
                if (passphrase.isEmpty()) null else passphrase.toByteArray(Charsets.UTF_8)
            )
        }
        jsch.hostKeyRepository = PinnedHostKeys(host, port)

        val session = jsch.getSession(user, host, port)
        if (pass.isNotEmpty()) session.setPassword(pass)
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = AuthPreferences.currentSshKeyPassphrase.ifEmpty { null }
            override fun getPassword(): String? = pass.ifEmpty { null }
            override fun promptPassword(message: String?) = pass.isNotEmpty()
            override fun promptPassphrase(message: String?) = AuthPreferences.currentSshKeyPassphrase.isNotEmpty()
            // PinnedHostKeys pins a new server's key itself, so JSch only asks when the key changed (or none was sent):
            // answering yes would connect to whoever answers, so refuse ("HostKey has been changed", see friendly())
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) {}
        }
        session.setConfig(Properties().apply {
            // "ask" routes unknown keys to the repository below, which pins them
            put("StrictHostKeyChecking", "ask")
            put(
                "PreferredAuthentications",
                if (usingKey) "publickey,password,keyboard-interactive" else "password,keyboard-interactive,publickey"
            )
            put("ConnectTimeout", connectTimeoutMs.toString())
            if (!hostKeyAlgos.isNullOrBlank()) put("server_host_key", hostKeyAlgos)
            put("compression.s2c", "zlib@openssh.com,zlib,none")
            put("compression.c2s", "zlib@openssh.com,zlib,none")
        })
        session.timeout = 15_000
        if (keepAliveMs > 0) {
            session.setServerAliveInterval(keepAliveMs)
            session.setServerAliveCountMax(3)
        }
        session.connect(connectTimeoutMs)
        return session
    }

    /**
     * Trusts exactly [keyBase64] (the key blob, as in a .pub file) for [host]:[port], replacing what was pinned there:
     * Connect to PC learns the PC's host key during pairing, so the first login is not a blind trust-on-first-use.
     */
    fun pinHostKey(host: String, port: Int, keyBase64: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("$host:$port", "key|${keyBase64.trim()}")?.commit()
    }

    /** Forget the pinned key of a server (after the server was reinstalled and its key legitimately changed). */
    fun forgetHostKey(host: String, port: Int) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("$host:$port")?.apply()
    }

    fun pinnedFingerprint(host: String, port: Int): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("$host:$port", null)?.substringAfter('|')

    /** Trust-on-first-use host key store, kept in the app's private preferences. */
    /** The fingerprint of the last host key that did not match its pin (for the error message), or null. */
    @Volatile
    var lastChangedFingerprint: String? = null
        private set

    private class PinnedHostKeys(private val host: String, private val port: Int) : HostKeyRepository {
        private val prefs get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val id = "$host:$port"

        override fun check(host: String?, key: ByteArray?): Int {
            val store = prefs ?: return HostKeyRepository.NOT_INCLUDED // no context yet: refuse rather than trust any key
            val presented = key?.let { Base64.getEncoder().encodeToString(it) } ?: return HostKeyRepository.NOT_INCLUDED
            val saved = store.getString(id, null)
            if (saved == null) {
                store.edit().putString(id, "key|$presented").apply()
                Log.i(TAG, "Pinned host key for $id")
                return HostKeyRepository.OK
            }
            val savedKey = saved.substringAfter('|')
            return if (savedKey == presented) HostKeyRepository.OK else {
                Log.w(TAG, "Host key for $id CHANGED")
                // shown in the error, so the user can compare it with the server's (ssh-keygen -lf …)
                lastChangedFingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(presented)))
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "antigem-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        /**
         * The pinned key, so JSch asks the server for that key type first (prefer_known_host_key_types): a server that
         * also offers another type later is not refused just because it offered that one first.
         */
        override fun getHostKey(host: String?, type: String?): Array<HostKey> {
            val saved = prefs?.getString(id, null)?.substringAfter('|') ?: return emptyArray()
            val key = runCatching { HostKey(host ?: id, Base64.getDecoder().decode(saved)) }.getOrNull() ?: return emptyArray()
            return if (type == null || key.type == type) arrayOf(key) else emptyArray()
        }
    }
}
