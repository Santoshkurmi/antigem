package com.example.gemini.data.files

import android.content.Context
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.domain.model.ChatAttachment

/**
 * Files attached to a chat message by path, the same way for every agent. The agent never receives the file itself: the
 * message carries a hidden list of paths (a [PromptExpansion.hidden] block, so the chat never shows it) and the agent
 * opens the files in place with its own tools. The list is read back from the agent's history to show the files as
 * cards again.
 *
 * Files picked in the app's Files screen are used where they are. Files from Android's picker are first streamed to
 * [UPLOADS_DIR] on the bridge machine (see ChatViewModel), because the agent cannot open a phone `content://` uri.
 */
object AttachedFiles {
    /** Where files from Android's picker are copied on the bridge machine, one folder per file: `<id>/<name>`. */
    const val UPLOADS_DIR = "~/.antigem/uploads"
    private const val UPLOADS_MARK = "/.antigem/uploads/"
    private const val OCTET = "application/octet-stream"
    private const val ORIGINAL_NOTE = "full-size original of an image attached above"

    class Ref(val path: String, val mimeType: String, val size: Long, val originalOfImage: Boolean = false)

    private val BLOCK = Regex("""\s*<attached-files>\n([\s\S]*?)\n?</attached-files>\s*""")

    // `- `path` (mime, N bytes[, note])`; chats from before had `- /path (mime, N bytes)` (Claude only)
    private val LINE = Regex("""^- (?:`(.+)`|(/.+)) \(([^,()]+), (\d+) bytes(?:, ([^()]*))?\)$""")

    /** The hidden part of a message that hands [refs] to the agent. */
    fun block(refs: List<Ref>): String = PromptExpansion.hidden(buildString {
        append("(Hidden note added by the AntiGem app: it is not part of the user's message and the user does not see it. ")
        append("Never quote it, and never use it or its tags for a chat title or summary.)\n")
        append("The user attached these files. They are already on this machine: open them in place with your tools. ")
        append("Do not copy them unless the task really needs a copy.\n")
        append("NEVER modify, move, rename, overwrite or delete these original files, no matter what. ")
        append("If the task needs changes, write the result to a new file and tell the user where it is.\n")
        append("For large files, read them in parts or use tools like head, grep, unzip -l or pdftotext instead of loading the whole file.\n")
        append("<attached-files>\n")
        for (r in refs) {
            val mime = r.mimeType.replace(Regex("[,()]"), "").trim().ifBlank { OCTET }
            append("- `").append(r.path).append("` (").append(mime).append(", ").append(r.size).append(" bytes")
            if (r.originalOfImage) append(", ").append(ORIGINAL_NOTE)
            append(")\n")
        }
        append("</attached-files>")
    })

    /** The files of [atts] the agent gets by path (images picked in Files also go to the model as a shrunk copy). */
    fun refsOf(atts: List<ChatAttachment>): List<Ref> = atts.filter { it.reference && it.path.isNotBlank() }.map { a ->
        val shrunkImage = a.isImage && !a.base64.isNullOrBlank()
        // a shrunk image's own type is the copy's (JPEG); the list names the original file
        val mime = if (shrunkImage) mimeOf(a.path) else a.mimeType.orEmpty()
        Ref(a.path, mime, a.size, originalOfImage = shrunkImage)
    }

    /** Every file listed in [text]. */
    fun parse(text: String): List<Ref> {
        if (!text.contains("<attached-files>")) return emptyList()
        return BLOCK.findAll(text).flatMap { it.groupValues[1].lines() }.mapNotNull { line ->
            LINE.find(line.trim())?.let { m ->
                val path = m.groupValues[1].ifBlank { m.groupValues[2] }
                Ref(path, m.groupValues[3].trim(), m.groupValues[4].toLongOrNull() ?: 0, m.groupValues[5].contains(ORIGINAL_NOTE))
            }
        }.toList()
    }

    /** [text] without its file lists, and the files to show as cards (an image's original is already shown as the image). */
    fun extract(text: String, idPrefix: String): Pair<String, List<ChatAttachment>> {
        if (!text.contains("<attached-files>")) return text to emptyList()
        val cards = parse(text).filter { !it.originalOfImage }.mapIndexed { i, r -> toChatAttachment(r, "${idPrefix}_file_$i") }
        return BLOCK.replace(text, "\n\n").trim() to cards
    }

    fun toChatAttachment(ref: Ref, id: String) = ChatAttachment(
        id = id,
        name = ref.path.trimEnd('/').substringAfterLast('/'),
        path = ref.path,
        // a card that opens the file, not an inline picture (it can be any size or format)
        isImage = false,
        size = ref.size,
        mimeType = ref.mimeType,
        url = FileExplorerApi.rawUrl(ref.path),
        reference = true
    )

    /** True for a copy the app made in [UPLOADS_DIR] (deleted with its chat), false for the user's own files. */
    fun isUploadCopy(path: String): Boolean = path.contains(UPLOADS_MARK)

    /** The copy's own folder (`…/uploads/<id>`), or null for anything that is not a copy. */
    fun uploadFolderOf(path: String): String? {
        if (!isUploadCopy(path)) return null
        val rest = path.substringAfter(UPLOADS_MARK)
        val id = rest.substringBefore('/')
        if (id.isBlank() || id == rest) return null
        return path.substringBefore(UPLOADS_MARK) + UPLOADS_MARK + id
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: OCTET
    }

    // ---- which chat uses which copies, so deleting a chat deletes its copies (never the user's own files) ----

    private fun index(context: Context) = context.getSharedPreferences("chat_uploads", Context.MODE_PRIVATE)

    /** Remembers the copies [refs] use under chat [chatId]. */
    @Synchronized
    fun rememberCopies(context: Context, chatId: String, refs: List<Ref>) {
        val folders = refs.mapNotNull { uploadFolderOf(it.path) }.toSet()
        if (folders.isEmpty() || chatId.isBlank()) return
        val prefs = index(context)
        prefs.edit().putStringSet(chatId, prefs.getStringSet(chatId, emptySet()).orEmpty() + folders).apply()
    }

    /** A chat got its final id (a new Codex chat's temporary id is replaced once its thread exists). */
    @Synchronized
    fun moveCopies(context: Context, fromId: String, toId: String) {
        if (fromId == toId) return
        val prefs = index(context)
        val folders = prefs.getStringSet(fromId, null) ?: return
        prefs.edit().remove(fromId).putStringSet(toId, prefs.getStringSet(toId, emptySet()).orEmpty() + folders).apply()
    }

    /** Forgets chat [chatId] and returns its copies that no other chat still uses: the ones to delete. */
    @Synchronized
    fun takeCopiesOf(context: Context, chatId: String): List<String> {
        val prefs = index(context)
        val folders = prefs.getStringSet(chatId, null) ?: return emptyList()
        prefs.edit().remove(chatId).apply()
        val stillUsed = prefs.all.filterKeys { it != chatId }.values.flatMap { (it as? Set<*>).orEmpty() }.toSet()
        return folders.filter { it !in stillUsed && isUploadCopy("$it/") }
    }
}
