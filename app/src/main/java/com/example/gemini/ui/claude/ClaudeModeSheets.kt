package com.example.gemini.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.components.ClaudeAccent

/** Color of a permission mode (chip, picker icon). */
@Composable
fun permissionModeColor(mode: String): Color = when (mode) {
    "plan" -> Color(0xFF0EA5E9)
    "acceptEdits" -> Color(0xFF16A34A)
    "auto" -> Color(0xFF8B5CF6)
    "bypassPermissions" -> Color(0xFFDC2626)
    "dontAsk" -> Color(0xFFD97706)
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
}

fun permissionModeIcon(mode: String): ImageVector = when (mode) {
    "plan" -> Icons.Outlined.Map
    "acceptEdits" -> Icons.Outlined.EditNote
    "auto" -> Icons.Outlined.AutoMode
    "bypassPermissions" -> Icons.Outlined.WarningAmber
    "dontAsk" -> Icons.Outlined.DoNotDisturbOn
    else -> Icons.Outlined.PanTool
}

/** Bottom sheet shell shared by the Claude chat pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudePickerSheet(title: String, detail: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()
        ) {
            AgentSheetHeader(AgentKind.CLAUDE, title, detail)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** One choice in a picker sheet: tinted icon (or custom leading), title, description, selected state. */
@Composable
private fun PickerOption(
    title: String,
    description: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) color.copy(alpha = 0.08f) else claudeCardColor(),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) color.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                if (leading != null) leading() else if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                Text(description, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** How Claude asks before acting in this chat. */
@Composable
fun ClaudePermissionModeSheet(
    current: String,
    modes: List<Pair<String, String>>,
    note: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ClaudePickerSheet("Permission mode", "how Claude asks before acting", onDismiss) {
        modes.forEach { (value, label) ->
            PickerOption(
                title = label,
                description = permissionModeDescription(value),
                color = permissionModeColor(value).takeIf { value != "default" } ?: ClaudeAccent,
                selected = value == current,
                icon = permissionModeIcon(value),
                onClick = {
                    onSelect(value)
                    onDismiss()
                }
            )
        }
        note?.let {
            Row(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}

private fun effortDescription(level: String?): String = when (level) {
    null -> "The model's own default"
    "low" -> "Quick answers with light reasoning"
    "medium" -> "Balanced speed and depth"
    "high" -> "Thorough work on hard tasks"
    "xhigh" -> "Extra depth for complex changes"
    "max" -> "Most thorough; uses plan limits fastest"
    else -> ""
}

/** Signal-style meter: [filled] of [total] bars. */
@Composable
private fun EffortBars(filled: Int, total: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..total) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + i * 13 / total).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= filled) color else color.copy(alpha = 0.22f))
            )
        }
    }
}

/** How much work Claude puts into each reply. */
@Composable
fun ClaudeEffortSheet(levels: List<String>, current: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    ClaudePickerSheet("Effort", "how much work Claude puts into each reply", onDismiss) {
        (listOf<String?>(null) + levels).forEachIndexed { index, level ->
            PickerOption(
                title = effortLabel(level),
                description = effortDescription(level),
                color = ClaudeAccent,
                selected = level == current,
                leading = {
                    if (level == null) Icon(Icons.Outlined.AutoMode, null, tint = ClaudeAccent, modifier = Modifier.size(18.dp))
                    else EffortBars(index, levels.size, ClaudeAccent)
                },
                onClick = {
                    onSelect(level)
                    onDismiss()
                }
            )
        }
    }
}
