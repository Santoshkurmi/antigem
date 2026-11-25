package ai.antigem.data.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies a secret (a token, a login code, a secret header): marked sensitive, so Android 13+ hides it in the copy
 * preview and keyboards leave it out of their clipboard history. Older Android versions ignore the mark.
 */
object SecretClipboard {
    // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the plain name works on older SDK levels to compile against
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
    }
}
