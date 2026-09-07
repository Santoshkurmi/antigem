package com.example.gemini.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "auth_prefs")

class AuthPreferences(private val context: Context) {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val EXPIRES_AT = androidx.datastore.preferences.core.longPreferencesKey("expires_at")
        val PROJECT_ID = stringPreferencesKey("project_id")
        val SUBSCRIPTION_TIER = stringPreferencesKey("subscription_tier")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val TERMUX_SSH_HOST = stringPreferencesKey("termux_ssh_host")
        val TERMUX_SSH_PORT = stringPreferencesKey("termux_ssh_port")
        val TERMUX_SSH_USER = stringPreferencesKey("termux_ssh_user")
        val TERMUX_SSH_PASS = stringPreferencesKey("termux_ssh_pass")
        val IS_TERMINAL_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_terminal_tool_enabled")
        val AUTO_EXECUTE_TERMINAL = androidx.datastore.preferences.core.booleanPreferencesKey("auto_execute_terminal")
        val IS_WEB_SEARCH_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_web_search_tool_enabled")
        val IS_WEB_READER_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_web_reader_tool_enabled")
        val IS_CHOICES_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_choices_tool_enabled")
        val IS_FILE_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_file_tool_enabled")
        val IS_AUTOMATION_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_automation_tool_enabled")
        val IS_MATH_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_math_tool_enabled")
        val IS_INTERACTIVE_UI_TOOL_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_interactive_ui_tool_enabled")
        val CONTEXT_WINDOW_LIMIT = androidx.datastore.preferences.core.intPreferencesKey("context_window_limit")
        val SUMMARY_MODEL_ID = stringPreferencesKey("summary_model_id")
        val ENABLED_MODELS = androidx.datastore.preferences.core.stringSetPreferencesKey("enabled_models")
        val IS_DEV_MODE_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_dev_mode_enabled")
        val CHAT_FONT_SCALE = androidx.datastore.preferences.core.floatPreferencesKey("chat_font_scale")
        val IS_LOCAL_TOOLS_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_local_tools_enabled")
        val IS_LOCAL_TOOLS_INSTALLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_local_tools_installed")
        val LOCAL_TOOLS_INSTALL_DATE = androidx.datastore.preferences.core.longPreferencesKey("local_tools_install_date")
        val LOCAL_TOOLS_VERSION = stringPreferencesKey("local_tools_version")
        val AGY_BRIDGE_WS_URL = stringPreferencesKey("agy_bridge_ws_url")
        val AGY_BRIDGE_HTTP_URL = stringPreferencesKey("agy_bridge_http_url")
        val AGY_HUB_URL = stringPreferencesKey("agy_hub_url")
        val USE_SSH_TERMINAL = androidx.datastore.preferences.core.booleanPreferencesKey("use_ssh_terminal")
    }

    val useSshTerminal: Flow<Boolean> = context.dataStore.data.map { it[USE_SSH_TERMINAL] ?: false }
    val isLocalToolsEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_LOCAL_TOOLS_ENABLED] ?: false }
    val isLocalToolsInstalled: Flow<Boolean> = context.dataStore.data.map { it[IS_LOCAL_TOOLS_INSTALLED] ?: false }
    val localToolsInstallDate: Flow<Long?> = context.dataStore.data.map { it[LOCAL_TOOLS_INSTALL_DATE] }
    val localToolsVersion: Flow<String?> = context.dataStore.data.map { it[LOCAL_TOOLS_VERSION] }

    val agyBridgeWsUrl: Flow<String> = context.dataStore.data.map { it[AGY_BRIDGE_WS_URL] ?: "ws://127.0.0.1:8080" }
    val agyBridgeHttpUrl: Flow<String> = context.dataStore.data.map { it[AGY_BRIDGE_HTTP_URL] ?: "http://127.0.0.1:8080" }
    val agyHubUrl: Flow<String> = context.dataStore.data.map { it[AGY_HUB_URL] ?: "http://127.0.0.1:8090" }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN] }
    val expiresAt: Flow<Long?> = context.dataStore.data.map { it[EXPIRES_AT] }
    val projectId: Flow<String?> = context.dataStore.data.map { it[PROJECT_ID] ?: "rising-fact-p41fc" }
    val subscriptionTier: Flow<String?> = context.dataStore.data.map { it[SUBSCRIPTION_TIER] ?: "pro" }
    val userEmail: Flow<String?> = context.dataStore.data.map { it[USER_EMAIL] }
    val enabledModelIds: Flow<Set<String>?> = context.dataStore.data.map { it[ENABLED_MODELS] }
    val isDevModeEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_DEV_MODE_ENABLED] ?: false }
    val chatFontScale: Flow<Float> = context.dataStore.data.map { it[CHAT_FONT_SCALE] ?: 1.0f }

    val termuxSshHost: Flow<String> = context.dataStore.data.map { it[TERMUX_SSH_HOST] ?: "127.0.0.1" }
    val termuxSshPort: Flow<Int> = context.dataStore.data.map { it[TERMUX_SSH_PORT]?.toIntOrNull() ?: 8022 }
    val termuxSshUser: Flow<String> = context.dataStore.data.map { it[TERMUX_SSH_USER] ?: "root" }
    val termuxSshPass: Flow<String> = context.dataStore.data.map { it[TERMUX_SSH_PASS] ?: "root" }
    val isTerminalToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_TERMINAL_TOOL_ENABLED] ?: false }
    val isAutoExecuteTerminal: Flow<Boolean> = context.dataStore.data.map { it[AUTO_EXECUTE_TERMINAL] ?: true }

    val isWebSearchToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_WEB_SEARCH_TOOL_ENABLED] ?: true }
    val isWebReaderToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_WEB_READER_TOOL_ENABLED] ?: true }
    val isChoicesToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_CHOICES_TOOL_ENABLED] ?: true }
    val isFileToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_FILE_TOOL_ENABLED] ?: false }
    val isAutomationToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_AUTOMATION_TOOL_ENABLED] ?: false }
    val isMathToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_MATH_TOOL_ENABLED] ?: true }
    val isInteractiveUiToolEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_INTERACTIVE_UI_TOOL_ENABLED] ?: false }
    val contextWindowLimit: Flow<Int> = context.dataStore.data.map { it[CONTEXT_WINDOW_LIMIT] ?: 10 }
    val summaryModelId: Flow<String> = context.dataStore.data.map { it[SUMMARY_MODEL_ID] ?: "always_ask" }

    suspend fun setWebSearchToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_WEB_SEARCH_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setWebReaderToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_WEB_READER_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setChoicesToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_CHOICES_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setFileToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_FILE_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setAutomationToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_AUTOMATION_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setMathToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_MATH_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setInteractiveUiToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_INTERACTIVE_UI_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setContextWindowLimit(limit: Int) {
        context.dataStore.edit { prefs ->
            prefs[CONTEXT_WINDOW_LIMIT] = limit
        }
    }

    suspend fun setSummaryModelId(modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[SUMMARY_MODEL_ID] = modelId
        }
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_DEV_MODE_ENABLED] = enabled
        }
    }

    suspend fun saveTermuxSshConfig(
        host: String,
        port: Int,
        user: String,
        pass: String,
        enabled: Boolean,
        autoExecute: Boolean
    ) {
        context.dataStore.edit { prefs ->
            prefs[TERMUX_SSH_HOST] = host
            prefs[TERMUX_SSH_PORT] = port.toString()
            prefs[TERMUX_SSH_USER] = user
            prefs[TERMUX_SSH_PASS] = pass
            prefs[IS_TERMINAL_TOOL_ENABLED] = enabled
            prefs[AUTO_EXECUTE_TERMINAL] = autoExecute
        }
    }

    suspend fun setTerminalToolEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_TERMINAL_TOOL_ENABLED] = enabled
        }
    }

    suspend fun setUseSshTerminal(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[USE_SSH_TERMINAL] = enabled
        }
    }

    suspend fun saveSshSettings(host: String, port: Int, user: String, pass: String) {
        context.dataStore.edit { prefs ->
            prefs[TERMUX_SSH_HOST] = host
            prefs[TERMUX_SSH_PORT] = port.toString()
            prefs[TERMUX_SSH_USER] = user
            prefs[TERMUX_SSH_PASS] = pass
        }
    }

    suspend fun setLocalToolsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IS_LOCAL_TOOLS_ENABLED] = enabled
        }
    }

    suspend fun setLocalToolsInstalled(installed: Boolean, version: String = "1.0.0") {
        context.dataStore.edit { prefs ->
            prefs[IS_LOCAL_TOOLS_INSTALLED] = installed
            if (installed) {
                prefs[LOCAL_TOOLS_INSTALL_DATE] = System.currentTimeMillis()
                prefs[LOCAL_TOOLS_VERSION] = version
            }
        }
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String?, email: String? = null, expiresInSeconds: Long? = 3600) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            if (refreshToken != null) {
                prefs[REFRESH_TOKEN] = refreshToken
            }
            if (email != null) {
                prefs[USER_EMAIL] = email
            }
            if (expiresInSeconds != null) {
                prefs[EXPIRES_AT] = System.currentTimeMillis() + (expiresInSeconds * 1000)
            }
        }
    }

    suspend fun saveProjectInfo(projectId: String, tier: String) {
        context.dataStore.edit { prefs ->
            prefs[PROJECT_ID] = projectId
            prefs[SUBSCRIPTION_TIER] = tier
        }
    }

    suspend fun saveEnabledModelIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[ENABLED_MODELS] = ids
        }
    }


    suspend fun saveChatFontScale(scale: Float) {
        context.dataStore.edit { prefs ->
            prefs[CHAT_FONT_SCALE] = scale
        }
    }

    suspend fun saveAgyHubUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[AGY_HUB_URL] = url
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(EXPIRES_AT)
            prefs.remove(USER_EMAIL)
        }
    }
}
