package com.example.gemini.data.agent.claude

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** App-side Claude Code options (Claude's own options live in its settings.json, edited through the bridge). */
class ClaudePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("claude_code_prefs", Context.MODE_PRIVATE)

    private val _voiceLanguage = MutableStateFlow(prefs.getString(KEY_VOICE_LANGUAGE, "en") ?: "en")
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    private val _newChatAgent = MutableStateFlow(
        runCatching { AgentKind.valueOf(prefs.getString(KEY_NEW_CHAT_AGENT, AgentKind.AGY.name)!!) }.getOrDefault(AgentKind.AGY)
    )
    val newChatAgent: StateFlow<AgentKind> = _newChatAgent.asStateFlow()

    /** The CLI's last `initialize` info (models, commands), so the model list shows at once on the next launch. */
    fun cachedInfo(): ClaudeInitializeInfo? =
        prefs.getString(KEY_INFO_CACHE, null)?.let { runCatching { ClaudeJson.decodeFromString<ClaudeInitializeInfo>(it) }.getOrNull() }

    fun saveInfo(info: ClaudeInitializeInfo) {
        prefs.edit().putString(KEY_INFO_CACHE, ClaudeJson.encodeToString(info)).apply()
    }

    /** Permission mode new chats start in, as the bridge last reported it (Auto unless set in settings.json). */
    fun cachedDefaultMode(): String = prefs.getString(KEY_DEFAULT_MODE, null) ?: "auto"

    fun saveDefaultMode(mode: String) {
        prefs.edit().putString(KEY_DEFAULT_MODE, mode).apply()
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
        const val KEY_DEFAULT_MODE = "default_permission_mode"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_NEW_CHAT_AGENT = "new_chat_agent"
        const val KEY_INFO_CACHE = "initialize_info_cache"
    }
}
