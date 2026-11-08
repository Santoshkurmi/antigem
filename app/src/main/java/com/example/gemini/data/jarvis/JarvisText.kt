package com.example.gemini.data.jarvis

import com.example.gemini.data.remote.PromptExpansion

/**
 * A chat reply as words. Every agent stores more than the reply in a message's text: the model's thinking
 * (`<!-- thought -->…<!-- /thought -->`, possibly still open while it streams), tool-call placeholders
 * (`<!-- tool_call:… -->`), error cards (`<!-- error -->{json}<!-- /error -->`) and hidden instructions. None of that may
 * ever be read out or passed on as what an agent "said"; the error card becomes one plain sentence instead.
 */
object JarvisText {
    private val thought = Regex("<!--\\s*thought[^>]*-->[\\s\\S]*?(<!--\\s*/thought\\s*-->|$)")
    // only the app's own thinking marker can be open (still streaming); anything else must be closed, so a reply that
    // merely mentions "<thinking>" or "<!--" keeps the rest of its text
    private val thoughtTag = Regex("<thought>[\\s\\S]*?</thought>", RegexOption.IGNORE_CASE)
    private val thinkingTag = Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE)
    private val errorCard = Regex("<!--\\s*error[^>]*-->([\\s\\S]*?)<!--\\s*/error\\s*-->")
    private val comment = Regex("<!--[\\s\\S]*?-->")
    private val field = { name: String -> Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") }
    private val userMessage = field("userMessage")
    private val title = field("title")

    data class Reply(
        /** What the agent wrote for the user (no thinking, markers or hidden blocks). */
        val text: String,
        /** The error the turn ended with, in one line, if any. */
        val error: String?
    )

    fun reply(raw: String?): Reply {
        if (raw.isNullOrBlank()) return Reply("", null)
        var s = PromptExpansion.collapse(raw)
        val errors = errorCard.findAll(s).mapNotNull { errorLine(it.groupValues[1]) }.toList()
        s = errorCard.replace(s, " ")
        s = thought.replace(s, " ")
        s = thoughtTag.replace(s, " ")
        s = thinkingTag.replace(s, " ")
        s = comment.replace(s, " ")
        s = s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").replace(Regex("[ \\t]{2,}"), " ").trim()
        return Reply(s, errors.lastOrNull())
    }

    /** The reply with its error (if any) as a closing sentence: what a manager is told an agent answered. */
    fun report(raw: String?): String {
        val r = reply(raw)
        return when {
            r.error == null -> r.text
            r.text.isBlank() -> "Error: ${r.error}"
            else -> "${r.text}\n\nError: ${r.error}"
        }
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")

    private fun errorLine(json: String): String? {
        val msg = userMessage.find(json)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val t = title.find(json)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val line = listOfNotNull(t?.takeIf { it.isNotBlank() }, msg?.takeIf { it.isNotBlank() && it != t }).joinToString(": ")
        return line.ifBlank { json.trim().take(160).ifBlank { null } }?.take(300)
    }
}
