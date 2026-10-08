package com.example.gemini.data.agent.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Claude Code configuration through the bridge: `~/.claude/settings.json` (defaults, permission rules, everything
 * else), CLAUDE.md memory, MCP servers, plugins & marketplaces, and CLI install/update.
 */
class ClaudeConfigManager(
    private val scope: CoroutineScope,
    private val client: ClaudeBridgeClient
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------------------------------------------------------------- settings.json

    private val _settings = MutableStateFlow<JsonObject?>(null)
    val settings: StateFlow<JsonObject?> = _settings.asStateFlow()

    private val _settingsPath = MutableStateFlow("")
    val settingsPath: StateFlow<String> = _settingsPath.asStateFlow()

    private val _settingsError = MutableStateFlow<String?>(null)
    val settingsError: StateFlow<String?> = _settingsError.asStateFlow()

    /** Called after settings change so the chat picks up new defaults. */
    var onSettingsChanged: () -> Unit = {}

    fun loadSettings() {
        scope.launch {
            client.settings()
                .onSuccess {
                    _settingsPath.value = it.path
                    if (it.success) {
                        _settings.value = it.settings
                        _settingsError.value = null
                    } else _settingsError.value = it.error
                }
                .onFailure { _settingsError.value = it.message }
        }
    }

    fun saveSettings(newSettings: JsonObject, feedback: String = "Saved") {
        scope.launch {
            client.saveSettings(newSettings)
                .onSuccess {
                    if (it.success) {
                        _settings.value = newSettings
                        _settingsError.value = null
                        _messages.tryEmit(feedback)
                        onSettingsChanged()
                    } else _messages.tryEmit("Save failed: ${it.error}")
                }
                .onFailure { _messages.tryEmit("Save failed: ${it.message}") }
        }
    }

    /** Sets (or removes, when [value] is null) a top-level key. */
    fun setValue(key: String, value: JsonElement?) {
        val cur = _settings.value ?: JsonObject(emptyMap())
        val next = if (value == null) JsonObject(cur - key) else JsonObject(cur + (key to value))
        saveSettings(next)
    }

    fun stringValue(key: String): String? = (_settings.value?.get(key) as? JsonPrimitive)?.contentOrNull

    /** permissions.allow / deny / ask */
    fun rules(behavior: String): List<String> =
        ((_settings.value?.get("permissions") as? JsonObject)?.get(behavior) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun addRule(behavior: String, rule: String) {
        val clean = rule.trim()
        if (clean.isEmpty()) return
        updatePermissions { perms ->
            val updated = perms.toMutableMap()
            for (b in listOf("allow", "deny", "ask")) {
                val list = (perms[b] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.contentOrNull != clean }
                updated[b] = JsonArray(if (b == behavior) list + JsonPrimitive(clean) else list)
            }
            updated
        }
    }

    fun removeRule(behavior: String, rule: String) = updatePermissions { perms ->
        val updated = perms.toMutableMap()
        updated[behavior] = JsonArray((perms[behavior] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.contentOrNull != rule })
        updated
    }

    /** permissions.defaultMode */
    fun defaultMode(): String? = ((_settings.value?.get("permissions") as? JsonObject)?.get("defaultMode") as? JsonPrimitive)?.contentOrNull

    fun setDefaultMode(mode: String?) = updatePermissions { perms ->
        val updated = perms.toMutableMap()
        if (mode == null) updated.remove("defaultMode") else updated["defaultMode"] = JsonPrimitive(mode)
        updated
    }

    private fun updatePermissions(change: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        val cur = _settings.value ?: JsonObject(emptyMap())
        val perms = (cur["permissions"] as? JsonObject).orEmpty()
        val next = JsonObject(cur + ("permissions" to JsonObject(change(perms))))
        saveSettings(next)
    }

    // ---------------------------------------------------------------- memory (CLAUDE.md)

    private val _memory = MutableStateFlow<ClaudeMemoryResponse?>(null)
    val memory: StateFlow<ClaudeMemoryResponse?> = _memory.asStateFlow()

    fun loadMemory(scopeName: String, cwd: String?) {
        scope.launch {
            _memory.value = null
            client.memory(scopeName, cwd)
                .onSuccess { _memory.value = it }
                .onFailure { _messages.tryEmit("Could not read CLAUDE.md: ${it.message}") }
        }
    }

    fun saveMemory(scopeName: String, cwd: String?, content: String) {
        scope.launch {
            client.saveMemory(scopeName, cwd, content)
                .onSuccess { if (it.success) _messages.tryEmit("CLAUDE.md saved") else _messages.tryEmit("Save failed: ${it.error}") }
                .onFailure { _messages.tryEmit("Save failed: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- MCP

    private val _mcpServers = MutableStateFlow<List<ClaudeMcpServer>>(emptyList())
    val mcpServers: StateFlow<List<ClaudeMcpServer>> = _mcpServers.asStateFlow()

    private val _mcpLoading = MutableStateFlow(false)
    val mcpLoading: StateFlow<Boolean> = _mcpLoading.asStateFlow()

    fun loadMcp(cwd: String?) {
        scope.launch {
            _mcpLoading.value = true
            client.mcpServers(cwd)
                .onSuccess { if (it.success) _mcpServers.value = it.servers else _messages.tryEmit("MCP status failed: ${it.error}") }
                .onFailure { _messages.tryEmit("MCP status failed: ${it.message}") }
            _mcpLoading.value = false
        }
    }

    fun addMcp(req: ClaudeMcpAddRequest, cwd: String?) {
        scope.launch {
            _mcpLoading.value = true
            client.addMcpServer(req)
                .onSuccess { if (it.success) _messages.tryEmit("Added MCP server '${req.name}'") else _messages.tryEmit(it.error ?: "Add failed") }
                .onFailure { _messages.tryEmit("Add failed: ${it.message}") }
            _mcpLoading.value = false
            loadMcp(cwd)
        }
    }

    fun removeMcp(name: String, scopeName: String?, cwd: String?) {
        scope.launch {
            _mcpLoading.value = true
            client.removeMcpServer(name, scopeName, cwd)
                .onSuccess { if (it.success) _messages.tryEmit("Removed '$name'") else _messages.tryEmit(it.error ?: "Remove failed") }
                .onFailure { _messages.tryEmit("Remove failed: ${it.message}") }
            _mcpLoading.value = false
            loadMcp(cwd)
        }
    }

    // ---------------------------------------------------------------- plugins

    private val _plugins = MutableStateFlow<ClaudePluginsResponse?>(null)
    val plugins: StateFlow<ClaudePluginsResponse?> = _plugins.asStateFlow()

    private val _pluginBusy = MutableStateFlow<String?>(null)
    /** Plugin id (or marketplace) currently being changed. */
    val pluginBusy: StateFlow<String?> = _pluginBusy.asStateFlow()

    fun loadPlugins() {
        scope.launch {
            client.plugins()
                .onSuccess { _plugins.value = it }
                .onFailure { _messages.tryEmit("Plugin list failed: ${it.message}") }
        }
    }

    /** action: install | uninstall | enable | disable | update | marketplace-add | marketplace-remove | marketplace-update */
    fun pluginAction(action: String, body: ClaudePluginAction) {
        scope.launch {
            _pluginBusy.value = body.plugin.ifBlank { body.name ?: body.source ?: action }
            client.pluginAction(action, body)
                .onSuccess {
                    if (it.success) _messages.tryEmit("${action.replace('-', ' ').replaceFirstChar { c -> c.uppercase() }}: done")
                    else _messages.tryEmit(it.error?.lines()?.lastOrNull { l -> l.isNotBlank() } ?: "$action failed")
                }
                .onFailure { _messages.tryEmit("$action failed: ${it.message}") }
            _pluginBusy.value = null
            loadPlugins()
        }
    }

    // ---------------------------------------------------------------- CLI install / update

    private val _cliJob = MutableStateFlow<ClaudeCliJob?>(null)
    val cliJob: StateFlow<ClaudeCliJob?> = _cliJob.asStateFlow()

    /** Called when an install/update finishes. */
    var onCliChanged: () -> Unit = {}

    fun startCliJob(action: String) {
        scope.launch {
            client.startCliJob(action)
                .onSuccess { resp ->
                    if (!resp.success) {
                        _messages.tryEmit(resp.error ?: "$action failed to start")
                        return@onSuccess
                    }
                    _cliJob.value = resp.job
                    while (true) {
                        delay(1500)
                        val job = client.cliJob().getOrNull()?.job ?: break
                        _cliJob.value = job
                        if (!job.running) {
                            _messages.tryEmit(if (job.exit_code == 0) "Claude Code $action finished" else "Claude Code $action failed (exit ${job.exit_code})")
                            onCliChanged()
                            break
                        }
                    }
                }
                .onFailure { _messages.tryEmit("$action failed: ${it.message}") }
        }
    }
}
