package com.example.gemini.data.agent.dsh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DeepSeek Harness choices the app remembers: the model and reasoning effort for new chats. The provider, API keys,
 * default model, MCP servers and permission mode live on the bridge (`/api/dsh/config`), shared by every app.
 */
class DshPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dsh_prefs", Context.MODE_PRIVATE)

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, "").orEmpty())
    /** "provider/model" for new chats; blank = the bridge's default model. */
    val model: StateFlow<String> = _model.asStateFlow()

    private val _effort = MutableStateFlow(prefs.getString(KEY_EFFORT, "").orEmpty())
    /** Reasoning effort for new chats ("low", "medium", "high"); blank = the model's default. */
    val effort: StateFlow<String> = _effort.asStateFlow()

    fun setModel(id: String) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        _model.value = id
    }

    fun setEffort(effort: String) {
        prefs.edit().putString(KEY_EFFORT, effort).apply()
        _effort.value = effort
    }

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"

        /** Reasoning efforts the app offers ("" = the model's default). */
        val EFFORTS = listOf("", "low", "medium", "high")

        /** "provider/model" → provider and model (a model id can hold slashes itself: only the first one splits). */
        fun split(id: String): Pair<String, String>? {
            val i = id.indexOf('/')
            if (i <= 0 || i == id.length - 1) return null
            return id.substring(0, i) to id.substring(i + 1)
        }

        fun join(provider: String?, model: String?): String =
            if (provider.isNullOrBlank() || model.isNullOrBlank()) model.orEmpty() else "$provider/$model"
    }
}
