package com.example.gemini.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        val AGY_BRIDGE_HTTP_URL = stringPreferencesKey("agy_bridge_http_url")
        val AGY_HUB_URL = stringPreferencesKey("agy_hub_url")
        val USE_SSH_TERMINAL = androidx.datastore.preferences.core.booleanPreferencesKey("use_ssh_terminal")
        val PREFERRED_MODEL_ID = stringPreferencesKey("preferred_model_id")
        val PREFERRED_MODEL_KEY = stringPreferencesKey("preferred_model_key")
        val PREFERRED_MODEL_NAME = stringPreferencesKey("preferred_model_name")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode") // "SYSTEM", "DARK", "LIGHT"
        val TERMINAL_FONT_SIZE = androidx.datastore.preferences.core.intPreferencesKey("terminal_font_size")
        val TERMINAL_CURSOR_STYLE = stringPreferencesKey("terminal_cursor_style")
        val TERMINAL_BUFFER_SIZE = androidx.datastore.preferences.core.intPreferencesKey("terminal_buffer_size")
        val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val COMMAND_AUTO_EXECUTION_POLICY = stringPreferencesKey("command_auto_execution_policy")
        val COMMAND_SANDBOX_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("command_sandbox_enabled")
        val REQUIRE_APPROVAL_FOR_FILE_EDITS = androidx.datastore.preferences.core.booleanPreferencesKey("require_approval_for_file_edits")
        val DEFAULT_APPROVAL_SCOPE = stringPreferencesKey("default_approval_scope")
        val GROUP_CHATS_BY_WORKSPACE = androidx.datastore.preferences.core.booleanPreferencesKey("group_chats_by_workspace")
        val IS_FLOATING_BUBBLE_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_floating_bubble_enabled")
        val AUTO_SHOW_FLOATING_BUBBLE_ON_MINIMIZE = androidx.datastore.preferences.core.booleanPreferencesKey("auto_show_floating_bubble_on_minimize")
        val IS_FLOATING_SWITCHER_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_floating_switcher_enabled")
        val FLOATING_SWITCHER_ORIENTATION = stringPreferencesKey("floating_switcher_orientation")
        val FLOATING_SWITCHER_ITEMS = stringPreferencesKey("floating_switcher_items")
        val FLOATING_SWITCHER_AUTO_COLLAPSE_SEC = androidx.datastore.preferences.core.intPreferencesKey("floating_switcher_auto_collapse_sec")
        val FLOATING_SWITCHER_POS_X = androidx.datastore.preferences.core.floatPreferencesKey("floating_switcher_pos_x")
        val FLOATING_SWITCHER_POS_Y = androidx.datastore.preferences.core.floatPreferencesKey("floating_switcher_pos_y")
        val IS_BROWSER_AUTOMATION_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_browser_automation_enabled")
        val IS_TERMINAL_AUTOMATION_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_terminal_automation_enabled")
        val IS_FLOATING_DIAGNOSTICS_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_floating_diagnostics_enabled")
        val IS_NETWORK_INSPECTOR_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_network_inspector_enabled")
        val IS_FLOATING_NETWORK_INSPECTOR_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_floating_network_inspector_enabled")
        val SECURITY_TOKEN = stringPreferencesKey("security_token")
        val AGY_BRIDGE_BINARY_PATH = stringPreferencesKey("agy_bridge_binary_path")
        val AGY_BINARY_PATH = stringPreferencesKey("agy_binary_path")
        val HAS_SEEN_TERMINAL_LAUNCHER_ONBOARDING = androidx.datastore.preferences.core.booleanPreferencesKey("has_seen_terminal_launcher_onboarding")
        val TERMINAL_LAUNCHER_STYLE = stringPreferencesKey("terminal_launcher_style") // "ANTITERM" or "TERMUX"
        val IS_TERMINAL_LAUNCHER_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("is_terminal_launcher_enabled")

        const val DEFAULT_BRIDGE_BINARY_PATH = "~/../usr/bin/agy_ide_bridge"
        const val DEFAULT_AGY_BINARY_PATH = "~/../usr/bin/agy"

        const val TOKEN_PREFIX = "x-ag"
        const val TOKEN_SUFFIX = "_9qx"
        const val TOKEN_LENGTH = 12

        fun buildFramedHeader(token: String): String {
            val clean = token.trim().take(TOKEN_LENGTH).padEnd(TOKEN_LENGTH, '0')
            return "$TOKEN_PREFIX$clean$TOKEN_SUFFIX"
        }

        private const val TOKEN_CHARSET = "0123456789abcdefghijklmnopqrstuvwxyz"
        private val secureRandom = java.security.SecureRandom()

        fun generateRandomToken(): String {
            val sb = StringBuilder(TOKEN_LENGTH)
            for (i in 0 until TOKEN_LENGTH) {
                sb.append(TOKEN_CHARSET[secureRandom.nextInt(TOKEN_CHARSET.length)])
            }
            return sb.toString()
        }

        @Volatile
        var currentSecurityToken: String = ""

        val currentFramedHeader: String
            get() = buildFramedHeader(currentSecurityToken)

        const val DEFAULT_HUB_URL = "http://127.0.0.1:1235"
        const val DEFAULT_BRIDGE_HTTP_URL = "http://127.0.0.1:1234"

        @Volatile
        var currentHubUrl: String = DEFAULT_HUB_URL

        @Volatile
        var currentBridgeHttpUrl: String = DEFAULT_BRIDGE_HTTP_URL

        val currentBridgeWsUrl: String
            get() = toWsUrl(currentBridgeHttpUrl)

        fun toWsUrl(httpUrl: String): String = when {
            httpUrl.startsWith("https://") -> httpUrl.replaceFirst("https://", "wss://")
            httpUrl.startsWith("http://") -> httpUrl.replaceFirst("http://", "ws://")
            else -> "ws://$httpUrl"
        }
    }

    private val syncPrefs = context.getSharedPreferences("anti_gem_sync_prefs", Context.MODE_PRIVATE)

    init {
        val savedHub = syncPrefs.getString("agy_hub_url", null)
        val savedBridgeHttp = syncPrefs.getString("agy_bridge_http_url", null)
        if (!savedHub.isNullOrBlank()) currentHubUrl = savedHub
        if (!savedBridgeHttp.isNullOrBlank()) currentBridgeHttpUrl = savedBridgeHttp
        val inspectorOn = syncPrefs.getBoolean("is_network_inspector_enabled", false)
        val inspectorFloating = syncPrefs.getBoolean("is_floating_network_inspector_enabled", false)
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled = inspectorOn
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled = inspectorFloating

        var token = syncPrefs.getString("security_token", null)
        if (token.isNullOrBlank() || token.length != TOKEN_LENGTH) {
            token = generateRandomToken()
            syncPrefs.edit().putString("security_token", token).apply()
        }
        currentSecurityToken = token
    }

    val groupChatsByWorkspace: Flow<Boolean> = context.dataStore.data.map { it[GROUP_CHATS_BY_WORKSPACE] ?: false }

    val preferredModelId: Flow<String?> = context.dataStore.data.map { it[PREFERRED_MODEL_ID] }
    val preferredModelKey: Flow<String?> = context.dataStore.data.map { it[PREFERRED_MODEL_KEY] }
    val preferredModelName: Flow<String?> = context.dataStore.data.map { it[PREFERRED_MODEL_NAME] }
    val useSshTerminal: Flow<Boolean> = context.dataStore.data.map { 
        it[USE_SSH_TERMINAL] ?: (context.packageName != "com.termux")
    }
    val isLocalToolsEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_LOCAL_TOOLS_ENABLED] ?: false }
    val isLocalToolsInstalled: Flow<Boolean> = context.dataStore.data.map { it[IS_LOCAL_TOOLS_INSTALLED] ?: false }
    val localToolsInstallDate: Flow<Long?> = context.dataStore.data.map { it[LOCAL_TOOLS_INSTALL_DATE] }
    val localToolsVersion: Flow<String?> = context.dataStore.data.map { it[LOCAL_TOOLS_VERSION] }
    val isBrowserAutomationEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_BROWSER_AUTOMATION_ENABLED] ?: true }
    val isTerminalAutomationEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_TERMINAL_AUTOMATION_ENABLED] ?: true }

    fun isBrowserAutomationEnabledSync(): Boolean = syncPrefs.getBoolean("is_browser_automation_enabled", true)
    fun isTerminalAutomationEnabledSync(): Boolean = syncPrefs.getBoolean("is_terminal_automation_enabled", true)

    val agyBridgeHttpUrl: Flow<String> = context.dataStore.data.map { 
        val url = it[AGY_BRIDGE_HTTP_URL] ?: DEFAULT_BRIDGE_HTTP_URL
        currentBridgeHttpUrl = url
        url
    }
    val agyBridgeWsUrl: Flow<String> = agyBridgeHttpUrl.map { toWsUrl(it) }
    val agyHubUrl: Flow<String> = context.dataStore.data.map { 
        val url = it[AGY_HUB_URL] ?: DEFAULT_HUB_URL
        currentHubUrl = url
        url
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = context.dataStore.data.map { it[REFRESH_TOKEN] }
    val expiresAt: Flow<Long?> = context.dataStore.data.map { it[EXPIRES_AT] }
    val projectId: Flow<String?> = context.dataStore.data.map { it[PROJECT_ID] ?: "rising-fact-p41fc" }
    val subscriptionTier: Flow<String?> = context.dataStore.data.map { it[SUBSCRIPTION_TIER] ?: "pro" }
    val userEmail: Flow<String?> = context.dataStore.data.map { it[USER_EMAIL] }

    fun getThemeModeSync(): String {
        val cached = syncPrefs.getString("app_theme_mode", null)
        if (cached != null) return cached

        // Fast fallback: parse datastore pb file directly if syncPrefs not yet populated
        try {
            val pbFile = java.io.File(context.filesDir, "datastore/auth_prefs.preferences_pb")
            if (pbFile.exists()) {
                val content = pbFile.readBytes().toString(Charsets.ISO_8859_1)
                val key = "app_theme_mode"
                val idx = content.indexOf(key)
                if (idx != -1) {
                    val sub = content.substring(idx + key.length, minOf(content.length, idx + key.length + 30))
                    val mode = when {
                        sub.contains("LIGHT") -> "LIGHT"
                        sub.contains("DARK") -> "DARK"
                        else -> "SYSTEM"
                    }
                    syncPrefs.edit().putString("app_theme_mode", mode).apply()
                    return mode
                }
            }
        } catch (_: Exception) {}

        return "SYSTEM"
    }

    val enabledModelIds: Flow<Set<String>?> = context.dataStore.data.map { it[ENABLED_MODELS] }
    val isDevModeEnabled: Flow<Boolean> = context.dataStore.data.map { it[IS_DEV_MODE_ENABLED] ?: false }
    val chatFontScale: Flow<Float> = context.dataStore.data.map { it[CHAT_FONT_SCALE] ?: 1.0f }
    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        val mode = prefs[APP_THEME_MODE] ?: "SYSTEM"
        if (syncPrefs.getString("app_theme_mode", null) != mode) {
            syncPrefs.edit().putString("app_theme_mode", mode).apply()
        }
        mode
    }
    fun getTerminalFontSizeSync(): Int = syncPrefs.getInt("terminal_font_size", 14)
    fun getTerminalCursorStyleSync(): String = syncPrefs.getString("terminal_cursor_style", "BAR") ?: "BAR"
    fun getTerminalBufferSizeSync(): Int = syncPrefs.getInt("terminal_buffer_size", 20000)
    fun getTerminalThemeSync(): String = syncPrefs.getString("terminal_theme", "DEFAULT") ?: "DEFAULT"

    val terminalFontSize: Flow<Int> = context.dataStore.data.map { prefs ->
        val size = prefs[TERMINAL_FONT_SIZE] ?: 14
        if (syncPrefs.getInt("terminal_font_size", 14) != size) {
            syncPrefs.edit().putInt("terminal_font_size", size).apply()
        }
        size
    }
    val terminalCursorStyle: Flow<String> = context.dataStore.data.map { prefs ->
        val style = prefs[TERMINAL_CURSOR_STYLE] ?: "BAR"
        if (syncPrefs.getString("terminal_cursor_style", null) != style) {
            syncPrefs.edit().putString("terminal_cursor_style", style).apply()
        }
        style
    }
    val terminalBufferSize: Flow<Int> = context.dataStore.data.map { prefs ->
        val size = prefs[TERMINAL_BUFFER_SIZE] ?: 20000
        if (syncPrefs.getInt("terminal_buffer_size", 20000) != size) {
            syncPrefs.edit().putInt("terminal_buffer_size", size).apply()
        }
        size
    }
    val terminalTheme: Flow<String> = context.dataStore.data.map { prefs ->
        val th = prefs[TERMINAL_THEME] ?: "DEFAULT"
        if (syncPrefs.getString("terminal_theme", null) != th) {
            syncPrefs.edit().putString("terminal_theme", th).apply()
        }
        th
    }

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

    val commandAutoExecutionPolicy: Flow<String> = context.dataStore.data.map {
        it[COMMAND_AUTO_EXECUTION_POLICY] ?: "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER"
    }
    val commandSandboxEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[COMMAND_SANDBOX_ENABLED] ?: false
    }
    val requireApprovalForFileEdits: Flow<Boolean> = context.dataStore.data.map {
        it[REQUIRE_APPROVAL_FOR_FILE_EDITS] ?: false
    }
    val defaultApprovalScope: Flow<String> = context.dataStore.data.map {
        it[DEFAULT_APPROVAL_SCOPE] ?: "PERMISSION_SCOPE_ONCE"
    }

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

    suspend fun setBrowserAutomationEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("is_browser_automation_enabled", enabled).apply()
        context.dataStore.edit { prefs ->
            prefs[IS_BROWSER_AUTOMATION_ENABLED] = enabled
        }
    }

    suspend fun setTerminalAutomationEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("is_terminal_automation_enabled", enabled).apply()
        context.dataStore.edit { prefs ->
            prefs[IS_TERMINAL_AUTOMATION_ENABLED] = enabled
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
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            currentHubUrl = trimmed
            syncPrefs.edit().putString("agy_hub_url", trimmed).apply()
            context.dataStore.edit { prefs ->
                prefs[AGY_HUB_URL] = trimmed
            }
        }
    }

    suspend fun saveAgyBridgeHttpUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            currentBridgeHttpUrl = trimmed
            syncPrefs.edit().putString("agy_bridge_http_url", trimmed).apply()
            context.dataStore.edit { prefs ->
                prefs[AGY_BRIDGE_HTTP_URL] = trimmed
            }
        }
    }

    suspend fun saveThemeMode(mode: String) {
        syncPrefs.edit().putString("app_theme_mode", mode).commit()
        context.dataStore.edit { prefs ->
            prefs[APP_THEME_MODE] = mode
        }
    }

    suspend fun saveGroupChatsByWorkspace(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[GROUP_CHATS_BY_WORKSPACE] = enabled
        }
    }

    fun saveTerminalPreferencesSync(fontSize: Int, cursorStyle: String = "BAR", bufferSize: Int = 20000, theme: String = "DEFAULT") {
        syncPrefs.edit()
            .putInt("terminal_font_size", fontSize)
            .putString("terminal_cursor_style", cursorStyle)
            .putInt("terminal_buffer_size", bufferSize)
            .putString("terminal_theme", theme)
            .apply()
    }

    suspend fun saveTerminalPreferences(fontSize: Int, cursorStyle: String, bufferSize: Int, theme: String) {
        saveTerminalPreferencesSync(fontSize, cursorStyle, bufferSize, theme)
        context.dataStore.edit { prefs ->
            prefs[TERMINAL_FONT_SIZE] = fontSize
            prefs[TERMINAL_CURSOR_STYLE] = cursorStyle
            prefs[TERMINAL_BUFFER_SIZE] = bufferSize
            prefs[TERMINAL_THEME] = theme
        }
    }

    suspend fun savePreferredModel(modelId: String, modelKey: String = "", displayName: String = "") {
        context.dataStore.edit { prefs ->
            prefs[PREFERRED_MODEL_ID] = modelId
            if (modelKey.isNotBlank()) {
                prefs[PREFERRED_MODEL_KEY] = modelKey
            }
            if (displayName.isNotBlank()) {
                prefs[PREFERRED_MODEL_NAME] = displayName
            }
        }
    }

    suspend fun setCommandAutoExecutionPolicy(policy: String) {
        context.dataStore.edit { prefs ->
            prefs[COMMAND_AUTO_EXECUTION_POLICY] = policy
        }
    }

    suspend fun setCommandSandboxEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[COMMAND_SANDBOX_ENABLED] = enabled
        }
    }

    suspend fun setRequireApprovalForFileEdits(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[REQUIRE_APPROVAL_FOR_FILE_EDITS] = enabled
        }
    }

    suspend fun setDefaultApprovalScope(scope: String) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_APPROVAL_SCOPE] = scope
        }
    }

    val isFloatingBubbleEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[IS_FLOATING_BUBBLE_ENABLED] ?: true }

    fun getFloatingBubbleEnabledSync(): Boolean = syncPrefs.getBoolean("is_floating_bubble_enabled", true)

    suspend fun saveFloatingBubbleEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("is_floating_bubble_enabled", enabled).apply()
        context.dataStore.edit { it[IS_FLOATING_BUBBLE_ENABLED] = enabled }
    }

    val autoShowFloatingBubbleOnMinimize: Flow<Boolean> = context.dataStore.data
        .map { it[AUTO_SHOW_FLOATING_BUBBLE_ON_MINIMIZE] ?: true }

    fun getAutoShowFloatingBubbleOnMinimizeSync(): Boolean = syncPrefs.getBoolean("auto_show_floating_bubble_on_minimize", true)

    suspend fun saveAutoShowFloatingBubbleOnMinimize(enabled: Boolean) {
        syncPrefs.edit().putBoolean("auto_show_floating_bubble_on_minimize", enabled).apply()
        context.dataStore.edit { it[AUTO_SHOW_FLOATING_BUBBLE_ON_MINIMIZE] = enabled }
    }

    val isFloatingSwitcherEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[IS_FLOATING_SWITCHER_ENABLED] ?: true }

    fun getFloatingSwitcherEnabledSync(): Boolean = syncPrefs.getBoolean("is_floating_switcher_enabled", true)

    suspend fun saveFloatingSwitcherEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("is_floating_switcher_enabled", enabled).apply()
        context.dataStore.edit { it[IS_FLOATING_SWITCHER_ENABLED] = enabled }
    }

    val floatingSwitcherOrientation: Flow<String> = context.dataStore.data
        .map { it[FLOATING_SWITCHER_ORIENTATION] ?: "HORIZONTAL" }

    fun getFloatingSwitcherOrientationSync(): String = syncPrefs.getString("floating_switcher_orientation", "HORIZONTAL") ?: "HORIZONTAL"

    suspend fun saveFloatingSwitcherOrientation(orientation: String) {
        syncPrefs.edit().putString("floating_switcher_orientation", orientation).apply()
        context.dataStore.edit { it[FLOATING_SWITCHER_ORIENTATION] = orientation }
    }

    val floatingSwitcherItems: Flow<List<String>> = context.dataStore.data
        .map {
            val raw = it[FLOATING_SWITCHER_ITEMS] ?: "chat,ide,terminal,browser"
            raw.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }
        }

    fun getFloatingSwitcherItemsSync(): List<String> {
        val raw = syncPrefs.getString("floating_switcher_items", "chat,ide,terminal,browser") ?: "chat,ide,terminal,browser"
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun saveFloatingSwitcherItems(items: List<String>) {
        val raw = items.joinToString(",")
        syncPrefs.edit().putString("floating_switcher_items", raw).apply()
        context.dataStore.edit { it[FLOATING_SWITCHER_ITEMS] = raw }
    }

    val floatingSwitcherAutoCollapseSec: Flow<Int> = context.dataStore.data
        .map { it[FLOATING_SWITCHER_AUTO_COLLAPSE_SEC] ?: 0 }

    fun getFloatingSwitcherAutoCollapseSecSync(): Int = syncPrefs.getInt("floating_switcher_auto_collapse_sec", 0)

    suspend fun saveFloatingSwitcherAutoCollapseSec(sec: Int) {
        syncPrefs.edit().putInt("floating_switcher_auto_collapse_sec", sec).apply()
        context.dataStore.edit { it[FLOATING_SWITCHER_AUTO_COLLAPSE_SEC] = sec }
    }

    val floatingSwitcherPosX: Flow<Float> = context.dataStore.data
        .map { it[FLOATING_SWITCHER_POS_X] ?: 0.95f }

    val floatingSwitcherPosY: Flow<Float> = context.dataStore.data
        .map { it[FLOATING_SWITCHER_POS_Y] ?: 0.50f }

    fun getFloatingSwitcherPositionSync(): Pair<Float, Float> {
        val x = syncPrefs.getFloat("floating_switcher_pos_x", 0.95f)
        val y = syncPrefs.getFloat("floating_switcher_pos_y", 0.50f)
        return Pair(x, y)
    }

    fun saveFloatingSwitcherPositionSync(x: Float, y: Float) {
        syncPrefs.edit()
            .putFloat("floating_switcher_pos_x", x)
            .putFloat("floating_switcher_pos_y", y)
            .apply()
    }

    suspend fun saveFloatingSwitcherPosition(x: Float, y: Float) {
        saveFloatingSwitcherPositionSync(x, y)
        context.dataStore.edit {
            it[FLOATING_SWITCHER_POS_X] = x
            it[FLOATING_SWITCHER_POS_Y] = y
        }
    }

    val isFloatingDiagnosticsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[IS_FLOATING_DIAGNOSTICS_ENABLED] ?: false }

    fun getFloatingDiagnosticsEnabledSync(): Boolean = syncPrefs.getBoolean("is_floating_diagnostics_enabled", false)

    suspend fun saveFloatingDiagnosticsEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("is_floating_diagnostics_enabled", enabled).apply()
        context.dataStore.edit { it[IS_FLOATING_DIAGNOSTICS_ENABLED] = enabled }
    }

    val isNetworkInspectorEnabled: Flow<Boolean> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "is_network_inspector_enabled") {
                trySend(getNetworkInspectorEnabledSync())
            }
        }
        syncPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getNetworkInspectorEnabledSync())
        awaitClose { syncPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getNetworkInspectorEnabledSync(): Boolean = syncPrefs.getBoolean("is_network_inspector_enabled", false)

    fun saveNetworkInspectorEnabledSync(enabled: Boolean) {
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled = enabled
        syncPrefs.edit().putBoolean("is_network_inspector_enabled", enabled).commit()
    }

    suspend fun saveNetworkInspectorEnabled(enabled: Boolean) {
        saveNetworkInspectorEnabledSync(enabled)
        context.dataStore.edit { it[IS_NETWORK_INSPECTOR_ENABLED] = enabled }
    }

    val isFloatingNetworkInspectorEnabled: Flow<Boolean> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "is_floating_network_inspector_enabled") {
                trySend(getFloatingNetworkInspectorEnabledSync())
            }
        }
        syncPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getFloatingNetworkInspectorEnabledSync())
        awaitClose { syncPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getFloatingNetworkInspectorEnabledSync(): Boolean = syncPrefs.getBoolean("is_floating_network_inspector_enabled", false)

    fun saveFloatingNetworkInspectorEnabledSync(enabled: Boolean) {
        com.example.gemini.data.remote.inspector.NetworkInspectorManager.isFloatingBubbleEnabled = enabled
        syncPrefs.edit().putBoolean("is_floating_network_inspector_enabled", enabled).commit()
    }

    suspend fun saveFloatingNetworkInspectorEnabled(enabled: Boolean) {
        saveFloatingNetworkInspectorEnabledSync(enabled)
        context.dataStore.edit { it[IS_FLOATING_NETWORK_INSPECTOR_ENABLED] = enabled }
    }

    val securityToken: Flow<String> = context.dataStore.data.map {
        it[SECURITY_TOKEN] ?: getSecurityTokenSync()
    }

    fun getSecurityTokenSync(): String {
        var token = syncPrefs.getString("security_token", null)
        if (token.isNullOrBlank() || token.length != TOKEN_LENGTH) {
            token = generateRandomToken()
            syncPrefs.edit().putString("security_token", token).apply()
        }
        currentSecurityToken = token
        return token
    }

    suspend fun saveSecurityToken(token: String) {
        val clean = token.trim()
        val validToken = if (clean.length == TOKEN_LENGTH) clean else generateRandomToken()
        syncPrefs.edit().putString("security_token", validToken).apply()
        currentSecurityToken = validToken
        context.dataStore.edit { it[SECURITY_TOKEN] = validToken }
    }

    val agyBridgeBinaryPath: Flow<String> = context.dataStore.data.map {
        it[AGY_BRIDGE_BINARY_PATH] ?: getAgyBridgeBinaryPathSync()
    }

    fun getAgyBridgeBinaryPathSync(): String =
        syncPrefs.getString("agy_bridge_binary_path", DEFAULT_BRIDGE_BINARY_PATH) ?: DEFAULT_BRIDGE_BINARY_PATH

    suspend fun saveAgyBridgeBinaryPath(path: String) {
        val trimmed = path.trim().ifBlank { DEFAULT_BRIDGE_BINARY_PATH }
        syncPrefs.edit().putString("agy_bridge_binary_path", trimmed).apply()
        context.dataStore.edit { it[AGY_BRIDGE_BINARY_PATH] = trimmed }
    }

    val agyBinaryPath: Flow<String> = context.dataStore.data.map {
        it[AGY_BINARY_PATH] ?: getAgyBinaryPathSync()
    }

    fun getAgyBinaryPathSync(): String =
        syncPrefs.getString("agy_binary_path", DEFAULT_AGY_BINARY_PATH) ?: DEFAULT_AGY_BINARY_PATH

    suspend fun saveAgyBinaryPath(path: String) {
        val trimmed = path.trim().ifBlank { DEFAULT_AGY_BINARY_PATH }
        syncPrefs.edit().putString("agy_binary_path", trimmed).apply()
        context.dataStore.edit { it[AGY_BINARY_PATH] = trimmed }
    }

    val hasSeenTerminalLauncherOnboarding: Flow<Boolean> = context.dataStore.data.map {
        it[HAS_SEEN_TERMINAL_LAUNCHER_ONBOARDING] ?: false
    }

    suspend fun setHasSeenTerminalLauncherOnboarding(seen: Boolean) {
        context.dataStore.edit { it[HAS_SEEN_TERMINAL_LAUNCHER_ONBOARDING] = seen }
    }

    val terminalLauncherStyle: Flow<String> = context.dataStore.data.map {
        it[TERMINAL_LAUNCHER_STYLE] ?: "ANTITERM"
    }

    suspend fun setTerminalLauncherStyle(style: String) {
        context.dataStore.edit { it[TERMINAL_LAUNCHER_STYLE] = style }
    }

    val isTerminalLauncherEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[IS_TERMINAL_LAUNCHER_ENABLED] ?: true
    }

    suspend fun setTerminalLauncherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IS_TERMINAL_LAUNCHER_ENABLED] = enabled }
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
