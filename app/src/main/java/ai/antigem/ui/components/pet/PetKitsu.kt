package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Kitsu: a fox spirit with up to nine tails. The tails are the show: gathered to one side when calm, fanned into a
 * halo when happy (or angry, puffed up), curled around it asleep, waving when it says hi. Head reactions: the ears
 * flatten, droop, perk up and twitch with each blink. Foxfire wisps circle it while the agent works.
 * Signature move: "Tail fan" (the tails flare and throw rings of foxfire).
 */
internal class KitsuArt(d: Float, palette: Int, private val tails: Int) : PetArt(d) {
    private val fur: List<Color>
    private val cream: Color
    private val tip: Color
    private val innerEar: Color
    private val socks: Color
    private val ink: Color
    private val fire: Color

    init {
        when (palette) {
            1 -> { fur = listOf(Color(0xFFFFFFFF), Color(0xFFF1F3FF), Color(0xFFD9DEF5)); cream = Color(0xFFFFFFFF); tip = Color(0xFFC6B8FF); innerEar = Color(0xFFFFC2D6); socks = Color(0xFFD0D4EE); ink = Color(0xFF4A4870); fire = Color(0xFFB9A8FF) }
            2 -> { fur = listOf(Color(0xFFDCD0FF), Color(0xFFA894FF), Color(0xFF7E6BFF)); cream = Color(0xFFF3EEFF); tip = Color(0xFF9FF5FF); innerEar = Color(0xFFFFC2E6); socks = Color(0xFF5B4FD0); ink = Color(0xFF2E2A6B); fire = Color(0xFF9FF5FF) }
            3 -> { fur = listOf(Color(0xFF7A7394), Color(0xFF4B4560), Color(0xFF2E2A40)); cream = Color(0xFFCFC8E0); tip = Color(0xFFFFB347); innerEar = Color(0xFFFF9FB0); socks = Color(0xFF1E1A2A); ink = Color(0xFF15121F); fire = Color(0xFFFFB347) }
            else -> { fur = listOf(Color(0xFFFFC27E), Color(0xFFFF9A3C), Color(0xFFEE7420)); cream = Color(0xFFFFF3E0); tip = Color(0xFFFFFFFF); innerEar = Color(0xFFFFB9A0); socks = Color(0xFF6B3A1E); ink = Color(0xFF5A2E12); fire = Color(0xFF8AF0FF) }
        }
    }

    override val face = FaceSpec(eyeY = -0.14f, eyeW = 0.2f, eyeH = 0.24f, mouthY = 0.33f, mouthScale = 0.9f, cheekY = 0.14f, cheekDX = 0.62f, animalMouth = true, headTop = -1.05f, ink = ink)
    override val sparkleColor = Color(0xFFFFE9A8)

    private val furMid = fur[1]
    private val bodyBrush = Brush.verticalGradient(colors = fur, startY = -r, endY = r)
    private val tailStroke = Stroke(width = 1.5f * d)

    private val bodyPath = Path().apply {
        moveTo(0f, -0.95f * r)
        cubicTo(0.62f * r, -0.95f * r, 1.0f * r, -0.48f * r, 1.0f * r, 0.12f * r)
        cubicTo(1.0f * r, 0.7f * r, 0.6f * r, 0.98f * r, 0f, 0.98f * r)
        cubicTo(-0.6f * r, 0.98f * r, -1.0f * r, 0.7f * r, -1.0f * r, 0.12f * r)
        cubicTo(-1.0f * r, -0.48f * r, -0.62f * r, -0.95f * r, 0f, -0.95f * r)
        close()
    }

