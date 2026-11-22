package ai.antigem.ui.chat

import androidx.compose.runtime.Immutable
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.MessageRole
import ai.antigem.domain.model.ToolType
import ai.antigem.ui.components.MarkdownBlock
import ai.antigem.ui.components.parseMarkdownBlocks
import ai.antigem.ui.components.CodeBlockCache
import ai.antigem.ui.components.MarkdownTextCache
import java.util.concurrent.ConcurrentHashMap

@Immutable
sealed class ChatFeedItem(val key: String, val contentType: String) {
    @Immutable
    data class Summary(val message: ChatMessage) : ChatFeedItem("summary_${message.id}", "SUMMARY")
    @Immutable
    data class User(val message: ChatMessage) : ChatFeedItem("user_${message.id}", "USER")
    /** News Jarvis' teams sent to a Jarvis chat (shown as a card, not as the user's words). */
    @Immutable
    data class JarvisEvent(val message: ChatMessage) : ChatFeedItem("jarvis_event_${message.id}", "JARVIS_EVENT")
    @Immutable
    data class AssistantThinking(val messageId: String, val thoughtText: String, val durationMs: Long?, val isStreaming: Boolean) : ChatFeedItem("thought_$messageId", "THOUGHT")
    @Immutable
    data class AssistantBlock(val messageId: String, val blockIndex: Int, val block: MarkdownBlock, val isFirst: Boolean, val isLast: Boolean) : ChatFeedItem("block_${messageId}_$blockIndex", block.javaClass.simpleName)
    @Immutable
    data class AssistantTyping(val messageId: String, val modelId: String) : ChatFeedItem("active_assistant_typing", "TYPING")
    @Immutable
    data class AssistantFooter(val message: ChatMessage) : ChatFeedItem("footer_${message.id}", "FOOTER")
    @Immutable
    data class StreamingMessage(val message: ChatMessage) : ChatFeedItem("streaming_${message.id}", "STREAMING")
    /** A wavy-line divider with a label: a new day, or a model switch. */
    @Immutable
    data class Divider(val id: String, val label: String) : ChatFeedItem("divider_$id", "DIVIDER")
    /**
     * An Antigravity voice call in the chat, as a wavy-line divider ("Voice call · 2:11 PM · 3m 01s"): a saved call
     * opens its transcript ([record]); a [live] one opens the call.
     */
    @Immutable
    data class VoiceCall(
        val id: String,
        val startedAt: Long,
        val durationMs: Long?,
        val live: Boolean,
        val record: ai.antigem.data.voice.VoiceChatRecord?
    ) : ChatFeedItem("voicecall_$id", "VOICE_CALL")
    /**
     * A run of a reply's thinking and tool steps folded into one pill (Settings → Appearance → Agent steps: Group).
     * [id] is the key of its first step. When [expanded], the [steps] follow the pill in the list as usual.
     * [live]: the agent is still working on this run; [startedAt] / [endedAt]: when it was seen to start / end (0 = unknown).
     */
    @Immutable
    data class StepGroup(
        val id: String,
        val steps: List<AssistantBlock>,
        val expanded: Boolean,
        val live: Boolean,
        val startedAt: Long,
        val endedAt: Long
    ) : ChatFeedItem("steps_$id", "STEP_GROUP")
}

/**
 * Folds the thinking and tool steps of replies (Settings → Appearance → Group Agent Steps, on by default; off: every
 * step is its own tile). A run of steps (no reply text between them) becomes one pill when it has at least
 * [MIN_TOOLS] tool calls or [MIN_STEPS] steps; shorter runs stay as they are. A pill the user opens stays open while
 * they stay in that chat (ChatScreen keeps which ones; opening another chat closes them all again).
 * Steps that need the user (an approval, a question), show a picture or are system notices always stay full tiles.
 */
object StepGroups {
    /** A run is folded with at least this many tool calls ... */
    const val MIN_TOOLS = 2
    /** ... or this many steps (thinking + tool calls). */
    const val MIN_STEPS = 3

    private val startedAt = ConcurrentHashMap<String, Long>()
    private val endedAt = ConcurrentHashMap<String, Long>()

