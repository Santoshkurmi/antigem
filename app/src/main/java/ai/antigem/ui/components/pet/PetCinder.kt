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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cinder: a baby dragon with little horns, a belly plate, bat wings and a spade-tipped tail. The wings flap while it
 * flies (held, flung or hopping), smoke puffs from its nose while the agent works and when it's annoyed, and a tiny
 * fang shows when it grins. Signature move: "Fire puff" (it breathes in, then a stream of flames toward the middle of
 * the screen, then smoke).
 */
internal class CinderArt(d: Float, palette: Int) : PetArt(d) {
    private val scales: List<Color>
    private val belly: Color
    private val horn: Color
    private val wing: Color
    private val ink: Color

    init {
        when (palette) {
            1 -> { scales = listOf(Color(0xFFC2F7D8), Color(0xFF5FD394), Color(0xFF2E9E63)); belly = Color(0xFFF3F8C8); horn = Color(0xFFFFF8DC); wing = Color(0xFF8FE6B0); ink = Color(0xFF13422A) }
            2 -> { scales = listOf(Color(0xFFCDE6FF), Color(0xFF7FB2FF), Color(0xFF3E6FE0)); belly = Color(0xFFE2F1FF); horn = Color(0xFFFFF6E0); wing = Color(0xFF9CC4FF); ink = Color(0xFF152B5E) }
            3 -> { scales = listOf(Color(0xFFE8D4FF), Color(0xFFB38AFF), Color(0xFF7F4FD9)); belly = Color(0xFFFFE3F6); horn = Color(0xFFFFF2DC); wing = Color(0xFFC8A8FF); ink = Color(0xFF32185E) }
            else -> { scales = listOf(Color(0xFFFFBC9A), Color(0xFFFF7A4F), Color(0xFFD9472E)); belly = Color(0xFFFFE2B0); horn = Color(0xFFFFF1D6); wing = Color(0xFFFF9A6B); ink = Color(0xFF5A1E12) }
        }
    }

    override val face = FaceSpec(eyeY = -0.18f, eyeDX = 0.35f, eyeW = 0.21f, eyeH = 0.25f, mouthY = 0.3f, cheekY = 0.08f, cheekDX = 0.62f, headTop = -1.05f, ink = ink)

    private val fireOuter = Color(0xFFFF8A3D)
    private val fireCore = Color(0xFFFFE27A)
    private val smoke = Color(0xFFB9B9C8)
    private val scaleMid = scales[1]
    private val bodyBrush = Brush.verticalGradient(colors = scales, startY = -r, endY = r)
    private val tailW = 0.2f * r
    private val tailOutline = Stroke(width = tailW + 2.6f * d, cap = StrokeCap.Round)
    private val tailFill = Stroke(width = tailW, cap = StrokeCap.Round)

    private val bodyPath = Path().apply {
        moveTo(0f, -0.98f * r)
        cubicTo(0.58f * r, -0.98f * r, 0.98f * r, -0.4f * r, 0.98f * r, 0.2f * r)
        cubicTo(0.98f * r, 0.74f * r, 0.6f * r, 0.98f * r, 0f, 0.98f * r)
        cubicTo(-0.6f * r, 0.98f * r, -0.98f * r, 0.74f * r, -0.98f * r, 0.2f * r)
        cubicTo(-0.98f * r, -0.4f * r, -0.58f * r, -0.98f * r, 0f, -0.98f * r)
        close()
    }
    /** Right wing from its root (0, 0): two scallops along the trailing edge. */
    private val wingPath = Path().apply {
        moveTo(0f, 0.05f * r)
        quadraticTo(0.35f * r, -0.62f * r, 0.98f * r, -0.62f * r)
        lineTo(0.84f * r, -0.32f * r)
        quadraticTo(0.78f * r, -0.12f * r, 0.6f * r, -0.08f * r)
        quadraticTo(0.5f * r, 0.12f * r, 0.3f * r, 0.12f * r)
        quadraticTo(0.15f * r, 0.2f * r, 0f, 0.05f * r)
        close()
    }
    private val wingBone = Path().apply {
        moveTo(0.05f * r, 0f)
        quadraticTo(0.4f * r, -0.5f * r, 0.96f * r, -0.6f * r)
        moveTo(0.45f * r, -0.43f * r)
        lineTo(0.6f * r, -0.08f * r)
    }
    /** Right horn from its base (0, 0). */
    private val hornPath = Path().apply {
        moveTo(-0.11f * r, 0.04f * r)
        quadraticTo(-0.02f * r, -0.2f * r, 0.12f * r, -0.36f * r)
        quadraticTo(0.14f * r, -0.12f * r, 0.12f * r, 0.06f * r)
        close()
    }
    private val spade = Path().apply {
        moveTo(0f, -1f)
        quadraticTo(0.9f, -0.2f, 0.25f, 0.5f)
        lineTo(0f, 0.25f)
        lineTo(-0.25f, 0.5f)
        quadraticTo(-0.9f, -0.2f, 0f, -1f)
        close()
    }
    private val chili = Path().apply {
        moveTo(-0.7f, -0.55f)
        quadraticTo(0.3f, -0.6f, 0.75f, 0.2f)
        quadraticTo(0.95f, 0.7f, 0.55f, 0.8f)
        quadraticTo(0.35f, 0.1f, -0.75f, -0.2f)
        close()
    }
    private val fang = Path().apply {
        moveTo(0.09f * r, 0.3f * r)
        lineTo(0.15f * r, 0.3f * r)
        lineTo(0.12f * r, 0.39f * r)
        close()
    }
    private val tailPath = Path()

