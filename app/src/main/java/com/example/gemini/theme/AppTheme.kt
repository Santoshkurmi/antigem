package com.example.gemini.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * One app theme: a name, whether it is dark, and its colors by token (see [ThemeTokens]).
 *
 * Built-in themes carry their own colors. A custom theme (made in the app or by a model through theme_mcp) stores only
 * what it changes on top of [base]; everything else comes from the base, and colors that depend on a changed one
 * (e.g. the primary container on a new primary) are worked out again ([ThemeTokens.derive]).
 */
data class AppTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val about: String = "",
    val builtIn: Boolean = false,
    /** Custom themes: the theme they build on. */
    val base: String? = null,
    /** Token → ARGB. Built-in: the theme's colors. Custom: only the changed ones. */
    val colors: Map<String, Long> = emptyMap()
)

/** Every color a theme can set, with what it is used for. */
object ThemeTokens {
    class Token(val key: String, val group: String, val about: String)

    /** Material color slots (the whole UI reads these). */
    val material = listOf(
        Token("primary", "accent", "Main accent: buttons, selected items, links, the send button, active icons"),
        Token("on_primary", "accent", "Text and icons on primary"),
        Token("primary_container", "accent", "Soft accent fill (selected chips, highlighted rows)"),
        Token("on_primary_container", "accent", "Text on primary_container"),
        Token("secondary", "accent", "Second accent (pressed / darker accent, secondary buttons)"),
        Token("on_secondary", "accent", "Text on secondary"),
        Token("secondary_container", "accent", "Soft fill of the second accent"),
        Token("on_secondary_container", "accent", "Text on secondary_container"),
        Token("tertiary", "accent", "Third accent (badges, special highlights)"),
        Token("on_tertiary", "accent", "Text on tertiary"),
        Token("tertiary_container", "accent", "Soft fill of the third accent"),
        Token("on_tertiary_container", "accent", "Text on tertiary_container"),
        Token("background", "surface", "Screen background behind everything"),
        Token("on_background", "text", "Main text on the background"),
        Token("surface", "surface", "Default surface (top bars, sheets, the chat area)"),
        Token("on_surface", "text", "Main text on surfaces (used almost everywhere)"),
        Token("surface_variant", "surface", "Raised or tinted surface (inputs, chips, user message bubble)"),
        Token("on_surface_variant", "text", "Secondary text and icons (hints, captions, inactive icons)"),
        Token("surface_container_lowest", "surface", "Lowest container level"),
        Token("surface_container_low", "surface", "Low container level"),
        Token("surface_container", "surface", "Container level (menus, cards)"),
        Token("surface_container_high", "surface", "High container level (dialogs, raised menus)"),
        Token("surface_container_highest", "surface", "Highest container level"),
        Token("outline", "line", "Strong borders (text fields, outlined buttons)"),
        Token("outline_variant", "line", "Subtle dividers and card borders"),
        Token("error", "status", "Errors (Material components)"),
        Token("on_error", "status", "Text on error"),
        Token("error_container", "status", "Soft error fill"),
        Token("on_error_container", "status", "Text on error_container"),
        Token("inverse_surface", "surface", "Snackbars and toasts background"),
        Token("inverse_on_surface", "text", "Text on inverse_surface"),
        Token("inverse_primary", "accent", "Accent on inverse_surface")
    )

    /** AntiGem's own colors (cards, chat, code, status). */
    val app = listOf(
        Token("card", "surface", "Cards and panels (settings cards, pickers, bottom sheets)"),
        Token("text_secondary", "text", "Secondary text in AntiGem's own screens"),
        Token("user_bubble", "chat", "Your messages in the chat"),
        Token("thinking_bg", "chat", "Thinking / summary blocks in the chat"),
        Token("code_bg", "code", "Code block background in chats"),
        Token("code_text", "code", "Plain code text on code_bg"),
        Token("success", "status", "Success, running OK, online, positive quota"),
        Token("warning", "status", "Warnings, waiting, quota running low"),
        Token("danger", "status", "Failures, stop and delete buttons, offline"),
        Token("info", "status", "Information, links to other places, the browser accent"),
        Token("accent_alt", "accent", "Extra accent for special features (purple by default)"),
        Token("syntax_keyword", "code", "Code: keywords (if, fun, class)"),
        Token("syntax_type", "code", "Code: types and class names"),
        Token("syntax_string", "code", "Code: strings"),
        Token("syntax_number", "code", "Code: numbers"),
        Token("syntax_comment", "code", "Code: comments"),
        Token("syntax_function", "code", "Code: function names"),
        Token("syntax_annotation", "code", "Code: annotations and decorators"),
        Token("syntax_punctuation", "code", "Code: punctuation and operators"),
        Token("syntax_control", "code", "Code: control keywords (return, if, for); default = syntax_keyword"),
        Token("syntax_tag", "code", "Code: HTML/XML tags"),
        Token("syntax_attribute", "code", "Code: attributes and properties"),
        Token("border", "line", "Borders of cards, inputs and chips in AntiGem's own screens"),
        Token("fill", "surface", "Subtle fills: icon circles, inactive chips, hovered rows"),
        Token("success_container", "status", "Soft success background (added files, OK banners)"),
        Token("warning_container", "status", "Soft warning background (modified files, notices)"),
        Token("danger_container", "status", "Soft danger background (errors, deleted files)"),
        Token("info_container", "status", "Soft info background (tips, renamed files)"),
        Token("accent_indigo", "accent", "Decorative indigo (backups, some settings groups)"),
        Token("accent_teal", "accent", "Decorative teal / cyan (utilities, tools)"),
        Token("accent_pink", "accent", "Decorative pink"),
        Token("editor_bg", "editor", "IDE editor, diff and log viewers background"),
        Token("editor_panel", "editor", "IDE side panels, tab bar, toolbars"),
        Token("editor_text", "editor", "IDE plain text"),
        Token("editor_muted", "editor", "IDE line numbers and hints"),
        Token("editor_border", "editor", "IDE borders and separators"),
        Token("editor_selection", "editor", "IDE selection and search matches"),
        Token("editor_line", "editor", "IDE current line / inactive tab"),
        Token("editor_added_bg", "editor", "Diff: added line background"),
        Token("editor_added_text", "editor", "Diff: added line text"),
        Token("editor_removed_bg", "editor", "Diff: removed line background"),
        Token("editor_removed_text", "editor", "Diff: removed line text"),
        Token("editor_info_bg", "editor", "Diff: hunk header background"),
        Token("editor_info_text", "editor", "Diff: hunk header text"),
        Token("terminal_bg", "terminal", "Terminal background"),
        Token("terminal_fg", "terminal", "Terminal text"),
        Token("terminal_cursor", "terminal", "Terminal cursor")
    ) + (0..15).map { Token("ansi_$it", "terminal", "Terminal ANSI color $it (" + ansiNames[it] + ")") }

