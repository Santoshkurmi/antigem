package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Beep: a little robot whose face is a glowing screen. Every mood has its own screen face (^ ^, X X, heart eyes, a
 * loading bar while the agent works, a red screen when annoyed); the antenna LED blinks while it thinks.
 * Signature move: "Scan" (a scan line sweeps the screen, signal rings leave the antenna, then a check mark).
 */
internal class BeepArt(d: Float, palette: Int) : PetArt(d) {
    private val shell: List<Color>
    private val ink: Color
    private val screen: Color
    private val glow: Color
    private val led: Color

    init {
        when (palette) {
            1 -> { shell = listOf(Color(0xFFE6FFF0), Color(0xFFB8EFCF), Color(0xFF7FD6A6)); ink = Color(0xFF23533B); screen = Color(0xFF0F2A1C); glow = Color(0xFF9DFF7A); led = Color(0xFFFFB347) }
            2 -> { shell = listOf(Color(0xFFFFE9F1), Color(0xFFFFC4D8), Color(0xFFF59AB8)); ink = Color(0xFF6A2A45); screen = Color(0xFF2A1220); glow = Color(0xFFFFC2E0); led = Color(0xFF7AF0FF) }
            3 -> { shell = listOf(Color(0xFFFFF4C8), Color(0xFFFFE08A), Color(0xFFE3B23C)); ink = Color(0xFF5E4512); screen = Color(0xFF2A1F0A); glow = Color(0xFFFFC94D); led = Color(0xFF7AF0FF) }
            else -> { shell = listOf(Color(0xFFF4F7FB), Color(0xFFD9E0EA), Color(0xFFB5C0D0)); ink = Color(0xFF3A4556); screen = Color(0xFF1B2333); glow = Color(0xFF5CF2FF); led = Color(0xFFFF6B6B) }
        }
    }

    override val face = FaceSpec(mouthY = 0.16f, headTop = -1.1f, ink = ink)
    override val sparkleColor get() = glow
    private val bolt = Color(0xFFFFD84D)

