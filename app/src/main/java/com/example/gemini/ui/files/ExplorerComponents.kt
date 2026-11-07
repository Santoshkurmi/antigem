package com.example.gemini.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.files.ExplorerEntry
import com.example.gemini.data.files.FileExplorerApi
import com.example.gemini.data.files.FileKind
import com.example.gemini.data.files.FileKinds
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- formatting ----------------

internal fun formatModified(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "'Today' HH:mm"
        sameYear -> "MMM d, HH:mm"
        else -> "MMM d, yyyy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

internal fun entrySubtitle(e: ExplorerEntry): String = if (e.isDir) {
    val count = when {
        e.childCount > 1000 -> "1000+ items"
        e.childCount == 1 -> "1 item"
        e.childCount > 1 -> "${e.childCount} items"
        else -> "Empty"
    }
    listOf(count, formatModified(e.modTime)).filter { it.isNotBlank() }.joinToString("  ·  ")
} else {
    listOf(FileKinds.formatSize(e.size), formatModified(e.modTime)).filter { it.isNotBlank() }.joinToString("  ·  ")
}

// ---------------- icon tile / thumbnail ----------------

@Composable
internal fun EntryIcon(entry: ExplorerEntry, size: Dp, thumbnails: Boolean, corner: Dp = 12.dp) {
    val folderColor = MaterialTheme.colorScheme.primary
    val kind = entry.kind
    val tint = FileKinds.color(kind, entry.name, folderColor)
    val shape = RoundedCornerShape(corner)
    val showThumb = thumbnails && kind == FileKind.IMAGE && !entry.name.endsWith(".svg", true)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = if (kind == FileKind.FOLDER) 0.16f else 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(
                imageVector = if (kind == FileKind.FOLDER && entry.childCount > 0) Icons.Outlined.Folder else FileKinds.icon(kind, entry.name),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.52f)
            )
        }
        if (showThumb) {
            val context = LocalContext.current
            val model = remember(entry.path, entry.modTime) {
                val src: Any = if (FileExplorerApi.isLocallyReadable(entry.path)) File(entry.path) else FileExplorerApi.rawUrl(entry.path)
                ImageRequest.Builder(context).data(src).size(256).crossfade(true).build()
            }
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { fallback() },
                error = { fallback() },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            fallback()
        }
        if (entry.symlink) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(size * 0.36f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Link, contentDescription = "Link", tint = tint, modifier = Modifier.size(size * 0.26f))
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val scale by animateFloatAsState(if (selected) 1f else 0.85f, label = "sel")
    if (selected) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp * scale))
        }
    } else {
        Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.6.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(22.dp)) {}
    }
}

// ---------------- list row ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    entry: ExplorerEntry,
    selected: Boolean,
    selecting: Boolean,
    thumbnails: Boolean,
    dimmed: Boolean,
    cut: Boolean,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp)
            .alpha(if (dimmed || cut) 0.45f else if (entry.hidden) 0.7f else 1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EntryIcon(entry, 42.dp, thumbnails)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 15.sp,
                fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitleOverride ?: entrySubtitle(entry),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selecting) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { SelectionMark(selected) }
        } else {
            IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${entry.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- grid tile ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryTile(
    entry: ExplorerEntry,
    selected: Boolean,
    selecting: Boolean,
    thumbnails: Boolean,
    dimmed: Boolean,
    cut: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (dimmed || cut) 0.45f else if (entry.hidden) 0.7f else 1f)
    ) {
        Box {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EntryIcon(entry, 64.dp, thumbnails, corner = 16.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.name,
                    fontSize = 12.5.sp,
                    fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )
                Text(
                    if (entry.isDir) entrySubtitle(entry).substringBefore("  ·") else FileKinds.formatSize(entry.size),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            AnimatedVisibility(selecting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                SelectionMark(selected)
            }
        }
    }
}

// ---------------- breadcrumbs ----------------

internal data class Crumb(val label: String, val path: String, val icon: ImageVector? = null)

internal fun crumbsFor(path: String, home: String): List<Crumb> {
    val clean = path.trimEnd('/').ifBlank { "/" }
    val storage = listOf("/storage/emulated/0", "/sdcard").firstOrNull { clean == it || clean.startsWith("$it/") }
    val (root, rootPath) = when {
        home.isNotBlank() && (clean == home || clean.startsWith("$home/")) -> Crumb("Home", home, Icons.Outlined.Home) to home
        storage != null -> Crumb("Internal storage", storage, Icons.Outlined.PhoneAndroid) to storage
        else -> Crumb("Root", "/", Icons.Outlined.Storage) to "/"
    }
    val rest = clean.removePrefix(rootPath).trim('/').split('/').filter { it.isNotBlank() }
    val crumbs = mutableListOf(root)
    var acc = rootPath.trimEnd('/')
    rest.forEach { seg ->
        acc = "$acc/$seg"
        crumbs += Crumb(seg, acc)
    }
    return crumbs
}

@Composable
internal fun BreadcrumbBar(
    crumbs: List<Crumb>,
    onPlaces: () -> Unit,
    onCrumb: (Crumb) -> Unit,
    onCopyPath: () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onPlaces,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.SdStorage, contentDescription = "Places", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
        ) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                crumbs.forEachIndexed { i, c ->
                    val last = i == crumbs.lastIndex
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .then(if (last) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                            .combinedClickableCompat(onClick = { onCrumb(c) }, onLongClick = onCopyPath)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        c.icon?.let {
                            Icon(it, null, modifier = Modifier.size(15.dp), tint = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            c.label,
                            fontSize = 13.sp,
                            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (!last) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

// ---------------- small pieces ----------------

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** A round icon + label button used in the action sheet's quick row. */
@Composable
internal fun QuickAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickableCompat(onClick = onClick, onLongClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.5.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun SheetRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, titleColor: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick = onClick, onLongClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
