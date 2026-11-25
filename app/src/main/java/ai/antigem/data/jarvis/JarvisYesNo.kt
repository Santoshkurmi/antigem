package ai.antigem.data.jarvis

/**
 * Reads the user's spoken answer to an approval question. Anything that is not clearly a yes is not a yes:
 * - a "no" word anywhere wins ("not", "never", "wait", "hold on", "later", "unsure", "can't"…), so "I'm not sure",
 *   "not okay" and "okay wait" are NO;
 * - YES only when the whole answer is a short yes ("yes", "okay", "go ahead", "yes do it"), with nothing else in it;
 * - YES_ALL only for "approve all" (or "approve everything") said on its own;
 * - a risky action needs the fixed phrase [RISKY_PHRASE] ("yes, run it"); a plain "yes" is not enough.
 */
object JarvisYesNo {
    enum class Decision { YES, YES_ALL, NO, UNCLEAR }

    /** What the user says to approve a risky action (punctuation and "please" / "Jarvis" around it don't matter). */
    const val RISKY_PHRASE = "yes run it"

    /** Longer answers are sentences, not a yes. */
    private const val MAX_WORDS = 6

    private val noWords = Regex(
        "\\b(no|nope|nah|not|never|nevermind|reject|rejected|deny|denied|don'?t|dont|stop|cancel|negative|decline|" +
            "wait|hold|hang|later|unsure|maybe|skip|abort|dunno)\\b|\\b\\w+n't\\b"
    )
    private val strong = setOf(
        "yes", "yeah", "yep", "yup", "approve", "approved", "allow", "ok", "okay", "sure", "accept", "proceed", "confirm",
        "confirmed", "affirmative", "alright", "correct"
    )
    private val filler = setOf("please", "jarvis", "hey", "go", "ahead", "do", "it", "that", "this", "run", "fine", "then", "thanks", "thank", "you", "now")
    private val strongPhrases = listOf("go ahead", "do it", "run it")
    private val polite = setOf("please", "jarvis", "hey")
    private val allPhrases = setOf("approve all", "approve everything", "approve them all", "approve all of them")

    fun normalize(text: String?): String = text.orEmpty().lowercase()
        .replace('’', '\'').replace('‘', '\'')
        .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun parse(text: String?, risky: Boolean = false): Decision {
        val t = normalize(text)
        if (t.isEmpty()) return Decision.UNCLEAR
        if (noWords.containsMatchIn(t)) return Decision.NO
        val words = t.split(' ')
        val core = words.filter { it !in polite }.joinToString(" ")
        if (risky) return if (core == RISKY_PHRASE) Decision.YES else Decision.UNCLEAR
        if (words.size > MAX_WORDS) return Decision.UNCLEAR
        if (core in allPhrases) return Decision.YES_ALL
        val onlyYesWords = words.all { it in strong || it in filler }
        val saysYes = words.any { it in strong } || strongPhrases.any { " $t ".contains(" $it ") }
        return if (onlyYesWords && saysYes) Decision.YES else Decision.UNCLEAR
    }
}
