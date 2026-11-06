package com.example.gemini.ui.jarvis

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisController.Phase
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.jarvis.JarvisTasks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal val Cyan = Color(0xFF22D3EE)
internal val Blue = Color(0xFF3B82F6)
internal val Violet = Color(0xFF8B5CF6)
internal val Pink = Color(0xFFEC4899)
internal val Green = Color(0xFF22C55E)
internal val Amber = Color(0xFFF59E0B)
internal val Danger = Color(0xFFEF4444)
internal val GlassTop = Color(0xF2162033)
internal val GlassBottom = Color(0xF20A0F1C)

internal fun accentOf(p: Phase) = when (p) {
    Phase.THINKING -> Violet
    Phase.SPEAKING -> Green
    else -> Cyan
}

private fun defaultLabel(p: Phase, quiet: Boolean) = when (p) {
    Phase.LISTENING -> "Listening…"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> if (quiet) "Jarvis" else "Speaking…"
    else -> "Ready"
}

/**
 * Jarvis' face. While idle it is a small arc-reactor orb resting on a screen edge (drag it anywhere: it snaps to the
 * nearest edge; tap to talk, long-press for the Jarvis panel). While Jarvis works a slim status pill shows the current
 * step; only while it listens or speaks does the full card open, with flowing waves, the live transcript, the
 * send countdown and the buttons. In full-screen style listening and speaking take the whole screen.
 */
@Composable
fun JarvisOverlay() {
    val enabled by JarvisPreferences.enabled.state.collectAsState()
    if (!enabled || !JarvisController.isAttached) return

    val phase by JarvisController.phase.collectAsState()
    val heard by JarvisController.heard.collectAsState()
    val said by JarvisController.said.collectAsState()
    val steps by JarvisController.steps.collectAsState()
    val notice by JarvisController.notice.collectAsState()
    val sendProgress by JarvisController.sendProgress.collectAsState()
    val holding by JarvisController.holding.collectAsState()
    val mcpProblem by JarvisController.mcpProblem.collectAsState()
    val panelOpen by JarvisController.panelOpen.collectAsState()
    val tasks by JarvisTasks.tasks.collectAsState()
    val style by JarvisPreferences.orbStyle.state.collectAsState()
    val showTranscript by JarvisPreferences.showTranscript.state.collectAsState()
    val showActivity by JarvisPreferences.showActivity.state.collectAsState()
    val orbSize by JarvisPreferences.orbSize.state.collectAsState()
    val autoNow by JarvisController.autoSendNow.collectAsState()
    val quiet by JarvisPreferences.quietMode.state.collectAsState()

    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(phase) {
        while (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            level = JarvisController.level()
            delay(45)
        }
        level = 0f
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) JarvisController.onMicPressed()
    }
    val onTap: () -> Unit = {
        if (!JarvisController.hasMic()) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else JarvisController.onMicPressed()
    }
    val onNoticeAction: (String) -> Unit = { action ->
        when (action) {
            "mic" -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> JarvisController.requestSettings("JARVIS")
        }
        JarvisController.clearNotice()
    }

    val expanded = phase == Phase.LISTENING || phase == Phase.SPEAKING
    val showPill = !expanded && showActivity && (phase == Phase.THINKING || steps.isNotEmpty())
    val line = when (phase) {
        Phase.LISTENING -> heard
        Phase.SPEAKING -> said
        else -> ""
    }
    val activeTasks = tasks.count { it.active }
    val attention = mcpProblem || tasks.any { it.state.needsUser }

    if (style == "fullscreen" && expanded) {
        FullScreenJarvis(phase, level, steps, line, showTranscript, showActivity, sendProgress, holding, autoNow, quiet, onTap)
    } else {
        OrbLayer(
            phase = phase, level = level, expanded = expanded, showPill = showPill, steps = steps, line = line,
            showTranscript = showTranscript, showActivity = showActivity, sendProgress = sendProgress, holding = holding,
            auto = autoNow, quiet = quiet, notice = notice, orbSize = orbSize, activeTasks = activeTasks,
            attention = attention, tasks = tasks, onTap = onTap, onNoticeAction = onNoticeAction
        )
    }
    if (panelOpen) JarvisPanel(onTalk = onTap, onNoticeAction = onNoticeAction)
}

