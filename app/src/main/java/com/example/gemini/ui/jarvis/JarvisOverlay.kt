package com.example.gemini.ui.jarvis

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisController.Phase
import com.example.gemini.data.jarvis.JarvisPreferences
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val Cyan = Color(0xFF22D3EE)
private val Blue = Color(0xFF3B82F6)
private val Violet = Color(0xFF8B5CF6)
private val Pink = Color(0xFFEC4899)
private val Green = Color(0xFF22C55E)

private fun accentOf(p: Phase) = when (p) {
    Phase.THINKING -> Violet
    Phase.SPEAKING -> Green
    else -> Cyan
}

private fun defaultLabel(p: Phase) = when (p) {
    Phase.LISTENING -> "Listening…"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Speaking…"
    else -> "Ready"
}

/**
 * Jarvis' face. A small draggable orb sits on every screen (tap to talk, long-press to stop). While Jarvis is active a
 * compact card rises above it with Siri-style flowing waves and a live line of what it is doing ("Checking the build"),
 * with the last few steps fading below. In full-screen style the same content takes the whole screen.
 */
@Composable
fun JarvisOverlay() {
    val enabled by JarvisPreferences.enabled.state.collectAsState()
    if (!enabled || !JarvisController.isAttached) return

    val phase by JarvisController.phase.collectAsState()
    val heard by JarvisController.heard.collectAsState()
    val log by JarvisController.log.collectAsState()
    val steps by JarvisController.steps.collectAsState()
    val notice by JarvisController.notice.collectAsState()
    val style by JarvisPreferences.orbStyle.state.collectAsState()
    val showTranscript by JarvisPreferences.showTranscript.state.collectAsState()
    val showActivity by JarvisPreferences.showActivity.state.collectAsState()

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
    val onTap = {
        if (!JarvisController.hasMic()) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else JarvisController.onMicPressed()
    }

    val active = phase != Phase.IDLE && phase != Phase.OFF
    val lastSaid = log.lastOrNull { it.first == "Jarvis" }?.second.orEmpty()
    val line = when {
        phase == Phase.LISTENING -> heard
        phase == Phase.SPEAKING -> lastSaid
        else -> ""
    }
    val cardVisible = active || (showActivity && steps.isNotEmpty())

    if (style == "fullscreen" && active) {
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
                StepsBlock(phase, steps, showActivity, big = true)
                if (showTranscript && line.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(line, color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(30.dp))
                ActionButtons(phase, onTap, big = true)
            }
        }
        return
    }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().padding(end = 14.dp, bottom = 96.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
        ) {
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 3 } + expandVertically(tween(260)),
                exit = fadeOut(tween(220)) + shrinkVertically(tween(260))
            ) {
                ActivityCard(phase, level, steps, line, showActivity, showTranscript, onTap, Modifier.padding(bottom = 10.dp))
            }
            AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    notice.orEmpty(), fontSize = 12.sp, color = Color.White, maxLines = 3,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE6B91C1C))
                        .clickable { JarvisController.clearNotice() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Box(
                Modifier
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); offsetX += drag.x; offsetY += drag.y } }
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { JarvisController.stopEverything() }) }
            ) {
                JarvisOrb(phase, level, if (active) 64.dp else 52.dp)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ card

@Composable
private fun ActivityCard(
    phase: Phase, level: Float, steps: List<JarvisController.Step>, line: String,
    showActivity: Boolean, showTranscript: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier
) {
    val accent by animateColor(accentOf(phase))
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .widthIn(max = 320.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF2162033), Color(0xF20A0F1C))))
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.65f), Color.White.copy(alpha = 0.05f), accent.copy(alpha = 0.25f))), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SiriWaves(phase, level, Modifier.fillMaxWidth().height(48.dp))
        if (showActivity) {
            Spacer(Modifier.height(6.dp))
            StepsBlock(phase, steps, true, big = false)
        }
        if (showTranscript && line.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(line, color = Color.White.copy(alpha = 0.82f), fontSize = 12.5.sp, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (phase != Phase.IDLE && phase != Phase.OFF) {
            Spacer(Modifier.height(10.dp))
            ActionButtons(phase, onTap, big = false)
        }
    }
}

