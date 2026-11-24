package ai.antigem.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import ai.antigem.i18n.L

/*
 * "Gemmy": a small cartoon buddy that floats over every screen of the app.
 *
 * Drawn entirely with a Compose Canvas: no images, no animation libraries. One frame loop advances a tiny
 * simulation (springs, fling physics, particles). Only the draw and placement phases read its per-frame state,
 * so it animates without recomposing.
 *
 * Power: full frame rate only while something moves; ~30 fps while just hovering. After 5 s without interaction it
 * eases into a still pose and the loop stops requesting frames entirely (zero CPU), waking only for a blink
 * (every 3–7 s), an idle trick (every 18–42 s), a snore while asleep, or a glint while the agent works; any touch
 * or agent state change wakes it at once. Compose also pauses the loop while the app is in the background.
 *
 * Interactions: drag & fling it (it bounces off the screen edges; shake it and it gets dizzy), tap (random
 * reactions; poke it 5 times fast and it gets angry), double-tap (spin), long-press (menu: Dance / Nap / Hide).
 * On its own it wanders, waves, hops, spins, yawns and falls asleep when left alone. While the agent is working
 * it thinks (glowing antenna, orbiting dots) and celebrates when the turn finishes.
 */

private const val ID_BODY = "mascot_body"
private const val ID_BUBBLE = "mascot_bubble"
private const val ID_MENU = "mascot_menu"

private const val PI_F = 3.1415927f
private const val MAX_PARTICLES = 40
private const val P_HEART = 0
private const val P_SPARKLE = 1
private const val P_Z = 2
private const val P_GLINT = 3 // slow sparkle rising from the antenna while the agent works
private const val SLEEP_AFTER_S = 50f // falls asleep after this long untouched

private val MascotInk = Color(0xFF2E2A6B)
private val MascotText = Color(0xFF1B1840)

private val HELLO_LINES get() = listOf("Hi! 👋", L.components.floatingMascot.heyThere, L.components.floatingMascot.helloFriend)
private val TAP_LINES get() = listOf(
    L.components.floatingMascot.heheThatTickles, L.components.floatingMascot.needAHand, L.components.floatingMascot.imGemmy, L.components.floatingMascot.letsBuildSomethingCool,
    L.components.floatingMascot.bugsWhere, L.components.floatingMascot.youreDoingGreat, L.components.floatingMascot.coffeeBreak, "Boop! 👉"
)
private val GRAB_LINES get() = listOf("Woah! 😲", L.components.floatingMascot.whereAreWeGoing, L.components.floatingMascot.upWeGo, L.components.floatingMascot.carefulWithMe)
private val FLING_LINES get() = listOf("Wheeee! 🚀", "Yaaay! 🎢", L.components.floatingMascot.woohoo)
private val OUCH_LINES get() = listOf("Ouch! 💫", L.components.floatingMascot.oof, "Bonk! 🤕")
private val DIZZY_LINES get() = listOf(L.components.floatingMascot.woahDizzy, L.components.floatingMascot.theRoomIsSpinning)
private val ANGRY_LINES get() = listOf(L.components.floatingMascot.heyStopPoking, L.components.floatingMascot.grrThatsEnough)
private val WAKE_LINES get() = listOf(L.components.floatingMascot.huhImAwake, L.components.floatingMascot.fiveMoreMinutes, L.components.floatingMascot.whaOhHi)
private val WORK_LINES get() = listOf(L.components.floatingMascot.onIt, "Thinking… 🤔", L.components.floatingMascot.agentIsCooking)
private val DONE_LINES get() = listOf("Done! ✨", L.components.floatingMascot.taDa, L.components.floatingMascot.allFinished)

private enum class Mood { NORMAL, HAPPY, LOVE, SURPRISED, DIZZY, SLEEP, ANGRY, THINKING }

private enum class Trick { NONE, WANDER, WAVE, HOP, SPIN, DANCE, LOOK_AROUND, YAWN }

/**
 * The app-wide overlay: shows Gemmy only once the one-time intro was answered (so it never pops up during setup)
 * and while it is enabled in Settings → Appearance.
 */
@Composable
fun GemmyOverlay(
    prefs: ai.antigem.data.preferences.AuthPreferences,
    agentWorking: StateFlow<Boolean>
) {
    val enabled by prefs.isGemmyEnabled.collectAsState(initial = false)
    val introSeen by prefs.hasSeenGemmyIntro.collectAsState(initial = false)
    if (enabled && introSeen) FloatingMascot(agentWorking = agentWorking)
}

@Composable
fun FloatingMascot(
    agentWorking: StateFlow<Boolean>,
    modifier: Modifier = Modifier
) {
    var visible by rememberSaveable { mutableStateOf(true) }
    if (!visible) return

    val density = LocalDensity.current.density
    val sim = remember(density) { MascotSim(density) }
    val art = remember(density) { MascotArt(density) }

    val haptic = LocalHapticFeedback.current
    SideEffect {
        sim.hapticLight = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        sim.hapticStrong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    val working by agentWorking.collectAsState()
    LaunchedEffect(working) { sim.setAgentWorking(working) }

    LaunchedEffect(sim) { runMascotLoop(sim) }

    if (sim.hideRequested) {
        LaunchedEffect(Unit) {
            delay(1100)
            visible = false
        }
    }

    Layout(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        content = {
            Box(Modifier.layoutId(ID_BODY).size(64.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    sim.frame // redraw on every simulated frame
                    drawMascot(sim, art)
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(sim) {
                            detectTapGestures(
                                onPress = {
                                    sim.onPress()
                                    tryAwaitRelease()
                                },
                                onTap = { sim.onTap() },
                                onDoubleTap = { sim.onDoubleTap() },
                                onLongPress = { sim.onLongPress() }
                            )
                        }
                        .pointerInput(sim) {
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = { _ ->
                                    tracker.resetTracking()
                                    sim.onDragStart()
                                },
                                onDragEnd = {
                                    val v = tracker.calculateVelocity()
                                    sim.onDragEnd(v.x, v.y)
                                },
                                onDragCancel = { sim.onDragEnd(0f, 0f) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    sim.onDrag(amount.x, amount.y)
                                    tracker.addPosition(change.uptimeMillis, Offset(sim.x, sim.y))
                                }
                            )
                        }
                )
            }
            AnimatedVisibility(
                visible = sim.speechVisible,
                modifier = Modifier.layoutId(ID_BUBBLE),
                enter = fadeIn() + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f))
            ) {
                MascotSpeechBubble(sim.speechText)
            }
            AnimatedVisibility(
                visible = sim.menuOpen,
                modifier = Modifier.layoutId(ID_MENU),
                enter = fadeIn() + scaleIn(initialScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f))
            ) {
                MascotMenu(onDance = sim::dance, onNap = sim::nap, onHide = sim::hide)
            }
        }
    ) { measurables, constraints ->
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        if (bw != sim.boundsW || bh != sim.boundsH) {
            sim.boundsW = bw
            sim.boundsH = bh
            sim.poke() // e.g. keyboard opened: wake so it gets pushed back inside the new bounds
        }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables.first { it.layoutId == ID_BODY }.measure(loose)
        val bubble = measurables.firstOrNull { it.layoutId == ID_BUBBLE }
            ?.measure(loose.copy(maxWidth = min(loose.maxWidth, 240.dp.roundToPx())))
        val menu = measurables.firstOrNull { it.layoutId == ID_MENU }?.measure(loose)
        val pad = 6.dp.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            sim.frame // re-place (no re-measure) on every simulated frame
            if (sim.x < 0f) return@layout // position not chosen yet (first frame)
            body.place((sim.x - sim.half).roundToInt(), (sim.y - sim.half).roundToInt())
            if (bubble != null) bubble.place(anchorAboveHead(sim, bubble.width, bubble.height, constraints.maxWidth, pad))
            if (menu != null) menu.place(anchorAboveHead(sim, menu.width, menu.height, constraints.maxWidth, pad))
        }
    }
}

