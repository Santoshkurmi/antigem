package ai.antigem.ui.components.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * How a pet looks and what its signature move does. Every shape is built once per pet, look and size; drawing a frame
 * allocates nothing (dynamic shapes reuse one Path). All pets share the face (eyes, mouth, blush for every mood),
 * the particle look (hearts, sparkles, z) and the frame, so each pet file only draws its body and its own effects.
 */

/** Where the face sits and how it looks, in body radii (r = 1) around the body center. */
internal class FaceSpec(
    val eyeY: Float = -0.12f,
    val eyeDX: Float = 0.36f,
    val eyeW: Float = 0.23f, // half width
    val eyeH: Float = 0.27f, // half height
    val mouthY: Float = 0.3f,
    val mouthScale: Float = 1f,
    val cheekY: Float = 0.11f,
    val cheekDX: Float = 0.6f,
    val plainEyes: Boolean = false, // dark eyes without whites (bear, ghost, axolotl...)
    val animalMouth: Boolean = false, // a little "ω" when calm (fox, bear, cat)
    val headTop: Float = -1.0f, // for the dizzy stars and the sleeping "z"
    val pupil: Color = Color(0xFF1B1840),
    val cheek: Color = Color(0xFFFF8FB8),
    val tongue: Color = Color(0xFFFF7A9A),
    val ink: Color = Color(0xFF2E2A6B)
)

internal abstract class PetArt(val d: Float) {
    val r = 24f * d

    abstract val face: FaceSpec
    open val heartColor = Color(0xFFFF5C8A)
    open val sparkleColor = Color(0xFFFFE27A)
    open val zColor = Color(0xFFA9B4FF)
    open val starColor = Color(0xFFFFD45E)
    /** Floating pets (ghost) bob more and cast a fainter shadow. */
    open val bobScale = 1f
    open val shadowAlpha = 1f

    val outlineStroke = Stroke(width = 2.2f * d, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = 1.3f * d, cap = StrokeCap.Round)
    val featureStroke = Stroke(width = 2.2f * d, cap = StrokeCap.Round)
    val mouthStroke = Stroke(width = 1.8f * d, cap = StrokeCap.Round)

    // Unit-size shapes (about -1..1), drawn with a scale transform; their strokes are in unit space.
    val unitOutline = Stroke(width = 0.16f, join = StrokeJoin.Round)
    val unitThin = Stroke(width = 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val zStroke = Stroke(width = 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val heart = Path().apply {
        moveTo(0f, 0.9f)
        cubicTo(-0.5f, 0.5f, -1f, 0.15f, -1f, -0.3f)
        cubicTo(-1f, -0.75f, -0.45f, -1f, 0f, -0.55f)
        cubicTo(0.45f, -1f, 1f, -0.75f, 1f, -0.3f)
        cubicTo(1f, 0.15f, 0.5f, 0.5f, 0f, 0.9f)
        close()
    }
    val sparkle = Path().apply {
        moveTo(0f, -1f)
        quadraticTo(0.12f, -0.12f, 1f, 0f)
        quadraticTo(0.12f, 0.12f, 0f, 1f)
        quadraticTo(-0.12f, 0.12f, -1f, 0f)
        quadraticTo(-0.12f, -0.12f, 0f, -1f)
        close()
    }
    val star = Path().apply {
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) 1f else 0.45f
            val ang = -PI_F / 2f + i * PI_F / 5f
            if (i == 0) moveTo(cos(ang) * rad, sin(ang) * rad) else lineTo(cos(ang) * rad, sin(ang) * rad)
        }
        close()
    }
    val zPath = Path().apply {
        moveTo(-0.55f, -0.55f)
        lineTo(0.55f, -0.55f)
        lineTo(-0.55f, 0.55f)
        lineTo(0.55f, 0.55f)
    }
    /** A flame pointing up: a main tip with a smaller tongue on the left, round base around y = 0.6. */
    val flame = Path().apply {
        moveTo(0.08f, -1f)
        cubicTo(0.3f, -0.62f, 0.72f, -0.42f, 0.62f, 0.18f)
        cubicTo(0.54f, 0.72f, -0.54f, 0.74f, -0.62f, 0.22f)
        cubicTo(-0.68f, -0.16f, -0.46f, -0.3f, -0.4f, -0.62f)
        cubicTo(-0.24f, -0.42f, -0.08f, -0.44f, 0.08f, -1f)
        close()
    }

    // ---- behaviour hooks (the signature move and the pet's own effects) ----

