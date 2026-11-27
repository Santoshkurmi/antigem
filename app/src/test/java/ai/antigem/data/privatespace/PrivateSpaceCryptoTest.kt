package ai.antigem.data.privatespace

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private Space's crypto and helpers against the RFC test vectors, and its merge / PIN / password rules. */
class PrivateSpaceCryptoTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun argon2id_matchesRfc9106Vector() {
        // RFC 9106 section 5.3 (Argon2id): proves the bundled BouncyCastle computes real Argon2id v1.3
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(32)
            .withParallelism(4)
            .withSalt(ByteArray(16) { 2 })
            .withSecret(ByteArray(8) { 3 })
            .withAdditional(ByteArray(12) { 4 })
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 1 }, out)
        assertArrayEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), out)
    }

    @Test
    fun passwordKey_isStableAndDependsOnPasswordAndSalt() {
        val kdf = SpaceCrypto.KdfParams(ByteArray(16) { it.toByte() }, 1024, 2, 1)
        val a = SpaceCrypto.passwordKey("Purple-Tiger-Rides-42-Boats!".toCharArray(), kdf)
        val b = SpaceCrypto.passwordKey("Purple-Tiger-Rides-42-Boats!".toCharArray(), kdf)
        val c = SpaceCrypto.passwordKey("Purple-Tiger-Rides-42-Boats?".toCharArray(), kdf)
        val d = SpaceCrypto.passwordKey("Purple-Tiger-Rides-42-Boats!".toCharArray(), kdf.copy(salt = ByteArray(16) { 9 }))
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertFalse(a.contentEquals(d))
    }

    @Test
    fun hkdf_matchesRfc5869Case3() {
        // RFC 5869 A.3: SHA-256, no salt, no info, 42 bytes
        val okm = SpaceCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), "", 42)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun recoveryKey_formatAndForgivingInput() {
        val key = SpaceCrypto.newRecoveryKey()
        assertTrue(Regex("^([0-9A-Z]{4}-){7}[0-9A-Z]{4}$").matches(key))
        val plain = SpaceCrypto.normalizeRecoveryKey(key)!!
        assertEquals(key.replace("-", ""), plain)
        // typed in lower case, with spaces, O for 0 and l for 1
        val typed = plain.lowercase().replace('0', 'o').replace('1', 'l').chunked(4).joinToString(" ")
        assertEquals(plain, SpaceCrypto.normalizeRecoveryKey(typed))
        assertNull(SpaceCrypto.normalizeRecoveryKey("ABCD-EFGH"))
        assertNull(SpaceCrypto.normalizeRecoveryKey(plain.dropLast(1) + "U"))
        // the same key always makes the same wrapping key for a salt
        val salt = ByteArray(16) { 7 }
        assertArrayEquals(SpaceCrypto.recoveryKeyBytes(plain, salt), SpaceCrypto.recoveryKeyBytes(SpaceCrypto.normalizeRecoveryKey(typed)!!, salt))
    }

    @Test
    fun totp_matchesRfc6238Sha1Vectors() {
        // RFC 6238 appendix B, SHA-1 secret "12345678901234567890"; a plain base32 secret gives 6 digits (the last 6)
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("287082", Totp.code(secret, 59_000L)!!.code)
        assertEquals("081804", Totp.code(secret, 1_111_111_109_000L)!!.code)
        assertEquals("050471", Totp.code(secret, 1_111_111_111_000L)!!.code)
        assertEquals("005924", Totp.code(secret, 1_234_567_890_000L)!!.code)
        assertEquals("279037", Totp.code(secret, 2_000_000_000_000L)!!.code)
        // spaces and lower case as sites show it
        assertEquals("287082", Totp.code("gezd gnbv gy3t qojq gezd gnbv gy3t qojq", 59_000L)!!.code)
        assertEquals(1, Totp.code(secret, 59_000L)!!.secondsLeft)
        assertNull(Totp.code("not base32 !!", 59_000L))
    }

    @Test
    fun pinRules() {
        assertTrue(PasswordTools.pinOk("482916".toCharArray()))
        assertTrue(PasswordTools.pinOk("90210457".toCharArray()))
        assertFalse(PasswordTools.pinOk("12345".toCharArray()))
        assertFalse(PasswordTools.pinOk("111111".toCharArray()))
        assertFalse(PasswordTools.pinOk("123456".toCharArray()))
        assertFalse(PasswordTools.pinOk("987654".toCharArray()))
        assertFalse(PasswordTools.pinOk("12a456".toCharArray()))
        assertFalse(PasswordTools.pinOk("1234567890123".toCharArray()))
        // the patterns people pick most
        assertFalse(PasswordTools.pinOk("121212".toCharArray())) // repeated block
        assertFalse(PasswordTools.pinOk("123123".toCharArray()))
        assertFalse(PasswordTools.pinOk("12341234".toCharArray()))
        assertFalse(PasswordTools.pinOk("123321".toCharArray())) // mirror
        assertFalse(PasswordTools.pinOk("112233".toCharArray())) // doubled run
        assertFalse(PasswordTools.pinOk("998877".toCharArray()))
        assertFalse(PasswordTools.pinOk("100100".toCharArray())) // two digits only
        assertFalse(PasswordTools.pinOk("147258".toCharArray())) // keypad line
        assertFalse(PasswordTools.pinOk("159753".toCharArray()))
        assertTrue(PasswordTools.pinOk("538174".toCharArray()))
        assertTrue(PasswordTools.pinOk("70394182".toCharArray()))
    }

    @Test
    fun masterPasswordRules() {
        assertTrue(PasswordTools.masterPasswordOk("Purple-Tiger-Rides-42-Boats!".toCharArray()))
        assertTrue(PasswordTools.masterPasswordOk("k8#Vq2!mZr7@xL".toCharArray()))
        assertFalse(PasswordTools.masterPasswordOk("password1234".toCharArray()))
        assertFalse(PasswordTools.masterPasswordOk("aaaaaaaaaaaaaaaa".toCharArray()))
        assertFalse(PasswordTools.masterPasswordOk("abcdefghijklmnop".toCharArray()))
        assertFalse(PasswordTools.masterPasswordOk("Short-1!".toCharArray()))
    }

    @Test
    fun generator_respectsOptions() {
        repeat(50) {
            val pw = PasswordTools.generate(PasswordTools.GeneratorOptions(length = 24, symbols = false, avoidSimilar = true))
            assertEquals(24, pw.length)
            assertTrue(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() } && pw.any { it.isDigit() })
            assertTrue(pw.all { it.isLetterOrDigit() })
            assertTrue(pw.none { it in "il1Lo0O" })
        }
    }

    @Test
    fun merge_byIdNewerWins() {
        val a = SpaceItem(id = "a", title = "old A", updatedAt = 100)
        val b = SpaceItem(id = "b", title = "mine B", updatedAt = 500)
        val c = SpaceItem(id = "c", title = "only mine", updatedAt = 10)
        val incoming = listOf(
            SpaceItem(id = "a", title = "new A", updatedAt = 200), // newer: replaces
            SpaceItem(id = "b", title = "old B", updatedAt = 400), // older: mine stays
            SpaceItem(id = "d", title = "only theirs", updatedAt = 1) // new id: added
        )
        val (merged, result) = mergeSpaceItems(listOf(a, b, c), incoming)
        assertEquals(MergeResult(added = 1, updated = 1, unchanged = 1), result)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
        assertEquals("new A", merged.first { it.id == "a" }.title)
        assertEquals("mine B", merged.first { it.id == "b" }.title)
        assertNotNull(merged.firstOrNull { it.id == "d" })
    }
}
