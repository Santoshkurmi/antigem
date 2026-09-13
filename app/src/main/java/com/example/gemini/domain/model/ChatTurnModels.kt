package com.example.gemini.domain.model

import com.example.gemini.data.remote.dto.CortexRequestedInteractionDto
import kotlinx.serialization.Serializable

/**
 * High-level conversational turn representing either a User prompt or an Assistant response.
 */
sealed interface ChatTurn {
    val key: String

    data class User(
        val stepIndex: Int,
        val text: String,
        val attachments: List<ChatAttachment> = emptyList()
    ) : ChatTurn {
        override val key: String get() = "user_turn_$stepIndex"
    }

    data class Assistant(
        val turnId: String,
        val blocks: List<TurnBlock>,
        val isStreaming: Boolean = false
    ) : ChatTurn {
        override val key: String get() = "assistant_turn_$turnId"
    }
}

/**
 * Individual ordered block within an Assistant Turn.
 * Keyed strictly by type-prefixed stepIndex to guarantee stable, flicker-free rendering in Compose.
 */
sealed interface TurnBlock {
    val stepIndex: Int
    val key: String

    data class Thinking(
        override val stepIndex: Int,
        val thought: String,
        val durationMs: Long? = null,
        val isStreaming: Boolean = false
    ) : TurnBlock {
        override val key: String get() = "thought_$stepIndex"
    }

    data class Text(
        override val stepIndex: Int,
        val markdown: String,
        val isStreaming: Boolean = false
    ) : TurnBlock {
        override val key: String get() = "text_$stepIndex"
    }

    data class Tool(
        override val stepIndex: Int,
        val toolCall: ToolCall
    ) : TurnBlock {
        override val key: String get() = "tool_$stepIndex"
    }

    data class Permission(
        override val stepIndex: Int,
        val trajectoryId: String,
        val interaction: CortexRequestedInteractionDto
    ) : TurnBlock {
        override val key: String get() = "perm_$stepIndex"
    }

    data class SystemNotice(
        override val stepIndex: Int,
        val title: String = "",
        val content: String
    ) : TurnBlock {
        override val key: String get() = "notice_$stepIndex"
    }

    data class ErrorNotice(
        override val stepIndex: Int,
        val message: String
    ) : TurnBlock {
        override val key: String get() = "err_$stepIndex"
    }
}
