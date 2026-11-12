package com.example.gemini.data.miniapps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Signed mini app packages (.agapp), like signed APKs. Format v1 (the PC tool `agapp.mjs` writes the same):
 *  - the app's files at the zip root
 *  - META-INF/AGAPP.json      {format, id, version, versionCode, files: {path: sha256}, signedAt}  ← what is signed
 *  - META-INF/AGAPP.CERT.json {format, algorithm: "ECDSA-P256-SHA256", publicKey: base64 SPKI DER, developer}
 *  - META-INF/AGAPP.SIG       base64 DER ECDSA-SHA256 signature over the bytes of AGAPP.json
 * The developer key is an EC P-256 key. On the phone it is kept encrypted by a key in Android Keystore (it never
 * sits readable on disk); a password-protected backup (.agkey) moves it to a PC or another phone.
 */
object MiniAppSigning {
    const val DIGEST = "META-INF/AGAPP.json"
    const val CERT = "META-INF/AGAPP.CERT.json"
    const val SIG = "META-INF/AGAPP.SIG"
    /** The type .agapp files are shared and opened with (AntiGem's file provider reports it for *.agapp). */
    const val MIME = "application/vnd.antigem.agapp"

    /** A zip with a signed file list: what to treat as a package before verifying it. */
    fun looksLikePackage(file: File): Boolean = runCatching { ZipFile(file).use { it.getEntry(DIGEST) != null } }.getOrDefault(false)
    private const val WRAP_ALIAS = "antigem_miniapp_devkey_wrap"
    private val ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** "AB12:CD34:EF56:7890" — the short form shown to people. */
    fun shortFingerprint(fp: String) = fp.take(16).uppercase().chunked(4).joinToString(":")

    fun fingerprint(spkiDer: ByteArray) = sha256Hex(spkiDer)

    /**
     * manifest version_code (a whole number, or a string of digits), else from "version": the part before any - or +,
     * "a.b.c" → a*1000000 + b*1000 + c (a part that isn't a number counts as 0). Same rule as tools/agapp.mjs.
     */
    fun versionCode(manifest: JSONObject): Long {
        if (manifest.has("version_code")) {
            val v = manifest.opt("version_code")
            return when {
                v is Int || v is Long -> (v as Number).toLong().takeIf { it >= 0 }
                v is Number -> v.toDouble().takeIf { it >= 0 && it == Math.floor(it) && it < 1e15 }?.toLong()
                v is String -> v.trim().takeIf { Regex("\\d{1,15}").matches(it) }?.toLong()
                else -> null
            } ?: throw MiniAppError("TypeError", "\"version_code\" must be a whole number (got $v)")
        }
        val p = manifest.optString("version").split('-', '+').first().split('.').map { it.trim().toLongOrNull() ?: 0L }
        return (p.getOrElse(0) { 0 } * 1_000_000) + (p.getOrElse(1) { 0 } * 1_000) + p.getOrElse(2) { 0 }
    }

    // ------------------------------------------------------------------ the developer key

    class DevKey(val developer: String, val publicKey: ByteArray, internal val privateKey: PrivateKey) {
        val fingerprint get() = fingerprint(publicKey)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mini_app_developer", Context.MODE_PRIVATE)

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun store(context: Context, developer: String, publicKey: ByteArray, pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val wrapped = c.iv + c.doFinal(pkcs8)
        prefs(context).edit()
            .putString("developer", developer)
            .putString("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .apply()
    }

    fun hasKey(context: Context) = prefs(context).contains("wrapped")

    /** The developer name and fingerprint of this phone's key, or null. */
    fun keyInfo(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val pub = p.getString("publicKey", null) ?: return null
        return p.getString("developer", "").orEmpty() to fingerprint(Base64.decode(pub, Base64.NO_WRAP))
    }

    fun loadKey(context: Context): DevKey? {
        val p = prefs(context)
        val wrapped = Base64.decode(p.getString("wrapped", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, wrapped, 0, 12)) }
        val pkcs8 = c.doFinal(wrapped, 12, wrapped.size - 12)
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        return DevKey(p.getString("developer", "").orEmpty(), Base64.decode(p.getString("publicKey", ""), Base64.NO_WRAP), priv)
    }

