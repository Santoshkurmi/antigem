package ai.antigem.ui.components

import ai.antigem.i18n.L
import ai.antigem.ui.components.pet.PetArt
import ai.antigem.ui.components.pet.PetKind
import ai.antigem.ui.components.pet.PetLines
import ai.antigem.ui.components.pet.PetLook
import ai.antigem.ui.components.pet.PetSettings
import ai.antigem.ui.components.pet.PetSim
import ai.antigem.ui.components.pet.PetTuning
import ai.antigem.ui.components.pet.Trick
import ai.antigem.ui.components.pet.createPetArt
import ai.antigem.ui.components.pet.drawPet
import ai.antigem.ui.components.pet.runPetLoop
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The floating pet (Gemmy and friends, Settings → Pets): a small cartoon buddy over every screen of the app.
 *
 * Drawn entirely with a Compose Canvas: no images, no animation libraries (see ui/components/pet: PetSim is the
 * simulation and its power rules, PetArt and one file per pet the drawing). One frame loop advances it; only the draw
 * and placement phases read its per-frame state, so it animates without recomposing. When still it uses no CPU at all.
 *
 * Interactions: drag & fling it (it bounces off the screen edges; shake it and it gets dizzy), tap (random
 * reactions; poke it 5 times fast and it gets angry), double-tap (spin), long-press (menu: Dance / its signature move
 * / Treat / Nap / Settings / Hide). On its own it wanders, waves, hops, spins, yawns, shows off and falls asleep when
 * left alone. While the agent is working it thinks (each pet its own way) and celebrates when the turn finishes.
 */

private const val ID_BODY = "mascot_body"
private const val ID_BUBBLE = "mascot_bubble"
private const val ID_MENU = "mascot_menu"

private val MascotInk = Color(0xFF2E2A6B)
private val MascotText = Color(0xFF1B1840)

/** "Hide" on the pet's menu: gone until the app restarts or Settings → Pets shows it again. */
object PetSession {
    val hidden = MutableStateFlow(false)
}

/**
 * The app-wide overlay: shows the chosen pet only once the one-time intro was answered (so it never pops up during
 * setup) and while it is on in Settings → Pets.
 */
@Composable
fun PetOverlay(
    prefs: ai.antigem.data.preferences.AuthPreferences,
    agentWorking: StateFlow<Boolean>,
    onOpenSettings: () -> Unit
) {
    val enabled by prefs.isGemmyEnabled.collectAsState(initial = false)
    val introSeen by prefs.hasSeenGemmyIntro.collectAsState(initial = false)
    val hidden by PetSession.hidden.collectAsState()
    val json by prefs.petSettingsJson.collectAsState(initial = null)
    if (!enabled || !introSeen || hidden) return
    val raw = json ?: return // not loaded yet: don't flash the default pet first
    val settings = remember(raw) { PetSettings.fromJson(raw) }
    FloatingPet(settings, agentWorking, onOpenSettings)
}

