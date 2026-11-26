package ai.antigem.data.media

import ai.antigem.domain.model.ChatAttachment
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures and voice notes that arrive inside a chat as base64 (an image pasted in a Claude / Codex / OpenCode chat,
 * an Antigravity attachment or generated image) are written once to the app cache, and the message keeps only the
 * file's path. A long chat full of screenshots then holds paths, not megabytes of text, in memory; Coil loads the file
 * on its own thread and keeps the decoded picture in its size-limited cache.
 *
 * - Same data, same file: the name is a hash of the bytes, so a chat reopened (or rebuilt) reuses its files.
 * - Recognising data seen before is cheap: its length, `hashCode()` and both ends are remembered, so the bytes are
 *   only decoded and hashed the first time in this process.
 * - Files are only trimmed when the app starts (oldest first, down to [MAX_BYTES]), never while a chat may show them;
 *   a file is marked as used whenever it is handed out again, so the ones in use are trimmed last.
 * - When a file cannot be written (storage full, not initialised) the caller keeps the base64, as before.
 */
object InlineMediaStore {
    const val DIR = "inline-media"
    private const val TAG = "InlineMediaStore"
    /** What the folder may hold after the trim at app start. */
    private const val MAX_BYTES = 256L * 1024 * 1024
    /** Length of each end of the base64 compared when data was seen before (with its length and hash). */
    private const val EDGE = 48

    @Volatile private var dir: File? = null

    private class Known(val head: String, val tail: String, val file: File)

    /** (length, hashCode) of base64 seen in this process → its file. */
    private val known = ConcurrentHashMap<Long, Known>()

    /** Sets the folder and trims it, off the main thread. Called once from the Application. */
    fun init(context: Context) {
        val d = File(context.cacheDir, DIR)
        dir = d
        Thread({ runCatching { d.mkdirs(); trim(d) } }, "inline-media-trim").start()
    }

    /**
     * The file holding the data of [base64] (written on first use), or null when it cannot be stored (then keep the
     * base64). [mime] picks the file's extension.
     */
    fun fileFor(base64: String, mime: String?): File? {
        val d = dir ?: return null
        if (base64.isBlank()) return null
        val key = (base64.length.toLong() shl 32) or (base64.hashCode().toLong() and 0xffffffffL)
        val head = base64.take(EDGE)
        val tail = base64.takeLast(EDGE)
        known[key]?.let { k ->
            if (k.head == head && k.tail == tail && k.file.isFile) return k.file
        }
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        val file = File(d, "${sha1(bytes)}.${extensionOf(mime)}")
        try {
            if (file.isFile && file.length() == bytes.size.toLong()) {
                file.setLastModified(System.currentTimeMillis())
            } else {
                d.mkdirs()
                val part = File(d, file.name + "." + Thread.currentThread().id + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) {
                    part.delete()
                    if (!file.isFile) return null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot store inline media: ${e.message}")
            return null
        }
        known[key] = Known(head, tail, file)
        return file
    }

    /**
     * The file holding [bytes] (written on first use; named after their hash), or null when it cannot be stored. For
     * data that arrives as bytes (a protobuf field), so it is never turned into base64 text at all.
     */
    fun fileForBytes(bytes: ByteArray, mime: String?): File? {
        val d = dir ?: return null
        if (bytes.isEmpty()) return null
        val file = File(d, "${sha1(bytes)}.${extensionOf(mime)}")
        return try {
            if (file.isFile && file.length() == bytes.size.toLong()) {
                file.setLastModified(System.currentTimeMillis())
            } else {
                d.mkdirs()
                val part = File(d, file.name + "." + Thread.currentThread().id + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) {
                    part.delete()
                    if (!file.isFile) return null
                }
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Cannot store inline media: ${e.message}")
            null
        }
    }

    /**
     * [attachment] with its base64 moved to a file: `localUri` points at the file and `base64` is gone. An attachment
     * the app is sending (a [ChatAttachment.reference] keeps its shrunk copy as data) or one without data comes back
     * as it is, as does one whose data cannot be stored.
     */
    fun stored(attachment: ChatAttachment): ChatAttachment {
        val b64 = attachment.base64
        if (b64.isNullOrBlank() || attachment.reference) return attachment
        val file = fileFor(b64, attachment.mimeType) ?: return attachment
        return attachment.copy(
            base64 = null,
            localUri = "file://${file.absolutePath}",
            size = if (attachment.size > 0) attachment.size else file.length()
        )
    }

    /** [attachments] with every base64 moved to a file (see [stored]); the same list when none has data. */
    fun stored(attachments: List<ChatAttachment>): List<ChatAttachment> {
        if (attachments.none { !it.base64.isNullOrBlank() && !it.reference }) return attachments
        return attachments.map { stored(it) }
    }

    /** A `data:<mime>;base64,…` uri as a `file://` uri of its stored data, or [dataUri] itself when it cannot be stored. */
    fun storedDataUri(dataUri: String): String {
        if (!dataUri.startsWith("data:")) return dataUri
        val comma = dataUri.indexOf(";base64,")
        if (comma < 0) return dataUri
        val mime = dataUri.substring(5, comma)
        val file = fileFor(dataUri.substring(comma + 8), mime) ?: return dataUri
        return "file://${file.absolutePath}"
    }

    /**
     * The base64 of [attachment] to send it again: its own data, or the data of the file it was stored in. Null when
     * it has neither.
     */
    fun base64Of(attachment: ChatAttachment): String? {
        attachment.base64?.takeIf { it.isNotBlank() }?.let { return it }
        val uri = attachment.localUri?.takeIf { it.startsWith("file://") } ?: return null
        val file = File(uri.removePrefix("file://"))
        if (file.parentFile?.name != DIR || !file.isFile) return null
        return runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull()
    }

    private fun extensionOf(mime: String?): String {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (m) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg" -> "ogg"
            "application/pdf" -> "pdf"
            else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(m)?.takeIf { it.isNotBlank() } ?: "bin"
        }
    }

    private fun sha1(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Unfinished writes go; then the oldest files until the folder fits [MAX_BYTES]. */
    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        var total = 0L
        files.filter { !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }.forEach { f ->
            total += f.length()
            if (total > MAX_BYTES) f.delete()
        }
    }
}
