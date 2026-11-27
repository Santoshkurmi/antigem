package ai.antigem.ui.privatespace

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import javax.crypto.Cipher

/**
 * The system's fingerprint dialog (Android 9+), bound to a security-chip key's Cipher: the chip only lets the Cipher
 * work after this dialog saw a real, strong-class fingerprint (or face) of the phone's owner.
 */
internal object SpaceBiometricPrompt {
    fun show(
        activity: Activity,
        cipher: Cipher,
        title: String,
        subtitle: String?,
        negativeText: String,
        onSuccess: (Cipher) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onError(null)
            return
        }
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(negativeText, executor) { _, _ -> onError(null) }
        if (subtitle != null) builder.setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val prompt = try {
            builder.build()
        } catch (e: Exception) {
            onError(e.message)
            return
        }
        try {
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null) onSuccess(c) else onError(null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onError(if (cancelled) null else errString?.toString())
                }
                // onAuthenticationFailed (a finger that isn't enrolled): the dialog says so and lets the user try again
            })
        } catch (e: Exception) {
            onError(e.message)
        }
    }
}