    private val ansiNames get() = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
        "bright black", "bright red", "bright green", "bright yellow", "bright blue", "bright magenta", "bright cyan", "bright white")

    val all = material + app
    val keys: Set<String> = all.map { it.key }.toSet()
    private val materialKeys = material.map { it.key }.toSet()
    fun isMaterial(key: String) = key in materialKeys

    /** The colors a new theme should set; the rest is worked out from them. */
    val seeds = listOf(
        "background", "surface", "card", "surface_variant", "on_background", "on_surface_variant",
        "primary", "secondary", "tertiary", "success", "warning", "danger", "info", "code_bg",
        "syntax_keyword", "syntax_type", "syntax_string", "syntax_number", "syntax_comment", "syntax_function",
        "syntax_annotation", "syntax_punctuation"
    )

    // ---------------------------------------------------------------- deriving dependent colors

    private val White = Color.White
    private val Black = Color(0xFF111111)

    /** Text color for a fill: whichever of near-black and white reads better on it. */
    fun on(c: Color): Color = if (contrast(c, Black) >= contrast(c, White)) Black else White

    private class Rule(val key: String, val sources: List<String>, val compute: (Map<String, Long>, Boolean) -> Color)

    private fun Map<String, Long>.c(key: String) = Color(getValue(key).toInt())

    private val rules = listOf(
        Rule("on_primary", listOf("primary")) { m, _ -> on(m.c("primary")) },
        Rule("primary_container", listOf("primary", "surface")) { m, d -> lerp(m.c("surface"), m.c("primary"), if (d) 0.30f else 0.18f) },
        Rule("on_primary_container", listOf("primary")) { m, d -> lerp(m.c("primary"), if (d) White else Black, if (d) 0.35f else 0.45f) },
        Rule("inverse_primary", listOf("primary")) { m, _ -> m.c("primary") },
        Rule("on_secondary", listOf("secondary")) { m, _ -> on(m.c("secondary")) },
        Rule("secondary_container", listOf("secondary", "surface")) { m, d -> lerp(m.c("surface"), m.c("secondary"), if (d) 0.30f else 0.18f) },
        Rule("on_secondary_container", listOf("secondary")) { m, d -> lerp(m.c("secondary"), if (d) White else Black, if (d) 0.35f else 0.45f) },
        Rule("on_tertiary", listOf("tertiary")) { m, _ -> on(m.c("tertiary")) },
        Rule("tertiary_container", listOf("tertiary", "surface")) { m, d -> lerp(m.c("surface"), m.c("tertiary"), if (d) 0.30f else 0.18f) },
        Rule("on_tertiary_container", listOf("tertiary")) { m, d -> lerp(m.c("tertiary"), if (d) White else Black, if (d) 0.35f else 0.45f) },
        Rule("on_surface", listOf("on_background")) { m, _ -> m.c("on_background") },
        Rule("text_secondary", listOf("on_surface_variant")) { m, _ -> m.c("on_surface_variant") },
        Rule("outline", listOf("on_surface_variant", "background")) { m, _ -> lerp(m.c("on_surface_variant"), m.c("background"), 0.25f) },
        Rule("outline_variant", listOf("on_background", "background")) { m, d -> lerp(m.c("background"), m.c("on_background"), if (d) 0.18f else 0.14f) },
        Rule("surface_container_lowest", listOf("background")) { m, d -> lerp(m.c("background"), if (d) Color.Black else White, if (d) 0.3f else 0.6f) },
        Rule("surface_container_low", listOf("background", "card")) { m, _ -> lerp(m.c("background"), m.c("card"), 0.5f) },
        Rule("surface_container", listOf("card")) { m, _ -> m.c("card") },
        Rule("surface_container_high", listOf("card", "on_background")) { m, d -> lerp(m.c("card"), m.c("on_background"), if (d) 0.06f else 0.04f) },
        Rule("surface_container_highest", listOf("card", "on_background")) { m, d -> lerp(m.c("card"), m.c("on_background"), if (d) 0.10f else 0.07f) },
        Rule("inverse_surface", listOf("on_background")) { m, _ -> m.c("on_background") },
        Rule("inverse_on_surface", listOf("background")) { m, _ -> m.c("background") },
        Rule("error", listOf("danger")) { m, _ -> m.c("danger") },
        Rule("on_error", listOf("danger")) { m, _ -> on(m.c("danger")) },
        Rule("error_container", listOf("danger", "surface")) { m, d -> lerp(m.c("surface"), m.c("danger"), if (d) 0.28f else 0.15f) },
        Rule("on_error_container", listOf("danger")) { m, d -> lerp(m.c("danger"), if (d) White else Black, if (d) 0.3f else 0.4f) },
        Rule("user_bubble", listOf("surface_variant")) { m, _ -> m.c("surface_variant") },
        Rule("thinking_bg", listOf("surface_variant", "background")) { m, _ -> lerp(m.c("surface_variant"), m.c("background"), 0.45f) },
        Rule("code_text", listOf("code_bg")) { m, _ -> if (m.c("code_bg").luminance() < 0.5f) Color(0xFFE6E6E6) else Color(0xFF1F2328) },
        Rule("accent_alt", listOf("tertiary")) { m, _ -> m.c("tertiary") },
        Rule("syntax_control", listOf("syntax_keyword")) { m, _ -> m.c("syntax_keyword") },
        Rule("syntax_tag", listOf("syntax_function")) { m, _ -> m.c("syntax_function") },
        Rule("syntax_attribute", listOf("syntax_type")) { m, _ -> m.c("syntax_type") },
        Rule("border", listOf("card", "on_background")) { m, d -> lerp(m.c("card"), m.c("on_background"), if (d) 0.14f else 0.11f) },
        Rule("fill", listOf("card", "on_background")) { m, d -> lerp(m.c("card"), m.c("on_background"), if (d) 0.07f else 0.05f) },
        Rule("success_container", listOf("success", "surface")) { m, d -> lerp(m.c("surface"), m.c("success"), if (d) 0.18f else 0.12f) },
        Rule("warning_container", listOf("warning", "surface")) { m, d -> lerp(m.c("surface"), m.c("warning"), if (d) 0.18f else 0.12f) },
        Rule("danger_container", listOf("danger", "surface")) { m, d -> lerp(m.c("surface"), m.c("danger"), if (d) 0.18f else 0.12f) },
        Rule("info_container", listOf("info", "surface")) { m, d -> lerp(m.c("surface"), m.c("info"), if (d) 0.18f else 0.12f) },
        Rule("accent_indigo", listOf("info", "accent_alt")) { m, _ -> lerp(m.c("info"), m.c("accent_alt"), 0.5f) },
        Rule("accent_teal", listOf("info", "success")) { m, _ -> lerp(m.c("info"), m.c("success"), 0.45f) },
        Rule("accent_pink", listOf("danger", "accent_alt")) { m, _ -> lerp(m.c("danger"), m.c("accent_alt"), 0.4f) },
        Rule("editor_bg", listOf("code_bg")) { m, _ -> m.c("code_bg") },
        Rule("editor_text", listOf("code_text")) { m, _ -> m.c("code_text") },
        Rule("editor_panel", listOf("editor_bg", "editor_text")) { m, _ -> lerp(m.c("editor_bg"), m.c("editor_text"), 0.05f) },
        Rule("editor_line", listOf("editor_bg", "editor_text")) { m, _ -> lerp(m.c("editor_bg"), m.c("editor_text"), 0.08f) },
        Rule("editor_border", listOf("editor_bg", "editor_text")) { m, _ -> lerp(m.c("editor_bg"), m.c("editor_text"), 0.15f) },
        Rule("editor_muted", listOf("editor_bg", "editor_text")) { m, _ -> lerp(m.c("editor_bg"), m.c("editor_text"), 0.5f) },
        Rule("editor_selection", listOf("editor_bg", "primary")) { m, _ -> lerp(m.c("editor_bg"), m.c("primary"), 0.32f) },
        Rule("editor_added_bg", listOf("editor_bg", "success")) { m, _ -> lerp(m.c("editor_bg"), m.c("success"), 0.22f) },
        Rule("editor_added_text", listOf("editor_text", "success")) { m, _ -> lerp(m.c("success"), m.c("editor_text"), 0.5f) },
        Rule("editor_removed_bg", listOf("editor_bg", "danger")) { m, _ -> lerp(m.c("editor_bg"), m.c("danger"), 0.22f) },
        Rule("editor_removed_text", listOf("editor_text", "danger")) { m, _ -> lerp(m.c("danger"), m.c("editor_text"), 0.5f) },
        Rule("editor_info_bg", listOf("editor_bg", "info")) { m, _ -> lerp(m.c("editor_bg"), m.c("info"), 0.18f) },
        Rule("editor_info_text", listOf("editor_text", "info")) { m, _ -> lerp(m.c("info"), m.c("editor_text"), 0.3f) },
        Rule("terminal_bg", listOf("code_bg")) { m, _ -> m.c("code_bg") },
        Rule("terminal_fg", listOf("code_text")) { m, _ -> m.c("code_text") },
        Rule("terminal_cursor", listOf("primary")) { m, _ -> m.c("primary") },
        Rule("ansi_0", listOf("terminal_bg", "terminal_fg")) { m, _ -> lerp(m.c("terminal_bg"), m.c("terminal_fg"), 0.18f) },
        Rule("ansi_1", listOf("danger")) { m, _ -> m.c("danger") },
        Rule("ansi_2", listOf("success")) { m, _ -> m.c("success") },
        Rule("ansi_3", listOf("warning")) { m, _ -> m.c("warning") },
        Rule("ansi_4", listOf("info")) { m, _ -> m.c("info") },
        Rule("ansi_5", listOf("accent_alt")) { m, _ -> m.c("accent_alt") },
        Rule("ansi_6", listOf("accent_teal")) { m, _ -> m.c("accent_teal") },
        Rule("ansi_7", listOf("terminal_bg", "terminal_fg")) { m, _ -> lerp(m.c("terminal_fg"), m.c("terminal_bg"), 0.15f) },
        Rule("ansi_8", listOf("terminal_bg", "terminal_fg")) { m, _ -> lerp(m.c("terminal_bg"), m.c("terminal_fg"), 0.45f) },
        Rule("ansi_9", listOf("ansi_1")) { m, _ -> lerp(m.c("ansi_1"), White, 0.25f) },
        Rule("ansi_10", listOf("ansi_2")) { m, _ -> lerp(m.c("ansi_2"), White, 0.25f) },
        Rule("ansi_11", listOf("ansi_3")) { m, _ -> lerp(m.c("ansi_3"), White, 0.25f) },
        Rule("ansi_12", listOf("ansi_4")) { m, _ -> lerp(m.c("ansi_4"), White, 0.25f) },
        Rule("ansi_13", listOf("ansi_5")) { m, _ -> lerp(m.c("ansi_5"), White, 0.25f) },
        Rule("ansi_14", listOf("ansi_6")) { m, _ -> lerp(m.c("ansi_6"), White, 0.25f) },
        Rule("ansi_15", listOf("terminal_fg")) { m, _ -> m.c("terminal_fg") }
    )

    /**
     * Works out again every color that depends on one of [changed] (and was not set explicitly in [changed] itself),
     * in an order where a worked-out color can feed the next one.
     */
    fun derive(map: MutableMap<String, Long>, changed: Set<String>, dark: Boolean) {
        val touched = changed.toMutableSet()
        for (r in rules) {
            if (r.key in changed) continue
            if (r.sources.none { it in touched }) continue
            if (r.sources.any { it !in map }) continue
            map[r.key] = r.compute(map, dark).toArgb().toLong() and 0xFFFFFFFFL
            touched += r.key
        }
    }

    /** The Material color scheme of a resolved theme: slots it does not set stay at Material's own defaults. */
    fun scheme(colors: Map<String, Long>, dark: Boolean): ColorScheme {
        val b = if (dark) darkColorScheme() else lightColorScheme()
        fun c(k: String, d: Color) = colors[k]?.let { Color(it.toInt()) } ?: d
        return b.copy(
            primary = c("primary", b.primary), onPrimary = c("on_primary", b.onPrimary),
            primaryContainer = c("primary_container", b.primaryContainer), onPrimaryContainer = c("on_primary_container", b.onPrimaryContainer),
            inversePrimary = c("inverse_primary", b.inversePrimary),
            secondary = c("secondary", b.secondary), onSecondary = c("on_secondary", b.onSecondary),
            secondaryContainer = c("secondary_container", b.secondaryContainer), onSecondaryContainer = c("on_secondary_container", b.onSecondaryContainer),
            tertiary = c("tertiary", b.tertiary), onTertiary = c("on_tertiary", b.onTertiary),
            tertiaryContainer = c("tertiary_container", b.tertiaryContainer), onTertiaryContainer = c("on_tertiary_container", b.onTertiaryContainer),
            background = c("background", b.background), onBackground = c("on_background", b.onBackground),
            surface = c("surface", b.surface), onSurface = c("on_surface", b.onSurface),
            surfaceVariant = c("surface_variant", b.surfaceVariant), onSurfaceVariant = c("on_surface_variant", b.onSurfaceVariant),
            surfaceTint = Color.Transparent,
            inverseSurface = c("inverse_surface", b.inverseSurface), inverseOnSurface = c("inverse_on_surface", b.inverseOnSurface),
            error = c("error", b.error), onError = c("on_error", b.onError),
            errorContainer = c("error_container", b.errorContainer), onErrorContainer = c("on_error_container", b.onErrorContainer),
            outline = c("outline", b.outline), outlineVariant = c("outline_variant", b.outlineVariant),
            surfaceContainerLowest = c("surface_container_lowest", b.surfaceContainerLowest),
            surfaceContainerLow = c("surface_container_low", b.surfaceContainerLow),
            surfaceContainer = c("surface_container", b.surfaceContainer),
            surfaceContainerHigh = c("surface_container_high", b.surfaceContainerHigh),
            surfaceContainerHighest = c("surface_container_highest", b.surfaceContainerHighest)
        )
    }

    /** WCAG contrast ratio of two colors (1 = none, 21 = black on white). */
    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** Readability problems of a resolved theme, in plain words (empty = fine). */
    fun check(colors: Map<String, Long>): List<String> {
        fun c(k: String) = colors[k]?.let { Color(it.toInt()) }
        val pairs = listOf(
            Triple("on_background", "background", 4.5f), Triple("on_surface", "surface", 4.5f),
            Triple("on_surface", "card", 4.5f), Triple("on_surface_variant", "surface", 3f),
            Triple("text_secondary", "background", 3f), Triple("on_primary", "primary", 3f),
            Triple("primary", "background", 2.5f), Triple("on_surface", "user_bubble", 4.5f),
            Triple("code_text", "code_bg", 4.5f), Triple("syntax_comment", "code_bg", 2.5f),
            Triple("danger", "background", 2.5f), Triple("success", "background", 2.5f),
            Triple("editor_text", "editor_bg", 4.5f), Triple("editor_muted", "editor_bg", 2.5f),
            Triple("editor_added_text", "editor_added_bg", 4f), Triple("editor_removed_text", "editor_removed_bg", 4f),
            Triple("terminal_fg", "terminal_bg", 4.5f), Triple("on_background", "fill", 4.5f)
        )
        return pairs.mapNotNull { (fg, bg, min) ->
            val f = c(fg) ?: return@mapNotNull null
            val b = c(bg) ?: return@mapNotNull null
            val r = contrast(f, b)
            if (r < min) "$fg on $bg has contrast ${"%.1f".format(r)} (needs at least $min)" else null
        }
    }

    fun hex(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

    /** "#RRGGBB", "#AARRGGBB", "RRGGBB" or "0xAARRGGBB" → ARGB, or null. */
    fun parse(text: String): Long? {
        val t = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (!t.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) return null
        val v = t.toLong(16)
        return if (t.length == 6) (0xFF000000L or v) else v
    }
}

