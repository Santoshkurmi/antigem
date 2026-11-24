package ai.antigem.ui.settings

import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.ui.components.PageOverviewPillIcon
import ai.antigem.ui.components.floatingSwitcherHandleIcon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MIN_PILL_SCALE = 0.6f
private const val MAX_PILL_SCALE = 1.4f

/** The page overview's mode as state: "SWITCHER" (the switcher pill opens it), "OWN_PILL" (a second pill) or "OFF". */
@Composable
fun rememberPageOverviewMode(): String {
    val context = LocalContext.current
    val prefs = remember(context) { AuthPreferences(context) }
    val mode by prefs.pageOverviewMode.collectAsState(initial = prefs.getPageOverviewModeSync())
    return mode
}

/**
 * Top of the Floating App Switcher card: the page overview (on/off, which pill opens it, its layout, its own pill's
 * size) and the switcher pill's size. Only what applies to the chosen setup is shown.
 */
@Composable
fun SwitcherOverviewOptions(handleIcon: String) {
    val context = LocalContext.current
    val prefs = remember(context) { AuthPreferences(context) }
    val scope = rememberCoroutineScope()
    val mode = rememberPageOverviewMode()
    val layout by prefs.pageOverviewLayout.collectAsState(initial = prefs.getPageOverviewLayoutSync())
    val switcherScale by prefs.floatingSwitcherScale.collectAsState(initial = prefs.getFloatingSwitcherScaleSync())
    val overviewPillScale by prefs.overviewPillScale.collectAsState(initial = prefs.getOverviewPillScaleSync())
    val overviewOn = mode == "SWITCHER" || mode == "OWN_PILL"
    val saveMode: (String) -> Unit = { m -> scope.launch { prefs.savePageOverviewMode(m) } }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingSwitchRow(
            title = "Page Overview",
            description = "Every page as a card, with how it looked when you left it and what it is doing now. " +
                "Tap a card to open the page; hold and drag it to move it.",
            checked = overviewOn,
            onCheckedChange = { on -> saveMode(if (on) "SWITCHER" else "OFF") }
        )
        if (overviewOn) {
            Spacer(modifier = Modifier.height(12.dp))
            ChoiceRow(
                title = "Open It With",
                options = listOf(
                    Triple(
                        "SWITCHER", "The switcher pill",
                        "A tap on the pill opens the overview, a double-tap goes back. The pill has no icon panel."
                    ),
                    Triple(
                        "OWN_PILL", "Its own pill",
                        "A second pill opens the overview, and the switcher pill keeps its icon panel."
                    )
                ),
                selected = mode,
                onSelect = saveMode
            )
            Spacer(modifier = Modifier.height(12.dp))
            ChoiceRow(
                title = "Overview Layout",
                options = listOf(
                    Triple("LARGE", "Large cards", "Two big cards a row, scrolls"),
                    Triple("FIT", "Fit screen", "Every page at once, no scrolling"),
                    Triple("LIST", "List", "Names and status, no pictures")
                ),
                selected = layout,
                onSelect = { l -> scope.launch { prefs.savePageOverviewLayout(l) } }
            )
            if (mode == "OWN_PILL") {
                Spacer(modifier = Modifier.height(12.dp))
                PillSizeSlider(
                    title = "Overview Pill Size",
                    description = "The overview's own pill and its icon",
                    icon = PageOverviewPillIcon,
                    value = overviewPillScale,
                    onValueChange = { s -> scope.launch { prefs.saveOverviewPillScale(s) } }
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(14.dp))
        PillSizeSlider(
            title = "Switcher Pill Size",
            description = if (mode == "SWITCHER") "The pill and its icon" else "The pill and its open icon panel",
            icon = floatingSwitcherHandleIcon(handleIcon),
            value = switcherScale,
            onValueChange = { s -> scope.launch { prefs.saveFloatingSwitcherScale(s) } }
        )
    }
}

/** A title, the chosen option's explanation and one button per option (the first option is the default). */
@Composable
private fun ChoiceRow(
    title: String,
    options: List<Triple<String, String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val current = options.firstOrNull { it.first == selected } ?: options.first()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = current.third,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label, _) ->
                val isSelected = id == current.first
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (!isSelected) onSelect(id) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ClaudeTerracotta
            )
        )
    }
}

/**
 * Size slider (60%–140% in 5% steps) with a preview of the collapsed pill at that size (the pill itself is hidden
 * behind Settings, so the preview shows the size while dragging).
 */
@Composable
private fun PillSizeSlider(
    title: String,
    description: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var dragValue by remember(value) { mutableFloatStateOf(value.coerceIn(MIN_PILL_SCALE, MAX_PILL_SCALE)) }
    // saved once the slider rests, not on every step of the drag
    LaunchedEffect(dragValue) {
        if (dragValue != value) {
            delay(300)
            onValueChange(dragValue)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(dragValue * 100).roundToInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ClaudeTerracotta
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The collapsed pill at this size: 44dp with a 20dp icon at 100%, like the real one
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(24.dp * dragValue),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp * dragValue)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp * dragValue)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = dragValue,
                onValueChange = { dragValue = (it * 20).roundToInt() / 20f },
                valueRange = MIN_PILL_SCALE..MAX_PILL_SCALE,
                steps = 15,
                colors = SliderDefaults.colors(
                    thumbColor = ClaudeTerracotta,
                    activeTrackColor = ClaudeTerracotta
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
