package ai.antigem.data.local

import android.view.KeyEvent
import ai.antigem.i18n.L
import org.json.JSONArray
import org.json.JSONObject

/**
 * One key of the terminal's extra-keys bar, in Termux's terms: [key] is a key name (ESC, CTRL, UP, F5, DRAWER, …) or
 * any text to type ("|", "~", "ls -la"); [popup] is what a swipe up on it sends; [display] its own label instead of
 * the usual one; [macro]: [key] is a sequence of keys separated by spaces ("CTRL d", "ESC : w q ENTER").
 */
data class ExtraKeySpec(
    val key: String,
    val popup: ExtraKeySpec? = null,
    val display: String? = null,
    val macro: Boolean = false
)

/**
 * The extra-keys bar the user can change (Settings → Terminal → Extra keys), saved in Termux's own format, so the
 * `extra-keys = [[…]]` line of a termux.properties can be pasted in and copied out:
 * rows of keys, each a string or `{key: …, popup: …, display: …, macro: …}`.
 */
object TerminalExtraKeys {
    /** AntiGem's bar: Termux's two rows (with a plain "-"). */
    const val DEFAULT = """[["ESC","/","-","HOME","UP","END","PGUP"],["TAB","CTRL","ALT","LEFT","DOWN","RIGHT","PGDN"]]"""

    /** Hold-to-repeat keys (plus one-character text keys). */
    private val REPEAT = setOf("UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL", "PGUP", "PGDN", "TAB")

    val MODIFIERS = listOf("CTRL", "ALT", "SHIFT")
    /** Not keys: open the sessions drawer, show/hide the keyboard, paste the clipboard. */
    val ACTIONS = listOf("DRAWER", "KEYBOARD", "PASTE")
    /** Termux keys with no counterpart here (FN; SCROLL needs a newer terminal emulator): left out when read. */
    private val UNSUPPORTED = setOf("FN", "SCROLL")
    val NAVIGATION = listOf("LEFT", "UP", "DOWN", "RIGHT", "HOME", "END", "PGUP", "PGDN")
    val EDITING = listOf("ESC", "TAB", "ENTER", "SPACE", "BKSP", "DEL", "INS")
    val FUNCTION = (1..12).map { "F$it" }

    /** Key name → Android key code and what to send when the terminal view doesn't take the key code. */
    private val CODES: Map<String, Pair<Int, String>> = mapOf(
        "ESC" to (KeyEvent.KEYCODE_ESCAPE to "\u001B"),
        "TAB" to (KeyEvent.KEYCODE_TAB to "\t"),
        "ENTER" to (KeyEvent.KEYCODE_ENTER to "\r"),
        "SPACE" to (KeyEvent.KEYCODE_SPACE to " "),
        "BKSP" to (KeyEvent.KEYCODE_DEL to "\u007F"),
        "DEL" to (KeyEvent.KEYCODE_FORWARD_DEL to "\u001B[3~"),
        "INS" to (KeyEvent.KEYCODE_INSERT to "\u001B[2~"),
        "HOME" to (KeyEvent.KEYCODE_MOVE_HOME to "\u0001"),
        "END" to (KeyEvent.KEYCODE_MOVE_END to "\u0005"),
        "PGUP" to (KeyEvent.KEYCODE_PAGE_UP to "\u001B[5~"),
        "PGDN" to (KeyEvent.KEYCODE_PAGE_DOWN to "\u001B[6~"),
        "UP" to (KeyEvent.KEYCODE_DPAD_UP to "\u001B[A"),
        "DOWN" to (KeyEvent.KEYCODE_DPAD_DOWN to "\u001B[B"),
        "RIGHT" to (KeyEvent.KEYCODE_DPAD_RIGHT to "\u001B[C"),
        "LEFT" to (KeyEvent.KEYCODE_DPAD_LEFT to "\u001B[D"),
        "F1" to (KeyEvent.KEYCODE_F1 to "\u001BOP"),
        "F2" to (KeyEvent.KEYCODE_F2 to "\u001BOQ"),
        "F3" to (KeyEvent.KEYCODE_F3 to "\u001BOR"),
        "F4" to (KeyEvent.KEYCODE_F4 to "\u001BOS"),
        "F5" to (KeyEvent.KEYCODE_F5 to "\u001B[15~"),
        "F6" to (KeyEvent.KEYCODE_F6 to "\u001B[17~"),
        "F7" to (KeyEvent.KEYCODE_F7 to "\u001B[18~"),
        "F8" to (KeyEvent.KEYCODE_F8 to "\u001B[19~"),
        "F9" to (KeyEvent.KEYCODE_F9 to "\u001B[20~"),
        "F10" to (KeyEvent.KEYCODE_F10 to "\u001B[21~"),
        "F11" to (KeyEvent.KEYCODE_F11 to "\u001B[23~"),
        "F12" to (KeyEvent.KEYCODE_F12 to "\u001B[24~")
    )

