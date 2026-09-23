package com.example.gemini.domain.chat

import com.example.gemini.data.remote.dto.*
import com.example.gemini.domain.model.ChatTurn
import com.example.gemini.domain.model.TurnBlock
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryErrorIngestionTest {

    @Test
    fun testErrorMessageStepIngestion() {
        val engine = TrajectoryEngine()
        engine.reset("test-conv-error")

        // 1. User prompt submitted
        engine.submitUserPrompt("Hello error test", emptyList(), "test-conv-error")

        // 2. Stream frame with CORTEX_STEP_TYPE_ERROR_MESSAGE
        val errorFrame = AgyStreamFrameDto(
            update = AgyMainUpdateDto(
                conversationId = "test-conv-error",
                trajectoryId = "traj-123",
                status = "CASCADE_RUN_STATUS_IDLE",
                fullyIdle = true,
                mainTrajectoryUpdate = AgyMainTrajectoryUpdateDto(
                    stepsUpdate = AgyStepsUpdateDto(
                        indices = listOf(0, 1),
                        steps = listOf(
                            CortexStepDto(
                                type = "CORTEX_STEP_TYPE_USER_INPUT",
                                status = "CORTEX_STEP_STATUS_DONE",
                                userInput = CortexUserInputDto(content = "Hello error test")
                            ),
                            CortexStepDto(
                                type = "CORTEX_STEP_TYPE_ERROR_MESSAGE",
                                status = "CORTEX_STEP_STATUS_DONE",
                                errorMessage = CortexErrorMessageDto(
                                    error = CortexErrorDto(
                                        userErrorMessage = "Agent execution terminated due to error.",
                                        shortError = "failed to construct executor: unknown model key MODEL_XYZ: model not found",
                                        fullError = "stack trace ... model not found",
                                        errorCode = 2,
                                        errorId = "err-123"
                                    ),
                                    shouldShowUser = true
                                )
                            )
                        ),
                        totalLength = 2
                    ),
                    lastStepError = CortexErrorDto(
                        userErrorMessage = "Agent execution terminated due to error.",
                        shortError = "failed to construct executor: unknown model key MODEL_XYZ: model not found",
                        fullError = "stack trace ... model not found",
                        errorCode = 2,
                        errorId = "err-123"
                    )
                )
            )
        )

        val turns = engine.ingestFrame(errorFrame)
        assertTrue(turns.isNotEmpty())

        val asstTurn = turns.filterIsInstance<ChatTurn.Assistant>().firstOrNull()
        assertNotNull(asstTurn)

        val errBlock = asstTurn!!.blocks.filterIsInstance<TurnBlock.ErrorNotice>().firstOrNull()
        assertNotNull(errBlock)
        assertEquals("Model Configuration Error", errBlock!!.title)
        assertEquals("Agent execution terminated due to error.", errBlock.userMessage)
        assertEquals("failed to construct executor: unknown model key MODEL_XYZ: model not found", errBlock.shortError)
        assertEquals(2, errBlock.errorCode)
        assertEquals("err-123", errBlock.errorId)

        // 3. Convert to ChatMessages and verify Markdown parsing
        val msgs = engine.toChatMessages("test-conv-error")
        val asstMsg = msgs.firstOrNull { it.role == com.example.gemini.domain.model.MessageRole.ASSISTANT }
        assertNotNull(asstMsg)
        assertTrue(asstMsg!!.content.contains("<!-- error:1 -->"))

        // 4. Verify parseMarkdownBlocks creates AgentError card
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
    fun testWarningFallbackParsing() {
        val warningText = "⚠️ Authentication Required\nPlease log in to your account to continue.\n\nFull stack trace at line 42"
        val mdBlocks = parseMarkdownBlocks(warningText)
        val agentErr = mdBlocks.filterIsInstance<MarkdownBlock.AgentError>().firstOrNull()
        assertNotNull(agentErr)
        assertEquals("Authentication Required", agentErr!!.title)
        assertTrue(agentErr.userMessage.contains("Please log in"))
    }
}

