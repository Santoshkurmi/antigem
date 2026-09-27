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
    data class AssistantBlock(val messageId: String, val blockIndex: Int, val block: MarkdownBlock, val isFirst: Boolean, val isLast: Boolean) : ChatFeedItem("block_${messageId}_$blockIndex", block.javaClass.simpleName)
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
        val cacheKey = "${msg.id}_${msg.content.hashCode()}_${msg.toolCalls.hashCode()}_${msg.thoughtText?.hashCode() ?: 0}_${msg.tokenUsage?.hashCode() ?: 0}"
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
                    for (i in blocks.indices) {
                        msgItems.add(ChatFeedItem.AssistantBlock(
                            messageId = msg.id,
                            blockIndex = i,
                            block = blocks[i],
                            isFirst = i == 0,
                            isLast = i == blocks.lastIndex
                        ))
                    }
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
     * Prewarm markdown blocks asynchronously on Dispatchers.Default for the most recent
     * window of messages (last 12 messages by default) so the UI thread has zero parsing work
     * during composition, without wasting memory/CPU on the entire chat history.
     */
    fun prewarm(messages: List<ChatMessage>, windowSize: Int = 12, isDark: Boolean = true) {
        if (messages.isEmpty()) return
        val targetMessages = if (messages.size > windowSize) messages.takeLast(windowSize) else messages
        for (msg in targetMessages) {
            if (!msg.isStreaming) {
                val items = getOrParse(msg)
                for (item in items) {
                    if (item is ChatFeedItem.AssistantBlock) {
                        when (val block = item.block) {
                            is MarkdownBlock.Code -> {
                                CodeBlockCache.prewarm(block.code, block.language)
                            }
                            is MarkdownBlock.Paragraph -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            is MarkdownBlock.Header -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            is MarkdownBlock.Bullet -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            is MarkdownBlock.Numbered -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            is MarkdownBlock.Blockquote -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            is MarkdownBlock.Task -> {
                                MarkdownTextCache.prewarm(block.text, isDark)
                            }
                            else -> {}
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
        val result = ArrayList<ChatFeedItem>(messages.size * 3)
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
                    for (i in blocks.indices) {
                        addItem(ChatFeedItem.AssistantBlock(
                            messageId = msg.id,
                            blockIndex = i,
                            block = blocks[i],
                            isFirst = i == 0,
                            isLast = i == blocks.lastIndex
                        ))
                    }
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

@Immutable
data class ConversationScrollPosition(
    val index: Int,
    val offset: Int,
    val isNearBottom: Boolean
)

object ConversationScrollCache {
    private val scrollPositions = ConcurrentHashMap<String, ConversationScrollPosition>()

    fun save(convId: String, index: Int, offset: Int, isNearBottom: Boolean) {
        if (convId.isBlank() || convId == "empty") return
        scrollPositions[convId] = ConversationScrollPosition(index, offset, isNearBottom)
    }

    fun get(convId: String?): ConversationScrollPosition? {
        if (convId.isNullOrBlank() || convId == "empty") return null
        return scrollPositions[convId]
    }

    fun clear(convId: String) {
        scrollPositions.remove(convId)
    }

    fun clearAll() {
        scrollPositions.clear()
    }
}
