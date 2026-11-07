package com.example.gemini.data.agent.opencode

import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a real OpenCode 2.2 session (recorded from `opencode serve`): the prompt asks for a shell command, the
 * command needs approval (approved once), then the model answers. The same chat is also loaded from the stored
 * history; both must render the same way.
 */
class OpenCodeTranscriptTest {

    // the recorded session (src/test/resources/opencode/); the tests that replay it are skipped when it is not there
    private fun resource(name: String): JsonArray? =
        javaClass.classLoader?.getResource("opencode/$name")?.readText()?.let { OpenCodeJson.parseToJsonElement(it).jsonArray }

    private val recorded: Pair<JsonArray, JsonArray>? = resource("opencode_events.json")?.let { e -> resource("opencode_history.json")?.let { e to it } }
    private val events by lazy { recorded!!.first.map { it.jsonObject } }
    private val history by lazy { recorded!!.second.map { it.jsonObject } }
    private val sessionId by lazy { events.first { it.obj().o("data").str("sessionID") != null }.o("data").str("sessionID")!! }
    private val promptId by lazy { events.first { it.str("type") == "session.inbox.enqueued" }.o("data").str("inboxID")!! }

    private fun needsRecording() = org.junit.Assume.assumeTrue("recorded OpenCode session not present", recorded != null)

    // a small stored chat: one prompt and its answer
    private val miniHistory = listOf(
        """{"id":"msg_a1","type":"user","text":"first prompt","time":{"created":1000}}""",
        """{"id":"msg_a2","type":"assistant","model":{"id":"m","providerID":"p"},"content":[{"type":"text","text":"answer one"}],"tokens":{"input":5,"output":2},"time":{"created":1001,"completed":1002}}"""
    ).map { OpenCodeJson.parseToJsonElement(it).jsonObject }

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content

