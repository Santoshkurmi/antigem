package com.example.gemini.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ThemeManager

/**
 * A floating theme picker (opened from the sidebar). It covers only the bottom of the screen and takes no touches
 * outside itself, so the sidebar and the chat stay visible (and usable) behind it: a tap on a theme applies it at once
 * and the whole app shows it right away. It lists the themes of the side on screen (light or dark); the mode row
 * switches sides. Back or ✕ closes it.
 */
@Composable
fun ThemeQuickPanel(themeMode: String, onSetThemeMode: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val dark = ThemeManager.isDark
    val selectedId = if (dark) ThemeManager.darkId else ThemeManager.lightId
    val custom = ThemeManager.customThemes
    val themes = remember(custom, dark) { ThemeManager.all().filter { it.dark == dark } }
    val listState = rememberLazyListState()
    // show the theme in use (with one neighbour before it) when the panel opens or the side changes
    LaunchedEffect(dark) {
        val i = themes.indexOfFirst { it.id == selectedId }
        if (i > 0) listState.scrollToItem(i - 1)
    }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, onSurface.copy(alpha = 0.10f)),
        shadowElevation = 18.dp
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Themes", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurface)
                Spacer(Modifier.weight(1f))
                ModeToggle(themeMode, onSetThemeMode)
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, "Close", tint = onSurface.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "Tap a theme to try it: the app changes behind this panel.",
                fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
            )
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(themes, key = { it.id }) { t ->
                    ThemeCard(
                        t, t.id == selectedId, MaterialTheme.colorScheme.surface, Modifier.width(138.dp),
                        onClick = { ThemeManager.select(t, switchMode = false) }, onDelete = null
                    )
                }
            }
        }
    }
}

/** Light / Dark / Auto as three small pills (the same setting as Settings → Appearance). */
@Composable
private fun ModeToggle(themeMode: String, onSetThemeMode: (String) -> Unit) {
    val options = listOf(
        Triple("LIGHT", "Light", Icons.Outlined.LightMode),
        Triple("DARK", "Dark", Icons.Outlined.DarkMode),
        Triple("SYSTEM", "Auto", Icons.Outlined.BrightnessAuto)
    )
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (mode, label, icon) -> ModePill(label, icon, themeMode == mode) { onSetThemeMode(mode) } }
    }
}

@Composable
private fun ModePill(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = fg)
    }
}
