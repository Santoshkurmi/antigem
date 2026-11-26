package ai.antigem.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.MarkdownBlock
import kotlinx.coroutines.delay
import ai.antigem.i18n.L

/**
 * A run of agent steps folded into one pill: "Working 12s · 5 tool calls · 2 running" while the agent works,
 * "Worked 40s · 5 tool calls · 2 thoughts" when done, with the latest step on a second line (open or closed, so the
 * pill keeps one height). A tap opens the steps below it.
 *
 * Kept cheap: no animation while working (a still dot, not a spinner), the counts are worked out only when the steps
 * change, and the clock is its own small text, so the once-a-second tick redraws only "Working 12s".
 */
@Composable
fun StepGroupPill(group: ChatFeedItem.StepGroup, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val summary = remember(group.steps) { summarize(group.steps) }
    val accent = ClaudeTerracotta
    val danger = AppColors.danger
    val worked = !group.live && group.startedAt > 0 && group.endedAt > group.startedAt
    val hasClock = group.live || worked
    // "5 tool calls · 2 running" (the clock before it is a separate text)
    val counts = remember(summary, hasClock, accent, danger) {
        buildAnnotatedString {
            val parts = mutableListOf<Pair<String, Color?>>()
            if (summary.tools > 0) parts += plural(summary.tools, L.chat.stepGroupPill.toolCall, L.chat.stepGroupPill.toolCalls) to null
            if (summary.thoughts > 0) parts += plural(summary.thoughts, "thought", "thoughts") to null
            if (summary.running > 0) parts += "${summary.running} running" to accent
            else if (summary.thinking) parts += "thinking" to accent
            if (summary.failed > 0) parts += "${summary.failed} failed" to danger
            parts.forEachIndexed { i, (text, color) ->
                if (i > 0 || hasClock) append(" · ")
                if (color != null) withStyle(SpanStyle(color = color)) { append(text) } else append(text)
            }
        }
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    // tabular digits: the clock does not change width (and push the counts) as the seconds go by
    val titleStyle = remember(onSurface) {
        TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onSurface, fontFeatureSettings = "tnum")
    }

    val rotation by animateFloatAsState(
        targetValue = if (group.expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "step_group_chevron"
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = onSurface.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, onSurface.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    onClickLabel = if (group.expanded) L.chat.stepGroupPill.hideSteps else L.chat.stepGroupPill.showSteps,
                    role = Role.Button,
                    onClick = onToggle
                )
                // TalkBack: "…, Collapsed, Button, double-tap to show steps" (the ticking clock is not in here on purpose)
                .semantics { stateDescription = if (group.expanded) L.components.a11y.expanded else L.components.a11y.collapsed }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                when {
                    group.live -> Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(accent, CircleShape)
                    )
                    summary.failed > 0 -> Icon(Icons.Default.Warning, contentDescription = null, tint = danger, modifier = Modifier.size(14.dp))
                    else -> Icon(Icons.Default.DoneAll, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        group.live && group.startedAt > 0 -> WorkingClock(group.startedAt, titleStyle)
                        group.live -> Text(L.chat.stepGroupPill.working, style = titleStyle, maxLines = 1)
                        worked -> Text(L.chat.stepGroupPill.workedElapsed(elapsed(group.endedAt - group.startedAt)), style = titleStyle, maxLines = 1)
                    }
                    Text(
                        text = counts,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                // the latest step, open or closed (an empty line when there is none): the pill keeps one height
                Text(
                    text = summary.latest,
                    fontSize = 11.sp,
                    color = muted.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = muted.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }
    }
}

/** "Working 12s": the only text that changes every second, so a tick redraws just this (once, right on the second). */
@Composable
private fun WorkingClock(startedAt: Long, style: TextStyle) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - (now - startedAt).mod(1_000L))
        }
    }
    Text(L.chat.stepGroupPill.workingElapsed(elapsed(now - startedAt)), style = style, maxLines = 1)
}

/** What the pill says about its steps. */
@Immutable
private data class StepSummary(
    val tools: Int,
    val thoughts: Int,
    val running: Int,
    val thinking: Boolean,
    val failed: Int,
    val latest: String
)

private fun summarize(steps: List<ChatFeedItem.AssistantBlock>): StepSummary {
    var tools = 0
    var thoughts = 0
    var running = 0
    var thinking = false
    var failed = 0
    for (step in steps) {
        when (val block = step.block) {
            is MarkdownBlock.AgentTool -> {
                tools++
                if (block.toolCall.status == "RUNNING") running++
                if (failed(block.toolCall)) failed++
            }
            is MarkdownBlock.AgentThought -> {
                thoughts++
                if (block.isStreaming) thinking = true
            }
            else -> {}
        }
    }
    return StepSummary(tools, thoughts, running, thinking, failed, steps.lastOrNull()?.let { stepLabel(it) }.orEmpty())
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/** "8s", "1m 05s", "1h 02m". */
private fun elapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> String.format(java.util.Locale.US, "%dm %02ds", s / 60, s % 60)
        else -> String.format(java.util.Locale.US, "%dh %02dm", s / 3600, (s % 3600) / 60)
    }
}

/** Same rule as the tool tile's red state. */
private fun failed(t: ToolCall): Boolean =
    t.status == "FAILED" || t.status == "TERMINATED" ||
        (t.exitCode != null && t.exitCode != 0 && t.status != "REJECTED" && t.status != "RUNNING")

/** One line about a step: "Running: npm test", "Read: src/App.kt", "Thinking…", "Thought: Checking the config". */
private fun stepLabel(step: ChatFeedItem.AssistantBlock): String? = when (val block = step.block) {
    is MarkdownBlock.AgentThought -> if (block.isStreaming) L.chat.stepGroupPill.thinking else firstLine(block.thought)?.let { L.chat.stepGroupPill.thoughtValue(it) }
    is MarkdownBlock.AgentTool -> {
        val t = block.toolCall
        val runningNow = t.status == "RUNNING"
        val verb = when {
            failed(t) -> L.common.failed
            t.status == "REJECTED" -> L.chat.stepGroupPill.rejected
            else -> when (t.toolType) {
                ToolType.VIEW_FILE -> if (runningNow) L.chat.stepGroupPill.reading else L.chat.stepGroupPill.read
                ToolType.EDIT_FILE -> if (runningNow) L.chat.stepGroupPill.editing else L.chat.stepGroupPill.edited
                ToolType.LIST_DIR -> if (runningNow) L.chat.stepGroupPill.listing else L.chat.stepGroupPill.listed
                ToolType.GREP_SEARCH, ToolType.FIND -> if (runningNow) L.chat.stepGroupPill.searching else L.chat.stepGroupPill.searched
                ToolType.SEARCH_WEB -> if (runningNow) L.chat.stepGroupPill.searchingWeb else L.chat.stepGroupPill.searchedWeb
                ToolType.READ_URL -> if (runningNow) L.chat.stepGroupPill.fetching else L.chat.stepGroupPill.fetched
                ToolType.MATH -> if (runningNow) L.chat.stepGroupPill.evaluating else L.chat.stepGroupPill.evaluated
                ToolType.MCP -> if (runningNow) L.chat.stepGroupPill.runningMcp else "MCP"
                else -> if (runningNow) L.common.running else L.chat.stepGroupPill.ran
            }
        }
        val target = firstLine(t.command)
        if (target != null) "$verb: $target" else verb
    }
    else -> null
}

private fun firstLine(text: String): String? =
    text.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', '>', ' ').trimEnd('*', ' ') }
        .firstOrNull { it.isNotBlank() }
        ?.take(200)
