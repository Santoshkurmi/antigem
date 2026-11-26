package ai.antigem.crash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import ai.antigem.i18n.L
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val PI_F = 3.1415927f

private val BodyTop = Color(0xFFFFF3E6)
private val BodyBottom = Color(0xFFFFD6BC)
private val Belly = Color(0xFFFFFBF5)
private val Outline = Color(0xFF7A4E3E)
private val EarInner = Color(0xFFF4A58E)
private val EyeInk = Color(0xFF34252A)
private val Blush = Color(0xFFFF8FA3)
private val Nose = Color(0xFFE77A8C)
private val TearBlue = Color(0xFF8FD3FF)
private val HeartPink = Color(0xFFFF6F91)
private val SparkleGold = Color(0xFFFFC94D)
private val BandAid = Color(0xFFF6CDA8)
private val BandAidPad = Color(0xFFE9B08A)

/**
 * The crash screen's character: a little cat with a band-aid, sad that the app crashed. It sniffles and cries a tear
 * now and then. Its eyes follow a finger, a tap makes it hop, and petting it (moving a finger over it) cheers it up:
 * ears and tail come up, it smiles and hearts float up. Left alone for a while it gets sad again.
 * Plain Canvas drawing with no other app code (the crash process loads nothing else), one frame loop while shown.
 */
@Composable
internal fun CrashBuddy(modifier: Modifier = Modifier) {
    val buddy = remember { BuddyState() }
    var frame by remember { mutableLongStateOf(0L) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(buddy) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                buddy.step(dt)
                frame = now
            }
        }
    }
    val description = L.crash.buddy.buddyDescription
    Canvas(
        modifier
            .onSizeChanged { buddy.w = it.width.toFloat(); buddy.h = it.height.toFloat() }
            .semantics {
                contentDescription = description
                onClick { buddy.cheer(); true }
            }
            .pointerInput(buddy) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    buddy.press(down.position)
                    var tap = false
                    try {
                        var moved = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                tap = !moved
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            buddy.move(change.position, moved)
                            change.consume()
                        }
                    } finally {
                        if (buddy.release(tap)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
    ) {
        frame // read here so the drawing runs again on every frame
        drawBuddy(buddy)
    }
}

private const val FX_HEART = 0
private const val FX_SPARKLE = 1
private const val FX_SPLASH = 2

private class Fx(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val size: Float, val kind: Int) {
    var age = 0f
}

/** Everything that moves, in pixels of the canvas; changed only on the main thread (frames and touches). */
private class BuddyState {
    var w = 0f
    var h = 0f
    val r get() = min(w * 0.24f, h * 0.27f)
    val cx get() = w / 2f
    val ground get() = h * 0.9f

    var t = 0f
    /** 0 = sad, 1 = cheered up. */
    var mood = 0f
    var touch: Offset? = null
    var petting = false
    private var lastTouch = -10f
    private var nextPetHeart = 0f
    private var cheered = false

    var squash = 0f
    private var squashV = 0f
    /** Hop height in body radii (negative = up). */
    var hop = 0f
    private var hopV = 0f
    var lean = 0f
    var lookX = 0f
    var lookY = 0.3f
    var blink = 1f
    private var blinkAt = 2f
    var shiver = 0f
    private var sniffleAt = 3f
    var twitch = 0f
    private var twitchAt = 5f

    /** 0 none, 1 welling up in the left eye, 2 falling. */
    var tearPhase = 0
    var tearGrow = 0f
    var tearX = 0f
    var tearY = 0f
    private var tearV = 0f
    private var tearAt = 1.5f
    var puddle = 0f
    var puddleX = 0f

    val fx = ArrayList<Fx>()
    private val rnd = Random.Default

    private fun bodyCenter() = Offset(cx, ground + hop * r - r * 0.92f)
    private fun onBody(p: Offset) = r > 0f && (p - bodyCenter()).getDistance() < r * 1.3f

    fun press(p: Offset) {
        touch = p
        lastTouch = t
    }

    fun move(p: Offset, moved: Boolean) {
        touch = p
        lastTouch = t
        petting = moved && onBody(p)
    }

