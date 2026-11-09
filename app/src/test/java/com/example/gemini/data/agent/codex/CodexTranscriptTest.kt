package com.example.gemini.data.agent.codex

import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays real Codex app-server traffic (recorded from codex-cli 0.167 on a live account) through [CodexTranscript]:
 * the model of each reply, its token card, the thinking block, a chat reopened from history, and a review.
 */
class CodexTranscriptTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource("codex/$name")!!.readText()

    private fun replay(t: CodexTranscript, file: String, skipLast: Int = 0) {
        val lines = resource(file).lines().filter { it.isNotBlank() }
        for (line in lines.dropLast(skipLast)) {
            val o = CodexJson.parseToJsonElement(line).obj()!!
            t.apply(o.str("method")!!, o["params"].obj()!!)
        }
    }

    private fun threadOf(file: String): String {
        val first = CodexJson.parseToJsonElement(resource(file).lines().first { it.isNotBlank() }).obj()!!["params"].obj()!!
        return first.str("threadId") ?: first["thread"].obj().str("id")!!
    }

    private fun replies(messages: List<ChatMessage>) = messages.filter { it.role == MessageRole.ASSISTANT }

    @Test
    fun liveTurnsShowTheModelTheyRanOnAfterASwitch() {
        val tid = threadOf("live_model_switch.jsonl")
        val t = CodexTranscript(tid)
        // the chat was on gpt-5.6-luna before (its last turn), then the user switched models per turn
        t.model = "gpt-5.6-luna"
        replay(t, "live_model_switch.jsonl")
        assertFalse("no turn may stay running", t.isTurnActive)
        val replies = replies(t.toChatMessages(tid, emptySet(), null))
        assertEquals(listOf("gpt-6-luna", "gpt-5.6-terra"), replies.map { it.modelId })
        for (r in replies) {
            assertNotNull("each finished reply has its token card", r.tokenUsage)
            val usage = r.tokenUsage!!
            assertEquals(r.modelId, usage.model)
            assertTrue(usage.totalTokens > 0)
            assertTrue(usage.outputTokens > 0)
        }
    }

    @Test
    fun reasoningSummaryBecomesTheThinkingBlock() {
        val tid = threadOf("live_model_switch.jsonl")
        val t = CodexTranscript(tid)
        replay(t, "live_model_switch.jsonl")
        val replies = replies(t.toChatMessages(tid, emptySet(), null))
        assertTrue(replies[0].content.contains("<!-- thought -->"))
        assertTrue(replies[0].thoughtText!!.contains("Calculating flock size"))
        assertTrue(replies[1].thoughtText!!.contains("Calculating final quantities"))
        // the answer itself follows the thinking
        assertTrue(replies[0].content.trim().endsWith("18"))
    }

    @Test
    fun thinkingStreamsWhileTheTurnRuns() {
        val tid = threadOf("live_model_switch.jsonl")
        val t = CodexTranscript(tid)
        val lines = resource("live_model_switch.jsonl").lines().filter { it.isNotBlank() }
        // stop right after the first summary delta: the thought is open and marked streaming
        val cut = lines.indexOfFirst { it.contains("item/reasoning/summaryTextDelta") }
        for (line in lines.take(cut + 1)) {
            val o = CodexJson.parseToJsonElement(line).obj()!!
            t.apply(o.str("method")!!, o["params"].obj()!!)
        }
        assertTrue(t.isTurnActive)
        val last = replies(t.toChatMessages(tid, emptySet(), null)).last()
        assertTrue(last.isStreaming)
        assertTrue(last.content.contains("<!-- thought:streaming -->"))
    }

    @Test
    fun reopenedChatKeepsModelsAndTokenCards() {
        val stored = CodexJson.parseToJsonElement(resource("history_turns.json")) as JsonArray
        val meta = CodexBridgeClient.parseTurnsMeta(CodexJson.parseToJsonElement(resource("history_meta.json")).obj())
        val t = CodexTranscript("thread")
        // history comes newest first
        t.loadHistory(stored.mapNotNull { it.obj() }.asReversed(), active = false)
        t.applyMeta(meta)
        val replies = replies(t.toChatMessages("thread", emptySet(), null))
        assertEquals(listOf("gpt-6-luna", "gpt-5.6-luna", "gpt-6-luna", "gpt-5.6-terra"), replies.map { it.modelId })
        for (r in replies) {
            val usage = r.tokenUsage
            assertNotNull("a reopened chat still shows the token card", usage)
            assertEquals(r.modelId, usage!!.model)
            assertTrue(usage.totalTokens > 0 && usage.durationMs > 0)
        }
        // the numbers are Codex's own record of the turn
        val firstTurn = (stored.last() as JsonObject).str("id")!!
        assertEquals(meta[firstTurn]!!.usage!!.totalTokens, replies[0].tokenUsage!!.totalTokens)
        // and history alone (no record available) still renders, without cards
        val plain = CodexTranscript("thread").apply { loadHistory(stored.mapNotNull { it.obj() }.asReversed(), active = false) }
        assertEquals(4, replies(plain.toChatMessages("thread", emptySet(), null)).size)
    }

    @Test
    fun reviewEndsAlthoughItsInnerTurnNeverReportsCompletion() {
        val tid = threadOf("live_review.jsonl")
        val t = CodexTranscript(tid)
        replay(t, "live_review.jsonl")
        assertFalse(t.isTurnActive)
        val text = t.toChatMessages(tid, emptySet(), null).joinToString("\n") { it.content }
        assertTrue(text.contains("Yes, 2 + 2 = 4."))
    }

    @Test
    fun idleThreadSettlesARunningTurn() {
        val tid = threadOf("live_review.jsonl")
        val t = CodexTranscript(tid)
        // the last event (turn/completed) never came
        replay(t, "live_review.jsonl", skipLast = 1)
        t.settleAll()
        assertFalse(t.isTurnActive)
    }

    /**
     * Recorded: a chat whose second reply was streaming when its history was read (Codex had saved only the prompt of
     * that turn). Coming back to the chat must keep what was streamed so far, and end with the whole answer.
     */
    @Test
    fun comingBackMidReplyKeepsWhatWasStreamedSoFar() {
        val rec = CodexJson.parseToJsonElement(resource("midturn.json")).obj()!!
        val tid = rec.str("thread")!!
        val events = (rec["events"] as JsonArray).mapNotNull { it.obj() }
        val upTo = rec["snapshot_after"].toString().toInt()
        val t = CodexTranscript(tid)
        // what the app saw live before the chat's history was read again
        for (e in events.take(upTo)) t.apply(e.str("method")!!, e["params"].obj()!!)
        val streamedSoFar = replies(t.toChatMessages(tid, emptySet(), null)).last().content
        assertTrue("the reply had started", streamedSoFar.isNotBlank())
        // the stored history at that moment: the running turn has only the prompt
        val snapshot = (rec["snapshot"] as JsonArray).mapNotNull { it.obj() }.asReversed()
        val running = snapshot.last()
        assertEquals("inProgress", running.str("status"))
        assertTrue((running["items"] as JsonArray).none { it.obj().str("type") == "agentMessage" })
        t.loadHistory(snapshot, active = true)
        val afterReload = t.toChatMessages(tid, emptySet(), null)
        assertEquals("both prompts are there", 2, afterReload.count { it.role == MessageRole.USER })
        assertEquals("the answer so far is kept", streamedSoFar, replies(afterReload).last().content)
        // the rest of the reply streams in, and the result is the whole answer Codex saved
        for (e in events.drop(upTo)) t.apply(e.str("method")!!, e["params"].obj()!!)
        assertFalse(t.isTurnActive)
        val finalText = (rec["final"] as JsonArray).first().obj()!!["items"].let { items ->
            (items as JsonArray).mapNotNull { it.obj() }.first { it.str("type") == "agentMessage" }.str("text")!!
        }
        assertEquals(finalText.trim(), replies(t.toChatMessages(tid, emptySet(), null)).last().content.trim())
    }

    @Test
    fun finishedHistoryReplacesWhatWasSeenLive() {
        val rec = CodexJson.parseToJsonElement(resource("midturn.json")).obj()!!
        val tid = rec.str("thread")!!
        val events = (rec["events"] as JsonArray).mapNotNull { it.obj() }
        val t = CodexTranscript(tid)
        for (e in events.take(rec["snapshot_after"].toString().toInt())) t.apply(e.str("method")!!, e["params"].obj()!!)
        // the reply ended meanwhile: the stored history is complete and is shown as is
        t.loadHistory((rec["final"] as JsonArray).mapNotNull { it.obj() }.asReversed(), active = false)
        assertFalse(t.isTurnActive)
        assertEquals(2, replies(t.toChatMessages(tid, emptySet(), null)).size)
    }

    @Test
    fun usageRepeatedOnResumeDoesNotOpenATurn() {
        val tid = threadOf("live_model_switch.jsonl")
        val t = CodexTranscript(tid)
        // the first usage event recorded is that of an older turn, repeated when the chat was resumed
        val first = CodexJson.parseToJsonElement(resource("live_model_switch.jsonl").lines().first { it.contains("thread/tokenUsage/updated") }).obj()!!
        t.apply(first.str("method")!!, first["params"].obj()!!)
        assertFalse(t.isTurnActive)
        assertTrue(t.toChatMessages(tid, emptySet(), null).isEmpty())
    }
}
