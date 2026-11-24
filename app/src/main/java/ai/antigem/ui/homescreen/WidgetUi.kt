package ai.antigem.ui.homescreen

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ai.antigem.R
import ai.antigem.data.homescreen.HomeChat
import ai.antigem.data.homescreen.HomeIcons
import ai.antigem.data.homescreen.HomeLinks
import ai.antigem.data.homescreen.HomePrefs
import ai.antigem.theme.ThemeManager

/**
 * The widgets' colors: the AntiGem theme's light and dark side (following the phone, or the app's fixed light / dark
 * mode), or the wallpaper's colors (Material You, Android 12+). Re-read whenever [HomeWidgets.version] changes.
 */
@Composable
fun HomeWidgetTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    HomePrefs.init(ctx)
    val colorsPref by HomePrefs.colors.collectAsState()
    @Suppress("UNUSED_VARIABLE") val version by HomeWidgets.version.collectAsState()
    if (colorsPref == HomePrefs.COLORS_WALLPAPER && Build.VERSION.SDK_INT >= 31) {
        // GlanceTheme's own default: the wallpaper's dynamic colors
        content()
        return
    }
    ThemeManager.init(ctx)
    val light = ThemeManager.schemeOf(ThemeManager.themeFor(false))
    val dark = ThemeManager.schemeOf(ThemeManager.themeFor(true))
    val colors = when (HomeWidgets.themeMode(ctx)) {
        "DARK" -> ColorProviders(dark)
        "LIGHT" -> ColorProviders(light)
        else -> ColorProviders(light, dark)
    }
    GlanceTheme(colors = colors, content = content)
}

/** [base] with [alpha], for the phone's light and its dark mode alike. */
fun ColorProvider.withAlpha(context: Context, alpha: Float): ColorProvider {
    if (alpha >= 1f) return this
    val day = getColor(modeContext(context, night = false))
    val night = getColor(modeContext(context, night = true))
    return androidx.glance.color.ColorProvider(day.copy(alpha = day.alpha * alpha), night.copy(alpha = night.alpha * alpha))
}

private fun modeContext(context: Context, night: Boolean): Context {
    val cfg = Configuration(context.resources.configuration)
    cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    return context.createConfigurationContext(cfg)
}

/** The widget's own background: rounded (Android 12+), as see-through as Settings → Home screen says. */
@Composable
fun widgetRoot(modifier: GlanceModifier = GlanceModifier): GlanceModifier {
    val ctx = LocalContext.current
    val opacity by HomePrefs.opacity.collectAsState()
    val bg = GlanceTheme.colors.widgetBackground.withAlpha(ctx, opacity / 100f)
    return modifier.appWidgetBackground().cornerRadius(24.dp).background(bg)
}

/** Cards on the widget (a chat row, a button): a little less see-through than the widget itself. */
@Composable
fun cardColor(): ColorProvider {
    val ctx = LocalContext.current
    val opacity by HomePrefs.opacity.collectAsState()
    return GlanceTheme.colors.surfaceVariant.withAlpha(ctx, (opacity / 100f + 0.2f).coerceAtMost(1f))
}

fun fixed(color: Long): ColorProvider = ColorProvider(Color(color))
fun fixed(color: Int): ColorProvider = ColorProvider(Color(color))

fun open(context: Context, target: HomeLinks.Target): Action = actionStartActivity(HomeLinks.intent(context, target))

/** A colored circle with white content on it (an avatar, an action's icon). */
@Composable
fun Disc(size: Dp, color: ColorProvider, content: @Composable () -> Unit) {
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(R.drawable.ic_home_circle), null,
            modifier = GlanceModifier.size(size),
            colorFilter = ColorFilter.tint(color)
        )
        content()
    }
}

/** A chat's avatar: its agent's color with the chat's initials. */
@Composable
fun ChatAvatar(chat: HomeChat, size: Dp, hideTitle: Boolean) {
    Disc(size, fixed(HomeIcons.agentColor(chat.agentKind))) {
        if (hideTitle) {
            Image(
                ImageProvider(R.drawable.ic_home_chat), null,
                modifier = GlanceModifier.size(size * 0.5f),
                colorFilter = ColorFilter.tint(fixed(0xFFFFFFFF))
            )
        } else {
            Text(
                HomeIcons.initials(chat.title),
                style = TextStyle(
                    color = fixed(0xFFFFFFFF), fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp, textAlign = TextAlign.Center
                ),
                maxLines = 1
            )
        }
    }
}

/** A white glyph on a colored circle. */
@Composable
fun GlyphDisc(size: Dp, color: ColorProvider, glyph: Int, glyphColor: ColorProvider = fixed(0xFFFFFFFF), glyphScale: Float = 0.52f) {
    Disc(size, color) {
        Image(
            ImageProvider(glyph), null,
            modifier = GlanceModifier.size(size * glyphScale),
            colorFilter = ColorFilter.tint(glyphColor)
        )
    }
}

/** A small tinted glyph. */
@Composable
fun Glyph(res: Int, size: Dp, color: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    Image(ImageProvider(res), null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}

@Composable
fun Label(text: String, size: TextUnit, color: ColorProvider, bold: Boolean = false, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        maxLines = maxLines
    )
}

/** The chat's name, or "Claude Code chat" while Settings → Home screen hides names. */
fun shownTitle(chat: HomeChat, hide: Boolean): String = if (hide) "${HomeIcons.agentLabel(chat.agentKind)} chat" else chat.title

/** "now", "5m", "3h", "2d", or a date. */
fun ago(at: Long): String {
    if (at <= 0L) return ""
    val d = System.currentTimeMillis() - at
    return when {
        d < 60_000L -> "now"
        d < DateUtils.HOUR_IN_MILLIS -> "${d / 60_000L}m"
        d < DateUtils.DAY_IN_MILLIS -> "${d / DateUtils.HOUR_IN_MILLIS}h"
        d < 7 * DateUtils.DAY_IN_MILLIS -> "${d / DateUtils.DAY_IN_MILLIS}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}
