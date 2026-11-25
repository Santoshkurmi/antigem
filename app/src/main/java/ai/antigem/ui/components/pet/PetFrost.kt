package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frost: a round polar bear cub in a striped scarf, carrying a little wood axe (can be switched off). Small dark eyes,
 * a big muzzle, round ears; the scarf end swings when it moves. Snowflakes drift down while the agent works.
 * Signature move: "Chop!" (three chops with flying wood chips and snow) or, without the axe, "Stomp" (snow stomps).
 */
internal class FrostArt(d: Float, palette: Int, private val axe: Boolean) : PetArt(d) {
    private val fur: List<Color>
    private val muzzle: Color
    private val innerEar: Color
    private val scarf: Color
    private val stripe: Color
    private val ink: Color

    init {
        when (palette) {
            1 -> { fur = listOf(Color(0xFFF3FBFF), Color(0xFFD6EEFF), Color(0xFFA9D4F5)); muzzle = Color(0xFFFFFFFF); innerEar = Color(0xFFB8D7EE); scarf = Color(0xFF1FB5A8); stripe = Color(0xFFE8FFFB); ink = Color(0xFF23506F) }
            2 -> { fur = listOf(Color(0xFFFFF4DC), Color(0xFFF7DFAE), Color(0xFFE6BF7A)); muzzle = Color(0xFFFFF8EA); innerEar = Color(0xFFE3A98F); scarf = Color(0xFF3DAA5C); stripe = Color(0xFFF2FFE8); ink = Color(0xFF6A4A22) }
            3 -> { fur = listOf(Color(0xFFC99470), Color(0xFFA9744F), Color(0xFF7E5235)); muzzle = Color(0xFFE9CBA7); innerEar = Color(0xFF8A5A3C); scarf = Color(0xFFF2C230); stripe = Color(0xFFFFF6D6); ink = Color(0xFF3A2414) }
            else -> { fur = listOf(Color(0xFFFFFFFF), Color(0xFFF2F6FB), Color(0xFFD5E1EE)); muzzle = Color(0xFFF7F1E6); innerEar = Color(0xFFDCC5CF); scarf = Color(0xFFE5484D); stripe = Color(0xFFFFFFFF); ink = Color(0xFF34485C) }
        }
    }

    override val face = FaceSpec(
        eyeY = -0.16f, eyeDX = 0.36f, eyeW = 0.085f, eyeH = 0.11f, mouthY = 0.36f, mouthScale = 0.85f,
        cheekY = 0.08f, cheekDX = 0.64f, plainEyes = true, animalMouth = true, headTop = -0.95f, pupil = Color(0xFF1F2A36), ink = ink
    )
    override val zColor = Color(0xFF9FD4FF)

    private val furMid = fur[1]
    private val pad = fur[2]
    private val bodyBrush = Brush.verticalGradient(colors = fur, startY = -r, endY = r)
    private val snow = Color(0xFFE6F6FF)
    private val wood = Color(0xFFB07A45)
    private val woodDark = Color(0xFF7A4E26)
    private val steel = Color(0xFFC9D3DE)
    private val steelDark = Color(0xFF8E9AAA)

