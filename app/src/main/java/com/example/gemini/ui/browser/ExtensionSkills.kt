package com.example.gemini.ui.browser

import java.io.File

/** One skill of a custom plugin: `skills/<name>.md`, with optional detail files in `skills/<name>/`. */
data class ExtensionSkill(
    val name: String,
    val title: String,
    val description: String,
    val path: String,
    val detailDir: String?
)

/**
 * Reads a plugin's skills the same way the extension host's `get_skills` tool does
 * (assets/browser-runtime/skills.mjs), so the chat's `/plu` picker matches what the model sees.
 */
object ExtensionSkills {
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val FRONT = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?")
    private val BLOCK_START = Regex("^(#|```|[-*]\\s|\\d+\\.\\s|\\||>).*")
    private const val PEEK_CHARS = 16 * 1024

    fun list(pluginDir: File): List<ExtensionSkill> {
        val root = File(pluginDir, "skills")
        val files = root.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) && NAME.matches(it.name.dropLast(3)) }
            .map { file ->
                val name = file.name.dropLast(3)
                val (title, description) = describe(peek(file), name)
                val details = File(root, name)
                ExtensionSkill(name, title, description, file.absolutePath, details.takeIf { it.isDirectory }?.absolutePath)
            }
            .sortedBy { it.name }
    }

    private fun peek(file: File): String = runCatching {
        file.bufferedReader().use { reader ->
            val buffer = CharArray(PEEK_CHARS)
            var count = 0
            while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            String(buffer, 0, count)
        }
    }.getOrDefault("")

    /** Title and description from optional `---` frontmatter, else the first heading and paragraph. */
    internal fun describe(text: String, fallback: String): Pair<String, String> {
        var body = text
        val meta = mutableMapOf<String, String>()
        FRONT.find(text)?.let { match ->
            body = text.substring(match.range.last + 1)
            match.groupValues[1].lines().forEach { line ->
                Regex("^([A-Za-z_-]+):\\s*(.*)$").find(line)?.let {
                    meta[it.groupValues[1].lowercase()] = it.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }
        val lines = body.lines()
        val headingIndex = lines.indexOfFirst { Regex("^#\\s+\\S.*").matches(it) }
        var description = meta["description"].orEmpty()
        if (description.isEmpty()) {
            val paragraph = mutableListOf<String>()
            for (line in lines.drop(headingIndex + 1)) {
                val t = line.trim()
                if (t.isEmpty()) { if (paragraph.isNotEmpty()) break else continue }
                if (BLOCK_START.matches(t)) { if (paragraph.isNotEmpty()) break else continue }
                paragraph.add(t)
            }
            description = paragraph.joinToString(" ").replace(Regex("[*_`]"), "").replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        }
        if (description.length > 240) description = description.take(237).trimEnd() + "…"
        val title = meta["name"] ?: meta["title"] ?: lines.getOrNull(headingIndex)?.replace(Regex("^#\\s+"), "")?.trim() ?: fallback
        return title to description
    }
}
