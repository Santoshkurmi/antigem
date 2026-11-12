package com.example.gemini.ui.usage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.usage.UsageAnalyzer
import com.example.gemini.domain.usage.UsageAnalyzer.CallStat
import com.example.gemini.domain.usage.UsageAnalyzer.CauseKind
import com.example.gemini.domain.usage.UsageAnalyzer.EventKind
import com.example.gemini.domain.usage.UsageAnalyzer.Report
import com.example.gemini.domain.usage.UsageLimitTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================================================ colors

/** The chart palette: the five token kinds in a fixed, colorblind-checked order; light and dark are separate steps. */
private class Viz(
    val input: Color, val cacheWrite: Color, val cacheRead: Color, val output: Color, val reasoning: Color,
    val text: Color, val muted: Color, val faint: Color, val grid: Color, val surface: Color, val card: Color,
    val good: Color, val warn: Color, val bad: Color, val dark: Boolean
)

@Composable
private fun rememberViz(): Viz {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val c = com.example.gemini.theme.AppColors
    return remember(dark, cs.onSurface, cs.surface, cs.background) {
        if (dark) Viz(
            Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181),
            cs.onSurface, cs.onSurface.copy(alpha = 0.62f), cs.onSurface.copy(alpha = 0.38f), cs.onSurface.copy(alpha = 0.08f),
            cs.background, cs.surface, c.success, c.warning, c.danger, true
        ) else Viz(
            Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4),
            cs.onSurface, cs.onSurface.copy(alpha = 0.62f), cs.onSurface.copy(alpha = 0.38f), cs.onSurface.copy(alpha = 0.08f),
            cs.background, cs.surface, c.success, c.warning, c.danger, false
        )
    }
}

private fun eventColor(v: Viz, e: EventKind): Color = when (e) {
    EventKind.COMPACTION -> v.good
    EventKind.CACHE_MISS, EventKind.CACHE_EXPIRED -> v.bad
    EventKind.BIG_ADDITION, EventKind.NEAR_FULL -> v.warn
}

private fun eventGlyph(e: EventKind) = when (e) {
    EventKind.COMPACTION -> "↓"
    EventKind.CACHE_MISS -> "✕"
    EventKind.CACHE_EXPIRED -> "⏱"
    EventKind.BIG_ADDITION -> "▲"
    EventKind.NEAR_FULL -> "!"
}

private fun eventLabel(e: EventKind) = when (e) {
    EventKind.COMPACTION -> "Compacted"
    EventKind.CACHE_MISS -> "Cache miss"
    EventKind.CACHE_EXPIRED -> "Cache expired"
    EventKind.BIG_ADDITION -> "Big addition"
    EventKind.NEAR_FULL -> "Context >80%"
}

// ============================================================================================ formatting

private fun k(n: Number) = UsageAnalyzer.compact(n.toDouble())
private fun grouped(n: Number) = String.format(Locale.US, "%,d", n.toLong())
private fun pct(x: Double, digits: Int = 0) = String.format(Locale.US, "%.${digits}f%%", x * 100)
private fun clock(ms: Long) = if (ms <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
private fun span(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
private fun ratio(x: Double) = if (x == x.roundToInt().toDouble()) "${x.roundToInt()}" else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.')

/** One chat's analysis, recomputed only when its requests or the limit readings change (not on every streamed word). */
@Composable
fun rememberUsageReport(agent: AgentKind, messages: List<ChatMessage>): Report {
    val limits by UsageLimitTracker.series.collectAsState()
    val key = remember(messages) { messages.map { Triple(it.id, it.tokenUsage, it.isStreaming) } }
    val latest by rememberUpdatedState(messages)
    // a minute tick keeps "runs out in" and the reset countdown fresh
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            tick++
        }
    }
    return remember(key, limits, agent, tick) { UsageAnalyzer.analyze(agent, latest, limits) }
}

// ============================================================================================ live overlay

/**
 * The floating live meter over the chat: context fill ring, the last requests as a stacked sparkline, the newest
 * request's weight and the 5-hour limit. Drag it anywhere; tap opens the inspector; long-press folds it to the ring.
 */
@Composable
fun UsageLiveOverlay(agent: AgentKind, messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val report = rememberUsageReport(agent, messages)
    var open by rememberSaveable { mutableStateOf(false) }
    var folded by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val v = rememberViz()
    val streaming = messages.lastOrNull()?.isStreaming == true

    AnimatedVisibility(
        visible = !report.isEmpty,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
    ) {
        // "+1.2k" floats up from the meter whenever a new request lands
        val flash = remember { Animatable(1f) }
        var flashText by remember { mutableStateOf("") }
        val count = report.calls.size
        val lastUnits = report.last?.units ?: 0.0
        var seenCount by remember { mutableIntStateOf(count) }
        LaunchedEffect(count) {
            if (count > seenCount) {
                flashText = "+${k(lastUnits)} u"
                flash.snapTo(0f)
                flash.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
            }
            seenCount = count
        }
        Box {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = v.card.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, v.text.copy(alpha = 0.10f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            drag += amount
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { open = true }, onLongPress = { folded = !folded })
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = if (folded) 5.dp else 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContextRing(report.fullness, v, live = streaming, size = 28.dp)
                    if (!folded) {
                        Column {
                            Text(k(report.contextNow), color = v.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
                            Text("context", color = v.muted, fontSize = 9.sp, lineHeight = 10.sp)
                        }
                        Sparkline(report.calls.takeLast(24), v, Modifier.size(width = 66.dp, height = 24.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(k(report.last?.units ?: 0.0) + " u", color = v.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
                            Text("last call", color = v.muted, fontSize = 9.sp, lineHeight = 10.sp)
                        }
                        report.fiveHour?.let { l ->
                            Box(Modifier.width(1.dp).height(22.dp).background(v.grid))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(pct(l.used), color = limitColor(v, l.used), fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp)
                                Text("5h used", color = v.muted, fontSize = 9.sp, lineHeight = 10.sp)
                            }
                        }
                    }
                }
            }
            if (flash.value < 1f && flashText.isNotEmpty()) {
                Text(
                    flashText,
                    color = v.output,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = (18 - 34 * flash.value).dp)
                        .alpha(1f - flash.value)
                )
            }
        }
    }
    if (open) UsageInspectorDialog(agent, messages, onDismiss = { open = false })
}