/** Centers a popup above the mascot's head, kept on screen; drops below it when there is no room above. */
private fun anchorAboveHead(s: MascotSim, w: Int, h: Int, boundsW: Int, pad: Int): IntOffset {
    val px = (s.x - w / 2f).roundToInt().coerceIn(pad, max(pad, boundsW - w - pad))
    var py = (s.y - s.r * 1.8f - h).roundToInt()
    if (py < pad) py = (s.y + s.r * 1.45f).roundToInt()
    return IntOffset(px, py)
}

/**
 * The single frame loop: fixed 1/60 s sub-steps, then one state write that redraws/re-places the mascot.
 * Parks with no frames at all while the mascot rests (see the header comment).
 */
private suspend fun runMascotLoop(sim: MascotSim) {
    var last = 0L
    while (true) {
        val now = withFrameNanos { it }
        var dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.25f)
        last = now
        while (dt > 0.0005f) {
            val h = min(dt, 1f / 60f)
            sim.step(h)
            dt -= h
        }
        sim.frame = now
        if (sim.canDeepRest()) {
            // Still pose: request no frames at all until the next scheduled wake or a touch/agent event.
            sim.beginRest()
            withTimeoutOrNull(sim.msUntilNextWake()) { sim.wakeSignal.receive() }
            sim.endRest()
            last = 0L
        } else {
            val rest = sim.calmDelayMs()
            if (rest > 0L) delay(rest)
        }
    }
}

/**
 * Gemmy in place (for the intro dialog and Settings): same drawing and behaviour, drawn [zoom]× bigger, stays put.
 * Tap, double-tap and poke it; it blinks, hops, waves and dozes off like the real one.
 */
