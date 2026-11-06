package com.example.gemini.theme

import org.json.JSONArray
import org.json.JSONObject

/**
 * theme_mcp: lets a model see, switch, create and edit the app's color themes (served by AndroidLocalBridgeServer at
 * /theme). Everything goes through [ThemeManager], the same place Settings → Appearance uses.
 */
object ThemeMcp {

    const val INSTRUCTIONS = "Controls AntiGem's color themes. theme_list shows every theme (built-in and custom) and which is used for the light " +
        "and the dark side; theme_apply switches. To make a theme: call theme_tokens once to see every color and what it is for, then " +
        "theme_create with a name, dark=true/false and at least the seed colors (background, surface, card, surface_variant, on_background, " +
        "on_surface_variant, primary, secondary, tertiary, success, warning, danger, info, code_bg and the syntax_* colors); every other color is " +
        "worked out from them (the IDE editor, diff colors and the terminal palette follow code_bg / code_text and the status colors unless you set editor_* , terminal_* or ansi_*). Read the readability warnings it returns and fix them with theme_update. Built-in themes cannot be changed: " +
        "duplicate one first. Colors are hex like #1E293B or #FF1E293B."

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val idProp = "id" to prop("string", "Theme id or name (default: the theme on screen)")
    private val colorsProp = "colors" to prop("object", "Token → hex color, e.g. {\"background\": \"#0B1020\", \"primary\": \"#FF4FD8\"}. Keys from theme_tokens.")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("theme_list", "Every theme (built-in and custom), light or dark, and which one is used for each side and on screen now."))
        put(tool("theme_get", "All colors of one theme (as hex), plus its readability check.", emptyList(), idProp))
        put(tool("theme_tokens", "Every color token a theme can set, what it is used for, and which ones are the seeds to give when creating a theme."))
        put(tool("theme_apply", "Use a theme (for its side: light or dark). If the app's mode would not show that side, the mode switches to it.",
            listOf("id"), idProp))
        put(tool("theme_set_mode", "Light, dark, or follow the system (Auto).", listOf("mode"),
            "mode" to prop("string", "The mode", listOf("system", "light", "dark"))))
        put(tool("theme_create",
            "Create a custom theme. Give the seed colors (see theme_tokens); the rest is worked out from them or taken from base. Returns the id and readability warnings.",
            listOf("name", "dark"),
            "name" to prop("string", "Theme name, e.g. Neon Night"),
            "dark" to prop("boolean", "true for a dark theme, false for a light one"),
            colorsProp,
            "base" to prop("string", "Theme to start from (default: the default theme of the same side)"),
            "about" to prop("string", "One line describing the look"),
            "apply" to prop("boolean", "Use it right away (default true)")))
        put(tool("theme_update", "Change colors, name or description of a custom theme (only the given colors change; dependent ones are worked out again).",
            listOf("id"), idProp, colorsProp,
            "name" to prop("string", "New name"), "about" to prop("string", "New description"),
            "remove" to prop("array", "Tokens to reset to the base theme's value"),
            "apply" to prop("boolean", "Also use it now")))
        put(tool("theme_duplicate", "Copy a theme (built-in or custom) into a new custom theme you can edit.", listOf("id"), idProp,
            "name" to prop("string", "Name of the copy")))
        put(tool("theme_delete", "Delete a custom theme. Ask the user first.", listOf("id"), idProp))
        put(tool("theme_export", "A theme as JSON (to share or keep).", emptyList(), idProp))
        put(tool("theme_import", "Add a theme from JSON (as made by theme_export).", listOf("json"),
            "json" to prop("string", "The theme JSON"), "apply" to prop("boolean", "Use it right away (default false)")))
    }

    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun json(o: JSONObject) = text(o.toString(2))
    private fun error(message: String) = text(message).put("isError", true)

    private fun target(args: JSONObject): AppTheme? = args.optString("id").trim().ifBlank { null }?.let { ThemeManager.find(it) } ?: ThemeManager.current

    private fun themeJson(t: AppTheme) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("dark", t.dark); put("built_in", t.builtIn)
        if (t.about.isNotBlank()) put("about", t.about)
        t.base?.let { put("base", it) }
        val uses = listOfNotNull(
            "light side".takeIf { ThemeManager.lightId == t.id && !t.dark },
            "dark side".takeIf { ThemeManager.darkId == t.id && t.dark },
            "on screen now".takeIf { ThemeManager.current.id == t.id }
        )
        if (uses.isNotEmpty()) put("in_use", JSONArray(uses))
    }

    /** Parses "colors"; unknown tokens and bad values are reported, not applied. */
    private fun colorsOf(args: JSONObject, problems: MutableList<String>): Map<String, Long> {
        val o = args.optJSONObject("colors") ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        o.keys().forEach { k ->
            val key = k.trim().lowercase()
            when {
                key !in ThemeTokens.keys -> problems += "unknown token \"$k\" (see theme_tokens)"
                else -> ThemeTokens.parse(o.optString(k))?.let { out[key] = it } ?: run { problems += "\"$k\": \"${o.optString(k)}\" is not a hex color" }
            }
        }
        return out
    }

    private fun report(t: AppTheme, problems: List<String>, appliedMode: String?, applied: Boolean): JSONObject {
        val warnings = ThemeTokens.check(ThemeManager.resolvedColors(t))
        return json(themeJson(t).apply {
            put("applied", applied)
            appliedMode?.let { put("mode_switched_to", it.lowercase()) }
            if (problems.isNotEmpty()) put("ignored", JSONArray(problems))
            put("readability", if (warnings.isEmpty()) JSONArray().put("ok") else JSONArray(warnings))
            if (warnings.isNotEmpty()) put("hint", "Fix the low-contrast pairs with theme_update (lighter text on dark, darker text on light).")
        })
    }

    fun call(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "theme_list" -> json(JSONObject().apply {
                put("mode", ThemeManager.mode().lowercase())
                put("light_side", ThemeManager.lightId); put("dark_side", ThemeManager.darkId); put("on_screen", ThemeManager.current.id)
                put("themes", JSONArray().apply { ThemeManager.all().forEach { put(themeJson(it)) } })
            })
            "theme_get" -> {
                val t = target(args) ?: return error("No theme \"${args.optString("id")}\". Use theme_list.")
                val colors = ThemeManager.resolvedColors(t)
                json(themeJson(t).apply {
                    put("colors", JSONObject().apply { colors.forEach { (k, v) -> put(k, ThemeTokens.hex(v)) } })
                    put("readability", JSONArray(ThemeTokens.check(colors).ifEmpty { listOf("ok") }))
                })
            }
            "theme_tokens" -> json(JSONObject().apply {
                put("seeds", JSONArray(ThemeTokens.seeds))
                put("tokens", JSONArray().apply {
                    ThemeTokens.all.forEach { put(JSONObject().put("key", it.key).put("group", it.group).put("used_for", it.about)) }
                })
                put("note", "Give the seeds when creating; the rest (containers, on_* text colors, outlines, surface levels) is worked out unless you set them.")
            })
            "theme_apply" -> {
                val t = target(args) ?: return error("No theme \"${args.optString("id")}\". Use theme_list.")
                val mode = ThemeManager.select(t)
                text("Using ${t.name} for the ${if (t.dark) "dark" else "light"} side" + (mode?.let { ", and switched the app to ${it.lowercase()} mode" } ?: "") + ".")
            }
            "theme_set_mode" -> {
                val m = when (args.optString("mode").lowercase()) { "light" -> "LIGHT"; "dark" -> "DARK"; "system", "auto" -> "SYSTEM"; else -> return error("Use system, light or dark.") }
                ThemeManager.setMode(m)
                text("Theme mode is now ${m.lowercase()}.")
            }
            "theme_create" -> {
                val nm = args.optString("name").trim().ifBlank { return error("name is required") }
                if (!args.has("dark")) return error("Say whether it is a dark theme (dark=true) or a light one (dark=false).")
                val dark = args.optBoolean("dark")
                val problems = ArrayList<String>()
                val colors = colorsOf(args, problems)
                val base = args.optString("base").trim().ifBlank { null }?.let { ThemeManager.find(it) ?: return error("No base theme \"$it\".") }
                    ?: BuiltInThemes.default(dark)
                if (base.dark != dark) problems += "base ${base.name} is a ${if (base.dark) "dark" else "light"} theme; its colors were still used where you gave none"
                val t = ThemeManager.saveCustom(AppTheme(id = "", name = nm.take(40), dark = dark, about = args.optString("about").take(200), base = base.id, colors = colors))
                val apply = args.optBoolean("apply", true)
                val mode = if (apply) ThemeManager.select(t) else null
                report(t, problems, mode, apply)
            }
            "theme_update" -> {
                val t = target(args) ?: return error("No theme \"${args.optString("id")}\".")
                if (t.builtIn) return error("${t.name} is built in and cannot be changed. Use theme_duplicate, then update the copy.")
                val problems = ArrayList<String>()
                val changes = colorsOf(args, problems)
                val remove = args.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } }.orEmpty()
                val updated = ThemeManager.saveCustom(t.copy(
                    name = args.optString("name").trim().ifBlank { t.name }.take(40),
                    about = if (args.has("about")) args.optString("about").take(200) else t.about,
                    colors = (t.colors - remove.toSet()) + changes
                ))
                val apply = args.optBoolean("apply") || ThemeManager.current.id == updated.id
                val mode = if (args.optBoolean("apply")) ThemeManager.select(updated) else null
                report(updated, problems, mode, apply)
            }
            "theme_duplicate" -> {
                val t = target(args) ?: return error("No theme \"${args.optString("id")}\".")
                val nm = args.optString("name").trim().ifBlank { "${t.name} copy" }
                // the copy keeps every resolved color, so it looks the same even if the original changes later
                val copy = ThemeManager.saveCustom(AppTheme(id = "", name = nm.take(40), dark = t.dark, about = t.about, base = t.id,
                    colors = ThemeManager.resolvedColors(t).filterKeys { it in ThemeTokens.keys }))
                json(themeJson(copy).put("note", "Edit it with theme_update."))
            }
            "theme_delete" -> {
                val t = ThemeManager.find(args.optString("id")) ?: return error("No theme \"${args.optString("id")}\".")
                if (t.builtIn) return error("Built-in themes cannot be deleted.")
                ThemeManager.deleteCustom(t.id)
                text("Deleted ${t.name}.")
            }
            "theme_export" -> {
                val t = target(args) ?: return error("No theme \"${args.optString("id")}\".")
                val o = ThemeManager.toJson(t.copy(colors = ThemeManager.resolvedColors(t).filterKeys { it in ThemeTokens.keys }, base = null))
                text(o.toString(2))
            }
            "theme_import" -> {
                val o = runCatching { JSONObject(args.optString("json")) }.getOrNull() ?: return error("json is not valid JSON.")
                val t = ThemeManager.fromJson(o) ?: return error("The JSON needs at least a name and colors.")
                val saved = ThemeManager.saveCustom(t.copy(id = ThemeManager.newId(t.name)))
                val mode = if (args.optBoolean("apply")) ThemeManager.select(saved) else null
                report(saved, emptyList(), mode, args.optBoolean("apply"))
            }
            else -> error("Unknown tool $name")
        }
    } catch (e: Exception) {
        error("$name failed: ${e.message ?: e.javaClass.simpleName}")
    }
}