    open val specialDur = 1.6f
    /** Where [PetSim.pose] springs to (-1..1); what it means is up to the pet. */
    open fun poseTarget(s: PetSim, mood: Mood): Float = 0f
    open fun specialMood(s: PetSim): Mood = Mood.HAPPY
    open fun specialStart(s: PetSim) {}
    open fun specialTick(s: PetSim, prev: Float, t: Float) {}
    /** Called every ~0.75 s (every 4 s once still) while the agent works: one ambient effect. */
    open fun workTick(s: PetSim) {
        s.fx(FX_GLINT, s.x, s.y - s.r * 1.7f, (Random.nextFloat() - 0.5f) * 40f, -40f, 1.1f, 3.5f, drag = 2f, spin = 200f, slow = true)
    }
    open fun celebrate(s: PetSim) {}
    /** The treat was swallowed. */
    open fun crumbs(s: PetSim) {}
    open fun angryTick(s: PetSim) {}

    // ---- drawing ----

    /** The whole pet, in body space: origin at the body center, tilt / squash already applied. */
    abstract fun DrawScope.drawCharacter(s: PetSim, mood: Mood)

    /** The treat, in unit space (about -1..1). */
    abstract fun DrawScope.drawTreat()

    /** One effect particle of [variant], in unit space; FX_GLINT is a sparkle unless a pet draws its own. */
    open fun DrawScope.drawFx(variant: Int, alpha: Float) {
        drawPath(sparkle, sparkleColor.copy(alpha = alpha))
    }

    companion object {
        const val FX_GLINT = 0
    }
}

internal fun crossed(prev: Float, now: Float, at: Float) = prev < at && now >= at

/** Smooth 0..1 between [a] and [b]. */
internal fun smooth(a: Float, b: Float, v: Float): Float {
    val p = ((v - a) / (b - a)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/** Arm angle for [side] (0 left, 1 right): > 0 down, < 0 up; waves, dances, flails while held. */
internal fun armAngle(s: PetSim, mood: Mood, side: Int): Float {
    val t = s.animT
    var ang = 28f + sin(t * 2.1f + side) * 4f
    when {
        s.held -> ang = -55f + sin(t * 16f + side * PI_F) * 14f
        s.trick == Trick.DANCE -> ang = -60f + sin(t * 9f + side * PI_F) * 45f
        mood == Mood.ANGRY -> ang = 60f
        mood == Mood.HAPPY || mood == Mood.LOVE -> ang = -20f + sin(t * 10f + side * PI_F) * 10f
    }
    if (side == 1 && s.wave > 0f) ang += ((-125f + sin(t * 15f) * 22f) - ang) * s.wave
    return ang
}

/** Two oval arms anchored at (±[ax], [ay]) (in r), [len] × [thick] (in r). */
internal fun DrawScope.drawArms(
    s: PetSim, a: PetArt, mood: Mood, fill: Color, outline: Color,
    ax: Float = 0.88f, ay: Float = 0.12f, len: Float = 0.46f, thick: Float = 0.26f, extra: Float = 0f
) {
    val r = a.r
    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        val ang = armAngle(s, mood, side) + extra
        withTransform({
            translate(sgn * ax * r, ay * r)
            scale(sgn, 1f, Offset.Zero)
            rotate(ang, Offset.Zero)
        }) {
            drawOval(fill, topLeft = Offset(-0.06f * r, -thick / 2f * r), size = Size(len * r, thick * r))
            drawOval(outline, topLeft = Offset(-0.06f * r, -thick / 2f * r), size = Size(len * r, thick * r), style = a.thinStroke)
        }
    }
}

/** Two feet at (±[dx], [y]) (in r): paddle while wandering, dangle and swing while held. */
internal fun DrawScope.drawFeet(
    s: PetSim, a: PetArt, fill: Color, outline: Color,
    dx: Float = 0.42f, y: Float = 0.84f, w: Float = 0.42f, h: Float = 0.3f
) {
    val r = a.r
    val t = s.animT
    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        val lift = if (s.trick == Trick.WANDER) max(0f, sin(t * 11f + side * PI_F)) * 0.14f * r else 0f
        val dangle = if (s.held) 0.12f * r else 0f
        val swing = s.legs.v + if (s.held) sin(t * 9f + side * PI_F) * 6f else 0f
        withTransform({
            translate(sgn * dx * r, y * r + dangle - lift)
            rotate(swing, Offset(0f, -0.35f * r))
        }) {
            drawOval(fill, topLeft = Offset(-w / 2f * r, -0.12f * r), size = Size(w * r, h * r))
            drawOval(outline, topLeft = Offset(-w / 2f * r, -0.12f * r), size = Size(w * r, h * r), style = a.thinStroke)
        }
    }
}

