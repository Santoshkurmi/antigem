package ai.antigem.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L

/** One chip of [ProviderFilterRow]: [key] is what the picker saves, [hasCurrent] marks the chip holding the chat's model. */
data class ProviderChip(val key: String, val label: String, val count: Int, val hasCurrent: Boolean = false)

/**
 * The model pickers' provider filter: "All" and one chip per provider, scrolling sideways, so one provider's models
 * are a tap away however many providers are set up. A dot marks the chip that holds the chat's current model.
 */
@Composable
fun ProviderFilterRow(chips: List<ProviderChip>, selected: String, accent: Color, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState()
    // a remembered provider far to the right is brought into view when the sheet opens (one chip before it stays visible)
    LaunchedEffect(Unit) {
        val i = chips.indexOfFirst { it.key == selected }
        if (i > 1) listState.scrollToItem(i - 1)
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(chips, key = { it.key }) { chip ->
            val on = chip.key == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (on) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                // TalkBack: "<provider>, 3 models, Tab, Selected"
                modifier = Modifier.clip(RoundedCornerShape(50)).selectable(selected = on, role = Role.Tab) { onSelect(chip.key) }
            ) {
                val spoken = L.models.providerFilter.chipDescription(chip.count, chip.label) +
                    if (chip.hasCurrent) ", " + L.models.providerFilter.hasTheChatsModel else ""
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp).clearAndSetSemantics { contentDescription = spoken },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (chip.hasCurrent) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        chip.label,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(chip.count.toString(), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                }
            }
        }
    }
}