    fun createKey(context: Context, developer: String): DevKey {
        if (developer.isBlank()) throw MiniAppError("TypeError", "A developer name is needed")
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
        val kp = gen.generateKeyPair()
        store(context, developer.trim().take(80), kp.public.encoded, kp.private.encoded)
        return loadKey(context)!!
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): SecretKey =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded, "AES")

    /** A password-protected backup of the key (.agkey), for a PC (agapp.mjs) or another phone. */
    fun exportKey(context: Context, password: String): JSONObject {
        if (password.length < 8) throw MiniAppError("TypeError", "Use a password of at least 8 characters")
        val key = loadKey(context) ?: throw MiniAppError("NotFoundError", "No developer key yet")
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val iterations = 210_000
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, pbkdf2(password.toCharArray(), salt, iterations), GCMParameterSpec(128, iv)) }
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return JSONObject().put("format", 1).put("developer", key.developer).put("publicKey", b64(key.publicKey))
            .put("kdf", "PBKDF2-SHA256").put("iterations", iterations).put("salt", b64(salt))
            .put("cipher", "AES-256-GCM").put("iv", b64(iv)).put("data", b64(c.doFinal(key.privateKey.encoded)))
    }

    fun importKey(context: Context, agkey: JSONObject, password: String): DevKey {
        val d = { k: String -> Base64.decode(agkey.getString(k), Base64.NO_WRAP) }
        val pkcs8 = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, pbkdf2(password.toCharArray(), d("salt"), agkey.optInt("iterations", 210_000)), GCMParameterSpec(128, d("iv")))
            }.doFinal(d("data"))
        }.getOrElse { throw MiniAppError("NotAllowedError", "Wrong password, or not an .agkey file") }
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        val pub = d("publicKey")
        // the private key must belong to the public key in the file
        val probe = "agkey".toByteArray()
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(pub)); update(probe)
            verify(Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(probe); sign() })
        }
        if (!ok) throw MiniAppError("DataError", "The key file is damaged")
        store(context, agkey.optString("developer").ifBlank { "Developer" }, pub, pkcs8)
        return loadKey(context)!!
    }

    private fun publicKey(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    // ------------------------------------------------------------------ packages

    /** Not packaged: dot files and folders, node_modules, and a top-level META-INF (where the signature goes). */
    private fun skip(name: String, topLevel: Boolean) = name.startsWith(".") || name == "node_modules" || (topLevel && name == "META-INF")

    /** The files of an app folder that go into a package: relative path → file. */
    fun packageFiles(folder: File): Map<String, File> {
        val out = sortedMapOf<String, File>()
        fun walk(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                if (skip(f.name, prefix.isEmpty()) || MiniAppPermissions.isSymlink(f)) return@forEach
                if (f.isDirectory) walk(f, "$prefix${f.name}/") else out["$prefix${f.name}"] = f
            }
        }
        walk(folder, "")
        return out
    }

    /** Signs [folder] (a development app) with this phone's developer key into [out]. */
    fun buildPackage(context: Context, folder: File, out: File): JSONObject {
        val key = loadKey(context) ?: throw MiniAppError("NotFoundError", "Create a developer key first")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        val id = manifest.optString("id")
        if (!ID_RE.matches(id)) throw MiniAppError("TypeError", "manifest.json has no valid id")
        val files = packageFiles(folder)
        val digest = JSONObject().put("format", 1).put("id", id).put("version", manifest.optString("version"))
            .put("versionCode", versionCode(manifest))
            .put("files", JSONObject().apply { files.forEach { (path, f) -> put(path, f.inputStream().use { sha256Hex(it) }) } })
            .put("signedAt", System.currentTimeMillis())
        val digestBytes = digest.toString(2).toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key.privateKey); update(digestBytes); sign() }
        val cert = JSONObject().put("format", 1).put("algorithm", "ECDSA-P256-SHA256")
            .put("publicKey", Base64.encodeToString(key.publicKey, Base64.NO_WRAP)).put("developer", key.developer)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            files.forEach { (path, f) -> z.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            z.putNextEntry(ZipEntry(DIGEST)); z.write(digestBytes); z.closeEntry()
            z.putNextEntry(ZipEntry(CERT)); z.write(cert.toString(2).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry(SIG)); z.write(Base64.encodeToString(sig, Base64.NO_WRAP).toByteArray()); z.closeEntry()
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        return JSONObject().put("path", out.path).put("id", id).put("version", manifest.optString("version"))
            .put("versionCode", versionCode(manifest)).put("developer", key.developer).put("fingerprint", shortFingerprint(key.fingerprint))
    }

    /** A verified package: everything below is covered by the developer's signature. */
    class Verified(
        val id: String, val version: String, val versionCode: Long,
        val developer: String, val publicKey: String, val fingerprint: String,
        val manifest: JSONObject, val files: Map<String, String>,
    )

    /** Safe relative path inside a package (no absolute paths, no "..", no backslashes). */
    fun safePath(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') && name.split('/').none { it == ".." || it == "." }

    /**
     * Checks a package: signature over AGAPP.json, every file's hash, no missing or extra files, safe paths, and the
     * manifest inside agreeing with what was signed. Throws MiniAppError("SecurityError" / "DataError") otherwise.
     */
    fun verify(file: File): Verified {
        val zip = runCatching { ZipFile(file) }.getOrElse { throw MiniAppError("DataError", "Not an .agapp package (not a zip)") }
        zip.use { z ->
            fun bytes(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
            val digestBytes = bytes(DIGEST) ?: throw MiniAppError("SecurityError", "Not signed: META-INF/AGAPP.json is missing")
            val cert = bytes(CERT)?.let { JSONObject(String(it)) } ?: throw MiniAppError("SecurityError", "Not signed: the certificate is missing")
            val sig = bytes(SIG)?.let { Base64.decode(String(it).trim(), Base64.DEFAULT) } ?: throw MiniAppError("SecurityError", "Not signed: the signature is missing")
            if (cert.optString("algorithm") != "ECDSA-P256-SHA256") throw MiniAppError("SecurityError", "Unknown signature algorithm")
            val spki = Base64.decode(cert.optString("publicKey"), Base64.DEFAULT)
            val ok = runCatching { Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(spki)); update(digestBytes); verify(sig) } }.getOrDefault(false)
            if (!ok) throw MiniAppError("SecurityError", "The signature is not valid")
            val digest = JSONObject(String(digestBytes))
            val listed = digest.optJSONObject("files") ?: throw MiniAppError("SecurityError", "The signed file list is missing")
            val files = LinkedHashMap<String, String>()
            listed.keys().forEach { files[it] = listed.getString(it) }
            val seen = HashSet<String>()
            for (e in z.entries()) {
                val name = e.name
                if (!safePath(name)) throw MiniAppError("SecurityError", "Unsafe path in package: $name")
                if (e.isDirectory || name.startsWith("META-INF/")) continue
                val expected = files[name] ?: throw MiniAppError("SecurityError", "$name is not covered by the signature")
                if (z.getInputStream(e).use { sha256Hex(it) } != expected) throw MiniAppError("SecurityError", "$name was changed after signing")
                seen += name
            }
            files.keys.firstOrNull { it !in seen }?.let { throw MiniAppError("SecurityError", "$it is missing from the package") }
            val manifest = bytes("manifest.json")?.let { JSONObject(String(it)) } ?: throw MiniAppError("DataError", "manifest.json is missing")
            val id = digest.optString("id")
            if (!ID_RE.matches(id) || manifest.optString("id") != id) throw MiniAppError("SecurityError", "The manifest id doesn't match the signed id")
            if (manifest.optString("version") != digest.optString("version")) throw MiniAppError("SecurityError", "The manifest version doesn't match the signed version")
            val manifestCode = runCatching { versionCode(manifest) }.getOrElse { throw MiniAppError("DataError", it.message ?: "Invalid version_code") }
            if (digest.has("versionCode") && digest.optLong("versionCode", -1) != manifestCode) throw MiniAppError("SecurityError", "The signed versionCode doesn't match the manifest")
            return Verified(id, digest.optString("version"), manifestCode,
                cert.optString("developer").ifBlank { "Unknown developer" }, cert.optString("publicKey"), fingerprint(spki), manifest, files)
        }
    }
}

