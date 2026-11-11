package com.example.gemini.data.agent.claude

import com.example.gemini.domain.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSubagentTranscriptTest {
    private val entries = listOf(
        """{"type":"user","uuid":"s1","parentUuid":null,"isSidechain":true,"message":{"role":"user","content":"find the files"}}""",
        """{"type":"assistant","uuid":"s2","parentUuid":"s1","isSidechain":true,"message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"found them"}]}}"""
    ).map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun subagentTranscriptShowsItsSidechain() {
        assertTrue(ClaudeTranscript.isSubagentId("agent-abc"))
        assertFalse(ClaudeTranscript.isSubagentId("11111111-2222-3333-4444-555555555555"))
        val t = ClaudeTranscript().apply { reset("agent-abc") }
        entries.forEach { t.applyHistory(it) }
        t.finishHistory()
        val msgs = t.toChatMessages()
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), msgs.map { it.role })
        assertTrue(msgs.last().content.contains("found them"))
    }

    @Test
    fun normalChatStillSkipsSidechains() {
        val t = ClaudeTranscript().apply { reset("11111111-2222-3333-4444-555555555555") }
        entries.forEach { t.applyHistory(it) }
        t.finishHistory()
        assertTrue(t.toChatMessages().isEmpty())
    }
}
