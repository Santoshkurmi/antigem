package com.example.gemini.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One file (or a shared piece of text) another app sent to AntiGem. */
data class IncomingItem(
    val uri: Uri?,
    val name: String,
    val mime: String,
    val size: Long,
    /** Set for plain text shared without a file (e.g. code copied from a chat app). */
    val text: String? = null
) {
    val kind: FileKind get() = if (text != null) FileKinds.of(name).takeIf { it.isTextual } ?: FileKind.TEXT else FileKinds.of(name).let {
        // a name without a known extension but a text MIME type is still text
        if (it == FileKind.BINARY && (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript"))) FileKind.TEXT else it
    }
}

/** Files shared to the app wait here until the user decides what to do with them (open in the IDE, save to a folder). */
object IncomingShare {
    private val _pending = MutableStateFlow<List<IncomingItem>?>(null)
    val pending: StateFlow<List<IncomingItem>?> = _pending.asStateFlow()

    fun clear() {
        _pending.value = null
    }

    fun offer(items: List<IncomingItem>) {
        if (items.isNotEmpty()) _pending.value = items
    }

    /** Uris carried by a VIEW / SEND / SEND_MULTIPLE intent. */
    fun urisOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        ).orEmpty()
        else -> emptyList()
    }

    fun describe(context: Context, uri: Uri, intentMime: String?): IncomingItem {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
        } else if (uri.scheme == "file") {
            uri.path?.let { p -> java.io.File(p).let { f -> name = f.name; if (f.isFile) size = f.length() } }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: intentMime ?: ""
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        var finalName = (name ?: fallbackName).replace('/', '_')
        // add an extension from the MIME type when the name has none, so the file opens with the right app later
        if (!finalName.contains('.') && mime.isNotBlank()) {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { finalName = "$finalName.$it" }
        }
        return IncomingItem(uri, finalName, mime, size)
    }

    fun textItem(text: String, subject: String?): IncomingItem {
        val looksLikeCode = Regex("""(^|\n)\s*(fun |def |class |import |#include|function |const |let |package |public |<\?php|<html)""").containsMatchIn(text)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val base = subject?.trim()?.replace(Regex("[^A-Za-z0-9._-]+"), "-")?.trim('-')?.take(40)?.ifBlank { null } ?: "shared-$stamp"
        val name = if (base.contains('.')) base else "$base.${if (looksLikeCode) "txt" else "md"}"
        return IncomingItem(uri = null, name = name, mime = "text/plain", size = text.toByteArray().size.toLong(), text = text)
    }
}

/** Requests for an open explorer to jump to a folder (e.g. "Show in Files" after saving). Key = explorer state key. */
object ExplorerNav {
    private val _request = MutableStateFlow<Pair<String, String>?>(null)
    val request: StateFlow<Pair<String, String>?> = _request.asStateFlow()

    fun open(stateKey: String, path: String) {
        _request.value = stateKey to path
    }

    fun consume() {
        _request.value = null
    }
}
