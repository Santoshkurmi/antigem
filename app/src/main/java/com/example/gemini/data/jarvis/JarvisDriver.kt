package com.example.gemini.data.jarvis

import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.agent.claude.ClaudeBridgeClient
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.opencode.OpenCodeBridgeClient
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the Jarvis text chats (the command centers) whether they are on screen or not: is it working, send it a
 * message (the news of its teams), stop it, and what it is running right now. A chat that is not on screen gets its
 * own backend that stays attached to it (like a task's), so its state is live and sending never touches the chat on
 * screen or the attachments the user is adding there.
 */
object JarvisDriver {
    private val claude = ConcurrentHashMap<String, ClaudeChatBackend>()
    private val openCode = ConcurrentHashMap<String, OpenCodeChatBackend>()
    private val hub by lazy { AgyHubClient() }
    private val vm get() = JarvisController.vm

    fun agentOf(id: String): AgentKind = JarvisManagers.get(id)?.agent ?: JarvisController.agentOfChat(id)

    private fun onScreen(id: String) = vm.currentConversation.value?.id == id

    /** The chat is working on a turn right now. */
    fun isBusy(id: String): Boolean {
        if (!JarvisController.isAttached || id.isBlank()) return false
        val agent = agentOf(id)
        if (onScreen(id) && vm.currentConversation.value?.agent == agent && vm.isCurrentChatActivelyRunning.value) return true
        return when (agent) {
            AgentKind.CLAUDE -> claude[id]?.isCurrentChatActivelyRunning?.value == true ||
                vm.claudeBackend.conversations.value.any { it.id == id && it.isRunning }
            AgentKind.OPENCODE -> openCode[id]?.isCurrentChatActivelyRunning?.value == true ||
                vm.openCodeBackend.conversations.value.any { it.id == id && it.isRunning }
            AgentKind.AGY -> {
                val c = vm.agyBackend.conversations.value.find { it.id == id }
                c != null && vm.systemConnectionState.value.isHubOnline && (c.isRunning || c.notFullyIdle)
            }
        }
    }

    /** Keeps a live backend on a chat that is not on screen (its state and messages stay current). */
    fun ensure(id: String) {
        if (!JarvisController.isAttached || onScreen(id)) return
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claude.getOrPut(id) { newClaude().also { it.selectConversation(id) } }
            AgentKind.OPENCODE -> openCode.getOrPut(id) { newOpenCode().also { it.selectConversation(id) } }
            AgentKind.AGY -> Unit
        }
    }

    /** The chat's own Claude backend (only while it is not on screen and something is going on). */
    fun claudeOf(id: String): ClaudeChatBackend? = claude[id]

    /** The chat's own OpenCode backend. */
    fun openCodeOf(id: String): OpenCodeChatBackend? = openCode[id]

    /** A live backend is kept on this chat. */
    fun isAttached(id: String): Boolean = claude.containsKey(id) || openCode.containsKey(id)

    fun release(id: String) {
        claude.remove(id)?.let { runCatching { it.detach() } }
        openCode.remove(id)?.let { runCatching { it.stop() } }
    }

    private fun newClaude() = ClaudeChatBackend(JarvisController.app, JarvisController.scopeForDrivers, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())

    private fun newOpenCode() = OpenCodeChatBackend(
        JarvisController.app, JarvisController.scopeForDrivers, ChatSessionStore(), vm.openCodePrefs, OpenCodeBridgeClient(),
        rememberChoices = false
    ).also { it.start() }

    /**
     * Sends [text] to the chat. On screen (and nothing attached in the input) it goes through the screen's backend so
     * the user sees it at once; otherwise through the chat's own backend.
     */
    suspend fun send(id: String, text: String): Result<Unit> = runCatching {
        val agent = agentOf(id)
        val screenFree = onScreen(id) && vm.currentConversation.value?.agent == agent && vm.attachments.value.isEmpty()
        when (agent) {
            AgentKind.CLAUDE -> if (screenFree) vm.claudeBackend.sendMessage(text) else {
                val fresh = !claude.containsKey(id)
                val b = claude.getOrPut(id) { newClaude().also { it.selectConversation(id) } }
                // a backend that was just attached loads the chat first
                if (fresh) delay(900) else if (b.currentConversation.value?.id != id) { b.selectConversation(id); delay(900) }
                b.sendMessage(text)
            }
            AgentKind.OPENCODE -> if (screenFree) vm.openCodeBackend.sendMessage(text) else {
                val fresh = !openCode.containsKey(id)
                val b = openCode.getOrPut(id) { newOpenCode().also { it.selectConversation(id) } }
                if (fresh) delay(900) else if (b.currentConversation.value?.id != id) { b.selectConversation(id); delay(900) }
                b.sendMessage(text)
            }
            AgentKind.AGY -> if (screenFree) vm.agyBackend.sendMessage(text) else {
                val enum = AgyHubClient.resolveModelEnum(vm.agyBackend.modelOfChat(id))
                hub.sendUserPrompt(id, text, enum).getOrThrow()
            }
        }
    }

    /** Stops the chat's current turn. */
    suspend fun stop(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> {
                if (onScreen(id) && vm.currentConversation.value?.agent == AgentKind.CLAUDE) vm.claudeBackend.stopStreaming()
                claude[id]?.stopStreaming()
                if (!onScreen(id) && claude[id] == null) {
                    val b = claude.getOrPut(id) { newClaude().also { it.selectConversation(id) } }
                    delay(700)
                    b.stopStreaming()
                }
            }
            AgentKind.OPENCODE -> (openCode[id] ?: vm.openCodeBackend).interrupt(id)
            AgentKind.AGY -> hub.cancelCascadeInvocation(id)
        }
    }

    /** The chat's messages as they are live (Claude, OpenCode); null when only the server has them (Antigravity). */
    fun liveMessages(id: String): List<ChatMessage>? = when (agentOf(id)) {
        AgentKind.CLAUDE -> if (onScreen(id) && vm.currentConversation.value?.agent == AgentKind.CLAUDE) vm.claudeBackend.messages.value else claude[id]?.messages?.value
        AgentKind.OPENCODE -> if (onScreen(id) && vm.currentConversation.value?.agent == AgentKind.OPENCODE) vm.openCodeBackend.messages.value else openCode[id]?.messages?.value
        AgentKind.AGY -> if (onScreen(id) && vm.currentConversation.value?.agent == AgentKind.AGY) vm.messages.value else null
    }

    /** The tool the chat's current reply is running, if any. */
    suspend fun runningTool(id: String): ToolCall? {
        val msgs = liveMessages(id) ?: runCatching { JarvisController.readChat(id) }.getOrNull() ?: return null
        return msgs.lastOrNull { it.role == MessageRole.ASSISTANT }?.toolCalls?.lastOrNull { it.status == "RUNNING" }
    }

    /** The chat's last reply text (hidden blocks taken out). */
    suspend fun lastReply(id: String): String {
        val msgs = liveMessages(id)?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(id) }.getOrDefault(emptyList())
        val raw = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content.orEmpty()
        return JarvisText.reply(raw).text
    }
}
