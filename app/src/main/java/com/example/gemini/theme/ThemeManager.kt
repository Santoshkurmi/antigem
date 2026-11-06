package com.example.gemini.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The one place the app's look comes from. It holds the built-in and custom themes, which one is used in light mode
 * and which in dark mode, and the colors of the theme on screen right now ([colors], read by [AppColors] and the old
 * palette names in Color.kt, so every screen follows a theme change at once).
 *
 * Light / Dark / Auto is still the app's theme mode setting (Settings → Appearance); this decides which light theme
 * and which dark theme those mean. Everything here is Compose snapshot state: reading it in the UI recomposes on change.
 */
object ThemeManager {
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var customThemes by mutableStateOf<List<AppTheme>>(emptyList())
        private set
    var lightId by mutableStateOf(BuiltInThemes.LIGHT_DEFAULT)
        private set
    var darkId by mutableStateOf(BuiltInThemes.DARK_DEFAULT)
        private set
    /** Whether the screen shows the dark side right now (set by GeminiTheme). */
    var isDark by mutableStateOf(false)
        private set

    private class Resolved(val theme: AppTheme, val map: Map<String, Long>, val scheme: ColorScheme, val colors: ThemeColors)
    // by identity: built-in themes never change, an edited custom theme is a new object
    private val cache = java.util.IdentityHashMap<AppTheme, Resolved>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences("app_themes", Context.MODE_PRIVATE)
        prefs = p
        customThemes = runCatching { parseList(p.getString("custom", null)) }.getOrDefault(emptyList())
        lightId = p.getString("light_id", null) ?: BuiltInThemes.LIGHT_DEFAULT
        darkId = p.getString("dark_id", null) ?: BuiltInThemes.DARK_DEFAULT
        // the mode decides which side shows before the first frame (the UI corrects it if the system says otherwise)
        isDark = p.getString("last_dark", null) == "1"
    }

    // ---------------------------------------------------------------- reading

    fun all(): List<AppTheme> = BuiltInThemes.all + customThemes

    /** By id, or by name (any case, also part of a name). */
    fun find(idOrName: String?): AppTheme? {
        val q = idOrName?.trim().orEmpty()
        if (q.isEmpty()) return null
        val list = all()
        val norm = q.lowercase().replace(' ', '_')
        return list.firstOrNull { it.id == q } ?: list.firstOrNull { it.id == norm }
            ?: list.firstOrNull { it.name.equals(q, true) }
            ?: list.firstOrNull { it.name.lowercase().contains(q.lowercase()) }
    }

    /** The theme used for the light or the dark side. */
    fun themeFor(dark: Boolean): AppTheme =
        find(if (dark) darkId else lightId)?.takeIf { it.dark == dark } ?: BuiltInThemes.default(dark)

    val current: AppTheme get() = themeFor(isDark)

    @Volatile private var cachedColors: ThemeColors? = null
    @Volatile private var cachedId: String? = null
    @Volatile private var cachedDark = false
    @Volatile private var cachedList: List<AppTheme>? = null

    /**
     * Colors of the theme on screen now. Works before [init] too (then the default themes). Read very often (every
     * palette color of every screen), so it only resolves again when the theme, the side or the custom themes change.
     */
    val colors: ThemeColors get() {
        val dark = isDark
        val id = if (dark) darkId else lightId
        val list = customThemes
        val c = cachedColors
        if (c != null && cachedId == id && cachedDark == dark && cachedList === list) return c
        val fresh = resolve(themeFor(dark)).colors
        cachedColors = fresh; cachedId = id; cachedDark = dark; cachedList = list
        return fresh
    }

    fun schemeOf(theme: AppTheme): ColorScheme = resolve(theme).scheme

    /** Every token of [theme] as ARGB (Material slots it leaves to Material's defaults included). */
    fun resolvedColors(theme: AppTheme): Map<String, Long> {
        val r = resolve(theme)
        val out = LinkedHashMap<String, Long>()
        val s = r.scheme
        fun put(k: String, c: Color) { out[k] = argb(c) }
        put("primary", s.primary); put("on_primary", s.onPrimary); put("primary_container", s.primaryContainer); put("on_primary_container", s.onPrimaryContainer)
        put("secondary", s.secondary); put("on_secondary", s.onSecondary); put("secondary_container", s.secondaryContainer); put("on_secondary_container", s.onSecondaryContainer)
        put("tertiary", s.tertiary); put("on_tertiary", s.onTertiary); put("tertiary_container", s.tertiaryContainer); put("on_tertiary_container", s.onTertiaryContainer)
        put("background", s.background); put("on_background", s.onBackground); put("surface", s.surface); put("on_surface", s.onSurface)
        put("surface_variant", s.surfaceVariant); put("on_surface_variant", s.onSurfaceVariant)
        put("surface_container_lowest", s.surfaceContainerLowest); put("surface_container_low", s.surfaceContainerLow); put("surface_container", s.surfaceContainer)
        put("surface_container_high", s.surfaceContainerHigh); put("surface_container_highest", s.surfaceContainerHighest)
        put("outline", s.outline); put("outline_variant", s.outlineVariant)
        put("error", s.error); put("on_error", s.onError); put("error_container", s.errorContainer); put("on_error_container", s.onErrorContainer)
        put("inverse_surface", s.inverseSurface); put("inverse_on_surface", s.inverseOnSurface); put("inverse_primary", s.inversePrimary)
        ThemeTokens.app.forEach { t -> r.map[t.key]?.let { out[t.key] = it } }
        return out
    }

    private fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    @Synchronized
    private fun resolve(theme: AppTheme): Resolved {
        cache[theme]?.let { return it }
        val map = resolveMap(theme, 0)
        val scheme = ThemeTokens.scheme(map, theme.dark)
        val r = Resolved(theme, map, scheme, ThemeColors(scheme, map))
        if (cache.size > 40) cache.clear()
        cache[theme] = r
        return r
    }

    /** Built-in: its colors. Custom: the base's colors with its changes on top, dependent colors worked out again. */
    private fun resolveMap(theme: AppTheme, depth: Int): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        if (theme.builtIn || depth > 5) {
            m.putAll(theme.colors)
        } else {
            val base = find(theme.base)?.takeIf { it.id != theme.id } ?: BuiltInThemes.default(theme.dark)
            m.putAll(resolveMap(base, depth + 1))
            m.putAll(theme.colors)
            ThemeTokens.derive(m, theme.colors.keys, theme.dark)
        }
        // AntiGem's own colors always exist (from the default theme of the same side)
        if (theme.id != BuiltInThemes.default(theme.dark).id) {
            BuiltInThemes.default(theme.dark).colors.forEach { (k, v) -> if (!ThemeTokens.isMaterial(k)) m.putIfAbsent(k, v) }
        }
        return m
    }

    // ---------------------------------------------------------------- changing

    /** Called by GeminiTheme with the side the app shows. */
    fun setEffectiveDark(dark: Boolean) {
        if (isDark != dark) {
            isDark = dark
            prefs?.edit()?.putString("last_dark", if (dark) "1" else "0")?.apply()
        }
    }

    /**
     * Uses [theme] for its side (light or dark). When the app's mode would not show that side, the mode is switched
     * to it so the theme is visible right away. Returns the new mode ("LIGHT"/"DARK") when it changed, else null.
     */
    fun select(theme: AppTheme, switchMode: Boolean = true): String? {
        if (theme.dark) darkId = theme.id else lightId = theme.id
        prefs?.edit()?.putString(if (theme.dark) "dark_id" else "light_id", theme.id)?.apply()
        if (switchMode && isDark != theme.dark) {
            val mode = if (theme.dark) "DARK" else "LIGHT"
            setMode(mode)
            return mode
        }
        return null
    }

    /** The app's theme mode: "SYSTEM", "LIGHT" or "DARK" (the same setting as Settings → Appearance). */
    fun setMode(mode: String) {
        val ctx = appContext ?: return
        io.launch { runCatching { com.example.gemini.data.preferences.AuthPreferences(ctx).saveThemeMode(mode) } }
    }

    /**
     * Tells Android the app's own light / dark mode (Android 12+), so the startup splash and system dialogs match it
     * even when it differs from the phone's setting. Only calls the system when the mode really changed.
     */
    fun syncSystemNightMode(mode: String) {
        val ctx = appContext ?: return
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val want = when (mode) {
            "DARK" -> android.app.UiModeManager.MODE_NIGHT_YES
            "LIGHT" -> android.app.UiModeManager.MODE_NIGHT_NO
            else -> android.app.UiModeManager.MODE_NIGHT_AUTO
        }
        if (prefs?.getInt("app_night_mode", -1) == want) return
        runCatching {
            ctx.getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(want)
            prefs?.edit()?.putInt("app_night_mode", want)?.apply()
        }
    }

    fun mode(): String = appContext?.let { runCatching { com.example.gemini.data.preferences.AuthPreferences(it).getThemeModeSync() }.getOrNull() } ?: "SYSTEM"

    /** Adds or replaces a custom theme. */
    fun saveCustom(theme: AppTheme): AppTheme {
        val t = theme.copy(builtIn = false, id = theme.id.ifBlank { newId(theme.name) })
        customThemes = customThemes.filter { it.id != t.id } + t
        persist()
        return t
    }

    /** Removes a custom theme; a side that used it goes back to the default theme. */
    fun deleteCustom(id: String): Boolean {
        val t = customThemes.firstOrNull { it.id == id } ?: return false
        customThemes = customThemes.filter { it.id != id }
        if (lightId == id) { lightId = BuiltInThemes.LIGHT_DEFAULT; prefs?.edit()?.putString("light_id", lightId)?.apply() }
        if (darkId == id) { darkId = BuiltInThemes.DARK_DEFAULT; prefs?.edit()?.putString("dark_id", darkId)?.apply() }
        persist()
        return t.id == id
    }

    fun newId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "theme" }
        var id = "custom_$slug"
        if (all().any { it.id == id }) id = "${id}_${UUID.randomUUID().toString().take(4)}"
        return id
    }

    // ---------------------------------------------------------------- storage

    fun toJson(t: AppTheme): JSONObject = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("dark", t.dark); put("about", t.about)
        t.base?.let { put("base", it) }
        put("colors", JSONObject().apply { t.colors.forEach { (k, v) -> put(k, ThemeTokens.hex(v)) } })
    }

    fun fromJson(o: JSONObject): AppTheme? {
        val name = o.optString("name").trim().ifBlank { return null }
        val colors = LinkedHashMap<String, Long>()
        o.optJSONObject("colors")?.let { c -> c.keys().forEach { k -> if (k in ThemeTokens.keys) ThemeTokens.parse(c.optString(k))?.let { colors[k] = it } } }
        return AppTheme(
            id = o.optString("id").trim(), name = name.take(40), dark = o.optBoolean("dark"),
            about = o.optString("about").take(200), builtIn = false, base = o.optString("base").ifBlank { null }, colors = colors
        )
    }

    private fun parseList(raw: String?): List<AppTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) }?.takeIf { it.id.isNotBlank() } }
    }

    private fun persist() {
        val arr = JSONArray().apply { customThemes.forEach { put(toJson(it)) } }
        prefs?.edit()?.putString("custom", arr.toString())?.apply()
        synchronized(this) { cache.clear() }
        cachedColors = null
    }
}

