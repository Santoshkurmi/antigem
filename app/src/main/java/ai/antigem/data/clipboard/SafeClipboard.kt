package ai.antigem.data.clipboard

import ai.antigem.i18n.L
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.nativeClipboardManager
import java.text.NumberFormat

/**
 * Every copy in the app goes through here. Android hands the copied text to the clipboard service in one binder call,
 * and a binder call can carry at most about 1 MB (shared with the app's other calls in flight); text travels as UTF-16,
 * 2 bytes a character. A bigger copy throws TransactionTooLargeException and closed the app (copying an AGY reply of
 * about 520,000 characters). Text over [MAX_CHARS] is not copied: the user gets an error toast instead, and so does any
 * other failure of the clipboard.
 */
object SafeClipboard {
    private const val TAG = "SafeClipboard"

    /** At most this many characters are copied: about 500 KB on the binder call, half of its limit. */
    const val MAX_CHARS = 250_000

    // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the plain name works on older SDK levels to compile against
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Whether [text] is small enough to copy, for a screen that shows its own message instead. */
    fun fits(text: CharSequence): Boolean = text.length <= MAX_CHARS

    /**
     * Copies [text] as plain text. [sensitive] marks it so Android 13+ hides it in the copy preview and keyboards leave
     * it out of their clipboard history. Returns false, after showing the error, when nothing was copied.
     */
    fun copy(context: Context, label: CharSequence, text: CharSequence, sensitive: Boolean = false): Boolean {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        return copy(context, clip)
    }

    /** Copies [clip] when its text fits; returns false, after showing the error, when nothing was copied. */
    fun copy(context: Context, clip: ClipData): Boolean {
        val chars = charsOf(clip)
        if (chars > MAX_CHARS) {
            val format = NumberFormat.getIntegerInstance()
            showError(context, L.common.tooBigToCopy(format.format(chars), format.format(MAX_CHARS)))
            return false
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            showError(context, L.common.couldNotCopy)
            return false
        }
        return try {
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            // also under the limit the call can fail (other binder calls in flight, a clipboard service that refuses)
            Log.w(TAG, "setPrimaryClip failed for $chars characters", e)
            showError(context, L.common.couldNotCopy)
            false
        }
    }

    /** The characters [clip] sends: its items' text and HTML. */
    private fun charsOf(clip: ClipData): Int {
        var total = 0L
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            total += (item.text?.length ?: 0) + (item.htmlText?.length ?: 0)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** A toast on the main thread: some copies come from a web page's script thread or a receiver. */
    private fun showError(context: Context, message: String) {
        val app = context.applicationContext ?: context
        val show = Runnable { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else main.post(show)
    }
}

/**
 * The app's Compose clipboard (provided in GeminiTheme): what Compose copies itself (selected text, a text field's Copy
 * or Cut) goes through [SafeClipboard] too, so selecting a huge reply and tapping Copy shows the error instead of
 * closing the app. Pasting and clearing go to the platform clipboard as usual.
 *
 * It must be an [AndroidClipboard]: Compose's text toolbar reads the system clipboard through `nativeClipboardManager`,
 * which throws for any other Clipboard.
 */
internal class SafeComposeClipboard(private val context: Context, private val platform: Clipboard) : AndroidClipboard {
    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clip = clipEntry?.clipData
        if (clip == null) platform.setClipEntry(clipEntry) else SafeClipboard.copy(context, clip)
    }

    override val clipboardManager: ClipboardManager get() = platform.nativeClipboardManager
}
