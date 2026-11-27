package ai.antigem.ui.ide

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.daemon.OpenTab
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.i18n.L
import java.io.File

/** The file a tab shows: diff, commit and revision tabs keep it inside their own made-up path. */
internal fun realPathOf(tab: OpenTab, projectPath: String?): String {
    if (tab.isDiff) {
        val f = tab.diffFile ?: return tab.path
        // git gives paths relative to the project
        return if (!File(f).isAbsolute && !projectPath.isNullOrBlank()) File(projectPath, f).path else f
    }
    // "commit:<hash>:<path>", "revision:HEAD:<path>"
    for (prefix in listOf("commit:", "revision:")) {
        if (tab.path.startsWith(prefix)) return tab.path.removePrefix(prefix).substringAfter(':')
    }
    return tab.path
}

/**
 * AntiGem's home folder as "~" (both ways Android writes the app's data folder: /data/user/0/… and /data/data/…).
 * Any other path is shown as it is.
 */
internal fun shortenHome(path: String, context: Context): String {
    val home = LocalEnvironmentManager.getHomeDir(context).absolutePath.trimEnd('/')
    val homes = linkedSetOf(home, home.replaceFirst("/data/user/0/", "/data/data/"), home.replaceFirst("/data/data/", "/data/user/0/"))
    for (h in homes) {
        if (path == h) return "~"
        if (path.startsWith("$h/")) return "~/" + path.removePrefix("$h/")
    }
    return path
}

/**
 * One grey line above the tabs: where the open file is. A long path scrolls sideways (no scrollbar, a fade at the right
 * while there is more); a tap copies the full path (not the "~" form).
 */
@Composable
internal fun ActiveFilePathBar(fullPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shown = remember(fullPath) { shortenHome(fullPath, context) }
    val scroll = rememberScrollState()
    // another file starts at its first folder
    LaunchedEffect(fullPath) { scroll.scrollTo(0) }
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClickLabel = L.ide.activeFilePathBar.copyPath) {
                clipboard.setText(AnnotatedString(fullPath))
                Toast.makeText(context, L.ide.activeFilePathBar.pathCopied, Toast.LENGTH_SHORT).show()
            }
    ) {
        Text(
            text = shown,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 3.dp)
        )
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val w = 28.dp.toPx().coerceAtMost(size.width)
                        drawRect(
                            brush = Brush.horizontalGradient(listOf(bg.copy(alpha = 0f), bg), startX = size.width - w, endX = size.width),
                            topLeft = Offset(size.width - w, 0f),
                            size = Size(w, size.height)
                        )
                    }
            )
        }
    }
}
