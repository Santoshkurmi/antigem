package ai.antigem.data.memory

import android.content.Context
import android.content.SharedPreferences
import ai.antigem.data.checkpoint.ProjectCheckpoints
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Settings → Agents → Agent memory: memories the agents keep across chats, and a cleaned copy of every chat they can
 * look up. The IDE bridge keeps both next to the agents (~/.antigem/memory, its pkg/memory), so it works the same with
 * the bridge on this phone or on a server. Off by default; when on, it can still be off for single agents.
 *
 * - A chat's first message (and, after Claude compacted a chat, its next one) carries the memory text the bridge
 *   builds: the "always" memories in full, the [listLimit] most relevant others by file name, how many more there
 *   are, where the past chats are, and how to save a memory (an ```antigem-memory block in the reply).
 * - After every turn [MemorySync] sends the chat to the bridge, which applies new memory blocks and rewrites the chat's
 *   history file.
 */
object AgentMemory {
    const val DEFAULT_LIST_LIMIT = 10
    const val MAX_LIST_LIMIT = 30
    private const val MAX_RESEND = 600

    private var sp: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _agents = MutableStateFlow(AgentKind.entries.toSet())
    /** The agents memory is on for (while [enabled]). */
    val agents: StateFlow<Set<AgentKind>> = _agents.asStateFlow()

    private val _listLimit = MutableStateFlow(DEFAULT_LIST_LIMIT)
    /** How many memories a new chat gets listed by name, besides the "always" ones. */
    val listLimit: StateFlow<Int> = _listLimit.asStateFlow()

    private val _saveHistory = MutableStateFlow(true)
    /** Keep a cleaned copy of every chat (the user's messages and the replies) the agents can look up. */
    val saveHistory: StateFlow<Boolean> = _saveHistory.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /** Bumps when memories changed (a reply saved one, or the settings screen did): lists reload. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Chats that lost their memory text (Claude compacted them): their next message carries it again. */
    private val resend = LinkedHashSet<String>()

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean("enabled", false)
        p.getStringSet("agents", null)?.let { names ->
            _agents.value = names.mapNotNull { n -> AgentKind.entries.firstOrNull { it.name == n } }.toSet()
        }
        _listLimit.value = p.getInt("list_limit", DEFAULT_LIST_LIMIT).coerceIn(0, MAX_LIST_LIMIT)
        _saveHistory.value = p.getBoolean("save_history", true)
        synchronized(resend) { p.getStringSet("resend", emptySet())?.let { resend.addAll(it) } }
        sp = p
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        sp?.edit()?.putBoolean("enabled", on)?.apply()
    }

    fun setAgent(agent: AgentKind, on: Boolean) {
        _agents.update { if (on) it + agent else it - agent }
        sp?.edit()?.putStringSet("agents", _agents.value.map { it.name }.toSet())?.apply()
    }

    fun setListLimit(n: Int) {
        _listLimit.value = n.coerceIn(0, MAX_LIST_LIMIT)
        sp?.edit()?.putInt("list_limit", _listLimit.value)?.apply()
    }

    fun setSaveHistory(on: Boolean) {
        _saveHistory.value = on
        sp?.edit()?.putBoolean("save_history", on)?.apply()
    }

    fun changed() = _changes.update { it + 1 }

    fun isOnFor(agent: AgentKind?): Boolean = agent != null && _enabled.value && agent in _agents.value

    /** The message about to go out needs the memory text: a chat's first message, or its first after compaction. */
    fun wants(agent: AgentKind?, chatId: String?, hasEarlierMessages: Boolean): Boolean {
        if (!isOnFor(agent)) return false
        if (!hasEarlierMessages) return true
        val id = chatId?.takeIf { it.isNotBlank() } ?: return false
        return synchronized(resend) { id in resend }
    }

    /** The chat lost its memory text (Claude compacted it): send it again with the next message. */
    fun forget(chatId: String?) {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return
        synchronized(resend) {
            resend.remove(id)
            resend.add(id)
            while (resend.size > MAX_RESEND) resend.remove(resend.first())
        }
        persistResend()
    }

    /** The chat got its memory text. */
    fun markSent(chatId: String?) {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return
        val removed = synchronized(resend) { resend.remove(id) }
        if (removed) persistResend()
    }

    private fun persistResend() {
        val copy = synchronized(resend) { resend.toSet() }
        sp?.edit()?.putStringSet("resend", copy)?.apply()
    }

    /** How long a first message waits for the memory text before it goes without it. */
    fun waitMs(): Long = if (AuthPreferences.isRemoteEndpoint()) 3_000L else 1_500L

    /** The memory text for a chat's first message, or null (memory empty, bridge too old, slow or unreachable). */
    suspend fun contextFor(agent: AgentKind, workspaceUri: String?, typed: String): String? =
        MemoryBridgeClient.context(
            project = ProjectCheckpoints.pathOf(workspaceUri),
            agent = agent,
            message = typed,
            limit = _listLimit.value,
            history = _saveHistory.value,
            timeoutMs = waitMs()
        ).onFailure { android.util.Log.w("AgentMemory", "no memory text for this message: ${it.message}") }
            .getOrNull()?.takeIf { it.isNotBlank() }
}
