package com.example.gemini.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.BuildConfig
import com.example.gemini.data.changelog.ChangeKind
import com.example.gemini.data.changelog.Changelog
import com.example.gemini.data.changelog.ChangelogVersion
import com.example.gemini.theme.AppColors

// Timeline geometry: the rail runs down the left gutter, each version's dot sits level with its card's title.
private val RailWidth = 34.dp
private val RailX = 13.dp
private val DotY = 23.dp
private val LineWidth = 2.dp

private fun ChangeKind.color(): Color = when (this) {
    ChangeKind.NEW -> AppColors.success
    ChangeKind.FIXED -> AppColors.warning
    ChangeKind.IMPROVED -> AppColors.info
    ChangeKind.REMOVED -> AppColors.danger
}

private fun ChangeKind.icon(): ImageVector = when (this) {
    ChangeKind.NEW -> Icons.Outlined.AutoAwesome
    ChangeKind.FIXED -> Icons.Outlined.Build
    ChangeKind.IMPROVED -> Icons.Outlined.Bolt
    ChangeKind.REMOVED -> Icons.Outlined.RemoveCircleOutline
}

/** Settings → About & Info → What's new: every version, newest first, on a timeline. */
@Composable
fun ChangelogScreen(cardBg: Color, cardBorder: BorderStroke) {
    // Built once from the generated list; the page itself only lays out what is on screen (LazyColumn)
    val versions = remember { Changelog.versions }
    val totalChanges = remember { versions.sumOf { it.lines.size } }
    val firstDate = remember { Changelog.firstDateLabel }
    val since = remember { Changelog.firstMonthLabel }
    // newest three start open; the rest open on tap
    val expanded = remember { mutableStateMapOf<Int, Boolean>().apply { versions.take(3).forEach { put(it.versionCode, true) } } }
    val railColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp)
    ) {
        item(key = "hero") {
            HeroCard(versions.size, totalChanges, since, versions.firstOrNull())
            Spacer(Modifier.height(20.dp))
        }
        versions.forEachIndexed { index, version ->
            val month = version.monthLabel
            if (month != null && month != versions.getOrNull(index - 1)?.monthLabel) {
                item(key = "month-$month-$index") {
                    MonthMarker(month, isFirst = index == 0, railColor = railColor)
                }
            }
            item(key = version.versionCode) {
                VersionNode(
                    version = version,
                    expanded = expanded[version.versionCode] == true,
                    onToggle = { expanded[version.versionCode] = expanded[version.versionCode] != true },
                    isFirst = index == 0 && month == null,
                    railColor = railColor,
                    cardBg = cardBg,
                    cardBorder = cardBorder
                )
            }
        }
        item(key = "origin") { OriginMarker(firstDate, railColor) }
    }
}

@Composable
private fun HeroCard(releases: Int, changes: Int, since: String?, latest: ChangelogVersion?) {
    val primary = MaterialTheme.colorScheme.primary
    val upToDate = latest == null || latest.versionCode <= BuildConfig.VERSION_CODE
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.22f), AppColors.accentAlt.copy(alpha = 0.14f), AppColors.accentTeal.copy(alpha = 0.10f))
                )
            )
            .border(1.dp, primary.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(primary, AppColors.accentAlt))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.NewReleases, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("What's new", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (upToDate) "You're on v${BuildConfig.VERSION_NAME}, the latest" else "You're on v${BuildConfig.VERSION_NAME}",
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("$releases", if (releases == 1) "release" else "releases", Modifier.weight(1f))
                HeroStat("$changes", if (changes == 1) "change" else "changes", Modifier.weight(1f))
                HeroStat(since ?: "—", "first release", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Draws the timeline rail in the gutter: from [from] to [to] (the row's top/bottom when null). */
private fun Modifier.rail(color: Color, from: Dp? = null, to: Dp? = null) = drawBehind {
    val x = RailX.toPx()
    val start = from?.toPx() ?: 0f
    val end = to?.toPx() ?: size.height
    if (end > start) drawLine(color, Offset(x, start), Offset(x, end), LineWidth.toPx(), StrokeCap.Round)
}

@Composable
private fun MonthMarker(label: String, isFirst: Boolean, railColor: Color) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).rail(railColor, from = if (isFirst) 20.dp else null),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(RailWidth)) {
            Box(
                Modifier.padding(start = RailX - 4.dp).size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
            )
        }
        Text(
            label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun VersionNode(
    version: ChangelogVersion,
    expanded: Boolean,
    onToggle: () -> Unit,
    isFirst: Boolean,
    railColor: Color,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    Row(Modifier.fillMaxWidth().rail(railColor, from = if (isFirst) DotY else null)) {
        Box(Modifier.width(RailWidth)) {
            TimelineDot(version, Modifier.padding(start = RailX - 8.dp, top = DotY - 8.dp))
        }
        VersionCard(version, expanded, onToggle, cardBg, cardBorder, Modifier.weight(1f).padding(bottom = 14.dp))
    }
}

/** A ring for each version; filled for the installed one; the latest also glows. */
@Composable
private fun TimelineDot(version: ChangelogVersion, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (version.isLatest) {
            val pulse = rememberInfiniteTransition(label = "latestPulse")
            val scale by pulse.animateFloat(1f, 2.1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "scale")
            val alpha by pulse.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "alpha")
            Box(
                Modifier.size(16.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(CircleShape).background(primary)
            )
        }
        when {
            version.isLatest || version.isInstalled -> Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(primary, AppColors.accentAlt)))
                    .border(3.dp, surface.copy(alpha = 0.9f), CircleShape)
            )
            else -> Box(
                Modifier.size(13.dp).clip(CircleShape).background(surface)
                    .border(2.dp, primary.copy(alpha = 0.6f), CircleShape)
            )
        }
    }
}

