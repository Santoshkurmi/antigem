package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mist: a small friendly ghost. It floats higher and bobs more than the others (and casts only a faint shadow), its
 * hem ripples, and it glows while the agent works. Signature move: "Vanish" (fades almost away, then pops back with
 * a "Boo!").
 */
internal class MistArt(d: Float, palette: Int) : PetArt(d) {
    private val sheet: List<Color>
    private val ink: Color
    private val glow: Color

    init {
        when (palette) {
            1 -> { sheet = listOf(Color(0xFFF2FFF9), Color(0xFFCFF7E6), Color(0xFFA2E9CE)); ink = Color(0xFF2F6B57); glow = Color(0xFFB8FFE6) }
            2 -> { sheet = listOf(Color(0xFFF8F2FF), Color(0xFFE2D4FF), Color(0xFFC3ABFF)); ink = Color(0xFF4E3A7A); glow = Color(0xFFE0D0FF) }
            3 -> { sheet = listOf(Color(0xFFFFF4E6), Color(0xFFFFDDB5), Color(0xFFFFB067)); ink = Color(0xFF7A4318); glow = Color(0xFFFFD08A) }
            else -> { sheet = listOf(Color(0xFFFFFFFF), Color(0xFFF4F6FF), Color(0xFFDDE3FA)); ink = Color(0xFF5B5F8A); glow = Color(0xFFC8D2FF) }
        }
    }

    override val face = FaceSpec(
        eyeY = -0.14f, eyeDX = 0.34f, eyeW = 0.12f, eyeH = 0.17f, mouthY = 0.24f, mouthScale = 0.85f,
        cheekY = 0.06f, cheekDX = 0.58f, plainEyes = true, headTop = -1.0f, pupil = Color(0xFF26233D), ink = ink
    )
    override val bobScale = 2.2f
    override val shadowAlpha = 0.45f
    override val sparkleColor get() = glow

