package ai.antigem.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chats pinned to the top of the chat list. App level: only the conversation ids are kept here (ids are unique across
 * agents), so pinning works the same for Antigravity, Claude Code, OpenCode, Codex and Jarvis chats without any agent
 * knowing about it.
 */
object PinnedChats {
    private const val KEY = "pinned_ids"
    private var sp: SharedPreferences? = null
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids

    /** Loads the saved pins once; safe to call again. */
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("pinned_chats", Context.MODE_PRIVATE)
        sp = p
        _ids.value = p.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun isPinned(id: String): Boolean = id in _ids.value

    fun setPinned(id: String, pinned: Boolean) {
        if (id.isBlank()) return
        val next = if (pinned) _ids.value + id else _ids.value - id
        if (next == _ids.value) return
        _ids.value = next
        sp?.edit()?.putStringSet(KEY, next)?.apply()
    }
}
