package com.example.gemini.data.agent.claude

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-side Claude Code options (Claude's own options live in its settings.json, edited through the bridge). */
class ClaudePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("claude_code_prefs", Context.MODE_PRIVATE)

    private val _allowBypass = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_BYPASS, false))
    /** Like the VS Code setting `allowDangerouslySkipPermissions`: makes the Bypass mode available. */
    val allowBypass: StateFlow<Boolean> = _allowBypass.asStateFlow()

    private val _voiceLanguage = MutableStateFlow(prefs.getString(KEY_VOICE_LANGUAGE, "en") ?: "en")
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    private val _newChatAgent = MutableStateFlow(
        runCatching { AgentKind.valueOf(prefs.getString(KEY_NEW_CHAT_AGENT, AgentKind.AGY.name)!!) }.getOrDefault(AgentKind.AGY)
    )
    val newChatAgent: StateFlow<AgentKind> = _newChatAgent.asStateFlow()

    fun setAllowBypass(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_BYPASS, enabled).apply()
        _allowBypass.value = enabled
    }

    fun setVoiceLanguage(language: String) {
        prefs.edit().putString(KEY_VOICE_LANGUAGE, language).apply()
        _voiceLanguage.value = language
    }

    fun setNewChatAgent(agent: AgentKind) {
        prefs.edit().putString(KEY_NEW_CHAT_AGENT, agent.name).apply()
        _newChatAgent.value = agent
    }

    private companion object {
        const val KEY_ALLOW_BYPASS = "allow_bypass_permissions"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_NEW_CHAT_AGENT = "new_chat_agent"
    }
}