// ------------------------------------------------------------------------------------------------ orb + card layer

@Composable
private fun OrbLayer(
    phase: Phase, level: Float, expanded: Boolean, showPill: Boolean, steps: List<JarvisController.Step>, line: String,
    showTranscript: Boolean, showActivity: Boolean, sendProgress: Float, holding: Boolean, auto: Boolean, quiet: Boolean,
    notice: JarvisController.Notice?, orbSize: String, activeTasks: Int, attention: Boolean, tasks: List<JarvisTasks.Task>,
    onTap: () -> Unit, onNoticeAction: (String) -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val side by JarvisPreferences.orbSide.state.collectAsState()
    val yRatio by JarvisPreferences.orbY.state.collectAsState()
    val active = expanded || phase == Phase.THINKING
    val idleDp = when (orbSize) { "tiny" -> 30.dp; "normal" -> 54.dp; else -> 40.dp }
    val orbDp = if (active) maxOf(idleDp, 50.dp) else idleDp
    val hidden = orbSize == "hidden" && !active && !showPill && notice == null
    // finger movement while dragging, then a spring that carries the orb to the edge it was dropped nearest to
    var dragOff by remember { mutableStateOf(Offset.Zero) }
    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // last placement: x, y, width, height (for snapping after a drag)
    val placed = remember { FloatArray(4) }

    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    val top = with(density) { 64.dp.toPx() }
    val bottom = with(density) { 84.dp.toPx() }
    val orbPx = with(density) { orbDp.toPx() }
    val maxCard = with(density) { 340.dp.toPx() }

    fun baseOf(s: String, y: Float, w: Float, h: Float): Offset {
        val x = if (s == "left") margin else w - margin - orbPx
        val usable = (h - top - bottom - orbPx).coerceAtLeast(1f)
        return Offset(x, top + y.coerceIn(0f, 1f) * usable)
    }

    fun snap() {
        val w = placed[2]
        val h = placed[3]
        if (w <= 0f || h <= 0f) return
        val cur = Offset(placed[0], placed[1])
        val newSide = if (cur.x + orbPx / 2 < w / 2) "left" else "right"
        val usable = (h - top - bottom - orbPx).coerceAtLeast(1f)
        val newY = ((cur.y - top) / usable).coerceIn(0f, 1f)
        val newBase = baseOf(newSide, newY, w, h)
        scope.launch {
            settle.snapTo(cur - newBase)
            dragOff = Offset.Zero
            JarvisPreferences.orbSide.set(newSide)
            JarvisPreferences.orbY.set(newY)
            settle.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val onLeft = side == "left"
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // 0: what sits next to the orb (notice, card or pill)
            Column(horizontalAlignment = if (onLeft) Alignment.Start else Alignment.End) {
                AnimatedVisibility(visible = notice != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    notice?.let { NoticeChip(it, onNoticeAction, Modifier.padding(bottom = 6.dp)) }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + scaleIn(tween(240), initialScale = 0.85f) + expandVertically(tween(240)),
                    exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.9f) + shrinkVertically(tween(220))
                ) {
                    ActivityCard(phase, level, steps, line, showActivity, showTranscript, sendProgress, holding, auto, quiet, tasks, onTap)
                }
                AnimatedVisibility(visible = !expanded && showPill, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    StatusPill(phase, steps)
                }
            }
            // 1: the orb
            if (hidden) {
                Box(Modifier.size(0.dp))
            } else {
                Box(
                    Modifier
                        .size(orbDp)
                        .pointerInput(orbPx) {
                            detectDragGestures(
                                onDragEnd = { snap() },
                                onDragCancel = { snap() },
                                onDrag = { change, d -> change.consume(); dragOff += d }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onTap() }, onLongPress = { JarvisController.panelOpen.value = true })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (active) JarvisOrb(phase, level, orbDp) else IdleOrb(orbDp, activeTasks, attention)
                    if (active && (activeTasks > 0 || attention)) TaskBadge(orbDp, activeTasks, attention)
                }
            }
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val orbP = measurables[1].measure(Constraints())
        val cardP = measurables[0].measure(Constraints(maxWidth = minOf(w - 2 * margin, maxCard).roundToInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val base = baseOf(side, yRatio, w, h)
            val ox = (base.x + dragOff.x + settle.value.x).coerceIn(0f, (w - orbPx).coerceAtLeast(0f))
            val oy = (base.y + dragOff.y + settle.value.y).coerceIn(0f, (h - orbPx).coerceAtLeast(0f))
            placed[0] = ox; placed[1] = oy; placed[2] = w; placed[3] = h
            orbP.place(ox.roundToInt(), oy.roundToInt())
            if (cardP.width > 0 && cardP.height > 0) {
                val orbOnLeft = ox + orbPx / 2 < w / 2
                val cx = if (orbOnLeft) margin else w - margin - cardP.width
                val above = oy - gap - cardP.height
                val cy = if (above >= top * 0.5f) above else (oy + orbPx + gap).coerceAtMost(h - cardP.height)
                cardP.place(cx.roundToInt(), cy.roundToInt())
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ card, pill, notice

@Composable
private fun ActivityCard(
    phase: Phase, level: Float, steps: List<JarvisController.Step>, line: String, showActivity: Boolean, showTranscript: Boolean,
    sendProgress: Float, holding: Boolean, auto: Boolean, quiet: Boolean, tasks: List<JarvisTasks.Task>, onTap: () -> Unit
) {
    val accent by animateColor(accentOf(phase))
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GlassTop, GlassBottom)))
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.7f), Color.White.copy(alpha = 0.05f), accent.copy(alpha = 0.25f))), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SiriWaves(phase, level, Modifier.fillMaxWidth().height(if (quiet && phase == Phase.SPEAKING) 26.dp else 44.dp))
        if (showActivity) {
            Spacer(Modifier.height(6.dp))
            StepsBlock(phase, steps, big = false, quiet = quiet)
        }
        if ((showTranscript || (quiet && phase == Phase.SPEAKING)) && line.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            if (quiet && phase == Phase.SPEAKING) {
                // quiet mode: the answer is the main content
                Text(line, color = Color.White, fontSize = 14.5.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            } else {
                Text(line, color = Color.White.copy(alpha = 0.85f), fontSize = 12.5.sp, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (phase == Phase.LISTENING) {
            Spacer(Modifier.height(8.dp))
            SendMeter(sendProgress, holding, auto)
        }
        Spacer(Modifier.height(10.dp))
        ActionButtons(phase, holding, auto, quiet, onTap, big = false)
        val live = tasks.filter { it.active }.take(3)
        if (live.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { live.forEach { TaskChip(it) } }
        }
    }
}

/**
 * How sending works right now. The hint stays the same for the whole capture (it never flips while you talk); in the
 * auto modes a thin bar fades in and fills only during a real pause, and empties again when you go on talking.
 */
@Composable
private fun SendMeter(progress: Float, holding: Boolean, auto: Boolean) {
    val phrase = remember { com.example.gemini.data.jarvis.JarvisPhrases.exampleSend() }
    val hint = when {
        holding -> if (phrase != null) "Waiting · say “$phrase” or tap Send" else "Waiting for you · tap Send"
        auto -> if (phrase != null) "Pause to send · or say “$phrase”" else "Pause to send"
        phrase != null -> "Say “$phrase” or tap Send"
        else -> "Tap Send when you're done"
    }
    Column {
        if (!holding && auto) {
            val p by animateFloatAsState(progress, tween(120), label = "send")
            val shown by animateFloatAsState(if (progress > 0f) 1f else 0f, tween(if (progress > 0f) 220 else 160), label = "sendAlpha")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p)
                        .fillMaxHeight()
                        .alpha(shown)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(Cyan, Blue)))
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Text(hint, color = Color.White.copy(alpha = 0.5f), fontSize = 10.5.sp)
    }
}