private fun limitColor(v: Viz, used: Double) = when {
    used >= 0.9 -> v.bad
    used >= 0.7 -> v.warn
    else -> v.text
}

@Composable
private fun ContextRing(fullness: Double, v: Viz, live: Boolean, size: Dp) {
    val color = when {
        fullness >= 0.8 -> v.bad
        fullness >= 0.5 -> v.warn
        else -> v.cacheRead
    }
    val pulse = if (live) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(size)) {
        val stroke = 3.5.dp.toPx()
        val inset = stroke / 2
        drawArc(v.grid, -90f, 360f, false, Offset(inset, inset), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
        drawArc(color, -90f, (360f * fullness.toFloat()).coerceIn(2f, 360f), false, Offset(inset, inset),
            Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        if (live) drawCircle(v.output.copy(alpha = pulse), radius = 3.dp.toPx())
        else {
            val txt = "${(fullness * 100).roundToInt()}"
            val layout = measurer.measure(txt, TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = v.text))
            drawText(layout, topLeft = Offset((this.size.width - layout.size.width) / 2, (this.size.height - layout.size.height) / 2))
        }
    }
}

@Composable
private fun Sparkline(calls: List<CallStat>, v: Viz, modifier: Modifier) {
    Canvas(modifier) {
        if (calls.isEmpty()) return@Canvas
        val maxU = calls.maxOf { it.units }.coerceAtLeast(1e-6)
        val step = size.width / max(calls.size, 12)
        val bw = (step - 1.5.dp.toPx()).coerceAtLeast(1.5f)
        calls.forEachIndexed { i, c ->
            val segs = segments(c, units = true)
            var y = size.height
            val x = size.width - (calls.size - i) * step
            for ((value, color) in segs.zip(segColors(v))) {
                val h = (value / maxU * size.height).toFloat()
                if (h <= 0f) continue
                drawRect(color, Offset(x, y - h), Size(bw, h))
                y -= h
            }
        }
    }
}

private fun segColors(v: Viz) = listOf(v.input, v.cacheWrite, v.cacheRead, v.output, v.reasoning)

/** A request's five stacked values in base→top order: input, cache write, cache read, answer output, reasoning. */
private fun segments(c: CallStat, units: Boolean): List<Double> = if (units) {
    listOf(c.unitsInput, c.unitsCacheWrite, c.unitsCacheRead, c.unitsOutput - c.unitsReasoning, c.unitsReasoning)
} else {
    listOf(c.call.input.toDouble(), c.call.cacheWrite.toDouble(), c.call.cacheRead.toDouble(), c.answerOutput.toDouble(), (c.call.reasoning ?: 0).coerceAtMost(c.call.output).toDouble())
}

private val SEG_NAMES = listOf("Fresh input", "Cache write", "Cache read", "Output", "Reasoning")

// ============================================================================================ inspector

@Composable
fun UsageInspectorDialog(agent: AgentKind, messages: List<ChatMessage>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val report = rememberUsageReport(agent, messages)
        UsageInspector(report, onDismiss)
    }
}

