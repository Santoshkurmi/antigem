package ai.antigem.ui.netcap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Css
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Html
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Javascript
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.clipboard.SafeClipboard
import ai.antigem.data.netcap.NetApps
import ai.antigem.data.netcap.NetRow
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- formatting ----

internal fun fmtSize(n: Long): String = when {
    n <= 0 -> "0"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(Locale.US, "%.1f k", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f M", n / 1048576.0)
    else -> String.format(Locale.US, "%.2f G", n / 1073741824.0)
}

internal fun fmtMs(ms: Double): String = when {
    ms <= 0 -> ""
    ms < 1 -> "<1 ms"
    ms < 1000 -> "${ms.toInt()} ms"
    ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000)
    else -> "${(ms / 60_000).toInt()} m ${((ms % 60_000) / 1000).toInt()} s"
}

private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
private val timeMsFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
private val dateTimeFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

internal fun fmtTime(ms: Long): String = if (ms <= 0) "" else timeFmt.get()!!.format(Date(ms))
internal fun fmtTimeMs(ms: Long): String = if (ms <= 0) "" else timeMsFmt.get()!!.format(Date(ms))
internal fun fmtDateTime(ms: Long): String = if (ms <= 0) "" else dateTimeFmt.get()!!.format(Date(ms))

// ---- colors ----

internal fun methodColor(method: String, kind: String = "http"): Color = when {
    kind == "ws" -> AppColors.accentIndigo
    kind == "grpc" -> AppColors.accentTeal
    kind == "tcp" || kind == "udp" || kind == "tls" -> AppColors.textSecondary
    else -> when (method.uppercase()) {
        "GET" -> AppColors.info
        "POST" -> AppColors.success
        "PUT", "PATCH" -> AppColors.warning
        "DELETE" -> AppColors.danger
        else -> AppColors.textSecondary
    }
}

internal fun statusColor(status: Int, error: String = ""): Color = when {
    error.isNotEmpty() -> AppColors.danger
    status == 0 -> AppColors.textSecondary
    status < 300 -> AppColors.success
    status < 400 -> AppColors.info
    status < 500 -> AppColors.warning
    else -> AppColors.danger
}

/** The highlight colors a user can give a row (stored by name, so the AI and the CLI see the same). */
internal val markColors = listOf("red", "orange", "yellow", "green", "blue", "purple")

internal fun markColor(name: String): Color? = when (name) {
    "red" -> Color(0xFFE5484D)
    "orange" -> Color(0xFFF76B15)
    "yellow" -> Color(0xFFFFC53D)
    "green" -> Color(0xFF30A46C)
    "blue" -> Color(0xFF0090FF)
    "purple" -> Color(0xFF8E4EC6)
    else -> null
}

internal val Mono = FontFamily.Monospace

/** A content type as an icon for the list ({} for JSON, an image for images…); null for unknown types. */
internal fun typeIcon(type: String): ImageVector? = when (type) {
    "json" -> Icons.Outlined.DataObject
    "html" -> Icons.Outlined.Html
    "js" -> Icons.Outlined.Javascript
    "css" -> Icons.Outlined.Css
    "xml" -> Icons.Outlined.Code
    "image" -> Icons.Outlined.Image
    "media" -> Icons.Outlined.Movie
    "font" -> Icons.Outlined.FontDownload
    "form" -> Icons.Outlined.TextFields
    "text" -> Icons.Outlined.Description
    "proto", "grpc", "binary" -> Icons.Outlined.Memory
    else -> null
}

// ---- small parts ----

internal fun copyText(context: android.content.Context, label: String, text: String) {
    if (SafeClipboard.copy(context, label, text)) AppToastHelper.showToast(L.netcap.ui.copied)
}

/** A page header: back arrow, title (and subtitle), actions. */
@Composable
internal fun NetTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.common.back, tint = AppColors.onBackground) }
        } else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, color = AppColors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = AppColors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** The app's icon, or a neutral glyph for unknown apps. */
@Composable
internal fun AppIcon(pkg: String, size: Dp = 28.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    var bmp by remember(pkg) { mutableStateOf(NetApps.cachedIcon(pkg)) }
    if (bmp == null && pkg.isNotEmpty()) {
        LaunchedEffect(pkg) { bmp = NetApps.icon(context, pkg, px.coerceAtLeast(48)) }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(AppColors.fill),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.Apps, null, tint = AppColors.textSecondary, modifier = Modifier.size(size * 0.6f)) }
    }
}

/** A small colored label (method, status, type). */
@Composable
internal fun Tag(text: String, color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text, color = if (filled) Color.White else color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = Mono, maxLines = 1
        )
    }
}

/** A chip that can be on or off. */
@Composable
internal fun ToggleChip(text: String, on: Boolean, icon: ImageVector? = null, color: Color = AppColors.primary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (on) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (on) color.copy(alpha = 0.6f) else AppColors.border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (on) color else AppColors.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = if (on) color else AppColors.onBackground, fontSize = 12.5.sp, maxLines = 1)
    }
}

/** A one-line search/filter field. */
@Composable
internal fun NetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(AppColors.fill).padding(horizontal = 10.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = AppColors.textSecondary, fontSize = 14.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AppColors.onBackground, fontSize = 14.sp, fontFamily = if (mono) Mono else null),
                cursorBrush = SolidColor(AppColors.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, L.common.clear, tint = AppColors.textSecondary,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onValueChange(""); onDone?.invoke() }
            )
        }
        trailing?.invoke()
    }
}

/** A titled card section. */
@Composable
internal fun NetCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.card)
            .border(BorderStroke(1.dp, AppColors.border), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        if (title != null) {
            Text(title, color = AppColors.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** label: value, the value selectable-looking and copyable on long press. */
@Composable
internal fun InfoLine(label: String, value: String, mono: Boolean = false, valueColor: Color = AppColors.onBackground) {
    if (value.isEmpty()) return
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { copyText(context, label, value) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = AppColors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(112.dp))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = if (mono) Mono else null, modifier = Modifier.weight(1f))
    }
}

/** A short status line ("Requests · 12 of 300"). */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = AppColors.textSecondary) {
    Text(text, color = color, fontSize = 12.5.sp, modifier = modifier)
}

/** One-line summary of a row for menus and dialogs. */
internal fun NetRow.title(): String = when {
    kind == "tcp" || kind == "udp" || kind == "tls" -> "${kind.uppercase()} $host:$port"
    else -> "$method $url"
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = AppColors.textSecondary.copy(alpha = 0.7f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = AppColors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = AppColors.textSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** Runs [block], showing its error as a toast. */
internal suspend fun <T> netTry(block: suspend () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    AppToastHelper.showToast(e.message ?: e.toString())
    null
}
