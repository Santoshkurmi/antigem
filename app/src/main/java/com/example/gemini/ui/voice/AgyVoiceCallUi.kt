package com.example.gemini.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.voice.AgyVoice
import com.example.gemini.data.voice.AgyVoiceCall
import com.example.gemini.data.voice.AgyVoiceCall.Phase
import com.example.gemini.data.voice.AgyVoicePreferences
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private fun rgb(v: Int) = Color(0xFF000000.toInt() or v)
private val CallRed = Color(0xFFE5484D)
private val Ink = Color(0xFF07080C)

/** "01:23" (or "1:02:03") since the voice agent picked up; null before that. */
@Composable
private fun rememberCallClock(connectedAt: Long): String? {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectedAt) {
        while (connectedAt > 0) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    if (connectedAt <= 0) return null
    val s = ((now - connectedAt) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun statusLine(st: AgyVoiceCall.State, agentWorking: Boolean): String = when (st.phase) {
    Phase.CONNECTING -> st.statusText.ifBlank { "Connecting…" }
    Phase.RECONNECTING -> if (st.statusText.startsWith("Switching")) st.statusText
        else "Reconnecting…" + if (st.maxAttempts > 0) " (${st.attempt}/${st.maxAttempts})" else ""
    Phase.ENDING -> "Ending…"
    Phase.FAILED -> "Call ended"
    Phase.IDLE -> ""
    Phase.LIVE -> when {
        st.muted -> "You're muted"
        st.agentSpeaking -> "${st.voice} is speaking · talk to interrupt"
        st.userSpeaking -> "Listening…"
        agentWorking -> "The agent is working on it…"
        else -> "Listening"
    }
}

/**
 * The full-screen call (shown while a call runs and is not minimized). Back minimizes it to [AgyVoiceCallPill].
 * [agentWorking]: the call's chat is running a turn right now.
 */
@Composable
fun AgyVoiceCallScreen(agentWorking: Boolean, onOpenAgySettings: () -> Unit) {
    val st by AgyVoiceCall.state.collectAsState()
    if (!st.active || st.minimized) return
    Dialog(
        onDismissRequest = { if (st.phase == Phase.FAILED) AgyVoiceCall.end() else AgyVoiceCall.setMinimized(true) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        CallContent(st, agentWorking, onOpenAgySettings)
    }
}

@Composable
private fun CallContent(st: AgyVoiceCall.State, agentWorking: Boolean, onOpenAgySettings: () -> Unit) {
    val context = LocalContext.current
    val voice = AgyVoicePreferences.voiceOf(st.voice)
    val captionsOn by AgyVoicePreferences.captions.collectAsState()
    val clock = rememberCallClock(st.connectedAt)
    var showVoices by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    // a call keeps the screen on; a light tap when the agent picks up and when the call drops
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val haptics = LocalHapticFeedback.current
    var wasLive by remember { mutableStateOf(false) }
    LaunchedEffect(st.phase) {
        if (st.phase == Phase.LIVE && !wasLive) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (st.phase == Phase.FAILED) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasLive = st.phase == Phase.LIVE || wasLive
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(rgb(voice.deep).copy(alpha = 1f), Ink, Ink)))
    ) {
        // soft colour wash behind the orb
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(rgb(voice.core).copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.38f), radius = size.width * 0.9f),
                radius = size.width * 0.9f, center = Offset(size.width / 2, size.height * 0.38f)
            )
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
            // ---- top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (st.phase == Phase.FAILED) AgyVoiceCall.end() else AgyVoiceCall.setMinimized(true) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(st.title.ifBlank { "Antigravity" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Antigravity voice · ${voice.name}" +
                            (if (st.language != AgyVoicePreferences.LANGUAGE_AUTO) " · ${AgyVoicePreferences.languageLabel(st.language)}" else ""),
                        color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1
                    )
                }
                if (clock != null) {
                    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.08f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            LiveDot(rgb(voice.core))
                            Spacer(Modifier.width(6.dp))
                            Text(clock, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                IconButton(onClick = { showLog = true }) {
                    Icon(Icons.Rounded.Info, "Call log", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                }
            }

            // ---- status
            Spacer(Modifier.height(10.dp))
            Text(
                statusLine(st, agentWorking),
                color = Color.White.copy(alpha = if (st.phase == Phase.LIVE) 0.8f else 0.6f),
                fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )

            // ---- orb
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight) * 0.92f
                VoiceOrb(
                    voice = voice, phase = st.phase, muted = st.muted, agentWorking = agentWorking && st.phase == Phase.LIVE,
                    modifier = Modifier.size(side).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null
                    ) { if (st.phase == Phase.LIVE) AgyVoiceCall.toggleMute() }
                )
            }

            // ---- failure, or the captions
            if (st.phase == Phase.FAILED) {
                FailureCard(
                    st,
                    onRetry = { AgyVoiceCall.retry(context) },
                    onUpdate = { AgyVoiceCall.end(); onOpenAgySettings() },
                    onDetails = { showLog = true },
                    onClose = { AgyVoiceCall.end() }
                )
            } else if (captionsOn) {
                Captions(st, voice, Modifier.fillMaxWidth().height(170.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }

            // ---- controls
            Spacer(Modifier.height(14.dp))
            if (st.phase != Phase.FAILED) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundControl(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Unmute" else "Mute", active = st.muted) { AgyVoiceCall.toggleMute() }
                    RoundControl(if (st.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.Hearing, if (st.speaker) "Speaker" else "Earpiece", active = st.speaker) { AgyVoiceCall.toggleSpeaker() }
                    RoundControl(if (captionsOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, "Captions", active = captionsOn) {
                        AgyVoicePreferences.setCaptions(!captionsOn)
                    }
                    RoundControl(Icons.Rounded.Tune, "Voice", active = false) { showVoices = true }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(onClick = { AgyVoiceCall.end() }, shape = CircleShape, color = CallRed, modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(30.dp)) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("End", color = Color.White.copy(alpha = 0.7f), fontSize = 11.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- voice & speed panel
        AnimatedVisibility(visible = showVoices, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showVoices = false })
        }
        AnimatedVisibility(
            visible = showVoices,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            VoicePanel(
                callVoice = st.voice,
                callLanguage = st.language,
                inCall = st.inCall,
                onBrowse = { showVoices = false; showPicker = true }
            ) { showVoices = false }
        }

        // ---- call log
        AnimatedVisibility(visible = showLog, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showLog = false })
        }
        AnimatedVisibility(
            visible = showLog,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            CallLogPanel(st.log) { showLog = false }
        }
    }

    if (showPicker) {
        AgyVoicePickerDialog(
            initialVoice = AgyVoicePreferences.voice.value,
            subtitle = "Swipe to hear each voice. The call switches to the one you pick.",
            onChoose = {
                AgyVoicePreferences.setVoice(it)
                AgyVoiceCall.changeVoice(it)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun LiveDot(color: Color) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dotA"
    )
    Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = pulse)))
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (active) Color.White else Color.White.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (active) 0f else 0.10f)),
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (active) Ink else Color.White, modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.5.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ orb