    // ---- behaviour ----

    override val specialDur = 1.9f

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.7f)
    }

    override fun specialMood(s: PetSim): Mood = if (s.trickT < 0.6f) Mood.SURPRISED else Mood.HAPPY

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        s.mouthOpen = if (t in 0.6f..1.35f) 1f else 0f
        if (crossed(prev, t, 0.6f)) {
            s.light()
            s.squash.vel -= 5f
        }
        if (t in 0.6f..1.35f) {
            // a flame every 1/20 s while breathing
            val k0 = (prev * 20f).toInt()
            val k1 = (t * 20f).toInt()
            if (k1 > k0) {
                val dir = s.facing()
                s.fx(
                    FX_FIRE, s.x + dir * 0.3f * s.r, s.y + 0.3f * s.r + s.hop,
                    dir * (240f + Random.nextFloat() * 120f), (Random.nextFloat() - 0.6f) * 70f,
                    0.55f, 6f + Random.nextFloat() * 3f, drag = 1.4f, rot = dir * 90f
                )
            }
        }
        if (crossed(prev, t, 1.45f)) puff(s, 3)
    }

    private fun puff(s: PetSim, n: Int) {
        repeat(n) {
            s.fx(FX_SMOKE, s.x + (Random.nextFloat() - 0.5f) * 0.3f * s.r, s.y + 0.1f * s.r + s.hop, (Random.nextFloat() - 0.5f) * 30f, -(40f + Random.nextFloat() * 30f), 1.2f, 5f, drag = 1f, wobble = 12f)
        }
    }

    override fun workTick(s: PetSim) {
        s.fx(FX_SMOKE, s.x + (Random.nextFloat() - 0.5f) * 0.2f * s.r, s.y + 0.08f * s.r, (Random.nextFloat() - 0.5f) * 10f, -30f, 1.6f, 4.5f, drag = 0.4f, wobble = 10f, slow = true)
    }

    override fun angryTick(s: PetSim) = puff(s, 3)

    override fun celebrate(s: PetSim) {
        repeat(6) {
            s.fx(FX_FIRE, s.x, s.y - 0.5f * s.r, (Random.nextFloat() - 0.5f) * 160f, -(200f + Random.nextFloat() * 100f), 0.7f, 6f, gravity = 300f, drag = 0.8f, rot = 0f)
        }
    }

    override fun crumbs(s: PetSim) = puff(s, 2)

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        when (variant) {
            FX_FIRE -> {
                drawCircle(fireOuter.copy(alpha = alpha * 0.2f), radius = 1.1f, center = Offset(0f, 0.15f))
                drawPath(flame, fireOuter.copy(alpha = alpha))
                withTransform({ translate(0f, 0.25f); scale(0.55f, 0.55f, Offset.Zero) }) { drawPath(flame, fireCore.copy(alpha = alpha)) }
            }
            FX_SMOKE -> drawCircle(smoke.copy(alpha = alpha * 0.6f), radius = 0.9f, center = Offset.Zero)
            else -> drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        drawPath(chili, Color(0xFFE53935))
        drawPath(chili, Color(0xFF7A1414), style = unitOutline)
        drawLine(Color(0xFF3FA34D), Offset(-0.72f, -0.4f), Offset(-0.95f, -0.75f), strokeWidth = 0.2f, cap = StrokeCap.Round)
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val t = s.animT
        val inhale = if (s.trick == Trick.SPECIAL) smooth(0f, 0.55f, s.trickT) * (1f - smooth(0.55f, 0.7f, s.trickT)) else 0f
        drawTail(s, mood, t)
        drawWings(s, mood, t)
        drawFeet(s, this@CinderArt, scales[2], ink, dx = 0.42f, y = 0.86f, w = 0.42f, h = 0.3f)
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            withTransform({
                translate(sgn * 0.4f * r, -0.8f * r)
                scale(sgn, 1f, Offset.Zero)
                rotate(8f + s.antenna.v * 0.15f, Offset.Zero)
            }) {
                drawPath(hornPath, horn)
                drawPath(hornPath, ink, style = thinStroke)
            }
        }
        withTransform({ scale(1f + inhale * 0.1f, 1f + inhale * 0.08f, Offset(0f, 0.9f * r)) }) {
            drawPath(bodyPath, bodyBrush)
            // belly plate with its bands
            drawOval(belly, topLeft = Offset(-0.5f * r, 0.0f), size = Size(1.0f * r, 0.95f * r))
            for (k in 0..2) {
                val y = 0.28f * r + k * 0.2f * r
                drawArc(ink.copy(alpha = 0.25f), startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(-0.38f * r, y - 0.1f * r), size = Size(0.76f * r, 0.16f * r), style = thinStroke)
            }
            if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.22f))
            drawPath(bodyPath, ink, style = outlineStroke)
            drawOval(Color.White.copy(alpha = 0.5f), topLeft = Offset(-0.62f * r, -0.78f * r), size = Size(0.32f * r, 0.16f * r))
            drawArms(s, this@CinderArt, mood, scaleMid, ink, ax = 0.84f, ay = 0.22f, len = 0.36f, thick = 0.22f)
            // nostrils
            drawCircle(ink, radius = 0.03f * r, center = Offset(-0.08f * r, 0.12f * r))
            drawCircle(ink, radius = 0.03f * r, center = Offset(0.08f * r, 0.12f * r))
            drawFace(s, this@CinderArt, mood)
            if ((mood == Mood.HAPPY || mood == Mood.LOVE) && s.mouthOpen < 0.05f) {
                drawPath(fang, Color.White) // a tiny fang
            }
        }
    }

    private fun DrawScope.drawWings(s: PetSim, mood: Mood, t: Float) {
        val speed = hypot(s.vx, s.vy) / d
        val flying = max(
            if (s.held || s.hop < -2f * d || s.trick == Trick.DANCE) 1f else 0f,
            min(1f, speed / 500f)
        )
        val idleFlap = if (mood == Mood.HAPPY || mood == Mood.LOVE) 0.45f else 0f
        val amt = max(flying, idleFlap)
        val flap = sin(t * 22f) * 30f * amt + sin(t * 2f) * 4f
        val fold = if (mood == Mood.SLEEP) 25f else 0f
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            withTransform({
                translate(sgn * 0.6f * r, -0.2f * r)
                scale(sgn, 1f, Offset.Zero)
                rotate(-6f + fold - flap, Offset.Zero)
            }) {
                drawPath(wingPath, wing)
                drawPath(wingBone, ink.copy(alpha = 0.45f), style = thinStroke)
                drawPath(wingPath, ink, style = thinStroke)
            }
        }
    }

    /** Mirrored toward the middle of the screen, so it never sticks out past the edge the pet is near. */
    private fun DrawScope.drawTail(s: PetSim, mood: Mood, t: Float) {
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawTailInner(s, mood, t) }
    }

    private fun DrawScope.drawTailInner(s: PetSim, mood: Mood, t: Float) {
        val sway = sin(t * 2.3f) * 0.1f * r - s.antenna.v * 0.01f * r
        val lift = if (mood == Mood.HAPPY || mood == Mood.LOVE) -0.25f * r else 0f
        val ex = 1.38f * r
        val ey = 0.5f * r + sway + lift
        tailPath.reset()
        tailPath.moveTo(0.55f * r, 0.75f * r)
        tailPath.quadraticTo(1.15f * r, 0.95f * r, ex, ey)
        drawPath(tailPath, ink, style = tailOutline)
        drawPath(tailPath, scaleMid, style = tailFill)
        val ang = -35f + (sway + lift) / r * 40f
        unit(ex + 0.02f * r, ey - 0.08f * r, 0.17f * r, rot = ang) {
            drawPath(spade, scales[2])
            drawPath(spade, ink, style = unitOutline)
        }
    }

    private companion object {
        const val FX_FIRE = 1
        const val FX_SMOKE = 2
    }
}
