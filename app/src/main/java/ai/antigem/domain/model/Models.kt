package ai.antigem.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID
import ai.antigem.i18n.L

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
    SUMMARY
}

enum class ToolType {
    BASH,
    VIEW_FILE,
    EDIT_FILE,
    LIST_DIR,
    GREP_SEARCH,
    FIND,
    SEARCH_WEB,
    READ_URL,
    GENERATE_IMAGE,
    MCP,
    ASK_CHOICE,
    MATH,
    SYSTEM_NOTIFICATION,
    UNKNOWN
}

@Serializable
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "bash",
    val toolType: ToolType = ToolType.BASH,
    val command: String,
    val status: String = "RUNNING", // RUNNING, SUCCESS, FAILED, TERMINATED
    val output: String = "",
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val stepIndex: Int? = null,
    val trajectoryId: String? = null,
    val interactionType: String? = null,
    val questionnaire: ChoiceQuestionnaire? = null
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0L,
    val isEstimated: Boolean = false,
    val costUsd: Double? = null,
    /** The model that produced the reply, when the agent reports it (Claude). */
    val model: String? = null
)

@Serializable
data class ArtifactSnapshot(
    val name: String = "",
    val absoluteUri: String = "",
    val lastEdited: String = "",
    val summary: String = "",
    val requestFeedback: Boolean = false,
    val userFacing: Boolean = false
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
    val attachments: List<ChatAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** The model that wrote this reply, as the agent reports it (null when unknown). */
    val modelId: String? = null,
    /** A request an Antigravity voice call sent: [content] is what was said (the full prompt is in [rawContent]). */
    val viaVoice: Boolean = false,
    /** What the voice said while handing that request to the agent. */
    val voiceLine: String? = null,
    /** A user message: the id its project checkpoint was saved under, when the agent's transcript knows it. */
    val checkpointKey: String? = null,
    /** A user message whose [content] the chat shows collapsed (hidden parts removed): the text as it was sent. */
    val sentContent: String? = null
)

@Serializable
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val isImage: Boolean = false,
    val isAudio: Boolean = false,
    val localUri: String? = null,
    val size: Long = 0L,
    val url: String? = null,
    val durationSeconds: Int = 0,
    val mimeType: String? = null,
    val base64: String? = null,
    /**
     * A file the agent opens in place: [path] is its real path on the agent's machine and it goes to the agent only as a
     * line in the message's hidden `<attached-files>` list (see AttachedFiles). An image may also carry [base64], a
     * shrunk copy the model sees directly. Never uploaded or inlined by the backends.
     */
    val reference: Boolean = false
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val modelId: String = "",
    val sessionId: String = UUID.randomUUID().toString(),
    val summary: String? = null,
    val customSystemPrompt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isRunning: Boolean = false,
    val notFullyIdle: Boolean = false,
    val hasActivity: Boolean = false,
    val runStatus: String = "CASCADE_RUN_STATUS_IDLE",
    val stepCount: Int = 0,
    val workspaceUri: String = "",
    val parentConversationId: String? = null,
    val subagentRole: String? = null,
    val subagentTypeName: String? = null,
    val nestingDepth: Int = 0,
    val agent: AgentKind = AgentKind.AGY,
    /** Archived: listed only in the sidebar's archive view. */
    val archived: Boolean = false,
    /** An Antigravity cloud chat (Remote Agents): the cloud project it runs in; null = runs on the hub's machine. */
    val cloudProjectId: String? = null,
    /** Can only be read: no sending, sidebar actions, fork or rewind (a Claude Code subagent's transcript). */
    val readOnly: Boolean = false
) {
    val isCloud: Boolean get() = cloudProjectId != null
}