    /** The cream cheeks and muzzle, rising to a point between the eyes. */
    private val maskPath = Path().apply {
        moveTo(0f, 0.0f)
        cubicTo(0.22f * r, -0.06f * r, 0.62f * r, -0.06f * r, 0.99f * r, 0.12f * r)
        cubicTo(0.86f * r, 0.42f * r, 0.42f * r, 0.6f * r, 0f, 0.6f * r)
        cubicTo(-0.42f * r, 0.6f * r, -0.86f * r, 0.42f * r, -0.99f * r, 0.12f * r)
        cubicTo(-0.62f * r, -0.06f * r, -0.22f * r, -0.06f * r, 0f, 0.0f)
        close()
    }
    /** A fluffy chest tuft under the chin. */
    private val chestPath = Path().apply {
        moveTo(-0.36f * r, 0.62f * r)
        quadraticTo(-0.2f * r, 0.74f * r, -0.12f * r, 0.66f * r)
        quadraticTo(0f, 0.8f * r, 0.12f * r, 0.66f * r)
        quadraticTo(0.2f * r, 0.74f * r, 0.36f * r, 0.62f * r)
        quadraticTo(0.3f * r, 0.95f * r, 0f, 0.96f * r)
        quadraticTo(-0.3f * r, 0.95f * r, -0.36f * r, 0.62f * r)
        close()
    }

    /** Right ear around its base (0, 0); mirrored for the left one. */
    private val earPath = Path().apply {
        moveTo(-0.27f * r, 0.06f * r)
        quadraticTo(-0.08f * r, -0.5f * r, 0.12f * r, -0.74f * r)
        quadraticTo(0.3f * r, -0.36f * r, 0.29f * r, 0.08f * r)
        close()
    }
    private val earInner = Path().apply {
        moveTo(-0.14f * r, 0.02f * r)
        quadraticTo(-0.02f * r, -0.34f * r, 0.1f * r, -0.5f * r)
        quadraticTo(0.18f * r, -0.24f * r, 0.17f * r, 0.04f * r)
        close()
    }

    private val tailPath = Path()
    private val tipPath = Path()

    init {
        // One fluffy tail from its base (0, 0) up to a flicked tip at (0.1, -1.42); the white tip shares the edges.
        val lx = floatArrayOf(-0.16f, -0.6f, -0.5f, 0.1f).map { it * r }
        val ly = floatArrayOf(0f, -0.28f, -1.08f, -1.42f).map { it * r }
        val rx = floatArrayOf(0.1f, 0.42f, 0.6f, 0.16f).map { it * r }
        val ry = floatArrayOf(-1.42f, -1.12f, -0.32f, 0f).map { it * r }
        tailPath.moveTo(lx[0], ly[0])
        tailPath.cubicTo(lx[1], ly[1], lx[2], ly[2], lx[3], ly[3])
        tailPath.cubicTo(rx[1], ry[1], rx[2], ry[2], rx[3], ry[3])
        tailPath.close()
        val left = splitCubic(lx, ly, 0.84f) // second half: from the tip boundary to the tip
        val right = splitCubic(rx, ry, 0.2f) // first half: from the tip down to the boundary
        tipPath.moveTo(left[8], left[9])
        tipPath.cubicTo(left[10], left[11], left[12], left[13], lx[3], ly[3])
        tipPath.cubicTo(right[2], right[3], right[4], right[5], right[6], right[7])
        tipPath.quadraticTo((left[8] + right[6]) / 2f, (left[9] + right[7]) / 2f + 0.16f * r, left[8], left[9])
        tipPath.close()
    }

    // ---- behaviour ----

    override val specialDur = 2.2f

