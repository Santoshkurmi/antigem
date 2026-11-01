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
        ),
        CustomSlashCommand(
            name = "browser_mcp",
            description = "Do a task in AntiGem's built-in browser with the Browser Automation MCP",
            expansion = """
                Use AntiGem's built-in browser to do this: ${'$'}ARGUMENTS
                If that is empty or unclear, first ask the user what to do and on which website, then continue.

                1. Check that you have the Browser Automation MCP: its tools start with browser_ (server browser_mcp), such as browser_open_url, browser_screenshot, browser_inspect_dom and browser_interact. It might not be added for this agent yet. If you don't have these tools, stop and tell the user how to add it:
                   - Antigravity: open AntiGem → Settings → MCP Servers & Tools, tap the "Browser Automation" preset under Quick Presets, and save.
                   - Claude Code: open AntiGem → Settings → Claude Code → MCP servers, and tap the "Browser Automation" preset.
                   - Then reload the tools or start a new chat, and send this command again.
                   Don't try to do the task another way while the tools are missing.
                2. Open the page with browser_open_url, then look before you act: browser_inspect_dom for the elements and selectors, browser_screenshot to see the page.
                3. Act with browser_interact (click, type, scroll) using selectors from the latest inspection, and check the result with another inspection or screenshot after each step. Use browser_get_console_logs when something fails.
                4. Ask the user before anything outward-facing or hard to undo: submitting forms, sending messages, posting, buying, or signing in with their account. If a page needs them to log in or solve a captcha, ask them to do it in the browser and tell you when they're done.
                5. Finish with what you did and what you found.
            """.trimIndent()
        ),
        CustomSlashCommand(
            name = "automation",
            description = "Control this phone (read the screen, tap, type, open apps) with the Android Automation MCP",
            expansion = """
                Use Android Automation to do this on the user's phone: ${'$'}ARGUMENTS
                If that is empty or unclear, first ask the user what they want done, then continue.

                1. Check that you have the Android Automation MCP: its tools start with android_ (server android_automation_mcp), such as android_get_ui_tree, android_screenshot and android_tap. It might not be set up yet. If you don't have these tools, or they return an error saying a permission is missing, stop and tell the user how to set it up:
                   - Allow it: open AntiGem → Settings → Utilities → Android Automation, tap "Open Accessibility" and turn on "AntiGem Android Automation". On Android 13+, if the switch is greyed out ("Restricted setting"), tap "Open App info" on that page, then ⋮ (top right) → Allow restricted settings, and try again. If ADB is connected, "Turn on with ADB" on that page does it in one tap.
                   - Optional: for key presses, installing, uninstalling, force-stopping apps and adb shell commands, connect ADB in AntiGem → Settings → Utilities → ADB Connect.
                   - Add the MCP. For Antigravity: Settings → MCP Servers & Tools, tap the "Android Automation" preset, and save. For Claude Code: Settings → Claude Code → MCP servers, and tap the "Android Automation" preset.
                   - Then reload the tools or start a new chat, and send this command again.
                   Don't try to do the task another way while the tools are missing.
                2. Start with android_get_status. If the screen is off or locked, ask the user to unlock the phone.
                3. Look before you act: android_get_ui_tree for elements and their ids, android_screenshot when you need to see the screen. Open apps with android_launch_app (android_list_apps to find the package).
                4. Act with element ids from the latest tree (android_tap, android_type_text, android_scroll), and read the tree again after each step, since ids change whenever the screen does. If a tool says ADB is needed, tell the user how to connect it as above.
                5. Ask the user before anything destructive or outward-facing: uninstalling apps, deleting data, sending messages or emails, calls, payments, or changing security settings.
                6. Finish with what you did and the state the phone is in now.
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
