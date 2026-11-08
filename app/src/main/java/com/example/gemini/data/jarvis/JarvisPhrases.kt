package com.example.gemini.data.jarvis

/**
 * Spoken commands that end a capture: send phrases ("send it", "send kar do") send what was said before them at once,
 * cancel phrases ("scratch that", "rehne do") throw it away. Both lists are the user's own (Settings → Jarvis) and start
 * empty; [SUGGESTED_SEND] and [SUGGESTED_CANCEL] are only offered there. The voice shortcuts (stop, repeat…) are the
 * user's own lists too ([isShortcut]).
 *
 * A phrase only counts at the very end of what was said, and a little sense is applied so it does not fire inside a
 * normal sentence:
 * - "…and send it", "…to send it", "…can you do it": a joining word right before the phrase makes it part of the request.
 * - "don't send it": a negation before it never sends (and "don't send" is a cancel phrase).
 * - Short or very common phrases ("send", "over", "do it", "that's it") must be said on their own: after a short pause
 *   or a punctuation mark from the recognizer, not run into the sentence.
 * - When a send and a cancel phrase both fit, the longer one wins; a tie cancels (nothing is sent by mistake).
 * - Said alone (nothing before it), a cancel phrase is left to the normal commands ("never mind" stops Jarvis), and a
 *   send phrase is passed on as the answer itself ("do it" after "Shall I start the build?").
 */
object JarvisPhrases {
    enum class Kind { SEND, CANCEL }

    /** [content] is the raw text before the phrase (or the whole text when [whole]). */
    data class Match(val kind: Kind, val phrase: String, val content: String, val whole: Boolean)

    val SUGGESTED_SEND = listOf(
        "send it", "send it now", "send now", "send this", "send that", "send the message", "go ahead and send",
        "send it jarvis", "jarvis send", "that's it send it", "send", "over", "do it", "that's it",
        "send kar do", "send kardo", "bhej do", "bhejo", "bhej de"
    )
    val SUGGESTED_CANCEL = listOf(
        "cancel that", "cancel this", "cancel the message", "scratch that", "never mind", "nevermind", "forget it",
        "forget that", "don't send", "don't send it", "do not send", "do not send it",
        "rehne do", "chhod do", "chod do", "mat bhejo", "cancel kar do"
    )

    /** Phrases offered for each voice shortcut in Settings (none is active until the user adds it). */
    val SUGGESTED_SHORTCUTS = mapOf(
        "stop" to listOf("stop talking", "jarvis stop", "be quiet"),
        "bye" to listOf("thanks jarvis", "that's all", "goodbye jarvis"),
        "repeat" to listOf("repeat that", "say that again", "what did you say"),
        "slower" to listOf("speak slower", "slow down"),
        "faster" to listOf("speak faster", "speed up"),
        "quiet_on" to listOf("quiet mode on", "text only mode"),
        "quiet_off" to listOf("quiet mode off", "use your voice"),
        "new_session" to listOf("start over", "new session", "fresh start"),
        "interrupt" to listOf("wait", "actually", "no stop", "change of plans")
    )

    /** Leading address and trailing politeness that do not change which shortcut was said. */
    private val address = Regex("^(hey |ok |okay )?jarvis ")
    private val politeEnd = Regex(" (please|jarvis|now)$")

    /** [text] (the whole utterance) is one of the phrases in [stored] (one per line), maybe with "Jarvis" or "please" around it. */
    fun isShortcut(text: String, stored: String): Boolean {
        val list = parse(stored)
        if (list.isEmpty()) return false
        val t = normalize(text)
        if (t in list) return true
        val bare = politeEnd.replace(address.replace(t, ""), "").trim()
        return bare in list
    }

    /** [text] starts with one of the phrases in [stored] (whole words). */
    fun startsWithAny(text: String, stored: String): Boolean {
        val list = parse(stored)
        if (list.isEmpty()) return false
        val t = address.replace(normalize(text), "")
        return list.any { p -> t == p || t.startsWith("$p ") }
    }

    /** Pause before a short phrase that makes it "said on its own". */
    private const val SEPARATE_GAP_MS = 600L

    private val wordRegex = Regex("[\\p{L}\\p{N}']+")
    private val common = setOf(
        "do", "it", "go", "ahead", "that's", "thats", "that", "is", "ok", "okay", "done", "over", "now", "yes", "yeah",
        "finish", "finished", "all", "the", "this", "fine", "good"
    )
    private val connectors = setOf(
        "and", "to", "then", "also", "can", "could", "would", "will", "should", "must", "might", "may", "i", "we", "you",
        "he", "she", "they", "me", "him", "her", "them", "us", "not", "don't", "dont", "never", "no", "can't", "cannot",
        "won't", "didn't", "doesn't", "isn't", "wouldn't", "shouldn't", "or", "if", "when", "until", "let", "lets", "let's"
    )
    /** Politeness after the phrase that is ignored ("send it please", "bhej do yaar"). */
    private val trailing = setOf("please", "jarvis", "yaar", "na", "bro", "now")

