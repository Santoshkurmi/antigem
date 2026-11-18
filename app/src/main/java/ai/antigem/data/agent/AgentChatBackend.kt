package ai.antigem.data.agent

import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import kotlinx.coroutines.flow.StateFlow

/** Chat lifecycle every agent backend (Antigravity, Claude Code) implements. */
interface AgentChatBackend {
    val kind: AgentKind
    val conversations: StateFlow<List<Conversation>>
    val currentConversation: StateFlow<Conversation?>
    val messages: StateFlow<List<ChatMessage>>
    val isStreaming: StateFlow<Boolean>
    val isLoadingConversation: StateFlow<Boolean>
    val conversationError: StateFlow<String?>

    fun startNewChat()
    fun selectConversation(id: String)
    fun sendMessage(content: String)
    fun stopStreaming()
    fun deleteConversation(id: String)
    fun forkConversation(id: String)
    fun retryConnections()
    fun onAppForegrounded()

    /**
     * The id a project checkpoint of this user [message] was saved under (see ProjectCheckpoints.beforeTurn), or null
     * when the message has none.
     */
    fun checkpointKey(message: ChatMessage): String? = message.checkpointKey
}
