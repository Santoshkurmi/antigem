package com.example.gemini.data.ssh

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
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
        keepAliveMs: Int = 0
    ): Session {
        val jsch = JSch()
        val key = AuthPreferences.currentSshPrivateKey
        val usingKey = looksLikePrivateKey(key)
        if (usingKey) {
            val passphrase = AuthPreferences.currentSshKeyPassphrase
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
            override fun promptYesNo(message: String?) = true // host keys are handled by PinnedHostKeys
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

    /** Forget the pinned key of a server (after the server was reinstalled and its key legitimately changed). */
    fun forgetHostKey(host: String, port: Int) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("$host:$port")?.apply()
    }

    fun pinnedFingerprint(host: String, port: Int): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("$host:$port", null)?.substringAfter('|')

    /** Trust-on-first-use host key store, kept in the app's private preferences. */
    private class PinnedHostKeys(private val host: String, private val port: Int) : HostKeyRepository {
        private val prefs get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val id = "$host:$port"

        override fun check(host: String?, key: ByteArray?): Int {
            val store = prefs ?: return HostKeyRepository.OK // no context yet: do not block
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
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "antigem-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
