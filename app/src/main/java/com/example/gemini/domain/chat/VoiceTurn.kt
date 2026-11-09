package com.example.gemini.domain.chat

/**
 * Messages an Antigravity voice call puts into the chat. When you ask the voice for real work, it hands the request to
 * the agent as a user message tagged `voice_turn`: `<voice_instructions>…</voice_instructions>` (first time only), a
 * `<talker_agent_context>` transcript of the call so far (`[time] user: "…"` / `talker: "…"` lines), and your speech
 * as audio clips; a `voice_talker:<text>` tag holds what the voice said while handing off.
 *
 * The chat shows such a message as what you said, with the voice's line under it, instead of the prompt and clips.
 */
object VoiceTurn {
    const val TAG = "voice_turn"
    private const val TALKER_TAG_PREFIX = "voice_talker:"

    data class Parsed(
        /** What the user said for this request (may be empty when the transcript has no user line). */
        val spoken: String,
        /** What the voice said while handing the request to the agent, or null. */
        val voiceLine: String?
    )

    private val lineRe = Regex("""^\s*(?:\[[^\]]*]\s*)?(user|talker):\s*"(.*)"\s*$""")

    fun isVoiceTurn(text: String, tags: List<String>): Boolean {
        if (tags.contains(TAG)) return true
        val t = text.trimStart()
        return t.startsWith("<voice_instructions>") || t.startsWith("<talker_agent_context>")
    }

    /** Null when [text]/[tags] are not a voice-call message. */
    fun parse(text: String, tags: List<String>): Parsed? {
        if (!isVoiceTurn(text, tags)) return null
        val voiceLine = tags.firstOrNull { it.startsWith(TALKER_TAG_PREFIX) }?.removePrefix(TALKER_TAG_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
        val context = text.substringAfter("<talker_agent_context>", "").substringBefore("</talker_agent_context>")
        data class L(val user: Boolean, val timed: Boolean, val text: String)
        val lines = context.lines().mapNotNull { raw ->
            val m = lineRe.find(raw) ?: return@mapNotNull null
            L(m.groupValues[1] == "user", raw.trimStart().startsWith("["), unescape(m.groupValues[2]).trim().removeSuffix("-").trim())
        }
        // the request is the last run of user lines (after the voice's last reply before it)
        var end = lines.size
        while (end > 0 && !lines[end - 1].user) end--
        var start = end
        while (start > 0 && lines[start - 1].user) start--
        val run = lines.subList(start, end).filter { it.text.isNotEmpty() }
        // an untimed user line right after a timed one is another hearing of the same words: keep the first
        val picked = if (run.any { it.timed }) run.filter { it.timed } else run
        return Parsed(picked.joinToString(" ") { it.text }.trim(), voiceLine)
    }

    /** The chat text of a message: what was said for a voice-call message, else [text] unchanged. */
    fun displayText(text: String, tags: List<String>): String {
        val p = parse(text, tags) ?: return text
        return p.spoken.ifBlank { p.voiceLine ?: "Voice request" }
    }

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")
}
