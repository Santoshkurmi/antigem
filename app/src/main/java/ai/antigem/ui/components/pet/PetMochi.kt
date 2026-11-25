package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mochi: a round, soft cat. Ears flatten and perk up, whiskers twitch, the tail swishes (straight up with a curl when
 * happy, puffed when annoyed, wrapped around its feet asleep). Optional collar bell. Paw prints while the agent works.
 * Signature move: "Stretch" (a long stretch and yawn, then a purr with little notes).
 */
internal class MochiArt(d: Float, palette: Int, private val bell: Boolean) : PetArt(d) {
    private val fur: List<Color>
    private val ink: Color
    private val innerEar: Color
    private val nose: Color
    private val stripes: Color?
    private val muzzle: Color?
    private val whisker: Color

    init {
        when (palette) {
            1 -> { fur = listOf(Color(0xFFFFDDB0), Color(0xFFFFB36B), Color(0xFFF08A3C)); ink = Color(0xFF6B3412); innerEar = Color(0xFFFFB8A0); nose = Color(0xFFFF8F8F); stripes = Color(0xFFC9601E); muzzle = Color(0xFFFFF1E0); whisker = Color(0xFF6B3412) }
            2 -> { fur = listOf(Color(0xFF5E5E6E), Color(0xFF3A3A48), Color(0xFF26262F)); ink = Color(0xFF15151C); innerEar = Color(0xFFFF9FB8); nose = Color(0xFFFF8FA8); stripes = null; muzzle = Color(0xFFFFFFFF); whisker = Color(0xFFE6E6EE) }
            3 -> { fur = listOf(Color(0xFFDDE1E8), Color(0xFFB4BAC8), Color(0xFF8E95A6)); ink = Color(0xFF3A3F4D); innerEar = Color(0xFFF5B7C8); nose = Color(0xFFE88FA0); stripes = Color(0xFF6E7486); muzzle = Color(0xFFF2F3F7); whisker = Color(0xFF3A3F4D) }
            else -> { fur = listOf(Color(0xFFFFFFFF), Color(0xFFF6F2FA), Color(0xFFE2DAEC)); ink = Color(0xFF4A3F5C); innerEar = Color(0xFFFFB8CC); nose = Color(0xFFFF8FA8); stripes = null; muzzle = null; whisker = Color(0xFF4A3F5C) }
        }
    }

    override val face = FaceSpec(
        eyeY = -0.14f, eyeDX = 0.38f, eyeW = 0.2f, eyeH = 0.23f, mouthY = 0.27f, mouthScale = 0.85f,
        cheekY = 0.1f, cheekDX = 0.66f, animalMouth = true, headTop = -0.95f, ink = ink
    )
    private val noteColor = Color(0xFFB48CFF)
    private val pawColor = Color(0xFFFF9FC0)

    private val furMid = fur[1]
    private val bodyBrush = Brush.verticalGradient(colors = fur, startY = -r, endY = r)
    private val tailW = 0.22f * r
    private val tailOutline = Stroke(width = tailW + 2.6f * d, cap = StrokeCap.Round)
    private val tailFill = Stroke(width = tailW, cap = StrokeCap.Round)
    private val tailOutlinePuff = Stroke(width = tailW * 1.6f + 2.6f * d, cap = StrokeCap.Round)
    private val tailFillPuff = Stroke(width = tailW * 1.6f, cap = StrokeCap.Round)
    private val stripeStroke = Stroke(width = 0.07f * r, cap = StrokeCap.Round)