@Composable
private fun VersionCard(
    version: ChangelogVersion,
    expanded: Boolean,
    onToggle: () -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke,
    modifier: Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val border = if (version.isLatest) BorderStroke(1.2.dp, Brush.linearGradient(listOf(primary.copy(alpha = 0.7f), AppColors.accentAlt.copy(alpha = 0.5f))))
    else cardBorder
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")

    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = cardBg, border = border) {
        Column(Modifier.clickable(onClick = onToggle).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${version.versionName}", fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                if (version.isLatest) VersionBadge("Latest", primary)
                if (version.isInstalled) {
                    if (version.isLatest) Spacer(Modifier.width(6.dp))
                    VersionBadge("Installed", AppColors.success)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp).rotate(chevron)
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CalendarToday, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        version.dateLabel == null -> "Not released yet"
                        version.relativeLabel == null -> version.dateLabel
                        else -> "${version.dateLabel}  ·  ${version.relativeLabel}"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(9.dp))
            KindSummary(version)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(160))
            ) {
                Column(Modifier.padding(top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    version.lines.forEach { line -> ChangeRow(line.text, line.kind) }
                }
            }
        }
    }
}

@Composable
private fun VersionBadge(text: String, color: Color) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.5.dp)
    )
}

/** "2 New · 1 Fixed" as small coloured chips. */
@Composable
private fun KindSummary(version: ChangelogVersion) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChangeKind.entries.forEach { kind ->
            val n = version.counts[kind] ?: return@forEach
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(kind.color().copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(kind.color()))
                Spacer(Modifier.width(5.dp))
                Text("$n ${kind.label}", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = kind.color())
            }
        }
    }
}

@Composable
private fun ChangeRow(text: String, kind: ChangeKind) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(kind.color().copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(kind.icon(), kind.label, tint = kind.color(), modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}

/** The bottom of the timeline: where it all began. */
@Composable
private fun OriginMarker(firstDate: String?, railColor: Color) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).rail(railColor, to = 18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(RailWidth)) {
            Box(
                Modifier.padding(start = RailX - 9.dp, top = 9.dp).size(18.dp).clip(CircleShape)
                    .background(AppColors.success.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Eco, null, tint = AppColors.success, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            Text("Where it all began", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (firstDate != null) Text(firstDate, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The About page's entry to the changelog: this version's first changes and a link to every version. */
@Composable
internal fun WhatsNewCard(cardBg: Color, cardBorder: BorderStroke, onClick: () -> Unit) {
    val versions = remember { Changelog.versions }
    val current = remember { versions.firstOrNull { it.isInstalled } ?: versions.firstOrNull() } ?: return
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(primary.copy(alpha = 0.55f), AppColors.accentAlt.copy(alpha = 0.35f)))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(primary, AppColors.accentAlt))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.NewReleases, null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("What's new in v${current.versionName}", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    current.dateLabel?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current.lines.take(2).forEach { line -> ChangeRow(line.text, line.kind) }
            Text(
                "See all ${versions.size} versions",
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = primary
            )
        }
    }
}
