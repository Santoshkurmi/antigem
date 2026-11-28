package ai.antigem.data.netcap

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Network Interceptor CA: one per install, made by the engine. The private key is kept encrypted with an Android
 * Keystore key (only this app can open it) and handed to the engine at each start; it is never written in plain text.
 * With root the certificate is installed as a system certificate until the next reboot.
 */
object NetcapCert {
    private const val ALIAS = "antigem_netcap_ca"

    /**
     * Shell helpers for the APEX store, one call per process but one action per mount namespace: unbind removes our
     * binds (they can stack), rebind = unbind + one fresh bind of the tmpfs store.
     */
    private val NS_FUNCS = """
SEEN=" "
ns_new() { NS=${'$'}(readlink /proc/${'$'}1/ns/mnt 2>/dev/null) || return 1; case "${'$'}SEEN" in *" ${'$'}NS "*) return 1 ;; esac; SEEN="${'$'}SEEN${'$'}NS "; return 0; }
ns_unbind() { i=0; while [ ${'$'}i -lt 400 ] && nsenter --mount=/proc/${'$'}1/ns/mnt -- /system/bin/umount /apex/com.android.conscrypt/cacerts 2>/dev/null; do i=${'$'}((i+1)); done; }
unbind() { ns_new ${'$'}1 && ns_unbind ${'$'}1; return 0; }
rebind() { ns_new ${'$'}1 || return 0; ns_unbind ${'$'}1; nsenter --mount=/proc/${'$'}1/ns/mnt -- /system/bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts 2>/dev/null || true; }
""".trim()
    private const val PREFS = "netcap_prefs"

    private fun dir(c: Context) = File(c.filesDir, "netcap_ca").apply { mkdirs() }

    fun certPem(c: Context): String? = File(dir(c), "ca.crt").takeIf { it.exists() }?.readText()