    /** Termux's other spellings of the key names. */
    private val ALIASES = mapOf(
        "ESCAPE" to "ESC", "CONTROL" to "CTRL", "SHFT" to "SHIFT", "RETURN" to "ENTER",
        "LT" to "LEFT", "RT" to "RIGHT", "DN" to "DOWN",
        "PAGEUP" to "PGUP", "PAGE_UP" to "PGUP", "PAGE UP" to "PGUP", "PAGE-UP" to "PGUP",
        "PAGEDOWN" to "PGDN", "PAGE_DOWN" to "PGDN", "PAGE-DOWN" to "PGDN",
        "DELETE" to "DEL", "BACKSPACE" to "BKSP",
        "BACKSLASH" to "\\", "QUOTE" to "\"", "APOSTROPHE" to "'"
    )

    fun normalize(key: String): String = ALIASES[key] ?: key

    fun codeOf(key: String): Pair<Int, String>? = CODES[key]
    fun isModifier(key: String) = key in MODIFIERS
    fun isAction(key: String) = key in ACTIONS
    /** A key name rather than text to type. */
    fun isNamed(key: String) = key in CODES || key in MODIFIERS || key in ACTIONS
    fun repeats(spec: ExtraKeySpec): Boolean =
        !spec.macro && (spec.key in REPEAT || (!isNamed(spec.key) && spec.key.codePointCount(0, spec.key.length) == 1))

    /** The key's label on the bar. */
    fun label(spec: ExtraKeySpec): String = spec.display?.takeIf { it.isNotBlank() } ?: labelOf(spec.key)

    fun labelOf(key: String): String = when (key) {
        "UP" -> "↑"
        "DOWN" -> "↓"
        "LEFT" -> "←"
        "RIGHT" -> "→"
        "TAB" -> "↹"
        "ENTER" -> "↲"
        "BKSP" -> "⌫"
        "DEL" -> "⌦"
        "SPACE" -> "␣"
        "DRAWER" -> "☰"
        "KEYBOARD" -> "⌨"
        "PASTE" -> "⎘"
        "PGUP" -> L.components.localTerminal.pgup
        "PGDN" -> L.components.localTerminal.pgdn
        "CTRL" -> L.components.localTerminal.ctrl
        "ALT" -> L.components.localTerminal.alt
        else -> key
    }

    /** The bar's rows from [text] (Termux's format, also a whole `extra-keys = …` line); throws when it isn't one. */
    fun parse(text: String): List<List<ExtraKeySpec>> {
        var t = text.trim()
        // a termux.properties line: extra-keys = [[...]]
        if (!t.startsWith("[") && t.contains('=')) t = t.substringAfter('=').trim()
        // Termux allows a line break inside the value with a trailing backslash
        t = t.replace("\\\n", "\n")
        val arr = JSONArray(t)
        val rows = (0 until arr.length()).map { i ->
            val row = arr.optJSONArray(i) ?: throw IllegalArgumentException("row ${i + 1} is not a list of keys")
            (0 until row.length()).mapNotNull { j -> keyOf(row.get(j)) }
        }.filter { it.isNotEmpty() }
        require(rows.isNotEmpty()) { "no keys" }
        return rows
    }

    /** [text]'s rows, or the default bar when it can't be read. */
    fun parseOrDefault(text: String?): List<List<ExtraKeySpec>> =
        runCatching { parse(text?.takeIf { it.isNotBlank() } ?: DEFAULT) }.getOrElse { parse(DEFAULT) }

    private fun keyOf(o: Any?): ExtraKeySpec? = when (o) {
        is String -> o.takeIf { it.isNotEmpty() && it !in UNSUPPORTED }?.let { ExtraKeySpec(normalize(it)) }
        is JSONObject -> {
            val macro = o.optString("macro").takeIf { it.isNotBlank() }
            val key = macro ?: o.optString("key").takeIf { it.isNotEmpty() && it !in UNSUPPORTED }
            key?.let {
                ExtraKeySpec(
                    key = if (macro != null) it.trim() else normalize(it),
                    popup = keyOf(o.opt("popup")),
                    display = o.optString("display").takeIf { d -> d.isNotEmpty() },
                    macro = macro != null
                )
            }
        }
        else -> null
    }

    // ------------------------------------------------------------------ session shortcuts (CTRL + a key)