    private val bodyPath = Path().apply {
        moveTo(0f, -0.86f * r)
        cubicTo(0.66f * r, -0.86f * r, 1.05f * r, -0.45f * r, 1.05f * r, 0.18f * r)
        cubicTo(1.05f * r, 0.74f * r, 0.62f * r, 0.96f * r, 0f, 0.96f * r)
        cubicTo(-0.62f * r, 0.96f * r, -1.05f * r, 0.74f * r, -1.05f * r, 0.18f * r)
        cubicTo(-1.05f * r, -0.45f * r, -0.66f * r, -0.86f * r, 0f, -0.86f * r)
        close()
    }
    private val muzzlePath = Path().apply {
        moveTo(0f, 0.02f * r)
        cubicTo(0.3f * r, -0.04f * r, 0.62f * r, 0.08f * r, 0.7f * r, 0.36f * r)
        cubicTo(0.72f * r, 0.7f * r, 0.4f * r, 0.94f * r, 0f, 0.94f * r)
        cubicTo(-0.4f * r, 0.94f * r, -0.72f * r, 0.7f * r, -0.7f * r, 0.36f * r)
        cubicTo(-0.62f * r, 0.08f * r, -0.3f * r, -0.04f * r, 0f, 0.02f * r)
        close()
    }
    /** Right ear around its base (0, 0); mirrored for the left. */
    private val earPath = Path().apply {
        moveTo(-0.26f * r, 0.08f * r)
        quadraticTo(-0.1f * r, -0.36f * r, 0.08f * r, -0.56f * r)
        quadraticTo(0.26f * r, -0.24f * r, 0.27f * r, 0.1f * r)
        close()
    }
    private val earInner = Path().apply {
        moveTo(-0.13f * r, 0.04f * r)
        quadraticTo(-0.04f * r, -0.24f * r, 0.07f * r, -0.38f * r)
        quadraticTo(0.16f * r, -0.16f * r, 0.15f * r, 0.06f * r)
        close()
    }
    private val nosePath = Path().apply {
        moveTo(-0.07f * r, 0.1f * r)
        lineTo(0.07f * r, 0.1f * r)
        lineTo(0f, 0.18f * r)
        close()
    }
    private val note = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(-0.75f, 0.2f, 0.05f, 0.85f))
        moveTo(-0.05f, 0.5f)
        lineTo(-0.05f, -0.9f)
        lineTo(0.6f, -0.6f)
        lineTo(0.6f, -0.35f)
        lineTo(0.05f, -0.6f)
        lineTo(0.05f, 0.5f)
        close()
    }
    private val tailPath = Path()

    // ---- behaviour ----

    override val specialDur = 2.5f

    override fun poseTarget(s: PetSim, mood: Mood): Float = when {
        mood == Mood.SLEEP -> -1f
        s.trick == Trick.SPECIAL || mood == Mood.HAPPY || mood == Mood.LOVE -> 1f
        mood == Mood.ANGRY || mood == Mood.SURPRISED -> 0.6f
        else -> 0f
    }

    override fun specialMood(s: PetSim): Mood = if (s.trickT < 0.95f) Mood.NORMAL else Mood.HAPPY

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 2.2f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        s.mouthOpen = stretchAmount(t) * 0.9f
        if (t > 1f) {
            s.tilt.vel += sin(t * 70f) * 30f // the purr
            var k = 1.05f
            while (k < 2.3f) {
                if (crossed(prev, t, k)) {
                    s.light()
                    s.fx(FX_NOTE, s.x + (Random.nextFloat() - 0.5f) * s.r, s.y - s.r * 0.9f, (Random.nextFloat() - 0.5f) * 40f, -55f, 1.2f, 5f, wobble = 16f, rot = (Random.nextFloat() - 0.5f) * 30f)
                }
                k += 0.4f
            }
        }
        if (crossed(prev, t, 2.2f)) s.burst(P_HEART, 3)
    }

    private fun stretchAmount(t: Float) = smooth(0.05f, 0.4f, t) * (1f - smooth(0.7f, 0.95f, t))

    override fun workTick(s: PetSim) {
        s.fx(FX_PAW, s.x + (Random.nextFloat() - 0.5f) * s.r * 2.2f, s.y - s.r * (1.2f + Random.nextFloat() * 0.5f), 0f, -10f, 1.6f, 4.5f, rot = (Random.nextFloat() - 0.5f) * 50f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        s.fxBurst(FX_NOTE, 4, s.x, s.y - s.r * 0.6f, 120f, 1.0f, 5f, upBias = 60f)
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_GLINT, 3, s.x, s.y + s.r * 0.3f + s.hop, 70f, 0.45f, 3f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        when (variant) {
            FX_NOTE -> drawPath(note, noteColor.copy(alpha = alpha))
            FX_PAW -> {
                val c = pawColor.copy(alpha = alpha * 0.85f)
                drawOval(c, topLeft = Offset(-0.45f, -0.05f), size = Size(0.9f, 0.75f))
                drawCircle(c, radius = 0.2f, center = Offset(-0.55f, -0.3f))
                drawCircle(c, radius = 0.2f, center = Offset(-0.2f, -0.62f))
                drawCircle(c, radius = 0.2f, center = Offset(0.2f, -0.62f))
                drawCircle(c, radius = 0.2f, center = Offset(0.55f, -0.3f))
            }
            else -> drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        // a little milk bottle
        drawRoundRect(Color.White, topLeft = Offset(-0.5f, -0.55f), size = Size(1f, 1.4f), cornerRadius = CornerRadius(0.3f))
        drawRoundRect(Color(0xFF5DA9FF), topLeft = Offset(-0.32f, -0.9f), size = Size(0.64f, 0.4f), cornerRadius = CornerRadius(0.1f))
        drawRoundRect(Color(0xFFBFDFFF), topLeft = Offset(-0.5f, 0.05f), size = Size(1f, 0.32f))
        drawRoundRect(ink, topLeft = Offset(-0.5f, -0.55f), size = Size(1f, 1.4f), cornerRadius = CornerRadius(0.3f), style = unitOutline)
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val t = s.animT
        val stretch = if (s.trick == Trick.SPECIAL) stretchAmount(s.trickT) else 0f
        withTransform({
            // the stretch: long and low, pivoting on the feet
            scale(1f + 0.22f * stretch, 1f - 0.16f * stretch, Offset(0f, 0.9f * r))
        }) {
            drawTail(s, mood, t)
            drawFeet(s, this@MochiArt, muzzle ?: furMid, ink, dx = 0.4f, y = 0.86f, w = 0.38f, h = 0.26f)
            drawEars(s, mood, t)
            drawPath(bodyPath, bodyBrush)
            muzzle?.let { drawPath(muzzlePath, it) }
            if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.2f))
            stripes?.let { st ->
                drawLine(st, Offset(0f, -0.82f * r), Offset(0f, -0.62f * r), strokeWidth = 0.07f * r, cap = StrokeCap.Round)
                drawLine(st, Offset(-0.18f * r, -0.8f * r), Offset(-0.14f * r, -0.64f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
                drawLine(st, Offset(0.18f * r, -0.8f * r), Offset(0.14f * r, -0.64f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
                for (sgn in SIDES) {
                    drawLine(st, Offset(sgn * 1.03f * r, 0.0f), Offset(sgn * 0.82f * r, 0.04f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
                    drawLine(st, Offset(sgn * 1.03f * r, 0.18f * r), Offset(sgn * 0.84f * r, 0.2f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
                }
            }
            drawPath(bodyPath, ink, style = outlineStroke)
            drawOval(Color.White.copy(alpha = 0.5f), topLeft = Offset(-0.7f * r, -0.68f * r), size = Size(0.32f * r, 0.16f * r))
            if (bell) drawCollar(s)
            drawArms(s, this@MochiArt, mood, muzzle ?: furMid, ink, ax = 0.74f, ay = 0.42f, len = 0.36f, thick = 0.24f)
            drawPath(nosePath, nose)
            drawFace(s, this@MochiArt, mood)
            drawWhiskers(s, t)
        }
    }

    private fun DrawScope.drawEars(s: PetSim, mood: Mood, t: Float) {
        val base = when (mood) {
            Mood.ANGRY -> 62f
            Mood.SLEEP -> 24f
            Mood.DIZZY -> 28f + sin(t * 9f) * 10f
            Mood.SURPRISED -> 0f
            else -> 12f
        }
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            val ang = base + s.blink * (if (side == 0) 14f else 4f) + s.antenna.v * 0.3f * sgn
            withTransform({
                translate(sgn * 0.55f * r, -0.62f * r)
                scale(sgn, 1f, Offset.Zero)
                rotate(ang, Offset.Zero)
            }) {
                drawPath(earPath, furMid)
                drawPath(earInner, innerEar)
                drawPath(earPath, ink, style = outlineStroke)
            }
        }
    }

    private fun DrawScope.drawWhiskers(s: PetSim, t: Float) {
        val twitch = s.antenna.v * 0.004f * r + sin(t * 3f) * 0.01f * r
        for (sgn in SIDES) for (k in 0..2) {
            val y0 = 0.14f * r + k * 0.07f * r
            val y1 = 0.02f * r + k * 0.14f * r + twitch
            drawLine(whisker.copy(alpha = 0.75f), Offset(sgn * 0.55f * r, y0), Offset(sgn * 1.2f * r, y1), strokeWidth = 1.1f * d, cap = StrokeCap.Round)
        }
    }

    private fun DrawScope.drawCollar(s: PetSim) {
        drawLine(Color(0xFFE5484D), Offset(-0.78f * r, 0.5f * r), Offset(0.78f * r, 0.5f * r), strokeWidth = 0.12f * r, cap = StrokeCap.Round)
        val bx = s.antenna.v * 0.004f * r
        val bc = Offset(bx, 0.64f * r)
        drawCircle(Color(0xFFFFCF4A), radius = 0.12f * r, center = bc)
        drawLine(Color(0xFF9A6A10), bc + Offset(-0.06f * r, 0.03f * r), bc + Offset(0.06f * r, 0.03f * r), strokeWidth = 1.2f * d, cap = StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 0.035f * r, center = bc + Offset(-0.04f * r, -0.04f * r))
        drawCircle(ink, radius = 0.12f * r, center = bc, style = thinStroke)
    }

    /** One cubic from the hip; its shape blends calm (side), happy (up with a curl) and asleep (around the feet). */
    /** Mirrored toward the middle of the screen, so it never sticks out past the edge the pet is near. */
    private fun DrawScope.drawTail(s: PetSim, mood: Mood, t: Float) {
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawTailInner(s, mood, t) }
    }

    private fun DrawScope.drawTailInner(s: PetSim, mood: Mood, t: Float) {
        val p = s.pose.v
        val sway = sin(t * (if (mood == Mood.THINKING) 4.5f else 2.4f)) * 0.18f * r - s.antenna.v * 0.012f * r
        // control points: calm, then blended toward up (p > 0) or curled (p < 0)
        var c1x = 1.25f; var c1y = 0.7f; var c2x = 1.45f; var c2y = 0.1f; var ex = 1.2f; var ey = -0.35f
        if (p > 0f) {
            c1x += (1.05f - c1x) * p; c1y += (0.3f - c1y) * p
            c2x += (1.1f - c2x) * p; c2y += (-0.5f - c2y) * p
            ex += (0.8f - ex) * p; ey += (-0.82f - ey) * p
        } else if (p < 0f) {
            val q = -p
            c1x += (0.95f - c1x) * q; c1y += (1.08f - c1y) * q
            c2x += (0.2f - c2x) * q; c2y += (1.18f - c2y) * q
            ex += (-0.4f - ex) * q; ey += (0.98f - ey) * q
        }
        tailPath.reset()
        tailPath.moveTo(0.7f * r, 0.62f * r)
        tailPath.cubicTo(c1x * r, c1y * r, c2x * r + sway * 0.5f, c2y * r, ex * r + sway, ey * r)
        val puff = mood == Mood.ANGRY
        drawPath(tailPath, ink, style = if (puff) tailOutlinePuff else tailOutline)
        drawPath(tailPath, furMid, style = if (puff) tailFillPuff else tailFill)
        stripes?.let {
            // a dark tip on striped cats
            drawCircle(it, radius = tailW * 0.5f, center = Offset(ex * r + sway, ey * r))
        }
    }

    private companion object {
        const val FX_NOTE = 1
        const val FX_PAW = 2
        val SIDES = floatArrayOf(-1f, 1f)
    }
}
