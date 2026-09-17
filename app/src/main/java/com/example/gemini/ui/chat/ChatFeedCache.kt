package com.example.gemini.ui.chat

import androidx.compose.runtime.Immutable
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.parseMarkdownBlocks
import com.example.gemini.ui.components.CodeBlockCache
import com.example.gemini.ui.components.MarkdownTextCache
import java.util.concurrent.ConcurrentHashMap

@Immutable
sealed class ChatFeedItem(val key: String, val contentType: String) {
    @Immutable
    data class Summary(val message: ChatMessage) : ChatFeedItem("summary_${message.id}", "SUMMARY")
    @Immutable
    data class User(val message: ChatMessage) : ChatFeedItem("user_${message.id}", "USER")
    @Immutable
    data class AssistantThinking(val messageId: String, val thoughtText: String, val durationMs: Long?, val isStreaming: Boolean) : ChatFeedItem("thought_$messageId", "THOUGHT")
    @Immutable
    data class AssistantMessage(val message: ChatMessage, val blocks: List<MarkdownBlock>) : ChatFeedItem("assistant_${message.id}", "ASSISTANT")
    @Immutable
    data class AssistantTyping(val messageId: String, val modelId: String) : ChatFeedItem("typing_$messageId", "TYPING")
    @Immutable
    data class AssistantFooter(val message: ChatMessage) : ChatFeedItem("footer_${message.id}", "FOOTER")
    @Immutable
    data class StreamingMessage(val message: ChatMessage) : ChatFeedItem("streaming_${message.id}", "STREAMING")
}

object ChatFeedCache {
    val cache = ConcurrentHashMap<String, List<ChatFeedItem>>()

    fun getOrParse(msg: ChatMessage): List<ChatFeedItem> {
        val cacheKey = "${msg.id}_${msg.content.hashCode()}_${msg.toolCalls.hashCode()}_${msg.thoughtText?.hashCode() ?: 0}"
        val cached = cache[cacheKey]
        if (cached != null) return cached

        val msgItems = mutableListOf<ChatFeedItem>()
        when (msg.role) {
            MessageRole.SUMMARY -> {
                msgItems.add(ChatFeedItem.Summary(msg))
            }
            MessageRole.USER -> {
                msgItems.add(ChatFeedItem.User(msg))
            }
            MessageRole.ASSISTANT -> {
                val contentToParse = if (!msg.thoughtText.isNullOrEmpty() && !msg.content.contains("<!-- thought") && !msg.content.contains("<thought")) {
                    "<!-- thought -->\n${msg.thoughtText}\n<!-- /thought -->\n${msg.content}"
                } else {
                    msg.content
                }
                if (contentToParse.isNotEmpty() || msg.toolCalls.isNotEmpty()) {
                    val blocks = parseMarkdownBlocks(contentToParse, msg.toolCalls)
                    msgItems.add(ChatFeedItem.AssistantMessage(
                        message = msg,
                        blocks = blocks
                    ))
                }
                if (msg.content.isNotEmpty()) {
                    msgItems.add(ChatFeedItem.AssistantFooter(msg))
                }
            }
            else -> {}
        }
        cache[cacheKey] = msgItems
        return msgItems
    }

    /**
     * Prewarm markdown blocks asynchronously on Dispatchers.Default so the UI thread
     * has zero parsing work during composition.
     */
    fun prewarm(messages: List<ChatMessage>) {
        for (msg in messages) {
            if (!msg.isStreaming) {
                val items = getOrParse(msg)
                for (item in items) {
                    if (item is ChatFeedItem.AssistantMessage) {
                        for (block in item.blocks) {
                            when (block) {
                                is MarkdownBlock.Code -> {
                                    CodeBlockCache.prewarm(block.code, block.language)
                                }
                                is MarkdownBlock.Paragraph -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                is MarkdownBlock.Header -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                is MarkdownBlock.Bullet -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                is MarkdownBlock.Numbered -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                is MarkdownBlock.Blockquote -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                is MarkdownBlock.Task -> {
                                    MarkdownTextCache.prewarm(block.text)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    fun buildFeedItems(
        messages: List<ChatMessage>,
        selectedModelId: String
    ): List<ChatFeedItem> {
        val result = ArrayList<ChatFeedItem>(messages.size * 2)
        val seenKeys = HashSet<String>()

        fun addItem(item: ChatFeedItem) {
            if (seenKeys.add(item.key)) {
                result.add(item)
            }
        }

        for (msg in messages) {
            if (!msg.isStreaming) {
                val parsed = getOrParse(msg)
                for (item in parsed) {
                    addItem(item)
                }
            } else {
                val hasActiveRunningTool = msg.toolCalls.any {
                    it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE"
                }
                val contentToParse = if (!msg.thoughtText.isNullOrEmpty() && !msg.content.contains("<!-- thought") && !msg.content.contains("<thought")) {
                    if (msg.content.isBlank()) {
                        "<!-- thought -->\n${msg.thoughtText}"
                    } else {
                        "<!-- thought -->\n${msg.thoughtText}\n<!-- /thought -->\n${msg.content}"
                    }
                } else {
                    msg.content
                }
                if (contentToParse.isNotEmpty() || msg.toolCalls.isNotEmpty()) {
                    val blocks = parseMarkdownBlocks(contentToParse, msg.toolCalls)
                    addItem(ChatFeedItem.AssistantMessage(
                        message = msg,
                        blocks = blocks
                    ))
                } else if (!hasActiveRunningTool) {
                    // Only show waiting indicator before ANY output or tool call has appeared
                    addItem(ChatFeedItem.AssistantTyping(msg.id, selectedModelId))
                }
            }
        }
        return result
    }

    fun clear() {
        cache.clear()
    }
}