@Composable
private fun UsageInspector(report: Report, onDismiss: () -> Unit) {
    val v = rememberViz()
    var unitsMode by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val sel = selected?.let { report.calls.getOrNull(it) } ?: report.last
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Surface(color = v.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ---- top bar
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, null, tint = v.input, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Usage inspector", color = v.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val model = report.last?.call?.model?.substringAfterLast('/') ?: ""
                    Text(
                        "${agentName(report.agent)}${if (model.isNotBlank()) " · $model" else ""} · ${report.calls.size} requests · ${report.turns.size} turns",
                        color = v.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Segmented(listOf("Units", "Tokens"), if (unitsMode) 0 else 1, v) { unitsMode = it == 0 }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close", tint = v.text) }
            }
            if (report.isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No model requests yet", color = v.muted)
                }
                return@Column
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "kpis") { KpiRow(report, v) }
                report.fiveHour?.let { l -> item(key = "limit") { LimitCard(report, l, v) } }
                item(key = "timeline") {
                    Card(v, "Every request", if (unitsMode) "Weight per request, in units (1 u = one fresh Sonnet input token). Tap a bar."
                    else "Tokens per request: the stack below output is the context it sent. Tap a bar.") {
                        Legend(v, report.totalReasoning > 0)
                        Spacer(Modifier.height(8.dp))
                        TimelineChart(report, v, unitsMode, sel?.index) { selected = it }
                        EventLegend(report, v)
                    }
                }
                sel?.let { s -> item(key = "detail") { CallDetail(report, s, v) } }
                item(key = "context") {
                    Card(v, "Context size", "Tokens each request sent. Dashed: the window; red zone: past 80%.") {
                        ContextChart(report, v, sel?.index) { selected = it }
                    }
                }
                item(key = "cumulative") {
                    Card(v, "Running total", "Actual weight so far vs. a straight line at the early pace and vs. no caching. The gap to the straight line is the cost of re-reading a growing context.") {
                        CumulativeChart(report, v, sel?.index)
                    }
                }
                item(key = "mix") { MixCard(report, v) }
                if (report.groups.isNotEmpty()) item(key = "groups") { GroupsCard(report, v) }
                if (report.ledger.isNotEmpty()) item(key = "ledger") { LedgerCard(report, v) { selected = it } }
                item(key = "turns_h") { SectionTitle("Turns", "What each of your messages set off", v) }
                items(report.turns.asReversed(), key = { "turn_${it.index}" }) { t -> TurnRow(report, t, v, t.lastCall == sel?.index || sel?.turnIndex == t.index) { selected = t.lastCall } }
                if (report.tips.isNotEmpty()) item(key = "tips") { TipsCard(report, v) }
                item(key = "foot") { Footnote(report, v) }
            }
        }
    }
}