    fun normalize(s: String): String = s.lowercase().replace('’', '\'').replace('‘', '\'')
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Stored lists: one phrase per line ("|" also separates, for tools). */
    fun parse(stored: String): List<String> = stored.split('\n', '|').map { normalize(it) }.filter { it.isNotBlank() }.distinct()

    fun format(list: List<String>): String = list.map { normalize(it) }.filter { it.isNotBlank() }.distinct().joinToString("\n")

    fun sendPhrases(): List<String> = if (JarvisPreferences.sendPhrasesOn.value) parse(JarvisPreferences.sendPhrases.value) else emptyList()
    fun cancelPhrases(): List<String> = if (JarvisPreferences.cancelPhrasesOn.value) parse(JarvisPreferences.cancelPhrases.value) else emptyList()

    /** The phrase to show as an example on the card ("Say “send it” or tap Send"). */
    fun exampleSend(): String? = sendPhrases().firstOrNull { !isRisky(it) } ?: sendPhrases().firstOrNull()

    /** Short or made only of everyday words: must be said on its own to count. */
    fun isRisky(phrase: String): Boolean {
        val w = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        return w.size <= 1 || w.all { it in common }
    }

    private class Candidate(val kind: Kind, val phrase: String, val start: Int, val size: Int)

    /**
     * Looks at the end of [raw]. [history] is the normalized text at each change during this capture with its time
     * (oldest first); it tells whether a short phrase came after a pause. [expectingReply]: Jarvis just asked something.
     */
    fun match(raw: String, history: List<Pair<String, Long>>, expectingReply: Boolean): Match? {
        val send = sendPhrases()
        val cancel = cancelPhrases()
        if (send.isEmpty() && cancel.isEmpty()) return null
        val text = raw.replace('’', '\'').replace('‘', '\'')
        val tokens = wordRegex.findAll(text).toList()
        if (tokens.isEmpty()) return null
        val words = tokens.map { it.value.lowercase() }
        // the end of what was said, without trailing politeness
        val ends = LinkedHashSet<Int>().apply {
            add(words.size)
            var e = words.size
            while (e > 1 && words[e - 1] in trailing) { e--; add(e) }
        }
        var best: Candidate? = null
        fun consider(kind: Kind, list: List<String>) {
            for (p in list) {
                val pw = p.split(' ')
                for (end in ends) {
                    if (end < pw.size || words.subList(end - pw.size, end) != pw) continue
                    val c = Candidate(kind, p, end - pw.size, pw.size)
                    val b = best
                    if (b == null || c.size > b.size || (c.size == b.size && kind == Kind.CANCEL)) best = c
                }
            }
        }
        consider(Kind.SEND, send)
        consider(Kind.CANCEL, cancel)
        val c = best ?: return null

        if (c.start == 0) {
            // nothing before it: a cancel phrase is a normal command; a send phrase is the answer itself
            return if (c.kind == Kind.SEND && (expectingReply || !isRisky(c.phrase))) Match(Kind.SEND, c.phrase, raw.trim(), whole = true) else null
        }
        if (words[c.start - 1] in connectors) return null
        if (isRisky(c.phrase) && !saidOnItsOwn(text, tokens, c.start, history)) return null
        val content = text.substring(0, tokens[c.start].range.first).trimEnd(' ', ',', ';', ':', '-', '—', '.', '!').trim()
        if (content.isBlank()) return null
        return Match(c.kind, c.phrase, content, whole = false)
    }

    /** The phrase starting at word [start] came after a pause (or the recognizer put a punctuation mark before it). */
    private fun saidOnItsOwn(text: String, tokens: List<MatchResult>, start: Int, history: List<Pair<String, Long>>): Boolean {
        val between = text.substring(tokens[start - 1].range.last + 1, tokens[start].range.first)
        if (between.any { it in ",.!?;:—-" }) return true
        val before = normalize(tokens.subList(0, start).joinToString(" ") { it.value })
        // the last moment the text was exactly what came before the phrase, and how long it stayed so
        val i = history.indexOfLast { it.first == before }.takeIf { it >= 0 }
            ?: history.indexOfLast { it.first.isNotEmpty() && before.startsWith(it.first) && it.first.length >= before.length - 2 }.takeIf { it >= 0 }
            ?: return false
        val next = history.getOrNull(i + 1) ?: return false
        return next.second - history[i].second >= SEPARATE_GAP_MS
    }
}
