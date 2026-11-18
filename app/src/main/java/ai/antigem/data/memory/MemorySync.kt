package ai.antigem.data.memory

import android.util.Log
import ai.antigem.data.agent.ChatCompletionMonitor
import ai.antigem.data.checkpoint.ProjectCheckpoints
import ai.antigem.data.files.AttachedFiles
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.data.remote.PromptExpansion
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent memory after a turn: the chat goes to the bridge, which applies the replies' new ```antigem-memory blocks and
 * rewrites the chat's history file (the whole chat, so edits and rewinds show). Runs for every agent's chats, on
 * screen or not, when a turn finishes, and when a chat is opened (turns that finished while the app was closed).
 * The bridge applies a block once, however often it sees it. Only for agents [AgentMemory] is on for; never for Jarvis.
 */
object MemorySync {
    private const val TAG = "AgentMemory"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false
    private lateinit var vm: ChatViewModel
    private val hub by lazy { AgyHubClient() }

    /** chat id → what was sent last time (the same again: nothing to send). */
    private val lastSent = ConcurrentHashMap<String, Int>()

    private val thought = Regex("<!--\\s*thought[^>]*-->[\\s\\S]*?(<!--\\s*/thought\\s*-->|$)")
    private val thoughtTag = Regex("<thought>[\\s\\S]*?</thought>", RegexOption.IGNORE_CASE)
    private val thinkingTag = Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE)
    private val errorCard = Regex("<!--\\s*error[^>]*-->[\\s\\S]*?<!--\\s*/error\\s*-->")
    private val comment = Regex("<!--[\\s\\S]*?-->")
    private val chatTitle = Regex("<chat_title>[\\s\\S]*?</chat_title>\\s*", RegexOption.IGNORE_CASE)

    @Synchronized
    fun start(viewModel: ChatViewModel) {
        if (started) return
        started = true
        vm = viewModel
        // a turn finished (any agent, on screen or not); the agents reload their messages right after, so wait a bit
        scope.launch {
            ChatCompletionMonitor.events.collect { c ->
                scope.launch {
                    delay(1_500)
                    sync(c)
                }
            }
        }
        // a chat opened on screen: catch up on turns that finished while the app was closed
        scope.launch {
            vm.currentConversation.map { it?.id }.distinctUntilChanged().collectLatest { id ->
                if (id.isNullOrBlank()) return@collectLatest
                delay(2_500) // its messages load first
                vm.currentConversation.value?.takeIf { it.id == id }?.let { sync(it) }
            }
        }
    }

    /** Sends [c] to the bridge (no-op when memory is off for its agent or nothing changed). */
    suspend fun sync(c: Conversation) {
        if (!AgentMemory.isOnFor(c.agent) || c.id.isBlank() || JarvisPreferences.isJarvisChat(c.id)) return
        val turns = turnsOf(messagesOf(c)).let { list ->
            // the chat's start date names its history file
            if (list.isEmpty()) return else listOf(list[0].copy(at = c.createdAt)) + list.drop(1)
        }
        val history = AgentMemory.saveHistory.value
        // history off: only replies with a memory block need the bridge
        if (!history && turns.none { it.role == "assistant" && it.text.contains("antigem-memory") }) return
        val title = PromptExpansion.cleanTitle(c.title)
        val project = ProjectCheckpoints.pathOf(c.workspaceUri)
        val fingerprint = listOf(turns.map { it.role to it.text }, history, title, project).hashCode()
        if (lastSent[c.id] == fingerprint) return
        lock.withLock {
            if (lastSent[c.id] == fingerprint) return
            MemoryBridgeClient.sync(project, c.agent, c.id, title, history, turns)
                .onSuccess { applied ->
                    lastSent[c.id] = fingerprint
                    if (applied.isNotEmpty()) AgentMemory.changed()
                }
                .onFailure { Log.w(TAG, "chat ${c.id} not sent to the bridge: ${it.message}") }
        }
    }

    /**
     * Sends the chat on screen again, even unchanged: after an Undo on its chip, so its history file notes the memory
     * change as undone (a later chat reading it must not save the memory again).
     */
    fun refreshOpenChat() {
        if (!started) return
        val c = vm.currentConversation.value ?: return
        lastSent.remove(c.id)
        scope.launch { sync(c) }
    }

    /** A deleted chat's history file goes too (also with memory off: it must not come back when memory is turned on). */
    fun onChatDeleted(chatId: String) {
        lastSent.remove(chatId)
        if (chatId.isBlank()) return
        scope.launch { MemoryBridgeClient.deleteHistory(chatId).onFailure { Log.w(TAG, "history of $chatId not deleted: ${it.message}") } }
    }

    private suspend fun messagesOf(c: Conversation): List<ChatMessage> {
        if (vm.currentConversation.value?.id == c.id) vm.messages.value.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            when (c.agent) {
                AgentKind.CLAUDE -> vm.loadClaudeMessages(c.id)
                AgentKind.OPENCODE -> vm.loadOpenCodeMessages(c.id)
                AgentKind.CODEX -> vm.loadCodexMessages(c.id)
                AgentKind.AGY -> {
                    val steps = hub.getCascadeTrajectorySteps(c.id).getOrNull().orEmpty()
                    ai.antigem.domain.chat.TrajectoryEngine().ingestStepsDirect(steps, c.id)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** The user's messages as typed and the replies as shown (no thinking, tool calls, error cards or hidden blocks). */
    private fun turnsOf(messages: List<ChatMessage>): List<MemoryBridgeClient.Turn> {
        val out = mutableListOf<MemoryBridgeClient.Turn>()
        for (m in messages) {
            if (m.isStreaming) continue
            val turn = when (m.role) {
                MessageRole.USER -> userText(m.content).takeIf { it.isNotBlank() }?.let { MemoryBridgeClient.Turn("user", it) }
                MessageRole.ASSISTANT -> replyText(m.content).takeIf { it.isNotBlank() }?.let { MemoryBridgeClient.Turn("assistant", it) }
                MessageRole.SUMMARY -> (m.contextSummary ?: m.content).trim().takeIf { it.isNotBlank() }?.let { MemoryBridgeClient.Turn("summary", it) }
                else -> null
            }
            if (turn != null) out += turn
        }
        return out
    }

    private fun userText(raw: String): String =
        PromptExpansion.collapse(AttachedFiles.extract(raw, "memory").first).trim()

    private fun replyText(raw: String): String {
        var s = PromptExpansion.collapse(raw)
        s = errorCard.replace(s, "")
        s = thought.replace(s, "")
        s = thoughtTag.replace(s, "")
        s = thinkingTag.replace(s, "")
        s = comment.replace(s, "")
        s = chatTitle.replace(s, "")
        return s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