/**
 * The call's living orb in the voice's colours: it breathes while idle, swells with the voice, ripples when you talk,
 * swirls faster while the agent works, and a ring spins while connecting.
 */
@Composable
private fun VoiceOrb(voice: AgyVoice, phase: Phase, muted: Boolean, agentWorking: Boolean, modifier: Modifier = Modifier) {
    val mic by AgyVoiceCall.micLevel.collectAsState()
    val out by AgyVoiceCall.voiceLevel.collectAsState()
    OrbCanvas(voice, phase, if (muted) 0f else mic, out, agentWorking, dimmed = muted || phase == Phase.FAILED, modifier = modifier)
}

/** The orb itself, driven by a microphone level and a voice level (0..1). */
@Composable
internal fun OrbCanvas(
    voice: AgyVoice,
    phase: Phase,
    micLevel: Float,
    voiceLevel: Float,
    agentWorking: Boolean = false,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val micA by animateFloatAsState(micLevel, tween(110), label = "mic")
    val outA by animateFloatAsState(voiceLevel, tween(110), label = "out")
    val work by animateFloatAsState(if (agentWorking) 1f else 0f, tween(600), label = "work")
    val dim by animateFloatAsState(if (dimmed) 0.55f else 1f, tween(400), label = "dim")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    val deep = rgb(voice.deep)
    val core = rgb(voice.core)
    val glint = rgb(voice.glint)
    val connecting = phase == Phase.CONNECTING || phase == Phase.RECONNECTING

    Canvas(modifier.graphicsLayer { alpha = dim; compositingStrategy = CompositingStrategy.Offscreen }) {
        val c = center
        val base = size.minDimension / 2f * 0.58f
        val energy = max(outA, micA * 0.75f)
        val r = base * (1f + 0.025f * sin(time * 1.6f)) * (1f + 0.20f * outA + 0.08f * micA)

        // glow
        drawCircle(
            Brush.radialGradient(listOf(core.copy(alpha = 0.30f + 0.35f * energy), Color.Transparent), center = c, radius = r * 2.0f),
            radius = r * 2.0f, center = c
        )
        // ripples while you talk (and a slow pulse while connecting)
        val rippleStrength = max(micA, if (connecting) 0.35f else 0f)
        if (rippleStrength > 0.02f) for (i in 0 until 3) {
            val p = ((time * (if (connecting) 0.45f else 0.8f)) + i / 3f) % 1f
            drawCircle(
                glint.copy(alpha = (1f - p) * 0.45f * rippleStrength),
                radius = r * (1.05f + p * 0.85f), center = c, style = Stroke(width = 2.dp.toPx())
            )
        }
        // body: three wobbling layers
        val speed = 1f + energy * 2.2f + work * 1.4f
        for (layer in 0 until 3) {
            val lr = r * (1f - layer * 0.13f)
            val amp = 0.035f + 0.11f * energy + 0.03f * work + layer * 0.012f
            val path = blobPath(c, lr, time * speed * (0.55f + layer * 0.3f), amp, layer * 1.7f)
            val shift = Offset(cos(time * 0.7f + layer * 2f) * lr * 0.18f, sin(time * 0.9f + layer) * lr * 0.18f)
            drawPath(
                path,
                Brush.radialGradient(listOf(glint, core, deep), center = c + shift - Offset(lr * 0.25f, lr * 0.3f), radius = lr * 1.5f),
                alpha = 0.55f + 0.15f * layer
            )
        }
        // highlight
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), center = c - Offset(r * 0.32f, r * 0.38f), radius = r * 0.55f),
            radius = r * 0.55f, center = c - Offset(r * 0.32f, r * 0.38f)
        )
        if (connecting) spinner(c, r * 1.22f, time, glint)
    }
}