    /** Finger up: true when it was a tap on the cat (it reacts). */
    fun release(tap: Boolean): Boolean {
        val p = touch
        touch = null
        petting = false
        lastTouch = t
        if (!tap || p == null || !onBody(p)) return false
        cheer()
        return true
    }

    /** A tap's reaction: a squish, a little hop, two hearts and a bit happier. */
    fun cheer() {
        if (r <= 0f) return
        lastTouch = t
        squashV += 7f
        if (hop >= 0f) hopV = -3.2f
        mood = min(1f, mood + 0.22f)
        repeat(2) { heart() }
    }

    fun step(dt: Float) {
        if (r <= 0f) return
        t += dt

        // mood: petting raises it, left alone it slowly falls back to sad
        if (petting) {
            mood = min(1f, mood + dt * 0.5f)
            if (t >= nextPetHeart) {
                heart()
                nextPetHeart = t + 0.35f
            }
        } else if (touch == null && t - lastTouch > 2.5f) {
            mood = max(0f, mood - dt * 0.09f)
        }
        if (mood >= 0.999f && !cheered) {
            cheered = true
            if (hop >= 0f) hopV = -5f
            repeat(8) { sparkle(it) }
        }
        if (mood < 0.4f) cheered = false

        // squash spring and hop
        squashV += (-squash * 160f - squashV * 10f) * dt
        squash += squashV * dt
        if (hopV != 0f || hop < 0f) {
            hopV += 24f * dt
            hop += hopV * dt
            if (hop >= 0f) {
                if (hopV > 1f) squashV += hopV * 1.6f
                hop = 0f
                hopV = 0f
            }
        }

        // leans and looks toward the finger; alone it looks down (sad) or ahead (cheered up)
        val p = touch
        val leanTarget = if (p != null) ((p.x - cx) / (r * 2.5f)).coerceIn(-1f, 1f) else 0f
        lean += (leanTarget - lean) * min(1f, dt * 5f)
        val lxT: Float
        val lyT: Float
        if (p != null) {
            val c = bodyCenter()
            lxT = ((p.x - c.x) / (r * 2f)).coerceIn(-1f, 1f)
            lyT = ((p.y - c.y) / (r * 2f)).coerceIn(-1f, 1f)
        } else {
            lxT = sin(t * 0.45f) * 0.35f
            lyT = 0.45f * (1f - mood) + sin(t * 0.3f) * 0.1f
        }
        lookX += (lxT - lookX) * min(1f, dt * 6f)
        lookY += (lyT - lookY) * min(1f, dt * 6f)

        // blink, sniffle, ear twitch
        val sinceBlink = t - blinkAt
        blink = if (sinceBlink in 0f..0.18f) 1f - sin(sinceBlink / 0.18f * PI_F) * 0.95f else 1f
        if (sinceBlink > 0.18f) blinkAt = t + 2f + rnd.nextFloat() * 3f
        val sinceSniffle = t - sniffleAt
        shiver = if (sinceSniffle in 0f..0.4f) sin(sinceSniffle * 75f) * (1f - sinceSniffle / 0.4f) else 0f
        if (sinceSniffle > 0.4f) sniffleAt = t + 3.5f + rnd.nextFloat() * 4f
        val sinceTwitch = t - twitchAt
        twitch = if (sinceTwitch in 0f..0.3f) sin(sinceTwitch / 0.3f * PI_F * 3f) * (1f - sinceTwitch / 0.3f) else 0f
        if (sinceTwitch > 0.3f) twitchAt = t + 3f + rnd.nextFloat() * 5f

        // a tear wells up in the left eye, drops and splashes into a little puddle (only while sad)
        val sad = mood < 0.45f
        when (tearPhase) {
            0 -> if (sad && t >= tearAt) {
                tearPhase = 1
                tearGrow = 0f
            }
            1 -> {
                tearGrow += if (sad) dt / 1.3f else -dt * 2f
                if (tearGrow <= 0f) {
                    tearPhase = 0
                    tearAt = t + 1f
                } else if (tearGrow >= 1f) {
                    val e = tearStart()
                    tearX = e.x
                    tearY = e.y
                    tearV = 0f
                    tearPhase = 2
                }
            }
            else -> {
                tearV += r * 12f * dt
                tearY += tearV * dt
                if (tearY >= ground + r * 0.05f) {
                    tearPhase = 0
                    tearAt = t + 1.5f + rnd.nextFloat() * 2.5f
                    puddle = min(1f, puddle + 0.2f)
                    puddleX = tearX
                    repeat(4) { splash(tearX) }
                }
            }
        }
        puddle = max(0f, puddle - dt * (if (sad) 0.012f else 0.08f))

        // hearts, sparkles and splashes
        val it = fx.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.age += dt
            if (f.age >= f.life) {
                it.remove()
                continue
            }
            when (f.kind) {
                FX_SPLASH -> f.vy += r * 9f * dt
                FX_SPARKLE -> {
                    val keep = 1f - min(1f, dt * 3f)
                    f.vx *= keep
                    f.vy *= keep
                }
            }
            f.x += f.vx * dt
            f.y += f.vy * dt
        }
    }

    /** Where the welling tear leaves the left eye, matching [drawBuddy] (lean included, squash left out). */
    private fun tearStart(): Offset {
        val base = ground + hop * r
        val by = base - r * 0.92f
        val x = cx - r * 0.37f + lookX * r * 0.09f - r * 0.07f
        val y = by - r * 0.06f + lookY * r * 0.06f + r * 0.33f * 0.42f + r * 0.1f
        val a = lean * 7f * PI_F / 180f
        val dx = x - cx
        val dy = y - base
        return Offset(cx + dx * cos(a) - dy * sin(a), base + dx * sin(a) + dy * cos(a))
    }

    private fun heart() {
        if (fx.size > 40) return
        val c = bodyCenter()
        fx += Fx(
            c.x + (rnd.nextFloat() - 0.5f) * r * 1.2f, c.y - r * 0.8f,
            (rnd.nextFloat() - 0.5f) * r * 0.5f, -r * (1.1f + rnd.nextFloat() * 0.5f),
            1.5f, r * (0.14f + rnd.nextFloat() * 0.08f), FX_HEART
        )
    }

    private fun sparkle(i: Int) {
        val c = bodyCenter()
        val a = i * PI_F / 4f
        fx += Fx(c.x + cos(a) * r * 0.9f, c.y + sin(a) * r * 0.9f, cos(a) * r * 1.6f, sin(a) * r * 1.6f, 0.9f, r * 0.13f, FX_SPARKLE)
    }

    private fun splash(x: Float) {
        fx += Fx(x, ground + r * 0.05f, (rnd.nextFloat() - 0.5f) * r * 1.8f, -r * (0.8f + rnd.nextFloat()), 0.45f, r * 0.035f, FX_SPLASH)
    }
}

