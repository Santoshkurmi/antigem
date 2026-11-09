package com.example.gemini.data.agent.codex

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Codex choices the app remembers: the model and reasoning effort for new chats, and the approval mode. */
class CodexPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, "").orEmpty())
    /** Model id for new chats; blank = Codex's default model. */
    val model: StateFlow<String> = _model.asStateFlow()

    private val _effort = MutableStateFlow(prefs.getString(KEY_EFFORT, "").orEmpty())
    /** Reasoning effort ("low", "medium", "high", …); blank = the model's default. */
    val effort: StateFlow<String> = _effort.asStateFlow()

    private val _permissionMode = MutableStateFlow(prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO)
    /** [MODE_READ_ONLY], [MODE_ASK], [MODE_AUTO] or [MODE_FULL]. */
    val permissionMode: StateFlow<String> = _permissionMode.asStateFlow()

    fun setModel(id: String) {
        prefs.edit().putString(KEY_MODEL, id).apply()
        _model.value = id
    }

    fun setEffort(effort: String) {
        prefs.edit().putString(KEY_EFFORT, effort).apply()
        _effort.value = effort
    }

    fun setPermissionMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
        _permissionMode.value = mode
    }

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_MODE = "permission_mode"

        /** Reads the project; asks before any change or command that needs more. */
        const val MODE_READ_ONLY = "read-only"
        /** Edits the project; asks before every command that is not a known safe one. */
        const val MODE_ASK = "ask"
        /** Codex's default: edits and runs commands inside the project; asks before going outside it or online. */
        const val MODE_AUTO = "auto"
        /** No sandbox and no questions. */
        const val MODE_FULL = "full"

        val MODES = listOf(MODE_READ_ONLY, MODE_ASK, MODE_AUTO, MODE_FULL)

        /** `approvalPolicy` of a mode (thread/start, turn/start). */
        fun approvalPolicy(mode: String): String = when (mode) {
            MODE_ASK -> "untrusted"
            MODE_FULL -> "never"
            else -> "on-request"
        }

        /** `sandbox` of a mode for thread/start. */
        fun sandboxMode(mode: String): String = when (mode) {
            MODE_READ_ONLY -> "read-only"
            MODE_FULL -> "danger-full-access"
            else -> "workspace-write"
        }

        /** `sandboxPolicy` of a mode for turn/start (applies to that turn and the next ones). */
        fun sandboxPolicy(mode: String): JsonElement = when (mode) {
            MODE_READ_ONLY -> buildJsonObject { put("type", "readOnly"); put("networkAccess", false) }
            MODE_FULL -> buildJsonObject { put("type", "dangerFullAccess") }
            else -> buildJsonObject {
                put("type", "workspaceWrite")
                put("writableRoots", JsonArray(emptyList()))
                put("networkAccess", false)
                put("excludeTmpdirEnvVar", false)
                put("excludeSlashTmp", false)
            }
        }

        fun approvalPolicyJson(mode: String): JsonElement = JsonPrimitive(approvalPolicy(mode))
    }
}
