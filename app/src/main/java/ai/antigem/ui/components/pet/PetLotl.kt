package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lotl: a smiley axolotl with a wide head, three feathery gills on each side and a little fin tail. The gills wiggle
 * (fluff up when happy, flare when annoyed), bubbles rise while the agent works. Signature move: "Bubble" (blows a big
 * bubble that wobbles and pops into little ones).
 */
internal class LotlArt(d: Float, palette: Int) : PetArt(d) {
    private val skin: List<Color>
    private val gill: Color
    private val gillDark: Color
    private val bellyC: Color
    private val ink: Color
    private val spots: Boolean

    init {
        when (palette) {
            1 -> { skin = listOf(Color(0xFFDDE8C8), Color(0xFFB7C99A), Color(0xFF8FA474)); gill = Color(0xFFD06C8C); gillDark = Color(0xFFA54766); bellyC = Color(0xFFEEF2DE); ink = Color(0xFF3A4A2A); spots = true }
            2 -> { skin = listOf(Color(0xFFFFF3CC), Color(0xFFFFE39A), Color(0xFFF5C04A)); gill = Color(0xFFFF8A5B); gillDark = Color(0xFFD9603A); bellyC = Color(0xFFFFF8E0); ink = Color(0xFF6E4A12); spots = false }
            3 -> { skin = listOf(Color(0xFFE4F2FF), Color(0xFFBFE0FF), Color(0xFF8FC0F5)); gill = Color(0xFF5C7BFF); gillDark = Color(0xFF3A55D0); bellyC = Color(0xFFF0F8FF); ink = Color(0xFF1E3A6E); spots = false }
            else -> { skin = listOf(Color(0xFFFFE6EE), Color(0xFFFFC2D6), Color(0xFFFF9CBC)); gill = Color(0xFFFF5C8A); gillDark = Color(0xFFD93A6A); bellyC = Color(0xFFFFF0F5); ink = Color(0xFF7A2A4A); spots = false }
        }
    }

    override val face = FaceSpec(
        eyeY = -0.14f, eyeDX = 0.5f, eyeW = 0.075f, eyeH = 0.09f, mouthY = 0.14f, mouthScale = 1.5f,
        cheekY = 0.0f, cheekDX = 0.66f, plainEyes = true, headTop = -0.9f, pupil = Color(0xFF241B2B), cheek = Color(0xFFFF7FA8), ink = ink
    )

    private val bubbleC = Color(0xFF8FD3FF)
    private val skinMid = skin[1]
    private val bodyBrush = Brush.verticalGradient(colors = skin, startY = -r, endY = r)
    private val bubbleStroke = Stroke(width = 1.4f * d)

    private val bodyPath = Path().apply {
        moveTo(0f, -0.82f * r)
        cubicTo(0.72f * r, -0.82f * r, 1.08f * r, -0.45f * r, 1.08f * r, 0.12f * r)
        cubicTo(1.08f * r, 0.72f * r, 0.65f * r, 0.96f * r, 0f, 0.96f * r)
        cubicTo(-0.65f * r, 0.96f * r, -1.08f * r, 0.72f * r, -1.08f * r, 0.12f * r)
        cubicTo(-1.08f * r, -0.45f * r, -0.72f * r, -0.82f * r, 0f, -0.82f * r)
        close()
    }
    /** One gill pointing right from (0, 0): a stalk with a frilly edge. */
    private val gillPath = Path().apply {
        moveTo(0f, -0.07f * r)
        quadraticTo(0.3f * r, -0.13f * r, 0.5f * r, -0.1f * r)
        quadraticTo(0.62f * r, -0.04f * r, 0.56f * r, 0.04f * r)
        quadraticTo(0.3f * r, 0.12f * r, 0f, 0.07f * r)
        close()
    }
    private val tailPath = Path()
    private val shrimp = Path().apply {
        moveTo(0.7f, -0.6f)
        cubicTo(-0.4f, -1.0f, -1.0f, 0.2f, -0.2f, 0.75f)
        lineTo(0.05f, 0.45f)
        cubicTo(-0.5f, 0.1f, -0.2f, -0.55f, 0.55f, -0.25f)
        close()
    }

