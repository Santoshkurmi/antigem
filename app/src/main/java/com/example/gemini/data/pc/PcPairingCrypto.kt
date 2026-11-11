package com.example.gemini.data.pc

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Connect to PC pairing: the phone and the PC prove to each other that they know the 6-digit code shown on the PC
 * without ever sending it, using SPAKE2 (the same idea as Android's "Pair device with pairing code"). Someone else
 * on the Wi-Fi who watches or even answers the exchange learns nothing they could test codes against offline: every
 * guess costs them one live attempt, and the PC allows only a few per code.
 *
 * Spec (the PC side, in the VS Code extension, must match it byte for byte): docs/connect-to-pc-protocol.md.
 *  - Group: RFC 3526 2048-bit MODP group 14, p, g = 2, working in the subgroup of order q = (p - 1) / 2.
 *  - M, N: SHA-256 expansion of "antigem-pair-v1 M" / "antigem-pair-v1 N" (bytes i = 0..8 appended) mod p, squared.
 *  - w = SHA-256("antigem-pair-v1 w" + code) mod q.
 *  - Phone: X = g^x · M^w.  PC: Y = g^y · N^w.  Shared Z = (Y / N^w)^x = (X / M^w)^y.
 *  - TT = "antigem-pair-v1", phoneId, X, Y, Z, w, each as a 4-byte big-endian length + bytes (group values 256 bytes).
 *  - K = SHA-256(TT); keys = HMAC-SHA256(K, label); confirmations = HMAC-SHA256(confirm key, TT).
 *  - Boxes: AES-256-GCM, base64(12-byte nonce + ciphertext + 16-byte tag), additional data "antigem-pair-v1".
 */
object PcPairingCrypto {

    const val LABEL = "antigem-pair-v1"

    private val P = BigInteger(
        ("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF"),
        16
    )
    private val Q = P.subtract(BigInteger.ONE).shiftRight(1)
    private val G = BigInteger.valueOf(2)
    private const val ELEMENT_BYTES = 256

    private val M = hashToGroup("$LABEL M")
    private val N = hashToGroup("$LABEL N")

    private val random = SecureRandom()

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** An element of the order-q subgroup nobody knows the discrete log of (hash, then square). */
    private fun hashToGroup(seed: String): BigInteger {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0..8) out.write(sha256(seed.toByteArray(Charsets.UTF_8), byteArrayOf(i.toByte())))
        val v = BigInteger(1, out.toByteArray()).mod(P)
        return v.modPow(BigInteger.valueOf(2), P)
    }

    /** The code as a number mod q (the same on both sides). */
    fun passwordScalar(code: String): BigInteger {
        val w = BigInteger(1, sha256("$LABEL w".toByteArray(Charsets.UTF_8), code.trim().toByteArray(Charsets.UTF_8))).mod(Q)
        return if (w.signum() == 0) BigInteger.ONE else w
    }

    fun toBytes(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(ELEMENT_BYTES)
        val src = if (raw.size > ELEMENT_BYTES) raw.copyOfRange(raw.size - ELEMENT_BYTES, raw.size) else raw
        System.arraycopy(src, 0, out, ELEMENT_BYTES - src.size, src.size)
        return out
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun fromHex(s: String): ByteArray {
        val t = s.trim()
        require(t.length % 2 == 0 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** A group value from the other side: in range and in the order-q subgroup (rejects small-subgroup tricks). */
    private fun checkElement(v: BigInteger) {
        require(v > BigInteger.ONE && v < P.subtract(BigInteger.ONE)) { "bad pairing value" }
        require(v.modPow(Q, P) == BigInteger.ONE) { "bad pairing value" }
    }

    /** The phone's half of one pairing attempt. */
    class PhoneSide(code: String, private val phoneId: String) {
        private val w = passwordScalar(code)
        private val x: BigInteger = run {
            var r: BigInteger
            do { r = BigInteger(Q.bitLength(), random) } while (r.signum() == 0 || r >= Q)
            r
        }
        /** X = g^x · M^w, sent to the PC (hex). */
        val xHex: String = toHex(toBytes(G.modPow(x, P).multiply(M.modPow(w, P)).mod(P)))

        /** Keys of this pairing, once the PC's Y is known. */
        fun finish(yHex: String): Keys {
            val y = BigInteger(1, fromHex(yHex))
            checkElement(y)
            val nw = N.modPow(w, P)
            val z = y.multiply(nw.modInverse(P)).mod(P).modPow(x, P)
            val tt = transcript(phoneId, fromHex(xHex), toBytes(y), toBytes(z), toBytes(w))
            return Keys(tt, sha256(tt))
        }
    }

    fun transcript(phoneId: String, x: ByteArray, y: ByteArray, z: ByteArray, w: ByteArray): ByteArray {
        val parts = listOf(LABEL.toByteArray(Charsets.UTF_8), phoneId.toByteArray(Charsets.UTF_8), x, y, z, w)
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            out.write(ByteBuffer.allocate(4).putInt(p.size).array())
            out.write(p)
        }
        return out.toByteArray()
    }

    class Keys(private val tt: ByteArray, private val k: ByteArray) {
        private fun derive(label: String): ByteArray = hmac(k, label.toByteArray(Charsets.UTF_8))

        /** What the PC sends to prove it knows the code. */
        val pcConfirm: ByteArray get() = hmac(derive("confirm-pc"), tt)
        /** What the phone sends back to prove it knows the code. */
        val phoneConfirm: ByteArray get() = hmac(derive("confirm-phone"), tt)
        val phoneToPc: ByteArray get() = derive("enc-phone-to-pc")
        val pcToPhone: ByteArray get() = derive("enc-pc-to-phone")
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Constant-time comparison (a confirmation from the other side). */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun seal(key: ByteArray, plain: ByteArray): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(LABEL.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(nonce + c.doFinal(plain), android.util.Base64.NO_WRAP)
    }

    /** Opens a box from the other side (throws when it was changed or sealed with another key). */
    fun open(key: ByteArray, box: String): ByteArray {
        val raw = android.util.Base64.decode(box, android.util.Base64.DEFAULT)
        require(raw.size > 12 + 16) { "bad box" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(LABEL.toByteArray(Charsets.UTF_8))
        return c.doFinal(raw, 12, raw.size - 12)
    }
}