@Composable
private fun FloatingPet(
    settings: PetSettings,
    agentWorking: StateFlow<Boolean>,
    onOpenSettings: () -> Unit
) {
    val densityObj = LocalDensity.current
    val d = densityObj.density * settings.size
    val kind = settings.kind
    val look = settings.look()
    val sim = remember { PetSim(d) }
    val art = remember(kind, look.palette, look.option(kind), d) { createPetArt(kind, look, d) }
    val tuning = remember(settings) { settings.tuning() }
    val lines = remember(kind, look) { PetLines(kind, look.name(kind), axe = look.option(kind) == 0) }

    val haptic = LocalHapticFeedback.current
    SideEffect {
        sim.d = d
        sim.art = art
        sim.tuning = tuning
        sim.lines = lines
        sim.hapticLight = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        sim.hapticStrong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    LaunchedEffect(art, tuning) { sim.poke() } // redraw now in the new look / pace

    // optional: out of the way while the keyboard is open (only watched when that setting is on)
    if (settings.hideWithKeyboard) {
        val ime = WindowInsets.ime
        val imeOpen by remember(ime, densityObj) { derivedStateOf { ime.getBottom(densityObj) > 0 } }
        if (imeOpen) return
    }

    val working by agentWorking.collectAsState()
    LaunchedEffect(working, tuning.agentReact) { sim.setAgentWorking(working) }

    LaunchedEffect(sim) { runPetLoop(sim) }

    if (sim.hideRequested) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1100)
            sim.hideRequested = false
            PetSession.hidden.value = true
        }
    }

    val boxSize = (64f * settings.size).dp
    val opacity = settings.opacity
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        content = {
            Box(
                Modifier
                    .layoutId(ID_BODY)
                    .size(boxSize)
                    .then(if (opacity < 0.99f) Modifier.graphicsLayer { alpha = opacity } else Modifier)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    sim.frame // redraw on every simulated frame
                    drawPet(sim, art)
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
                PetMenu(
                    kind = kind,
                    look = look,
                    onDance = sim::dance,
                    onSpecial = sim::special,
                    onTreat = sim::treat,
                    onNap = sim::nap,
                    onSettings = {
                        sim.menuOpen = false
                        onOpenSettings()
                    },
                    onHide = sim::hide
                )
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
            body.place((sim.x - body.width / 2f).roundToInt(), (sim.y - body.height / 2f).roundToInt())
            if (bubble != null) bubble.place(anchorAboveHead(sim, bubble.width, bubble.height, constraints.maxWidth, pad))
            if (menu != null) menu.place(anchorAboveHead(sim, menu.width, menu.height, constraints.maxWidth, pad))
        }
    }
}

/** Centers a popup above the pet's head, kept on screen; drops below it when there is no room above. */
private fun anchorAboveHead(s: PetSim, w: Int, h: Int, boundsW: Int, pad: Int): IntOffset {
    val px = (s.x - w / 2f).roundToInt().coerceIn(pad, max(pad, boundsW - w - pad))
    var py = (s.y - s.r * 1.8f - h).roundToInt()
    if (py < pad) py = (s.y + s.r * 1.45f).roundToInt()
    return IntOffset(px, py)
}

/** Lets Settings make a preview pet do things ("Try it" buttons). */
class PetPreviewController {
    internal var sim: PetSim? = null
    fun sayName() { sim?.sayName() }
    fun tap() { sim?.let { it.onPress(); it.onTap() } }
    fun wave() { sim?.waveHello() }
    fun special() { sim?.special() }
    fun treat() { sim?.treat() }
    fun dance() { sim?.dance() }
    fun spin() { sim?.onDoubleTap() }
    fun nap() { sim?.nap() }
    fun dizzy() { sim?.dizzy() }
    fun angry() { sim?.angry() }
    fun celebrate() { sim?.celebrate() }
    /** Acts as if the agent were working (true) or stops and celebrates (false). */
    fun toggleThinking(): Boolean = sim?.toggleThinking() ?: false
}

@Composable
fun rememberPetPreviewController(): PetPreviewController = remember { PetPreviewController() }

/**
 * A pet in place (intro dialog and Settings): same drawing and behaviour, drawn [zoom]× bigger, stays put, with its
 * own speech bubble. Tap: says its name (then the usual tap reactions), double-tap: spin, long-press: its signature
 * move. [interactive] false: just plays (for color swatches inside a clickable).
 */