    private val shellBrush = Brush.verticalGradient(colors = shell, startY = -r, endY = r)
    private val shellMid = shell[1]
    private val shellDark = shell[2]
    private val glowStroke = Stroke(width = 0.09f * 24f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val glowThin = Stroke(width = 0.06f * 24f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val ringStroke = Stroke(width = 2.4f * d, cap = StrokeCap.Round)
    private val ringHalo = Stroke(width = 6f * d, cap = StrokeCap.Round)

    private val headTL = Offset(-0.98f * r, -0.86f * r)
    private val headSize = Size(1.96f * r, 1.66f * r)
    private val headCorner = CornerRadius(0.4f * r)
    private val screenTL = Offset(-0.72f * r, -0.6f * r)
    private val screenSize = Size(1.44f * r, 1.0f * r)
    private val screenCorner = CornerRadius(0.24f * r)

    private val check = Path().apply {
        moveTo(-0.3f * r, -0.1f * r)
        lineTo(-0.08f * r, 0.12f * r)
        lineTo(0.32f * r, -0.3f * r)
    }
    private val zig = Path()
    private val boltPath = Path().apply {
        moveTo(0.15f, -1f)
        lineTo(-0.45f, 0.1f)
        lineTo(-0.02f, 0.1f)
        lineTo(-0.2f, 1f)
        lineTo(0.5f, -0.2f)
        lineTo(0.06f, -0.2f)
        lineTo(0.3f, -1f)
        close()
    }

    // ---- behaviour ----

    override val specialDur = 2.1f

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.8f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        if (crossed(prev, t, 0.3f) || crossed(prev, t, 0.75f) || crossed(prev, t, 1.2f)) s.light()
        if (crossed(prev, t, 1.55f)) {
            s.jump(220f)
            s.fxBurst(FX_BOLT, 4, s.x, s.y - s.r, 170f, 0.7f, 5f)
        }
    }

    override fun specialMood(s: PetSim): Mood = if (s.trickT > 1.55f) Mood.HAPPY else Mood.NORMAL

    override fun workTick(s: PetSim) {
        s.fx(FX_PIXEL, s.x + (Random.nextFloat() - 0.5f) * s.r * 0.6f, s.y - s.r * 1.5f, (Random.nextFloat() - 0.5f) * 30f, -38f, 1.3f, 3.2f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        s.fxBurst(FX_BOLT, 5, s.x, s.y - s.r * 0.4f, 180f, 0.8f, 5.5f)
        s.fxBurst(FX_PIXEL, 6, s.x, s.y - s.r * 0.4f, 150f, 0.8f, 3f)
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_PIXEL, 5, s.x, s.y + s.hop, 110f, 0.5f, 2.6f)
    }

    override fun angryTick(s: PetSim) {
        s.fxBurst(FX_BOLT, 3, s.x, s.y - s.r * 1.2f, 120f, 0.6f, 4.5f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        when (variant) {
            FX_BOLT -> drawPath(boltPath, bolt.copy(alpha = alpha))
            FX_PIXEL -> drawRect(glow.copy(alpha = alpha), topLeft = Offset(-0.6f, -0.6f), size = Size(1.2f, 1.2f))
            else -> drawPath(sparkle, glow.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        drawRoundRect(Color(0xFF5BD68A), topLeft = Offset(-0.5f, -0.8f), size = Size(1f, 1.6f), cornerRadius = CornerRadius(0.2f))
        drawRect(Color(0xFF2E7D4F), topLeft = Offset(-0.18f, -0.98f), size = Size(0.36f, 0.2f))
        withTransform({ scale(0.62f, 0.62f, Offset.Zero) }) { drawPath(boltPath, Color(0xFFFFF3A0)) }
        drawRoundRect(Color(0xFF1E5235), topLeft = Offset(-0.5f, -0.8f), size = Size(1f, 1.6f), cornerRadius = CornerRadius(0.2f), style = unitOutline)
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val t = s.animT
        drawFeet(s, this@BeepArt, shellDark, ink, dx = 0.42f, y = 0.86f, w = 0.4f, h = 0.26f)
        drawArms(s, this@BeepArt, mood, shellMid, ink, ax = 0.98f, ay = 0.18f, len = 0.42f, thick = 0.22f)
        drawAntenna(s, mood, t)
        // side bolts
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            drawCircle(shellDark, radius = 0.13f * r, center = Offset(sgn * 1.0f * r, -0.08f * r))
            drawCircle(ink, radius = 0.13f * r, center = Offset(sgn * 1.0f * r, -0.08f * r), style = thinStroke)
        }
        drawRoundRect(brush = shellBrush, topLeft = headTL, size = headSize, cornerRadius = headCorner)
        drawRoundRect(ink, topLeft = headTL, size = headSize, cornerRadius = headCorner, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.55f), topLeft = Offset(-0.78f * r, -0.8f * r), size = Size(0.34f * r, 0.13f * r))
        // chest light under the screen
        drawCircle(if (s.agentWorking && sin(t * 8f) > 0f) led else shellDark, radius = 0.06f * r, center = Offset(0f, 0.6f * r))

        drawRoundRect(screen, topLeft = screenTL, size = screenSize, cornerRadius = screenCorner)
        if (mood == Mood.ANGRY) drawRoundRect(Color(0xFFFF3B3B).copy(alpha = 0.3f), topLeft = screenTL, size = screenSize, cornerRadius = screenCorner)
        drawScreenFace(s, mood, t)
        drawRoundRect(ink, topLeft = screenTL, size = screenSize, cornerRadius = screenCorner, style = thinStroke)
        // glass glare
        drawLine(Color.White.copy(alpha = 0.12f), Offset(-0.55f * r, 0.3f * r), Offset(-0.2f * r, -0.5f * r), strokeWidth = 0.12f * r, cap = StrokeCap.Round)
    }

    private fun DrawScope.drawAntenna(s: PetSim, mood: Mood, t: Float) {
        val ang = s.antenna.v
        withTransform({
            translate(0f, -0.86f * r)
            rotate(ang * 0.8f, Offset.Zero)
        }) {
            drawLine(ink, Offset.Zero, Offset(0f, -0.4f * r), strokeWidth = 0.08f * r, cap = StrokeCap.Round)
            val on = when {
                mood == Mood.THINKING -> sin(t * 9f) > 0f
                mood == Mood.SLEEP -> false
                else -> true
            }
            if (on) drawCircle(led.copy(alpha = 0.3f), radius = 0.22f * r, center = Offset(0f, -0.46f * r))
            drawCircle(if (on) led else led.copy(alpha = 0.35f), radius = 0.11f * r, center = Offset(0f, -0.46f * r))
            drawCircle(ink, radius = 0.11f * r, center = Offset(0f, -0.46f * r), style = thinStroke)
            // Scan: signal rings leaving the antenna
            if (s.trick == Trick.SPECIAL && s.trickT < 1.5f) {
                for (k in 0..2) {
                    val ph = ((s.trickT * 1.6f) + k / 3f).let { it - it.toInt() }
                    val rr = (0.25f + ph * 0.85f) * r
                    val tl = Offset(-rr, -0.46f * r - rr)
                    drawArc(glow.copy(alpha = 0.25f * (1f - ph)), startAngle = 210f, sweepAngle = 120f, useCenter = false, topLeft = tl, size = Size(2f * rr, 2f * rr), style = ringHalo)
                    drawArc(glow.copy(alpha = 1f - ph), startAngle = 210f, sweepAngle = 120f, useCenter = false, topLeft = tl, size = Size(2f * rr, 2f * rr), style = ringStroke)
                }
            }
        }
    }

    private fun DrawScope.drawScreenFace(s: PetSim, mood: Mood, t: Float) {
        val g = if (mood == Mood.SLEEP) glow.copy(alpha = 0.45f) else glow
        val ex = 0.3f * r
        val ey = -0.16f * r
        val lx = s.lookX * 0.09f * r
        val ly = s.lookY * 0.06f * r
        val scanning = s.trick == Trick.SPECIAL && s.trickT < 1.55f
        when {
            scanning -> {
                // a visor line and a sweeping scan beam
                val sy = -0.5f * r + (0.5f - 0.5f * kotlin.math.cos(s.trickT * 4.2f)) * 0.8f * r
                drawLine(g.copy(alpha = 0.25f), Offset(-0.66f * r, sy), Offset(0.66f * r, sy), strokeWidth = 0.18f * r)
                drawLine(g, Offset(-0.66f * r, sy), Offset(0.66f * r, sy), strokeWidth = 0.04f * r)
                drawLine(g, Offset(-0.42f * r, ey), Offset(0.42f * r, ey), strokeWidth = 0.1f * r, cap = StrokeCap.Round)
            }
            s.trick == Trick.SPECIAL -> drawPath(check, g, style = glowStroke)
            mood == Mood.HAPPY -> for (sgn in SIDES) {
                drawLine(g, Offset(sgn * ex - 0.13f * r, ey + 0.06f * r), Offset(sgn * ex, ey - 0.08f * r), strokeWidth = 0.09f * r, cap = StrokeCap.Round)
                drawLine(g, Offset(sgn * ex, ey - 0.08f * r), Offset(sgn * ex + 0.13f * r, ey + 0.06f * r), strokeWidth = 0.09f * r, cap = StrokeCap.Round)
            }
            mood == Mood.LOVE -> for (sgn in SIDES) {
                val hs = 0.16f * r * (1f + 0.12f * sin(t * 10f))
                unit(sgn * ex, ey, hs) { drawPath(heart, heartColor) }
            }
            mood == Mood.DIZZY -> for (sgn in SIDES) {
                val c = Offset(sgn * ex, ey)
                drawLine(g, c + Offset(-0.11f * r, -0.11f * r), c + Offset(0.11f * r, 0.11f * r), strokeWidth = 0.08f * r, cap = StrokeCap.Round)
                drawLine(g, c + Offset(0.11f * r, -0.11f * r), c + Offset(-0.11f * r, 0.11f * r), strokeWidth = 0.08f * r, cap = StrokeCap.Round)
            }
            mood == Mood.SLEEP -> for (sgn in SIDES) {
                drawLine(g, Offset(sgn * ex - 0.12f * r, ey), Offset(sgn * ex + 0.12f * r, ey), strokeWidth = 0.08f * r, cap = StrokeCap.Round)
            }
            mood == Mood.SURPRISED -> for (sgn in SIDES) {
                drawCircle(g, radius = 0.13f * r, center = Offset(sgn * ex + lx, ey + ly), style = glowThin)
                drawCircle(g, radius = 0.04f * r, center = Offset(sgn * ex + lx, ey + ly))
            }
            mood == Mood.ANGRY -> for (sgn in SIDES) {
                withTransform({ translate(sgn * ex, ey); rotate(-sgn * 20f, Offset.Zero) }) {
                    drawRoundRect(g, topLeft = Offset(-0.15f * r, -0.05f * r), size = Size(0.3f * r, 0.12f * r), cornerRadius = CornerRadius(0.05f * r))
                }
            }
            else -> {
                val open = max(0.12f, (1f - s.blink) * (1f - s.yawn * 0.6f))
                val h = 0.3f * r * open
                for (sgn in SIDES) {
                    drawRoundRect(g.copy(alpha = 0.22f), topLeft = Offset(sgn * ex + lx - 0.13f * r, ey + ly - h / 2f - 0.04f * r), size = Size(0.26f * r, h + 0.08f * r), cornerRadius = CornerRadius(0.08f * r))
                    drawRoundRect(g, topLeft = Offset(sgn * ex + lx - 0.08f * r, ey + ly - h / 2f), size = Size(0.16f * r, h), cornerRadius = CornerRadius(0.06f * r))
                }
            }
        }

        if (scanning || s.trick == Trick.SPECIAL) return
        // mouth row
        val my = 0.2f * r
        val open = max(s.yawn, s.mouthOpen)
        when {
            mood == Mood.THINKING -> {
                // loading bar: a block running along a track
                drawRoundRect(g.copy(alpha = 0.3f), topLeft = Offset(-0.38f * r, my - 0.05f * r), size = Size(0.76f * r, 0.1f * r), cornerRadius = CornerRadius(0.05f * r))
                val p = (t * 0.9f).let { it - it.toInt() }
                drawRoundRect(g, topLeft = Offset(-0.38f * r + p * 0.52f * r, my - 0.05f * r), size = Size(0.24f * r, 0.1f * r), cornerRadius = CornerRadius(0.05f * r))
            }
            open > 0.05f -> drawRoundRect(g, topLeft = Offset(-0.12f * r, my - 0.04f * r), size = Size(0.24f * r, (0.06f + 0.18f * open) * r), cornerRadius = CornerRadius(0.06f * r))
            mood == Mood.HAPPY || mood == Mood.LOVE -> drawArc(g, startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(-0.18f * r, my - 0.12f * r), size = Size(0.36f * r, 0.26f * r))
            mood == Mood.SURPRISED -> drawCircle(g, radius = 0.07f * r, center = Offset(0f, my + 0.02f * r), style = glowThin)
            mood == Mood.DIZZY || mood == Mood.ANGRY -> {
                zig.reset()
                zig.moveTo(-0.2f * r, my)
                for (k in 1..4) zig.lineTo(-0.2f * r + k * 0.1f * r, my + (if (k % 2 == 1) -0.05f else 0.05f) * r)
                drawPath(zig, g, style = glowThin)
            }
            mood == Mood.SLEEP -> drawCircle(g, radius = 0.04f * r, center = Offset(0f, my))
            else -> drawArc(g, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(-0.16f * r, my - 0.14f * r), size = Size(0.32f * r, 0.22f * r), style = glowThin)
        }
        // asleep: a "z" on the screen corner
        if (mood == Mood.SLEEP) unit(0.48f * r, -0.42f * r, 0.08f * r) { drawPath(zPath, g, style = zStroke) }
    }

    private companion object {
        const val FX_BOLT = 1
        const val FX_PIXEL = 2
        val SIDES = floatArrayOf(-1f, 1f)
    }
}