    @Test
    fun liveEventsBuildTheTurn() {
        needsRecording()
        val t = OpenCodeTranscript(sessionId)
        t.addLocalPrompt(promptId, "Run `echo hello-from-shell` in the shell, then tell me exactly what it printed.", emptyList())
        var sawApproval = false
        for (e in events) {
            val data = e.o("data") ?: JsonObject(emptyMap())
            t.apply(e.type(), data)
            if (e.type() == "permission.asked") {
                sawApproval = true
                assertEquals(1, t.pendingPermissions.size)
                val perm = t.pendingPermissions.values.first()
                assertEquals("shell", perm.action)
                assertTrue(perm.resources.first().contains("echo hello-from-shell"))
                val tool = t.toChatMessages(sessionId).last().toolCalls.single()
                assertEquals("PENDING_APPROVAL", tool.status)
                assertTrue(t.isTurnActive)
            }
            if (e.type() == "permission.replied") assertTrue(t.pendingPermissions.isEmpty())
        }
        assertTrue("the recording has an approval", sawApproval)
        assertFalse(t.isTurnActive)
        // OpenCode names the chat by itself during the turn
        assertFalse(t.lastTitle.isNullOrBlank())

        val msgs = t.toChatMessages(sessionId)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), msgs.map { it.role })
        assertEquals(promptId, msgs[0].id)
        val reply = msgs[1]
        assertFalse(reply.isStreaming)
        val tool = reply.toolCalls.single()
        assertEquals(ToolType.BASH, tool.toolType)
        assertEquals("SUCCESS", tool.status)
        assertTrue(tool.command.contains("echo hello-from-shell"))
        assertTrue("tool output: ${tool.output}", tool.output.contains("hello-from-shell"))
        // the tool card sits inline where the call happened, and the final text follows
        assertTrue(reply.content.contains("<!-- tool_call:${tool.id} -->"))
        assertTrue(reply.content.substringAfter("<!-- tool_call:${tool.id} -->").contains("hello-from-shell"))
        assertNotNull(reply.tokenUsage)
        assertTrue(reply.tokenUsage!!.promptTokens > 0)
        assertEquals("opencode/deepseek-v4.1-flash-free", reply.modelId)
    }

    @Test
    fun historyMatchesLiveRendering() {
        needsRecording()
        val live = OpenCodeTranscript(sessionId)
        live.addLocalPrompt(promptId, "Run `echo hello-from-shell` in the shell, then tell me exactly what it printed.", emptyList())
        for (e in events) live.apply(e.type(), e.o("data") ?: JsonObject(emptyMap()))

        val stored = OpenCodeTranscript(sessionId)
        stored.loadHistory(history, active = false)
        val a = live.toChatMessages(sessionId)
        val b = stored.toChatMessages(sessionId)
        assertEquals(a.map { it.role }, b.map { it.role })
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a[1].toolCalls.map { it.id to it.status }, b[1].toolCalls.map { it.id to it.status })
        // the stored text is the reference; the live one is built from the same deltas
        assertEquals(a[1].content, b[1].content)
        assertEquals(a[1].tokenUsage?.promptTokens, b[1].tokenUsage?.promptTokens)
    }

    @Test
    fun streamingTurnIsMarkedAndToolIdsAreSafe() {
        val t = OpenCodeTranscript("ses_x")
        t.apply("session.execution.started", obj("""{"sessionID":"ses_x"}"""))
        t.apply("session.step.started", obj("""{"sessionID":"ses_x","assistantMessageID":"msg_a","model":{"id":"m","providerID":"p"}}"""))
        t.apply("session.reasoning.delta", obj("""{"sessionID":"ses_x","assistantMessageID":"msg_a","ordinal":0,"delta":"thinking"}"""))
        t.apply("session.tool.input.started", obj("""{"sessionID":"ses_x","assistantMessageID":"msg_a","id":"functions.read:0","name":"read"}"""))
        t.apply("session.tool.called", obj("""{"sessionID":"ses_x","assistantMessageID":"msg_a","id":"functions.read:0","input":{"path":"/a/b/c.txt"}}"""))
        val m = t.toChatMessages("ses_x").single()
        assertTrue(m.isStreaming)
        assertNull(m.tokenUsage)
        val tool = m.toolCalls.single()
        assertEquals("functions_read_0", tool.id)
        assertEquals("c.txt", tool.command)
        assertEquals(ToolType.VIEW_FILE, tool.toolType)
        assertTrue(m.content.contains("<!-- thought:streaming -->"))
        assertTrue(Regex("<!--\\s*tool_call:([a-zA-Z0-9_-]+)\\s*-->").containsMatchIn(m.content))

        t.apply("session.execution.failed", obj("""{"sessionID":"ses_x","error":{"type":"provider.rate-limit","message":"slow down"}}"""))
        val done = t.toChatMessages("ses_x").single()
        assertFalse(done.isStreaming)
        assertEquals("TERMINATED", done.toolCalls.single().status)
        assertTrue(done.content.contains("<!-- error -->"))
        assertTrue(done.content.contains("slow down"))
    }

    @Test
    fun formsBecomeQuestions() {
        val t = OpenCodeTranscript("ses_q")
        t.apply("session.step.started", obj("""{"sessionID":"ses_q","assistantMessageID":"msg_q"}"""))
        t.apply("form.created", obj("""{"form":{"id":"frm_1","sessionID":"ses_q","title":"Pick a stack","fields":[
            {"key":"lang","type":"multiselect","title":"Which language?","maxItems":1,"options":[{"value":"kt","label":"Kotlin"},{"value":"go","label":"Go"}]},
            {"key":"tests","type":"boolean","title":"Add tests?"}]}}"""))
        val call = t.toChatMessages("ses_q").single().toolCalls.single()
        assertEquals(ToolType.ASK_CHOICE, call.toolType)
        assertEquals("AWAITING_CHOICE", call.status)
        assertEquals(OpenCodeTranscript.formToolId("frm_1"), call.id)
        val q = call.questionnaire!!.questions
        assertEquals(listOf("Which language?", "Add tests?"), q.map { it.prompt })
        assertFalse(q[0].isMultiSelect)
        assertEquals(listOf("kt", "go"), q[0].options.map { it.id })
        assertEquals("lang", t.pendingForms["frm_1"]!!.keyByPrompt["Which language?"])
        t.apply("form.replied", obj("""{"formID":"frm_1"}"""))
        assertTrue(t.toChatMessages("ses_q").single().toolCalls.isEmpty())
    }

    @Test
    fun permissionRulesPerMode() {
        val ask = OpenCodeChatBackend.rules(OpenCodePreferences.MODE_ASK, "build")!!
        assertTrue(ask.any { it.jsonObject.str("action") == "shell" && it.jsonObject.str("effect") == "ask" })
        assertTrue(ask.any { it.jsonObject.str("action") == "edit" })
        // the plan agent keeps its own "no edits" rule
        assertFalse(OpenCodeChatBackend.rules(OpenCodePreferences.MODE_ASK, "plan")!!.any { it.jsonObject.str("action") == "edit" })
        val auto = OpenCodeChatBackend.rules(OpenCodePreferences.MODE_AUTO, "plan")!!
        assertEquals("deny", auto.last().jsonObject.str("effect"))
        assertNull(OpenCodeChatBackend.rules(OpenCodePreferences.MODE_DEFAULT, "build"))
    }

    @Test
    fun appIdsSortLikeOpenCodes() {
        val s1 = OpenCodeChatBackend.newId("ses", descending = true)
        Thread.sleep(3)
        val s2 = OpenCodeChatBackend.newId("ses", descending = true)
        assertTrue(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(s1))
        assertTrue("newer sessions sort first", s2 < s1)
        val m1 = OpenCodeChatBackend.newId("msg", descending = false)
        Thread.sleep(3)
        val m2 = OpenCodeChatBackend.newId("msg", descending = false)
        assertTrue("newer messages sort last", m1 < m2)
    }

    @Test
    fun promptSentWhileHistoryLoadsIsNotLost() {
        // the bug: turn 1 ended, its history reload was in flight, the user sent prompt 2 (already delivered), then the
        // stale snapshot replaced the chat: prompt 2 vanished and its reply was glued to the answer before it
        val sid = "ses_m"
        val t = OpenCodeTranscript(sid)
        t.loadHistory(miniHistory, active = false)
        val before = t.toChatMessages(sid).size
        t.addLocalPrompt("msg_zzzzzzzzzzzz_second", "second prompt", emptyList())
        t.apply("session.inbox.delivered", obj("""{"sessionID":"$sid","inboxID":"msg_zzzzzzzzzzzz_second"}"""))
        t.loadHistory(miniHistory, active = false) // the stale snapshot (it has no prompt 2)
        t.apply("session.step.started", obj("""{"sessionID":"$sid","assistantMessageID":"msg_zzzzzzzzzzzz_reply2"}"""))
        t.apply("session.text.ended", obj("""{"sessionID":"$sid","assistantMessageID":"msg_zzzzzzzzzzzz_reply2","ordinal":0,"text":"answer two"}"""))
        val msgs = t.toChatMessages(sid)
        assertEquals(before + 2, msgs.size)
        assertEquals(MessageRole.USER, msgs[msgs.size - 2].role)
        assertEquals("second prompt", msgs[msgs.size - 2].content)
        assertEquals("answer two", msgs.last().content)
        // once the stored history has it, the local copy gives way to the stored one
        val stored = miniHistory + obj("""{"id":"msg_zzzzzzzzzzzz_second","type":"user","text":"second prompt","time":{"created":9999999999999}}""")
        t.loadHistory(stored, active = false)
        assertEquals(1, t.toChatMessages(sid).count { it.content == "second prompt" })
    }

    @Test
    fun previousReplyKeepsItsCardWhileTheNextPromptWaits() {
        // the bug: right after a new prompt (no reply step yet) the previous reply was marked streaming, so its usage
        // card and copy / retry actions disappeared until the new reply started
        val t = OpenCodeTranscript("ses_m")
        t.loadHistory(miniHistory, active = false)
        assertNotNull(t.toChatMessages("ses_m")[1].tokenUsage)
        t.addLocalPrompt("msg_zzzzzzzzzzzz_second", "hello again", emptyList())
        t.apply("session.execution.started", obj("""{"sessionID":"ses_m"}"""))
        val waiting = t.toChatMessages("ses_m")
        assertFalse(waiting[1].isStreaming)
        assertNotNull(waiting[1].tokenUsage)
        assertEquals(MessageRole.USER, waiting.last().role)
        // the new reply streams; the old one stays finished
        t.apply("session.step.started", obj("""{"sessionID":"ses_m","assistantMessageID":"msg_zzzzzzzzzzzz_reply2"}"""))
        t.apply("session.text.delta", obj("""{"sessionID":"ses_m","assistantMessageID":"msg_zzzzzzzzzzzz_reply2","ordinal":0,"delta":"hi"}"""))
        val streaming = t.toChatMessages("ses_m")
        assertFalse(streaming[1].isStreaming)
        assertNotNull(streaming[1].tokenUsage)
        assertTrue(streaming.last().isStreaming)
    }

    @Test
    fun retriesAreCountedAndAppErrorsSurviveReload() {
        val t = OpenCodeTranscript("ses_r")
        t.addLocalPrompt("msg_p", "hi", emptyList())
        t.apply("session.execution.started", obj("""{"sessionID":"ses_r"}"""))
        for (attempt in 2..5) t.apply("session.retry.scheduled", obj("""{"sessionID":"ses_r","attempt":$attempt,"error":{"type":"provider.rate-limit","message":"overloaded"}}"""))
        assertEquals(4, t.retries)
        assertTrue(t.retries > OpenCodeTranscript.MAX_RETRIES)
        assertTrue(t.retryNotice!!.contains("retry 4 of 3"))
        assertTrue(t.isWaitingOnModel)
        t.addError("Gave up after 3 retries", "The provider kept failing: overloaded")
        t.apply("session.execution.interrupted", obj("""{"sessionID":"ses_r","reason":"user"}"""))
        t.loadHistory(listOf(obj("""{"id":"msg_p","type":"user","text":"hi","time":{"created":1}}""")), active = false)
        val last = t.toChatMessages("ses_r").last()
        assertTrue(last.content.contains("Gave up after 3 retries"))
        // a new turn starts counting again
        t.apply("session.execution.started", obj("""{"sessionID":"ses_r"}"""))
        assertEquals(0, t.retries)
    }

    @Test
    fun runningCommandIsNotWaitingOnTheModel() {
        val t = OpenCodeTranscript("ses_w")
        t.apply("session.execution.started", obj("""{"sessionID":"ses_w"}"""))
        assertTrue(t.isWaitingOnModel)
        t.apply("session.tool.called", obj("""{"sessionID":"ses_w","assistantMessageID":"msg_a","id":"t1","name":"shell","input":{"command":"sleep 300"}}"""))
        assertFalse(t.isWaitingOnModel)
        t.apply("session.tool.success", obj("""{"sessionID":"ses_w","assistantMessageID":"msg_a","id":"t1","content":[{"type":"text","text":"ok"}]}"""))
        assertTrue(t.isWaitingOnModel)
    }

    @Test
    fun promptBeforeFindsTheQuestionOfAReply() {
        val t = OpenCodeTranscript("ses_m")
        t.loadHistory(miniHistory, active = false)
        val msgs = t.toChatMessages("ses_m")
        assertEquals("msg_a1", t.promptBefore(msgs[1].id)?.id)
    }

    private fun obj(json: String) = OpenCodeJson.parseToJsonElement(json).jsonObject
}