@Composable
fun PetPreview(
    kind: PetKind,
    look: PetLook = PetLook(),
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    controller: PetPreviewController? = null,
    interactive: Boolean = true,
    showBubble: Boolean = true,
    haptics: Boolean = true
) {
    val density = LocalDensity.current.density
    val sim = remember(density) {
        // A huge virtual area with the pet in the middle: it never hits an edge, and no greeting on the first frame.
        PetSim(density).apply {
            boundsW = 100_000f
            boundsH = 100_000f
            x = 50_000f
            y = 50_000f
            greet = false
            tuning = PetTuning(restAfterS = 4f, sleepAfterS = 45f, wander = false, haptics = haptics)
        }
    }
    val art: PetArt = remember(kind, look.palette, look.option(kind), density) { createPetArt(kind, look, density) }
    val lines = remember(kind, look) { PetLines(kind, look.name(kind), axe = look.option(kind) == 0) }
    val haptic = LocalHapticFeedback.current
    val lastNameAt = remember { longArrayOf(0L) }
    SideEffect {
        sim.art = art
        sim.lines = lines
        sim.hapticLight = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        controller?.sim = sim
    }
    LaunchedEffect(art) { sim.poke() }
    LaunchedEffect(sim) { runPetLoop(sim) }

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        // Taller than wide: antennas, ears and the ground shadow reach well past the 48dp body.
        Canvas(
            Modifier
                .size(width = 72.dp * zoom, height = 96.dp * zoom)
                .then(
                    if (!interactive) Modifier else Modifier.pointerInput(sim) {
                        detectTapGestures(
                            onPress = {
                                sim.onPress()
                                tryAwaitRelease()
                            },
                            onTap = {
                                val now = System.currentTimeMillis()
                                if (now - lastNameAt[0] > 5000L) {
                                    lastNameAt[0] = now
                                    sim.sayName()
                                } else {
                                    sim.onTap()
                                }
                            },
                            onDoubleTap = { sim.onDoubleTap() },
                            onLongPress = { if (sim.trick != Trick.SPECIAL) sim.special() }
                        )
                    }
                )
        ) {
            sim.frame // redraw on every simulated frame
            scale(zoom, pivot = center) { drawPet(sim, art) }
        }
        if (showBubble) {
            AnimatedVisibility(
                visible = sim.speechVisible,
                modifier = Modifier
                    .zIndex(1f)
                    .wrapContentSize(unbounded = true)
                    .offset(y = (-6).dp),
                enter = fadeIn() + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f))
            ) {
                Box(Modifier.widthIn(max = 200.dp)) { MascotSpeechBubble(sim.speechText, small = zoom < 1.2f) }
            }
        }
    }
}

/** Gemmy in place, as before (the one-time "Meet Gemmy" dialog). */
@Composable
fun GemmyPreview(
    modifier: Modifier = Modifier,
    zoom: Float = 1f
) {
    PetPreview(PetKind.GEMMY, modifier = modifier, zoom = zoom, showBubble = false)
}

@Composable
private fun MascotSpeechBubble(text: String, small: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .shadow(6.dp, shape)
            .background(Color.White, shape)
            .border(1.5.dp, MascotInk, shape)
            .padding(horizontal = if (small) 9.dp else 11.dp, vertical = if (small) 4.dp else 6.dp)
    ) {
        Text(
            text = text,
            color = MascotText,
            fontSize = if (small) 11.5.sp else 13.sp,
            lineHeight = if (small) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** The long-press menu: two rows of three. */
@Composable
private fun PetMenu(
    kind: PetKind,
    look: PetLook,
    onDance: () -> Unit,
    onSpecial: () -> Unit,
    onTreat: () -> Unit,
    onNap: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .shadow(8.dp, shape)
            .background(Color.White, shape)
            .border(1.5.dp, MascotInk, shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MascotMenuChip("💃", L.components.floatingMascot.dance, onDance)
            MascotMenuChip(kind.specialEmoji, kind.specialLabel(look), onSpecial)
            MascotMenuChip(kind.treatEmoji, L.pets.common.treat, onTreat)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MascotMenuChip("😴", L.components.floatingMascot.nap, onNap)
            MascotMenuChip("⚙️", L.common.settings, onSettings)
            MascotMenuChip("👋", L.common.hide, onHide)
        }
    }
}

@Composable
private fun MascotMenuChip(emoji: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(width = 74.dp, height = 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 16.sp, lineHeight = 18.sp)
        Text(label, color = MascotText, fontSize = 11.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
