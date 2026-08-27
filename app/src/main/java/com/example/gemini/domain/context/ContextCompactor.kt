package com.example.gemini.domain.context

import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.util.UUID

data class SummarizationResult(
    val summary: String,
    val title: String? = null
)

object ContextCompactor {

    /**
     * Splits chat history into (olderMessages, recentMessages) based on contextWindowLimit.
     */
    fun splitHistory(
        messages: List<ChatMessage>,
        windowLimit: Int
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (messages.size <= windowLimit) {
            return Pair(emptyList(), messages)
        }
        val splitIndex = messages.size - windowLimit
        val older = messages.take(splitIndex)
        val recent = messages.drop(splitIndex)
        return Pair(older, recent)
    }

    /**
     * Builds the prompt sent to the LLM to generate an executive context summary and updated title.
     */
    fun buildSummarizationPrompt(
        messagesToSummarize: List<ChatMessage>,
        existingSummary: String? = null
    ): String {
        val conversationText = buildString {
            if (!existingSummary.isNullOrBlank()) {
                appendLine("=== PREVIOUS CONTEXT SUMMARY ===")
                appendLine(existingSummary)
                appendLine("================================\n")
            }

            appendLine("=== CONVERSATION TURNS TO SUMMARIZE ===")
            for (msg in messagesToSummarize) {
                val roleName = if (msg.role == MessageRole.USER) "User" else "Assistant"
                val cleanContent = msg.content.replace(Regex("<!--\\s*tool_call:[a-zA-Z0-9_-]+\\s*-->"), "").trim()
                appendLine("[$roleName]: $cleanContent")

                if (msg.toolCalls.isNotEmpty()) {
                    for (tool in msg.toolCalls) {
                        appendLine("  [Tool Executed: ${tool.name}] command: ${tool.command.take(120)} (status: ${tool.status}, exit: ${tool.exitCode ?: 0})")
                        if (tool.output.isNotBlank()) {
                            val shortOut = if (tool.output.length > 250) tool.output.take(250) + "..." else tool.output
                            appendLine("  [Tool Output Snippet]: $shortOut")
                        }
                    }
                }
                appendLine()
            }
            appendLine("=======================================")
        }

        return """
You are an expert AI Context Summarizer. Condense the following conversation history into a high-density, crystal-clear executive memory block that an AI can use to continue working seamlessly without losing any critical context.

$conversationText

INSTRUCTIONS:
1. Output a refined, comprehensive 3-6 word title for this overall conversation enclosed in <chat_title>...</chat_title> at the very top (e.g. <chat_title>Spring Boot Auth Migration</chat_title>).
2. Produce a concise, structured markdown summary (around 150-300 words) with these exact sections:
• **Core Goal & Requirements**: What the user is building or asking for.
• **Key Decisions & Architecture**: Important choices made, libraries/frameworks chosen, preferences specified.
• **Work Accomplished & Tool Results**: Files created/modified, commands run, features implemented, and key outcomes.
• **Current Status & Pending Tasks**: Where the conversation currently left off and what needs to happen next.

Keep it factual, concise, and focused on code, files, decisions, and outcomes. Do NOT include filler words.
""".trimIndent()
    }

    /**
     * Executes the summarization API call using the selected model.
     */
    suspend fun executeSummarization(
        apiService: AntigravityApiService,
        token: String,
        projectId: String,
        modelId: String,
        messagesToSummarize: List<ChatMessage>,
        existingSummary: String? = null
    ): Result<SummarizationResult> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildSummarizationPrompt(messagesToSummarize, existingSummary)
            val syntheticMessage = ChatMessage(
                conversationId = "summary-temp",
                role = MessageRole.USER,
                content = prompt
            )

            val summaryBuilder = StringBuilder()
            val thoughtBuilder = StringBuilder()

            android.util.Log.d("GeminiApp", "[ContextCompactor] Starting summarization with model: $modelId, messages: ${messagesToSummarize.size}")

            apiService.streamGenerateContent(
                token = token,
                projectId = projectId,
                modelId = modelId,
                sessionId = "summary-" + UUID.randomUUID().toString(),
                messages = listOf(syntheticMessage),
                summary = null,
                thinkingBudget = 0,
                isThinkingEnabled = false,
                toolInstruction = null
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> summaryBuilder.append(event.text)
                    is StreamEvent.ThoughtChunk -> thoughtBuilder.append(event.thought)
                    is StreamEvent.Error -> throw Exception(event.message)
                    else -> {}
                }
            }

            var finalSummary = summaryBuilder.toString().trim()
            if (finalSummary.isBlank() && thoughtBuilder.isNotBlank()) {
                finalSummary = thoughtBuilder.toString().trim()
            }

            if (finalSummary.isBlank()) {
                return@withContext Result.failure(Exception("Summarizer returned empty content"))
            }

            var extractedTitle: String? = null
            val titleMatch = Regex("<chat_title>([\\s\\S]*?)</chat_title>", RegexOption.IGNORE_CASE).find(finalSummary)
            if (titleMatch != null) {
                extractedTitle = titleMatch.groupValues[1].trim().replace("\"", "").replace("'", "")
                finalSummary = finalSummary.replace(Regex("<chat_title>[\\s\\S]*?</chat_title>\\s*", RegexOption.IGNORE_CASE), "").trim()
            }

            android.util.Log.d("GeminiApp", "[ContextCompactor] Summarization completed! Title: '$extractedTitle', Length: ${finalSummary.length}")
            Result.success(SummarizationResult(summary = finalSummary, title = extractedTitle))
        } catch (e: Exception) {
            android.util.Log.e("GeminiApp", "[ContextCompactor] Summarization failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