@Composable
fun GemmyPreview(
    modifier: Modifier = Modifier,
    zoom: Float = 1f
) {
    val density = LocalDensity.current.density
    val sim = remember(density) {
        // A huge virtual area with Gemmy in the middle: it never hits an edge, and the first-frame greeting is skipped.
        MascotSim(density).apply {
            boundsW = 100_000f
            boundsH = 100_000f
            x = 50_000f
            y = 50_000f
        }
    }
    val art = remember(density) { MascotArt(density) }
    val haptic = LocalHapticFeedback.current
    SideEffect { sim.hapticLight = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
    LaunchedEffect(sim) { runMascotLoop(sim) }

    // Taller than wide: the antenna and the ground shadow reach well past the 48dp body.
    Canvas(
        modifier
            .size(width = 72.dp * zoom, height = 96.dp * zoom)
            .pointerInput(sim) {
                detectTapGestures(
                    onPress = {
                        sim.onPress()
                        tryAwaitRelease()
                    },
                    onTap = { sim.onTap() },
                    onDoubleTap = { sim.onDoubleTap() }
                )
            }
    ) {
        sim.frame // redraw on every simulated frame
        scale(zoom, pivot = center) { drawMascot(sim, art) }
    }
}

@Composable
private fun MascotSpeechBubble(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .shadow(6.dp, shape)
            .background(Color.White, shape)
            .border(1.5.dp, MascotInk, shape)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = MascotText,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MascotMenu(onDance: () -> Unit, onNap: () -> Unit, onHide: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .shadow(8.dp, shape)
            .background(Color.White, shape)
            .border(1.5.dp, MascotInk, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        MascotMenuChip("💃", L.components.floatingMascot.dance, onDance)
        MascotMenuChip("😴", L.components.floatingMascot.nap, onNap)
        MascotMenuChip("👋", L.common.hide, onHide)
    }
}

@Composable
private fun MascotMenuChip(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = MascotText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Simulation
// ---------------------------------------------------------------------------------------------------------------

/** A damped spring on one value (semi-implicit Euler). */
private class Spring1 {
    var v = 0f
    var vel = 0f
    private var target = 0f

    fun step(target: Float, k: Float, c: Float, dt: Float) {
        this.target = target
        vel += (k * (target - v) - c * vel) * dt
        v += vel * dt
    }

    fun settled(eps: Float) = abs(vel) < eps * 4f && abs(v - target) < eps
}

/**
 * All mascot state. Positions are in px, in the coordinates of the full-screen overlay; [x]/[y] is the body center.
 * Plain fields on purpose: [frame] is the only per-frame snapshot state, read only by draw and placement.
 */
private class MascotSim(private val d: Float) {
    var frame by mutableLongStateOf(0L)
    var speechText by mutableStateOf("")
    var speechVisible by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var hideRequested by mutableStateOf(false)

    var hapticLight: () -> Unit = {}
    var hapticStrong: () -> Unit = {}

    val r = 24f * d // body radius
    val half = 32f * d // half of the 64dp touch box

    var boundsW = 0f
    var boundsH = 0f

    var x = -1f
    var y = -1f
    var vx = 0f
    var vy = 0f
    var time = 0f // real seconds; drives timers (kept exact across rests, see syncClock)

    /** Clock for cosmetic motion (bob, breathing, glow...). Advances at [liveliness] speed, so it eases to a halt
     *  before a rest and resumes from the same phase afterwards: the still pose never jumps. */
    var animT = 0f
    private var liveliness = 1f

    // Deep rest: the frame loop is parked on [wakeSignal] with a timeout.
    val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var resting = false
    private var restStartNanos = 0L
    private var restStartTime = 0f
    private var aliveParticles = 0

    var held = false
    private var heldTime = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var dragVx = 0f
    private var dragVy = 0f
    private var shakeFlips = 0f
    private var lastDragSign = 0

    val squash = Spring1() // >0 flatter/wider, <0 thinner/taller
    val tilt = Spring1() // degrees
    val antenna = Spring1() // degrees
    val legs = Spring1() // degrees
    var hop = 0f // visual vertical offset (px, negative = up)
    private var hopV = 0f
    var spin = 0f
    var wave = 0f // 0..1 right arm raised and waving
    var yawn = 0f

    var lookX = 0f
    var lookY = 0f
    private var lookTX = 0f
    private var lookTY = 0f
    private var nextGlance = 1.5f
    var blink = 0f
    private var blinkT = -1f
    private var nextBlink = 2.5f

    private var tempMood = Mood.NORMAL
    private var tempMoodUntil = 0f
    var sleeping = false
    private var agentWorking = false
    private var workStartedAt = 0f
    var trick = Trick.NONE
    var trickT = 0f
    private var trickDur = 0f
    private var lastBeat = -1
    private var wanderTX = 0f
    private var wanderTY = 0f
    private var nextTrickAt = 8f
    private var lastInteraction = 0f
    private var wokeAt = -10f
    private var pokes = 0
    private var lastPokeAt = -10f
    private var speechUntil = 0f
    private var menuUntil = 0f
    private var nextFx = 0f

    // Particle pool (container coordinates), reused round-robin: no allocations while animating.
    val pX = FloatArray(MAX_PARTICLES)
    val pY = FloatArray(MAX_PARTICLES)
    private val pVX = FloatArray(MAX_PARTICLES)
    private val pVY = FloatArray(MAX_PARTICLES)
    val pLife = FloatArray(MAX_PARTICLES)
    val pMax = FloatArray(MAX_PARTICLES)
    val pRot = FloatArray(MAX_PARTICLES)
    val pSize = FloatArray(MAX_PARTICLES)
    val pType = IntArray(MAX_PARTICLES)
    private var nextSlot = 0
    private var busyParticles = 0

    fun mood(): Mood = when {
        time < tempMoodUntil -> tempMood
        held -> if (heldTime < 0.9f || hypot(dragVx, dragVy) > 900f * d) Mood.SURPRISED else Mood.HAPPY
        sleeping -> Mood.SLEEP
        trick == Trick.DANCE -> Mood.HAPPY
        agentWorking -> Mood.THINKING
        else -> Mood.NORMAL
    }

    private fun minX() = r * 1.3f
    private fun maxX() = max(minX(), boundsW - r * 1.3f)
    private fun minY() = r * 1.9f // room for the antenna
    private fun maxY() = max(minY(), boundsH - r * 1.5f)

    // ---- frame step ----

    fun step(dt: Float) {
        time += dt
        if (boundsW <= 0f || boundsH <= 0f) return
        if (x < 0f) {
            x = maxX() - 6f * d
            y = (boundsH * 0.62f).coerceIn(minY(), maxY())
            prevX = x
            prevY = y
            lastInteraction = time
            nextTrickAt = time + 6f
            setMood(Mood.HAPPY, 1.2f)
            say(HELLO_LINES.random(), 2f)
            jump(240f)
        }

        if (speechVisible && time > speechUntil) speechVisible = false
        if (menuOpen && time > menuUntil) menuOpen = false

        stepBody(dt)
        stepFace(dt)
        stepBehaviour(dt)
        stepParticles(dt)

        val targetLife = if (restEligible()) 0f else 1f
        liveliness = if (liveliness < targetLife) min(targetLife, liveliness + dt * 2.5f) else max(targetLife, liveliness - dt * 2.5f)
        animT += dt * liveliness
    }

    // ---- low-power rest ----

    /** Untouched for 5 s and nothing moving or flying around. */
    private fun restEligible(): Boolean =
        time - lastInteraction > 5f && !held && !menuOpen && trick == Trick.NONE &&
            hop == 0f && hopV == 0f && vx == 0f && vy == 0f && wave == 0f && aliveParticles == 0 &&
            squash.settled(0.004f) && tilt.settled(0.3f) && antenna.settled(0.3f) && legs.settled(0.3f)

    /** Rest eligible, motion fully eased out, and no blink or glance in progress. */
    fun canDeepRest(): Boolean =
        liveliness <= 0f && blinkT < 0f && restEligible() &&
            abs(lookTX - lookX) < 0.01f && abs(lookTY - lookY) < 0.01f

    /** How long the loop may sleep: until the next blink, trick, snore/glint, falling asleep, bubble or mood change. */
    fun msUntilNextWake(): Long {
        var wake = time + 60f
        if (!sleeping) wake = min(wake, nextBlink)
        if (!sleeping && !agentWorking) {
            wake = min(wake, nextTrickAt)
            wake = min(wake, lastInteraction + SLEEP_AFTER_S)
        }
        if (sleeping || agentWorking) wake = min(wake, nextFx)
        if (speechVisible) wake = min(wake, speechUntil)
        if (tempMoodUntil > time) wake = min(wake, tempMoodUntil)
        return ((wake - time) * 1000f).toLong().coerceIn(30L, 60_000L)
    }

    fun beginRest() {
        while (wakeSignal.tryReceive().isSuccess) { } // drop stale pokes
        resting = true
        restStartNanos = System.nanoTime()
        restStartTime = time
    }

    fun endRest() {
        syncClock()
        resting = false
    }

    /** While resting no frames run, so bring [time] up to the real clock before anything reads it. */
    private fun syncClock() {
        if (resting) time = restStartTime + (System.nanoTime() - restStartNanos) / 1_000_000_000f
    }

    /** Wakes the loop from a rest (touch, agent state, bounds change). */
    fun poke() {
        syncClock()
        wakeSignal.trySend(Unit)
    }

    private fun stepBody(dt: Float) {
        if (held) {
            heldTime += dt
            val k = min(1f, dt * 14f)
            dragVx += ((x - prevX) / dt - dragVx) * k
            dragVy += ((y - prevY) / dt - dragVy) * k
            prevX = x
            prevY = y
            // Quick left/right reversals = being shaken.
            val sign = when {
                dragVx > 350f * d -> 1
                dragVx < -350f * d -> -1
                else -> 0
            }
            if (sign != 0 && sign != lastDragSign) {
                if (lastDragSign != 0) shakeFlips += 1f
                lastDragSign = sign
            }
            shakeFlips = max(0f, shakeFlips - dt * 1.2f)
            if (shakeFlips >= 4f) setMood(Mood.DIZZY, 2.6f)
            tilt.step((dragVx / d * 0.05f).coerceIn(-32f, 32f), 160f, 9f, dt)
            legs.step((dragVx / d * 0.08f).coerceIn(-45f, 45f), 120f, 6f, dt)
            antenna.step((-dragVx / d * 0.06f).coerceIn(-40f, 40f), 90f, 4f, dt)
        } else {
            if (trick == Trick.WANDER) {
                val dx = wanderTX - x
                val dy = wanderTY - y
                val dist = hypot(dx, dy)
                if (dist < 5f * d) {
                    endTrick()
                } else {
                    val sp = min(70f * d, dist * 1.8f)
                    val k = min(1f, dt * 4f)
                    vx += (dx / dist * sp - vx) * k
                    vy += (dy / dist * sp - vy) * k
                }
            }
            if (trick != Trick.WANDER) {
                val f = exp(-2.2f * dt)
                vx *= f
                vy *= f
                if (abs(vx) < 3f * d && abs(vy) < 3f * d) {
                    vx = 0f
                    vy = 0f
                }
            }
            x += vx * dt
            y += vy * dt
            collide()
            dragVx = 0f
            dragVy = 0f
            prevX = x
            prevY = y
            val tiltTarget = if (trick == Trick.DANCE) sin(trickT * 9f) * 14f else (vx / d * 0.045f).coerceIn(-22f, 22f)
            tilt.step(tiltTarget, 140f, 10f, dt)
            legs.step(0f, 90f, 5f, dt)
            antenna.step((-vx / d * 0.06f).coerceIn(-35f, 35f), 90f, 4f, dt)
        }
        squash.step(0f, 320f, 13f, dt)

        // Little hop: a visual offset with gravity, independent of the world position.
        if (hop < 0f || hopV != 0f) {
            hopV += 2200f * d * dt
            hop += hopV * dt
            if (hop >= 0f) {
                if (hopV > 150f * d) squash.vel += min(hopV / d / 110f, 5f)
                hop = 0f
                hopV = 0f
            }
        }
    }

    private fun collide() {
        val lx = minX()
        val hx = maxX()
        val ly = minY()
        val hy = maxY()
        if (x < lx) {
            x = lx
            if (vx < 0f) { impact(-vx, sideWall = true); vx = -vx * 0.55f }
        } else if (x > hx) {
            x = hx
            if (vx > 0f) { impact(vx, sideWall = true); vx = -vx * 0.55f }
        }
        if (y < ly) {
            y = ly
            if (vy < 0f) { impact(-vy, sideWall = false); vy = -vy * 0.55f }
        } else if (y > hy) {
            y = hy
            if (vy > 0f) { impact(vy, sideWall = false); vy = -vy * 0.55f }
        }
    }

    private fun impact(speed: Float, sideWall: Boolean) {
        val s = speed / d
        if (s < 120f) return
        val amt = min(s / 280f, 8f)
        if (sideWall) squash.vel -= amt else squash.vel += amt
        antenna.vel += (if (sideWall) amt else -amt) * 28f
        if (s > 700f) {
            hapticLight()
            burst(P_SPARKLE, 3)
        }
        if (s > 1700f) {
            setMood(Mood.DIZZY, 2.2f)
            if (!speechVisible) say(OUCH_LINES.random(), 1.4f)
        }
    }

    private fun stepFace(dt: Float) {
        when {
            held -> {
                lookTX = (dragVx / (500f * d)).coerceIn(-1f, 1f)
                lookTY = (dragVy / (500f * d)).coerceIn(-1f, 1f)
            }
            mood() == Mood.THINKING -> {
                lookTX = 0.55f + sin(animT * 1.3f) * 0.3f // eased clock: settles when resting
                lookTY = -0.85f
            }
            trick == Trick.LOOK_AROUND -> {
                lookTX = when {
                    trickT < 0.7f -> -1f
                    trickT < 1.5f -> 1f
                    else -> 0f
                }
                lookTY = 0.1f
            }
            vx != 0f || vy != 0f -> {
                val sp = hypot(vx, vy)
                lookTX = vx / sp
                lookTY = vy / sp * 0.7f
            }
            time > nextGlance -> {
                if (Random.nextInt(3) == 0) {
                    lookTX = 0f
                    lookTY = 0.15f
                } else {
                    lookTX = Random.nextFloat() * 1.6f - 0.8f
                    lookTY = Random.nextFloat() - 0.4f
                }
                nextGlance = time + 1.2f + Random.nextFloat() * 2.8f
            }
        }
        val k = min(1f, dt * 10f)
        lookX += (lookTX - lookX) * k
        lookY += (lookTY - lookY) * k

        if (blinkT >= 0f) {
            blinkT += dt
            val halfBlink = 0.075f
            blink = (if (blinkT < halfBlink) blinkT / halfBlink else 1f - (blinkT - halfBlink) / halfBlink).coerceIn(0f, 1f)
            if (blinkT >= halfBlink * 2f) {
                blinkT = -1f
                blink = 0f
            }
        } else if (time > nextBlink && !sleeping) {
            blinkT = 0f
            val idle = time - lastInteraction > 5f
            nextBlink = time + when {
                Random.nextInt(5) == 0 -> 0.3f // double blink
                idle -> 3f + Random.nextFloat() * 4f // rarer while resting: each blink is a short wake
                else -> 2f + Random.nextFloat() * 3.5f
            }
        }
    }

    private fun stepBehaviour(dt: Float) {
        if (trick != Trick.NONE) {
            trickT += dt
            when (trick) {
                Trick.WAVE -> wave = min(1f, trickT * 5f) * ((trickDur - trickT) / 0.3f).coerceIn(0f, 1f)
                Trick.SPIN -> {
                    val p = (trickT / trickDur).coerceIn(0f, 1f)
                    spin = 360f * p * p * (3f - 2f * p)
                }
                Trick.DANCE -> {
                    val beat = (trickT / 0.42f).toInt()
                    if (beat != lastBeat) {
                        lastBeat = beat
                        jump(160f)
                        if (beat % 2 == 0) burst(P_SPARKLE, 2)
                    }
                }
                Trick.HOP -> {
                    val beat = (trickT / 0.36f).toInt()
                    if (beat != lastBeat && beat < 3) {
                        lastBeat = beat
                        jump(210f)
                    }
                }
                Trick.YAWN -> yawn = sin((trickT / trickDur).coerceIn(0f, 1f) * PI_F)
                else -> Unit
            }
            if (trick != Trick.NONE && trickT >= trickDur) endTrick()
        } else if (wave > 0f) {
            wave = max(0f, wave - dt * 4f)
        }

        if (!held && !sleeping && !agentWorking && trick == Trick.NONE) {
            if (time - lastInteraction > SLEEP_AFTER_S) {
                sleeping = true
                nextFx = time + 0.4f
            } else if (time > nextTrickAt) {
                val roll = Random.nextInt(100)
                startTrick(
                    when {
                        roll < 34 -> Trick.WANDER
                        roll < 52 -> Trick.LOOK_AROUND
                        roll < 64 -> Trick.WAVE
                        roll < 75 -> Trick.HOP
                        roll < 84 -> Trick.SPIN
                        roll < 93 -> Trick.YAWN
                        else -> Trick.DANCE
                    }
                )
                nextTrickAt = time + 18f + Random.nextFloat() * 24f
            }
        }

        if (time > nextFx) {
            val idle = time - lastInteraction > 5f
            if (sleeping) {
                spawn(P_Z, x + r * 0.55f, y - r * 1.1f)
                nextFx = time + if (idle) 8f + Random.nextFloat() * 4f else 1.1f
            } else if (agentWorking && !held) {
                spawn(P_GLINT, x, y - r * 1.7f)
                nextFx = time + if (idle) 4f else 0.75f
            }
        }
    }

    private fun stepParticles(dt: Float) {
        var busy = 0
        var alive = 0
        val damp = exp(-3f * dt)
        for (i in 0 until MAX_PARTICLES) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            when (pType[i]) {
                P_SPARKLE, P_GLINT -> {
                    pVX[i] *= damp
                    pVY[i] *= damp
                    pRot[i] += 200f * dt
                }
                else -> pX[i] += sin((pMax[i] - pLife[i]) * 5f + i) * 14f * d * dt
            }
            pX[i] += pVX[i] * dt
            pY[i] += pVY[i] * dt
            if (pLife[i] > 0f) {
                alive++
                if (pType[i] == P_HEART || pType[i] == P_SPARKLE) busy++
            }
        }
        busyParticles = busy
        aliveParticles = alive
    }

    /** Frame pacing: full rate while anything moves, ~30 fps when just hovering, ~20 fps asleep. */
    fun calmDelayMs(): Long {
        val busy = held || trick != Trick.NONE || hop != 0f || hopV != 0f || vx != 0f || vy != 0f ||
            !squash.settled(0.004f) || !tilt.settled(0.3f) || !antenna.settled(0.3f) || !legs.settled(0.3f) ||
            busyParticles > 0
        return when {
            busy -> 0L
            sleeping -> 50L
            else -> 16L
        }
    }

    // ---- helpers ----

    private fun setMood(m: Mood, dur: Float) {
        tempMood = m
        tempMoodUntil = time + dur
    }

    private fun say(text: String, dur: Float = 2.4f) {
        speechText = text
        speechVisible = true
        speechUntil = time + dur
        menuOpen = false
    }

    private fun jump(power: Float) {
        if (hop > -4f * d) hopV = -power * d
    }

    private fun touched() {
        lastInteraction = time
        if (sleeping) {
            sleeping = false
            wokeAt = time
            setMood(Mood.SURPRISED, 0.9f)
            jump(240f)
            say(WAKE_LINES.random(), 1.8f)
        }
    }

    private fun startTrick(t: Trick) {
        trick = t
        trickT = 0f
        lastBeat = -1
        spin = 0f
        trickDur = when (t) {
            Trick.WANDER -> 7f
            Trick.WAVE -> 1.8f
            Trick.HOP -> 1.2f
            Trick.SPIN -> 0.75f
            Trick.DANCE -> 3.4f
            Trick.LOOK_AROUND -> 2.2f
            Trick.YAWN -> 1.8f
            Trick.NONE -> 0f
        }
        when (t) {
            Trick.WANDER -> pickWanderTarget()
            Trick.WAVE -> if (!speechVisible && Random.nextInt(2) == 0) say(HELLO_LINES.random(), 1.8f)
            Trick.YAWN -> if (!speechVisible) say("*yawn* 🥱", 1.6f)
            else -> Unit
        }
    }

    private fun endTrick() {
        trick = Trick.NONE
        trickT = 0f
        spin = 0f
        yawn = 0f
        lastBeat = -1
    }

    private fun pickWanderTarget() {
        val ang = Random.nextFloat() * 2f * PI_F
        val dist = (80f + Random.nextFloat() * 140f) * d
        val loY = max(minY(), boundsH * 0.15f).coerceAtMost(maxY())
        val hiY = min(maxY(), boundsH * 0.85f).coerceAtLeast(loY)
        wanderTX = (x + cos(ang) * dist).coerceIn(minX(), maxX())
        wanderTY = (y + sin(ang) * dist).coerceIn(loY, hiY)
    }

    private fun spawn(type: Int, px: Float, py: Float) {
        val i = nextSlot
        nextSlot = (nextSlot + 1) % MAX_PARTICLES
        pType[i] = type
        pX[i] = px
        pY[i] = py
        when (type) {
            P_HEART -> {
                pVX[i] = (Random.nextFloat() - 0.5f) * 70f * d
                pVY[i] = -(60f + Random.nextFloat() * 50f) * d
                pLife[i] = 1.2f + Random.nextFloat() * 0.4f
                pSize[i] = (6f + Random.nextFloat() * 3f) * d
                pRot[i] = (Random.nextFloat() - 0.5f) * 30f
            }
            P_SPARKLE, P_GLINT -> {
                val slow = type == P_GLINT
                val a = Random.nextFloat() * 2f * PI_F
                val sp = (if (slow) 20f + Random.nextFloat() * 25f else 90f + Random.nextFloat() * 140f) * d
                pVX[i] = cos(a) * sp
                pVY[i] = sin(a) * sp - (if (slow) 30f * d else 0f)
                pLife[i] = if (slow) 1.1f else 0.5f + Random.nextFloat() * 0.3f
                pSize[i] = (if (slow) 3.5f else 4.5f + Random.nextFloat() * 3f) * d
                pRot[i] = Random.nextFloat() * 90f
            }
            else -> {
                pVX[i] = 14f * d
                pVY[i] = -24f * d
                pLife[i] = 2.4f
                pSize[i] = 5f * d
                pRot[i] = -12f
            }
        }
        pMax[i] = pLife[i]
    }

    private fun burst(type: Int, n: Int) {
        repeat(n) { spawn(type, x + (Random.nextFloat() - 0.5f) * r, y - r * 0.6f + hop) }
    }

    // ---- outside events ----

    fun setAgentWorking(working: Boolean) {
        if (working == agentWorking) return
        poke()
        agentWorking = working
        lastInteraction = time
        if (working) {
            workStartedAt = time
            sleeping = false
            if (trick == Trick.WANDER || trick == Trick.YAWN) endTrick()
            if (Random.nextInt(2) == 0) say(WORK_LINES.random(), 1.8f)
        } else if (time - workStartedAt > 3f) {
            setMood(Mood.HAPPY, 2f)
            jump(300f)
            burst(P_SPARKLE, 10)
            say(DONE_LINES.random(), 2.2f)
        }
    }

    // ---- gestures ----

    fun onPress() {
        poke()
        touched()
        squash.vel += 3.5f
        lookTX = 0f
        lookTY = 0.2f
        nextGlance = time + 1.5f
    }

    fun onTap() {
        poke()
        if (menuOpen) {
            menuOpen = false
            return
        }
        if (time - wokeAt < 1f) return // this tap just woke it up
        pokes = if (time - lastPokeAt < 1.3f) pokes + 1 else 1
        lastPokeAt = time
        hapticLight()
        if (pokes >= 5) {
            pokes = 0
            setMood(Mood.ANGRY, 2.4f)
            say(ANGRY_LINES.random())
            tilt.vel += 260f
            squash.vel -= 4f
            return
        }
        when (Random.nextInt(4)) {
            0 -> {
                setMood(Mood.HAPPY, 1.4f)
                jump(260f)
                burst(P_HEART, 3)
            }
            1 -> {
                setMood(Mood.HAPPY, 1.6f)
                jump(200f)
                say(TAP_LINES.random())
            }
            2 -> {
                setMood(Mood.SURPRISED, 0.8f)
                squash.vel -= 6f
                say("Boop! 👉", 1.4f)
            }
            else -> {
                setMood(Mood.LOVE, 1.4f)
                burst(P_HEART, 5)
            }
        }
    }

    fun onDoubleTap() {
        poke()
        touched()
        menuOpen = false
        startTrick(Trick.SPIN)
        burst(P_SPARKLE, 8)
        say("Wheee! ✨", 1.4f)
        hapticLight()
    }

    fun onLongPress() {
        poke()
        touched()
        hapticStrong()
        speechVisible = false
        menuOpen = true
        menuUntil = time + 5f
        setMood(Mood.LOVE, 1.2f)
        burst(P_HEART, 2)
    }

    fun onDragStart() {
        poke()
        touched()
        held = true
        heldTime = 0f
        vx = 0f
        vy = 0f
        if (trick != Trick.NONE) endTrick()
        menuOpen = false
        shakeFlips = 0f
        lastDragSign = 0
        prevX = x
        prevY = y
        dragVx = 0f
        dragVy = 0f
        if (Random.nextInt(3) == 0) say(GRAB_LINES.random(), 1.6f)
        hapticLight()
    }

    fun onDrag(dx: Float, dy: Float) {
        if (!held) return
        poke()
        x = (x + dx).coerceIn(minX(), maxX())
        y = (y + dy).coerceIn(minY(), maxY())
        lastInteraction = time
    }

    fun onDragEnd(velX: Float, velY: Float) {
        if (!held) return
        poke()
        held = false
        lastInteraction = time
        val maxV = 4200f * d
        vx = velX.coerceIn(-maxV, maxV)
        vy = velY.coerceIn(-maxV, maxV)
        val speed = hypot(vx, vy) / d
        when {
            time < tempMoodUntil && tempMood == Mood.DIZZY -> say(DIZZY_LINES.random(), 2f)
            speed > 1400f -> say(FLING_LINES.random(), 1.4f)
            speed < 150f -> {
                vx = 0f
                vy = 0f
                squash.vel += 5f
                setMood(Mood.HAPPY, 0.7f)
            }
        }
        shakeFlips = 0f
        lastDragSign = 0
    }

    // ---- long-press menu ----

    fun dance() {
        poke()
        touched()
        startTrick(Trick.DANCE)
        say(L.components.floatingMascot.letsDance, 1.8f)
    }

    fun nap() {
        poke()
        menuOpen = false
        endTrick()
        sleeping = true
        nextFx = time + 0.4f
        lastInteraction = time
        say(L.components.floatingMascot.napTime, 1.6f)
    }

    fun hide() {
        poke()
        touched()
        startTrick(Trick.WAVE)
        say(L.components.floatingMascot.byeBye, 1.2f)
        hideRequested = true
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Drawing (all shapes built once per density; nothing is allocated per frame except reusing [MascotArt.antennaPath])
// ---------------------------------------------------------------------------------------------------------------

private class MascotArt(val d: Float) {
    val r = 24f * d

    /** Gumdrop body, centered on (0, 0). */
    val bodyPath = Path().apply {
        moveTo(0f, -1.05f * r)
        cubicTo(0.62f * r, -1.05f * r, 1.02f * r, -0.45f * r, 1.02f * r, 0.15f * r)
        cubicTo(1.02f * r, 0.7f * r, 0.6f * r, 0.98f * r, 0f, 0.98f * r)
        cubicTo(-0.6f * r, 0.98f * r, -1.02f * r, 0.7f * r, -1.02f * r, 0.15f * r)
        cubicTo(-1.02f * r, -0.45f * r, -0.62f * r, -1.05f * r, 0f, -1.05f * r)
        close()
    }
    val bodyBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFC9BCFF), Color(0xFF8F7DFF), Color(0xFF5B57E0)),
        startY = -r,
        endY = r
    )

    val outline = MascotInk
    val limb = Color(0xFF5B52D6)
    val pupil = Color(0xFF1B1840)
    val cheek = Color(0xFFFF8FB8)
    val tongue = Color(0xFFFF7A9A)
    val gem = Color(0xFFFFD45E)
    val heartColor = Color(0xFFFF5C8A)
    val sparkleColor = Color(0xFFFFE27A)
    val zColor = Color(0xFFA9B4FF)

    val outlineStroke = Stroke(width = 2.2f * d, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = 1.3f * d, cap = StrokeCap.Round)
    val featureStroke = Stroke(width = 2.2f * d, cap = StrokeCap.Round)

    // Unit-size shapes (about -1..1), drawn with a scale transform; their strokes are in unit space.
    val unitOutline = Stroke(width = 0.16f, join = StrokeJoin.Round)
    val zStroke = Stroke(width = 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val antennaPath = Path()

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
}

private fun DrawScope.drawMascot(s: MascotSim, a: MascotArt) {
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
    val bob = if (s.held) 0f else sin(t * bobSpeed) * (if (sleeping) 1.5f else 3f) * d
    val breathe = sin(t * bobSpeed + 1.1f) * (if (sleeping) 0.035f else 0.018f)
    val cx = size.width / 2f
    val cy = size.height / 2f + bob + s.hop

    // Soft shadow on the "ground" line: shrinks while it hops, faint while held.
    val lift = (-(bob + s.hop) / (40f * d)).coerceIn(0f, 1f)
    val shadowW = r * 1.5f * (1f - 0.35f * lift)
    drawOval(
        color = Color.Black.copy(alpha = 0.16f * (1f - 0.5f * lift) * (if (s.held) 0.4f else 1f)),
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
        drawLimbs(s, a, mood)
        drawPath(a.bodyPath, a.bodyBrush)
        if (mood == Mood.ANGRY) drawPath(a.bodyPath, Color(0xFFFF3B3B).copy(alpha = 0.28f))
        drawOval(Color.White.copy(alpha = 0.16f), topLeft = Offset(-0.55f * r, 0.18f * r), size = Size(1.1f * r, 0.66f * r))
        drawPath(a.bodyPath, a.outline, style = a.outlineStroke)
        drawOval(Color.White.copy(alpha = 0.6f), topLeft = Offset(-0.68f * r, -0.82f * r), size = Size(0.36f * r, 0.2f * r))
        drawAntenna(s, a, mood)
        drawFace(s, a, mood)
    }

    // The canvas center is where the body is (overlay: the 64dp box around x/y; preview: a zoomed canvas).
    drawParticles(s, a, s.x - size.width / 2f, s.y - size.height / 2f)
}

private fun DrawScope.drawLimbs(s: MascotSim, a: MascotArt, mood: Mood) {
    val r = a.r
    val t = s.animT

    // Feet: paddle while wandering, dangle and swing while held.
    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        val lift = if (s.trick == Trick.WANDER) max(0f, sin(t * 11f + side * PI_F)) * 0.14f * r else 0f
        val dangle = if (s.held) 0.12f * r else 0f
        val swing = s.legs.v + if (s.held) sin(t * 9f + side * PI_F) * 6f else 0f
        withTransform({
            translate(sgn * 0.42f * r, 0.84f * r + dangle - lift)
            rotate(swing, Offset(0f, -0.35f * r))
        }) {
            drawOval(a.limb, topLeft = Offset(-0.21f * r, -0.12f * r), size = Size(0.42f * r, 0.3f * r))
            drawOval(a.outline, topLeft = Offset(-0.21f * r, -0.12f * r), size = Size(0.42f * r, 0.3f * r), style = a.thinStroke)
        }
    }

    // Arms: drawn pointing right, mirrored for the left one. Angle > 0 = down, < 0 = up.
    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        var ang = 28f + sin(t * 2.1f + side) * 4f
        when {
            s.held -> ang = -55f + sin(t * 16f + side * PI_F) * 14f
            s.trick == Trick.DANCE -> ang = -60f + sin(t * 9f + side * PI_F) * 45f
            mood == Mood.ANGRY -> ang = 60f
            mood == Mood.HAPPY || mood == Mood.LOVE -> ang = -20f + sin(t * 10f + side * PI_F) * 10f
        }
        if (side == 1 && s.wave > 0f) ang += ((-125f + sin(t * 15f) * 22f) - ang) * s.wave
        withTransform({
            translate(sgn * 0.88f * r, 0.12f * r)
            scale(sgn, 1f, Offset.Zero)
            rotate(ang, Offset.Zero)
        }) {
            drawOval(a.limb, topLeft = Offset(-0.06f * r, -0.13f * r), size = Size(0.46f * r, 0.26f * r))
            drawOval(a.outline, topLeft = Offset(-0.06f * r, -0.13f * r), size = Size(0.46f * r, 0.26f * r), style = a.thinStroke)
        }
    }
}