private fun DrawScope.drawBuddy(b: BuddyState) {
    val r = b.r
    if (r <= 0f) return
    val cx = b.cx
    val ground = b.ground
    val sad = 1f - b.mood
    val base = ground + b.hop * r // bottom of the body
    val by = base - r * 0.92f // middle of the body

    // shadow (smaller while hopping) and the puddle of tears in front of it
    val lift = (-b.hop).coerceIn(0f, 1f)
    val shadowHalf = r * (1f - lift * 0.3f)
    drawOval(
        Color.Black.copy(alpha = 0.14f * (1f - lift * 0.5f)),
        topLeft = Offset(cx - shadowHalf, ground - r * 0.09f),
        size = Size(shadowHalf * 2f, r * 0.2f)
    )
    if (b.puddle > 0.02f) {
        val pw = r * (0.25f + 0.55f * b.puddle)
        drawOval(TearBlue.copy(alpha = 0.45f * b.puddle), Offset(b.puddleX - pw / 2f, ground + r * 0.02f), Size(pw, pw * 0.22f))
        drawOval(Color.White.copy(alpha = 0.5f * b.puddle), Offset(b.puddleX - pw * 0.25f, ground + r * 0.03f), Size(pw * 0.2f, pw * 0.06f))
    }

    val breathe = sin(b.t * 2.2f) * 0.018f
    val sq = b.squash.coerceIn(-0.6f, 0.6f)
    withTransform({
        translate(left = b.shiver * r * 0.035f * sad)
        rotate(b.lean * 7f, pivot = Offset(cx, base))
        scale(1f + sq * 0.16f + breathe * 0.5f, 1f - sq * 0.16f + breathe, pivot = Offset(cx, base))
    }) {
        // tail: lies on the ground when sad, up and wagging when cheered up
        val up = b.mood
        val wag = sin(b.t * 9f) * up
        val tail = Path().apply {
            moveTo(cx + r * 0.75f, base - r * 0.2f)
            cubicTo(
                cx + r * 1.2f, base - r * 0.05f,
                cx + r * 1.6f, base - r * (0.05f + 0.5f * up),
                cx + r * (1.55f - 0.25f * up + 0.25f * wag), base - r * (0.12f + 1.2f * up)
            )
        }
        drawPath(tail, Outline, style = Stroke(r * 0.3f, cap = StrokeCap.Round))
        drawPath(tail, BodyBottom, style = Stroke(r * 0.2f, cap = StrokeCap.Round))

        // ears droop when sad (the left one more), stand up when cheered up
        drawEar(cx - r * 0.55f, by - r * 0.62f, -(18f + 50f * sad + b.twitch * 10f), r)
        drawEar(cx + r * 0.55f, by - r * 0.62f, 18f + 38f * sad, r)

        // body: a soft round bun
        val body = Path().apply {
            moveTo(cx, by - r * 0.95f)
            cubicTo(cx + r * 0.72f, by - r * 0.95f, cx + r * 1.08f, by - r * 0.45f, cx + r * 1.08f, by + r * 0.25f)
            cubicTo(cx + r * 1.08f, by + r * 0.75f, cx + r * 0.7f, base, cx, base)
            cubicTo(cx - r * 0.7f, base, cx - r * 1.08f, by + r * 0.75f, cx - r * 1.08f, by + r * 0.25f)
            cubicTo(cx - r * 1.08f, by - r * 0.45f, cx - r * 0.72f, by - r * 0.95f, cx, by - r * 0.95f)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(BodyTop, BodyBottom), startY = by - r, endY = base))
        drawPath(body, Outline, style = Stroke(r * 0.05f))
        drawOval(Belly.copy(alpha = 0.85f), Offset(cx - r * 0.5f, by + r * 0.25f), Size(r, r * 0.6f))

        // paws
        for (side in intArrayOf(-1, 1)) {
            val px = cx + side * r * 0.4f
            val py = base - r * 0.1f
            drawOval(BodyTop, Offset(px - r * 0.22f, py - r * 0.13f), Size(r * 0.44f, r * 0.26f))
            drawOval(Outline, Offset(px - r * 0.22f, py - r * 0.13f), Size(r * 0.44f, r * 0.26f), style = Stroke(r * 0.04f))
            for (toe in intArrayOf(-1, 1)) {
                drawLine(
                    Outline.copy(alpha = 0.6f), Offset(px + toe * r * 0.06f, py + r * 0.03f),
                    Offset(px + toe * r * 0.06f, py + r * 0.11f), strokeWidth = r * 0.025f, cap = StrokeCap.Round
                )
            }
        }

        // band-aid on the forehead
        val bx = cx + r * 0.28f
        val bly = by - r * 0.6f
        rotate(-28f, pivot = Offset(bx, bly)) {
            drawRoundRect(BandAid, Offset(bx - r * 0.27f, bly - r * 0.09f), Size(r * 0.54f, r * 0.18f), CornerRadius(r * 0.09f))
            drawRoundRect(
                Outline.copy(alpha = 0.5f), Offset(bx - r * 0.27f, bly - r * 0.09f), Size(r * 0.54f, r * 0.18f),
                CornerRadius(r * 0.09f), style = Stroke(r * 0.025f)
            )
            drawRoundRect(BandAidPad, Offset(bx - r * 0.09f, bly - r * 0.07f), Size(r * 0.18f, r * 0.14f), CornerRadius(r * 0.03f))
            for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) {
                drawCircle(Outline.copy(alpha = 0.35f), r * 0.013f, Offset(bx + dx * r * 0.04f, bly + dy * r * 0.03f))
            }
        }

        // face, turned a little toward where it looks
        val fdx = b.lookX * r * 0.09f
        val fdy = b.lookY * r * 0.06f
        val eyeY = by - r * 0.06f + fdy
        val eyeW = r * 0.27f
        val eyeFull = r * 0.33f
        val happyEyes = b.petting || b.mood > 0.85f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * r * 0.37f + fdx
            val eh = eyeFull * b.blink
            if (happyEyes) {
                val arc = Path().apply {
                    moveTo(ex - r * 0.13f, eyeY + r * 0.05f)
                    quadraticTo(ex, eyeY - r * 0.13f, ex + r * 0.13f, eyeY + r * 0.05f)
                }
                drawPath(arc, EyeInk, style = Stroke(r * 0.06f, cap = StrokeCap.Round))
            } else if (b.blink < 0.3f) {
                val closed = Path().apply {
                    moveTo(ex - r * 0.12f, eyeY)
                    quadraticTo(ex, eyeY + r * 0.06f, ex + r * 0.12f, eyeY)
                }
                drawPath(closed, EyeInk, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
            } else {
                drawOval(EyeInk, Offset(ex - eyeW / 2f, eyeY - eh / 2f), Size(eyeW, eh))
                // teary bottom and wobbly shines
                drawOval(TearBlue.copy(alpha = 0.5f * sad), Offset(ex - eyeW * 0.38f, eyeY + eh * 0.12f), Size(eyeW * 0.76f, eh * 0.3f))
                val wob = sin(b.t * 4f + side) * r * 0.008f * sad
                drawCircle(Color.White, r * 0.07f, Offset(ex - eyeW * 0.18f + b.lookX * r * 0.03f + wob, eyeY - eh * 0.2f + b.lookY * r * 0.02f))
                drawCircle(Color.White.copy(alpha = 0.9f), r * 0.032f, Offset(ex + eyeW * 0.2f + wob, eyeY + eh * 0.12f))
            }
            if (sad > 0.05f) {
                // worried brows (inner end up) and a wet streak down the cheek
                drawLine(
                    EyeInk.copy(alpha = 0.75f * sad), Offset(ex + side * r * 0.13f, eyeY - r * 0.25f),
                    Offset(ex - side * r * 0.09f, eyeY - r * 0.33f), strokeWidth = r * 0.04f, cap = StrokeCap.Round
                )
                if (!happyEyes) {
                    drawLine(
                        TearBlue.copy(alpha = 0.35f * sad), Offset(ex - r * 0.07f, eyeY + eyeFull * 0.42f),
                        Offset(ex - r * 0.09f, eyeY + r * 0.3f), strokeWidth = r * 0.035f, cap = StrokeCap.Round
                    )
                }
            }
            drawOval(
                Blush.copy(alpha = 0.28f + 0.35f * b.mood),
                Offset(cx + side * r * 0.62f - r * 0.13f + fdx, by + r * 0.12f + fdy), Size(r * 0.26f, r * 0.14f)
            )
            for (k in 0..1) {
                val wy = by + r * 0.14f + k * r * 0.09f + fdy
                drawLine(
                    Outline.copy(alpha = 0.45f), Offset(cx + side * r * 0.78f, wy),
                    Offset(cx + side * r * 1.2f, wy - r * 0.06f + k * r * 0.12f + r * 0.07f * sad),
                    strokeWidth = r * 0.022f, cap = StrokeCap.Round
                )
            }
        }

        // nose and mouth: a trembling pout when sad, a cat smile when cheered up
        val nx = cx + fdx
        val ny = by + r * 0.13f + fdy
        val nose = Path().apply {
            moveTo(nx - r * 0.055f, ny - r * 0.03f)
            lineTo(nx + r * 0.055f, ny - r * 0.03f)
            lineTo(nx, ny + r * 0.035f)
            close()
        }
        drawPath(nose, Nose)
        val my = ny + r * 0.07f
        val curve = -1f + 2f * b.mood
        val corner = r * 0.06f * sad - r * 0.03f * b.mood + sin(b.t * 30f) * r * 0.006f * sad
        drawLine(EyeInk, Offset(nx, ny + r * 0.035f), Offset(nx, my), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
        val mouth = Path().apply {
            moveTo(nx - r * 0.15f, my + corner)
            quadraticTo(nx - r * 0.075f, my + curve * r * 0.08f, nx, my)
            quadraticTo(nx + r * 0.075f, my + curve * r * 0.08f, nx + r * 0.15f, my + corner)
        }
        drawPath(mouth, EyeInk, style = Stroke(r * 0.035f, cap = StrokeCap.Round))

        // the tear welling up before it drops
        if (b.tearPhase == 1) {
            val ex = cx - r * 0.37f + fdx - r * 0.07f
            drawDrop(ex, eyeY + eyeFull * 0.42f + b.tearGrow * r * 0.1f, r * 0.065f * b.tearGrow.coerceIn(0.2f, 1f), TearBlue)
        }
    }

    if (b.tearPhase == 2) drawDrop(b.tearX, b.tearY, r * 0.065f, TearBlue)

    for (f in b.fx) {
        val k = f.age / f.life
        val alpha = if (k > 0.7f) (1f - k) / 0.3f else 1f
        when (f.kind) {
            FX_HEART -> drawHeart(f.x + sin(f.age * 5f) * r * 0.08f, f.y, f.size * min(1f, f.age / 0.15f), HeartPink.copy(alpha = alpha))
            FX_SPARKLE -> drawStar(f.x, f.y, f.size * (1f - k * 0.5f), SparkleGold.copy(alpha = alpha))
            else -> drawCircle(TearBlue.copy(alpha = alpha), f.size, Offset(f.x, f.y))
        }
    }
}