/** The resolved colors of one theme, ready to use. */
class ThemeColors(scheme: ColorScheme, map: Map<String, Long>) {
    private fun c(map: Map<String, Long>, k: String, d: Color) = map[k]?.let { Color(it.toInt()) } ?: d

    val primary = scheme.primary
    val secondary = scheme.secondary
    val tertiary = scheme.tertiary
    val background = scheme.background
    val surface = scheme.surface
    val onBackground = scheme.onBackground
    val card = c(map, "card", scheme.surfaceContainer)
    val textSecondary = c(map, "text_secondary", scheme.onSurfaceVariant)
    val userBubble = c(map, "user_bubble", scheme.surfaceVariant)
    val thinkingBg = c(map, "thinking_bg", scheme.surfaceVariant)
    val codeBg = c(map, "code_bg", Color(0xFF1E1E1C))
    val codeText = c(map, "code_text", Color(0xFFF5F5F4))
    val success = c(map, "success", Color(0xFF16A34A))
    val warning = c(map, "warning", Color(0xFFD97706))
    val danger = c(map, "danger", Color(0xFFDC2626))
    val info = c(map, "info", Color(0xFF3B82F6))
    val accentAlt = c(map, "accent_alt", Color(0xFF8B5CF6))
    val syntaxKeyword = c(map, "syntax_keyword", Color(0xFFC678DD))
    val syntaxType = c(map, "syntax_type", Color(0xFFE5C07B))
    val syntaxString = c(map, "syntax_string", Color(0xFF98C379))
    val syntaxNumber = c(map, "syntax_number", Color(0xFFD19A66))
    val syntaxComment = c(map, "syntax_comment", Color(0xFF7F848E))
    val syntaxFunction = c(map, "syntax_function", Color(0xFF61AFEF))
    val syntaxAnnotation = c(map, "syntax_annotation", Color(0xFFE5C07B))
    val syntaxPunctuation = c(map, "syntax_punctuation", Color(0xFFABB2BF))
    val syntaxControl = c(map, "syntax_control", Color(0xFFC678DD))
    val syntaxTag = c(map, "syntax_tag", Color(0xFFE06C75))
    val syntaxAttribute = c(map, "syntax_attribute", Color(0xFFD19A66))
    val border = c(map, "border", Color(0xFFE5E7EB))
    val fill = c(map, "fill", Color(0xFFF3F4F6))
    val successContainer = c(map, "success_container", Color(0xFFE8F5E9))
    val warningContainer = c(map, "warning_container", Color(0xFFFFF3E0))
    val dangerContainer = c(map, "danger_container", Color(0xFFFEF2F2))
    val infoContainer = c(map, "info_container", Color(0xFFE3F2FD))
    val accentIndigo = c(map, "accent_indigo", Color(0xFF6366F1))
    val accentTeal = c(map, "accent_teal", Color(0xFF00ACC1))
    val accentPink = c(map, "accent_pink", Color(0xFFEC4899))
    val editorBg = c(map, "editor_bg", Color(0xFF1E1E1E))
    val editorPanel = c(map, "editor_panel", Color(0xFF252526))
    val editorText = c(map, "editor_text", Color(0xFFD4D4D4))
    val editorMuted = c(map, "editor_muted", Color(0xFF888888))
    val editorBorder = c(map, "editor_border", Color(0xFF3C3C3C))
    val editorSelection = c(map, "editor_selection", Color(0xFF264F78))
    val editorLine = c(map, "editor_line", Color(0xFF2D2D2D))
    val editorAddedBg = c(map, "editor_added_bg", Color(0xFF1B4728))
    val editorAddedText = c(map, "editor_added_text", Color(0xFFB9F6CA))
    val editorRemovedBg = c(map, "editor_removed_bg", Color(0xFF4B1E1E))
    val editorRemovedText = c(map, "editor_removed_text", Color(0xFFFFCDD2))
    val editorInfoBg = c(map, "editor_info_bg", Color(0xFF1E293B))
    val editorInfoText = c(map, "editor_info_text", Color(0xFF90CAF9))
    val terminalBg = c(map, "terminal_bg", Color(0xFF000000))
    val terminalFg = c(map, "terminal_fg", Color(0xFFFFFFFF))
    val terminalCursor = c(map, "terminal_cursor", Color(0xFFFFFFFF))
    val inverseSurface = scheme.inverseSurface
    val inverseOnSurface = scheme.inverseOnSurface
    /** Terminal ANSI palette 0-15 (ARGB). */
    val ansi: IntArray = IntArray(16) { i -> (map["ansi_$i"] ?: 0xFF808080L).toInt() }
}

