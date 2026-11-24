package ai.antigem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.theme.AppTheme
import ai.antigem.theme.BuiltInThemes
import ai.antigem.theme.ThemeManager
import ai.antigem.i18n.L

/**
 * Settings → Themes: pick the light theme and the dark theme from previews of every built-in and custom theme.
 * Light / Dark / Auto (the mode cards above) decides which of the two shows.
 */
@Composable
fun ThemeGallery(cardBg: Color, themeMode: String, onSetThemeMode: (String) -> Unit) {
    val lightId = ThemeManager.lightId
    val darkId = ThemeManager.darkId
    val custom = ThemeManager.customThemes
    val all = remember(custom) { ThemeManager.all() }
    var toDelete by remember { mutableStateOf<AppTheme?>(null) }

    fun pick(t: AppTheme) {
        // a fixed mode switches to the picked theme's side so it shows at once; Auto keeps following the system
        ThemeManager.select(t, switchMode = false)
        val wantMode = if (t.dark) "DARK" else "LIGHT"
        if (themeMode != "SYSTEM" && themeMode != wantMode) onSetThemeMode(wantMode)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryHeader(L.settings.themeGallery.lightTheme, L.settings.themeGallery.usedInLightModeAndBy)
        ThemeGrid(all.filter { !it.dark }, lightId, cardBg, ::pick) { toDelete = it }
        GalleryHeader(L.settings.themeGallery.darkTheme, L.settings.themeGallery.usedInDarkModeAndBy)
        ThemeGrid(all.filter { it.dark }, darkId, cardBg, ::pick) { toDelete = it }
        Text(
            L.settings.themeGallery.makeYourOwnAddTheTheme,
            fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete “${t.name}”?") },
            text = { Text(L.settings.themeGallery.thisCustomThemeIsRemovedIf) },
            confirmButton = { TextButton(onClick = { ThemeManager.deleteCustom(t.id); toDelete = null }) { Text(L.common.delete) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun GalleryHeader(title: String, hint: String) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun ThemeGrid(themes: List<AppTheme>, selectedId: String, cardBg: Color, onPick: (AppTheme) -> Unit, onDelete: (AppTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    ThemeCard(t, t.id == selectedId, cardBg, Modifier.weight(1f), { onPick(t) }, if (t.builtIn) null else ({ onDelete(t) }))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A small live preview: the theme's background, a card with text lines and a button, your bubble, and status dots.
 * Also used by the floating [ThemeQuickPanel].
 */
@Composable
internal fun ThemeCard(t: AppTheme, selected: Boolean, cardBg: Color, modifier: Modifier, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val c = remember(t) { ThemeManager.resolvedColors(t).mapValues { Color(it.value.toInt()) } }
    fun col(k: String, d: Color = Color.Gray) = c[k] ?: d
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(cardBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
            .clickable { onClick() }
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp).background(col("background")).padding(8.dp)) {
            // a card with two text lines and a button
            Column(
                Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(8.dp)).background(col("card", col("surface"))).padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(50)).background(col("on_background")))
                Box(Modifier.fillMaxWidth(0.55f).height(4.dp).clip(RoundedCornerShape(50)).background(col("text_secondary", col("on_surface_variant"))))
                Box(Modifier.width(30.dp).height(10.dp).clip(RoundedCornerShape(50)).background(col("primary")))
            }
            // your message bubble
            Box(
                Modifier.align(Alignment.TopEnd).width(34.dp).height(14.dp)
                    .clip(RoundedCornerShape(7.dp)).background(col("user_bubble", col("surface_variant")))
            )
            // a code strip and the status colors
            Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(col("code_bg")).padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("syntax_keyword", "syntax_string", "syntax_function").forEach { k ->
                        Box(Modifier.width(9.dp).height(3.dp).clip(RoundedCornerShape(50)).background(col(k)))
                    }
                }
                Spacer(Modifier.width(6.dp))
                listOf("success", "warning", "danger", "info").forEach { k ->
                    Box(Modifier.padding(end = 3.dp).size(7.dp).clip(CircleShape).background(col(k)))
                }
            }
            // the default theme of its side: a small star in the preview's corner (no text badge, so every card
            // keeps the same height)
            if (BuiltInThemes.recommendedRank(t) == 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(18.dp)
                        .clip(CircleShape).background(accent)
                        .border(1.5.dp, col("background"), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Star, L.settings.themeGallery.defaultTheme, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
            // a custom theme: a small brush in the same corner (a custom theme is never the default, so the two never
            // meet), instead of a "Custom" text line that made its card taller than the others
            if (!t.builtIn) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(18.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.tertiary)
                        .border(1.5.dp, col("background"), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Brush, L.settings.themeGallery.customTheme, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(11.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (onDelete != null) {
                Icon(Icons.Outlined.Delete, L.common.delete, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onDelete() })
                Spacer(Modifier.width(4.dp))
            }
            if (selected) Icon(Icons.Filled.CheckCircle, L.common.inUse, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}