    /**
     * What a CTRL shortcut can do (Termux's shortcut.create-session, next-session, previous-session, rename-session,
     * plus the sessions drawer). It works with the CTRL extra key and with a hardware keyboard's Ctrl.
     */
    val SHORTCUT_ACTIONS = listOf("create", "next", "previous", "rename", "drawer")

    /** AntiGem's shortcuts so far: CTRL+T a new session, CTRL+1 / CTRL+2 the previous / next one. */
    val DEFAULT_SHORTCUTS = mapOf("create" to "t", "next" to "2", "previous" to "1", "rename" to "", "drawer" to "")

    private val TERMUX_SHORTCUT_NAMES = mapOf(
        "create-session" to "create", "next-session" to "next", "previous-session" to "previous", "rename-session" to "rename"
    )

    /** action → its key (one character, lower case; "" = no shortcut) from what was saved (empty: the defaults). */
    fun parseShortcuts(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return DEFAULT_SHORTCUTS
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return DEFAULT_SHORTCUTS
        return SHORTCUT_ACTIONS.associateWith { a -> cleanShortcutKey(o.optString(a, DEFAULT_SHORTCUTS[a].orEmpty())) }
    }

    fun shortcutsJson(map: Map<String, String>): String =
        JSONObject().apply { SHORTCUT_ACTIONS.forEach { put(it, cleanShortcutKey(map[it].orEmpty())) } }.toString()

    /** One character (a letter in lower case), else "". */
    fun cleanShortcutKey(k: String): String {
        val t = k.trim()
        if (t.isEmpty() || t.codePointCount(0, t.length) != 1) return ""
        return t.lowercase()
    }

    /** The code point of each shortcut's key (lower case) → its action. */
    fun shortcutIndex(map: Map<String, String>): Map<Int, String> =
        map.entries.mapNotNull { (a, k) -> k.takeIf { it.isNotEmpty() }?.let { it.codePointAt(0) to a } }.toMap()

    /**
     * What a pasted termux.properties holds: its `extra-keys` (also spread over lines ending in \) and its
     * `shortcut.*-session = ctrl + x` lines. Null parts were not in it.
     */
    data class Imported(val rows: List<List<ExtraKeySpec>>?, val shortcuts: Map<String, String>?)

    fun importText(text: String): Imported {
        val trimmed = text.trim()
        // only the keys themselves: [[...]]
        if (trimmed.startsWith("[")) return Imported(parse(trimmed), null)
        // a properties file: join the lines continued with a trailing backslash
        val lines = trimmed.replace(Regex("\\\\\r?\n"), " ").lines()
        var rows: List<List<ExtraKeySpec>>? = null
        val shortcuts = mutableMapOf<String, String>()
        for (line in lines) {
            val l = line.trim()
            if (l.startsWith("#") || !l.contains('=') && !l.contains(':')) continue
            val sep = l.indexOfFirst { it == '=' || it == ':' }
            val name = l.substring(0, sep).trim()
            val value = l.substring(sep + 1).trim()
            when {
                name == "extra-keys" -> rows = parse(value)
                name.startsWith("shortcut.") -> {
                    val action = TERMUX_SHORTCUT_NAMES[name.removePrefix("shortcut.")] ?: continue
                    val parts = value.lowercase().split('+').map { it.trim() }
                    if (parts.size == 2 && parts[0] == "ctrl") shortcuts[action] = cleanShortcutKey(parts[1])
                }
            }
        }
        require(rows != null || shortcuts.isNotEmpty()) { "no extra-keys or shortcut lines found" }
        return Imported(rows, shortcuts.takeIf { it.isNotEmpty() })
    }

    /** The keys and shortcuts as termux.properties lines. */
    fun exportText(rows: List<List<ExtraKeySpec>>, shortcuts: Map<String, String>): String = buildString {
        append("extra-keys = ").append(toJson(rows)).append('\n')
        TERMUX_SHORTCUT_NAMES.forEach { (termuxName, action) ->
            shortcuts[action]?.takeIf { it.isNotEmpty() }?.let { append("shortcut.").append(termuxName).append(" = ctrl + ").append(it).append('\n') }
        }
    }

    /** [rows] in Termux's format (what termux.properties takes after `extra-keys = `). */
    fun toJson(rows: List<List<ExtraKeySpec>>): String =
        JSONArray().apply { rows.forEach { row -> put(JSONArray().apply { row.forEach { put(jsonOf(it)) } }) } }.toString()

    private fun jsonOf(spec: ExtraKeySpec): Any =
        if (spec.popup == null && spec.display == null && !spec.macro) spec.key
        else JSONObject().apply {
            put(if (spec.macro) "macro" else "key", spec.key)
            spec.popup?.let { put("popup", jsonOf(it)) }
            spec.display?.let { put("display", it) }
        }
}