/** The themes that come with the app. */
object BuiltInThemes {
    const val LIGHT_DEFAULT = "antigem_light"
    const val DARK_DEFAULT = "midnight_aurora"
    /** The original AntiGem dark look (no longer the default; the id stays so a saved choice still finds it). */
    private const val ANTIGEM_DARK = "antigem_dark"

    /** How many themes at the top of each side's list are marked as recommended (the first is the default). */
    const val RECOMMENDED_COUNT = 3

    private val oneDarkSyntax = listOf(0xFFC678DD, 0xFFE5C07B, 0xFF98C379, 0xFFD19A66, 0xFF7F848E, 0xFF61AFEF, 0xFFE5C07B, 0xFFABB2BF)
    private val syntaxKeys = listOf("syntax_keyword", "syntax_type", "syntax_string", "syntax_number", "syntax_comment", "syntax_function", "syntax_annotation", "syntax_punctuation")

    /** The original AntiGem look, exactly as before themes existed (other Material slots stay at Material's defaults). */
    private fun antigem(dark: Boolean) = AppTheme(
        id = if (dark) ANTIGEM_DARK else LIGHT_DEFAULT,
        name = if (dark) "Amber Night" else "Porcelain",
        dark = dark, builtIn = true,
        about = if (dark) "AntiGem's original warm dark look with the amber accent" else "AntiGem's original clean light look with the amber accent",
        colors = buildMap {
            put("primary", 0xFFD97706); put("secondary", 0xFFB45309); put("on_primary", 0xFFFFFFFF)
            if (dark) {
                put("background", 0xFF181816); put("surface", 0xFF181816); put("on_background", 0xFFF5F5F4); put("on_surface", 0xFFF5F5F4)
                put("surface_variant", 0xFF32312D)
                put("card", 0xFF232320); put("text_secondary", 0xFFA8A29E); put("user_bubble", 0xFF32312D); put("thinking_bg", 0xFF282724)
            } else {
                put("background", 0xFFF9F9FB); put("surface", 0xFFF9F9FB); put("on_background", 0xFF1C1917); put("on_surface", 0xFF1C1917)
                put("surface_variant", 0xFFE8EAED)
                put("card", 0xFFFFFFFF); put("text_secondary", 0xFF78716C); put("user_bubble", 0xFFE8EAED); put("thinking_bg", 0xFFF1F3F5)
            }
            put("code_bg", 0xFF1E1E1C); put("code_text", 0xFFF5F5F4)
            put("success", 0xFF16A34A); put("warning", 0xFFD97706); put("danger", 0xFFDC2626); put("info", 0xFF3B82F6); put("accent_alt", 0xFF8B5CF6)
            syntaxKeys.zip(oneDarkSyntax).forEach { (k, v) -> put(k, v) }
            put("syntax_control", 0xFFC678DD); put("syntax_tag", 0xFFE06C75); put("syntax_attribute", 0xFFD19A66)
            if (dark) {
                put("border", 0xFF34332F); put("fill", 0xFF2C2C2A)
                put("success_container", 0xFF143324); put("warning_container", 0xFF451A03); put("danger_container", 0xFF381919); put("info_container", 0xFF1E2A44)
            } else {
                put("border", 0xFFE5E7EB); put("fill", 0xFFF3F4F6)
                put("success_container", 0xFFE8F5E9); put("warning_container", 0xFFFFF3E0); put("danger_container", 0xFFFEF2F2); put("info_container", 0xFFE3F2FD)
            }
            put("accent_indigo", 0xFF6366F1); put("accent_teal", 0xFF00ACC1); put("accent_pink", 0xFFEC4899)
            // the IDE's VS Code-style editor and the black terminal, as before
            put("editor_bg", 0xFF1E1E1E); put("editor_panel", 0xFF252526); put("editor_text", 0xFFD4D4D4); put("editor_muted", 0xFF888888)
            put("editor_border", 0xFF3C3C3C); put("editor_selection", 0xFF264F78); put("editor_line", 0xFF2D2D2D)
            put("editor_added_bg", 0xFF1B4728); put("editor_added_text", 0xFFB9F6CA); put("editor_removed_bg", 0xFF4B1E1E); put("editor_removed_text", 0xFFFFCDD2)
            put("editor_info_bg", 0xFF1E293B); put("editor_info_text", 0xFF90CAF9)
            put("terminal_bg", 0xFF000000); put("terminal_fg", 0xFFFFFFFF); put("terminal_cursor", 0xFFFFFFFF)
            listOf(0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF6495ED, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
                0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF).forEachIndexed { i, v -> put("ansi_$i", v) }
        }
    )

