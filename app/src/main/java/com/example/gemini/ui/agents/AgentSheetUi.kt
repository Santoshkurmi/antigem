package com.example.gemini.ui.agents

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.label

/** Sheet header shared by both agents' pickers: agent mark, title, "Agent · detail" line, refresh. */
@Composable
fun AgentSheetHeader(
    agent: AgentKind,
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AgentMark(agent, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                listOfNotNull(agent.label(), detail).joinToString(" · "),
                fontSize = 12.sp,
                color = agent.accent(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRefresh != null) {
            val angle = if (refreshing) {
                rememberInfiniteTransition(label = "sheet_refresh")
                    .animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "sheet_refresh_angle").value
            } else 0f
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Refresh, "Refresh", tint = agent.accent(), modifier = Modifier.size(20.dp).rotate(angle))
            }
        }
    }
}

@Composable
fun SheetSectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        trailing?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)) }
    }
}

/** Equal-width segmented control. */
@Composable
fun <T> SegmentedSelector(
    options: List<Pair<T, String>>,
    selected: T,
    accent: Color,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, text) ->
            val isOn = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(9.dp),
                color = if (isOn) accent else Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text,
                        fontSize = 12.sp,
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isOn) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