private fun DrawScope.drawAntenna(s: MascotSim, a: MascotArt, mood: Mood) {
    val r = a.r
    val t = s.animT
    val baseY = -1.02f * r
    val len = 0.62f * r
    val ang = s.antenna.v * (PI_F / 180f)
    val tipX = sin(ang) * len
    val tipY = baseY - cos(ang) * len
    a.antennaPath.reset()
    a.antennaPath.moveTo(0f, baseY)
    a.antennaPath.quadraticTo(sin(ang * 0.4f) * len * 0.5f, baseY - len * 0.55f, tipX, tipY)
    drawPath(a.antennaPath, a.outline, style = a.featureStroke)

    val pulse = when (mood) {
        Mood.THINKING -> 0.55f + 0.45f * sin(t * 7f)
        Mood.SLEEP -> 0.15f
        else -> 0.35f + 0.1f * sin(t * 2f)
    }
    drawCircle(a.gem.copy(alpha = 0.35f * pulse), radius = 0.42f * r * (0.8f + pulse * 0.4f), center = Offset(tipX, tipY))
    withTransform({
        translate(tipX, tipY)
        rotate(t * 40f, Offset.Zero)
        scale(0.24f * r, 0.24f * r, Offset.Zero)
    }) {
        drawPath(a.star, a.gem)
        drawPath(a.star, a.outline, style = a.unitOutline)
    }

    if (mood == Mood.THINKING) {
        for (i in 0..2) {
            val o = t * 3f + i * 2.094f
            drawCircle(Color.White, radius = 0.07f * r, center = Offset(tipX + cos(o) * 0.5f * r, tipY + sin(o) * 0.22f * r))
        }
    }
}

