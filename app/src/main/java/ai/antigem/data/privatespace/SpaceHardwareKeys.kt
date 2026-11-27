package ai.antigem.data.privatespace

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Keys that live inside the phone's security chip (Android Keystore: the TEE, or StrongBox on phones that have one).
 * Their key material never leaves the chip: not to the app, not to an agent in the terminal, not to root, not to a
 * copy of the files on a PC.
 *
 * - Fingerprint key (Android 9+): the chip only uses it right after a real fingerprint touch in the system's
 *   fingerprint dialog (BiometricPrompt with this key's Cipher), and it stops working for good when a fingerprint is
 *   added to the phone.
 * - PIN key (Android 12+): an HMAC key with a use limit the chip / Keystore enforces. Every PIN check needs one use of
 *   it, so whoever runs code as the app can only make [PIN_TRIES] guesses, then the key is gone and only the master
 *   password opens the space. A right PIN replaces the key with a fresh one (new tries).
 */
internal object SpaceHardwareKeys {
    private const val TAG = "SpaceHardwareKeys"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val BIO_ALIAS = "antigem_private_space_fingerprint"
    private const val PIN_ALIAS_PREFIX = "antigem_private_space_pin_"

    /** Wrong PINs allowed before the PIN stops working. */
    const val PIN_TRIES = 5

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    // ---------------- fingerprint ----------------

    enum class BioAvailability { AVAILABLE, NO_HARDWARE, NONE_ENROLLED, NO_SCREEN_LOCK, ANDROID_TOO_OLD }

    fun bioAvailability(context: Context): BioAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return BioAvailability.ANDROID_TOO_OLD
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return BioAvailability.NO_SCREEN_LOCK
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val bm = context.getSystemService(BiometricManager::class.java) ?: return BioAvailability.NO_HARDWARE
                when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                    BiometricManager.BIOMETRIC_SUCCESS -> BioAvailability.AVAILABLE
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BioAvailability.NONE_ENROLLED
                    else -> BioAvailability.NO_HARDWARE
                }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                val bm = context.getSystemService(BiometricManager::class.java) ?: return BioAvailability.NO_HARDWARE
                @Suppress("DEPRECATION")
                when (bm.canAuthenticate()) {
                    BiometricManager.BIOMETRIC_SUCCESS -> BioAvailability.AVAILABLE
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BioAvailability.NONE_ENROLLED
                    else -> BioAvailability.NO_HARDWARE
                }
            }
            else -> {
                // Android 9: no BiometricManager yet; a phone with a sensor is offered it (the dialog says if no finger is enrolled)
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) BioAvailability.AVAILABLE
                else BioAvailability.NO_HARDWARE
            }
        }
    }

    private fun newBioKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // every single use needs a fresh strong-biometric touch (no "unlocked for N seconds" window)
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** A new fingerprint key (the old one is replaced) and a Cipher ready to wrap the space key after the touch. */
    fun bioEncryptCipher(): Cipher {
        deleteBioKey()
        val key = try {
            newBioKey(strongBox = true)
        } catch (e: Exception) {
            // no StrongBox (StrongBoxUnavailableException), or a phone that claims one but fails to make the key there
            Log.w(TAG, "StrongBox fingerprint key failed, using the TEE: ${e.javaClass.simpleName}")
            deleteBioKey()
            newBioKey(strongBox = false)
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /**
     * A Cipher to unwrap the space key after the touch, or null if the key is gone. Throws
     * android.security.keystore.KeyPermanentlyInvalidatedException when a fingerprint was added since, and
     * [KeyTamperedException] when the key under this name isn't one Private Space made (see [bioKeyTrusted]).
     */
    fun bioDecryptCipher(iv: ByteArray): Cipher? {
        val key = keyStore().getKey(BIO_ALIAS, null) as? SecretKey ?: return null
        if (!bioKeyTrusted(key)) {
            deleteBioKey()
            throw KeyTamperedException()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }

    /** The key under a Private Space name isn't one Private Space made: something else running as the app replaced it. */
    class KeyTamperedException : Exception("Private Space key replaced outside AntiGem")

    private fun keyInfo(key: SecretKey): KeyInfo? = try {
        SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as? KeyInfo
    } catch (e: Exception) {
        Log.w(TAG, "Key info unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Whether the fingerprint key still needs a fresh strong fingerprint for every use. Anything running as the app (an
     * agent in the terminal) could delete it and make one under the same name that needs no finger (or works for a while
     * after any unlock), wrap a space key it knows with it, and swap in a space of its own: the next fingerprint "unlock"
     * would open that space and everything added to it would be readable by it. Such a key is refused. When the phone
     * can't describe the key, it is trusted (that can't be caused from outside).
     */
    private fun bioKeyTrusted(key: SecretKey): Boolean {
        val info = keyInfo(key) ?: return true
        if (!info.isUserAuthenticationRequired || !info.isInvalidatedByBiometricEnrollment) return false
        @Suppress("DEPRECATION")
        if (info.userAuthenticationValidityDurationSeconds > 0) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = info.userAuthenticationType
            if (type and KeyProperties.AUTH_BIOMETRIC_STRONG == 0 || type and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0) return false
        }
        return true
    }

    fun deleteBioKey() {
        runCatching { keyStore().deleteEntry(BIO_ALIAS) }
    }

    // ---------------- PIN ----------------

    /** The PIN's try limit has to be enforced by Keystore: Android 12+ only. */
    val pinSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** A new PIN key with [PIN_TRIES] + 1 uses (one goes to wrapping the space key right away). Returns its alias. */
    fun newPinKey(): String {
        check(pinSupported) { "The PIN needs Android 12 or newer" }
        val alias = PIN_ALIAS_PREFIX + SpaceCrypto.randomBytes(8).joinToString("") { "%02x".format(it) }
        fun make(strongBox: Boolean) {
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setMaxUsageCount(PIN_TRIES + 1)
            if (strongBox) builder.setIsStrongBoxBacked(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).apply { init(builder.build()) }.generateKey()
        }
        try {
            make(strongBox = true)
        } catch (e: Exception) {
            runCatching { keyStore().deleteEntry(alias) }
            make(strongBox = false)
        }
        return alias
    }

    /**
     * The chip's answer for this PIN (uses up one of the key's uses), or null when the key is gone (all tries used,
     * or it was deleted). Only the chip can compute it, so the PIN can't be guessed anywhere else.
     */
    fun pinAnswer(alias: String, salt: ByteArray, pin: CharArray): ByteArray? {
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            null
        } ?: return null
        val pinBytes = String(pin).toByteArray(Charsets.UTF_8)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            mac.update(salt)
            mac.doFinal(pinBytes)
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException: its uses ran out
            Log.w(TAG, "PIN key unusable: ${e.javaClass.simpleName}")
            null
        } finally {
            pinBytes.fill(0)
        }
    }

    /**
     * How many uses the chip still allows for this PIN key (Android 12+): the truth about tries left, whoever used
     * them. Something running as the app can call the key itself, past AntiGem's PIN pad; each such guess still uses
     * one of these, so tries the app didn't make show up as a gap ([PrivateSpace] warns about them).
     * Null when unknown (no such key, or the phone can't tell); [UNLIMITED] when the key has no use limit at all, which
     * Private Space never makes (a replaced key).
     */
    fun pinUsesLeft(alias: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            null
        } ?: return null
        val left = keyInfo(key)?.remainingUsageCount ?: return null
        return if (left == KeyProperties.UNRESTRICTED_USAGE_COUNT) UNLIMITED else left
    }

    const val UNLIMITED = -1

    fun deletePinKey(alias: String?) {
        if (alias.isNullOrBlank()) return
        runCatching { keyStore().deleteEntry(alias) }
    }

    /** Every key Private Space ever made (for "Delete Private Space"). */
    fun deleteAll() {
        runCatching {
            val ks = keyStore()
            ks.aliases().toList().filter { it == BIO_ALIAS || it.startsWith(PIN_ALIAS_PREFIX) }.forEach { ks.deleteEntry(it) }
        }
    }
}
