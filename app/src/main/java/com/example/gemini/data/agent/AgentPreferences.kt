package com.example.gemini.data.agent

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which agents the app offers. Both are on by default and at least one always stays on. */
class AgentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(
        AgentKind.entries.filter { prefs.getBoolean(key(it), true) }.toSet().ifEmpty { AgentKind.entries.toSet() }
    )
    val enabled: StateFlow<Set<AgentKind>> = _enabled.asStateFlow()

    /** Returns false (and changes nothing) when [agent] is the last one still on. */
    fun setEnabled(agent: AgentKind, on: Boolean): Boolean {
        val next = if (on) _enabled.value + agent else _enabled.value - agent
        if (next.isEmpty()) return false
        prefs.edit().putBoolean(key(agent), on).apply()
        _enabled.value = next
        return true
    }

    private fun key(agent: AgentKind) = "enabled_" + agent.name.lowercase()
}