/** A slim line with the current step while Jarvis works (tap: the Jarvis panel). */
@Composable
private fun StatusPill(phase: Phase, steps: List<JarvisController.Step>) {
    val text = steps.lastOrNull()?.text ?: defaultLabel(phase, false)
    val accent = accentOf(phase)
    Row(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(GlassTop, GlassBottom)))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable { JarvisController.panelOpen.value = true }
            .padding(start = 12.dp, end = if (phase == Phase.THINKING) 6.dp else 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulseDot(accent, working = phase == Phase.THINKING)
        Spacer(Modifier.width(8.dp))
        Crossfade(targetState = text, animationSpec = tween(220), label = "pill") { t ->
            Text(t, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (phase == Phase.THINKING) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Danger.copy(alpha = 0.16f)).clickable { JarvisController.stopEverything() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Stop, "Stop", tint = Danger, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
internal fun NoticeChip(n: JarvisController.Notice, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val color = if (n.action == "setup") Amber else Danger
    Row(
        modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF0101827))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable { JarvisController.clearNotice() }
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(n.text, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        n.action?.let { a ->
            Spacer(Modifier.width(8.dp))
            Text(
                if (a == "mic") "Allow" else "Set up", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(color).clickable { onAction(a) }.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
internal fun TaskChip(t: JarvisTasks.Task) {
    val c = taskColor(t.state)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable { JarvisController.panelOpen.value = true }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(5.dp))
        Text(t.title.take(16), color = Color.White.copy(alpha = 0.85f), fontSize = 10.5.sp, maxLines = 1)
    }
}

internal fun taskColor(s: JarvisTasks.State): Color = when (s) {
    JarvisTasks.State.STARTING -> Cyan
    JarvisTasks.State.RUNNING -> Violet
    JarvisTasks.State.NEEDS_APPROVAL, JarvisTasks.State.NEEDS_ANSWER -> Amber
    JarvisTasks.State.STALLED -> Color(0xFFF97316)
    JarvisTasks.State.DONE -> Green
    JarvisTasks.State.FAILED -> Danger
    JarvisTasks.State.STOPPED -> Color(0xFF9CA3AF)
}

/** What the user can do right now: cancel / wait / send while listening, stop / reply while Jarvis speaks. */
@Composable
private fun ActionButtons(phase: Phase, holding: Boolean, auto: Boolean, quiet: Boolean, onTap: () -> Unit, big: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (phase) {
            Phase.LISTENING -> {
                PillButton("Cancel", Icons.Filled.Close, Danger, big) { JarvisController.cancelCapture() }
                if (!holding && auto) PillButton("Wait", Icons.Filled.PauseCircle, Amber, big) { JarvisController.holdListening() }
                PillButton("Send", Icons.Filled.Send, Cyan, big, filled = true) { JarvisController.finishListening() }
            }
            Phase.SPEAKING -> {
                if (quiet) PillButton("OK", Icons.Filled.Check, Green, big) { JarvisController.silence() }
                else PillButton("Stop", Icons.Filled.Stop, Danger, big) { JarvisController.silence() }
                PillButton("Reply", Icons.Filled.Mic, Cyan, big, filled = true) { onTap() }
            }
            Phase.THINKING -> PillButton("Stop", Icons.Filled.Stop, Danger, big) { JarvisController.stopEverything() }
            else -> {}
        }
    }
}

@Composable
internal fun PillButton(label: String, icon: ImageVector, color: Color, big: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = if (filled) 0.7f else 0.45f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = if (big) 20.dp else 12.dp, vertical = if (big) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(if (big) 20.dp else 15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = if (big) 14.sp else 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun animateColor(target: Color): State<Color> {
    val r by animateFloatAsState(target.red, tween(400), label = "r")
    val g by animateFloatAsState(target.green, tween(400), label = "g")
    val b by animateFloatAsState(target.blue, tween(400), label = "b")
    return remember { derivedStateOf { Color(r, g, b) } }
}

/** The current step, big and bright, with the steps before it fading out below. */
@Composable
private fun StepsBlock(phase: Phase, steps: List<JarvisController.Step>, big: Boolean, quiet: Boolean) {
    val current = steps.lastOrNull()?.takeIf { phase == Phase.THINKING || System.currentTimeMillis() - it.at < 6000 }
    val text = if (phase == Phase.LISTENING) defaultLabel(phase, quiet) else current?.text ?: defaultLabel(phase, quiet)
    val accent = accentOf(phase)
    Column(horizontalAlignment = if (big) Alignment.CenterHorizontally else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent, working = phase != Phase.IDLE)
            Spacer(Modifier.width(9.dp))
            Crossfade(targetState = text, animationSpec = tween(260), label = "step") { t ->
                Text(t, color = Color.White, fontSize = if (big) 17.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (phase == Phase.THINKING) {
            val previous = steps.dropLast(1).takeLast(3).reversed()
            previous.forEachIndexed { i, s ->
                Text(
                    "✓  ${s.text}",
                    color = Color.White.copy(alpha = (0.5f - i * 0.14f).coerceAtLeast(0.15f)),
                    fontSize = if (big) 13.sp else 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, start = if (big) 0.dp else 19.dp)
                )
            }
        }
    }
}

@Composable
internal fun PulseDot(color: Color, working: Boolean) {
    // nothing animates unless Jarvis is actually working (an infinite transition costs battery even when idle)
    if (!working) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color.copy(alpha = 0.6f), radius = size.minDimension / 3.2f, center = Offset(size.width / 2, size.height / 2))
        }
        return
    }
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val s by t.animateFloat(0.85f, 1.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Canvas(Modifier.size(10.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color.copy(alpha = 0.28f * a), radius = size.minDimension / 2 * s * 1.5f, center = c)
        drawCircle(color.copy(alpha = a), radius = size.minDimension / 3.2f, center = c)
    }
}

// ------------------------------------------------------------------------------------------------ full screen

@Composable
private fun FullScreenJarvis(
    phase: Phase, level: Float, steps: List<JarvisController.Step>, line: String, showTranscript: Boolean, showActivity: Boolean,
    sendProgress: Float, holding: Boolean, auto: Boolean, quiet: Boolean, onTap: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xF0070B14), Color(0xF2101828))))
            // swallow touches so nothing behind the overlay is hit by accident
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            SiriWaves(phase, level, Modifier.fillMaxWidth().height(200.dp))
            Spacer(Modifier.height(20.dp))
            if (showActivity) StepsBlock(phase, steps, big = true, quiet = quiet)
            if ((showTranscript || quiet) && line.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(line, color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            if (phase == Phase.LISTENING) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.widthIn(max = 320.dp)) { SendMeter(sendProgress, holding, auto) }
            }
            Spacer(Modifier.height(30.dp))
            ActionButtons(phase, holding, auto, quiet, onTap, big = true)
        }
    }
}

