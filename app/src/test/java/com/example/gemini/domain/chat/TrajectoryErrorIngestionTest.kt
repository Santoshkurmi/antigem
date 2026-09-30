package com.example.gemini.domain.chat

import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolType
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.parseMarkdownBlocks
import exa.language_server_pb.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryErrorIngestionTest {

    @Test
    fun testErrorMessageStepIngestion() {
        val engine = TrajectoryEngine()
        engine.reset("test-conv-error")

        val steps = listOf(
            Step(
                type = CortexStepType.CORTEX_STEP_TYPE_USER_INPUT,
                status = CortexStepStatus.CORTEX_STEP_STATUS_DONE,
                user_input = CortexStepUserInput(
                    query = "Hello error test"
                )
            ),
            Step(
                type = CortexStepType.CORTEX_STEP_TYPE_ERROR_MESSAGE,
                status = CortexStepStatus.CORTEX_STEP_STATUS_DONE,
                error_message = CortexStepErrorMessage(
                    error = CortexErrorDetails(
                        user_error_message = "Agent execution terminated due to error.",
                        short_error = "failed to construct executor: unknown model key MODEL_XYZ: model not found",
                        full_error = "stack trace ... model not found",
                        error_code = 2,
                        error_id = "err-123"
                    ),
                    should_show_user = true
                )
            )
        )

        val msgs = engine.ingestStepsDirect(steps, "test-conv-error")
        val asstMsg = msgs.firstOrNull { it.role == MessageRole.ASSISTANT }
        assertNotNull(asstMsg)
        assertTrue(asstMsg!!.content.contains("<!-- error:1 -->"))

        val mdBlocks = parseMarkdownBlocks(asstMsg.content)
        val agentErr = mdBlocks.filterIsInstance<MarkdownBlock.AgentError>().firstOrNull()
        assertNotNull(agentErr)
        assertEquals("Model Configuration Error", agentErr!!.title)
        assertEquals("Agent execution terminated due to error.", agentErr.userMessage)
        assertEquals("failed to construct executor: unknown model key MODEL_XYZ: model not found", agentErr.shortError)
        assertEquals("stack trace ... model not found", agentErr.fullError)
        assertEquals(2, agentErr.errorCode)
    }

    @Test
    fun testSystemNoticeToolCallIngestion() {
        val engine = TrajectoryEngine()
        engine.reset("test-conv-sys")

        val steps = listOf(
            Step(
                type = CortexStepType.CORTEX_STEP_TYPE_USER_INPUT,
                status = CortexStepStatus.CORTEX_STEP_STATUS_DONE,
                user_input = CortexStepUserInput(query = "Schedule a timer")
            ),
            Step(
                type = CortexStepType.CORTEX_STEP_TYPE_SYSTEM_MESSAGE,
                status = CortexStepStatus.CORTEX_STEP_STATUS_DONE,
                system_message = CortexStepSystemMessage(
                    message = "Schedule timer: Timer has expired: [Message] timestamp=2026-09-25T16:02:39Z sender=task-2 content=30 seconds have passed.",
                    render_info = StepRenderInfo(title = "Schedule Timer")
                )
            )
        )

        val msgs = engine.ingestStepsDirect(steps, "test-conv-sys")
        val asstMsg = msgs.firstOrNull { it.role == MessageRole.ASSISTANT }
        assertNotNull(asstMsg)
        assertEquals(1, asstMsg!!.toolCalls.size)
        val tool = asstMsg.toolCalls.first()
        assertEquals(ToolType.SYSTEM_NOTIFICATION, tool.toolType)
        assertEquals("Schedule Timer", tool.command)
        assertTrue(tool.output.contains("30 seconds have passed"))
        assertTrue(asstMsg.content.contains("<!-- tool_call:notice_1 -->"))
    }

    @Test
    fun testWarningFallbackParsing() {
        val warningText = "⚠️ Authentication Required\nPlease log in to your account to continue.\n\nFull stack trace at line 42"
        val mdBlocks = parseMarkdownBlocks(warningText)
        val agentErr = mdBlocks.filterIsInstance<MarkdownBlock.AgentError>().firstOrNull()
        assertNotNull(agentErr)
        assertEquals("Authentication Required", agentErr!!.title)
        assertTrue(agentErr.userMessage.contains("Please log in"))
    }
}