    override fun poseTarget(s: PetSim, mood: Mood): Float = when {
        s.trick == Trick.SPECIAL || s.trick == Trick.DANCE -> 1f
        mood == Mood.SLEEP -> -1f
        mood == Mood.HAPPY || mood == Mood.LOVE || mood == Mood.SURPRISED || mood == Mood.ANGRY -> 1f
        mood == Mood.THINKING -> 0.45f
        else -> 0f
    }

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 2f)
        s.jump(220f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        if (crossed(prev, t, 0.45f) || crossed(prev, t, 1.15f)) {
            s.light()
            val n = 7
            val off = if (t < 1f) 0f else PI_F / n
            for (i in 0 until n) {
                val a = off + i * 2f * PI_F / n
                s.fx(FX_FIRE, s.x, s.y + s.hop, cos(a) * 150f, sin(a) * 150f - 20f, 1.0f, 7f, drag = 2.2f, wobble = 10f)
            }
        }
        if (crossed(prev, t, 1.8f)) s.burst(P_SPARKLE, 6)
    }

    override fun workTick(s: PetSim) {
        s.fx(FX_FIRE, s.x + (Random.nextFloat() - 0.2f) * s.r * 1.6f, s.y - s.r * 0.9f, 0f, -34f, 1.4f, 5.5f, wobble = 22f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        for (i in 0 until 6) {
            val a = -PI_F / 2f + (i - 2.5f) * 0.42f
            s.fx(FX_FIRE, s.x, s.y - s.r * 0.6f, cos(a) * 190f, sin(a) * 190f, 1.1f, 7f, drag = 2f, wobble = 12f)
        }
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_GLINT, 4, s.x, s.y + s.hop + s.r * 0.3f, 80f, 0.45f, 3f)
    }

    override fun angryTick(s: PetSim) {
        s.fxBurst(FX_FIRE, 3, s.x, s.y - s.r, 70f, 0.7f, 5f, upBias = 60f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        if (variant == FX_FIRE) {
            drawFoxfire(alpha)
        } else {
            drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        // inari: a golden tofu pouch with rice peeking out
        drawOval(Color.White, topLeft = Offset(-0.8f, -0.78f), size = Size(1.6f, 0.62f))
        drawOval(Color(0xFFD9993A), topLeft = Offset(-1f, -0.55f), size = Size(2f, 1.25f))
        drawOval(Color(0xFFF0B85C), topLeft = Offset(-0.7f, -0.42f), size = Size(1.1f, 0.4f))
        drawOval(ink, topLeft = Offset(-1f, -0.55f), size = Size(2f, 1.25f), style = unitOutline)
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        drawTails(s, mood)
        drawFeet(s, this@KitsuArt, socks, ink, dx = 0.36f, y = 0.86f, w = 0.36f, h = 0.26f)
        drawEars(s, mood)
        drawPath(bodyPath, bodyBrush)
        drawPath(maskPath, cream)
        drawPath(chestPath, cream)
        if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.22f))
        drawPath(bodyPath, ink, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.45f), topLeft = Offset(-0.66f * r, -0.74f * r), size = Size(0.32f * r, 0.17f * r))
        // nose
        drawOval(ink, topLeft = Offset(-0.085f * r, 0.1f * r), size = Size(0.17f * r, 0.12f * r))
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 0.025f * r, center = Offset(-0.03f * r, 0.13f * r))
        drawFace(s, this@KitsuArt, mood)
        if (mood == Mood.THINKING) drawFoxfireRing(s)
    }

    private fun DrawScope.drawEars(s: PetSim, mood: Mood) {
        val t = s.animT
        val base = when (mood) {
            Mood.ANGRY -> 58f
            Mood.SLEEP -> 38f
            Mood.DIZZY -> 30f + sin(t * 9f) * 12f
            Mood.SURPRISED -> 2f
            Mood.THINKING -> 10f
            else -> 14f
        }
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            // a little flick on each blink, the right ear more
            val twitch = s.blink * (if (side == 1) 16f else 6f)
            val ang = base + twitch + s.antenna.v * 0.35f * sgn
            withTransform({
                translate(sgn * 0.5f * r, -0.66f * r)
                scale(sgn, 1f, Offset.Zero)
                rotate(ang, Offset.Zero)
            }) {
                drawPath(earPath, furMid)
                drawPath(earInner, innerEar)
                drawPath(earPath, ink, style = outlineStroke)
            }
        }
    }

    /** Mirrored toward the middle of the screen, so it never sticks out past the edge the pet is near. */
    private fun DrawScope.drawTails(s: PetSim, mood: Mood) {
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawTailsInner(s, mood) }
    }

    private fun DrawScope.drawTailsInner(s: PetSim, mood: Mood) {
        val t = s.animT
        val p = s.pose.v
        // calm: one bushy bundle up to the right; happy: a full fan; asleep: wrapped around the front
        var spread = if (p >= 0f) 70f + 140f * p else 70f + 10f * p
        val center = if (p >= 0f) 48f * (1f - p) else 48f - 72f * p
        if (s.trick == Trick.SPECIAL) spread *= 1f + 0.12f * sin(s.trickP() * PI_F * 4f)
        val puff = if (mood == Mood.ANGRY) 1.3f else if (s.trick == Trick.SPECIAL) 1.12f else 1f
        val n = tails
        val sway = when {
            mood == Mood.THINKING -> 9f
            s.trick == Trick.DANCE -> 16f
            mood == Mood.SLEEP -> 2f
            else -> 5f
        }
        for (i in 0 until n) {
            val f = if (n == 1) 0.5f else i / (n - 1f)
            var ang = (if (n == 1) center + 10f else center - spread / 2f + spread * f) +
                sin(t * 2.2f + i * 0.7f) * sway - s.antenna.v * 0.5f
            if (mood == Mood.ANGRY) ang += sin(t * 40f + i) * 2.5f
            // the outermost right tail does the waving
            if (i == n - 1 && s.wave > 0f) ang += s.wave * (-30f + sin(t * 15f) * 28f)
            val len = 1.45f + (if (n > 1) 0.3f * (1f - kotlin.math.abs(f - 0.5f) * 2f) else 0.3f)
            withTransform({
                translate(0f, 0.4f * r)
                rotate(ang, Offset.Zero)
                scale(puff, len, Offset.Zero)
            }) {
                drawPath(tailPath, furMid)
                drawPath(tipPath, tip)
                drawPath(tailPath, ink, style = tailStroke)
            }
        }
    }

    private fun DrawScope.drawFoxfireRing(s: PetSim) {
        val t = s.animT
        for (i in 0..2) {
            val o = t * 2.4f + i * 2.094f
            val fx = cos(o) * 1.05f * r
            val fy = -1.05f * r + sin(o) * 0.3f * r
            val flick = 1f + 0.15f * sin(t * 13f + i * 2f)
            unit(fx, fy, 0.2f * r * flick, rot = sin(t * 7f + i) * 10f) { drawFoxfire(1f) }
        }
    }

    /** One foxfire in unit space: soft glow, the flame, a white-hot core. */
    private fun DrawScope.drawFoxfire(alpha: Float) {
        drawCircle(fire.copy(alpha = alpha * 0.22f), radius = 1.15f, center = Offset(0f, 0.15f))
        drawPath(flame, fire.copy(alpha = alpha * 0.92f))
        withTransform({ translate(0f, 0.3f); scale(0.5f, 0.5f, Offset.Zero) }) { drawPath(flame, Color.White.copy(alpha = alpha * 0.95f)) }
    }

    private companion object {
        const val FX_FIRE = 1
    }
}

/**
 * Splits the cubic Bézier ([xs], [ys]: 4 points) at [t]: returns 16 numbers, the first half's 4 points (x, y) then the
 * second half's 4 points.
 */
internal fun splitCubic(xs: List<Float>, ys: List<Float>, t: Float): FloatArray {
    fun lerp(a: Float, b: Float) = a + (b - a) * t
    val x01 = lerp(xs[0], xs[1]); val y01 = lerp(ys[0], ys[1])
    val x12 = lerp(xs[1], xs[2]); val y12 = lerp(ys[1], ys[2])
    val x23 = lerp(xs[2], xs[3]); val y23 = lerp(ys[2], ys[3])
    val x012 = lerp(x01, x12); val y012 = lerp(y01, y12)
    val x123 = lerp(x12, x23); val y123 = lerp(y12, y23)
    val xm = lerp(x012, x123); val ym = lerp(y012, y123)
    return floatArrayOf(xs[0], ys[0], x01, y01, x012, y012, xm, ym, xm, ym, x123, y123, x23, y23, xs[3], ys[3])
}