private fun agentName(a: AgentKind) = when (a) {
    AgentKind.CLAUDE -> "Claude"
    AgentKind.CODEX -> "Codex"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.AGY -> "Antigravity"
    else -> a.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, v: Viz, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(v.grid).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Text(
                o,
                color = if (on) v.text else v.muted,
                fontSize = 12.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) v.card else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun Card(v: Viz, title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = v.card, border = BorderStroke(1.dp, v.text.copy(alpha = 0.07f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = v.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = v.muted, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String, v: Viz) {
    Column(Modifier.padding(top = 4.dp, start = 2.dp)) {
        Text(title, color = v.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = v.muted, fontSize = 11.5.sp)
    }
}

// ---------------------------------------------------------------------------------------- KPIs

@Composable
private fun KpiRow(r: Report, v: Viz) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Surface(shape = RoundedCornerShape(16.dp), color = v.card, border = BorderStroke(1.dp, v.text.copy(alpha = 0.07f)), modifier = Modifier.weight(1.1f).fillMaxHeight()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ContextGauge(r, v)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Context now", color = v.muted, fontSize = 11.sp)
                    Text(k(r.contextNow), color = v.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("of ${k(r.window)} window", color = v.muted, fontSize = 11.sp)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("This chat", k(r.totalUnits) + " u", "no cache: ${k(r.totalUnitsNoCache)} (−${pct(r.savedShare)})", v)
            Kpi("Last request", k(r.last?.units ?: 0.0) + " u", "cache hit ${pct(r.last?.hitRate ?: 0.0)} · all ${pct(r.cacheHitRate)}", v)
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, sub: String, v: Viz) {
    Surface(shape = RoundedCornerShape(14.dp), color = v.card, border = BorderStroke(1.dp, v.text.copy(alpha = 0.07f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, color = v.muted, fontSize = 11.sp)
            Text(value, color = v.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = v.muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContextGauge(r: Report, v: Viz) {
    val f = r.fullness.toFloat().coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(f) { anim.animateTo(f, tween(700, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val color = when {
        f >= 0.8f -> v.bad
        f >= 0.5f -> v.warn
        else -> v.cacheRead
    }
    Canvas(Modifier.size(64.dp)) {
        val stroke = 7.dp.toPx()
        val tl = Offset(stroke / 2, stroke / 2)
        val sz = Size(size.width - stroke, size.height - stroke)
        drawArc(v.grid, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        // the 80% mark
        drawArc(v.bad.copy(alpha = 0.25f), 135f + 270f * 0.8f, 270f * 0.2f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
        drawArc(color, 135f, (270f * anim.value).coerceAtLeast(1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val layout = measurer.measure("${(f * 100).roundToInt()}%", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = v.text))
        drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
    }
}

// ---------------------------------------------------------------------------------------- limit

@Composable
private fun LimitCard(r: Report, l: UsageAnalyzer.LimitInfo, v: Viz) {
    val now = System.currentTimeMillis()
    Card(v, "5-hour limit", "${l.scope} · measured from the agent's own limit readings") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(pct(l.used), color = limitColor(v, l.used), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("used", color = v.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            l.resetAtMs?.let { Text("resets in ${span(it - now)}", color = v.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        // the bar: used so far, with this chat's turns marked in it
        val chatShare = r.turns.sumOf { it.limitDelta ?: 0.0 }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val rad = CornerRadius(6.dp.toPx())
            drawRoundRect(v.grid, cornerRadius = rad)
            val usedW = size.width * l.used.toFloat().coerceIn(0f, 1f)
            drawRoundRect(limitColor(v, l.used).copy(alpha = if (limitColor(v, l.used) == v.text) 0.35f else 0.9f), size = Size(usedW, size.height), cornerRadius = rad)
            val chatW = size.width * chatShare.toFloat().coerceIn(0f, l.used.toFloat())
            if (chatW > 1f) drawRoundRect(v.input, topLeft = Offset(usedW - chatW, 0f), size = Size(chatW, size.height), cornerRadius = rad)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (chatShare > 0) "This chat's measured turns: ${pct(chatShare, 1)} of the limit (blue)" else "No turn measured yet (a reading before and after a turn is needed)",
            color = v.muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat("Pace", l.burnPerHour?.let { pct(it, 1) + "/h" } ?: "—", v, Modifier.weight(1f))
            MiniStat("Runs out", l.emptyAtMs?.let { "in " + span(it - now) } ?: if (l.burnPerHour != null) "not before reset" else "—", v, Modifier.weight(1f))
            MiniStat("1% ≈", l.unitsPerPercent?.let { k(it) + " u" } ?: "learning…", v, Modifier.weight(1f))
        }
        if (l.samples.size >= 2) {
            Spacer(Modifier.height(10.dp))
            LimitHistory(l, v)
        }
        r.weekly?.let { w ->
            Spacer(Modifier.height(8.dp))
            Text("Weekly: ${pct(w.used)} used${w.resetAtMs?.let { " · resets in " + span(it - now) } ?: ""}", color = v.muted, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, v: Viz, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(v.grid.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(label, color = v.muted, fontSize = 10.5.sp)
        Text(value, color = v.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The limit's readings over the last hours, as a step line. */
@Composable
private fun LimitHistory(l: UsageAnalyzer.LimitInfo, v: Viz) {
    val measurer = rememberTextMeasurer()
    val samples = l.samples.takeLast(400)
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val t0 = samples.first().atMs
        val t1 = max(samples.last().atMs, System.currentTimeMillis())
        val span = (t1 - t0).coerceAtLeast(1L).toFloat()
        val lo = 0.0
        val hi = max(samples.maxOf { it.used }, 0.05)
        fun x(t: Long) = (t - t0) / span * size.width
        fun y(u: Double) = size.height - ((u - lo) / (hi - lo)).toFloat() * (size.height - 12.dp.toPx())
        drawLine(v.grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val path = Path()
        samples.forEachIndexed { i, s ->
            if (i == 0) path.moveTo(x(s.atMs), y(s.used)) else {
                path.lineTo(x(s.atMs), y(samples[i - 1].used))
                path.lineTo(x(s.atMs), y(s.used))
            }
        }
        path.lineTo(size.width, y(samples.last().used))
        drawPath(path, v.input, style = Stroke(2.dp.toPx()))
        val layout = measurer.measure(pct(hi), TextStyle(fontSize = 9.sp, color = v.muted))
        drawText(layout, topLeft = Offset(0f, 0f))
        val l2 = measurer.measure(clock(t0).substring(0, 5), TextStyle(fontSize = 9.sp, color = v.muted))
        drawText(l2, topLeft = Offset(0f, size.height - l2.size.height - 2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------------------- legends

@Composable
private fun Legend(v: Viz, reasoning: Boolean) {
    FlowRowSimple {
        segColors(v).zip(SEG_NAMES).forEachIndexed { i, (c, n) ->
            if (i == 4 && !reasoning) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Spacer(Modifier.width(5.dp))
                Text(n, color = v.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EventLegend(r: Report, v: Viz) {
    val present = EventKind.values().filter { e -> r.calls.any { e in it.events } }
    if (present.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    FlowRowSimple {
        present.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp)) {
                Text(eventGlyph(e), color = eventColor(v, e), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("${eventLabel(e)} ×${r.calls.count { e in it.events }}", color = v.muted, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    FlowRow { content() }
}

// ---------------------------------------------------------------------------------------- charts

private fun DrawScope.topRounded(color: Color, left: Float, top: Float, width: Float, height: Float, radius: Float) {
    val r = min(radius, min(width / 2, height))
    val path = Path().apply {
        addRoundRect(RoundRect(left, top, left + width, top + height, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero))
    }
    drawPath(path, color)
}

/** Stacked bar per request, scrolling sideways once the bars would get thin; turn boundaries and events marked. */
@Composable
private fun TimelineChart(r: Report, v: Viz, units: Boolean, selected: Int?, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val calls = r.calls
    val scroll = rememberScrollState()
    val chartH = 190.dp
    val axisW = 38.dp
    val maxV = calls.maxOf { segments(it, units).sum() }.coerceAtLeast(1e-6)
    val niceMax = niceCeil(maxV)
    val streamingLast = r.turns.lastOrNull()?.streaming == true
    // the newest bar breathes while its reply still runs
    val live = if (streamingLast) rememberInfiniteTransition(label = "live").animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value else 1f

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avail = maxWidth - axisW
        val step = (avail / calls.size).coerceIn(12.dp, 34.dp)
        val contentW = max(avail.value, (step * calls.size).value + 8f).dp
        // follow the newest request while nothing older is picked
        LaunchedEffect(calls.size, selected) {
            val target = with(density) { ((selected ?: calls.lastIndex) * step.toPx()).toInt() - (avail.toPx() * 0.6f).toInt() }
            scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
        Row {
            // fixed y axis
            Canvas(Modifier.width(axisW).height(chartH)) {
                val top = 18.dp.toPx()
                val bottom = size.height - 16.dp.toPx()
                for (i in 0..3) {
                    val value = niceMax * i / 3
                    val y = bottom - (bottom - top) * i / 3
                    val layout = measurer.measure(k(value), TextStyle(fontSize = 9.sp, color = v.faint))
                    drawText(layout, topLeft = Offset(size.width - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2))
                }
            }
            Box(Modifier.horizontalScroll(scroll)) {
                Canvas(
                    Modifier
                        .width(contentW)
                        .height(chartH)
                        .pointerInput(calls.size, step) {
                            detectTapGestures { p ->
                                val i = (p.x / step.toPx()).toInt()
                                if (i in calls.indices) onSelect(i)
                            }
                        }
                ) {
                    val top = 18.dp.toPx()
                    val bottom = size.height - 16.dp.toPx()
                    val plotH = bottom - top
                    val stepPx = step.toPx()
                    val gap = 2.dp.toPx()
                    val bw = (stepPx * 0.68f).coerceAtLeast(4.dp.toPx())
                    // grid
                    for (i in 0..3) {
                        val y = bottom - plotH * i / 3
                        drawLine(v.grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    var lastTurn = -1
                    calls.forEachIndexed { i, c ->
                        val x0 = i * stepPx
                        val x = x0 + (stepPx - bw) / 2
                        // turn boundary + label
                        if (c.turnIndex != lastTurn) {
                            if (i > 0) drawLine(v.text.copy(alpha = 0.16f), Offset(x0, top - 4.dp.toPx()), Offset(x0, bottom), 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                            val lay = measurer.measure("T${c.turnIndex + 1}", TextStyle(fontSize = 8.sp, color = v.faint))
                            drawText(lay, topLeft = Offset(x0 + 2.dp.toPx(), bottom + 3.dp.toPx()))
                            lastTurn = c.turnIndex
                        }
                        if (i == selected) drawRoundRect(v.text.copy(alpha = 0.07f), Offset(x0 + 1, top - 14.dp.toPx()), Size(stepPx - 2, plotH + 14.dp.toPx()), CornerRadius(6.dp.toPx()))
                        val dim = if (selected != null && i != selected) 0.55f else 1f
                        val pulse = if (streamingLast && i == calls.lastIndex) live else 1f
                        val segs = segments(c, units)
                        val colors = segColors(v)
                        val topSeg = segs.indexOfLast { it > 0 }
                        var y = bottom
                        segs.forEachIndexed { si, value ->
                            if (value <= 0) return@forEachIndexed
                            val h = (value / niceMax * plotH).toFloat()
                            val drawH = if (si == topSeg) h else (h - gap).coerceAtLeast(min(h, 1f))
                            val col = colors[si].copy(alpha = dim * pulse)
                            if (si == topSeg) topRounded(col, x, y - h, bw, h, 4.dp.toPx())
                            else drawRect(col, Offset(x, y - drawH), Size(bw, drawH))
                            y -= h
                        }
                        if (c.aggregated) drawLine(v.muted, Offset(x, y - 3.dp.toPx()), Offset(x + bw, y - 3.dp.toPx()), 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
                        // event markers above the bar
                        var my = y - 5.dp.toPx()
                        for (e in c.events.sortedBy { it.ordinal }) {
                            val lay = measurer.measure(eventGlyph(e), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = eventColor(v, e)))
                            my -= lay.size.height
                            drawText(lay, topLeft = Offset(x0 + (stepPx - lay.size.width) / 2, max(0f, my)))
                        }
                    }
                }
            }
        }
    }
}

private fun niceCeil(x: Double): Double {
    if (x <= 0) return 1.0
    val exp = kotlin.math.floor(kotlin.math.log10(x)).let { e -> java.lang.Math.pow(10.0, e) }
    val f = x / exp
    val nice = when {
        f <= 1.5 -> 1.5
        f <= 3 -> 3.0
        f <= 6 -> 6.0
        else -> 10.0
    }
    return nice * exp
}

/** Context tokens per request as an area, the window as a dashed line, past-80% shaded. */
@Composable
private fun ContextChart(r: Report, v: Viz, selected: Int?, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val calls = r.calls
    Canvas(
        Modifier.fillMaxWidth().height(130.dp).pointerInput(calls.size) {
            detectTapGestures { p ->
                if (calls.size <= 1) return@detectTapGestures
                val i = (p.x / size.width * (calls.size - 1)).roundToInt()
                if (i in calls.indices) onSelect(i)
            }
        }
    ) {
        val top = 10.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        val hi = max(r.window.toDouble(), calls.maxOf { it.context }.toDouble()) * 1.04
        fun y(t: Double) = bottom - (t / hi).toFloat() * (bottom - top)
        fun x(i: Int) = if (calls.size == 1) size.width / 2 else i * size.width / (calls.size - 1)
        // danger zone above 80%
        drawRect(v.bad.copy(alpha = 0.06f), Offset(0f, y(hi)), Size(size.width, y(r.window * 0.8) - y(hi)))
        val wy = y(r.window.toDouble())
        drawLine(v.muted, Offset(0f, wy), Offset(size.width, wy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
        val wl = measurer.measure("window ${k(r.window)}", TextStyle(fontSize = 9.sp, color = v.muted))
        drawText(wl, topLeft = Offset(size.width - wl.size.width, wy - wl.size.height - 1.dp.toPx()))
        val line = Path()
        val area = Path()
        calls.forEachIndexed { i, c ->
            val px = x(i)
            val py = y(c.context.toDouble())
            if (i == 0) {
                line.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py)
            } else {
                line.lineTo(px, py); area.lineTo(px, py)
            }
        }
        area.lineTo(x(calls.lastIndex), bottom)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(v.cacheRead.copy(alpha = 0.35f), v.cacheRead.copy(alpha = 0.02f)), startY = top, endY = bottom))
        drawPath(line, v.cacheRead, style = Stroke(2.dp.toPx()))
        calls.forEachIndexed { i, c ->
            if (c.events.isEmpty()) return@forEachIndexed
            val e = c.events.minByOrNull { it.ordinal }!!
            drawCircle(v.surface, 5.dp.toPx(), Offset(x(i), y(c.context.toDouble())))
            drawCircle(eventColor(v, e), 3.5.dp.toPx(), Offset(x(i), y(c.context.toDouble())))
        }
        selected?.let { i ->
            if (i in calls.indices) {
                drawLine(v.text.copy(alpha = 0.35f), Offset(x(i), top), Offset(x(i), bottom), 1.dp.toPx())
                drawCircle(v.surface, 6.dp.toPx(), Offset(x(i), y(calls[i].context.toDouble())))
                drawCircle(v.cacheRead, 4.dp.toPx(), Offset(x(i), y(calls[i].context.toDouble())))
            }
        }
    }
}

/** Cumulative units: actual, a straight line at the first requests' pace, and the same chat without caching. */
@Composable
private fun CumulativeChart(r: Report, v: Viz, selected: Int?) {
    val measurer = rememberTextMeasurer()
    val calls = r.calls
    val early = calls.take(max(1, min(5, calls.size / 4))).let { it.sumOf { c -> c.units } / it.size }
    var noCache = 0.0
    val noCacheCum = calls.map { noCache += it.unitsNoCache; noCache }
    Column {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val top = 8.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val hi = max(noCacheCum.lastOrNull() ?: 1.0, max(r.totalUnits, early * calls.size)) * 1.05
            fun y(u: Double) = bottom - (u / hi).toFloat() * (bottom - top)
            fun x(i: Int) = if (calls.size == 1) size.width else (i + 1) * size.width / calls.size
            for (i in 0..3) drawLine(v.grid, Offset(0f, bottom - (bottom - top) * i / 3), Offset(size.width, bottom - (bottom - top) * i / 3), 1.dp.toPx())
            fun series(values: List<Double>, color: Color, effect: PathEffect?, width: Float) {
                val p = Path()
                p.moveTo(0f, bottom)
                values.forEachIndexed { i, u -> p.lineTo(x(i), y(u)) }
                drawPath(p, color, style = Stroke(width, pathEffect = effect, cap = StrokeCap.Round))
            }
            series(noCacheCum, v.muted, PathEffect.dashPathEffect(floatArrayOf(8f, 6f)), 1.5.dp.toPx())
            series(calls.indices.map { early * (it + 1) }, v.faint, PathEffect.dashPathEffect(floatArrayOf(2f, 5f)), 1.5.dp.toPx())
            // the actual line over a soft fill
            val fill = Path().apply {
                moveTo(0f, bottom)
                calls.forEachIndexed { i, c -> lineTo(x(i), y(c.cumulativeUnits)) }
                lineTo(x(calls.lastIndex), bottom); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(v.input.copy(alpha = 0.22f), Color.Transparent), startY = top, endY = bottom))
            series(calls.map { it.cumulativeUnits }, v.input, null, 2.dp.toPx())
            val lay = measurer.measure(k(r.totalUnits) + " u", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = v.text))
            drawText(lay, topLeft = Offset((size.width - lay.size.width).coerceAtLeast(0f), (y(r.totalUnits) - lay.size.height - 3.dp.toPx()).coerceAtLeast(0f)))
            selected?.let { i ->
                if (i in calls.indices) {
                    drawLine(v.text.copy(alpha = 0.3f), Offset(x(i), top), Offset(x(i), bottom), 1.dp.toPx())
                    drawCircle(v.surface, 5.5.dp.toPx(), Offset(x(i), y(calls[i].cumulativeUnits)))
                    drawCircle(v.input, 3.5.dp.toPx(), Offset(x(i), y(calls[i].cumulativeUnits)))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRowSimple {
            LineKey("Actual", v.input, null, v)
            LineKey("Early pace", v.faint, "dot", v)
            LineKey("No caching", v.muted, "dash", v)
        }
    }
}

@Composable
private fun LineKey(label: String, color: Color, style: String?, v: Viz) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                pathEffect = when (style) {
                    "dot" -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f))
                    "dash" -> PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    else -> null
                })
        }
        Spacer(Modifier.width(5.dp))
        Text(label, color = v.muted, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------------------- selected request

@Composable
private fun CallDetail(r: Report, c: CallStat, v: Viz) {
    val p = c.prices
    val w = p.modelWeight
    Card(v, "Request #${c.index + 1}${if (c.index == r.calls.lastIndex) " · latest" else ""}",
        "Turn ${c.turnIndex + 1} · ${clock(c.call.atMs)}${c.gapMs?.let { " · ${span(it)} after the previous" } ?: ""}${c.call.model?.let { " · " + it.substringAfterLast('/') } ?: ""}") {
        if (c.events.isNotEmpty()) {
            FlowRowSimple {
                c.events.forEach { e ->
                    Row(
                        Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(eventColor(v, e).copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(eventGlyph(e), color = eventColor(v, e), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Text(eventLabel(e), color = v.text, fontSize = 11.sp)
                    }
                }
            }
        }
        if (c.aggregated) Text("This reply has no per-request record (history the agent keeps only totals for): one bar is the whole reply.", color = v.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        // the arithmetic
        val wTxt = if (w != 1.0) " × ${ratio(w)} (${p.family})" else ""
        val write5m = (c.call.cacheWrite - c.call.cacheWrite1h).coerceAtLeast(0)
        MathRow(v.input, "Fresh input", "${grouped(c.call.input)} × 1$wTxt", c.unitsInput, v)
        if (c.call.cacheWrite > 0) {
            if (c.call.cacheWrite1h > 0) {
                MathRow(v.cacheWrite, "Cache write 1h", "${grouped(c.call.cacheWrite1h)} × ${ratio(p.cacheWrite1h)}$wTxt", c.call.cacheWrite1h * p.cacheWrite1h * w, v)
                if (write5m > 0) MathRow(v.cacheWrite, "Cache write 5m", "${grouped(write5m)} × ${ratio(p.cacheWrite5m)}$wTxt", write5m * p.cacheWrite5m * w, v)
            } else MathRow(v.cacheWrite, "Cache write", "${grouped(c.call.cacheWrite)} × ${ratio(p.cacheWrite5m)}$wTxt", c.unitsCacheWrite, v)
        }
        MathRow(v.cacheRead, "Cache read", "${grouped(c.call.cacheRead)} × ${ratio(p.cacheRead)}$wTxt", c.unitsCacheRead, v)
        MathRow(v.output, "Output", "${grouped(c.call.output)} × ${ratio(p.output)}$wTxt", c.unitsOutput, v)
        c.call.reasoning?.takeIf { it > 0 }?.let { Text("   of which reasoning: ${grouped(it)} tokens (${k(c.unitsReasoning)} u)", color = v.muted, fontSize = 11.sp) }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(v.grid))
        Row {
            Text("Total", color = v.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(k(c.units) + " u", color = v.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("Without cache it would be ${k(c.unitsNoCache)} u · cache hit ${pct(c.hitRate)} · context ${grouped(c.context)} tokens", color = v.muted, fontSize = 11.sp)

        // why the context grew
        if (c.causes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val title = when {
                c.index == 0 || r.calls.getOrNull(c.index - 1)?.aggregated == true -> "What the context holds"
                EventKind.COMPACTION in c.events -> "Context compacted: ${k(c.delta)} tokens"
                c.delta >= 0 -> "Why the context grew: +${grouped(c.delta)} tokens"
                else -> "Context changed: ${grouped(c.delta)} tokens"
            }
            Text(title, color = v.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val maxT = c.causes.maxOf { kotlin.math.abs(it.tokens) }.coerceAtLeast(1)
            c.causes.sortedByDescending { kotlin.math.abs(it.tokens) }.forEach { cause ->
                CauseBar(cause, maxT, v, perCall = cause.tokens * p.cacheRead * w)
            }
            Text("Estimated by text size where the agent does not count per item; the reply's own output is measured.", color = v.faint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MathRow(color: Color, label: String, formula: String, units: Double, v: Viz) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = v.text, fontSize = 12.sp, modifier = Modifier.width(98.dp))
        Text(formula, color = v.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("= " + k(units), color = v.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

private fun causeColor(v: Viz, kind: CauseKind) = when (kind) {
    CauseKind.TOOL -> v.input
    CauseKind.PROMPT -> v.cacheWrite
    CauseKind.REPLY -> v.output
    CauseKind.START -> v.cacheRead
    CauseKind.COMPACTED -> v.good
    CauseKind.OTHER -> v.muted
}

@Composable
private fun CauseBar(cause: UsageAnalyzer.Cause, maxT: Int, v: Viz, perCall: Double) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cause.label, color = v.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text((if (cause.tokens > 0) "+" else "") + k(cause.tokens), color = v.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(v.grid)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((kotlin.math.abs(cause.tokens).toFloat() / maxT).coerceIn(0.01f, 1f)).clip(RoundedCornerShape(3.dp)).background(causeColor(v, cause.kind)))
            }
            if (cause.tokens > 0 && cause.kind != CauseKind.COMPACTED) {
                Spacer(Modifier.width(8.dp))
                Text("${k(perCall)} u / later call", color = v.muted, fontSize = 10.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- composition

@Composable
private fun MixCard(r: Report, v: Viz) {
    val parts = listOf(
        r.calls.sumOf { it.unitsInput }, r.calls.sumOf { it.unitsCacheWrite }, r.calls.sumOf { it.unitsCacheRead },
        r.calls.sumOf { it.unitsOutput - it.unitsReasoning }, r.calls.sumOf { it.unitsReasoning }
    )
    val tokens = listOf(r.totalInput, r.totalCacheWrite, r.totalCacheRead, r.totalOutput - r.totalReasoning, r.totalReasoning)
    val total = parts.sum().coerceAtLeast(1e-6)
    Card(v, "Where the weight went", "Share of this chat's units by token kind. Tokens alone look very different: cache reads are huge in count but light.") {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            var x = 0f
            val gap = 2.dp.toPx()
            val shown = parts.withIndex().filter { it.value > 0 }
            shown.forEachIndexed { n, (i, p) ->
                val wdt = (p / total * size.width).toFloat()
                val rad = CornerRadius(4.dp.toPx())
                val drawW = if (n == shown.lastIndex) wdt else (wdt - gap).coerceAtLeast(1f)
                drawRoundRect(segColors(v)[i], Offset(x, 0f), Size(drawW, size.height), rad)
                x += wdt
            }
        }
        Spacer(Modifier.height(10.dp))
        parts.forEachIndexed { i, p ->
            if (tokens[i] <= 0 && p <= 0) return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(segColors(v)[i]))
                Spacer(Modifier.width(7.dp))
                Text(SEG_NAMES[i], color = v.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${k(tokens[i])} tok", color = v.muted, fontSize = 11.5.sp, modifier = Modifier.width(76.dp))
                Text(k(p) + " u", color = v.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                Text(pct(p / total), color = v.muted, fontSize = 11.5.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

@Composable
private fun GroupsCard(r: Report, v: Viz) {
    val maxT = r.groups.maxOf { it.tokens }.coerceAtLeast(1)
    Card(v, "What fills the context", "Tokens each source added, and what re-reading them has cost on later requests") {
        r.groups.take(10).forEach { g ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, color = v.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.count}×", color = v.muted, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(k(g.tokens) + " tok", color = v.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(v.grid)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((g.tokens.toFloat() / maxT).coerceIn(0.01f, 1f)).clip(RoundedCornerShape(4.dp)).background(causeColor(v, g.kind)))
                }
                Text("re-read cost so far: ${k(g.rereadUnits)} u", color = v.muted, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun LedgerCard(r: Report, v: Viz, onSelect: (Int) -> Unit) {
    Card(v, "Biggest additions", "Each one is carried (and re-read) by every later request until the context is compacted") {
        r.ledger.take(8).forEachIndexed { i, e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(e.callIndex) }.padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(causeColor(v, e.cause.kind).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = v.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.cause.label, color = v.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("+${k(e.cause.tokens)} at request #${e.callIndex + 1} · re-read ${e.rereadCalls}× · ${k(e.perCallUnits)} u each", color = v.muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(k(e.rereadUnits) + " u", color = v.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    Text("so far", color = v.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- turns, tips

@Composable
private fun TurnRow(r: Report, t: UsageAnalyzer.TurnStat, v: Viz, highlighted: Boolean, onClick: () -> Unit) {
    val maxU = r.turns.maxOf { it.units }.coerceAtLeast(1e-6)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = v.card,
        border = BorderStroke(1.dp, if (highlighted) v.input.copy(alpha = 0.6f) else v.text.copy(alpha = 0.07f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("T${t.index + 1}", color = v.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(t.prompt, color = v.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t.streaming) {
                    Spacer(Modifier.width(6.dp))
                    Text("live", color = v.output, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            // the turn's requests as a mini stacked strip
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val total = t.units.coerceAtLeast(1e-6)
                val barW = (t.units / maxU * size.width).toFloat()
                var x = 0f
                for (i in t.firstCall..t.lastCall) {
                    val c = r.calls[i]
                    val segs = segments(c, true)
                    segs.forEachIndexed { si, s ->
                        val wdt = (s / total * barW).toFloat()
                        if (wdt > 0) drawRect(segColors(v)[si], Offset(x, 0f), Size(wdt, size.height))
                        x += wdt
                    }
                    if (i < t.lastCall) drawLine(v.card, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.callCount} request${if (t.callCount > 1) "s" else ""} · ${k(t.tokens)} tok · ${span((t.endMs - t.startMs).coerceAtLeast(0))}", color = v.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(k(t.units) + " u", color = v.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                t.limitDelta?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("5h +${pct(it, 1)}", color = v.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(v.input.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun TipsCard(r: Report, v: Viz) {
    Card(v, "What is driving it") {
        r.tips.forEach { tip ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Outlined.Lightbulb, null, tint = v.output, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(tip, color = v.text, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun Footnote(r: Report, v: Viz) {
    Text(
        "Units are a sense of weight, not a price: 1 u = one fresh input token of a Sonnet-class model. Cache read 0.1×, " +
            "cache write 1.25× (1-hour: 2×), output 5× for Claude (8× GPT, 6× Gemini); Haiku ⅓×, Opus/Fable 5⁄3×. Plan limits " +
            "are the agent's own readings; \"1% ≈\" is learned from this chat's turns. Context growth per tool is estimated by text size.",
        color = v.faint, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 4.dp)
    )
}
