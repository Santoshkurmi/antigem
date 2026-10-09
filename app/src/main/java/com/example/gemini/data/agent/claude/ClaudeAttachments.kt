package com.example.gemini.data.agent.claude

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.ChatAttachment

/**
 * Attachments saved on the bridge are listed in the prompt in an `<attached-files>` block (Claude reads them with
 * its tools). The same block is read back from the transcript to show the files again when a chat is reopened.
 */
object ClaudeAttachments {
    private val BLOCK = Regex("""\s*<attached-files>\n([\s\S]*?)\n?</attached-files>\s*""")
    private val LINE = Regex("""^- (/.+) \(([^,()]+), (\d+) bytes\)$""")

    class SavedFile(val path: String, val mimeType: String, val size: Long)

    /** The block appended to a prompt for [files]. */
    fun block(files: List<SavedFile>): String = buildString {
        append("<attached-files>\n")
        append("The user attached these files. Read them with your tools when they are relevant.\n")
        files.forEach { append("- ${it.path} (${it.mimeType.ifBlank { "application/octet-stream" }}, ${it.size} bytes)\n") }
        append("</attached-files>")
    }

    /** Splits a prompt into its visible text and the files of its `<attached-files>` block. */
    fun extract(text: String): Pair<String, List<SavedFile>> {
        val match = BLOCK.find(text) ?: return text to emptyList()
        val files = match.groupValues[1].lines().mapNotNull { line ->
            LINE.find(line.trim())?.let { SavedFile(it.groupValues[1], it.groupValues[2], it.groupValues[3].toLongOrNull() ?: 0) }
        }
        return text.removeRange(match.range).trim() to files
    }

    /** The bridge URL the app loads a saved attachment from. */
    fun rawUrl(path: String): String =
        AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/claude/attachments/raw?path=" + java.net.URLEncoder.encode(path, "UTF-8")

    fun toChatAttachment(file: SavedFile, id: String) = ChatAttachment(
        id = id,
        name = file.path.substringAfterLast('/'),
        path = "",
        isImage = file.mimeType.startsWith("image/"),
        isAudio = file.mimeType.startsWith("audio/"),
        size = file.size,
        mimeType = file.mimeType,
        url = rawUrl(file.path)
    )
}
