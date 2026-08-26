package com.example.gemini.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM
}

@Serializable
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "bash",
    val command: String,
    val status: String = "RUNNING", // RUNNING, SUCCESS, FAILED, TERMINATED
    val output: String = "",
    val exitCode: Int? = null,
    val durationMs: Long? = null
)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val thoughtText: String? = null,
    val thoughtDurationMs: Long? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val isStreaming: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val modelId: String = "claude-sonnet-4-5-thinking",
    val sessionId: String = UUID.randomUUID().toString(),
    val summary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ModelFamily {
    CLAUDE,
    GEMINI
}

@Serializable
data class AiModel(
    val id: String,
    val displayName: String,
    val family: ModelFamily = if (id.contains("claude", ignoreCase = true)) ModelFamily.CLAUDE else ModelFamily.GEMINI,
    val supportsThinking: Boolean = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true),
    val description: String = "",
    val isDefault: Boolean = false
) {
    companion object {
        val DEFAULT_MODELS = emptyList<AiModel>()

        fun fromApi(id: String, displayName: String?, description: String? = null): AiModel {
            val name = displayName?.takeIf { it.isNotBlank() } ?: formatModelName(id)
            val family = if (id.contains("claude", ignoreCase = true)) ModelFamily.CLAUDE else ModelFamily.GEMINI
            val thinking = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true)
            val desc = description ?: if (family == ModelFamily.CLAUDE) "Anthropic Claude via Antigravity" else "Google Gemini via Antigravity"
            return AiModel(
                id = id,
                displayName = name,
                family = family,
                supportsThinking = thinking,
                description = desc
            )
        }

        private fun formatModelName(id: String): String {
            return id.split("-", "_").joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }

        fun findInList(models: List<AiModel>, id: String): AiModel {
            return models.find { it.id == id } ?: if (id.isNotBlank()) fromApi(id, null) else AiModel("gemini", "Select Model")
        }
    }
}

enum class ThinkingLevel(val label: String, val tokens: Int, val description: String) {
    OFF("Off", 0, "0 tokens • Direct response (no reasoning)"),
    LOW("Low", 1024, "1,024 tokens (~4,000 chars) • Quick logic"),
    MEDIUM("Med", 4096, "4,096 tokens (~16,000 chars) • Standard"),
    HIGH("High", 8192, "8,192 tokens (~32,000 chars) • Deep thinking"),
    CUSTOM("Custom", 0, "Custom token budget")
}

data class ThinkingPreference(
    val level: ThinkingLevel = ThinkingLevel.MEDIUM,
    val customTokens: Int = 4096
) {
    val activeTokens: Int
        get() = if (level == ThinkingLevel.CUSTOM) customTokens else level.tokens

    val isEnabled: Boolean
        get() = level != ThinkingLevel.OFF && activeTokens > 0

    val displaySummary: String
        get() = when (level) {
            ThinkingLevel.OFF -> "Thinking: Off"
            ThinkingLevel.LOW -> "Thinking: Low (1K)"
            ThinkingLevel.MEDIUM -> "Thinking: Med (4K)"
            ThinkingLevel.HIGH -> "Thinking: High (8K)"
            ThinkingLevel.CUSTOM -> "Thinking: Custom (${activeTokens})"
        }
}

@Serializable
data class ModelQuota(
    val modelId: String,
    val remainingFraction: Float? = null,
    val resetTime: String? = null
) {
    val percentage: Int
        get() = remainingFraction?.let { (it * 100).toInt() } ?: 0
}