/** What the user can do right now: cancel or send while listening, stop while Jarvis works or speaks. */
@Composable
private fun ActionButtons(phase: Phase, onTap: () -> Unit, big: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (phase) {
            Phase.LISTENING -> {
                PillButton("Cancel", Icons.Filled.Close, Color(0xFFEF4444), big) { JarvisController.cancelCapture() }
                PillButton("Send", Icons.Filled.Send, Cyan, big, filled = true) { JarvisController.finishListening() }
            }
            Phase.SPEAKING -> {
                PillButton("Stop", Icons.Filled.Stop, Color(0xFFEF4444), big) { JarvisController.silence() }
                PillButton("Reply", Icons.Filled.Mic, Cyan, big, filled = true) { onTap() }
            }
            Phase.THINKING -> {
                PillButton("Stop", Icons.Filled.Stop, Color(0xFFEF4444), big) { JarvisController.stopEverything() }
            }
            else -> {}
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, color: Color, big: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = if (filled) 0.7f else 0.45f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = if (big) 20.dp else 14.dp, vertical = if (big) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(if (big) 20.dp else 16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = if (big) 14.sp else 12.5.sp, fontWeight = FontWeight.Medium)
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
private fun StepsBlock(phase: Phase, steps: List<JarvisController.Step>, show: Boolean, big: Boolean) {
    if (!show) return
    val current = steps.lastOrNull()
    val text = current?.text ?: defaultLabel(phase)
    val accent = accentOf(phase)
    Column(horizontalAlignment = if (big) Alignment.CenterHorizontally else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(accent, working = phase != Phase.IDLE)
            Spacer(Modifier.width(9.dp))
            Crossfade(targetState = text, animationSpec = tween(260), label = "step") { t ->
                Text(t, color = Color.White, fontSize = if (big) 17.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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

@Composable
private fun PulseDot(color: Color, working: Boolean) {
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
        if (working) drawCircle(color.copy(alpha = 0.28f * a), radius = size.minDimension / 2 * s * 1.5f, center = c)
        drawCircle(color.copy(alpha = if (working) a else 0.6f), radius = size.minDimension / 3.2f, center = c)
    }
}

// ------------------------------------------------------------------------------------------------ waves & orb

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

/** The orb button: rings that follow the level, a gradient core, and a spinning arc while thinking. */
@Composable
fun JarvisOrb(phase: Phase, level: Float, size: Dp) {
    if (phase == Phase.IDLE || phase == Phase.OFF) {
        // idle: a still orb, no running animation
        Canvas(Modifier.size(size * 1.5f)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val core = this.size.minDimension / 3f
            drawCircle(Cyan.copy(alpha = 0.12f), radius = core * 1.3f, center = c)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Cyan.copy(alpha = 0.6f), Cyan.copy(alpha = 0.35f)), center = c, radius = core),
                radius = core, center = c
            )
        }
        return
    }
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val smooth by animateFloatAsState(level, tween(90), label = "level")
    val color = when (phase) {
        Phase.LISTENING -> Cyan
        Phase.THINKING -> Violet
        Phase.SPEAKING -> Green
        else -> Cyan.copy(alpha = 0.6f)
    }
    Canvas(Modifier.size(size * 1.5f)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val core = this.size.minDimension / 3f
        val grow = when (phase) {
            Phase.LISTENING, Phase.SPEAKING -> 1f + smooth * 0.5f
            Phase.THINKING -> breathe
            else -> breathe * 0.96f
        }
        for (i in 1..3) {
            drawCircle(color.copy(alpha = (0.20f - i * 0.045f).coerceAtLeast(0.02f)), radius = core * (1f + i * 0.28f) * grow, center = c)
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.55f)), center = c, radius = core * grow),
            radius = core * grow, center = c
        )
        if (phase == Phase.THINKING) {
            drawArc(
                Color.White.copy(alpha = 0.9f), startAngle = spin, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(c.x - core * 1.3f, c.y - core * 1.3f), size = Size(core * 2.6f, core * 2.6f),
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
        }
    }
}