    /** The default dark theme: every color set by hand (first made as a custom theme in the app, then exported). */
    private fun midnightAurora() = AppTheme(
        id = DARK_DEFAULT, name = "Midnight Aurora", dark = true, builtIn = true,
        about = "Deep ink-blue midnight with luminous violet, aurora teal and rose accents",
        colors = linkedMapOf(
            "primary" to 0xFFA78BFA, "on_primary" to 0xFF14102B, "primary_container" to 0xFF373256, "on_primary_container" to 0xFFC4B5FE,
            "secondary" to 0xFF7C6AE6, "on_secondary" to 0xFF111111, "secondary_container" to 0xFF2C2A52, "on_secondary_container" to 0xFFA6A0F2,
            "tertiary" to 0xFF5EEAD4, "on_tertiary" to 0xFF111111, "tertiary_container" to 0xFF28494D, "on_tertiary_container" to 0xFFA2F2E3,
            "background" to 0xFF0A0B14, "on_background" to 0xFFE8EAF6, "surface" to 0xFF0F111C, "on_surface" to 0xFFE8EAF6,
            "surface_variant" to 0xFF1C2033, "on_surface_variant" to 0xFF9AA1BF, "surface_container_lowest" to 0xFF030408,
            "surface_container_low" to 0xFF0F111D, "surface_container" to 0xFF151826, "surface_container_high" to 0xFF1F2231,
            "surface_container_highest" to 0xFF262938, "outline" to 0xFF3A4066, "outline_variant" to 0xFF23283F, "error" to 0xFFFB7185,
            "on_error" to 0xFF111111, "error_container" to 0xFF492B38, "on_error_container" to 0xFFFF9EA9, "inverse_surface" to 0xFFE8EAF6,
            "inverse_on_surface" to 0xFF0A0B14, "inverse_primary" to 0xFFA78BFA, "card" to 0xFF151826, "text_secondary" to 0xFF9AA1BF,
            "user_bubble" to 0xFF252A47, "thinking_bg" to 0xFF12152A, "code_bg" to 0xFF0C0E19, "code_text" to 0xFFD6DBF5, "success" to 0xFF4ADE80,
            "warning" to 0xFFFBBF24, "danger" to 0xFFFB7185, "info" to 0xFF60A5FA, "accent_alt" to 0xFFC084FC, "syntax_keyword" to 0xFFC4A7FF,
            "syntax_type" to 0xFF5EEAD4, "syntax_string" to 0xFF9AE6B4, "syntax_number" to 0xFFFFB86B, "syntax_comment" to 0xFF5B6285,
            "syntax_function" to 0xFF7DB5FF, "syntax_annotation" to 0xFFF4A9D8, "syntax_punctuation" to 0xFF8991B3, "syntax_control" to 0xFFC4A7FF,
            "syntax_tag" to 0xFF7DB5FF, "syntax_attribute" to 0xFF5EEAD4, "border" to 0xFF2D313F, "fill" to 0xFF212432,
            "success_container" to 0xFF1C302E, "warning_container" to 0xFF322D27, "danger_container" to 0xFF34222E, "info_container" to 0xFF1D283E,
            "accent_indigo" to 0xFF818CF8, "accent_teal" to 0xFF2DD4BF, "accent_pink" to 0xFFF472B6, "editor_bg" to 0xFF0C0E19,
            "editor_panel" to 0xFF141622, "editor_text" to 0xFFD6DBF5, "editor_muted" to 0xFF676B7E, "editor_border" to 0xFF252735,
            "editor_selection" to 0xFF373258, "editor_line" to 0xFF191B27, "editor_added_bg" to 0xFF1C3530, "editor_added_text" to 0xFF9CDEBD,
            "editor_removed_bg" to 0xFF39232F, "editor_removed_text" to 0xFFEFAABD, "editor_info_bg" to 0xFF1A253B, "editor_info_text" to 0xFF86B6FA,
            "terminal_bg" to 0xFF0C0E19, "terminal_fg" to 0xFFD6DBF5, "terminal_cursor" to 0xFFA78BFA, "ansi_0" to 0xFF2A2D3A,
            "ansi_1" to 0xFFFB7185, "ansi_2" to 0xFF4ADE80, "ansi_3" to 0xFFFBBF24, "ansi_4" to 0xFF60A5FA, "ansi_5" to 0xFFC084FC,
            "ansi_6" to 0xFF2DD4BF, "ansi_7" to 0xFFB3B8D0, "ansi_8" to 0xFF5D6173, "ansi_9" to 0xFFFF97A3, "ansi_10" to 0xFF85E7A1,
            "ansi_11" to 0xFFFDD072, "ansi_12" to 0xFF89BCFD, "ansi_13" to 0xFFCFA4FF, "ansi_14" to 0xFF79DFCF, "ansi_15" to 0xFFD6DBF5
        )
    )