// ------------------------------------------------------------------------------------------------ waves & orbs

/**
 * Siri-style ribbon: five translucent sine layers that add up to a glowing, tapering wave. It swells with the level while
 * listening or speaking, drifts slowly while thinking and flattens when idle.
 */
@Composable
fun SiriWaves(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    if (phase == Phase.IDLE || phase == Phase.OFF) {
        // idle: one still, faint line; no animation runs
        Canvas(modifier) {
            val y = size.height / 2f
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.5f), Color.Transparent), 0f, size.width),
                Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
        return
    }
    val t = rememberInfiniteTransition(label = "siri")
    val time by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "time")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900), RepeatMode.Reverse), label = "drift")
    val smooth by animateFloatAsState(level, tween(110), label = "lvl")
    val amp by animateFloatAsState(
        when (phase) {
            Phase.LISTENING -> 0.16f + smooth * 0.84f
            Phase.SPEAKING -> 0.22f + smooth * 0.78f
            Phase.THINKING -> 0.26f + drift * 0.14f
            else -> 0.06f
        },
        tween(160), label = "amp"
    )
    val colors = when (phase) {
        Phase.THINKING -> listOf(Violet, Pink, Blue)
        Phase.SPEAKING -> listOf(Green, Cyan, Blue)
        else -> listOf(Cyan, Blue, Violet)
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val brush = Brush.horizontalGradient(colors, 0f, w)
        val segments = 72
        for (layer in 0 until 5) {
            val a = amp * (h / 2f) * (1f - layer * 0.16f)
            val freq = 1.3f + layer * 0.6f
            val speed = (1f + layer * 0.35f) * (if (layer % 2 == 0) 1f else -1f)
            val path = Path()
            for (i in 0..segments) {
                val x = w * i / segments
                val env = sin(PI * i / segments).toFloat().pow(2)
                val y = mid + a * env * sin((freq * (i.toFloat() / segments) * 2f * PI.toFloat()) + time * speed + layer)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, brush, alpha = 0.95f - layer * 0.17f,
                style = Stroke(width = (3.4f - layer * 0.45f).dp.toPx(), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
        }
    }
}

/** The idle orb: a small, still arc reactor (glass body, segmented ring, glowing core). Running tasks show as violet arcs. */
@Composable
fun IdleOrb(size: Dp, activeTasks: Int, attention: Boolean) {
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Color.Black.copy(alpha = 0.28f), radius = r, center = c.copy(y = c.y + r * 0.08f))
        drawCircle(Brush.radialGradient(listOf(Color(0xF0152238), Color(0xF00A0F1C)), c, r), radius = r * 0.96f, center = c)
        drawCircle(Cyan.copy(alpha = 0.55f), radius = r * 0.92f, center = c, style = Stroke(width = r * 0.06f))
        val ring = r * 0.68f
        for (i in 0 until 3) {
            drawArc(
                Cyan.copy(alpha = 0.45f), startAngle = i * 120f + 20f, sweepAngle = 80f, useCenter = false,
                topLeft = Offset(c.x - ring, c.y - ring), size = Size(ring * 2, ring * 2), style = Stroke(width = r * 0.09f, cap = StrokeCap.Round)
            )
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, Cyan.copy(alpha = 0.85f), Cyan.copy(alpha = 0f)), c, r * 0.46f), radius = r * 0.46f, center = c)
        if (activeTasks > 0) {
            val sweep = (360f / activeTasks.coerceAtMost(4)) - 14f
            for (i in 0 until activeTasks.coerceAtMost(4)) {
                drawArc(
                    Violet, startAngle = -90f + i * (sweep + 14f) + 7f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(c.x - r * 0.92f, c.y - r * 0.92f), size = Size(r * 1.84f, r * 1.84f), style = Stroke(width = r * 0.1f, cap = StrokeCap.Round)
                )
            }
        }
        if (attention) {
            drawCircle(Color(0xFF0A0F1C), radius = r * 0.27f, center = Offset(c.x + r * 0.7f, c.y - r * 0.7f))
            drawCircle(Amber, radius = r * 0.2f, center = Offset(c.x + r * 0.7f, c.y - r * 0.7f))
        }
    }
}

