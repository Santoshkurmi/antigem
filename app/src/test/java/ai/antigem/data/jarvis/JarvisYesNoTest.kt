package ai.antigem.data.jarvis

import ai.antigem.data.jarvis.JarvisYesNo.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class JarvisYesNoTest {

    private fun check(expected: Decision, vararg phrases: String, risky: Boolean = false) {
        for (p in phrases) assertEquals("\"$p\" (risky=$risky)", expected, JarvisYesNo.parse(p, risky))
    }

    @Test
    fun hesitationIsNeverYes() {
        check(
            Decision.NO,
            "I'm not sure", "not okay", "okay wait", "sure, but not now", "no", "nope", "don't", "do not do it",
            "hold on", "hang on", "wait", "later", "maybe", "I don't know", "never", "can't say", "yes wait",
            "okay… hold on", "I’m not sure", "stop", "cancel that", "reject it"
        )
    }

    @Test
    fun shortYes() {
        check(Decision.YES, "yes", "Yes.", "yeah", "yep", "okay", "OK", "sure", "go ahead", "do it", "yes do it", "yes please",
            "approve", "Jarvis, approve it", "okay go ahead", "allow it")
    }

    @Test
    fun sentencesAreUnclear() {
        check(Decision.UNCLEAR, "", "   ", "hmm", "what is it doing", "yes and also delete the other folder",
            "sure thing buddy whatever you think is best", "yes to all", "yes all", "approve all of them and everything else",
            "it", "go")
    }

    @Test
    fun approveAllOnlyOnItsOwn() {
        check(Decision.YES_ALL, "approve all", "Approve all.", "approve everything", "Jarvis approve all please")
        check(Decision.UNCLEAR, "yes all", "okay always", "sure everything")
    }

    @Test
    fun riskyNeedsTheFixedPhrase() {
        check(Decision.YES, "yes, run it", "Yes run it.", "Jarvis, yes, run it please", risky = true)
        check(Decision.UNCLEAR, "yes", "okay", "do it", "approve all", "yes run it and more", risky = true)
        check(Decision.NO, "no", "yes run it, wait", "not yet", risky = true)
    }
}