private fun blobPath(c: Offset, r: Float, t: Float, amp: Float, seed: Float): Path {
    val n = 72
    val pts = Array(n) { k ->
        val a = (2 * PI * k / n).toFloat()
        val wobble = 0.55f * sin(3 * a + t * 1.3f + seed) + 0.30f * sin(5 * a - t * 1.7f + seed * 2f) + 0.35f * sin(2 * a + t * 0.7f - seed)
        val rr = r * (1f + amp * wobble)
        Offset(c.x + rr * cos(a), c.y + rr * sin(a))
    }
    val path = Path()
    val mid = { a: Offset, b: Offset -> Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) }
    path.moveTo(mid(pts[n - 1], pts[0]).x, mid(pts[n - 1], pts[0]).y)
    for (k in 0 until n) {
        val p = pts[k]
        val m = mid(p, pts[(k + 1) % n])
        path.quadraticTo(p.x, p.y, m.x, m.y)
    }
    path.close()
    return path
}

private fun DrawScope.spinner(c: Offset, r: Float, t: Float, color: Color) {
    drawArc(
        color = color.copy(alpha = 0.85f),
        startAngle = (t * 260f) % 360f, sweepAngle = 70f, useCenter = false,
        topLeft = c - Offset(r, r), size = Size(r * 2, r * 2),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
    )
}

// ------------------------------------------------------------------------------------------------ captions

