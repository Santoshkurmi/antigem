package ai.antigem.ui.settings.chroot

import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A section card of the chroot pages. */
@Composable
internal fun ChrootSection(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = ClaudeTerracotta,
    cardBg: Color = AppColors.card,
    border: BorderStroke = BorderStroke(1.dp, AppColors.border.copy(alpha = 0.6f)),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = border, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = AppColors.onBackground)
                    if (subtitle != null) Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = AppColors.textSecondary)
                }
            }
            Spacer(Modifier.padding(top = 10.dp))
            content()
        }
    }
}

@Composable
internal fun ChrootRadioRow(
    title: String,
    description: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    tag: String? = null,
    tagColor: Color = AppColors.success,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) AppColors.onBackground else AppColors.textSecondary,
                    modifier = Modifier.weight(1f, fill = false))
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    ChrootTag(tag, tagColor)
                }
            }
            if (description != null) Text(description, fontSize = 11.5.sp, lineHeight = 15.sp, color = AppColors.textSecondary)
        }
    }
}

@Composable
internal fun ChrootTag(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
internal fun ChrootSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) AppColors.onBackground else AppColors.textSecondary)
            if (description != null) Text(description, fontSize = 11.5.sp, lineHeight = 15.sp, color = AppColors.textSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = ClaudeTerracotta))
    }
}

@Composable
internal fun ChrootKeyValue(label: String, value: String, valueColor: Color = AppColors.onBackground, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, color = AppColors.textSecondary, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ChrootNote(text: String, color: Color = AppColors.textSecondary) {
    Text(text, fontSize = 11.5.sp, lineHeight = 15.sp, color = color, modifier = Modifier.padding(top = 4.dp))
}

/** A scrolling log (monospace), following its end. */
@Composable
internal fun ChrootLog(lines: List<String>, maxHeight: Int = 260) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
    Surface(shape = RoundedCornerShape(10.dp), color = AppColors.codeBg, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(max = maxHeight.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(
                lines.takeLast(400).joinToString("\n").ifEmpty { "…" },
                fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = AppColors.codeText
            )
        }
    }
}

internal fun formatKb(kb: Long): String = when {
    kb >= 1024L * 1024L -> "%.2f GB".format(kb / 1024.0 / 1024.0)
    kb >= 1024L -> "%.1f MB".format(kb / 1024.0)
    else -> "$kb KB"
}

internal fun formatBytes(bytes: Long): String = formatKb(bytes / 1024)

internal val chrootColumnSpacing = Arrangement.spacedBy(14.dp)