/** Small marker on the active orb: violet with the number of running tasks, amber when something needs the user. */
@Composable
private fun TaskBadge(orb: Dp, count: Int, attention: Boolean) {
    Box(Modifier.size(orb), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(if (attention) Amber else Violet).border(1.5.dp, Color(0xFF0A0F1C), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(if (attention) "!" else count.coerceAtMost(9).toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }
}

/** The active orb: rings that follow the level, a gradient core, and a spinning arc while thinking. */
@Composable
fun JarvisOrb(phase: Phase, level: Float, size: Dp) {
    if (phase == Phase.IDLE || phase == Phase.OFF) {
        IdleOrb(size, 0, false)
        return
    }
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val smooth by animateFloatAsState(level, tween(90), label = "level")
    val color = accentOf(phase)
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val core = this.size.minDimension / 3.1f
        val grow = when (phase) {
            Phase.LISTENING, Phase.SPEAKING -> 1f + smooth * 0.45f
            else -> breathe
        }
        drawCircle(Color(0xE60A0F1C), radius = this.size.minDimension / 2, center = c)
        for (i in 1..3) {
            drawCircle(color.copy(alpha = (0.22f - i * 0.05f).coerceAtLeast(0.03f)), radius = core * (1f + i * 0.22f) * grow, center = c)
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.55f)), center = c, radius = core * grow),
            radius = core * grow, center = c
        )
        if (phase == Phase.THINKING) {
            drawArc(
                Color.White.copy(alpha = 0.9f), startAngle = spin, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(c.x - core * 1.35f, c.y - core * 1.35f), size = Size(core * 2.7f, core * 2.7f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
}