    // ---- behaviour ----

    override val specialDur = 2.3f

    override fun poseTarget(s: PetSim, mood: Mood): Float = when (mood) {
        Mood.HAPPY, Mood.LOVE -> 1f
        Mood.ANGRY, Mood.SURPRISED -> 0.7f
        Mood.SLEEP -> -0.6f
        else -> 0f
    }

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.5f)
    }

    override fun specialMood(s: PetSim): Mood = if (s.trickT < 1.65f) Mood.NORMAL else Mood.HAPPY

    /** The big bubble's radius (in r) while it grows, 0 when there is none. */
    private fun bubbleR(t: Float): Float = if (t < 0.25f || t >= 1.65f) 0f else 0.85f * smooth(0.25f, 1.5f, t)

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        s.mouthOpen = if (t in 0.2f..1.65f) 0.35f else 0f
        if (crossed(prev, t, 1.65f)) {
            s.say(s.lines.special2(), 1.2f)
            s.light()
            s.squash.vel -= 5f
            val br = 0.85f * s.r
            val cx = s.x
            val cy = s.y + s.hop + 0.35f * s.r // the bubble's center when it pops (see drawCharacter)
            for (i in 0 until 9) {
                val a = i * 2f * PI_F / 9f
                s.fx(FX_BUBBLE, cx + cos(a) * br, cy + sin(a) * br, cos(a) * 110f, sin(a) * 110f - 30f, 1.1f, 3.5f + Random.nextFloat() * 2f, drag = 2f, wobble = 14f)
            }
        }
    }

    override fun workTick(s: PetSim) {
        s.fx(FX_BUBBLE, s.x + (Random.nextFloat() - 0.5f) * s.r * 0.8f, s.y - s.r * 0.6f, 0f, -42f, 1.7f, 3.5f + Random.nextFloat() * 1.5f, wobble = 24f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        repeat(8) {
            s.fx(FX_BUBBLE, s.x + (Random.nextFloat() - 0.5f) * s.r * 1.6f, s.y, (Random.nextFloat() - 0.5f) * 60f, -(90f + Random.nextFloat() * 90f), 1.2f, 3f + Random.nextFloat() * 3f, wobble = 18f)
        }
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_BUBBLE, 3, s.x, s.y + s.hop, 60f, 0.7f, 3f, upBias = 40f)
    }

    override fun angryTick(s: PetSim) {
        s.fxBurst(FX_BUBBLE, 4, s.x, s.y - s.r * 0.5f, 120f, 0.7f, 3f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        if (variant == FX_BUBBLE) {
            drawCircle(bubbleC.copy(alpha = alpha * 0.18f), radius = 1f, center = Offset.Zero)
            drawCircle(bubbleC.copy(alpha = alpha * 0.9f), radius = 1f, center = Offset.Zero, style = unitThin)
            drawCircle(Color.White.copy(alpha = alpha * 0.9f), radius = 0.25f, center = Offset(-0.38f, -0.38f))
        } else {
            drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        drawPath(shrimp, Color(0xFFFF9C8A))
        drawPath(shrimp, Color(0xFFB4473A), style = unitOutline)
        drawCircle(Color(0xFF241B2B), radius = 0.09f, center = Offset(0.45f, -0.52f))
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val t = s.animT
        drawTail(s, t)
        drawFeet(s, this@LotlArt, skin[2], ink, dx = 0.48f, y = 0.86f, w = 0.32f, h = 0.24f)
        drawGills(s, mood, t)
        drawPath(bodyPath, bodyBrush)
        drawOval(bellyC, topLeft = Offset(-0.62f * r, 0.32f * r), size = Size(1.24f * r, 0.6f * r))
        if (spots) {
            drawCircle(ink.copy(alpha = 0.3f), radius = 0.06f * r, center = Offset(-0.2f * r, -0.6f * r))
            drawCircle(ink.copy(alpha = 0.3f), radius = 0.05f * r, center = Offset(0.25f * r, -0.52f * r))
            drawCircle(ink.copy(alpha = 0.3f), radius = 0.04f * r, center = Offset(0.62f * r, -0.3f * r))
            drawCircle(ink.copy(alpha = 0.3f), radius = 0.05f * r, center = Offset(-0.7f * r, -0.2f * r))
        }
        if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.2f))
        drawPath(bodyPath, ink, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.55f), topLeft = Offset(-0.7f * r, -0.66f * r), size = Size(0.34f * r, 0.15f * r))
        drawArms(s, this@LotlArt, mood, skinMid, ink, ax = 0.92f, ay = 0.38f, len = 0.3f, thick = 0.18f)
        drawFace(s, this@LotlArt, mood)
        val br = if (s.trick == Trick.SPECIAL) bubbleR(s.trickT) else 0f
        if (br > 0f) {
            // the big bubble, growing in front of the mouth (see-through) and wobbling
            val wob = 1f + 0.05f * sin(s.trickT * 14f)
            val cy = (0.14f + 0.25f * br) * r
            withTransform({ scale(wob, 2f - wob, Offset(0f, cy)) }) {
                drawCircle(bubbleC.copy(alpha = 0.18f), radius = br * r, center = Offset(0f, cy))
                drawCircle(bubbleC, radius = br * r, center = Offset(0f, cy), style = bubbleStroke)
                drawCircle(Color.White.copy(alpha = 0.85f), radius = br * 0.22f * r, center = Offset(-br * 0.42f * r, cy - br * 0.42f * r))
            }
        }
    }

    private fun DrawScope.drawGills(s: PetSim, mood: Mood, t: Float) {
        val p = s.pose.v
        val fluff = 1f + 0.18f * p.coerceAtLeast(0f)
        val spread = 1f + 0.3f * p
        val color = if (mood == Mood.ANGRY) gillDark else gill
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            for (k in 0..2) {
                val base = (-48f + k * 34f) * spread
                val wig = sin(t * 4f + k * 1.1f + side * 0.6f) * 9f + s.antenna.v * 0.4f * sgn
                withTransform({
                    translate(sgn * 0.84f * r, (-0.34f + k * 0.16f) * r)
                    scale(sgn * fluff, fluff, Offset.Zero)
                    rotate(base + wig, Offset.Zero)
                }) {
                    drawPath(gillPath, color)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(0.05f * r, 0f), Offset(0.45f * r, -0.04f * r), strokeWidth = 0.03f * r, cap = StrokeCap.Round)
                    drawPath(gillPath, ink, style = thinStroke)
                }
            }
        }
    }

    /** Mirrored toward the middle of the screen, so it never sticks out past the edge the pet is near. */
    private fun DrawScope.drawTail(s: PetSim, t: Float) {
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawTailInner(s, t) }
    }

    private fun DrawScope.drawTailInner(s: PetSim, t: Float) {
        val sway = sin(t * 2.6f) * 0.12f * r - s.antenna.v * 0.012f * r
        tailPath.reset()
        tailPath.moveTo(0.55f * r, 0.55f * r)
        tailPath.quadraticTo(1.25f * r, 0.85f * r + sway * 0.5f, 1.5f * r, 0.3f * r + sway)
        tailPath.quadraticTo(1.2f * r, 0.32f * r + sway * 0.5f, 0.7f * r, 0.2f * r)
        tailPath.close()
        drawPath(tailPath, skin[2])
        drawPath(tailPath, ink, style = thinStroke)
    }

    private companion object {
        const val FX_BUBBLE = 1
    }
}
