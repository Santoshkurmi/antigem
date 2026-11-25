package ai.antigem.ui.components.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * The pets' shared simulation (it was Gemmy's; every pet now runs on it): springs, fling physics, bounce, dizzy,
 * particles, moods, tricks, wander, sleep, blink, treats and each pet's signature move.
 *
 * Power: full frame rate only while something moves (capped by the user's "while moving" rate), the user's hover rate
 * while just floating. After the user's "goes still after" time without interaction it eases into a still pose and the
 * loop stops requesting frames entirely (zero CPU), waking only for a blink (every 3–7 s), an idle trick, a snore while
 * asleep, or an effect while the agent works; any touch or agent state change wakes it at once. Compose also pauses the
 * loop while the app is in the background. Nothing is allocated per frame: particles live in fixed arrays.
 */

internal const val PI_F = 3.1415927f
internal const val MAX_PARTICLES = 48
internal const val P_HEART = 0
internal const val P_SPARKLE = 1
internal const val P_Z = 2
internal const val P_FX = 3 // the pet's own effect (foxfire, snowflake, bubble...): which one is the particle's variant

/** "Never" for times in seconds (still far from Float's limits when added to the clock). */
internal const val NEVER_S = 1e8f

enum class Mood { NORMAL, HAPPY, LOVE, SURPRISED, DIZZY, SLEEP, ANGRY, THINKING }

enum class Trick { NONE, WANDER, WAVE, HOP, SPIN, DANCE, LOOK_AROUND, YAWN, SPECIAL, EAT }

/** The user's Settings → Pets choices that change how a pet behaves (not how it looks). */
internal class PetTuning(
    val restAfterS: Float = 5f, // NEVER_S: never goes still
    val sleepAfterS: Float = 50f, // NEVER_S: never falls asleep by itself
    val trickEvery: Float = 1f, // multiplies the 18–42 s gap between idle tricks; 0 = no tricks of its own
    val wander: Boolean = true,
    val speech: Boolean = true,
    val bubbleTime: Float = 1f,
    val agentReact: Boolean = true,
    val haptics: Boolean = true,
    val effects: Boolean = true,
    val idleFps: Int = 30,
    val moveFps: Int = 0 // 0 = every screen frame
)

/** A damped spring on one value (semi-implicit Euler). */
internal class Spring1 {
    var v = 0f
    var vel = 0f
    private var target = 0f

    fun step(target: Float, k: Float, c: Float, dt: Float) {
        this.target = target
        vel += (k * (target - v) - c * vel) * dt
        v += vel * dt
    }

    fun settled(eps: Float) = abs(vel) < eps * 4f && abs(v - target) < eps

    fun reset() {
        v = 0f
        vel = 0f
        target = 0f
    }
}

/**
 * All pet state. Positions are in px, in the coordinates of the full-screen overlay; [x]/[y] is the body center.
 * Plain fields on purpose: [frame] is the only per-frame snapshot state, read only by draw and placement.
 * [d] is px per dp times the user's size, so a bigger pet is simply drawn and moved at a bigger scale.
 */
internal class PetSim(d0: Float) {
    var d = d0
    var frame by mutableLongStateOf(0L)
    var speechText by mutableStateOf("")
    var speechVisible by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var hideRequested by mutableStateOf(false)

    var hapticLight: () -> Unit = {}
    var hapticStrong: () -> Unit = {}

    /** The look being drawn: gives the signature move its timing and effects. */
    var art: PetArt? = null
    var tuning = PetTuning()
    var lines: PetLines = PetLines.EMPTY

    val r get() = 24f * d // body radius
    val half get() = 32f * d // half of the 64dp touch box

    var boundsW = 0f
    var boundsH = 0f

    var x = -1f
    var y = -1f
    var vx = 0f
    var vy = 0f
    var time = 0f // real seconds; drives timers (kept exact across rests, see syncClock)
    var greet = true // the first frame says hello (not in previews)

    /** Clock for cosmetic motion (bob, breathing, glow...). Advances at [liveliness] speed, so it eases to a halt
     *  before a rest and resumes from the same phase afterwards: the still pose never jumps. */
    var animT = 0f
    var liveliness = 1f
        private set

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
    var dragVx = 0f
        private set
    var dragVy = 0f
        private set
    private var shakeFlips = 0f
    private var lastDragSign = 0

    val squash = Spring1() // >0 flatter/wider, <0 thinner/taller
    val tilt = Spring1() // degrees
    val antenna = Spring1() // degrees: the pet's springy part (antenna, ears, tails, gills, scarf...)
    val legs = Spring1() // degrees
    val pose = Spring1() // the pet's own pose, -1..1 (fox tails gathered → fanned, cat tail curled → up...)
    var hop = 0f // visual vertical offset (px, negative = up)
    private var hopV = 0f
    var spin = 0f
    var wave = 0f // 0..1 right arm raised and waving
    var yawn = 0f
    var mouthOpen = 0f // 0..1 (treats, fire, bubbles)

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
    var agentWorking = false
        private set
    private var workStartedAt = 0f
    var trick = Trick.NONE
    var trickT = 0f
    var trickDur = 0f
        private set
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
    val pVar = IntArray(MAX_PARTICLES)
    private val pGrav = FloatArray(MAX_PARTICLES)
    private val pDrag = FloatArray(MAX_PARTICLES)
    private val pSpin = FloatArray(MAX_PARTICLES)
    private val pWobble = FloatArray(MAX_PARTICLES)
    private val pSlow = BooleanArray(MAX_PARTICLES)
    private var nextSlot = 0
    private var busyParticles = 0

    fun mood(): Mood = when {
        time < tempMoodUntil -> tempMood
        held -> if (heldTime < 0.9f || hypot(dragVx, dragVy) > 900f * d) Mood.SURPRISED else Mood.HAPPY
        sleeping -> Mood.SLEEP
        trick == Trick.DANCE || trick == Trick.EAT -> Mood.HAPPY
        trick == Trick.SPECIAL -> art?.specialMood(this) ?: Mood.HAPPY
        agentWorking -> Mood.THINKING
        else -> Mood.NORMAL
    }

    /** 0..1 progress of the running trick. */
    fun trickP(): Float = if (trickDur > 0f) (trickT / trickDur).coerceIn(0f, 1f) else 0f

    /** -1 when the pet is on the right half (so it faces the middle of the screen), else 1. */
    fun facing(): Float = if (boundsW > 0f && x > boundsW * 0.5f + 1f) -1f else 1f

    private fun minX() = r * 1.3f
    private fun maxX() = max(minX(), boundsW - r * 1.3f)
    private fun minY() = r * 1.9f // room for the antenna / ears
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
            if (greet) {
                setMood(Mood.HAPPY, 1.2f)
                say(lines.hello(), 2f)
                jump(240f)
            }
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

    private fun idleFor() = time - lastInteraction

    /** Untouched for the user's "goes still after" time and nothing moving or flying around. */
    private fun restEligible(): Boolean =
        idleFor() > tuning.restAfterS && !held && !menuOpen && trick == Trick.NONE &&
            hop == 0f && hopV == 0f && vx == 0f && vy == 0f && wave == 0f && aliveParticles == 0 &&
            squash.settled(0.004f) && tilt.settled(0.3f) && antenna.settled(0.3f) && legs.settled(0.3f) && pose.settled(0.01f)

    /** Rest eligible, motion fully eased out, and no blink or glance in progress. */
    fun canDeepRest(): Boolean =
        liveliness <= 0f && blinkT < 0f && restEligible() &&
            abs(lookTX - lookX) < 0.01f && abs(lookTY - lookY) < 0.01f

    /** How long the loop may sleep: until the next blink, trick, snore/effect, falling asleep, bubble or mood change. */
    fun msUntilNextWake(): Long {
        var wake = time + 60f
        if (!sleeping) wake = min(wake, nextBlink)
        if (!sleeping && !agentWorking) {
            wake = min(wake, nextTrickAt)
            wake = min(wake, lastInteraction + tuning.sleepAfterS)
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

    /** Wakes the loop from a rest (touch, agent state, bounds or settings change). */
    fun poke() {
        syncClock()
        wakeSignal.trySend(Unit)
    }

    /** Frames per second wanted now: 0 = every screen frame. */
    fun targetFps(): Int {
        val busy = held || trick != Trick.NONE || hop != 0f || hopV != 0f || vx != 0f || vy != 0f ||
            !squash.settled(0.004f) || !tilt.settled(0.3f) || !antenna.settled(0.3f) || !legs.settled(0.3f) ||
            !pose.settled(0.01f) || busyParticles > 0
        return when {
            busy -> tuning.moveFps
            sleeping -> min(tuning.idleFps, 20)
            else -> tuning.idleFps
        }
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
        pose.step(art?.poseTarget(this, mood()) ?: 0f, 70f, 12f, dt)

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
            light()
            burst(P_SPARKLE, 3)
        }
        if (s > 1700f) {
            setMood(Mood.DIZZY, 2.2f)
            if (!speechVisible) say(lines.ouch(), 1.4f)
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
            trick == Trick.EAT -> {
                lookTX = 0f
                lookTY = if (trickT < 0.7f) -0.9f else 0.2f // watches the treat fall
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
            val idle = idleFor() > tuning.restAfterS
            nextBlink = time + when {
                Random.nextInt(5) == 0 -> 0.3f // double blink
                idle -> 3f + Random.nextFloat() * 4f // rarer while resting: each blink is a short wake
                else -> 2f + Random.nextFloat() * 3.5f
            }
        }
    }

    private fun stepBehaviour(dt: Float) {
        if (trick != Trick.NONE) {
            val prevT = trickT
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
                Trick.EAT -> stepEat(prevT, trickT)
                Trick.SPECIAL -> art?.specialTick(this, prevT, trickT)
                else -> Unit
            }
            if (trick != Trick.NONE && trickT >= trickDur) endTrick()
        } else if (wave > 0f) {
            wave = max(0f, wave - dt * 4f)
        }

        if (!held && !sleeping && !agentWorking && trick == Trick.NONE) {
            if (idleFor() > tuning.sleepAfterS) {
                sleeping = true
                nextFx = time + 0.4f
            } else if (time > nextTrickAt) {
                if (tuning.trickEvery > 0f) {
                    val roll = Random.nextInt(100)
                    var t = when {
                        roll < 30 -> Trick.WANDER
                        roll < 46 -> Trick.LOOK_AROUND
                        roll < 57 -> Trick.WAVE
                        roll < 67 -> Trick.HOP
                        roll < 75 -> Trick.SPIN
                        roll < 84 -> Trick.YAWN
                        roll < 93 -> Trick.SPECIAL
                        else -> Trick.DANCE
                    }
                    if (t == Trick.WANDER && !tuning.wander) t = Trick.LOOK_AROUND
                    startTrick(t)
                }
                scheduleNextTrick()
            }
        }

        if (time > nextFx) {
            val idle = idleFor() > tuning.restAfterS
            if (sleeping) {
                spawn(P_Z, x + r * 0.55f, y - r * 1.1f)
                nextFx = time + if (idle) 8f + Random.nextFloat() * 4f else 1.1f
            } else if (agentWorking && !held) {
                if (tuning.effects) art?.workTick(this)
                nextFx = time + if (idle) 4f else 0.75f
            }
        }
    }

    private fun scheduleNextTrick() {
        nextTrickAt = if (tuning.trickEvery <= 0f) time + NEVER_S else time + (18f + Random.nextFloat() * 24f) * tuning.trickEvery
    }

    // Treat: falls from above into the mouth (0–0.7 s), chewing (to 1.7 s), then hearts.
    private fun stepEat(prev: Float, t: Float) {
        mouthOpen = when {
            t < 0.7f -> ((t - 0.25f) / 0.35f).coerceIn(0f, 1f)
            t < 1.7f -> abs(sin((t - 0.7f) * 13f)) * 0.55f
            else -> 0f
        }
        if (prev < 0.7f && t >= 0.7f) {
            squash.vel += 5f
            light()
            art?.crumbs(this)
        }
        if (prev < 1.75f && t >= 1.75f) {
            setMood(Mood.LOVE, 1.3f)
            jump(200f)
            burst(P_HEART, 4)
            say(lines.eat(), 1.8f)
        }
    }

    /** Where the falling treat is, in body coordinates (y), or null when it is not shown. */
    fun treatY(mouthY: Float): Float? {
        if (trick != Trick.EAT || trickT >= 0.7f) return null
        val p = (trickT / 0.7f).coerceIn(0f, 1f)
        return -2.6f * r + (mouthY + 2.6f * r) * p * p
    }

    private fun stepParticles(dt: Float) {
        var busy = 0
        var alive = 0
        val damp = exp(-3f * dt)
        for (i in 0 until MAX_PARTICLES) {
            if (pLife[i] <= 0f) continue
            pLife[i] -= dt
            when (pType[i]) {
                P_SPARKLE -> {
                    pVX[i] *= damp
                    pVY[i] *= damp
                    pRot[i] += 200f * dt
                }
                P_FX -> {
                    pVY[i] += pGrav[i] * dt
                    if (pDrag[i] > 0f) {
                        val f = exp(-pDrag[i] * dt)
                        pVX[i] *= f
                        pVY[i] *= f
                    }
                    pRot[i] += pSpin[i] * dt
                    if (pWobble[i] != 0f) pX[i] += sin((pMax[i] - pLife[i]) * 6f + i) * pWobble[i] * dt
                }
                else -> pX[i] += sin((pMax[i] - pLife[i]) * 5f + i) * 14f * d * dt
            }
            pX[i] += pVX[i] * dt
            pY[i] += pVY[i] * dt
            if (pLife[i] > 0f) {
                alive++
                if (pType[i] == P_HEART || pType[i] == P_SPARKLE || (pType[i] == P_FX && !pSlow[i])) busy++
            }
        }
        busyParticles = busy
        aliveParticles = alive
    }

    // ---- helpers (also used by each pet's signature move) ----

    fun setMood(m: Mood, dur: Float) {
        tempMood = m
        tempMoodUntil = time + dur
    }

    /** Shows a speech bubble; [force] shows it even with speech bubbles off (the pet saying its name in Settings). */
    fun say(text: String, dur: Float = 2.4f, force: Boolean = false) {
        if (!tuning.speech && !force) return
        speechText = text
        speechVisible = true
        speechUntil = time + dur * tuning.bubbleTime
        menuOpen = false
    }

    fun jump(power: Float) {
        if (hop > -4f * d) hopV = -power * d
    }

    fun light() {
        if (tuning.haptics) hapticLight()
    }

    private fun strong() {
        if (tuning.haptics) hapticStrong()
    }

    private fun touched() {
        lastInteraction = time
        if (sleeping) {
            sleeping = false
            wokeAt = time
            setMood(Mood.SURPRISED, 0.9f)
            jump(240f)
            say(lines.wake(), 1.8f)
        }
    }

    fun startTrick(t: Trick) {
        trick = t
        trickT = 0f
        lastBeat = -1
        spin = 0f
        mouthOpen = 0f
        trickDur = when (t) {
            Trick.WANDER -> 7f
            Trick.WAVE -> 1.8f
            Trick.HOP -> 1.2f
            Trick.SPIN -> 0.75f
            Trick.DANCE -> 3.4f
            Trick.LOOK_AROUND -> 2.2f
            Trick.YAWN -> 1.8f
            Trick.EAT -> 2.1f
            Trick.SPECIAL -> art?.specialDur ?: 1.5f
            Trick.NONE -> 0f
        }
        when (t) {
            Trick.WANDER -> pickWanderTarget()
            Trick.WAVE -> if (!speechVisible && Random.nextInt(2) == 0) say(lines.hello(), 1.8f)
            Trick.YAWN -> if (!speechVisible) say("*yawn* 🥱", 1.6f)
            Trick.SPECIAL -> art?.specialStart(this)
            else -> Unit
        }
    }

    private fun endTrick() {
        trick = Trick.NONE
        trickT = 0f
        spin = 0f
        yawn = 0f
        mouthOpen = 0f
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

    private fun slot(): Int {
        val i = nextSlot
        nextSlot = (nextSlot + 1) % MAX_PARTICLES
        return i
    }

    fun spawn(type: Int, px: Float, py: Float) {
        if (!tuning.effects && type != P_Z) return
        val i = slot()
        pType[i] = type
        pVar[i] = 0
        pX[i] = px
        pY[i] = py
        pSlow[i] = false
        when (type) {
            P_HEART -> {
                pVX[i] = (Random.nextFloat() - 0.5f) * 70f * d
                pVY[i] = -(60f + Random.nextFloat() * 50f) * d
                pLife[i] = 1.2f + Random.nextFloat() * 0.4f
                pSize[i] = (6f + Random.nextFloat() * 3f) * d
                pRot[i] = (Random.nextFloat() - 0.5f) * 30f
            }
            P_SPARKLE -> {
                val a = Random.nextFloat() * 2f * PI_F
                val sp = (90f + Random.nextFloat() * 140f) * d
                pVX[i] = cos(a) * sp
                pVY[i] = sin(a) * sp
                pLife[i] = 0.5f + Random.nextFloat() * 0.3f
                pSize[i] = (4.5f + Random.nextFloat() * 3f) * d
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

    /**
     * A pet effect particle (see [PetArt.drawFx]). Speeds, gravity and wobble are in dp (times [d] here), size in dp.
     * [slow] ones (ambient glints) don't hold the loop at full frame rate.
     */
    fun fx(
        variant: Int, px: Float, py: Float, vx: Float, vy: Float, life: Float, size: Float,
        gravity: Float = 0f, drag: Float = 0f, spin: Float = 0f, wobble: Float = 0f, rot: Float = 0f, slow: Boolean = false
    ) {
        if (!tuning.effects) return
        val i = slot()
        pType[i] = P_FX
        pVar[i] = variant
        pX[i] = px
        pY[i] = py
        pVX[i] = vx * d
        pVY[i] = vy * d
        pLife[i] = life
        pMax[i] = life
        pSize[i] = size * d
        pGrav[i] = gravity * d
        pDrag[i] = drag
        pSpin[i] = spin
        pWobble[i] = wobble * d
        pRot[i] = rot
        pSlow[i] = slow
    }

    /** [n] effects shot out from ([px], [py]) in random directions at [speed] dp/s (±40 %). */
    fun fxBurst(variant: Int, n: Int, px: Float, py: Float, speed: Float, life: Float, size: Float, gravity: Float = 0f, upBias: Float = 0f) {
        repeat(n) {
            val a = Random.nextFloat() * 2f * PI_F
            val sp = speed * (0.6f + Random.nextFloat() * 0.8f)
            fx(
                variant, px, py, cos(a) * sp, sin(a) * sp - upBias, life * (0.8f + Random.nextFloat() * 0.4f),
                size * (0.75f + Random.nextFloat() * 0.5f), gravity = gravity, drag = 2.2f,
                spin = (Random.nextFloat() - 0.5f) * 360f, rot = Random.nextFloat() * 360f
            )
        }
    }

    fun burst(type: Int, n: Int) {
        repeat(n) { spawn(type, x + (Random.nextFloat() - 0.5f) * r, y - r * 0.6f + hop) }
    }

    // ---- outside events ----

    fun setAgentWorking(working0: Boolean) {
        val working = working0 && tuning.agentReact
        if (working == agentWorking) return
        poke()
        agentWorking = working
        lastInteraction = time
        if (working) {
            workStartedAt = time
            sleeping = false
            if (trick == Trick.WANDER || trick == Trick.YAWN) endTrick()
            if (Random.nextInt(2) == 0) say(lines.work(), 1.8f)
        } else if (time - workStartedAt > 3f) {
            celebrate()
        }
    }

    /** The agent finished (or "Done" was tried in Settings): jump, effects and a line. */
    fun celebrate() {
        poke()
        touched()
        setMood(Mood.HAPPY, 2f)
        jump(300f)
        burst(P_SPARKLE, 8)
        art?.celebrate(this)
        say(lines.done(), 2.2f)
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
        light()
        if (pokes >= 5) {
            pokes = 0
            angry()
            return
        }
        when (Random.nextInt(5)) {
            0 -> {
                setMood(Mood.HAPPY, 1.4f)
                jump(260f)
                burst(P_HEART, 3)
            }
            1, 2 -> {
                setMood(Mood.HAPPY, 1.6f)
                jump(200f)
                say(lines.tap())
            }
            3 -> {
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

    fun angry() {
        poke()
        touched()
        setMood(Mood.ANGRY, 2.4f)
        say(lines.angry())
        tilt.vel += 260f
        squash.vel -= 4f
        art?.angryTick(this)
    }

    fun dizzy() {
        poke()
        touched()
        setMood(Mood.DIZZY, 2.6f)
        tilt.vel += 400f
        say(lines.dizzy(), 2f)
    }

    fun onDoubleTap() {
        poke()
        touched()
        menuOpen = false
        startTrick(Trick.SPIN)
        burst(P_SPARKLE, 8)
        say("Wheee! ✨", 1.4f)
        light()
    }

    fun onLongPress() {
        poke()
        touched()
        strong()
        speechVisible = false
        menuOpen = true
        menuUntil = time + 6f
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
        if (Random.nextInt(3) == 0) say(lines.grab(), 1.6f)
        light()
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
            time < tempMoodUntil && tempMood == Mood.DIZZY -> say(lines.dizzy(), 2f)
            speed > 1400f -> say(lines.fling(), 1.4f)
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

    // ---- long-press menu (and the "Try it" buttons in Settings) ----

    fun dance() {
        poke()
        touched()
        startTrick(Trick.DANCE)
        say(lines.dance(), 1.8f)
    }

    fun special() {
        poke()
        touched()
        menuOpen = false
        startTrick(Trick.SPECIAL)
    }

    fun treat() {
        poke()
        touched()
        menuOpen = false
        startTrick(Trick.EAT)
    }

    fun waveHello() {
        poke()
        touched()
        startTrick(Trick.WAVE)
        say(lines.hello(), 1.8f)
    }

    fun nap() {
        poke()
        menuOpen = false
        endTrick()
        sleeping = true
        nextFx = time + 0.4f
        lastInteraction = time
        say(lines.nap(), 1.6f)
    }

    /** Settings' "Think" button: acts as if the agent were working, until pressed again. */
    fun toggleThinking(): Boolean {
        val was = agentWorking
        agentWorking = !was
        poke()
        lastInteraction = time
        if (!was) {
            workStartedAt = time
            sleeping = false
            say(lines.work(), 1.8f)
        } else {
            celebrate()
        }
        return agentWorking
    }

    /** Says its name (tapping it in Settings): always shown, even with speech bubbles off. */
    fun sayName() {
        poke()
        touched()
        setMood(Mood.HAPPY, 1.4f)
        jump(220f)
        burst(P_HEART, 2)
        say(lines.name(), 1.8f, force = true)
        light()
    }

    fun hide() {
        poke()
        touched()
        startTrick(Trick.WAVE)
        say(lines.bye(), 1.2f)
        hideRequested = true
    }
}

/**
 * The single frame loop: fixed 1/60 s sub-steps, then one state write that redraws/re-places the pet.
 * Paces itself to the pet's wanted frame rate and parks with no frames at all while it rests (see the header).
 */
internal suspend fun runPetLoop(sim: PetSim) {
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
            val fps = sim.targetFps()
            if (fps > 0) {
                // wake a little before the frame after the wanted interval (frame times are System.nanoTime based)
                val elapsedMs = ((System.nanoTime() - now) / 1_000_000f).coerceAtLeast(0f)
                val waitMs = 1000f / fps - elapsedMs - 4f
                if (waitMs >= 1f) delay(waitMs.toLong())
            }
        }
    }
}