/** A unit-space shape at ([x], [y]) (px, body space), [size] px, rotated [rot] degrees. */
internal inline fun DrawScope.unit(x: Float, y: Float, size: Float, rot: Float = 0f, block: DrawScope.() -> Unit) {
    withTransform({
        translate(x, y)
        if (rot != 0f) rotate(rot, Offset.Zero)
        scale(size, size, Offset.Zero)
    }) { block() }
}

/** The whole frame: ground shadow, the pet with its bob / squash / tilt, the falling treat, then the particles. */
internal fun DrawScope.drawPet(s: PetSim, a: PetArt) {
    val d = a.d
    val r = a.r
    val t = s.animT
    val mood = s.mood()
    val sleeping = mood == Mood.SLEEP
    val bobSpeed = when (mood) {
        Mood.SLEEP -> 1.3f
        Mood.THINKING -> 3.2f
        else -> 2.1f
    }
    val bob = if (s.held) 0f else sin(t * bobSpeed) * (if (sleeping) 1.5f else 3f) * d * a.bobScale
    val breathe = sin(t * bobSpeed + 1.1f) * (if (sleeping) 0.035f else 0.018f)
    val cx = size.width / 2f
    val cy = size.height / 2f + bob + s.hop

    // Soft shadow on the "ground" line: shrinks while it hops, faint while held.
    val lift = (-(bob + s.hop) / (40f * d)).coerceIn(0f, 1f)
    val shadowW = r * 1.5f * (1f - 0.35f * lift)
    drawOval(
        color = Color.Black.copy(alpha = 0.16f * a.shadowAlpha * (1f - 0.5f * lift) * (if (s.held) 0.4f else 1f)),
        topLeft = Offset(cx - shadowW / 2f, size.height / 2f + r * 1.18f),
        size = Size(shadowW, r * 0.32f)
    )

    val sq = s.squash.v.coerceIn(-0.35f, 0.35f)
    val sx = (1f + sq) * (1f - breathe * 0.5f)
    val sy = (1f - sq) * (1f + breathe)
    withTransform({
        translate(cx, cy)
        rotate(s.tilt.v + s.spin, Offset.Zero)
        scale(sx, sy, Offset.Zero)
    }) {
        with(a) { drawCharacter(s, mood) }
        val ty = s.treatY(a.face.mouthY * r)
        if (ty != null) unit(0f, ty, 0.32f * r, rot = sin(s.trickT * 9f) * 12f) { with(a) { drawTreat() } }
    }

    // The canvas center is where the body is (overlay: the box around x/y; preview: a zoomed canvas).
    drawParticles(s, a, s.x - size.width / 2f, s.y - size.height / 2f)
}

/** [ox]/[oy]: where this canvas' origin sits in overlay coordinates (particles live in overlay coordinates). */
private fun DrawScope.drawParticles(s: PetSim, a: PetArt, ox: Float, oy: Float) {
    for (i in 0 until MAX_PARTICLES) {
        val life = s.pLife[i]
        if (life <= 0f) continue
        val k = life / s.pMax[i] // 1 -> 0
        val alpha = min(1f, k * 2.5f)
        val type = s.pType[i]
        val grow = when (type) {
            P_HEART -> 0.6f + 0.4f * min(1f, (1f - k) * 4f)
            P_Z -> 0.6f + 0.6f * (1f - k)
            P_FX -> 0.55f + 0.45f * min(1f, (1f - k) * 5f)
            else -> k
        }
        val sz = s.pSize[i] * grow
        withTransform({
            translate(s.pX[i] - ox, s.pY[i] - oy)
            rotate(s.pRot[i], Offset.Zero)
            scale(sz, sz, Offset.Zero)
        }) {
            when (type) {
                P_HEART -> drawPath(a.heart, a.heartColor.copy(alpha = alpha))
                P_Z -> drawPath(a.zPath, a.zColor.copy(alpha = alpha), style = a.zStroke)
                P_FX -> with(a) { drawFx(s.pVar[i], alpha) }
                else -> drawPath(a.sparkle, a.sparkleColor.copy(alpha = alpha))
            }
        }
    }
}

