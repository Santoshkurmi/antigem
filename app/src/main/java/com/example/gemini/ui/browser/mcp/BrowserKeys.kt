package com.example.gemini.ui.browser.mcp

import android.view.KeyEvent

/** A physical key as DevTools (`Input.dispatchKeyEvent`) and Android (`KeyEvent`) describe it. */
data class KeyDef(
    val key: String,
    val code: String,
    val keyCode: Int,
    val text: String? = null,
    val androidCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val modifierBit: Int = 0,
    val location: Int = 0,
)

/** One step of a `key` action: a key with modifiers held, or plain text to insert. */
sealed class KeyStep {
    data class Press(val def: KeyDef, val modifiers: Int) : KeyStep()
    data class Text(val text: String) : KeyStep()
}

/**
 * Key names, aliases and US-layout characters for the browser MCP's `key` and `type` actions.
 * Modifier bits follow DevTools: Alt=1, Ctrl=2, Meta=4, Shift=8.
 */
object BrowserKeys {
    const val ALT = 1
    const val CTRL = 2
    const val META = 4
    const val SHIFT = 8

    private val named = HashMap<String, KeyDef>()
    private val chars = HashMap<Char, Pair<KeyDef, Boolean>>()

    private fun name(def: KeyDef, vararg aliases: String) {
        named[def.key.lowercase()] = def
        aliases.forEach { named[it] = def }
    }

    init {
        name(KeyDef("Enter", "Enter", 13, "\r", KeyEvent.KEYCODE_ENTER), "return", "enter", "kp_enter")
        name(KeyDef("Tab", "Tab", 9, null, KeyEvent.KEYCODE_TAB), "tab")
        name(KeyDef("Backspace", "Backspace", 8, null, KeyEvent.KEYCODE_DEL), "backspace", "back_space", "bksp")
        name(KeyDef("Delete", "Delete", 46, null, KeyEvent.KEYCODE_FORWARD_DEL), "delete", "del")
        name(KeyDef("Escape", "Escape", 27, null, KeyEvent.KEYCODE_ESCAPE), "escape", "esc")
        name(KeyDef(" ", "Space", 32, " ", KeyEvent.KEYCODE_SPACE), "space", "spacebar")
        name(KeyDef("ArrowUp", "ArrowUp", 38, null, KeyEvent.KEYCODE_DPAD_UP), "arrowup", "up")
        name(KeyDef("ArrowDown", "ArrowDown", 40, null, KeyEvent.KEYCODE_DPAD_DOWN), "arrowdown", "down")
        name(KeyDef("ArrowLeft", "ArrowLeft", 37, null, KeyEvent.KEYCODE_DPAD_LEFT), "arrowleft", "left")
        name(KeyDef("ArrowRight", "ArrowRight", 39, null, KeyEvent.KEYCODE_DPAD_RIGHT), "arrowright", "right")
        name(KeyDef("Home", "Home", 36, null, KeyEvent.KEYCODE_MOVE_HOME), "home")
        name(KeyDef("End", "End", 35, null, KeyEvent.KEYCODE_MOVE_END), "end")
        name(KeyDef("PageUp", "PageUp", 33, null, KeyEvent.KEYCODE_PAGE_UP), "pageup", "page_up", "pgup")
        name(KeyDef("PageDown", "PageDown", 34, null, KeyEvent.KEYCODE_PAGE_DOWN), "pagedown", "page_down", "pgdn")
        name(KeyDef("Insert", "Insert", 45, null, KeyEvent.KEYCODE_INSERT), "insert", "ins")
        name(KeyDef("ContextMenu", "ContextMenu", 93, null, KeyEvent.KEYCODE_MENU), "contextmenu", "menu", "apps")
        name(KeyDef("Shift", "ShiftLeft", 16, null, KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, 1), "shift")
        name(KeyDef("Control", "ControlLeft", 17, null, KeyEvent.KEYCODE_CTRL_LEFT, CTRL, 1), "control", "ctrl")
        name(KeyDef("Alt", "AltLeft", 18, null, KeyEvent.KEYCODE_ALT_LEFT, ALT, 1), "alt", "option", "opt")
        name(KeyDef("Meta", "MetaLeft", 91, null, KeyEvent.KEYCODE_META_LEFT, META, 1), "meta", "cmd", "command", "super", "win", "windows")
        for (i in 1..12) name(KeyDef("F$i", "F$i", 111 + i, null, KeyEvent.KEYCODE_F1 + i - 1), "f$i")

        for (c in 'a'..'z') {
            val up = c.uppercaseChar()
            val android = KeyEvent.KEYCODE_A + (c - 'a')
            chars[c] = KeyDef(c.toString(), "Key$up", up.code, c.toString(), android) to false
            chars[up] = KeyDef(up.toString(), "Key$up", up.code, up.toString(), android) to true
        }
        val shiftedDigits = ")!@#$%^&*("
        for (d in 0..9) {
            val c = '0' + d
            chars[c] = KeyDef(c.toString(), "Digit$d", c.code, c.toString(), KeyEvent.KEYCODE_0 + d) to false
            val s = shiftedDigits[d]
            chars[s] = KeyDef(s.toString(), "Digit$d", c.code, s.toString(), KeyEvent.KEYCODE_0 + d) to true
        }
        fun punct(plain: Char, shifted: Char, code: String, vk: Int, android: Int) {
            chars[plain] = KeyDef(plain.toString(), code, vk, plain.toString(), android) to false
            chars[shifted] = KeyDef(shifted.toString(), code, vk, shifted.toString(), android) to true
        }
        punct('-', '_', "Minus", 189, KeyEvent.KEYCODE_MINUS)
        punct('=', '+', "Equal", 187, KeyEvent.KEYCODE_EQUALS)
        punct('[', '{', "BracketLeft", 219, KeyEvent.KEYCODE_LEFT_BRACKET)
        punct(']', '}', "BracketRight", 221, KeyEvent.KEYCODE_RIGHT_BRACKET)
        punct('\\', '|', "Backslash", 220, KeyEvent.KEYCODE_BACKSLASH)
        punct(';', ':', "Semicolon", 186, KeyEvent.KEYCODE_SEMICOLON)
        punct('\'', '"', "Quote", 222, KeyEvent.KEYCODE_APOSTROPHE)
        punct(',', '<', "Comma", 188, KeyEvent.KEYCODE_COMMA)
        punct('.', '>', "Period", 190, KeyEvent.KEYCODE_PERIOD)
        punct('/', '?', "Slash", 191, KeyEvent.KEYCODE_SLASH)
        punct('`', '~', "Backquote", 192, KeyEvent.KEYCODE_GRAVE)
        chars[' '] = named.getValue("space") to false
    }