private fun DrawScope.drawFace(s: MascotSim, a: MascotArt, mood: Mood) {
    val d = a.d
    val r = a.r
    val t = s.animT
    val eyeY = -0.12f * r
    val eyeDX = 0.36f * r

    val blush = when (mood) {
        Mood.HAPPY, Mood.LOVE -> 0.75f
        Mood.ANGRY -> 0f
        else -> 0.45f
    }
    if (blush > 0f) {
        for (side in 0..1) {
            val sgn = if (side == 0) -1f else 1f
            drawOval(a.cheek.copy(alpha = blush), topLeft = Offset(sgn * 0.6f * r - 0.15f * r, 0.11f * r), size = Size(0.3f * r, 0.18f * r))
        }
    }

    for (side in 0..1) {
        val sgn = if (side == 0) -1f else 1f
        val ex = sgn * eyeDX
        when (mood) {
            Mood.HAPPY -> drawArc(
                a.pupil, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(ex - 0.17f * r, eyeY - 0.08f * r), size = Size(0.34f * r, 0.3f * r), style = a.featureStroke
            )
            Mood.LOVE -> {
                val hs = 0.2f * r * (1f + 0.12f * sin(t * 10f))
                withTransform({
                    translate(ex, eyeY)
                    scale(hs, hs, Offset.Zero)
                }) {
                    drawPath(a.heart, a.heartColor)
                    drawPath(a.heart, a.outline, style = a.unitOutline)
                }
            }
            Mood.SLEEP -> drawArc(
                a.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(ex - 0.16f * r, eyeY - 0.12f * r), size = Size(0.32f * r, 0.22f * r), style = a.featureStroke
            )
            Mood.DIZZY -> {
                val rot = t * 600f
                drawCircle(Color.White, radius = 0.24f * r, center = Offset(ex, eyeY))
                drawArc(
                    a.pupil, startAngle = rot, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(ex - 0.19f * r, eyeY - 0.19f * r), size = Size(0.38f * r, 0.38f * r), style = a.thinStroke
                )
                drawArc(
                    a.pupil, startAngle = rot + 180f, sweepAngle = 260f, useCenter = false,
                    topLeft = Offset(ex - 0.09f * r, eyeY - 0.09f * r), size = Size(0.18f * r, 0.18f * r), style = a.thinStroke
                )
            }
            else -> {
                val big = mood == Mood.SURPRISED
                val ew = if (big) 0.27f * r else 0.23f * r
                val eh0 = if (big) 0.3f * r else 0.27f * r
                val open = (1f - s.blink) * (1f - s.yawn * 0.6f)
                if (open < 0.18f) {
                    drawLine(a.pupil, Offset(ex - ew * 0.8f, eyeY), Offset(ex + ew * 0.8f, eyeY), strokeWidth = 2.2f * d, cap = StrokeCap.Round)
                } else {
                    val eh = eh0 * open
                    drawOval(Color.White, topLeft = Offset(ex - ew, eyeY - eh), size = Size(2f * ew, 2f * eh))
                    val pr = min(
                        when {
                            big -> 0.1f * r
                            mood == Mood.ANGRY -> 0.12f * r
                            else -> 0.15f * r
                        },
                        eh * 0.9f
                    )
                    val px = ex + s.lookX * (ew - pr) * 0.7f
                    val py = eyeY + s.lookY * (eh - pr) * 0.6f
                    drawCircle(a.pupil, radius = pr, center = Offset(px, py))
                    drawCircle(Color.White, radius = pr * 0.38f, center = Offset(px - pr * 0.35f, py - pr * 0.4f))
                    drawOval(a.outline, topLeft = Offset(ex - ew, eyeY - eh), size = Size(2f * ew, 2f * eh), style = a.thinStroke)
                }
                if (mood == Mood.ANGRY) {
                    drawLine(
                        a.outline, Offset(ex + sgn * 0.22f * r, eyeY - 0.4f * r), Offset(ex - sgn * 0.18f * r, eyeY - 0.26f * r),
                        strokeWidth = 2.4f * d, cap = StrokeCap.Round
                    )
                } else if (mood == Mood.THINKING && side == 1) {
                    drawLine(
                        a.outline, Offset(ex - 0.16f * r, eyeY - 0.44f * r), Offset(ex + 0.18f * r, eyeY - 0.4f * r),
                        strokeWidth = 2.2f * d, cap = StrokeCap.Round
                    )
                }
            }
        }
    }

    // Mouth
    val my = 0.3f * r
    when {
        s.yawn > 0.05f -> {
            val h = 0.12f * r + 0.28f * r * s.yawn
            drawOval(a.pupil, topLeft = Offset(-0.13f * r, my - h * 0.4f), size = Size(0.26f * r, h))
        }
        mood == Mood.HAPPY || mood == Mood.LOVE -> {
            drawArc(a.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = true, topLeft = Offset(-0.22f * r, my - 0.2f * r), size = Size(0.44f * r, 0.4f * r))
            drawOval(a.tongue, topLeft = Offset(-0.1f * r, my + 0.07f * r), size = Size(0.2f * r, 0.1f * r))
        }
        mood == Mood.SURPRISED -> drawOval(a.pupil, topLeft = Offset(-0.08f * r, my - 0.06f * r), size = Size(0.16f * r, 0.2f * r))
        mood == Mood.SLEEP -> {
            val h = 0.07f * r + 0.05f * r * (0.5f + 0.5f * sin(t * 1.3f))
            drawOval(a.pupil, topLeft = Offset(-0.06f * r, my), size = Size(0.12f * r, h))
        }
        mood == Mood.DIZZY -> {
            drawArc(a.pupil, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(-0.2f * r, my - 0.06f * r), size = Size(0.2f * r, 0.12f * r), style = a.featureStroke)
            drawArc(a.pupil, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0f, my - 0.06f * r), size = Size(0.2f * r, 0.12f * r), style = a.featureStroke)
        }
        mood == Mood.ANGRY -> drawArc(
            a.pupil, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(-0.18f * r, my), size = Size(0.36f * r, 0.24f * r), style = a.featureStroke
        )
        mood == Mood.THINKING -> drawLine(a.pupil, Offset(-0.06f * r, my + 0.04f * r), Offset(0.14f * r, my), strokeWidth = 2.2f * d, cap = StrokeCap.Round)
        else -> drawArc(
            a.pupil, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(-0.2f * r, my - 0.14f * r), size = Size(0.4f * r, 0.28f * r), style = a.featureStroke
        )
    }

    // Dizzy: little stars circling the head.
    if (mood == Mood.DIZZY) {
        for (i in 0..2) {
            val o = t * 4f + i * 2.094f
            withTransform({
                translate(cos(o) * 0.95f * r, -0.95f * r + sin(o) * 0.22f * r)
                scale(0.13f * r, 0.13f * r, Offset.Zero)
            }) {
                drawPath(a.star, a.gem)
                drawPath(a.star, a.outline, style = a.unitOutline)
            }
        }
    }

    // Asleep: a small static "z" by the head, so the still (resting) pose still reads as sleeping between snores.
    if (mood == Mood.SLEEP) {
        withTransform({
            translate(0.95f * r, -1.2f * r)
            rotate(-12f, Offset.Zero)
            scale(0.16f * r, 0.16f * r, Offset.Zero)
        }) {
            drawPath(a.zPath, a.zColor, style = a.zStroke)
        }
    }
}

/** [ox]/[oy]: where this canvas' origin sits in overlay coordinates (particles live in overlay coordinates). */
private fun DrawScope.drawParticles(s: MascotSim, a: MascotArt, ox: Float, oy: Float) {
    for (i in 0 until MAX_PARTICLES) {
        val life = s.pLife[i]
        if (life <= 0f) continue
        val k = life / s.pMax[i] // 1 -> 0
        val alpha = min(1f, k * 2.5f)
        val type = s.pType[i]
        val grow = when (type) {
            P_HEART -> 0.6f + 0.4f * min(1f, (1f - k) * 4f)
            P_Z -> 0.6f + 0.6f * (1f - k)
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
                else -> drawPath(a.sparkle, a.sparkleColor.copy(alpha = alpha))
            }
        }
    }
}
