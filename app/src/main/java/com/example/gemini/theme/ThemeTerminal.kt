package com.example.gemini.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Puts the theme's terminal colors (terminal_bg, terminal_fg, terminal_cursor, ansi_0…ansi_15) into the terminal
 * emulator: its shared color scheme is what every session starts from, and running sessions are reset to it so they
 * repaint in the new colors at once. [version] changes after each update, so terminal views know to redraw.
 */
object ThemeTerminal {
    @Volatile private var applied: ThemeColors? = null
    var version by mutableIntStateOf(0)
        private set

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    fun apply(c: ThemeColors) {
        if (applied === c) return
        applied = c
        runCatching {
            val p = java.util.Properties()
            p["foreground"] = hex(c.terminalFg)
            p["background"] = hex(c.terminalBg)
            p["cursor"] = hex(c.terminalCursor)
            c.ansi.forEachIndexed { i, v -> p["color$i"] = "#%06X".format(v and 0xFFFFFF) }
            com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(p)
            com.example.gemini.data.local.LocalTerminalManager.sessions.value.forEach { s ->
                runCatching { s.terminalSession.emulator?.mColors?.reset() }
            }
        }
        version++
    }
}
