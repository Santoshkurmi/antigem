package com.example.gemini.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
    SUMMARY
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
data class TokenUsage(
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0L,
    val isEstimated: Boolean = false
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
    val tokenUsage: TokenUsage? = null,
    val rawPayload: String? = null,
    val rawContent: String? = null,
    val contextSummary: String? = null,
    val stepIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val modelId: String = "claude-sonnet-4-5-thinking",
    val sessionId: String = UUID.randomUUID().toString(),
    val summary: String? = null,
    val customSystemPrompt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ModelFamily {
    CLAUDE,
    GEMINI,
    OTHER
}

@Serializable
data class AiModel(
    val id: String,
    val displayName: String,
    val family: ModelFamily = when {
        id.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
        id.contains("gemini", ignoreCase = true) -> ModelFamily.GEMINI
        else -> ModelFamily.OTHER
    },
    val supportsThinking: Boolean = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true) || id.contains("high", ignoreCase = true) || id.contains("medium", ignoreCase = true) || id.contains("low", ignoreCase = true),
    val description: String = "",
    val isDefault: Boolean = false
) {
    companion object {
        val DEFAULT_MODELS = emptyList<AiModel>()

        fun fromApi(id: String, displayName: String?, description: String? = null): AiModel {
            val name = displayName?.takeIf { it.isNotBlank() } ?: formatModelName(id)
            val family = when {
                id.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                id.contains("gemini", ignoreCase = true) -> ModelFamily.GEMINI
                else -> ModelFamily.OTHER
            }
            val thinking = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true) || id.contains("high", ignoreCase = true) || id.contains("medium", ignoreCase = true) || id.contains("low", ignoreCase = true)
            val desc = description ?: when (family) {
                ModelFamily.CLAUDE -> "Anthropic Claude via Antigravity"
                ModelFamily.GEMINI -> "Google Gemini via Antigravity"
                ModelFamily.OTHER -> "OpenAI / Other Model via Antigravity"
            }
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
data class QuotaWindowInfo(
    val window: String = "5h",
    val displayName: String = "",
    val remainingFraction: Float = 1.0f,
    val remainingPct: String = "100.0%",
    val usedPct: String = "0.0%",
    val resetTime: String? = null,
    val countdown: String = "",
    val description: String = ""
)

@Serializable
data class ModelQuotaGroup(
    val groupId: String = "",
    val groupName: String = "",
    val description: String = "",
    val fiveHour: QuotaWindowInfo? = null,
    val weekly: QuotaWindowInfo? = null
)

@Serializable
data class QuotaSummaryResponse(
    val groups: List<ModelQuotaGroup> = emptyList(),
    val lastUpdated: String? = null
)

@Serializable
data class ModelQuota(
    val modelId: String,
    val remainingFraction: Float? = null,
    val resetTime: String? = null,
    val usedPercentage: String? = null,
    val resetCountdown: String? = null,
    val weeklyRemainingFraction: Float? = null,
    val weeklyUsedPercentage: String? = null,
    val weeklyResetCountdown: String? = null
) {
    val percentage: Int
        get() = remainingFraction?.let { (it * 100).toInt() } ?: 0
}
