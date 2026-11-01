package com.example.gemini.data.remote

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * A slash command the user defines in Settings → Slash Commands. Sending `/name args` sends [expansion]
 * instead, with `$ARGUMENTS` replaced by what follows the command; the chat still shows `/name args`.
 * [pluginId]/[skill] optionally attach a custom AntiGem plugin (and one of its skills) like `/plu:` does.
 */
data class CustomSlashCommand(
    val name: String,
    val description: String = "",
    val expansion: String = "",
    val pluginId: String = "",
    val skill: String = ""
)

object CustomSlashCommands {
    private const val PREFS = "custom_slash_commands"
    private const val KEY = "commands"
    /** Names of built-in commands already offered once, so a deleted one doesn't come back; new built-ins still do. */
    private const val SEEDED = "seeded_defaults"
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")

    /** Built-in commands, added once and then edited or deleted like the user's own. */
    val DEFAULTS = listOf(
        CustomSlashCommand(
            name = "custom_plugin",
            description = "Create a custom AntiGem plugin with the plugin developer MCP",
            expansion = """
                The user wants to create a custom AntiGem plugin: a package that gives you MCP tools and/or skills inside the AntiGem app (tools can automate websites in the app's browser). What it should do: ${'$'}ARGUMENTS
                If that is empty or unclear, first ask what the plugin should do and on which website, then continue.

                1. Check that you have the Custom AntiGem Plugin Developer MCP for this agent: its tools start with webview_extension_ (server custom_plugin_developer_mcp, or webview_extension_developer_mcp in older setups). If you don't have them, stop and tell the user to open MCP settings, add the "Custom AntiGem Plugin Developer" preset, then reload tools or start a new chat and send this command again. Don't build the plugin without it: the SDK docs, installer, tests and logs come from that server.
                2. Read the SDK before writing code: webview_extension_docs for README.md, developer-mcp.md, lifecycle.md and skills.md, plus chrome-api.md / antigem-api.md for the APIs you use. Use only documented APIs.
                3. Create a draft with webview_extension_create and write files with webview_extension_write_files: a manifest with a version and an icon (an SVG written as text), narrow host permissions, tools that validate their arguments, and skills/<name>.md guides that explain how to use the tools for real tasks.
                4. Install with webview_extension_install, list its tools with webview_extension_test, then really exercise the main tools. On errors read webview_extension_logs, fix the draft, raise the version and install again until it works.
                5. Finish by telling the user the plugin ID, its tools and skills, what you tested, and that they can add it under MCP settings → Custom AntiGem Plugin MCP and point you at it in chat with /plu:<plugin id>.
            """.trimIndent()
        )
    )

    val commands = mutableStateListOf<CustomSlashCommand>()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { stored ->
            runCatching {
                val array = JSONArray(stored)
                commands.addAll((0 until array.length()).map { i ->
                    array.getJSONObject(i).let {
                        CustomSlashCommand(it.getString("name"), it.optString("description"), it.optString("expansion"), it.optString("pluginId"), it.optString("skill"))
                    }
                })
            }
        }
        val seeded = prefs.getStringSet(SEEDED, emptySet()).orEmpty()
        val fresh = DEFAULTS.filter { it.name !in seeded }
        if (fresh.isEmpty()) return
        fresh.filter { d -> commands.none { it.name.equals(d.name, ignoreCase = true) } }.forEach { commands.add(it) }
        prefs.edit().putStringSet(SEEDED, seeded + fresh.map { it.name }).apply()
        persist()
    }

    fun isDefault(name: String) = DEFAULTS.any { it.name.equals(name, ignoreCase = true) }

    /** Brings back deleted built-in commands and resets edited ones; the user's own commands are untouched. */
    fun restoreDefaults() {
        for (d in DEFAULTS) {
            val index = commands.indexOfFirst { it.name.equals(d.name, ignoreCase = true) }
            if (index >= 0) commands[index] = d else commands.add(d)
        }
        persist()
    }

    /** Adds [command], or replaces the one named [previousName]. Throws with a readable message if invalid. */
    fun save(command: CustomSlashCommand, previousName: String? = null) {
        require(NAME.matches(command.name)) { "Use 1–48 letters, numbers, underscores or hyphens, starting with a letter or number" }
        require(!command.name.equals("plu", ignoreCase = true)) { "/plu is reserved for custom AntiGem plugins" }
        require(commands.none { it.name.equals(command.name, ignoreCase = true) && !it.name.equals(previousName, ignoreCase = true) }) { "/${command.name} already exists" }
        require(command.expansion.isNotBlank() || command.pluginId.isNotBlank()) { "Write what the command expands to, or attach a plugin" }
        val index = commands.indexOfFirst { it.name.equals(previousName, ignoreCase = true) }
        if (index >= 0) commands[index] = command else commands.add(command)
        persist()
    }

    fun delete(name: String) {
        commands.removeAll { it.name == name }
        persist()
    }

    fun find(name: String): CustomSlashCommand? = commands.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun slashItems(): List<SlashCommandItem> = commands.map {
        SlashCommandItem(it.name, "/${it.name}", it.description.ifBlank { it.expansion.lineSequence().firstOrNull().orEmpty().take(80) }, "custom",
            argumentHint = if (it.expansion.contains("\$ARGUMENTS")) "arguments" else "")
    }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray(commands.map {
            JSONObject().put("name", it.name).put("description", it.description).put("expansion", it.expansion).put("pluginId", it.pluginId).put("skill", it.skill)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