/**
 * The colors of the theme on screen, by role. Use these instead of fixed colors: they follow every theme change.
 * (Reading them during composition or drawing subscribes to changes, like any Compose state.)
 */
object AppColors {
    val primary: Color get() = ThemeManager.colors.primary
    val secondary: Color get() = ThemeManager.colors.secondary
    val tertiary: Color get() = ThemeManager.colors.tertiary
    val background: Color get() = ThemeManager.colors.background
    val surface: Color get() = ThemeManager.colors.surface
    val onBackground: Color get() = ThemeManager.colors.onBackground
    val card: Color get() = ThemeManager.colors.card
    val textSecondary: Color get() = ThemeManager.colors.textSecondary
    val userBubble: Color get() = ThemeManager.colors.userBubble
    val thinkingBg: Color get() = ThemeManager.colors.thinkingBg
    val codeBg: Color get() = ThemeManager.colors.codeBg
    val codeText: Color get() = ThemeManager.colors.codeText
    val success: Color get() = ThemeManager.colors.success
    val warning: Color get() = ThemeManager.colors.warning
    val danger: Color get() = ThemeManager.colors.danger
    val info: Color get() = ThemeManager.colors.info
    val accentAlt: Color get() = ThemeManager.colors.accentAlt
    val syntaxKeyword: Color get() = ThemeManager.colors.syntaxKeyword
    val syntaxType: Color get() = ThemeManager.colors.syntaxType
    val syntaxString: Color get() = ThemeManager.colors.syntaxString
    val syntaxNumber: Color get() = ThemeManager.colors.syntaxNumber
    val syntaxComment: Color get() = ThemeManager.colors.syntaxComment
    val syntaxFunction: Color get() = ThemeManager.colors.syntaxFunction
    val syntaxAnnotation: Color get() = ThemeManager.colors.syntaxAnnotation
    val syntaxPunctuation: Color get() = ThemeManager.colors.syntaxPunctuation
    val syntaxControl: Color get() = ThemeManager.colors.syntaxControl
    val syntaxTag: Color get() = ThemeManager.colors.syntaxTag
    val syntaxAttribute: Color get() = ThemeManager.colors.syntaxAttribute
    val border: Color get() = ThemeManager.colors.border
    val fill: Color get() = ThemeManager.colors.fill
    val successContainer: Color get() = ThemeManager.colors.successContainer
    val warningContainer: Color get() = ThemeManager.colors.warningContainer
    val dangerContainer: Color get() = ThemeManager.colors.dangerContainer
    val infoContainer: Color get() = ThemeManager.colors.infoContainer
    val accentIndigo: Color get() = ThemeManager.colors.accentIndigo
    val accentTeal: Color get() = ThemeManager.colors.accentTeal
    val accentPink: Color get() = ThemeManager.colors.accentPink
    val editorBg: Color get() = ThemeManager.colors.editorBg
    val editorPanel: Color get() = ThemeManager.colors.editorPanel
    val editorText: Color get() = ThemeManager.colors.editorText
    val editorMuted: Color get() = ThemeManager.colors.editorMuted
    val editorBorder: Color get() = ThemeManager.colors.editorBorder
    val editorSelection: Color get() = ThemeManager.colors.editorSelection
    val editorLine: Color get() = ThemeManager.colors.editorLine
    val editorAddedBg: Color get() = ThemeManager.colors.editorAddedBg
    val editorAddedText: Color get() = ThemeManager.colors.editorAddedText
    val editorRemovedBg: Color get() = ThemeManager.colors.editorRemovedBg
    val editorRemovedText: Color get() = ThemeManager.colors.editorRemovedText
    val editorInfoBg: Color get() = ThemeManager.colors.editorInfoBg
    val editorInfoText: Color get() = ThemeManager.colors.editorInfoText
    val terminalBg: Color get() = ThemeManager.colors.terminalBg
    val terminalFg: Color get() = ThemeManager.colors.terminalFg
    val terminalCursor: Color get() = ThemeManager.colors.terminalCursor
    val inverseSurface: Color get() = ThemeManager.colors.inverseSurface
    val inverseOnSurface: Color get() = ThemeManager.colors.inverseOnSurface
}