/**
 * Backups of the developer key. The key itself lives in AntiGem's private storage, which is wiped when AntiGem is
 * uninstalled or its data is cleared: without a backup every app signed with it could never be updated again. A backup
 * is the password-protected .agkey, saved to the phone's public Download/AntiGem folder (it outlives AntiGem) and offered
 * to the share sheet (Drive, email…). The password is typed only on the phone.
 */
object MiniAppKeyBackup {
    class Info(val path: String, val at: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("mini_apps", Context.MODE_PRIVATE)

    /** The last backup of the current key, or null when it was never backed up (a backup of an older key doesn't count). */
    fun info(context: Context): Info? {
        val fp = MiniAppSigning.keyInfo(context)?.second ?: return null
        val p = prefs(context)
        if (p.getString("key_backup_fp", null) != fp) return null
        return Info(p.getString("key_backup_path", "").orEmpty(), p.getLong("key_backup_at", 0))
    }

    fun needed(context: Context) = MiniAppSigning.hasKey(context) && info(context) == null

    /** An imported key came from a backup file: that file is its backup. */
    fun markImported(context: Context) {
        val fp = MiniAppSigning.keyInfo(context)?.second ?: return
        prefs(context).edit().putString("key_backup_fp", fp).putString("key_backup_path", "the .agkey file it was imported from")
            .putLong("key_backup_at", System.currentTimeMillis()).apply()
    }

    /** Saves the encrypted key to Download/AntiGem; returns the shown path and a private copy for the share sheet. */
    fun save(context: Context, password: String): Pair<String, File> {
        val (developer, fp) = MiniAppSigning.keyInfo(context) ?: throw MiniAppError("NotFoundError", "There is no developer key")
        val bytes = MiniAppSigning.exportKey(context, password).toString(2).toByteArray()
        val safe = developer.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(30).ifBlank { "developer" }
        val name = "antigem-key-$safe-${fp.take(8).lowercase()}.agkey"
        val shown: String = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/AntiGem")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = context.contentResolver
            val uri = r.insert(android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw MiniAppError("InvalidStateError", "Couldn't create the file in Download/AntiGem")
            r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw MiniAppError("InvalidStateError", "Couldn't write Download/AntiGem/$name")
            values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, values, null, null)
            // the name it really got (Android adds " (1)" when one exists)
            val real = r.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            "Download/AntiGem/$real"
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AntiGem").also { it.mkdirs() }
            var f = File(dir, name); var n = 1
            while (f.exists()) f = File(dir, name.removeSuffix(".agkey") + " (${n++}).agkey")
            f.writeBytes(bytes)
            "Download/AntiGem/${f.name}"
        }
        val copy = File(context.cacheDir, "key-backup").also { it.deleteRecursively(); it.mkdirs() }.let { File(it, name) }
        copy.writeBytes(bytes)
        prefs(context).edit().putString("key_backup_fp", fp).putString("key_backup_path", shown).putLong("key_backup_at", System.currentTimeMillis()).apply()
        return shown to copy
    }
}
