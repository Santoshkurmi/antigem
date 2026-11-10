package com.example.gemini.data.agent.opencode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OpenCode choices the app remembers: the model for new chats, the agent (build / plan) and the approval mode. */
class OpenCodePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("opencode_prefs", Context.MODE_PRIVATE)

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, "").orEmpty())
    /** "provider/model" for new chats; blank = OpenCode's default. */
    val model: StateFlow<String> = _model.asStateFlow()

    private val _agent = MutableStateFlow(prefs.getString(KEY_AGENT, "build") ?: "build")
    val agent: StateFlow<String> = _agent.asStateFlow()

    private val _permissionMode = MutableStateFlow(prefs.getString(KEY_MODE, MODE_ASK) ?: MODE_ASK)
    /** [MODE_DEFAULT], [MODE_ASK] or [MODE_AUTO]. */
    val permissionMode: StateFlow<String> = _permissionMode.asStateFlow()

    private val _hiddenModels = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_MODELS, null)?.toSet().orEmpty())
    /** "provider/model" ids turned off in Settings → OpenCode → Providers: left out of the model picker. */
    val hiddenModels: StateFlow<Set<String>> = _hiddenModels.asStateFlow()

    private val _hiddenProviders = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_PROVIDERS, null)?.toSet().orEmpty())
    /** Provider ids turned off: none of their models are in the picker (each model keeps its own on/off). */
    val hiddenProviders: StateFlow<Set<String>> = _hiddenProviders.asStateFlow()

    fun setHiddenModels(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_MODELS, ids.toSet()).apply()
        _hiddenModels.value = ids.toSet()
    }

    fun setHiddenProviders(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_PROVIDERS, ids.toSet()).apply()
        _hiddenProviders.value = ids.toSet()
    }

    fun setModel(id: String) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        _model.value = id
    }

    fun setAgent(id: String) {
        prefs.edit().putString(KEY_AGENT, id).apply()
        _agent.value = id
    }

    fun setPermissionMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
        _permissionMode.value = mode
    }

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_AGENT = "agent"
        private const val KEY_MODE = "permission_mode"
        private const val KEY_HIDDEN_MODELS = "hidden_models"
        private const val KEY_HIDDEN_PROVIDERS = "hidden_providers"

        /** OpenCode's own rules (its config and the agent's; most tools run without asking). */
        const val MODE_DEFAULT = "default"
        /** Ask before commands, file edits and web access; reading the project is allowed. */
        const val MODE_ASK = "ask"
        /** Allow everything without asking. */
        const val MODE_AUTO = "auto"
    }
}
