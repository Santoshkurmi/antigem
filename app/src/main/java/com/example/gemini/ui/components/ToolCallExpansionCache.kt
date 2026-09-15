package com.example.gemini.ui.components

import androidx.compose.runtime.mutableStateMapOf
import com.example.gemini.domain.model.ToolCall

/**
 * In-RAM expansion state cache for tool calls in chat.
 * Survives LazyColumn item recycling/scrolling.
 * Clears automatically when the user switches to a different conversation.
 */
object ToolCallExpansionCache {
    private var activeChatId: String? = null
    val expansionMap = mutableStateMapOf<String, Boolean>()

    fun setChat(chatId: String?) {
        if (chatId != activeChatId) {
            activeChatId = chatId
            expansionMap.clear()
        }
    }

    fun isExpanded(toolCall: ToolCall, default: Boolean = false): Boolean {
        checkChatContext(toolCall)
        val key = getKey(toolCall)
        return expansionMap[key] ?: default
    }

    fun setExpanded(toolCall: ToolCall, expanded: Boolean) {
        checkChatContext(toolCall)
        val key = getKey(toolCall)
        expansionMap[key] = expanded
    }

    fun toggle(toolCall: ToolCall, default: Boolean = false): Boolean {
        val next = !isExpanded(toolCall, default)
        setExpanded(toolCall, next)
        return next
    }

    private fun checkChatContext(toolCall: ToolCall) {
        val convId = toolCall.trajectoryId?.takeIf { it.isNotBlank() }
        if (convId != null && activeChatId != null && convId != activeChatId) {
            activeChatId = convId
            expansionMap.clear()
        }
    }

    fun getKey(toolCall: ToolCall): String {
        return if (toolCall.stepIndex != null) {
            "step_${toolCall.stepIndex}"
        } else {
            "id_${toolCall.id}"
        }
    }

    fun clear() {
        activeChatId = null
        expansionMap.clear()
    }
}

/**
 * In-RAM expansion state cache for code blocks in chat.
 * Survives LazyColumn recycling when scrolled out of view.
 * Clears automatically when switching conversations.
 */
object CodeBlockExpansionCache {
    private var activeChatId: String? = null
    val expansionMap = mutableStateMapOf<String, Boolean>()

    fun setChat(chatId: String?) {
        if (chatId != activeChatId) {
            activeChatId = chatId
            expansionMap.clear()
        }
    }

    fun isExpanded(key: String, default: Boolean = false): Boolean {
        return expansionMap[key] ?: default
    }

    fun setExpanded(key: String, expanded: Boolean) {
        expansionMap[key] = expanded
    }

    fun toggle(key: String, default: Boolean = false): Boolean {
        val next = !isExpanded(key, default)
        setExpanded(key, next)
        return next
    }

    fun clear() {
        activeChatId = null
        expansionMap.clear()
    }
}

