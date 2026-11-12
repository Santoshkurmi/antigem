package com.example.gemini.data.miniapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a mini app may do with AntiGem's MCP servers (AntiGem.mcp). The manifest asks for a whole server, only its
 * read-only tools, or a list of tools; each tool is then its own permission the user can switch off (like storage
 * folders). Servers that can control the phone (terminal, Android, browser) need the typed confirmation and, by default,
 * the user's OK before every call. A few servers are never reachable from a mini app at all.
 *
 * Permission names: "mcp:<server>" (whole server, spec {} or {"readOnly": true}) and "mcp:<server>/<tool>" (one tool).
 */
object MiniAppMcpAccess {
    /** Never reachable from a mini app: installing plugins, voice control, building and scripting other mini apps. */
    val BLOCKED = setOf("custom_plugin_developer_mcp", "webview_extension_developer_mcp", "jarvis_mcp", "mini_app_developer_mcp")

    /** Servers that run commands, drive the phone or read what's in the browser. */
    fun isSensitive(server: String): Boolean = server == "terminal_mcp" || server == "android_automation_mcp" || server == "browser_mcp" ||
        server == "google_flow_mcp" || listOf("terminal", "android", "browser", "shell").any { server.contains(it) }

    /** Built-in tools that only read (the built-in servers don't set readOnlyHint, and their schemas stay as agents know them). */
    private val READ_ONLY = mapOf(
        "notes_mcp" to setOf("notes_list", "notes_get"),
        "theme_mcp" to setOf("theme_list", "theme_get", "theme_tokens", "theme_export"),
        "browser_mcp" to setOf("browser_list_tabs", "browser_read_page", "browser_find", "browser_get_page_text", "browser_get_console_logs", "browser_read_network"),
        "terminal_mcp" to setOf("terminal_list_sessions", "terminal_read_output"),
    )

    fun isReadOnly(server: String, tool: JSONObject): Boolean =
        tool.optJSONObject("annotations")?.optBoolean("readOnlyHint") == true || tool.optString("name") in READ_ONLY[server].orEmpty()

    /** The permission that covers [tool] on [server]: the tool's own, else the whole server's; null when not declared. */
    fun permissionFor(app: MiniApp, server: String, tool: String): String? = when {
        "mcp:$server/$tool" in app.permissions -> "mcp:$server/$tool"
        "mcp:$server" in app.permissions -> "mcp:$server"
        else -> null
    }

    fun declaresServer(app: MiniApp, server: String) = app.permissions.keys.any { it == "mcp:$server" || it.startsWith("mcp:$server/") }

    /** Of [tools] (a tools/list result), the ones the app may use now. */
    fun allowedTools(context: Context, app: MiniApp, server: String, tools: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            if (allows(context, app, server, t)) put(t)
        }
    }

    fun allows(context: Context, app: MiniApp, server: String, tool: JSONObject): Boolean {
        val name = tool.optString("name")
        val perm = permissionFor(app, server, name) ?: return false
        if (!MiniAppPermissions.isGranted(context, app, perm)) return false
        return perm != "mcp:$server" || app.permissions[perm]?.optBoolean("readOnly") != true || isReadOnly(server, tool)
    }

    // ---------------- ask before each call ----------------

    private fun prefs(context: Context) = context.getSharedPreferences("mini_app_mcp_ask", Context.MODE_PRIVATE)
    private fun stored(context: Context, app: MiniApp) = runCatching { JSONObject(prefs(context).getString(app.id, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** The manifest's default for [server]: it asked for it ("ask"), or the server is sensitive. */
    fun askByDefault(app: MiniApp, server: String, tool: String? = null): Boolean {
        if (isSensitive(server)) return true
        if (!app.mcpAsk.containsKey(server)) return false
        val only = app.mcpAsk[server] ?: return true // every tool
        return tool == null || tool in only
    }

    /** Whether the user's OK is needed before calling [tool]: the user's App info choice wins over the default. */
    fun asks(context: Context, app: MiniApp, server: String, tool: String): Boolean {
        val s = stored(context, app)
        if (s.optJSONArray("always")?.let { a -> (0 until a.length()).any { a.optString(it) == "$server/$tool" } } == true) return false
        return if (s.has(server)) s.optBoolean(server) else askByDefault(app, server, tool)
    }

    /** The App info switch: on = the user's OK before every call to [server]. */
    fun askSwitch(context: Context, app: MiniApp, server: String): Boolean {
        val s = stored(context, app)
        return if (s.has(server)) s.optBoolean(server) else askByDefault(app, server)
    }

    fun setAsk(context: Context, app: MiniApp, server: String, ask: Boolean) {
        val s = stored(context, app).put(server, ask)
        // switching asking back on forgets the "always allow" answers for that server
        if (ask) s.optJSONArray("always")?.let { a -> s.put("always", JSONArray((0 until a.length()).map { a.optString(it) }.filterNot { it.startsWith("$server/") })) }
        prefs(context).edit().putString(app.id, s.toString()).apply()
    }

    /** "Always allow" in the per-call question: that tool doesn't ask again. */
    fun allowAlways(context: Context, app: MiniApp, server: String, tool: String) {
        val s = stored(context, app)
        val a = s.optJSONArray("always") ?: JSONArray().also { s.put("always", it) }
        if ((0 until a.length()).none { a.optString(it) == "$server/$tool" }) a.put("$server/$tool")
        prefs(context).edit().putString(app.id, s.toString()).apply()
    }

    fun reset(context: Context, appId: String) = prefs(context).edit().remove(appId).apply()

    /** The servers an app declares (for App info). */
    fun servers(app: MiniApp): List<String> = app.permissions.keys.filter { it.startsWith("mcp:") }.map { it.removePrefix("mcp:").substringBefore('/') }.distinct()
}
