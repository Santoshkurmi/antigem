package com.example.gemini.domain.context

import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole

object ContextCompactor {

    // Threshold in estimated characters (~4 chars = 1 token, so 300k chars ~= 75k tokens)
    private const val COMPACTION_CHAR_THRESHOLD = 280_000

    fun estimateTokens(messages: List<ChatMessage>): Int {
        val totalChars = messages.sumOf { it.content.length + (it.thoughtText?.length ?: 0) }
        return totalChars / 4
    }

    fun needsCompaction(messages: List<ChatMessage>): Boolean {
        val totalChars = messages.sumOf { it.content.length }
        return totalChars > COMPACTION_CHAR_THRESHOLD && messages.size > 8
    }

    /**
     * Compacts older conversation turns into a summarized memory block
     * while retaining the latest turns in full fidelity.
     */
    fun compactHistory(messages: List<ChatMessage>): Pair<String, List<ChatMessage>> {
        if (messages.size <= 6) {
            return Pair("", messages)
        }

        // Keep last 4 turns intact
        val recentMessages = messages.takeLast(4)
        val olderMessages = messages.dropLast(4)

        // Build brief summary of older turns
        val summaryBuilder = StringBuilder()
        summaryBuilder.append("Key topics discussed: ")

        olderMessages.filter { it.role == MessageRole.USER }.takeLast(5).forEach { msg ->
            val preview = if (msg.content.length > 100) msg.content.take(100) + "..." else msg.content
            summaryBuilder.append("• User: $preview\n")
        }

        return Pair(summaryBuilder.toString(), recentMessages)
    }
}