    /** The key that types [c] on a US keyboard, and whether Shift is needed; null for other characters. */
    fun forChar(c: Char): Pair<KeyDef, Boolean>? = chars[c]

    fun enter(): KeyDef = named.getValue("enter")
    fun tab(): KeyDef = named.getValue("tab")

    private fun lookup(name: String): KeyDef? = named[name.lowercase()] ?: name.singleOrNull()?.let { chars[it]?.first ?: chars[it.lowercaseChar()]?.first }

    /**
     * Parses a `key` action: space-separated presses, each a key name ("Enter", "ArrowDown"), a chord ("ctrl+a",
     * "shift+Tab", "ctrl+shift+k", "shift++" for the + key) or one character.
     */
    fun parse(input: String): List<KeyStep> {
        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "text is empty: name the key(s) to press, e.g. \"Enter\" or \"ctrl+a\"" }
        return tokens.mapIndexed { index, token ->
            if (token == "+" || !token.contains('+')) {
                val def = lookup(token)
                    ?: if (token.codePointCount(0, token.length) == 1) return@mapIndexed KeyStep.Text(token)
                    else throw IllegalArgumentException("Unknown key \"$token\". Use names like Enter, Tab, Escape, Backspace, ArrowDown, PageDown, F5, or chords like ctrl+a. To enter text use the type action.")
                if (def.modifierBit != 0 && index < tokens.size - 1) {
                    throw IllegalArgumentException("\"$token\" on its own is released before the next key. Join them with +, e.g. ctrl+a or shift+Tab.")
                }
                KeyStep.Press(def, 0)
            } else {
                val parts = if (token.endsWith("++")) token.dropLast(2).split('+') + "+" else token.split('+')
                var mods = 0
                val keys = mutableListOf<String>()
                for (part in parts) {
                    val def = named[part.lowercase()]
                    if (def != null && def.modifierBit != 0) mods = mods or def.modifierBit else keys += part
                }
                require(keys.size == 1 && keys[0].isNotEmpty()) {
                    if (keys.isEmpty() || keys.any { it.isEmpty() }) "\"$token\" is incomplete: join modifiers and one key with +, e.g. ctrl+a (shift++ for the + key)."
                    else "\"$token\" has more than one key. Modifiers are ctrl, shift, alt and meta/cmd; separate keys pressed one after another with spaces."
                }
                val def = lookup(keys[0]) ?: throw IllegalArgumentException("Unknown key \"${keys[0]}\" in \"$token\".")
                KeyStep.Press(def, mods)
            }
        }
    }

    /** The modifier keys held for [modifiers], in press order. */
    fun modifierKeys(modifiers: Int): List<KeyDef> = listOf(CTRL to "control", ALT to "alt", SHIFT to "shift", META to "meta")
        .filter { modifiers and it.first != 0 }.map { named.getValue(it.second) }

    /** Parses a click's modifiers string ("ctrl", "shift+alt", "cmd") into DevTools bits. */
    fun parseModifiers(spec: String?): Int {
        if (spec.isNullOrBlank()) return 0
        var bits = 0
        for (part in spec.lowercase().split('+', ' ', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val def = named[part]
            require(def != null && def.modifierBit != 0) { "Unknown modifier \"$part\": use ctrl, shift, alt or meta (cmd)." }
            bits = bits or def.modifierBit
        }
        return bits
    }

    fun androidMeta(modifiers: Int): Int {
        var meta = 0
        if (modifiers and SHIFT != 0) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (modifiers and CTRL != 0) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers and ALT != 0) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (modifiers and META != 0) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return meta
    }
}