    private val IMAGE_FILE = Regex(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun isStep(item: ChatFeedItem): Boolean {
        if (item !is ChatFeedItem.AssistantBlock) return false
        return when (val block = item.block) {
            is MarkdownBlock.AgentThought -> true
            is MarkdownBlock.AgentTool -> {
                val t = block.toolCall
                t.status != "PENDING_APPROVAL" && t.status != "AWAITING_CHOICE" &&
                    t.toolType != ToolType.ASK_CHOICE && t.toolType != ToolType.GENERATE_IMAGE &&
                    t.toolType != ToolType.SYSTEM_NOTIFICATION && t.name != "system_notice" &&
                    !t.output.startsWith("data:image/") && !(t.output.startsWith("file://") && t.output.matches(IMAGE_FILE))
            }
            else -> false
        }
    }

    fun isRunning(step: ChatFeedItem.AssistantBlock): Boolean = when (val block = step.block) {
        is MarkdownBlock.AgentThought -> block.isStreaming
        is MarkdownBlock.AgentTool -> block.toolCall.status == "RUNNING"
        else -> false
    }

    /** [items] from [ChatFeedCache.buildFeedItems] with runs of steps folded when [enabled]; [expandedKeys]: the opened pills' keys. */
    fun apply(items: List<ChatFeedItem>, enabled: Boolean, expandedKeys: Set<String>, messages: List<ChatMessage>): List<ChatFeedItem> {
        if (!enabled) return items
        val byId by lazy { messages.associateBy { it.id } }
        val out = ArrayList<ChatFeedItem>(items.size)
        val messagesWithRun = HashSet<String>()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            if (!isStep(item)) {
                out.add(item)
                i++
                continue
            }
            var j = i
            while (j < items.size && isStep(items[j])) j++
            @Suppress("UNCHECKED_CAST")
            val run = items.subList(i, j).toList() as List<ChatFeedItem.AssistantBlock>
            val next = items.getOrNull(j)
            i = j
            val first = run.first()
            val firstOfReply = messagesWithRun.add(first.messageId)
            if (run.count { it.block is MarkdownBlock.AgentTool } < MIN_TOOLS && run.size < MIN_STEPS) {
                out.addAll(run)
                continue
            }
            val msg = byId[first.messageId]
            // still working: a step runs, or the run is the end of a reply that is still coming in (between two steps)
            val atReplyEnd = next !is ChatFeedItem.AssistantBlock || next.messageId != run.last().messageId
            val live = run.any { isRunning(it) } || (byId[run.last().messageId]?.isStreaming == true && atReplyEnd)
            val key = "steps_${first.key}"
            val now = System.currentTimeMillis()
            val started: Long
            val ended: Long
            if (live) {
                // the first run of a reply starts with the reply
                started = startedAt.getOrPut(key) {
                    val created = msg?.createdAt ?: 0L
                    if (firstOfReply && created in 1..now) created else now
                }
                endedAt.remove(key)
                ended = 0L
            } else {
                started = startedAt[key] ?: 0L
                ended = if (started > 0) endedAt.getOrPut(key) { now } else 0L
            }
            val open = key in expandedKeys
            out.add(ChatFeedItem.StepGroup(first.key, run, open, live, started, ended))
            if (open) out.addAll(run)
        }
        return out
    }
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
                if (ai.antigem.data.jarvis.JarvisEvents.isEventMessage(msg.content)) {
                    msgItems.add(ChatFeedItem.JarvisEvent(msg))
                } else {
                    // Show the slash command the user typed, not the instructions it expanded to
                    val shown = ai.antigem.data.remote.PromptExpansion.collapse(msg.content)
                    // (the full text stays along for "View full prompt")
                    msgItems.add(ChatFeedItem.User(if (shown === msg.content) msg else msg.copy(content = shown, sentContent = msg.content)))
                }
            }
            MessageRole.ASSISTANT -> {
                val durTag = if (msg.thoughtDurationMs != null && msg.thoughtDurationMs > 0) ":${msg.thoughtDurationMs}" else ""
                val contentToParse = if (!msg.thoughtText.isNullOrEmpty() && !msg.content.contains("<!-- thought") && !msg.content.contains("<thought")) {
                    "<!-- thought$durTag -->\n${msg.thoughtText}\n<!-- /thought -->\n${msg.content}"
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

    /**
     * The chat as list items. With [dividers], a divider goes before the first prompt of each new day ("Sat, Oct 10")
     * and before the prompt of a reply whose model differs from the previous reply's ("Switched to Sonnet 5.5";
     * [modelName] turns a model id into its display name).
     */
    fun buildFeedItems(
        messages: List<ChatMessage>,
        selectedModelId: String,
        dividers: Boolean = false,
        modelName: (String) -> String = { it },
        /** Antigravity voice calls of the chat, oldest first: each goes before the first prompt sent after it started. */
        voiceCalls: List<ChatFeedItem.VoiceCall> = emptyList()
    ): List<ChatFeedItem> {
        val result = ArrayList<ChatFeedItem>(messages.size * 3)
        val seenKeys = HashSet<String>()

        fun addItem(item: ChatFeedItem) {
            if (seenKeys.add(item.key)) {
                result.add(item)
            }
        }

        var lastDay: String? = null
        var lastPromptAt = 0L
        var lastModel: String? = null
        // where the latest prompt's items start: a model divider goes right before that prompt
        var promptStart = -1
        var nextCall = 0
        fun addCall(call: ChatFeedItem.VoiceCall) {
            if (dividers) {
                val day = dayKey(call.startedAt)
                if (day != lastDay) {
                    addItem(ChatFeedItem.Divider("day_$day", dayLabel(call.startedAt)))
                    lastDay = day
                }
            }
            addItem(call)
        }
        for (msg in messages) {
            if (msg.role == MessageRole.USER) {
                while (nextCall < voiceCalls.size && voiceCalls[nextCall].startedAt <= msg.createdAt) addCall(voiceCalls[nextCall++])
            }
            if (dividers && msg.role == MessageRole.USER) {
                val day = dayKey(msg.createdAt)
                if (day != lastDay) {
                    addItem(ChatFeedItem.Divider("day_$day", dayLabel(msg.createdAt)))
                    lastDay = day
                } else if (lastPromptAt > 0 && msg.createdAt - lastPromptAt >= GAP_DIVIDER_MS) {
                    addItem(ChatFeedItem.Divider("gap_${msg.id}", dayLabel(msg.createdAt)))
                }
                lastPromptAt = msg.createdAt
                promptStart = result.size
            }
            if (dividers && msg.role == MessageRole.ASSISTANT && msg.modelId != null) {
                val previous = lastModel
                if (previous != null && msg.modelId != previous) {
                    val divider = ChatFeedItem.Divider("model_${msg.id}", "Switched to ${modelName(msg.modelId)}")
                    if (seenKeys.add(divider.key)) result.add(if (promptStart >= 0) promptStart else result.size, divider)
                }
                lastModel = msg.modelId
            }
            // only the first reply after a prompt may put a divider before that prompt
            if (msg.role == MessageRole.ASSISTANT) promptStart = -1
            if (!msg.isStreaming) {
                val parsed = getOrParse(msg)
                for (item in parsed) {
                    addItem(item)
                }
            } else {
                val hasActiveRunningTool = msg.toolCalls.any {
                    it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE"
                }
                val durTag = if (msg.thoughtDurationMs != null && msg.thoughtDurationMs > 0) ":${msg.thoughtDurationMs}" else ""
                val contentToParse = if (!msg.thoughtText.isNullOrEmpty() && !msg.content.contains("<!-- thought") && !msg.content.contains("<thought")) {
                    if (msg.content.isBlank()) {
                        "<!-- thought:streaming$durTag -->\n${msg.thoughtText}"
                    } else {
                        "<!-- thought:streaming$durTag -->\n${msg.thoughtText}\n<!-- /thought -->\n${msg.content}"
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
        // calls after the last prompt (or a call going on now) close the chat
        while (nextCall < voiceCalls.size) addCall(voiceCalls[nextCall++])
        return result
    }

    fun clear() {
        cache.clear()
    }

    private fun dayKey(millis: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.DAY_OF_YEAR)}"
    }

    /** A prompt sent this long after the previous one on the same day gets a divider with the time. */
    private const val GAP_DIVIDER_MS = 60 * 60 * 1000L

    /** "Sat, Oct 10, 15:45" (24-hour time) in the phone's language; the year is added when it is not this year. */
    private fun dayLabel(millis: Long): String {
        val locale = java.util.Locale.getDefault()
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val year = java.util.Calendar.getInstance().apply { timeInMillis = millis }.get(java.util.Calendar.YEAR)
        val skeleton = (if (year == thisYear) "EEEMMMd" else "EEEMMMdyyyy") + "Hmm"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(millis))
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
