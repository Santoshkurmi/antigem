package ai.antigem.data.privatespace

import android.net.Uri
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.log2

/** 2FA codes (TOTP, RFC 6238) from a base32 secret or an otpauth://totp/… link. */
object Totp {
    data class Spec(val secret: ByteArray, val digits: Int, val period: Int, val algorithm: String)

    data class Code(val code: String, val secondsLeft: Int, val period: Int)

    fun parse(value: String): Spec? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return if (text.startsWith("otpauth://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
            if (!uri.host.equals("totp", ignoreCase = true)) return null
            val secret = base32(uri.getQueryParameter("secret") ?: return null) ?: return null
            val digits = uri.getQueryParameter("digits")?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6
            val period = uri.getQueryParameter("period")?.toIntOrNull()?.takeIf { it in 10..300 } ?: 30
            val algorithm = when (uri.getQueryParameter("algorithm")?.uppercase()) {
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> "HmacSHA1"
            }
            Spec(secret, digits, period, algorithm)
        } else {
            base32(text)?.let { Spec(it, 6, 30, "HmacSHA1") }
        }
    }

    fun code(value: String, nowMillis: Long = System.currentTimeMillis()): Code? {
        val spec = parse(value) ?: return null
        val seconds = nowMillis / 1000
        val counter = seconds / spec.period
        val msg = ByteArray(8) { i -> (counter ushr (8 * (7 - i))).toByte() }
        val hash = try {
            Mac.getInstance(spec.algorithm).apply { init(SecretKeySpec(spec.secret, "RAW")) }.doFinal(msg)
        } catch (e: Exception) {
            return null
        }
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(spec.digits) { mod *= 10 }
        val code = (binary % mod).toString().padStart(spec.digits, '0')
        return Code(code, (spec.period - (seconds % spec.period)).toInt(), spec.period)
    }

    private fun base32(text: String): ByteArray? {
        val clean = text.uppercase().filter { !it.isWhitespace() && it != '-' }.trimEnd('=')
        if (clean.isEmpty()) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}

/** Random passwords and a strength estimate (for the master password rules and the strength bar). */
object PasswordTools {
    private val random = SecureRandom()
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"
    private const val SIMILAR = "il1Lo0O"

    data class GeneratorOptions(
        val length: Int = 20,
        val upper: Boolean = true,
        val lower: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidSimilar: Boolean = false
    )

    fun generate(o: GeneratorOptions): String {
        val sets = buildList {
            if (o.lower) add(LOWER)
            if (o.upper) add(UPPER)
            if (o.digits) add(DIGITS)
            if (o.symbols) add(SYMBOLS)
        }.map { s -> if (o.avoidSimilar) s.filterNot { it in SIMILAR } else s }.ifEmpty { listOf(LOWER) }
        val length = o.length.coerceIn(4, 128)
        val all = sets.joinToString("")
        // one of each chosen kind, the rest from all of them, then shuffled
        val chars = sets.take(length).map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < length) chars += all[random.nextInt(all.length)]
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return chars.joinToString("")
    }

    /** 0 very weak, 1 weak, 2 fair, 3 strong, 4 very strong; with the estimated bits of guessing work. */
    data class Strength(val score: Int, val bits: Int)

    private val common = listOf(
        "password", "passw0rd", "123456", "qwerty", "letmein", "welcome", "admin", "iloveyou", "monkey", "dragon",
        "abc123", "football", "baseball", "master", "sunshine", "princess", "shadow", "superman", "trustno1", "login",
        "starwars", "whatever", "freedom", "hello", "secret", "asdfgh", "zxcvbn", "111111", "000000", "computer"
    )

    fun strength(password: CharArray): Strength = strength(String(password))

    fun strength(password: String): Strength {
        if (password.isEmpty()) return Strength(0, 0)
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 33
        if (password.any { it.code >= 128 }) pool += 100
        // repeats and runs ("aaaa", "1234", "abcd") add little: they count as one character each
        var effective = 0.0
        var i = 0
        while (i < password.length) {
            var j = i + 1
            while (j < password.length && (password[j] == password[j - 1] || password[j].code - password[j - 1].code == 1 || password[j - 1].code - password[j].code == 1)) j++
            effective += if (j - i >= 3) 1.5 else (j - i).toDouble()
            i = j
        }
        var bits = effective * log2(pool.coerceAtLeast(2).toDouble())
        val lower = password.lowercase()
        common.forEach { w -> if (lower.contains(w)) bits -= w.length * 3.5 }
        val distinct = password.toSet().size
        if (distinct <= 3) bits = minOf(bits, 15.0)
        val b = bits.coerceAtLeast(0.0).toInt()
        val score = when {
            b < 28 -> 0
            b < 40 -> 1
            b < 60 -> 2
            b < 80 -> 3
            else -> 4
        }
        return Strength(score, b)
    }

    /** The master password rule: long and strong (it is the one thing protecting copies and backups of the space). */
    const val MASTER_MIN_LENGTH = 12

    fun masterPasswordOk(password: CharArray): Boolean =
        password.size >= MASTER_MIN_LENGTH && strength(password).score >= 3

    /**
     * The quick PIN rule: 6–12 digits and not one of the patterns people pick most (the first guesses of anyone trying
     * PINs): runs (123456, 987654), a repeated block (111111, 121212, 123123), a mirror (123321), only one or two
     * different digits, pairs in a run (112233), keypad lines (147258, 159753) and other well-known ones.
     */
    const val PIN_MIN = 6
    const val PIN_MAX = 12

    private val commonPins = setOf(
        "147258", "258369", "369258", "147369", "159357", "159753", "753951", "951753", "357159", "789456", "456123",
        "321654", "654987", "147852", "741852", "963852", "852963", "258456", "852456", "520520", "102030", "010203",
        "112358", "135790", "246810", "135791", "246802", "098765", "012345", "123654", "147896", "123789", "789123",
        "456789", "741963", "369147", "159951", "753159"
    )

    fun pinOk(pin: CharArray): Boolean {
        if (pin.size !in PIN_MIN..PIN_MAX || pin.any { it !in '0'..'9' }) return false
        val s = String(pin)
        if (s in commonPins) return false
        if (s.toSet().size < 3) return false
        val up = (1 until pin.size).all { pin[it] - pin[it - 1] == 1 }
        val down = (1 until pin.size).all { pin[it - 1] - pin[it] == 1 }
        if (up || down) return false
        // the same block over and over: 121212, 123123, 12341234
        if ((1..pin.size / 2).any { p -> pin.size % p == 0 && (p until pin.size).all { pin[it] == pin[it - p] } }) return false
        // a mirror: 123321, 1234554321
        if (pin.indices.all { pin[it] == pin[pin.size - 1 - it] }) return false
        // doubled digits in a run: 112233, 998877
        if (pin.size % 2 == 0) {
            val pairs = (pin.indices step 2).map { pin[it] to pin[it + 1] }
            if (pairs.all { it.first == it.second }) {
                val firsts = pairs.map { it.first }
                if ((1 until firsts.size).all { firsts[it] - firsts[it - 1] == 1 } || (1 until firsts.size).all { firsts[it - 1] - firsts[it] == 1 }) return false
            }
        }
        return true
    }
}