/** A message sent while the agent was working, waiting to be delivered (shown in the queued-messages card). */
data class QueuedMessage(
    val id: String,
    val text: String,
    val attachmentCount: Int = 0,
    /** "Send now" is offered (interrupts the current work and delivers this message). */
    val canSendNow: Boolean = true,
    /** "Delete" is offered (the message is removed from the queue). */
    val canDelete: Boolean = true,
    /** Not confirmed by the agent yet (just sent from this app). */
    val isPending: Boolean = false
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
        id.contains("claude", ignoreCase = true) || displayName.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
        else -> ModelFamily.GEMINI
    },
    val supportsThinking: Boolean = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true) || id.contains("high", ignoreCase = true) || id.contains("medium", ignoreCase = true) || id.contains("low", ignoreCase = true),
    val description: String = "",
    val isDefault: Boolean = false,
    val key: String = "",
    val baseName: String = "",
    val tier: String? = null,
    /** Whether the model reads images: false = text only (the chat warns about attached images), null = not known. */
    val supportsImages: Boolean? = null
) {
    companion object {
        val DEFAULT_MODELS = emptyList<AiModel>()

        fun fromApi(id: String, displayName: String?, description: String? = null): AiModel {
            val name = displayName?.takeIf { it.isNotBlank() } ?: formatModelName(id)
            val family = when {
                id.contains("claude", ignoreCase = true) || name.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                else -> ModelFamily.GEMINI
            }
            val thinking = id.contains("thinking", ignoreCase = true) || id.contains("flash", ignoreCase = true) || id.contains("pro", ignoreCase = true) || id.contains("high", ignoreCase = true) || id.contains("medium", ignoreCase = true) || id.contains("low", ignoreCase = true)
            val desc = description ?: when (family) {
                ModelFamily.CLAUDE -> L.model.models.anthropicClaudeViaAntigravity
                ModelFamily.GEMINI -> L.model.models.googleGeminiViaAntigravity
                ModelFamily.OTHER -> L.model.models.otherModelViaAntigravity
            }
            var baseName = name
            var tier: String? = null
            val tierMatch = Regex("^(.*?)\\s*\\((High|Medium|Low|Med|Thinking)\\)$", RegexOption.IGNORE_CASE).find(name)
            if (tierMatch != null) {
                baseName = tierMatch.groupValues[1].trim()
                tier = tierMatch.groupValues[2].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            return AiModel(
                id = id,
                displayName = name,
                family = family,
                supportsThinking = thinking,
                description = desc,
                key = id,
                baseName = baseName,
                tier = tier
            )
        }

        private fun formatModelName(id: String): String {
            if (id.startsWith("MODEL_", ignoreCase = true)) {
                return "Gemini"
            }
            return id.split("-", "_").joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }

        fun findInList(models: List<AiModel>, id: String, fallbackDisplayName: String? = null): AiModel {
            val found = models.find { it.id == id || (it.key.isNotBlank() && it.key.equals(id, ignoreCase = true)) }
            if (found != null) return found
            if (!fallbackDisplayName.isNullOrBlank()) {
                return fromApi(id.ifBlank { "gemini" }, fallbackDisplayName)
            }
            if (id.isNotBlank() && !id.startsWith("MODEL_", ignoreCase = true)) {
                return fromApi(id, null)
            }
            return if (models.isNotEmpty()) models.first() else AiModel("gemini", "Gemini")
        }
    }
}

enum class ThinkingLevel(private val labelText: () -> String, val tokens: Int, private val descriptionText: () -> String) {
    OFF({ L.model.models.off }, 0, { L.model.models.n0TokensDirectResponseNoReasoning }),
    LOW({ L.model.models.low }, 1024, { L.model.models.n1024Tokens4000Chars }),
    MEDIUM({ L.model.models.med }, 4096, { L.model.models.n4096Tokens16000Chars }),
    HIGH({ L.model.models.high }, 8192, { L.model.models.n8192Tokens32000Chars }),
    CUSTOM({ L.model.models.custom }, 0, { L.model.models.customTokenBudget });
    val label: String get() = labelText()
    val description: String get() = descriptionText()
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
            ThinkingLevel.OFF -> L.model.models.thinkingOff
            ThinkingLevel.LOW -> L.model.models.thinkingLow1k
            ThinkingLevel.MEDIUM -> L.model.models.thinkingMed4k
            ThinkingLevel.HIGH -> L.model.models.thinkingHigh8k
            ThinkingLevel.CUSTOM -> L.model.models.thinkingCustomTokens(activeTokens)
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
    val weeklyResetCountdown: String? = null,
    /** Share left of the window the chat pill shows (5h or 7d, the user's choice; 7d when there is no 5h limit). */
    val pillFraction: Float? = null,
    val pillIsWeekly: Boolean = false,
    /** The window comes with decimals (Antigravity), shown as "48.8%". */
    val pillOneDecimal: Boolean = false
) {
    val percentage: Int
        get() = remainingFraction?.let { (it * 100).toInt() } ?: 0

    /** Share left the chat pill shows, or null when this model has no limit to show. */
    val pillRemaining: Float?
        get() = pillFraction ?: remainingFraction

    /** The chat pill's text, formatted exactly like the model picker's meters ("48.8%" for Antigravity, "75%" for Claude). */
    val pillText: String?
        get() = pillRemaining?.let { quotaPercentText(it, pillOneDecimal) }
}

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String = "",
    val inputSchema: String = ""
)

@Serializable
data class McpServerSpec(
    val serverName: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val serverUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val disabled: Boolean = false,
    val disabledTools: List<String> = emptyList(),
    val cwd: String = ""
)

@Serializable
data class McpServerState(
    val name: String,
    val spec: McpServerSpec? = null,
    val status: String = "MCP_SERVER_STATUS_UNKNOWN",
    val error: String? = null,
    val tools: List<McpToolInfo> = emptyList(),
    val instructions: String? = null,
    val isEnabled: Boolean = true
)