    private val bodyBrush = Brush.verticalGradient(colors = sheet.map { it.copy(alpha = 0.95f) }, startY = -r, endY = r)
    private val sheetMid = sheet[1]
    private val bodyPath = Path()
    private val wrapper = Path()
    private val glowBrush = Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0.18f), Color.Transparent), center = Offset.Zero, radius = 1.5f * r)
    private val layerPaint = Paint()
    private val layerRect = Rect(-2.2f * r, -2.4f * r, 2.2f * r, 2.2f * r)

    // ---- behaviour ----

    override val specialDur = 2.2f

    /** How visible it is during Vanish: 1 → almost gone → back. */
    private fun visibility(s: PetSim): Float {
        if (s.trick != Trick.SPECIAL) return 1f
        val t = s.trickT
        return when {
            t < 0.6f -> 1f - 0.92f * smooth(0f, 0.6f, t)
            t < 1.35f -> 0.08f + 0.04f * sin(t * 12f)
            else -> 0.08f + 0.92f * smooth(1.35f, 1.5f, t)
        }
    }

    override fun specialMood(s: PetSim): Mood = if (s.trickT < 1.35f) Mood.HAPPY else Mood.SURPRISED

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.3f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        if (crossed(prev, t, 0.3f) || crossed(prev, t, 0.7f) || crossed(prev, t, 1.1f)) {
            s.fxBurst(FX_WISP, 2, s.x, s.y, 60f, 0.9f, 5f, upBias = 30f)
        }
        s.mouthOpen = if (t in 1.35f..1.95f) 1f - smooth(1.75f, 1.95f, t) else 0f
        if (crossed(prev, t, 1.35f)) {
            s.say(s.lines.special2(), 1.4f)
            s.jump(280f)
            s.squash.vel -= 7f
            s.light()
            s.fxBurst(FX_WISP, 6, s.x, s.y, 170f, 0.8f, 5f)
        }
    }

    override fun workTick(s: PetSim) {
        val a = Random.nextFloat() * 2f * PI_F
        s.fx(FX_WISP, s.x + cos(a) * s.r * 1.1f, s.y + sin(a) * s.r * 0.6f, 0f, -28f, 1.5f, 4.5f, wobble = 20f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        s.fxBurst(FX_WISP, 8, s.x, s.y - s.r * 0.3f, 160f, 1.0f, 5f, upBias = 40f)
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_WISP, 3, s.x, s.y + s.r * 0.3f + s.hop, 60f, 0.6f, 3.5f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        if (variant == FX_WISP) {
            drawCircle(glow.copy(alpha = alpha * 0.45f), radius = 1f, center = Offset.Zero)
            drawCircle(Color.White.copy(alpha = alpha * 0.85f), radius = 0.45f, center = Offset.Zero)
        } else {
            drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        // a wrapped candy
        val c = Color(0xFFFF8FB8)
        drawCircle(c, radius = 0.55f, center = Offset.Zero)
        drawLine(Color.White.copy(alpha = 0.8f), Offset(-0.3f, -0.3f), Offset(0.3f, 0.3f), strokeWidth = 0.14f, cap = StrokeCap.Round)
        for (sgn in SIDES) {
            wrapper.reset()
            wrapper.moveTo(sgn * 0.5f, 0f)
            wrapper.lineTo(sgn * 1f, -0.4f)
            wrapper.lineTo(sgn * 1f, 0.4f)
            wrapper.close()
            drawPath(wrapper, Color(0xFFFFD3E4))
            drawPath(wrapper, ink, style = unitOutline)
        }
        drawCircle(ink, radius = 0.55f, center = Offset.Zero, style = unitOutline)
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val vis = visibility(s)
        if (vis < 0.99f) {
            layerPaint.alpha = vis
            drawIntoCanvas { it.saveLayer(layerRect, layerPaint) }
            drawGhost(s, mood)
            drawIntoCanvas { it.restore() }
        } else {
            drawGhost(s, mood)
        }
    }

    private fun DrawScope.drawGhost(s: PetSim, mood: Mood) {
        val t = s.animT
        // glow while thinking or showing off
        if (mood == Mood.THINKING || s.trick == Trick.SPECIAL) {
            val pulse = 0.6f + 0.4f * sin(t * 4f)
            drawCircle(glowBrush, radius = 1.5f * r, center = Offset.Zero, alpha = pulse)
        }
        drawArms(s, this@MistArt, mood, sheetMid, ink, ax = 0.86f, ay = 0.18f, len = 0.32f, thick = 0.22f)
        buildBody(s, t)
        drawPath(bodyPath, bodyBrush)
        if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.2f))
        drawPath(bodyPath, ink, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.7f), topLeft = Offset(-0.6f * r, -0.78f * r), size = Size(0.3f * r, 0.16f * r))
        drawFace(s, this@MistArt, mood)
    }

    /** Dome on top, a rippling four-scallop hem at the bottom that trails behind when it moves. */
    private fun buildBody(s: PetSim, t: Float) {
        val trail = (s.antenna.v * 0.012f).coerceIn(-0.35f, 0.35f) * r
        val hemY = 0.78f * r
        bodyPath.reset()
        bodyPath.moveTo(-0.92f * r, 0.3f * r)
        bodyPath.cubicTo(-0.92f * r, -0.6f * r, -0.5f * r, -0.98f * r, 0f, -0.98f * r)
        bodyPath.cubicTo(0.5f * r, -0.98f * r, 0.92f * r, -0.6f * r, 0.92f * r, 0.3f * r)
        bodyPath.lineTo(0.94f * r + trail, hemY + sin(t * 5f) * 0.05f * r)
        for (k in 1..4) {
            val x0 = 0.94f * r - (k - 1) * 0.47f * r + trail
            val x1 = 0.94f * r - k * 0.47f * r + trail
            val wob = sin(t * 5f + k * 1.3f) * 0.05f * r
            bodyPath.quadraticTo((x0 + x1) / 2f, hemY + 0.26f * r + wob, x1, hemY + wob * 0.5f)
        }
        bodyPath.close()
    }

    private companion object {
        const val FX_WISP = 1
        val SIDES = floatArrayOf(-1f, 1f)
    }
}