    /** (certificate PEM, key PEM) or null when there is none yet. */
    fun load(c: Context): Pair<String, String>? = runCatching {
        val crt = File(dir(c), "ca.crt")
        val enc = File(dir(c), "ca.key.enc")
        if (!crt.exists() || !enc.exists()) return null
        val bytes = enc.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        crt.readText() to String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun save(c: Context, certPem: String, keyPem: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(keyPem.toByteArray(Charsets.UTF_8))
        File(dir(c), "ca.key.enc").writeBytes(out)
        File(dir(c), "ca.crt").writeText(certPem)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun x509(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    /** Android's file name for a CA: OpenSSL's old subject hash (MD5 of the DER subject, first 4 bytes little-endian). */
    fun androidName(pem: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(x509(pem).subjectX500Principal.encoded)
        val v = (md5[0].toLong() and 0xff) or ((md5[1].toLong() and 0xff) shl 8) or ((md5[2].toLong() and 0xff) shl 16) or ((md5[3].toLong() and 0xff) shl 24)
        return String.format("%08x.0", v)
    }

    /** Whether this app sees the CA in the system store (apps started after an install see it too). */
    fun isTrusted(c: Context): Boolean {
        val pem = certPem(c) ?: return false
        val name = androidName(pem)
        return File("/apex/com.android.conscrypt/cacerts/$name").exists() || File("/system/etc/security/cacerts/$name").exists()
    }

    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun installedThisBoot(c: Context): Boolean {
        val at = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("cert_boot", 0L)
        return at != 0L && kotlin.math.abs(at - bootTime()) < 60_000
    }

    fun statusText(c: Context): String {
        if (certPem(c) == null) return "no certificate yet (the engine makes one at its first start)"
        return when {
            isTrusted(c) -> "system certificate ✓ (until reboot; restart the app under test after installing)"
            installedThisBoot(c) -> "installed with root this boot (apps started since then trust it)"
            else -> "not installed: net_cert action=install (root), or the user installs it as a user certificate (only apps that allow user certificates, like debug builds, then work)"
        }
    }

    /** Writes the certificate to Downloads (for a manual install). */
    fun saveToDownloads(c: Context): File {
        val pem = certPem(c) ?: throw IOException("no certificate yet")
        @Suppress("DEPRECATION")
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val f = File(dl, "antigem-network-interceptor-ca.crt")
        f.writeText(pem)
        return f
    }

    /** Installs the CA as a system certificate with root, until the next reboot. */
    suspend fun install(c: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pem = certPem(c) ?: throw IOException("no certificate yet")
            val name = androidName(pem)
            val script = """
                set -e
                NAME=$name
                TMP=/data/local/tmp/antigem-netcap-ca
                rm -rf "${'$'}TMP"; mkdir -p -m 700 "${'$'}TMP"
                cat > "${'$'}TMP/${'$'}NAME" <<'ANTIGEM_CA_EOF'
                __PEM__
                ANTIGEM_CA_EOF
                if [ -d /apex/com.android.conscrypt/cacerts ]; then SRC=/apex/com.android.conscrypt/cacerts; else SRC=/system/etc/security/cacerts; fi
                for f in "${'$'}SRC"/*; do [ "${'$'}(basename "${'$'}f")" = "${'$'}NAME" ] || cp "${'$'}f" "${'$'}TMP"/ 2>/dev/null || true; done
                umount /system/etc/security/cacerts 2>/dev/null || true
                mount -t tmpfs tmpfs /system/etc/security/cacerts
                cp "${'$'}TMP"/* /system/etc/security/cacerts/
                chown root:root /system/etc/security/cacerts/*
                chmod 644 /system/etc/security/cacerts/*
                chcon u:object_r:system_file:s0 /system/etc/security/cacerts 2>/dev/null || true
                chcon u:object_r:system_file:s0 /system/etc/security/cacerts/* 2>/dev/null || true
                if [ -d /apex/com.android.conscrypt/cacerts ]; then
                  # Android 14+: the store is in the Conscrypt APEX; bind ours over it in zygote's and every app's mount
                  # namespace, once per namespace (many apps share one), after removing older binds there
                  __NSFUNCS__
                  for Z in ${'$'}(pidof zygote zygote64 2>/dev/null); do
                    rebind ${'$'}Z
                    for P in ${'$'}(ps -o PID -P ${'$'}Z 2>/dev/null | grep -v PID); do rebind ${'$'}P; done
                  done
                fi
                rm -rf "${'$'}TMP"
                ls /system/etc/security/cacerts/${'$'}NAME >/dev/null
                echo installed
            """.trimIndent().replace("__NSFUNCS__", NS_FUNCS).replace("__PEM__", pem.trim())
            val (code, out) = runRoot(c, script)
            if (code != 0 || !out.contains("installed")) throw IOException("root install failed (exit $code): ${out.takeLast(600)}")
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("cert_boot", bootTime()).apply()
            "installed as system certificate $name until reboot; restart the apps you capture so they use it"
        }
    }

    /** Removes the system certificate mounts made by [install]. */
    suspend fun remove(c: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val script = """
                if [ -d /apex/com.android.conscrypt/cacerts ]; then
                  __NSFUNCS__
                  for Z in ${'$'}(pidof zygote zygote64 2>/dev/null); do
                    unbind ${'$'}Z
                    for P in ${'$'}(ps -o PID -P ${'$'}Z 2>/dev/null | grep -v PID); do unbind ${'$'}P; done
                  done
                fi
                umount /system/etc/security/cacerts 2>/dev/null || true
                echo removed
            """.trimIndent().replace("__NSFUNCS__", NS_FUNCS)
            val (code, out) = runRoot(c, script)
            if (!out.contains("removed")) throw IOException("root remove failed (exit $code): ${out.takeLast(600)}")
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("cert_boot").apply()
            "removed (apps already running keep it until they restart; a reboot removes it everywhere)"
        }
    }

    /**
     * Runs a script as root in the global mount namespace (so other apps see the mounts): `su --mount-master`
     * (Magisk, KernelSU, APatch), or else plain su entering init's namespace with nsenter.
     */
    private fun runRoot(c: Context, script: String): Pair<Int, String> {
        val file = File(c.cacheDir, "netcap-root.sh")
        file.writeText(script + "\n")
        file.setReadable(true, false)
        try {
            val first = exec(listOf("su", "--mount-master", "-c", "sh ${file.absolutePath}"))
            val unsupported = first.first != 0 && Regex("(?i)(invalid|unknown|unrecognized) option|usage:").containsMatchIn(first.second)
            if (!unsupported) return first
            return exec(listOf("su", "-c", "nsenter --mount=/proc/1/ns/mnt -- sh ${file.absolutePath}"))
        } finally {
            file.delete()
        }
    }

    private fun exec(cmd: List<String>): Pair<Int, String> {
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return -1 to "su was not found: this phone is not rooted, or the root manager hides su from AntiGem"
        }
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        val start = System.currentTimeMillis()
        while (true) {
            try {
                val code = p.exitValue()
                reader.join(2000)
                return code to out.toString()
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() - start > TimeUnit.SECONDS.toMillis(90)) {
                    p.destroy()
                    return 124 to (out.toString() + "\n(timed out; was the root prompt answered?)")
                }
                Thread.sleep(100)
            }
        }
    }
}
