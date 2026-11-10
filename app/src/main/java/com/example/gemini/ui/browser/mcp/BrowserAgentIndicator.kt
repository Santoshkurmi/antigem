package com.example.gemini.ui.browser.mcp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the browser MCP is doing on a tab, drawn natively over the WebView by [BrowserAgentOverlay]: a glowing frame
 * while an agent is active, an animated cursor that travels to each click, a ripple on every press and a short label.
 * Nothing is injected into the page, so it never shows in screenshots and never intercepts touches.
 */
object BrowserAgentIndicator {
    /** Seconds the indicator stays after the last agent action. */
    private const val LINGER_MS = 6_000L

    class TabState {
        var x by mutableStateOf(0.5f)
        var y by mutableStateOf(0.5f)
        var hasPointer by mutableStateOf(false)
        var pressed by mutableStateOf(false)
        var clicks by mutableIntStateOf(0)
        var label by mutableStateOf<String?>(null)
        var activeUntil by mutableLongStateOf(0L)
    }

    private val states = mutableStateMapOf<String, TabState>()
    private val main = Handler(Looper.getMainLooper())

    fun state(tabId: String): TabState? = states[tabId]

    private fun update(tabId: String, block: TabState.() -> Unit) = main.post {
        val s = states.getOrPut(tabId) { TabState() }
        s.block()
        s.activeUntil = SystemClock.uptimeMillis() + LINGER_MS
    }

    /** Moves the cursor to ([fx], [fy]), fractions of the visible page. */
    fun moveTo(tabId: String, fx: Double, fy: Double, label: String?) = update(tabId) {
        x = fx.toFloat().coerceIn(0f, 1f); y = fy.toFloat().coerceIn(0f, 1f); hasPointer = true
        label?.let { this.label = it }
    }

    fun press(tabId: String, down: Boolean) = update(tabId) { pressed = down }

    fun click(tabId: String) = update(tabId) { clicks++; pressed = false }

    /** Agent activity without moving the pointer (typing, keys, reading): frame and caption. */
    fun activity(tabId: String, label: String?) = update(tabId) { label?.let { this.label = it } }

    fun clear(tabId: String) = main.post { states.remove(tabId) }
}

@Composable
fun BrowserAgentOverlay(tabId: String, modifier: Modifier = Modifier, topInset: androidx.compose.ui.unit.Dp = 10.dp) {
    val s = BrowserAgentIndicator.state(tabId) ?: return
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(s.activeUntil) {
        val left = s.activeUntil - SystemClock.uptimeMillis()
        active = left > 0
        if (left > 0) {
            delay(left)
            active = false
        }
    }
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 180 else 600), label = "agentShown")
    if (shown <= 0.01f) return

    val accent = ClaudeTerracotta
    val pulse by rememberInfiniteTransition(label = "agentPulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "agentPulseValue"
    )
    val cx by animateFloatAsState(s.x, tween(260, easing = FastOutSlowInEasing), label = "agentX")
    val cy by animateFloatAsState(s.y, tween(260, easing = FastOutSlowInEasing), label = "agentY")
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(s.clicks) {
        if (s.clicks > 0) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
    }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().alpha(shown)) {
        Canvas(Modifier.fillMaxSize()) {
            // Glowing frame: a few strokes fading inwards
            val glow = listOf(2.dp to 0.95f, 6.dp to 0.35f, 12.dp to 0.14f)
            for ((width, a) in glow) {
                val w = width.toPx()
                drawRoundRect(
                    color = accent.copy(alpha = a * pulse),
                    topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = w)
                )
            }
            if (s.hasPointer) {
                val px = cx * size.width
                val py = cy * size.height
                // Click ripple
                if (ripple.value < 1f) {
                    val r = 6.dp.toPx() + 26.dp.toPx() * ripple.value
                    drawCircle(accent.copy(alpha = 0.45f * (1f - ripple.value)), radius = r, center = Offset(px, py))
                    drawCircle(accent.copy(alpha = 0.9f * (1f - ripple.value)), radius = r, center = Offset(px, py), style = Stroke(2.dp.toPx()))
                }
                if (s.pressed) drawCircle(accent.copy(alpha = 0.35f), radius = 10.dp.toPx(), center = Offset(px, py))
                // Arrow cursor with its tip on the point
                val u = with(density) { 1.dp.toPx() } * if (s.pressed) 0.9f else 1f
                val arrow = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(0f, 17f * u)
                    lineTo(4.4f * u, 13.2f * u)
                    lineTo(7.4f * u, 19.6f * u)
                    lineTo(10.2f * u, 18.4f * u)
                    lineTo(7.3f * u, 12.1f * u)
                    lineTo(12.6f * u, 12.1f * u)
                    close()
                }
                translate(px + 1.2f * u, py + 1.8f * u) { drawPath(arrow, Color.Black.copy(alpha = 0.28f)) }
                translate(px, py) {
                    drawPath(arrow, Color.White)
                    drawPath(arrow, Color(0xFF1F1F1F), style = Stroke(width = 1.4f * u, join = StrokeJoin.Round))
                }
            }
        }
        val label = s.label?.takeIf { it.isNotBlank() }
        if (s.hasPointer && label != null) {
            // Caption riding next to the cursor: below-right of the tip, flipped left/up near the edges
            var labelW by remember { mutableIntStateOf(0) }
            var labelH by remember { mutableIntStateOf(0) }
            val areaW = constraints.maxWidth
            val areaH = constraints.maxHeight
            val gapX = with(density) { 18.dp.roundToPx() }
            val gapY = with(density) { 22.dp.roundToPx() }
            val edge = with(density) { 4.dp.roundToPx() }
            // Stay below whatever sits at the top (e.g. the lock pill)
            val topLimit = with(density) { (topInset - 6.dp).roundToPx() }.coerceAtLeast(edge)
            Box(
                Modifier
                    .offset {
                        val px = (cx * areaW).roundToInt()
                        val py = (cy * areaH).roundToInt()
                        var x = px + gapX
                        var y = py + gapY
                        if (x + labelW > areaW - edge) x = px - labelW - edge
                        if (y + labelH > areaH - edge) y = py - labelH - edge
                        if (y < topLimit) y = max(topLimit, py + gapY)
                        IntOffset(x.coerceIn(edge, max(edge, areaW - labelW - edge)), y.coerceIn(topLimit, max(topLimit, areaH - labelH - edge)))
                    }
                    .onSizeChanged { labelW = it.width; labelH = it.height }
                    .widthIn(max = maxWidth * 0.7f)
                    .background(accent.copy(alpha = 0.95f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
                    .widthIn(max = maxWidth * 0.86f)
                    .background(Color(0xE6202020), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (label == null) "AI is using this tab" else "AI · $label",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