/** Eyes, cheeks and mouth for every mood, placed by [a]'s [FaceSpec]. */
internal fun DrawScope.drawFace(s: PetSim, a: PetArt, mood: Mood) {
    val f = a.face
    val d = a.d
    val r = a.r
    val t = s.animT
    val eyeY = f.eyeY * r
    val eyeDX = f.eyeDX * r

    val blush = when (mood) {
        Mood.HAPPY, Mood.LOVE -> 0.75f
        Mood.ANGRY -> 0f
        else -> 0.45f
    }
    if (blush > 0f) {
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            drawOval(
                f.cheek.copy(alpha = blush),
                topLeft = Offset(sgn * f.cheekDX * r - 0.15f * r, f.cheekY * r), size = Size(0.3f * r, 0.18f * r)
            )
        }
    }

    val arcW = max(f.eyeW * 1.48f, 0.24f) * r
    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        val ex = sgn * eyeDX
        when (mood) {
            Mood.HAPPY -> drawArc(
                f.pupil, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(ex - arcW / 2f, eyeY - 0.08f * r), size = Size(arcW, arcW * 0.88f), style = a.featureStroke
            )
            Mood.LOVE -> {
                val hs = max(f.eyeW, 0.16f) * 0.87f * r * (1f + 0.12f * sin(t * 10f))
                unit(ex, eyeY, hs) {
                    drawPath(a.heart, a.heartColor)
                    drawPath(a.heart, f.ink, style = a.unitOutline)
                }
            }
            Mood.SLEEP -> drawArc(
                f.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(ex - arcW * 0.47f, eyeY - 0.12f * r), size = Size(arcW * 0.94f, arcW * 0.65f), style = a.featureStroke
            )
            Mood.DIZZY -> {
                val rot = t * 600f
                val er = max(f.eyeW, 0.17f) * r
                if (!f.plainEyes) drawCircle(Color.White, radius = er * 1.04f, center = Offset(ex, eyeY))
                drawArc(
                    f.pupil, startAngle = rot, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(ex - er * 0.83f, eyeY - er * 0.83f), size = Size(er * 1.66f, er * 1.66f), style = a.thinStroke
                )
                drawArc(
                    f.pupil, startAngle = rot + 180f, sweepAngle = 260f, useCenter = false,
                    topLeft = Offset(ex - er * 0.4f, eyeY - er * 0.4f), size = Size(er * 0.8f, er * 0.8f), style = a.thinStroke
                )
            }
            else -> {
                val big = mood == Mood.SURPRISED
                val open = (1f - s.blink) * (1f - s.yawn * 0.6f)
                val ew = f.eyeW * r * (if (big) 1.17f else 1f)
                val eh0 = f.eyeH * r * (if (big) 1.12f else 1f)
                if (open < 0.18f) {
                    drawLine(f.pupil, Offset(ex - ew * 0.8f, eyeY), Offset(ex + ew * 0.8f, eyeY), strokeWidth = 2.2f * d, cap = StrokeCap.Round)
                } else if (f.plainEyes) {
                    // dark eyes with a shine; they shift a little toward where the pet looks
                    val eh = eh0 * open
                    val px = ex + s.lookX * ew * 0.35f
                    val py = eyeY + s.lookY * eh * 0.25f
                    drawOval(f.pupil, topLeft = Offset(px - ew, py - eh), size = Size(2f * ew, 2f * eh))
                    drawCircle(Color.White, radius = ew * 0.36f, center = Offset(px - ew * 0.32f, py - eh * 0.42f))
                    if (big) drawCircle(Color.White, radius = ew * 0.16f, center = Offset(px + ew * 0.35f, py + eh * 0.3f))
                } else {
                    val eh = eh0 * open
                    drawOval(Color.White, topLeft = Offset(ex - ew, eyeY - eh), size = Size(2f * ew, 2f * eh))
                    val pr = min(
                        when {
                            big -> 0.43f * ew
                            mood == Mood.ANGRY -> 0.52f * ew
                            else -> 0.65f * ew
                        },
                        eh * 0.9f
                    )
                    val px = ex + s.lookX * (ew - pr) * 0.7f
                    val py = eyeY + s.lookY * (eh - pr) * 0.6f
                    drawCircle(f.pupil, radius = pr, center = Offset(px, py))
                    drawCircle(Color.White, radius = pr * 0.38f, center = Offset(px - pr * 0.35f, py - pr * 0.4f))
                    drawOval(f.ink, topLeft = Offset(ex - ew, eyeY - eh), size = Size(2f * ew, 2f * eh), style = a.thinStroke)
                }
                val browH = max(eh0, 0.15f * r)
                if (mood == Mood.ANGRY) {
                    drawLine(
                        f.ink, Offset(ex + sgn * 0.22f * r, eyeY - browH - 0.13f * r), Offset(ex - sgn * 0.18f * r, eyeY - browH + 0.01f * r),
                        strokeWidth = 2.4f * d, cap = StrokeCap.Round
                    )
                } else if (mood == Mood.THINKING && side == 1) {
                    drawLine(
                        f.ink, Offset(ex - 0.16f * r, eyeY - browH - 0.17f * r), Offset(ex + 0.18f * r, eyeY - browH - 0.13f * r),
                        strokeWidth = 2.2f * d, cap = StrokeCap.Round
                    )
                }
            }
        }
    }

    // Mouth
    val my = f.mouthY * r
    val ms = f.mouthScale
    val open = max(s.yawn, s.mouthOpen)
    when {
        open > 0.05f -> {
            val h = (0.1f * r + 0.26f * r * open) * ms
            val w = (0.2f + 0.08f * open) * r * ms
            drawOval(f.pupil, topLeft = Offset(-w / 2f, my - h * 0.4f), size = Size(w, h))
            if (open > 0.4f) drawOval(f.tongue, topLeft = Offset(-w * 0.32f, my + h * 0.25f), size = Size(w * 0.64f, h * 0.3f))
        }
        mood == Mood.HAPPY || mood == Mood.LOVE -> {
            drawArc(
                f.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(-0.22f * r * ms, my - 0.2f * r * ms), size = Size(0.44f * r * ms, 0.4f * r * ms)
            )
            drawOval(f.tongue, topLeft = Offset(-0.1f * r * ms, my + 0.07f * r * ms), size = Size(0.2f * r * ms, 0.1f * r * ms))
        }
        mood == Mood.SURPRISED -> drawOval(f.pupil, topLeft = Offset(-0.08f * r * ms, my - 0.06f * r), size = Size(0.16f * r * ms, 0.2f * r * ms))
        mood == Mood.SLEEP -> {
            val h = (0.07f * r + 0.05f * r * (0.5f + 0.5f * sin(t * 1.3f))) * ms
            drawOval(f.pupil, topLeft = Offset(-0.06f * r * ms, my), size = Size(0.12f * r * ms, h))
        }
        mood == Mood.DIZZY -> {
            drawArc(f.pupil, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(-0.2f * r * ms, my - 0.06f * r), size = Size(0.2f * r * ms, 0.12f * r * ms), style = a.featureStroke)
            drawArc(f.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0f, my - 0.06f * r), size = Size(0.2f * r * ms, 0.12f * r * ms), style = a.featureStroke)
        }
        mood == Mood.ANGRY -> drawArc(
            f.pupil, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(-0.18f * r * ms, my), size = Size(0.36f * r * ms, 0.24f * r * ms), style = a.featureStroke
        )
        mood == Mood.THINKING -> drawLine(f.pupil, Offset(-0.06f * r * ms, my + 0.04f * r), Offset(0.14f * r * ms, my), strokeWidth = 2.2f * d, cap = StrokeCap.Round)
        f.animalMouth -> {
            // "ω"
            val w = 0.15f * r * ms
            drawArc(f.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(-w, my - w * 0.5f), size = Size(w, w * 0.9f), style = a.mouthStroke)
            drawArc(f.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0f, my - w * 0.5f), size = Size(w, w * 0.9f), style = a.mouthStroke)
        }
        else -> drawArc(
            f.pupil, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(-0.2f * r * ms, my - 0.14f * r * ms), size = Size(0.4f * r * ms, 0.28f * r * ms), style = a.featureStroke
        )
    }

    // Dizzy: little stars circling the head.
    if (mood == Mood.DIZZY) {
        for (i in 0..2) {
            val o = t * 4f + i * 2.094f
            unit(cos(o) * 0.95f * r, (f.headTop + 0.05f) * r + sin(o) * 0.22f * r, 0.13f * r) {
                drawPath(a.star, a.starColor)
                drawPath(a.star, f.ink, style = a.unitOutline)
            }
        }
    }

    // Asleep: a small static "z" by the head, so the still (resting) pose still reads as sleeping between snores.
    if (mood == Mood.SLEEP) {
        unit(0.95f * r, (f.headTop - 0.2f) * r, 0.16f * r, rot = -12f) {
            drawPath(a.zPath, a.zColor, style = a.zStroke)
        }
    }
}
