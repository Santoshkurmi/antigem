package com.example.gemini.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.ui.components.bounceWhenNotScrollable

/**
 * One row of a settings list: an icon in a soft colored square, the title, an optional short value on the right
 * (with a status dot when [valueColor] is set) and a chevron. No description: the title says what it is.
 */
class SettingsRowItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val value: String? = null,
    val valueColor: Color? = null,
    val onClick: () -> Unit
)

/** A page of settings rows: rounded groups (no titles) like a phone's settings app; empty groups are skipped. */
@Composable
fun SettingsRowList(
    groups: List<List<SettingsRowItem>>,
    cardBg: Color,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()
) {
    // iOS-style bounce, also when the page is short and cannot scroll
    val bounce = com.example.gemini.ui.components.rememberBouncyOverscrollEffect()
    val canScroll = scrollState.canScrollForward || scrollState.canScrollBackward
    Column(
        modifier.fillMaxSize()
            .bounceWhenNotScrollable(!canScroll, bounce)
            .verticalScroll(scrollState, overscrollEffect = bounce, enabled = canScroll)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        groups.filter { it.isNotEmpty() }.forEach { SettingsRowGroup(it, cardBg) }
    }
}

/** Rows in one rounded card, split by thin dividers that start after the icon. */
@Composable
fun SettingsRowGroup(items: List<SettingsRowItem>, cardBg: Color) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBg)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), shape)
    ) {
        items.forEachIndexed { i, item ->
            SettingsRow(item)
            if (i < items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp),
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(item: SettingsRowItem) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(item.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, null, tint = item.tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (!item.value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            if (item.valueColor != null) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(item.valueColor))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                item.value, fontSize = 12.5.sp, color = onSurface.copy(alpha = 0.55f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = onSurface.copy(alpha = 0.35f), modifier = Modifier.padding(start = 2.dp).size(20.dp)
        )
    }
}