@Composable
private fun Captions(st: AgyVoiceCall.State, voice: AgyVoice, modifier: Modifier) {
    val list = rememberLazyListState()
    val last = st.lines.lastOrNull()
    LaunchedEffect(st.lines.size, last?.text?.length) {
        if (st.lines.isNotEmpty()) list.animateScrollToItem(st.lines.lastIndex)
    }
    if (st.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (st.phase == Phase.LIVE) "Say what you want done: the agent works in this chat while you talk." else "",
                color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // fade the oldest lines out at the top
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(st.lines, key = { it.key }) { line ->
            val user = line.role == AgyVoiceCall.Role.USER
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                Text(
                    if (user) "You" else voice.name,
                    color = if (user) Color.White.copy(alpha = 0.45f) else rgb(voice.glint).copy(alpha = 0.8f),
                    fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    line.text,
                    color = Color.White.copy(alpha = if (user) 0.72f else 0.95f),
                    fontSize = if (user) 14.sp else 15.5.sp,
                    lineHeight = if (user) 19.sp else 21.sp,
                    fontStyle = if (line.interim) FontStyle.Italic else FontStyle.Normal,
                    textAlign = if (user) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }
        }
    }
}

@Composable
private fun FailureCard(st: AgyVoiceCall.State, onRetry: () -> Unit, onUpdate: () -> Unit, onDetails: () -> Unit, onClose: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("The call couldn't continue", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(st.error ?: "Something went wrong.", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.problem == AgyVoiceCall.Problem.UPDATE_AGY) {
                    Surface(onClick = onUpdate, shape = RoundedCornerShape(50), color = Color.White) {
                        Text("Update agy", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                } else {
                    Surface(onClick = onRetry, shape = RoundedCornerShape(50), color = Color.White) {
                        Text("Try again", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
                // the full error, how far the call got and what the hub logged: to copy or screenshot
                Surface(onClick = onDetails, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f)) {
                    Text("Details", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
                Surface(onClick = onClose, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.12f)) {
                    Text("Close", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ voice & speed

@Composable
private fun VoicePanel(callVoice: String, callLanguage: String, inCall: Boolean, onBrowse: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // tapping a voice only marks it and plays its sample (to compare them); the call switches on "Switch to …"
    var marked by remember(callVoice) { mutableStateOf(callVoice) }
    var markedLanguage by remember(callLanguage) { mutableStateOf(callLanguage) }
    val speed by AgyVoicePreferences.speed.collectAsState()
    val samplePlaying by com.example.gemini.data.voice.AgyVoicePreview.playing.collectAsState()
    val sampleLoading by com.example.gemini.data.voice.AgyVoicePreview.loading.collectAsState()
    DisposableEffect(Unit) { onDispose { com.example.gemini.data.voice.AgyVoicePreview.stop() } }
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Color(0xFF14161C),
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f)))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Voice", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    "Browse voices", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBrowse).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                if (inCall) "Tap a voice to hear it, then switch the call to it." else "Tap a voice to hear it.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            AgyVoicePreferences.voices.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { v ->
                        val on = v.name == marked
                        val inUse = v.name == callVoice
                        Surface(
                            onClick = {
                                marked = v.name
                                com.example.gemini.data.voice.AgyVoicePreview.play(context, v.name)
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (on) rgb(v.core).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, if (on) rgb(v.core) else Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(18.dp).clip(CircleShape).background(Brush.radialGradient(listOf(rgb(v.glint), rgb(v.core), rgb(v.deep)))))
                                    Spacer(Modifier.width(6.dp))
                                    Text(v.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                    when {
                                        sampleLoading == v.name || samplePlaying == v.name ->
                                            Icon(Icons.Rounded.VolumeUp, "Playing", tint = rgb(v.glint), modifier = Modifier.size(15.dp))
                                        inUse -> Icon(Icons.Rounded.Check, "In use", tint = Color.White, modifier = Modifier.size(15.dp))
                                    }
                                }
                                Text(
                                    if (inUse) "In use now" else v.tone,
                                    color = Color.White.copy(alpha = if (inUse) 0.8f else 0.55f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text("Language", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("The language the voice talks in. Phone language uses your phone's language list.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            val languageTags = remember { listOf(AgyVoicePreferences.LANGUAGE_AUTO) + AgyVoicePreferences.languages.map { it.first } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(languageTags) { tag ->
                    val on = tag == markedLanguage
                    val inUse = tag == callLanguage
                    val english = AgyVoicePreferences.languages.firstOrNull { it.first == tag }?.third
                    Surface(
                        onClick = { markedLanguage = tag },
                        shape = RoundedCornerShape(50),
                        color = if (on) Color.White else Color.White.copy(alpha = 0.07f)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (inUse) {
                                Icon(Icons.Rounded.Check, "In use", tint = if (on) Ink else Color.White, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                AgyVoicePreferences.languageLabel(tag) + if (english != null && english != AgyVoicePreferences.languageLabel(tag)) " · $english" else "",
                                color = if (on) Ink else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Speed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgyVoicePreferences.speeds.forEach { sp ->
                    val on = sp == speed
                    Surface(
                        onClick = { AgyVoiceCall.setSpeed(sp) },
                        shape = RoundedCornerShape(50),
                        color = if (on) Color.White else Color.White.copy(alpha = 0.07f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            (if (sp % 1.0 == 0.0) sp.toInt().toString() else sp.toString()) + "×",
                            color = if (on) Ink else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val voiceChanged = marked != callVoice
            val languageChanged = markedLanguage != callLanguage
            val changed = voiceChanged || languageChanged
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onClose, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f), modifier = Modifier.weight(1f)) {
                    Text(if (changed) "Cancel" else "Done", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 11.dp))
                }
                if (changed) {
                    // only now the choice is saved and the call reconnects with it (once, for voice and language)
                    Surface(
                        onClick = {
                            AgyVoicePreferences.setVoice(marked)
                            AgyVoicePreferences.setLanguage(markedLanguage)
                            if (inCall) AgyVoiceCall.switchTo(marked, markedLanguage)
                            onClose()
                        },
                        shape = RoundedCornerShape(50), color = Color.White, modifier = Modifier.weight(1.4f)
                    ) {
                        val target = when {
                            voiceChanged && languageChanged -> null
                            voiceChanged -> marked
                            else -> AgyVoicePreferences.languageLabel(markedLanguage)
                        }
                        Text(
                            when {
                                target == null -> "Apply changes"
                                inCall -> "Switch to $target"
                                else -> "Use $target"
                            },
                            color = Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
                            modifier = Modifier.padding(vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ call log

/** What happened in the call: to tell whether the voice agent heard you and why it did (not) answer. */
@Composable
private fun CallLogPanel(log: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) list.scrollToItem(log.lastIndex) }
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Color(0xFF14161C),
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.systemBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Call log", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    "Copy", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Voice call log", "AntiGem voice call log\n" + log.joinToString("\n")))
                        com.example.gemini.ui.components.AppToastHelper.showToast("Call log copied", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                    }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                "“Heard you” followed by nothing means the voice agent got your words but chose not to answer (that is the voice service). " +
                    "Your words still in italics in the captions mean it still thinks you are talking: a quiet place, or a short mute, ends your turn.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.5.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(log) { line ->
                    Text(
                        line, color = Color.White.copy(alpha = 0.85f), fontSize = 11.5.sp, lineHeight = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(onClick = onClose, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
                Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 11.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ minimized

/** The call while minimized: a pill above the message box (tap to open the call). */
@Composable
fun AgyVoiceCallPill(agentWorking: Boolean, modifier: Modifier = Modifier) {
    val st by AgyVoiceCall.state.collectAsState()
    if (!st.active || !st.minimized) return
    val voice = AgyVoicePreferences.voiceOf(st.voice)
    val clock = rememberCallClock(st.connectedAt)
    val level by AgyVoiceCall.voiceLevel.collectAsState()
    val mic by AgyVoiceCall.micLevel.collectAsState()
    val pulse by animateFloatAsState(1f + 0.35f * max(level, if (st.muted) 0f else mic * 0.7f), tween(120), label = "pill")
    Surface(
        onClick = { AgyVoiceCall.setMinimized(false) },
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(rgb(voice.deep), Color(0xFF15171E))))
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(22.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(CircleShape)
                    .background(Brush.radialGradient(listOf(rgb(voice.glint), rgb(voice.core), rgb(voice.deep))))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Voice call" + (clock?.let { " · $it" } ?: ""),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                Text(statusLine(st, agentWorking), color = Color.White.copy(alpha = 0.65f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (st.phase != Phase.FAILED) {
                IconButton(onClick = { AgyVoiceCall.toggleMute() }) {
                    Icon(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Unmute" else "Mute", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                }
            }
            Surface(onClick = { AgyVoiceCall.end() }, shape = CircleShape, color = CallRed, modifier = Modifier.padding(end = 6.dp).size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}
