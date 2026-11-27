package ai.antigem.data.privatespace

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Private Space's cryptography, all in software except the phone's security chip keys ([SpaceHardwareKeys]).
 *
 * - The space key: 32 random bytes; everything in the space is encrypted with it (AES-256-GCM).
 * - The master password is stretched with Argon2id (memory-hard: slow and costly to guess, even with graphics cards)
 *   into a key that encrypts ("wraps") the space key. The recovery key wraps it too (HKDF: it is 160 random bits).
 * - Every encrypted part names the space it belongs to (GCM associated data), so parts can't be swapped between files.
 */
internal object SpaceCrypto {
    private val random = SecureRandom()

    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Argon2id cost for new spaces (stored in the file, so it can be raised later without breaking old files). */
    const val ARGON_MEMORY_KIB = 64 * 1024
    const val ARGON_ITERATIONS = 3
    const val ARGON_PARALLELISM = 1
    /** The most a space or backup file may ask for (room to raise the cost later, not enough to run a phone out of memory). */
    const val MAX_MEMORY_KIB = 256 * 1024
    const val MAX_ITERATIONS = 10

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    /** AES-256-GCM with a fresh random IV: {"iv","ct"}. */
    fun seal(key: ByteArray, plain: ByteArray, aad: String): JSONObject {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return JSONObject().put("iv", b64(iv)).put("ct", b64(cipher.doFinal(plain)))
    }

    /** Opens [seal]'s box; throws javax.crypto.AEADBadTagException when the key is wrong or the box was changed. */
    fun open(key: ByteArray, box: JSONObject, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, unb64(box.getString("iv"))))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(unb64(box.getString("ct")))
    }

    data class KdfParams(val salt: ByteArray, val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", "argon2id")
            .put("salt", b64(salt))
            .put("memKiB", memoryKiB)
            .put("iter", iterations)
            .put("par", parallelism)

        companion object {
            fun fresh() = KdfParams(randomBytes(16), ARGON_MEMORY_KIB, ARGON_ITERATIONS, ARGON_PARALLELISM)

            fun fromJson(o: JSONObject): KdfParams {
                require(o.optString("name") == "argon2id") { "Unknown key derivation \"${o.optString("name")}\"" }
                val memory = o.getInt("memKiB")
                val iterations = o.getInt("iter")
                val parallelism = o.getInt("par")
                // a crafted file (a backup, or space.json changed by something on the phone) could ask for gigabytes or
                // minutes and crash the app: only costs a phone can carry are accepted (new spaces use 64 MiB, 3 passes)
                require(memory in 8 * 1024..MAX_MEMORY_KIB && iterations in 1..MAX_ITERATIONS && parallelism in 1..8) { "Bad key derivation cost" }
                return KdfParams(unb64(o.getString("salt")), memory, iterations, parallelism)
            }
        }
    }

    /** The master password → a 32-byte key (Argon2id). Takes about a second on purpose. */
    fun passwordKey(password: CharArray, kdf: KdfParams): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(kdf.salt)
            .withMemoryAsKB(kdf.memoryKiB)
            .withIterations(kdf.iterations)
            .withParallelism(kdf.parallelism)
            .build()
        val out = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        return out
    }

    /** HKDF-SHA256 (RFC 5869): for keys made from high-entropy input (the recovery key, the chip's PIN answer). */
    fun hkdf(input: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(input)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            expand.update(previous)
            expand.update(info.toByteArray(Charsets.UTF_8))
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val n = minOf(previous.size, length - done)
            System.arraycopy(previous, 0, out, done, n)
            done += n
            counter++
        }
        prk.fill(0)
        return out
    }

    // ---------------- recovery key ----------------

    /** Crockford base32: no I, L, O, U, so it is easy to write down and read back. */
    private const val RECOVERY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val RECOVERY_CHARS = 32 // 160 bits

    /** A new recovery key: 160 random bits as 8 groups of 4, like "K7QM-3XTA-…". */
    fun newRecoveryKey(): String {
        val chars = CharArray(RECOVERY_CHARS) { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }
        return chars.toList().chunked(4).joinToString("-") { it.joinToString("") }
    }

    /** The recovery key as typed (any case, spaces, dashes, O for 0, I/L for 1) in its plain form, or null if it can't be one. */
    fun normalizeRecoveryKey(typed: String): String? {
        val plain = typed.uppercase()
            .filter { !it.isWhitespace() && it != '-' }
            .map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }
            .joinToString("")
        if (plain.length != RECOVERY_CHARS || plain.any { it !in RECOVERY_ALPHABET }) return null
        return plain
    }

    fun recoveryKeyBytes(normalized: String, salt: ByteArray): ByteArray =
        hkdf(normalized.toByteArray(Charsets.US_ASCII), salt, "antigem.privatespace.recovery.v1")
}
