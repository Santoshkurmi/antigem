package com.example.gemini.data.agent

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which agents the app runs. Antigravity is on and Claude Code off until the user picks on first launch; at least
 * one always stays on. The bridge is started with the matching flags (see [bridgeFlags]).
 */
class AgentPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(read(prefs))
    val enabled: StateFlow<Set<AgentKind>> = _enabled.asStateFlow()

    private val _hasChosen = MutableStateFlow(prefs.getBoolean(KEY_CHOSEN, false))
    /** The first-launch agent choice was made. */
    val hasChosen: StateFlow<Boolean> = _hasChosen.asStateFlow()

    /** Saves the agent selection (ignored when empty). */
    fun setEnabled(agents: Set<AgentKind>) {
        if (agents.isEmpty()) return
        prefs.edit().apply {
            AgentKind.entries.forEach { putBoolean(key(it), it in agents) }
            putBoolean(KEY_CHOSEN, true)
        }.apply()
        _enabled.value = agents
        _hasChosen.value = true
    }

    companion object {
        private const val PREFS = "agent_prefs"
        private const val KEY_CHOSEN = "agents_chosen"

        private fun key(agent: AgentKind) = "enabled_" + agent.name.lowercase()

        private fun default(agent: AgentKind) = agent == AgentKind.AGY

        private fun read(prefs: android.content.SharedPreferences): Set<AgentKind> =
            AgentKind.entries.filter { prefs.getBoolean(key(it), default(it)) }.toSet().ifEmpty { setOf(AgentKind.AGY) }

        /** The first-launch agent choice was made, read synchronously (the server does not start before it). */
        fun hasChosenSync(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHOSEN, false)

        /** Enabled agents, read synchronously (bridge launch). */
        fun enabledSync(context: Context): Set<AgentKind> =
            read(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        /** Bridge flags that keep a disabled agent from running at all. */
        fun bridgeFlags(context: Context): String {
            val enabled = enabledSync(context)
            return buildString {
                if (AgentKind.AGY !in enabled) append(" --no-hub")
                if (AgentKind.CLAUDE !in enabled) append(" --no-claude")
            }
        }
    }
}
