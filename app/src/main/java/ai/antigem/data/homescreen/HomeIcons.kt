package ai.antigem.data.homescreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import ai.antigem.domain.model.AgentKind

/**
 * Launcher icons for home-screen shortcuts, drawn as adaptive icons (the launcher cuts them to its own shape): a
 * gradient of the item's color filling the shape, a white glyph in the middle. A chat's icon is its agent's color
 * with a white speech bubble holding the chat's initials, so several chats of one agent still look different.
 */
object HomeIcons {
    /** Antigravity, Claude Code, OpenCode and Codex: the same colors the app uses for them. */
    fun agentColor(agent: AgentKind): Int = when (agent) {
        AgentKind.AGY -> 0xFF3B82F6.toInt()
        AgentKind.CLAUDE -> 0xFFD97757.toInt()
        AgentKind.OPENCODE -> 0xFF10B981.toInt()
        AgentKind.CODEX -> 0xFF8B5CF6.toInt()
    }

    fun agentLabel(agent: AgentKind): String = when (agent) {
        AgentKind.AGY -> "Antigravity"
        AgentKind.CLAUDE -> "Claude Code"
        AgentKind.OPENCODE -> "OpenCode"
        AgentKind.CODEX -> "Codex"
    }

    /** One or two letters for a chat: the first letters of its first two words ("Fix login bug" → "FL"). */
    fun initials(title: String): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "?"
        fun first(w: String): String = String(Character.toChars(w.codePointAt(0))).uppercase()
        return if (words.size == 1) first(words[0]) else first(words[0]) + first(words[1])
    }

    fun action(context: Context, action: HomeAction): IconCompat = glyphIcon(context, action.color.toInt(), action.glyph)

    fun glyphIcon(context: Context, color: Int, glyph: Int): IconCompat =
        IconCompat.createWithAdaptiveBitmap(draw(context, color) { c, unit, size ->
            val d = ContextCompat.getDrawable(context, glyph)?.mutate() ?: return@draw
            d.setTint(Color.WHITE)
            val g = (42 * unit).toInt()
            val left = ((size - g) / 2f).toInt()
            d.setBounds(left, left, left + g, left + g)
            d.draw(c)
        })

    fun chat(context: Context, agent: AgentKind, title: String): IconCompat {
        val color = agentColor(agent)
        val text = initials(title)
        return IconCompat.createWithAdaptiveBitmap(draw(context, color) { c, unit, size ->
            val cx = size / 2f
            val cy = size / 2f - 3 * unit
            // the bubble, with its tail at the lower left
            val w = 58 * unit
            val h = 44 * unit
            val box = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
            c.drawRoundRect(box, 15 * unit, 15 * unit, white)
            val tail = Path().apply {
                moveTo(box.left + 11 * unit, box.bottom - 4 * unit)
                lineTo(box.left + 8 * unit, box.bottom + 10 * unit)
                lineTo(box.left + 25 * unit, box.bottom - 2 * unit)
                close()
            }
            c.drawPath(tail, white)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ColorUtils.blendARGB(color, Color.BLACK, 0.18f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = (if (text.length > 1) 21 else 25) * unit
            }
            val fm = ink.fontMetrics
            c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, ink)
        })
    }

    /** A 108dp adaptive-icon bitmap: [color]'s gradient over the whole square, then [content] (unit = 1dp of 108). */
    private fun draw(context: Context, color: Int, content: (Canvas, Float, Int) -> Unit): Bitmap {
        val density = context.resources.displayMetrics.density.coerceIn(2f, 4f)
        val size = (108 * density).toInt()
        val unit = size / 108f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                ColorUtils.blendARGB(color, Color.WHITE, 0.16f), ColorUtils.blendARGB(color, Color.BLACK, 0.20f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        content(c, unit, size)
        return bmp
    }
}