    private val bodyPath = Path().apply {
        moveTo(0f, -0.92f * r)
        cubicTo(0.62f * r, -0.92f * r, 1.0f * r, -0.5f * r, 1.0f * r, 0.12f * r)
        cubicTo(1.0f * r, 0.72f * r, 0.62f * r, 0.98f * r, 0f, 0.98f * r)
        cubicTo(-0.62f * r, 0.98f * r, -1.0f * r, 0.72f * r, -1.0f * r, 0.12f * r)
        cubicTo(-1.0f * r, -0.5f * r, -0.62f * r, -0.92f * r, 0f, -0.92f * r)
        close()
    }
    private val scarfBand = Path().apply {
        moveTo(-0.95f * r, 0.36f * r)
        quadraticTo(0f, 0.6f * r, 0.95f * r, 0.36f * r)
        lineTo(0.9f * r, 0.6f * r)
        quadraticTo(0f, 0.86f * r, -0.9f * r, 0.6f * r)
        close()
    }
    /** The hanging end, from its knot (0, 0) down. */
    private val scarfEnd = Path().apply {
        moveTo(-0.12f * r, 0f)
        lineTo(0.12f * r, 0f)
        lineTo(0.16f * r, 0.5f * r)
        lineTo(-0.1f * r, 0.52f * r)
        close()
    }
    /** Axe head at the far end of the handle (arm space: the arm and handle point along +x), edge on the +y side,
     *  which leads when the arm swings down. */
    private val blade = Path().apply {
        moveTo(0.76f * r, 0.03f * r)
        lineTo(1.02f * r, 0.03f * r)
        quadraticTo(1.12f * r, 0.24f * r, 1.08f * r, 0.46f * r)
        quadraticTo(0.89f * r, 0.54f * r, 0.7f * r, 0.46f * r)
        quadraticTo(0.66f * r, 0.24f * r, 0.76f * r, 0.03f * r)
        close()
    }
    private val fish = Path().apply {
        moveTo(-0.95f, 0f)
        quadraticTo(-0.3f, -0.7f, 0.45f, -0.1f)
        lineTo(0.95f, -0.45f)
        lineTo(0.85f, 0f)
        lineTo(0.95f, 0.45f)
        lineTo(0.45f, 0.1f)
        quadraticTo(-0.3f, 0.7f, -0.95f, 0f)
        close()
    }
    private val snowflakeArm = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, -1f)
        moveTo(0f, -0.55f)
        lineTo(-0.28f, -0.8f)
        moveTo(0f, -0.55f)
        lineTo(0.28f, -0.8f)
    }

    // ---- behaviour ----

    override val specialDur get() = if (axe) 1.95f else 1.7f

    override fun specialMood(s: PetSim): Mood = if (s.trickT > specialDur - 0.35f) Mood.HAPPY else Mood.NORMAL

    override fun specialStart(s: PetSim) {
        s.say(s.lines.special(), 1.8f)
        if (!axe) s.jump(260f)
    }

    override fun specialTick(s: PetSim, prev: Float, t: Float) {
        if (axe) {
            for (hit in HITS) if (crossed(prev, t, hit)) {
                s.squash.vel += 5f
                s.light()
                // where the blade lands at the bottom of the swing (arm at DOWN degrees)
                val dir = s.facing()
                val hx = s.x + dir * 1.45f * s.r
                val hy = s.y + 0.95f * s.r + s.hop
                repeat(4) { s.fx(FX_CHIP, hx, hy, dir * (40f + Random.nextFloat() * 120f), -(120f + Random.nextFloat() * 120f), 0.9f, 4f, gravity = 700f, spin = 500f, rot = Random.nextFloat() * 180f) }
                repeat(3) { s.fx(FX_SNOW, hx, hy, dir * (Random.nextFloat() - 0.3f) * 140f, -(60f + Random.nextFloat() * 90f), 1.1f, 5f, gravity = 60f, drag = 1.5f, spin = 120f) }
            }
        } else {
            if (crossed(prev, t, 0.75f)) s.jump(260f)
            if (crossed(prev, t, 0.5f) || crossed(prev, t, 1.25f)) {
                s.light()
                s.squash.vel += 6f
                s.fxBurst(FX_SNOW, 6, s.x, s.y + 0.9f * s.r, 150f, 1.0f, 5f, gravity = 80f, upBias = 120f)
            }
        }
    }

    override fun workTick(s: PetSim) {
        s.fx(FX_SNOW, s.x + (Random.nextFloat() - 0.5f) * s.r * 2.4f, s.y - s.r * 1.6f, (Random.nextFloat() - 0.5f) * 12f, 26f, 2.2f, 4.5f, wobble = 18f, spin = 50f, slow = true)
    }

    override fun celebrate(s: PetSim) {
        s.fxBurst(FX_SNOW, 8, s.x, s.y - s.r * 0.5f, 170f, 1.2f, 5.5f, gravity = 60f, upBias = 80f)
    }

    override fun crumbs(s: PetSim) {
        s.fxBurst(FX_SNOW, 3, s.x, s.y + s.r * 0.3f + s.hop, 70f, 0.6f, 3f)
    }

    override fun angryTick(s: PetSim) {
        s.fxBurst(FX_SNOW, 4, s.x, s.y - s.r, 120f, 0.8f, 4f, gravity = 100f, upBias = 60f)
    }

    // ---- drawing ----

    override fun DrawScope.drawFx(variant: Int, alpha: Float) {
        when (variant) {
            FX_CHIP -> {
                drawRoundRect(wood.copy(alpha = alpha), topLeft = Offset(-0.9f, -0.45f), size = Size(1.8f, 0.9f), cornerRadius = CornerRadius(0.3f))
                drawLine(woodDark.copy(alpha = alpha), Offset(-0.6f, 0f), Offset(0.6f, 0f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            }
            FX_SNOW -> for (k in 0 until 6) {
                withTransform({ rotate(k * 60f, Offset.Zero) }) { drawPath(snowflakeArm, snow.copy(alpha = alpha), style = unitThin) }
            }
            else -> drawPath(sparkle, sparkleColor.copy(alpha = alpha))
        }
    }

    override fun DrawScope.drawTreat() {
        drawPath(fish, Color(0xFF8FB8DE))
        drawPath(fish, Color(0xFF3F6587), style = unitOutline)
        drawCircle(Color(0xFF1F2A36), radius = 0.1f, center = Offset(-0.55f, -0.08f))
    }

    override fun DrawScope.drawCharacter(s: PetSim, mood: Mood) {
        val t = s.animT
        drawFeet(s, this@FrostArt, pad, ink, dx = 0.44f, y = 0.86f, w = 0.44f, h = 0.3f)
        // ears: back a little when angry, flicking on blinks
        val earDrop = if (mood == Mood.ANGRY) 0.08f * r else 0f
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            val ex = sgn * (0.6f * r + s.antenna.v * 0.004f * r)
            val ey = -0.7f * r + earDrop - (if (side == 1) s.blink * 0.03f * r else 0f)
            drawCircle(furMid, radius = 0.25f * r, center = Offset(ex, ey))
            drawCircle(ink, radius = 0.25f * r, center = Offset(ex, ey), style = outlineStroke)
            drawCircle(innerEar, radius = 0.13f * r, center = Offset(ex, ey + 0.02f * r))
        }
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawLeftArm(s, mood) }
        drawPath(bodyPath, bodyBrush)
        if (mood == Mood.ANGRY) drawPath(bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.2f))
        drawPath(bodyPath, ink, style = outlineStroke)
        drawOval(Color.White.copy(alpha = 0.55f), topLeft = Offset(-0.64f * r, -0.72f * r), size = Size(0.32f * r, 0.17f * r))
        // muzzle and nose
        drawOval(muzzle, topLeft = Offset(-0.32f * r, 0.0f * r), size = Size(0.64f * r, 0.46f * r))
        drawOval(ink.copy(alpha = 0.25f), topLeft = Offset(-0.32f * r, 0.0f * r), size = Size(0.64f * r, 0.46f * r), style = thinStroke)
        drawOval(Color(0xFF1F2A36), topLeft = Offset(-0.11f * r, 0.07f * r), size = Size(0.22f * r, 0.15f * r))
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 0.03f * r, center = Offset(-0.04f * r, 0.11f * r))
        drawFace(s, this@FrostArt, mood)
        drawScarf(s)
        // the axe arm is on the side facing the middle of the screen
        withTransform({ scale(s.facing(), 1f, Offset.Zero) }) { drawRightArm(s, mood, t) }
    }

    private fun DrawScope.drawScarf(s: PetSim) {
        drawPath(scarfBand, scarf)
        drawLine(stripe, Offset(-0.5f * r, 0.6f * r), Offset(-0.44f * r, 0.75f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
        drawLine(stripe, Offset(-0.1f * r, 0.66f * r), Offset(-0.06f * r, 0.8f * r), strokeWidth = 0.06f * r, cap = StrokeCap.Round)
        drawPath(scarfBand, ink, style = thinStroke)
        val swing = (s.antenna.v * 0.8f + s.legs.v * 0.4f + sin(s.animT * 2.1f) * 4f).coerceIn(-60f, 60f)
        withTransform({
            translate(0.42f * r, 0.62f * r)
            rotate(swing, Offset.Zero)
        }) {
            drawPath(scarfEnd, scarf)
            drawLine(stripe, Offset(-0.1f * r, 0.18f * r), Offset(0.13f * r, 0.18f * r), strokeWidth = 0.05f * r)
            drawLine(stripe, Offset(-0.1f * r, 0.32f * r), Offset(0.14f * r, 0.32f * r), strokeWidth = 0.05f * r)
            drawPath(scarfEnd, ink, style = thinStroke)
        }
    }

    private fun DrawScope.drawLeftArm(s: PetSim, mood: Mood) {
        val ang = armAngle(s, mood, 0)
        withTransform({
            translate(-0.86f * r, 0.2f * r)
            scale(-1f, 1f, Offset.Zero)
            rotate(ang, Offset.Zero)
        }) {
            drawOval(furMid, topLeft = Offset(-0.06f * r, -0.15f * r), size = Size(0.44f * r, 0.3f * r))
            drawOval(ink, topLeft = Offset(-0.06f * r, -0.15f * r), size = Size(0.44f * r, 0.3f * r), style = thinStroke)
        }
    }

    /** The right arm, holding the axe: on the shoulder, chopping, raised when angry or celebrating. */
    private fun DrawScope.drawRightArm(s: PetSim, mood: Mood, t: Float) {
        var ang = armAngle(s, mood, 1)
        if (axe) {
            ang = when {
                s.trick == Trick.SPECIAL -> chopAngle(s.trickT)
                s.held -> -70f + sin(t * 16f) * 14f
                mood == Mood.ANGRY -> -100f + sin(t * 30f) * 6f
                mood == Mood.HAPPY || mood == Mood.LOVE -> -85f + sin(t * 10f) * 12f
                s.wave > 0f -> ang
                else -> REST + sin(t * 2.1f) * 3f
            }
        }
        withTransform({
            translate(0.86f * r, 0.2f * r)
            rotate(ang, Offset.Zero)
        }) {
            if (axe) {
                // the handle runs on from the paw along the arm; the head is at its far end
                drawLine(woodDark, Offset(0.2f * r, 0f), Offset(1.1f * r, 0f), strokeWidth = 0.15f * r, cap = StrokeCap.Round)
                drawLine(wood, Offset(0.2f * r, 0f), Offset(1.1f * r, 0f), strokeWidth = 0.09f * r, cap = StrokeCap.Round)
                drawRoundRect(steelDark, topLeft = Offset(0.8f * r, -0.15f * r), size = Size(0.2f * r, 0.2f * r), cornerRadius = CornerRadius(0.04f * r))
                drawPath(blade, steel)
                drawLine(Color.White, Offset(0.76f * r, 0.41f * r), Offset(1.03f * r, 0.42f * r), strokeWidth = 0.045f * r, cap = StrokeCap.Round)
                drawPath(blade, ink, style = thinStroke)
            }
            drawOval(furMid, topLeft = Offset(-0.06f * r, -0.15f * r), size = Size(0.44f * r, 0.3f * r))
            drawOval(ink, topLeft = Offset(-0.06f * r, -0.15f * r), size = Size(0.44f * r, 0.3f * r), style = thinStroke)
        }
    }

    /** Raise the axe overhead, then a fast swing down to the front, three times (impacts at [HITS]). */
    private fun chopAngle(t: Float): Float {
        if (t < 0.2f) return REST + (UP - REST) * smooth(0f, 0.2f, t)
        val x = (t - 0.2f) / 0.5f
        val cycle = x.toInt()
        val ph = x - cycle
        if (cycle >= 3) return DOWN + (REST - DOWN) * smooth(0f, 0.25f, t - 1.7f)
        return if (ph < 0.6f) {
            if (cycle == 0) UP else DOWN + (UP - DOWN) * smooth(0f, 0.6f, ph)
        } else {
            UP + (DOWN - UP) * ((ph - 0.6f) / 0.4f).let { it * it }
        }
    }

    private companion object {
        const val FX_SNOW = 1
        const val FX_CHIP = 2
        val HITS = floatArrayOf(0.7f, 1.2f, 1.7f)
        const val REST = -58f // axe up by its shoulder
        const val UP = -105f // raised overhead
        const val DOWN = 35f // the blade down in front
    }
}