/** One ear, its base at ([x], [y]), pointing up and turned [angle] degrees (negative: to the left). */
private fun DrawScope.drawEar(x: Float, y: Float, angle: Float, r: Float) {
    rotate(angle, pivot = Offset(x, y)) {
        val outer = Path().apply {
            moveTo(x - r * 0.32f, y + r * 0.1f)
            quadraticTo(x - r * 0.2f, y - r * 0.4f, x - r * 0.04f, y - r * 0.58f)
            quadraticTo(x, y - r * 0.64f, x + r * 0.04f, y - r * 0.58f)
            quadraticTo(x + r * 0.2f, y - r * 0.4f, x + r * 0.32f, y + r * 0.1f)
            close()
        }
        drawPath(outer, BodyTop)
        drawPath(outer, Outline, style = Stroke(r * 0.05f))
        val inner = Path().apply {
            moveTo(x - r * 0.18f, y + r * 0.05f)
            quadraticTo(x - r * 0.1f, y - r * 0.28f, x, y - r * 0.42f)
            quadraticTo(x + r * 0.1f, y - r * 0.28f, x + r * 0.18f, y + r * 0.05f)
            close()
        }
        drawPath(inner, EarInner)
    }
}

private fun DrawScope.drawHeart(x: Float, y: Float, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(x, y + s * 0.9f)
        cubicTo(x - s * 1.3f, y, x - s * 0.7f, y - s, x, y - s * 0.35f)
        cubicTo(x + s * 0.7f, y - s, x + s * 1.3f, y, x, y + s * 0.9f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawStar(x: Float, y: Float, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) radius else radius * 0.45f
        val a = (-90f + i * 36f) * PI_F / 180f
        val px = x + cos(a) * rr
        val py = y + sin(a) * rr
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawDrop(x: Float, y: Float, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(x, y - r * 1.7f)
        cubicTo(x + r, y - r * 0.4f, x + r, y + r, x, y + r)
        cubicTo(x - r, y + r, x - r, y - r * 0.4f, x, y - r * 1.7f)
        close()
    }
    drawPath(path, color)
}