    /** A full theme from a few colors; everything else is worked out ([ThemeTokens.derive]). */
    private fun seeded(
        id: String, name: String, about: String, dark: Boolean,
        bg: Long, surface: Long, card: Long, variant: Long, text: Long, muted: Long,
        primary: Long, secondary: Long, tertiary: Long, success: Long, warning: Long, danger: Long, info: Long,
        codeBg: Long, syntax: List<Long>
    ): AppTheme {
        val m = mutableMapOf(
            "background" to bg, "surface" to surface, "card" to card, "surface_variant" to variant,
            "on_background" to text, "on_surface_variant" to muted, "primary" to primary, "secondary" to secondary,
            "tertiary" to tertiary, "success" to success, "warning" to warning, "danger" to danger, "info" to info, "code_bg" to codeBg
        )
        syntaxKeys.zip(syntax).forEach { (k, v) -> m[k] = v }
        ThemeTokens.derive(m, m.keys.toSet(), dark)
        return AppTheme(id = id, name = name, dark = dark, about = about, builtIn = true, colors = m)
    }

    val all: List<AppTheme> by lazy {
        listOf(
            // light, best first: the first is the default and the first RECOMMENDED_COUNT are marked as recommended
            antigem(false),
            seeded("lavender", "Lavender", "Soft lilac with a violet accent", false,
                0xFFF8F6FD, 0xFFF8F6FD, 0xFFFFFFFF, 0xFFECE7F8, 0xFF241B3A, 0xFF655B7F,
                0xFF7C3AED, 0xFFA855F7, 0xFFDB2777, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFF2563EB,
                0xFF1E1830, listOf(0xFFD8B4FE, 0xFFFDE68A, 0xFFA7F3D0, 0xFFFDBA74, 0xFF7E7396, 0xFF93C5FD, 0xFFFDE68A, 0xFFE4DDF5)),
            seeded("sky", "Sky", "Crisp, airy light blue", false,
                0xFFF5F8FC, 0xFFF5F8FC, 0xFFFFFFFF, 0xFFE3EBF5, 0xFF0F1B2D, 0xFF52637A,
                0xFF2563EB, 0xFF0EA5E9, 0xFF7C3AED, 0xFF16A34A, 0xFFB45309, 0xFFDC2626, 0xFF0284C7,
                0xFF0F172A, listOf(0xFFC792EA, 0xFFFFCB6B, 0xFFA3E4A1, 0xFFF9A87A, 0xFF64748B, 0xFF82AAFF, 0xFFFFCB6B, 0xFFCBD5E1)),
            seeded("sakura", "Sakura", "Soft cherry-blossom pink with a magenta accent", false,
                0xFFFFF7F9, 0xFFFFF7F9, 0xFFFFFFFF, 0xFFFBE4EA, 0xFF3B1F2B, 0xFF7F5A68,
                0xFFDB2777, 0xFFC026D3, 0xFF7C3AED, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFF2563EB,
                0xFF2A1520, listOf(0xFFF472B6, 0xFFFCD34D, 0xFF86EFAC, 0xFFFDBA74, 0xFF9C7B88, 0xFF93C5FD, 0xFFFCD34D, 0xFFF3E8EE)),
            seeded("mint", "Mint", "Fresh, clean light green", false,
                0xFFF4FBF8, 0xFFF4FBF8, 0xFFFFFFFF, 0xFFDDF2EA, 0xFF10231B, 0xFF4B6858,
                0xFF059669, 0xFF0D9488, 0xFF7C3AED, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFF0369A1,
                0xFF0F1E19, listOf(0xFFC4B5FD, 0xFFFDE68A, 0xFFA7F3D0, 0xFFFDBA74, 0xFF6B8A7A, 0xFF7DD3FC, 0xFFFDE68A, 0xFFD1E7DD)),
            seeded("linen", "Linen", "Warm minimal off-white with a burnt-orange accent", false,
                0xFFFBF8F3, 0xFFFBF8F3, 0xFFFFFFFF, 0xFFF0EBE3, 0xFF26211C, 0xFF6F6861,
                0xFFC2410C, 0xFFEA580C, 0xFF7C3AED, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFF1D4ED8,
                0xFF1F1B18, listOf(0xFFE4A7F0, 0xFFF2CC8F, 0xFFB7D99A, 0xFFF5A97F, 0xFF8A8078, 0xFF8FC2F0, 0xFFF2CC8F, 0xFFE6DED5)),
            seeded("paper", "Paper", "Warm sepia paper with a rust accent, easy on the eyes", false,
                0xFFF7F1E3, 0xFFF7F1E3, 0xFFFFFAEF, 0xFFECE3CE, 0xFF2B2418, 0xFF6E6250,
                0xFF9A3412, 0xFFB45309, 0xFF4D7C0F, 0xFF4D7C0F, 0xFFB45309, 0xFFB91C1C, 0xFF1D4ED8,
                0xFF2B2418, listOf(0xFFF0A6CA, 0xFFF2D58A, 0xFFB5D49A, 0xFFF4B183, 0xFF8C7F67, 0xFF9CC3E6, 0xFFF2D58A, 0xFFE8DFCC)),
            seeded("solarized_light", "Solarized Light", "The classic low-glare cream palette", false,
                0xFFFDF6E3, 0xFFFDF6E3, 0xFFFFFBEF, 0xFFEEE8D5, 0xFF073642, 0xFF586E75,
                0xFF268BD2, 0xFF2AA198, 0xFF6C71C4, 0xFF859900, 0xFFB58900, 0xFFDC322F, 0xFF268BD2,
                0xFF002B36, listOf(0xFF859900, 0xFFB58900, 0xFF2AA198, 0xFFD33682, 0xFF657B83, 0xFF268BD2, 0xFFCB4B16, 0xFF93A1A1)),
            // dark: the same
            midnightAurora(),
            seeded("obsidian", "Obsidian", "Refined deep black with layered surfaces and a soft periwinkle glow", true,
                0xFF0D0E12, 0xFF0D0E12, 0xFF16181D, 0xFF1F2229, 0xFFECEDF0, 0xFF9297A3,
                0xFF8B9CFF, 0xFF6E7FF3, 0xFFC4A1FF, 0xFF4ADE80, 0xFFF5B454, 0xFFF87171, 0xFF60A5FA,
                0xFF111318, listOf(0xFFC4A1FF, 0xFF7DD3FC, 0xFF9FE2B0, 0xFFF9B98A, 0xFF5E6573, 0xFF8BB4FF, 0xFFF5D08A, 0xFFB8BDC8)),
            seeded("graphite", "Graphite", "Warm charcoal with a glowing amber accent: AntiGem's look, refined", true,
                0xFF121212, 0xFF121212, 0xFF1B1B1C, 0xFF262628, 0xFFEDEBE8, 0xFFA29E98,
                0xFFF0A04B, 0xFFD9822B, 0xFFB794F4, 0xFF5BD18A, 0xFFF4C152, 0xFFEF6B6B, 0xFF6CB4F2,
                0xFF161616, listOf(0xFFC678DD, 0xFFE5C07B, 0xFF98C379, 0xFFD19A66, 0xFF6B717D, 0xFF61AFEF, 0xFFE5C07B, 0xFFABB2BF)),
            seeded("midnight", "Midnight", "Pure black for OLED screens, with an electric cyan accent", true,
                0xFF000000, 0xFF000000, 0xFF0F1115, 0xFF1A1D24, 0xFFECEFF4, 0xFF9AA3B2,
                0xFF22D3EE, 0xFF0891B2, 0xFFA78BFA, 0xFF22C55E, 0xFFF59E0B, 0xFFF43F5E, 0xFF60A5FA,
                0xFF0A0C10, listOf(0xFFC792EA, 0xFFFFCB6B, 0xFFC3E88D, 0xFFF78C6C, 0xFF5C6370, 0xFF82AAFF, 0xFFFFCB6B, 0xFFA6ACCD)),
            seeded("high_contrast", "High Contrast", "Maximum readability: white on black with bold yellow", true,
                0xFF000000, 0xFF000000, 0xFF0D0D0D, 0xFF1F1F1F, 0xFFFFFFFF, 0xFFD4D4D4,
                0xFFFFD60A, 0xFF00E5FF, 0xFFFF6EFF, 0xFF00E676, 0xFFFFC400, 0xFFFF5252, 0xFF40C4FF,
                0xFF000000, listOf(0xFFFF6EFF, 0xFFFFD60A, 0xFF00E676, 0xFFFF9E40, 0xFFBDBDBD, 0xFF40C4FF, 0xFFFFD60A, 0xFFFFFFFF)),
            antigem(true),
            seeded("mocha", "Mocha", "Cozy pastel dark: lavender, pink and sky on deep cocoa-blue", true,
                0xFF1E1E2E, 0xFF1E1E2E, 0xFF252536, 0xFF313244, 0xFFCDD6F4, 0xFFA6ADC8,
                0xFFCBA6F7, 0xFFF5C2E7, 0xFF89B4FA, 0xFFA6E3A1, 0xFFF9E2AF, 0xFFF38BA8, 0xFF89DCEB,
                0xFF181825, listOf(0xFFCBA6F7, 0xFFF9E2AF, 0xFFA6E3A1, 0xFFFAB387, 0xFF7F849C, 0xFF89B4FA, 0xFFF5C2E7, 0xFFBAC2DE)),
            seeded("neon_city", "Neon City", "Night-time city blues with electric violet", true,
                0xFF1A1B26, 0xFF1A1B26, 0xFF1F2335, 0xFF292E42, 0xFFC0CAF5, 0xFF8B93B8,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFF9ECE6A, 0xFFE0AF68, 0xFFF7768E, 0xFF7DCFFF,
                0xFF16161E, listOf(0xFFBB9AF7, 0xFF2AC3DE, 0xFF9ECE6A, 0xFFFF9E64, 0xFF6A72A0, 0xFF7AA2F7, 0xFFE0AF68, 0xFFA9B1D6)),
            seeded("aurora", "Aurora", "Northern-lights teal and violet over a deep night sky", true,
                0xFF0B1118, 0xFF0B1118, 0xFF121A23, 0xFF1A2530, 0xFFE3ECF2, 0xFF8DA2B3,
                0xFF2DD4BF, 0xFF34D399, 0xFFA78BFA, 0xFF4ADE80, 0xFFFBBF24, 0xFFFB7185, 0xFF67E8F9,
                0xFF0A1016, listOf(0xFFA78BFA, 0xFFFDE68A, 0xFF86EFAC, 0xFFFDBA74, 0xFF5B6F80, 0xFF67E8F9, 0xFFFDE68A, 0xFFCBD9E3)),
            seeded("nord", "Nord", "Calm arctic blue-grey", true,
                0xFF2E3440, 0xFF2E3440, 0xFF3B4252, 0xFF434C5E, 0xFFECEFF4, 0xFFA3ADC2,
                0xFF88C0D0, 0xFF81A1C1, 0xFFB48EAD, 0xFFA3BE8C, 0xFFEBCB8B, 0xFFBF616A, 0xFF5E81AC,
                0xFF272C36, listOf(0xFF81A1C1, 0xFF8FBCBB, 0xFFA3BE8C, 0xFFB48EAD, 0xFF6E7A94, 0xFF88C0D0, 0xFFD08770, 0xFFECEFF4)),
            seeded("dracula", "Dracula", "Dark purple with vivid pink, green and cyan", true,
                0xFF282A36, 0xFF282A36, 0xFF303241, 0xFF44475A, 0xFFF8F8F2, 0xFFA9ADCB,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFF50FA7B, 0xFFFFB86C, 0xFFFF5555, 0xFF8BE9FD,
                0xFF21222C, listOf(0xFFFF79C6, 0xFF8BE9FD, 0xFFF1FA8C, 0xFFBD93F9, 0xFF7282B5, 0xFF50FA7B, 0xFFFFB86C, 0xFFF8F8F2)),
            seeded("velvet", "Velvet", "Dusky rose and soft violet on deep plum", true,
                0xFF191724, 0xFF191724, 0xFF1F1D2E, 0xFF26233A, 0xFFE0DEF4, 0xFF9A96B5,
                0xFFEBBCBA, 0xFFC4A7E7, 0xFF9CCFD8, 0xFF95D5A7, 0xFFF6C177, 0xFFEB6F92, 0xFF9CCFD8,
                0xFF16141F, listOf(0xFFC4A7E7, 0xFF9CCFD8, 0xFFF6C177, 0xFFEBBCBA, 0xFF6E6A86, 0xFFEA9A97, 0xFFF6C177, 0xFFBDB9D6)),
            seeded("ocean", "Deep Ocean", "Deep navy with a sky-blue accent", true,
                0xFF0B1426, 0xFF0B1426, 0xFF111D33, 0xFF1A2942, 0xFFE2E8F0, 0xFF94A3B8,
                0xFF38BDF8, 0xFF6366F1, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24, 0xFFFB7185, 0xFF38BDF8,
                0xFF070E1C, listOf(0xFFC084FC, 0xFFFCD34D, 0xFF86EFAC, 0xFFFDBA74, 0xFF64748B, 0xFF7DD3FC, 0xFFFCD34D, 0xFFCBD5E1)),
            seeded("ember", "Ember", "Retro warm dark: toasted browns with orange and gold", true,
                0xFF1D2021, 0xFF1D2021, 0xFF282828, 0xFF32302F, 0xFFEBDBB2, 0xFFA89984,
                0xFFFE8019, 0xFFFABD2F, 0xFFD3869B, 0xFFB8BB26, 0xFFFABD2F, 0xFFFB4934, 0xFF83A598,
                0xFF1A1C1D, listOf(0xFFFB4934, 0xFFFABD2F, 0xFFB8BB26, 0xFFD3869B, 0xFF928374, 0xFF8EC07C, 0xFFFE8019, 0xFFEBDBB2)),
            seeded("forest", "Forest", "Dark pine green with a fresh leaf accent", true,
                0xFF0F1A14, 0xFF0F1A14, 0xFF16241C, 0xFF1E3127, 0xFFE6F0E9, 0xFF9DB5A6,
                0xFF4ADE80, 0xFF2DD4BF, 0xFFFACC15, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFF60A5FA,
                0xFF0A120E, listOf(0xFFC4B5FD, 0xFFFDE68A, 0xFFA7F3D0, 0xFFFDBA74, 0xFF6A8777, 0xFF7DD3FC, 0xFFFDE68A, 0xFFCBD5D1)),
            seeded("sunset", "Sunset", "Warm plum dusk with an orange glow", true,
                0xFF1C1020, 0xFF1C1020, 0xFF26162B, 0xFF342036, 0xFFF6E7EF, 0xFFC2A3B4,
                0xFFFB923C, 0xFFF472B6, 0xFFC084FC, 0xFF4ADE80, 0xFFFBBF24, 0xFFF43F5E, 0xFF818CF8,
                0xFF140A17, listOf(0xFFF472B6, 0xFFFDE047, 0xFFBEF264, 0xFFFB923C, 0xFF8A7083, 0xFF93C5FD, 0xFFFDE047, 0xFFE5D4DE)),
            seeded("solarized_dark", "Solarized Dark", "The classic low-glare teal-black palette", true,
                0xFF002B36, 0xFF002B36, 0xFF073642, 0xFF0B4452, 0xFFEEE8D5, 0xFF93A1A1,
                0xFF268BD2, 0xFF2AA198, 0xFF6C71C4, 0xFF859900, 0xFFB58900, 0xFFDC322F, 0xFF2AA198,
                0xFF00212B, listOf(0xFF859900, 0xFFB58900, 0xFF2AA198, 0xFFD33682, 0xFF6C8288, 0xFF268BD2, 0xFFCB4B16, 0xFF93A1A1))
        )
    }

    fun default(dark: Boolean): AppTheme = all.first { it.id == if (dark) DARK_DEFAULT else LIGHT_DEFAULT }

    /**
     * A built-in theme's place among the recommended ones of its side: 0 = the default (also the top pick),
     * 1 and 2 = the next recommended ones; null when it is not one of them (or a custom theme).
     */
    fun recommendedRank(theme: AppTheme): Int? {
        if (!theme.builtIn) return null
        val i = all.filter { it.dark == theme.dark }.indexOfFirst { it.id == theme.id }
        return i.takeIf { it in 0 until RECOMMENDED_COUNT }
    }
}
