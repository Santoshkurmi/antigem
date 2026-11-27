package ai.antigem.ui.ide

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import ai.antigem.data.files.FileExplorerApi
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.i18n.L
import ai.antigem.ui.components.a11yMinTouchTarget
import ai.antigem.ui.components.rememberTalkBackOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The bridge's own home folder: "/home/<user>" in the Ubuntu chroot or on a PC, AntiGem's home under the built-in
 * runtime. Read once per bridge address, and again after the bridge reconnects.
 */
internal object BridgeHome {
    private val _home = MutableStateFlow<String?>(null)
    val home: StateFlow<String?> = _home.asStateFlow()
    @Volatile private var readFrom: String? = null

    suspend fun load(force: Boolean = false) {
        val url = AuthPreferences.currentBridgeHttpUrl
        if (!force && url == readFrom) return
        // another bridge (the runtime or the server changed): its home is somewhere else
        if (url != readFrom) _home.value = null
        val home = runCatching { FileExplorerApi.roots().first }.getOrDefault("").trimEnd('/')
        if (home.isNotEmpty()) {
            _home.value = home
            readFrom = url
        }
    }
}

/** The app's data folder written one way (/data/user/0/<pkg>), so both ways Android writes it compare equal. */
internal fun oneDataForm(path: String): String =
    if (path.startsWith("/data/data/")) "/data/user/0/" + path.removePrefix("/data/data/") else path

/** [path] both ways Android writes the app's data folder: /data/user/0/… and /data/data/…. */
private fun bothDataForms(path: String): List<String> = when {
    path.startsWith("/data/user/0/") -> listOf(path, "/data/data/" + path.removePrefix("/data/user/0/"))
    path.startsWith("/data/data/") -> listOf(path, "/data/user/0/" + path.removePrefix("/data/data/"))
    else -> listOf(path)
}

/**
 * A path inside a home folder as "~/…": the bridge's home ([bridgeHome]: the chroot's or the PC's own) and AntiGem's.
 * Any other path is shown as it is.
 */
internal fun shortenHome(path: String, context: Context, bridgeHome: String?): String {
    val homes = buildList {
        bridgeHome?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { addAll(bothDataForms(it)) }
        addAll(bothDataForms(LocalEnvironmentManager.getHomeDir(context).absolutePath.trimEnd('/')))
    }.distinct().sortedByDescending { it.length }
    for (h in homes) {
        if (path == h) return "~"
        if (path.startsWith("$h/")) return "~/" + path.removePrefix("$h/")
    }
    return path
}

/**
 * A tab with no file of its own yet: text or a file shared from another app ("shared-text:…", "content://…"), or the
 * private copy kept for a shared picture ([sharedCopies]). It gets a folder only once it is saved.
 */
private fun isFromAnotherApp(tab: OpenTab, fullPath: String, sharedCopies: String): Boolean =
    !tab.isDiff && (!fullPath.startsWith("/") || fullPath.startsWith("$sharedCopies/"))

/**
 * The folder of the tab's file when it lies outside the open project (or no project is open), so it can become the
 * project; null for a file inside it, a diff, a file from another app or a file right in "/".
 */
private fun folderOutsideProject(tab: OpenTab, fullPath: String, projectPath: String?, sharedCopies: String): String? {
    if (tab.isDiff || tab.isExternal || isFromAnotherApp(tab, fullPath, sharedCopies)) return null
    val folder = File(fullPath).parent?.takeIf { it != "/" } ?: return null
    if (!projectPath.isNullOrBlank()) {
        val project = oneDataForm(projectPath.trimEnd('/'))
        val file = oneDataForm(fullPath)
        // "" is "/": every file is inside it
        if (project.isEmpty() || file == project || file.startsWith("$project/")) return null
    }
    return folder
}

/**
 * One grey line above the tabs: where the open file is. A long path scrolls sideways (no scrollbar, a fade at the right
 * while there is more); a tap copies the full path (not the "~" form). A file outside the open project gets an
 * "Open folder" button at the end ([onOpenFolder] gets its folder). A file from another app has no path until it is
 * saved: the line says so.
 */
@Composable
internal fun ActiveFilePathBar(
    tab: OpenTab,
    projectPath: String?,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val talkBackOn = rememberTalkBackOn()
    val bridgeHome by BridgeHome.home.collectAsState()
    // where MainActivity keeps its copy of a picture shared from another app
    val sharedCopies = remember(context) { File(context.cacheDir, "shared_open").path }
    val fullPath = realPathOf(tab, projectPath)
    val fromAnotherApp = isFromAnotherApp(tab, fullPath, sharedCopies)
    val folder = folderOutsideProject(tab, fullPath, projectPath, sharedCopies)
    val shown = if (fromAnotherApp) L.ide.activeFilePathBar.fromAnotherAppNotSavedTo
    else remember(fullPath, bridgeHome) { shortenHome(fullPath, context, bridgeHome) }
    val scroll = rememberScrollState()
    // another file starts at its first folder
    LaunchedEffect(fullPath) { scroll.scrollTo(0) }
    val bg = MaterialTheme.colorScheme.background
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (fromAnotherApp) Modifier
                    else Modifier.clickable(onClickLabel = L.ide.activeFilePathBar.copyPath) {
                        clipboard.setText(AnnotatedString(fullPath))
                        Toast.makeText(context, L.ide.activeFilePathBar.pathCopied, Toast.LENGTH_SHORT).show()
                    }
                )
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
        if (folder != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .a11yMinTouchTarget(talkBackOn)
                    .clickable(onClickLabel = L.ide.activeFilePathBar.openThisFilesFolderAsThe) { onOpenFolder(folder) }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = L.ide.activeFilePathBar.openFolder,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}
