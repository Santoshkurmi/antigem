package com.example.gemini.ui.models

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.QuotaGroup
import com.example.gemini.domain.model.QuotaPillWindow
import com.example.gemini.domain.model.QuotaWindowUi
import com.example.gemini.domain.model.quotaPercentText
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.agents.SegmentedSelector

/** Green / amber / red by the share left. */
fun quotaLeftColor(remaining: Float): Color = when {
    remaining > 0.5f -> QuotaGreen
    remaining > 0.2f -> QuotaAmber
    else -> QuotaRed
}

/** "5h ▬▬▬▭ 85% · 2h 4m": a limit's share left as a small meter. */
@Composable
fun QuotaMeter(window: QuotaWindowUi, modifier: Modifier = Modifier) {
    val pctText = quotaPercentText(window.remaining, window.oneDecimal)
    val color = quotaLeftColor(window.remaining)
    val fill by animateFloatAsState(window.remaining.coerceIn(0f, 1f), label = "quota_meter")
    val time = formatCleanCountdown(window.resetIso, window.countdown)
    val muted = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = muted.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, muted.copy(alpha = 0.07f))
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(window.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = muted.copy(alpha = 0.8f))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(30.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(muted.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
            }
            Spacer(Modifier.width(6.dp))
            Text(pctText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
            if (time.isNotBlank()) {
                Text(" · $time", fontSize = 11.sp, color = muted.copy(alpha = 0.5f), maxLines = 1)
            }
        }
    }
}

/** Section header of a quota group: accent dot, name, model count, then its limits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuotaGroupHeader(
    title: String,
    accent: Color,
    count: Int,
    windows: List<QuotaWindowUi>,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(
                title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (count == 1) "1 model" else "$count models",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        if (windows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                windows.forEach { QuotaMeter(it) }
            }
        }
    }
}

/** "Shown in chat [5h | 7d]": which limit the model pill in the chat shows. */
@Composable
fun PillWindowRow(selected: QuotaPillWindow, accent: Color, modifier: Modifier = Modifier, onSelect: (QuotaPillWindow) -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Shown in chat", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Share left next to the model name. Models with no 5h limit show 7d.",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.width(12.dp))
        SegmentedSelector(
            options = listOf(QuotaPillWindow.FIVE_HOUR to "5h", QuotaPillWindow.WEEKLY to "7d"),
            selected = selected,
            accent = accent,
            onSelect = onSelect,
            modifier = Modifier.width(108.dp)
        )
    }
}

/** Header button that turns group editing on and off. */
@Composable
fun EditGroupsAction(editing: Boolean, accent: Color, onToggle: () -> Unit) {
    val bg by animateColorAsState(if (editing) accent.copy(alpha = 0.16f) else Color.Transparent, label = "edit_groups_bg")
    IconButton(onClick = onToggle, modifier = Modifier.size(38.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(
                if (editing) Icons.Filled.Check else Icons.Outlined.Edit,
                contentDescription = if (editing) "Done editing groups" else "Edit quota groups",
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Shown while editing groups, in place of the "Shown in chat" row. */
@Composable
fun EditGroupsBanner(accent: Color, modifier: Modifier = Modifier, onDone: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Edit quota groups", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Pick which limit each model counts against. Saved on this device.",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            TextButton(onClick = onDone) { Text("Done", color = accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "⇄ Gemini 4 Argon ▾": moves a model to another quota group, or back to the automatic one. */
@Composable
fun GroupMoveButton(
    current: QuotaGroup?,
    automatic: QuotaGroup?,
    isMoved: Boolean,
    groups: List<QuotaGroup>,
    accent: Color,
    onPick: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurface
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
        ) {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SwapHoriz, null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(current?.title ?: "No group", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
                if (isMoved) Text("  · moved", fontSize = 10.5.sp, color = muted.copy(alpha = 0.5f))
                Icon(Icons.Filled.ArrowDropDown, null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Automatic", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(automatic?.title ?: "No group", fontSize = 11.5.sp, color = muted.copy(alpha = 0.55f))
                    }
                },
                leadingIcon = { CheckSlot(!isMoved, accent) },
                onClick = { open = false; onPick(null) }
            )
            HorizontalDivider(color = muted.copy(alpha = 0.08f))
            groups.forEach { g ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(g.title, fontSize = 14.sp)
                            val limits = g.headerWindows.joinToString(" · ") { "${it.label} ${quotaPercentText(it.remaining, it.oneDecimal)}" }
                            if (limits.isNotBlank()) Text(limits, fontSize = 11.5.sp, color = muted.copy(alpha = 0.55f))
                        }
                    },
                    leadingIcon = { CheckSlot(isMoved && g.id == current?.id, accent) },
                    onClick = { open = false; onPick(g.id) }
                )
            }
        }
    }
}

@Composable
private fun CheckSlot(on: Boolean, accent: Color) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

/** "✓ Active" badge on the selected model. */
@Composable
fun ActiveModelBadge(accent: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("Active", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}
