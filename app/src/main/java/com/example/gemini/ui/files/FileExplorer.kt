package com.example.gemini.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.gemini.ui.components.bounceWhenNotScrollable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.data.files.*
import com.example.gemini.ui.components.MarkdownDocViewerModal
import kotlinx.coroutines.launch

/** Files picked with Copy / Cut, shared by every explorer (copy in the Files screen, paste in a picker). */
object ExplorerClipboard {
    val entries = mutableStateListOf<ExplorerEntry>()
    var move by mutableStateOf(false)
    /** Which side they are on (this phone, or the bridge's machine): pasting on the other side copies across. */
    var side by mutableStateOf(ExplorerSide.BRIDGE)

    fun set(list: List<ExplorerEntry>, move: Boolean, side: ExplorerSide = ExplorerSide.BRIDGE) {
        entries.clear()
        entries.addAll(list)
        this.move = move
        this.side = side
    }

    fun clear() = entries.clear()
}

private val naturalOrder = Comparator<String> { a, b ->
    val x = a.lowercase()
    val y = b.lowercase()
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val ci = x[i]
        val cj = y[j]
        if (ci.isDigit() && cj.isDigit()) {
            val si = i; while (i < x.length && x[i].isDigit()) i++
            val sj = j; while (j < y.length && y[j].isDigit()) j++
            val ni = x.substring(si, i).trimStart('0')
            val nj = y.substring(sj, j).trimStart('0')
            if (ni.length != nj.length) return@Comparator ni.length - nj.length
            val c = ni.compareTo(nj)
            if (c != 0) return@Comparator c
        } else {
            if (ci != cj) return@Comparator ci - cj
            i++; j++
        }
    }
    (x.length - i) - (y.length - j)
}

private fun arrange(list: List<ExplorerEntry>, s: ExplorerSettings): List<ExplorerEntry> {
    val shown = if (s.showHidden) list else list.filter { !it.hidden }
    val byName = Comparator<ExplorerEntry> { a, b -> naturalOrder.compare(a.name, b.name) }
    val base: Comparator<ExplorerEntry> = when (s.sortBy) {
        SortBy.NAME -> byName
        SortBy.DATE -> compareBy<ExplorerEntry> { it.modTime }.then(byName)
        SortBy.SIZE -> compareBy<ExplorerEntry> { if (it.isDir) it.childCount.toLong() else it.size }.then(byName)
        SortBy.TYPE -> compareBy<ExplorerEntry> { FileKinds.extOf(it.name) }.then(byName)
    }
    val ordered = if (s.descending) base.reversed() else base
    return shown.sortedWith(if (s.foldersFirst) compareByDescending<ExplorerEntry> { it.isDir }.then(ordered) else ordered)
}

private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

private fun copyText(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("path", text))
    }
}

private enum class DestOp { COPY, MOVE, EXTRACT }

private data class DestRequest(val op: DestOp, val entries: List<ExplorerEntry>, val side: ExplorerSide = ExplorerSide.BRIDGE)

/** One tab of the Files screen: a folder on the bridge's machine or (server / PC) this phone. */
private data class ExplorerTabState(val id: Long, val side: ExplorerSide, val path: String)

private var tabIds = 0L
private fun nextTabId() = ++tabIds

/** The side the explorer on screen shows (thumbnails and dialogs read files from it). */
val LocalExplorerSide = staticCompositionLocalOf { ExplorerSide.BRIDGE }

/** The bridge's machine as the tab names it: the PC in use (Connect to PC), or the server. */
private fun bridgeTabName(): String =
    com.example.gemini.data.preferences.AuthPreferences.pcOverride?.pcName?.takeIf { it.isNotBlank() } ?: "Server"

private data class PendingConflict(val names: List<String>, val move: Boolean, val run: (ConflictPolicy) -> Unit)

/**
 * The file explorer. One component serves the Files screen and every picker:
 * - [onConfirmFolder]: a "choose this folder" bar (open as project, pick a destination, import shared files here)
 * - [onPickFile]: tapping a file picks it
 * - [onPickFiles]: tapping a file picks it; a long press selects several, picked together with [pickFilesLabel]
 * - [onSave]: a file name field and a "Save here" bar
 * - [onOpenedInIde] / [onOpenFolderInIde]: the IDE options, and what happens after a file or folder is opened there
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorer(
    stateKey: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialPath: String? = null,
    isVisible: Boolean = true,
    inDialog: Boolean = false,
    title: String? = null,
    confirmLabel: String = "Choose this folder",
    onConfirmFolder: ((String) -> Unit)? = null,
    onPickFile: ((ExplorerEntry) -> Unit)? = null,
    onPickFiles: ((List<ExplorerEntry>) -> Unit)? = null,
    pickFilesLabel: String = "Pick",
    saveFileName: String? = null,
    onSave: ((folder: String, name: String) -> Unit)? = null,
    onOpenedInIde: (() -> Unit)? = null,
    onOpenFolderInIde: ((String) -> Unit)? = null,
    onProjectCreated: ((ProjectItem) -> Unit)? = null,
    showClipboard: Boolean = true,
    /** Server mode / Connect to PC: a "This phone" tab next to the bridge machine's files (copy and move between them). */
    allowPhoneTab: Boolean = false,
    initialSide: ExplorerSide = ExplorerSide.BRIDGE,
    /** Like [onConfirmFolder], with the side the chosen folder is on (a destination picker with both tabs). */
    onConfirmFolderAt: ((String, ExplorerSide) -> Unit)? = null
) {
    val context = LocalContext.current
    remember { ExplorerPrefs.init(context); PhoneSource.init(context) }
    // the phone tab only makes sense while the bridge (and its files) is on another machine
    val phoneTab = allowPhoneTab && com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode

    // ----- tabs (the Files screen): each its own folder. Server / PC: always, starting with the bridge's machine and this
    // phone. Phone-only: turned on with the toolbar button. Pickers (dialogs) show the two sides as plain tabs instead.
    val multiTab = allowPhoneTab && !inDialog
    val tabsKey = stateKey + if (phoneTab) ":remote" else ":local"
    var tabMode by remember { mutableStateOf(multiTab && (phoneTab || ExplorerPrefs.tabMode(stateKey))) }
    val tabs = remember { mutableStateListOf<ExplorerTabState>() }
    var activeTab by remember { mutableIntStateOf(0) }
    remember {
        if (multiTab) {
            val (saved, active) = ExplorerPrefs.tabs(tabsKey)
            saved.forEach { (sideName, p) ->
                val sd = runCatching { ExplorerSide.valueOf(sideName) }.getOrNull()
                if (sd != null && (phoneTab || sd == ExplorerSide.BRIDGE)) tabs.add(ExplorerTabState(nextTabId(), sd, p))
            }
            if (tabs.isEmpty()) {
                tabs.add(ExplorerTabState(nextTabId(), ExplorerSide.BRIDGE, ExplorerPrefs.lastPath(stateKey) ?: "~"))
                if (phoneTab) tabs.add(ExplorerTabState(nextTabId(), ExplorerSide.PHONE, ExplorerPrefs.lastPath("$stateKey:phone") ?: "~"))
            }
            activeTab = active.coerceIn(0, tabs.lastIndex)
            if (!tabMode && tabs.size > 1) { val keep = tabs[activeTab]; tabs.clear(); tabs.add(keep); activeTab = 0 }
        }
        true
    }
    val showTabs = multiTab && tabMode

    var side by remember { mutableStateOf(if (multiTab && tabs.isNotEmpty()) tabs[activeTab].side else if (phoneTab) initialSide else ExplorerSide.BRIDGE) }
    fun src(): ExplorerSource = ExplorerSource.of(side)
    fun keyOf(s: ExplorerSide) = if (s == ExplorerSide.PHONE) "$stateKey:phone" else stateKey
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val settings by ExplorerPrefs.settings.collectAsState()
    val projects by TermuxDaemonManager.projects.collectAsState()
    // the IDE edits files on the bridge's machine: not this phone's (on the phone tab)
    val ideAvailable = onOpenedInIde != null && side == ExplorerSide.BRIDGE

    // ----- listing state -----
    var path by remember {
        mutableStateOf(initialPath?.takeIf { it.isNotBlank() } ?: tabs.getOrNull(activeTab)?.path?.takeIf { multiTab } ?: ExplorerPrefs.lastPath(keyOf(side)) ?: "~")
    }
    var home by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ExplorerEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<String>() }

    // ----- interaction state -----
    val selected = remember { mutableStateMapOf<String, ExplorerEntry>() }
    val selecting = selected.isNotEmpty()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deep by remember { mutableStateOf(false) }
    var deepResults by remember { mutableStateOf<List<ExplorerEntry>?>(null) }
    var deepLoading by remember { mutableStateOf(false) }
    var deepTruncated by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableStateOf<Float?>(null) }
    var saveName by remember(saveFileName) { mutableStateOf(saveFileName ?: "") }

    // ----- sheets & dialogs -----
    var menuOpen by remember { mutableStateOf(false) }
    var selMenuOpen by remember { mutableStateOf(false) }
    var placesOpen by remember { mutableStateOf(false) }
    var roots by remember { mutableStateOf<List<ExplorerRoot>>(emptyList()) }
    var viewOptionsOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var newIsDir by remember { mutableStateOf<Boolean?>(null) }
    var newProjectOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ExplorerEntry?>(null) }
    var openWithFor by remember { mutableStateOf<ExplorerEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<ExplorerEntry>?>(null) }
    var renameTarget by remember { mutableStateOf<ExplorerEntry?>(null) }
    var compressTargets by remember { mutableStateOf<List<ExplorerEntry>?>(null) }
    var detailsFor by remember { mutableStateOf<ExplorerEntry?>(null) }
    var archiveFor by remember { mutableStateOf<ExplorerEntry?>(null) }
    var destRequest by remember { mutableStateOf<DestRequest?>(null) }
    var conflict by remember { mutableStateOf<PendingConflict?>(null) }
    var notText by remember { mutableStateOf<Pair<ExplorerEntry, String>?>(null) }
    var markdownDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var imageView by remember { mutableStateOf<ExplorerEntry?>(null) }
    var confirmOverwrite by remember { mutableStateOf<String?>(null) }

    fun snack(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    suspend fun load(target: String, pushFrom: String?): Boolean {
        loading = true
        val res = try { src().list(target) } finally { loading = false }
        res.onSuccess { l ->
            if (pushFrom != null && pushFrom != l.path) history.add(pushFrom)
            path = l.path
            home = l.home
            entries = l.entries
            loadError = null
            loaded = true
            selected.clear()
            ExplorerPrefs.setLastPath(keyOf(side), l.path)
            if (multiTab && activeTab in tabs.indices) {
                tabs[activeTab] = tabs[activeTab].copy(side = side, path = l.path)
                ExplorerPrefs.setTabs(tabsKey, tabs.map { it.side.name to it.path }, activeTab)
            }
            ExplorerPrefs.addRecentFolder(l.path)
        }.onFailure {
            if (!loaded) loadError = it.message else snack(it.message ?: "Cannot open folder")
        }
        return res.isSuccess
    }

    fun go(target: String) {
        if (searching) { searching = false; query = ""; deepResults = null }
        scope.launch { load(target, pushFrom = if (loaded) path else null) }
    }

    fun reload(silent: Boolean = true) {
        scope.launch {
            if (!silent) refreshing = true
            val keep = selected.keys.toSet()
            src().list(path).onSuccess { l ->
                entries = l.entries
                home = l.home
                loadError = null
                loaded = true
                selected.keys.retainAll(keep.intersect(l.entries.map { it.path }.toSet()))
            }.onFailure { if (!silent) snack(it.message ?: "Refresh failed") }
            refreshing = false
        }
    }

    /** Shows [to] in [folder] (a fresh listing: selection, history and search start over). */
    fun show(to: ExplorerSide, folder: String) {
        side = to
        selected.clear()
        history.clear()
        searching = false; query = ""; deepResults = null; deep = false
        loaded = false
        loadError = null
        entries = emptyList()
        path = folder
        scope.launch { if (!load(folder, null) && folder != "~") load("~", null) }
    }

    /** Shows the other side, in the folder it showed last (the pickers' two tabs). */
    fun switchSide(to: ExplorerSide) {
        if (to == side || busy != null) return
        show(to, ExplorerPrefs.lastPath(keyOf(to)) ?: "~")
    }

    fun saveTabs() = ExplorerPrefs.setTabs(tabsKey, tabs.map { it.side.name to it.path }, activeTab)

    fun openTab(i: Int) {
        if (i == activeTab || i !in tabs.indices) return
        activeTab = i
        saveTabs()
        show(tabs[i].side, tabs[i].path)
    }

    /** A new tab on [on]: the folder on screen when it is the same side, else that side's last folder. */
    fun addTab(on: ExplorerSide) {
        val folder = if (on == side) path else tabs.lastOrNull { it.side == on }?.path ?: ExplorerPrefs.lastPath(keyOf(on)) ?: "~"
        tabs.add(ExplorerTabState(nextTabId(), on, folder))
        openTab(tabs.lastIndex)
    }

    fun closeTab(i: Int) {
        if (tabs.size <= 1 || i !in tabs.indices) return
        val wasActive = i == activeTab
        tabs.removeAt(i)
        if (wasActive) { activeTab = -1; openTab(i.coerceAtMost(tabs.lastIndex)) }
        else { if (i < activeTab) activeTab--; saveTabs() }
    }

    /** Phone-only mode's toolbar button: tabs on, or back to one folder (the one on screen). */
    fun setTabsOn(on: Boolean) {
        tabMode = on
        ExplorerPrefs.setTabMode(stateKey, on)
        if (!on && tabs.size > 1) {
            val keep = tabs.getOrNull(activeTab) ?: ExplorerTabState(nextTabId(), side, path)
            tabs.clear(); tabs.add(keep); activeTab = 0; saveTabs()
        }
    }

    fun goBack(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        scope.launch { load(prev, pushFrom = null) }
        return true
    }

    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        // a pending "open this folder" request does the first load itself
        if (!loaded && ExplorerNav.request.value?.first == stateKey) return@LaunchedEffect
        if (!loaded) {
            if (!load(path, null) && path != "~") {
                // the remembered folder is gone: start at home and say why
                val missing = path
                if (load("~", null)) snack("\"$missing\" is not available. Showing Home.")
            }
        } else {
            reload()
        }
    }

    // "Show in Files" and similar requests from elsewhere in the app
    val navRequest by ExplorerNav.request.collectAsState()
    LaunchedEffect(navRequest) {
        val (key, target) = navRequest ?: return@LaunchedEffect
        if (key != stateKey) return@LaunchedEffect
        ExplorerNav.consume()
        // "Show in Files" points at the bridge's machine: a tab on it (with tabs), or the bridge side
        if (side != ExplorerSide.BRIDGE) {
            val t = tabs.indexOfFirst { it.side == ExplorerSide.BRIDGE }
            if (multiTab && t >= 0) { activeTab = t; saveTabs() }
            side = ExplorerSide.BRIDGE; history.clear(); loaded = false
        }
        if (!loaded) path = target
        val from = if (loaded) path else null
        // consume() just changed navRequest, which cancels this effect: load in the explorer's own scope
        scope.launch {
            if (load(target, pushFrom = from)) return@launch
            snack("Cannot open $target")
            // nothing shown yet: fall back to home instead of an empty screen
            if (!loaded && target != "~") load("~", null)
        }
    }

    fun runOp(label: String, op: suspend () -> FsResult, after: suspend (FsResult) -> Unit = {}) {
        if (busy != null) return
        scope.launch {
            busy = label
            busyProgress = null
            val r = op()
            busy = null
            reload()
            if (!r.ok && r.error != null) snack(r.error)
            after(r)
        }
    }

    // ----- opening -----

    fun openExternal(e: ExplorerEntry, chooser: Boolean = false) {
        if (busy != null) return
        scope.launch {
            busy = "Preparing ${e.name}…"
            busyProgress = null
            val uri = FileOpener.uriFor(context, e.path, e.size, local = side == ExplorerSide.PHONE) { busyProgress = it }
            busy = null
            uri.onSuccess { FileOpener.view(context, it, e.name, chooser)?.let(::snack) }
                .onFailure { snack(it.message ?: "Could not open ${e.name}") }
        }
    }

    /** "Share" in the Open with sheet: sends the file to another app. */
    fun shareOne(e: ExplorerEntry) {
        if (busy != null) return
        scope.launch {
            busy = "Preparing ${e.name}…"
            busyProgress = null
            val uri = FileOpener.uriFor(context, e.path, e.size, local = side == ExplorerSide.PHONE) { busyProgress = it }
            busy = null
            uri.onSuccess { FileOpener.share(context, listOf(it to e.name))?.let(::snack) }
                .onFailure { snack(it.message ?: "Could not share ${e.name}") }
        }
    }

    fun openInIde(e: ExplorerEntry, force: Boolean = false) {
        scope.launch {
            busy = "Opening ${e.name}…"
            val r = IdeFileLauncher.open(e.path, e.name, e.size, force)
            busy = null
            when (r) {
                is IdeFileLauncher.Outcome.Opened -> onOpenedInIde?.invoke()
                is IdeFileLauncher.Outcome.NeedsConfirm -> notText = e to r.mimeType
                is IdeFileLauncher.Outcome.Failed -> snack(r.message)
            }
        }
    }

    fun preview(e: ExplorerEntry) {
        if (e.kind == FileKind.VIDEO) {
            com.example.gemini.ui.components.FullscreenVideo.play(context, e.path, e.name)
        } else if (e.kind == FileKind.IMAGE) {
            imageView = e
        } else if (e.kind == FileKind.MARKDOWN) {
            scope.launch {
                busy = "Opening ${e.name}…"
                val content = if (side == ExplorerSide.PHONE) runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(e.path).readText() } }.getOrNull()
                    else IdeApiClient.readFile(e.path)
                busy = null
                if (content == null) snack("Cannot read ${e.name}") else markdownDoc = e.path to content
            }
        } else {
            archiveFor = e
        }
    }

    fun perform(e: ExplorerEntry, choice: OpenChoice) = when (choice) {
        OpenChoice.IDE -> if (ideAvailable) openInIde(e) else openExternal(e)
        OpenChoice.EXTERNAL -> openExternal(e)
        OpenChoice.PREVIEW -> preview(e)
        OpenChoice.SHARE -> shareOne(e)
    }

    fun openEntry(e: ExplorerEntry) {
        when {
            e.isDir -> go(e.path)
            onPickFiles != null -> onPickFiles(listOf(e))
            onPickFile != null -> onPickFile(e)
            onSave != null -> saveName = e.name
            else -> {
                val remembered = ExplorerPrefs.rememberedChoice(FileKinds.extOf(e.name))
                if (remembered != null && (remembered != OpenChoice.IDE || ideAvailable)) perform(e, remembered) else openWithFor = e
            }
        }
    }

    fun toggle(e: ExplorerEntry) {
        if (selected.containsKey(e.path)) selected.remove(e.path) else selected[e.path] = e
    }

    // ----- file operations -----

    /**
     * Copies / moves [items] (on [from]) into [dest] (on [to]). Between this phone and the bridge's machine the files
     * stream across; on one side the side does it itself.
     */
    fun transfer(
        items: List<ExplorerEntry>, dest: String, move: Boolean, policy: ConflictPolicy?,
        from: ExplorerSide = side, to: ExplorerSide = side
    ) {
        if (items.isEmpty()) return
        scope.launch {
            val across = from != to
            if (policy == null) {
                val destNames = if (dest == path && to == side) entries.map { it.name }.toSet()
                else ExplorerSource.of(to).list(dest).getOrNull()?.entries?.map { it.name }?.toSet().orEmpty()
                val clashes = items.filter { it.name in destNames && (across || parentOf(it.path) != dest.trimEnd('/')) }
                if (clashes.isNotEmpty()) {
                    conflict = PendingConflict(clashes.map { it.name }, move) { p -> conflict = null; transfer(items, dest, move, p, from, to) }
                    return@launch
                }
            }
            val n = items.size
            val noun = if (n == 1) "\"${items.first().name}\"" else "$n items"
            val where = if (!across) "" else if (to == ExplorerSide.PHONE) " to this phone" else " to ${bridgeTabName()}"
            runOp(if (move) "Moving $noun$where…" else "Copying $noun$where…", {
                if (across) CrossTransfer.run(from, items, dest, move, policy ?: ConflictPolicy.RENAME) { busyProgress = it }
                else ExplorerSource.of(to).transfer(items.map { it.path }, dest, move, policy ?: ConflictPolicy.RENAME)
            }) { r ->
                if (r.ok) {
                    if (ExplorerClipboard.entries.map { it.path }.toSet() == items.map { it.path }.toSet()) ExplorerClipboard.clear()
                    val place = if (dest == path && to == side) "here" else "to ${dest.trimEnd('/').substringAfterLast('/')}"
                    snack("${if (move) "Moved" else "Copied"} $noun $place" + if (across) (if (to == ExplorerSide.PHONE) " on this phone" else " on ${bridgeTabName()}") else "")
                } else if (r.count > 0) {
                    snack("${r.count} done, ${r.failed} failed: ${r.error ?: ""}")
                }
            }
        }
    }

    fun shareEntries(items: List<ExplorerEntry>) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) { snack("Folders cannot be shared. Compress them to ZIP first."); return }
        scope.launch {
            busy = "Preparing to share…"
            val uris = files.mapNotNull { f -> FileOpener.uriFor(context, f.path, f.size, local = side == ExplorerSide.PHONE).getOrNull()?.let { it to f.name } }
            busy = null
            if (uris.isEmpty()) snack("Could not prepare files to share") else FileOpener.share(context, uris)?.let(::snack)
        }
    }

    fun extract(e: ExplorerEntry, targetDir: String?) {
        runOp("Extracting ${e.name}…", { src().extract(e.path, targetDir) }) { r ->
            if (r.ok && r.path != null) {
                val res = snackbar.showSnackbar("Extracted ${r.count} files", actionLabel = "Open", withDismissAction = true, duration = SnackbarDuration.Long)
                if (res == SnackbarResult.ActionPerformed) go(r.path)
            }
        }
    }

    val shown = remember(entries, settings) { arrange(entries, settings) }
    val visible: List<ExplorerEntry> = when {
        searching && deep -> deepResults.orEmpty()
        searching && query.isNotBlank() -> shown.filter { it.name.contains(query.trim(), ignoreCase = true) }
        else -> shown
    }
    val cutPaths = if (ExplorerClipboard.move) ExplorerClipboard.entries.map { it.path }.toSet() else emptySet()
    val crumbs = remember(path, home) { crumbsFor(path, home) }
    val folderName = crumbs.lastOrNull()?.label ?: path
    val existingNames = remember(entries) { entries.map { it.name }.toSet() }
    val pickingFolder = onConfirmFolder != null && onPickFile == null && onSave == null

    BackHandler(enabled = isVisible && (selecting || searching || history.isNotEmpty() || inDialog)) {
        when {
            selecting -> selected.clear()
            searching -> { searching = false; query = ""; deepResults = null; deep = false }
            goBack() -> Unit
            else -> onClose()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    when {
                        selecting && onPickFiles != null -> {
                            val pickable = visible.filter { !it.isDir }
                            val files = selected.values.filter { !it.isDir }
                            PickSelectionBar(
                                count = files.size,
                                label = pickFilesLabel,
                                allSelected = files.size == pickable.size && pickable.isNotEmpty(),
                                onClear = { selected.clear() },
                                onSelectAll = { if (files.size == pickable.size) selected.clear() else pickable.forEach { selected[it.path] = it } },
                                onPick = { selected.clear(); onPickFiles(files) }
                            )
                        }
                        selecting -> SelectionBar(
                            count = selected.size,
                            allSelected = selected.size == visible.size && visible.isNotEmpty(),
                            onClear = { selected.clear() },
                            onSelectAll = { if (selected.size == visible.size) selected.clear() else visible.forEach { selected[it.path] = it } },
                            onCopy = { ExplorerClipboard.set(selected.values.toList(), move = false, side); selected.clear(); snack(if (phoneTab) "Copied. Open a folder (either tab) and tap Paste." else "Copied. Open a folder and tap Paste.") },
                            onCut = { ExplorerClipboard.set(selected.values.toList(), move = true, side); selected.clear(); snack(if (phoneTab) "Cut. Open a folder (either tab) and tap Paste." else "Cut. Open a folder and tap Paste.") },
                            onDelete = { deleteTargets = selected.values.toList() },
                            menuOpen = selMenuOpen,
                            onMenu = { selMenuOpen = it },
                            menu = {
                                val sel = selected.values.toList()
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { selMenuOpen = false; shareEntries(sel) })
                                DropdownMenuItem(text = { Text("Compress to ZIP") }, leadingIcon = { Icon(Icons.Outlined.FolderZip, null) }, onClick = { selMenuOpen = false; compressTargets = sel })
                                DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }, onClick = { selMenuOpen = false; destRequest = DestRequest(DestOp.COPY, sel, side) })
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = { selMenuOpen = false; destRequest = DestRequest(DestOp.MOVE, sel, side) })
                                if (sel.size == 1) {
                                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { selMenuOpen = false; renameTarget = sel.first() })
                                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { selMenuOpen = false; detailsFor = sel.first() })
                                }
                                DropdownMenuItem(text = { Text("Copy paths") }, leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = {
                                    selMenuOpen = false; copyText(context, sel.joinToString("\n") { it.path }); snack("Paths copied")
                                })
                            }
                        )
                        searching -> SearchBar(
                            query = query,
                            folderName = folderName,
                            deep = deep,
                            onQuery = { query = it; if (deep) deepResults = null },
                            onBack = { searching = false; query = ""; deepResults = null; deep = false },
                            onDeep = { d ->
                                deep = d
                                deepResults = null
                                if (d && query.isNotBlank()) {
                                    scope.launch {
                                        deepLoading = true
                                        src().search(path, query.trim(), settings.showHidden)
                                            .onSuccess { deepResults = it.first; deepTruncated = it.second }
                                            .onFailure { snack(it.message ?: "Search failed") }
                                        deepLoading = false
                                    }
                                }
                            },
                            onSubmit = {
                                focusManager.clearFocus()
                                if (query.isNotBlank()) {
                                    deep = true
                                    scope.launch {
                                        deepLoading = true
                                        src().search(path, query.trim(), settings.showHidden)
                                            .onSuccess { deepResults = it.first; deepTruncated = it.second }
                                            .onFailure { snack(it.message ?: "Search failed") }
                                        deepLoading = false
                                    }
                                }
                            }
                        )
                        else -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (!goBack()) onClose() }) {
                                Icon(if (inDialog && history.isEmpty()) Icons.Outlined.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                                if (title != null) Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(folderName, fontSize = if (title != null) 18.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                            // phone-only mode: tabs on demand (server / PC mode always shows them)
                            if (multiTab && !phoneTab) {
                                IconButton(onClick = { setTabsOn(!tabMode) }) {
                                    Icon(Icons.Outlined.Tab, contentDescription = if (tabMode) "Turn off tabs" else "Open folders in tabs",
                                        tint = if (tabMode) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                }
                            }
                            IconButton(onClick = { ExplorerPrefs.update { it.copy(grid = !it.grid) } }) {
                                Icon(if (settings.grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, contentDescription = if (settings.grid) "List view" else "Grid view")
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = RoundedCornerShape(14.dp)) {
                                    DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { menuOpen = false; newIsDir = true })
                                    DropdownMenuItem(text = { Text("New file") }, leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) }, onClick = { menuOpen = false; newIsDir = false })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Outlined.SelectAll, null) }, onClick = { menuOpen = false; visible.forEach { selected[it.path] = it } })
                                    DropdownMenuItem(
                                        text = { Text("Show hidden files") },
                                        leadingIcon = { Icon(if (settings.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) },
                                        trailingIcon = { Checkbox(checked = settings.showHidden, onCheckedChange = null) },
                                        onClick = { ExplorerPrefs.update { it.copy(showHidden = !it.showHidden) } }
                                    )
                                    DropdownMenuItem(text = { Text("Sort & view…") }, leadingIcon = { Icon(Icons.Outlined.Sort, null) }, onClick = { menuOpen = false; viewOptionsOpen = true })
                                    DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { menuOpen = false; reload(silent = false) })
                                    HorizontalDivider()
                                    if (onOpenFolderInIde != null && side == ExplorerSide.BRIDGE) {
                                        DropdownMenuItem(text = { Text("Open this folder in IDE") }, leadingIcon = { Icon(Icons.Outlined.Code, null) }, onClick = { menuOpen = false; onOpenFolderInIde(path) })
                                    }
                                    DropdownMenuItem(text = { Text("Copy folder path") }, leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = { menuOpen = false; copyText(context, path); snack("Path copied") })
                                    DropdownMenuItem(text = { Text("Folder details") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = {
                                        menuOpen = false
                                        detailsFor = ExplorerEntry(name = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }, path = path, isDir = true, childCount = entries.size)
                                    })
                                }
                            }
                        }
                    }
                    if (showTabs && !selecting && !searching) {
                        FolderTabs(
                            tabs = tabs.map { t ->
                                val label = when {
                                    t.path == "~" -> if (t.side == ExplorerSide.PHONE) "Phone" else "Home"
                                    t.side == ExplorerSide.PHONE && t.path.trimEnd('/') == PhoneSource.home -> "Internal storage"
                                    else -> t.path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
                                }
                                FolderTab(t.id, label, t.side)
                            },
                            active = activeTab,
                            showSides = phoneTab,
                            bridgeName = bridgeTabName(),
                            onOpen = ::openTab,
                            onClose = ::closeTab,
                            onAdd = ::addTab
                        )
                    } else if (phoneTab && !multiTab && !selecting && !searching) {
                        SideTabs(side, bridgeTabName(), enabled = busy == null) { switchSide(it) }
                    }
                    if (!searching) {
                        BreadcrumbBar(
                            crumbs = crumbs,
                            onPlaces = {
                                placesOpen = true
                                scope.launch { roots = src().roots().second }
                            },
                            onCrumb = { c -> if (c.path != path) go(c.path) },
                            onCopyPath = { copyText(context, path); snack("Path copied") }
                        )
                    }
                    if (busy != null || loading || deepLoading) {
                        val p = busyProgress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(2.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    } else {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                AnimatedVisibility(busy != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                (busy ?: "") + (busyProgress?.let { "  ${(it * 100).toInt()}%" } ?: ""),
                                color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (showClipboard && ExplorerClipboard.entries.isNotEmpty() && !selecting) {
                    // files from the other tab can be pasted here only where that tab exists (and not into a picker)
                    val clipSide = ExplorerClipboard.side
                    val pasteable = clipSide == side || phoneTab
                    ClipboardBar(
                        count = ExplorerClipboard.entries.size,
                        firstName = ExplorerClipboard.entries.first().name,
                        move = ExplorerClipboard.move,
                        enabled = busy == null && loaded && pasteable,
                        fromLabel = if (clipSide == side) null else if (clipSide == ExplorerSide.PHONE) "from this phone" else "from ${bridgeTabName()}",
                        onPaste = { transfer(ExplorerClipboard.entries.toList(), path, ExplorerClipboard.move, null, from = clipSide, to = side) },
                        onCancel = { ExplorerClipboard.clear() }
                    )
                }
                if (onSave != null && !selecting) {
                    SaveBar(
                        name = saveName,
                        folderName = folderName,
                        onName = { saveName = it },
                        enabled = loaded && saveName.isNotBlank() && !saveName.contains('/'),
                        onSave = {
                            val n = saveName.trim()
                            val folder = path
                            // checked now, not against the listing from when the folder was opened (a file may have
                            // been created there since: an earlier save, the agent, another app)
                            scope.launch {
                                val names = src().list(folder).getOrNull()?.entries?.map { it.name }?.toSet() ?: existingNames
                                if (n in names) confirmOverwrite = n else onSave(folder, n)
                            }
                        }
                    )
                } else if (onConfirmFolder != null && !selecting) {
                    ConfirmFolderBar(folderName = folderName, path = path, label = confirmLabel, enabled = loaded && busy == null) {
                        if (onConfirmFolderAt != null) onConfirmFolderAt(path, side) else onConfirmFolder(path)
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting && !searching && loaded, enter = fadeIn(), exit = fadeOut()) {
                FloatingActionButton(
                    onClick = { createOpen = true },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Outlined.Add, contentDescription = "Create") }
            }
        }
    ) { padding ->
        CompositionLocalProvider(LocalExplorerSide provides side) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            // the phone tab without storage access: what to do, and a reload once it is allowed
            var storageOk by remember(side) { mutableStateOf(side != ExplorerSide.PHONE || com.example.gemini.data.local.TermuxStorageSetupManager.hasStoragePermission(context)) }
            if (!storageOk) {
                LaunchedEffect(Unit) {
                    while (!storageOk) {
                        kotlinx.coroutines.delay(1500)
                        if (com.example.gemini.data.local.TermuxStorageSetupManager.hasStoragePermission(context)) { storageOk = true; reload(silent = false) }
                    }
                }
            }
            when {
                !storageOk && (!loaded || entries.isEmpty()) -> EmptyState(
                    Icons.Outlined.PhoneAndroid, "Allow access to this phone's files",
                    "AntiGem needs \"All files access\" to show and copy this phone's storage."
                ) {
                    Button(onClick = { com.example.gemini.data.local.TermuxStorageSetupManager.requestStoragePermission(context) }) { Text("Allow") }
                }
                !loaded && loadError == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !loaded -> EmptyState(Icons.Outlined.CloudOff, "Files are not available", loadError ?: "") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { scope.launch { loadError = null; load(path, null) } }) { Text("Retry") }
                        OutlinedButton(onClick = { scope.launch { loadError = null; load("~", null) } }) { Text("Go home") }
                    }
                }
                searching && deep && deepLoading && deepResults == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Searching all subfolders of $folderName…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                searching && visible.isEmpty() && query.isNotBlank() -> EmptyState(
                    Icons.Outlined.SearchOff, "No matches",
                    if (deep) "Nothing named like \"$query\" below $folderName." else "Nothing in this folder. Press search on the keyboard to look in all subfolders."
                )
                visible.isEmpty() && !searching -> EmptyState(
                    Icons.Outlined.FolderOpen,
                    if (entries.isEmpty()) "This folder is empty" else "Only hidden files here",
                    if (entries.isEmpty()) "Tap + to create a file or folder." else "Turn on \"Show hidden files\" in the menu to see them."
                )
                else -> Box(Modifier.fillMaxSize()) {
                    // iOS bounce at both ends, also when the folder fits on screen. Pull-to-refresh rides on it:
                    // pulling down past the top only bounces, until the pull passes refreshPullPx; letting go then refreshes.
                    val listBounce = com.example.gemini.ui.components.rememberBouncyOverscrollEffect()
                    val refreshPullPx = with(LocalDensity.current) { 72.dp.toPx() }
                    val currentRefreshing by rememberUpdatedState(refreshing)
                    val currentReload by rememberUpdatedState { reload(silent = false) }
                    DisposableEffect(listBounce, refreshPullPx) {
                        listBounce.onPullReleased = { dy -> if (dy >= refreshPullPx && !currentRefreshing) currentReload() }
                        onDispose { listBounce.onPullReleased = null }
                    }
                    val pullArmed by remember(listBounce, refreshPullPx) { derivedStateOf { listBounce.offsetY >= refreshPullPx } }
                    val haptics = LocalHapticFeedback.current
                    LaunchedEffect(pullArmed) { if (pullArmed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

                    val folders = visible.count { it.isDir }
                    val files = visible.size - folders
                    val summary = buildString {
                        append(if (folders == 1) "1 folder" else "$folders folders")
                        append("  ·  ")
                        append(if (files == 1) "1 file" else "$files files")
                        val bytes = visible.filter { !it.isDir }.sumOf { it.size }
                        if (bytes > 0) append("  ·  ${FileKinds.formatSize(bytes)}")
                        if (searching && deep && deepTruncated) append("  ·  more results not shown")
                    }
                    val rowClick: (ExplorerEntry) -> Unit = { e -> if (selecting) toggle(e) else openEntry(e) }
                    val rowLong: (ExplorerEntry) -> Unit = { e -> toggle(e) }
                    if (settings.grid) {
                        val gridState = rememberLazyGridState()
                        val gridCanScroll by remember(gridState) { derivedStateOf { gridState.canScrollForward || gridState.canScrollBackward } }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(108.dp),
                            state = gridState,
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 120.dp),
                            modifier = Modifier.fillMaxSize().bounceWhenNotScrollable(!gridCanScroll, listBounce),
                            userScrollEnabled = gridCanScroll,
                            overscrollEffect = listBounce
                        ) {
                            // one continuous grid: folders come first through the sort order, no section headers
                            items(visible, key = { it.path }) { e ->
                                EntryTile(
                                    e, selected.containsKey(e.path), selecting, settings.thumbnails,
                                    dimmed = pickingFolder && !e.isDir, cut = e.path in cutPaths,
                                    onClick = { rowClick(e) }, onLongClick = { rowLong(e) }
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "summary") { SummaryFooter(summary) }
                        }
                    } else {
                        val rowsState = rememberLazyListState()
                        val rowsCanScroll by remember(rowsState) { derivedStateOf { rowsState.canScrollForward || rowsState.canScrollBackward } }
                        LazyColumn(
                            Modifier.fillMaxSize().bounceWhenNotScrollable(!rowsCanScroll, listBounce),
                            state = rowsState,
                            contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                            userScrollEnabled = rowsCanScroll,
                            overscrollEffect = listBounce
                        ) {
                            // one continuous list: folders come first through the sort order, no section headers
                            items(visible, key = { it.path }) { e ->
                                EntryRow(
                                    e, selected.containsKey(e.path), selecting, settings.thumbnails,
                                    dimmed = pickingFolder && !e.isDir, cut = e.path in cutPaths,
                                    subtitleOverride = if (searching && deep) e.path.removePrefix(path).trim('/').substringBeforeLast('/', "").let { rel -> if (rel.isBlank()) entrySubtitle(e) else "in $rel" } else null,
                                    onClick = { rowClick(e) }, onLongClick = { rowLong(e) }, onMore = { actionsFor = e }
                                )
                            }
                            item(key = "summary") { SummaryFooter(summary) }
                        }
                    }

                    // Refresh indicator: sits in the gap the pull opens above the list, fills as the pull nears the
                    // threshold (haptic tick when reached), and spins at the top while refreshing.
                    if (refreshing || listBounce.offsetY > 1f) {
                        val pullFraction = (listBounce.offsetY / refreshPullPx).coerceIn(0f, 1f)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .graphicsLayer {
                                    val size = 36.dp.toPx()
                                    val centered = (listBounce.offsetY - size) / 2f
                                    translationY = if (refreshing) maxOf(12.dp.toPx(), centered) else centered
                                    val shown = if (refreshing) 1f else pullFraction
                                    alpha = shown
                                    scaleX = 0.6f + 0.4f * shown
                                    scaleY = 0.6f + 0.4f * shown
                                }
                                .size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (refreshing) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                } else {
                                    CircularProgressIndicator(
                                        progress = { pullFraction },
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.5.dp,
                                        color = if (pullArmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    // ---------------- sheets & dialogs ----------------

    if (placesOpen) {
        PlacesSheet(
            roots = roots, projects = projects, recent = ExplorerPrefs.recentFolders(), currentPath = path,
            onPick = { placesOpen = false; go(it) }, onDismiss = { placesOpen = false }
        )
    }

    if (viewOptionsOpen) {
        var remembered by remember { mutableIntStateOf(ExplorerPrefs.rememberedExtensions().size) }
        ViewOptionsSheet(
            settings = settings,
            onChange = { ExplorerPrefs.update(it) },
            rememberedCount = remembered,
            onForgetChoices = { ExplorerPrefs.forgetChoices(); remembered = 0; snack("\"Always open with\" choices cleared") },
            onDismiss = { viewOptionsOpen = false }
        )
    }

    if (createOpen) {
        CreateSheet(
            allowProject = onProjectCreated != null && side == ExplorerSide.BRIDGE,
            onFolder = { createOpen = false; newIsDir = true },
            onFile = { createOpen = false; newIsDir = false },
            onProject = { createOpen = false; newProjectOpen = true },
            onDismiss = { createOpen = false }
        )
    }

    newIsDir?.let { isDir ->
        NameDialog(
            title = if (isDir) "New folder" else "New file",
            icon = if (isDir) Icons.Outlined.CreateNewFolder else Icons.Outlined.NoteAdd,
            initial = "",
            confirmLabel = "Create",
            selectStem = false,
            existingNames = existingNames,
            helper = if (isDir) "Created in $folderName" else "Include the extension, e.g. main.py",
            onConfirm = { name ->
                val r = src().create(path, name, isDir)
                if (r.ok) {
                    newIsDir = null
                    reload()
                    if (isDir) {
                        snack("Created folder $name")
                    } else if (ideAvailable && onSave == null && onPickFile == null) {
                        scope.launch {
                            val res = snackbar.showSnackbar("Created $name", actionLabel = "Edit", withDismissAction = true)
                            if (res == SnackbarResult.ActionPerformed) openInIde(ExplorerEntry(name, r.path ?: "$path/$name", false))
                        }
                    } else snack("Created $name")
                    null
                } else r.error
            },
            onDismiss = { newIsDir = null }
        )
    }

    if (newProjectOpen && onProjectCreated != null) {
        NewProjectDialog(
            onCreate = { name, template ->
                val created = IdeApiClient.createProject(name = name, template = template, path = path)
                if (created != null) {
                    newProjectOpen = false
                    onProjectCreated(created)
                    null
                } else "Could not create the project (does it already exist?)"
            },
            onDismiss = { newProjectOpen = false }
        )
    }

    actionsFor?.let { e ->
        val close = { actionsFor = null }
        EntryActionsSheet(
            entry = e,
            caps = ActionCaps(ide = ideAvailable, openFolderInIde = onOpenFolderInIde != null && side == ExplorerSide.BRIDGE, pickFolder = onConfirmFolder != null),
            onDismiss = close,
            onOpen = { close(); openEntry(e) },
            onOpenWith = { close(); openWithFor = e },
            onOpenInIde = { close(); openInIde(e) },
            onOpenFolderInIde = { close(); onOpenFolderInIde?.invoke(e.path) },
            onShare = { close(); shareEntries(listOf(e)) },
            onCopy = { close(); ExplorerClipboard.set(listOf(e), move = false, side); snack(if (phoneTab) "Copied. Open a folder (either tab) and tap Paste." else "Copied. Open a folder and tap Paste.") },
            onCut = { close(); ExplorerClipboard.set(listOf(e), move = true, side); snack(if (phoneTab) "Cut. Open a folder (either tab) and tap Paste." else "Cut. Open a folder and tap Paste.") },
            onCopyTo = { close(); destRequest = DestRequest(DestOp.COPY, listOf(e), side) },
            onMoveTo = { close(); destRequest = DestRequest(DestOp.MOVE, listOf(e), side) },
            onRename = { close(); renameTarget = e },
            onDuplicate = { close(); transfer(listOf(e), parentOf(e.path), move = false, policy = ConflictPolicy.RENAME) },
            onCompress = { close(); compressTargets = listOf(e) },
            onExtractHere = { close(); extract(e, null) },
            onExtractTo = { close(); destRequest = DestRequest(DestOp.EXTRACT, listOf(e)) },
            onViewArchive = { close(); archiveFor = e },
            onCopyPath = { close(); copyText(context, e.path); snack("Path copied") },
            onDetails = { close(); detailsFor = e },
            onDelete = { close(); deleteTargets = listOf(e) }
        )
    }

    imageView?.let { e ->
        com.example.gemini.ui.components.FullScreenImageDialog(imageUrl = e.path, title = e.name, onDismiss = { imageView = null })
    }

    openWithFor?.let { e ->
        OpenWithSheet(
            entry = e,
            ideAvailable = ideAvailable,
            onChoose = { choice, rememberIt ->
                openWithFor = null
                if (rememberIt) {
                    ExplorerPrefs.rememberChoice(FileKinds.extOf(e.name), choice)
                    snack(".${FileKinds.extOf(e.name)} files now open with: ${choice.label}. Change it in Sort & view.")
                }
                perform(e, choice)
            },
            onDismiss = { openWithFor = null }
        )
    }

    deleteTargets?.let { targets ->
        DeleteConfirmDialog(
            entries = targets,
            onDismiss = { deleteTargets = null },
            onConfirm = {
                deleteTargets = null
                val noun = if (targets.size == 1) "\"${targets.first().name}\"" else "${targets.size} items"
                runOp("Deleting $noun…", { src().delete(targets.map { it.path }) }) { r ->
                    // close editor tabs of deleted files, including files inside deleted folders
                    val gone = TermuxDaemonManager.openTabs.value.map { it.path }
                        .filter { tab -> targets.any { t -> tab == t.path || (t.isDir && tab.startsWith(t.path.trimEnd('/') + "/")) } }
                    gone.forEach { TermuxDaemonManager.closeTab(it) }
                    ExplorerClipboard.entries.removeAll { c -> targets.any { c.path == it.path || c.path.startsWith(it.path + "/") } }
                    if (r.ok) snack("Deleted $noun")
                    else if (r.count > 0) snack("Deleted ${r.count}, ${r.failed} failed: ${r.error}")
                }
            }
        )
    }

    renameTarget?.let { e ->
        NameDialog(
            title = "Rename",
            icon = Icons.Outlined.DriveFileRenameOutline,
            initial = e.name,
            confirmLabel = "Rename",
            selectStem = !e.isDir,
            existingNames = existingNames,
            onConfirm = { name ->
                if (name == e.name) { renameTarget = null; null } else {
                    val r = src().rename(e.path, name)
                    if (r.ok) {
                        renameTarget = null
                        val newPath = r.path ?: "${parentOf(e.path)}/$name"
                        if (!e.isDir) TermuxDaemonManager.renameTab(e.path, newPath, name)
                        reload()
                        snack("Renamed to $name")
                        null
                    } else r.error
                }
            },
            onDismiss = { renameTarget = null }
        )
    }

    compressTargets?.let { targets ->
        val suggested = if (targets.size == 1) targets.first().name.substringBeforeLast('.').ifBlank { targets.first().name } + ".zip" else "$folderName.zip"
        NameDialog(
            title = if (targets.size == 1) "Compress \"${targets.first().name}\"" else "Compress ${targets.size} items",
            icon = Icons.Outlined.FolderZip,
            initial = suggested,
            confirmLabel = "Compress",
            selectStem = true,
            existingNames = emptySet(),
            helper = "Saved in $folderName (renamed if the name is taken)",
            onConfirm = { name ->
                compressTargets = null
                runOp("Compressing…", { src().compress(targets.map { it.path }, "$path/$name") }) { r ->
                    if (r.ok) snack("Created ${r.path?.substringAfterLast('/') ?: name} (${r.count} files)")
                }
                null
            },
            onDismiss = { compressTargets = null }
        )
    }

    detailsFor?.let { e ->
        DetailsSheet(entry = e, onCopyPath = { copyText(context, it); snack("Path copied") }, onDismiss = { detailsFor = null }, side = side)
    }

    archiveFor?.let { e ->
        ArchiveSheet(
            entry = e,
            onExtractHere = { archiveFor = null; extract(e, null) },
            onExtractTo = { archiveFor = null; destRequest = DestRequest(DestOp.EXTRACT, listOf(e)) },
            onDismiss = { archiveFor = null },
            side = side
        )
    }

    conflict?.let { c ->
        ConflictDialog(names = c.names, move = c.move, onChoose = c.run, onDismiss = { conflict = null })
    }

    notText?.let { (e, mime) ->
        NotTextDialog(
            name = e.name, mime = mime,
            onOpenAnyway = { notText = null; openInIde(e, force = true) },
            onOpenElsewhere = { notText = null; openExternal(e) },
            onDismiss = { notText = null }
        )
    }

    markdownDoc?.let { (docPath, content) ->
        MarkdownDocViewerModal(
            filePath = docPath,
            content = content,
            onDismiss = { markdownDoc = null },
            onOpenInIde = { p ->
                markdownDoc = null
                if (ideAvailable) openInIde(ExplorerEntry(p.substringAfterLast('/'), p, false, size = content.length.toLong()))
            }
        )
    }

    confirmOverwrite?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmOverwrite = null },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Replace \"$name\"?", fontWeight = FontWeight.SemiBold) },
            text = { Text("A file with this name already exists in $folderName. Saving replaces its contents.") },
            confirmButton = {
                TextButton(onClick = { confirmOverwrite = null; onSave?.invoke(path, name) }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = null }) { Text("Cancel") } }
        )
    }

    destRequest?.let { req ->
        val n = req.entries.size
        val noun = if (n == 1) "\"${req.entries.first().name}\"" else "$n items"
        FileExplorerDialog(
            stateKey = "destination",
            initialPath = path,
            // copy / move: the other tab is a destination too
            allowPhoneTab = phoneTab && req.op != DestOp.EXTRACT,
            initialSide = req.side,
            title = when (req.op) {
                DestOp.COPY -> "Copy $noun to"
                DestOp.MOVE -> "Move $noun to"
                DestOp.EXTRACT -> "Extract $noun to"
            },
            confirmLabel = when (req.op) {
                DestOp.COPY -> "Copy here"
                DestOp.MOVE -> "Move here"
                DestOp.EXTRACT -> "Extract here"
            },
            showClipboard = false,
            onConfirmFolder = {},
            onConfirmFolderAt = { dest, destSide ->
                destRequest = null
                when (req.op) {
                    DestOp.COPY -> transfer(req.entries, dest, move = false, policy = null, from = req.side, to = destSide)
                    DestOp.MOVE -> transfer(req.entries, dest, move = true, policy = null, from = req.side, to = destSide)
                    DestOp.EXTRACT -> extract(req.entries.first(), dest)
                }
            },
            onDismiss = { destRequest = null }
        )
    }
}

/** [FileExplorer] as a full-screen dialog, for pickers opened from other screens. */
@Composable
fun FileExplorerDialog(
    stateKey: String,
    onDismiss: () -> Unit,
    initialPath: String? = null,
    title: String? = null,
    confirmLabel: String = "Choose this folder",
    onConfirmFolder: ((String) -> Unit)? = null,
    onPickFile: ((ExplorerEntry) -> Unit)? = null,
    onPickFiles: ((List<ExplorerEntry>) -> Unit)? = null,
    pickFilesLabel: String = "Pick",
    saveFileName: String? = null,
    onSave: ((folder: String, name: String) -> Unit)? = null,
    onOpenedInIde: (() -> Unit)? = null,
    onOpenFolderInIde: ((String) -> Unit)? = null,
    onProjectCreated: ((ProjectItem) -> Unit)? = null,
    showClipboard: Boolean = true,
    allowPhoneTab: Boolean = false,
    initialSide: ExplorerSide = ExplorerSide.BRIDGE,
    onConfirmFolderAt: ((String, ExplorerSide) -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FileExplorer(
                stateKey = stateKey,
                onClose = onDismiss,
                initialPath = initialPath,
                inDialog = true,
                title = title,
                confirmLabel = confirmLabel,
                onConfirmFolder = onConfirmFolder,
                onPickFile = onPickFile,
                onPickFiles = onPickFiles,
                pickFilesLabel = pickFilesLabel,
                saveFileName = saveFileName,
                onSave = onSave,
                onOpenedInIde = onOpenedInIde,
                onOpenFolderInIde = onOpenFolderInIde,
                onProjectCreated = onProjectCreated,
                showClipboard = showClipboard,
                allowPhoneTab = allowPhoneTab,
                initialSide = initialSide,
                onConfirmFolderAt = onConfirmFolderAt
            )
        }
    }
}

// ---------------- bars ----------------

/** Picking several files: what is selected and the button that picks them. */
@Composable
private fun PickSelectionBar(
    count: Int,
    label: String,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onPick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
        Text("$count selected", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, contentDescription = "Select all files") }
        Button(onClick = onPick, enabled = count > 0, modifier = Modifier.padding(end = 8.dp)) { Text("$label ($count)") }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    menu: @Composable ColumnScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
        Text("$count selected", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onSelectAll) { Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, contentDescription = "Select all") }
        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy") }
        IconButton(onClick = onCut) { Icon(Icons.Outlined.ContentCut, contentDescription = "Cut") }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, shape = RoundedCornerShape(14.dp), content = menu)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    folderName: String,
    deep: Boolean,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onDeep: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search in $folderName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear") } }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus)
            )
        }
        Row(Modifier.padding(start = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !deep, onClick = { onDeep(false) }, label = { Text("This folder") })
            FilterChip(selected = deep, onClick = { onDeep(true) }, label = { Text("All subfolders") }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun ClipboardBar(count: Int, firstName: String, move: Boolean, enabled: Boolean, fromLabel: String? = null, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (move) Icons.Outlined.ContentCut else Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${if (move) "Move" else "Copy"} ${if (count == 1) "\"$firstName\"" else "$count items"}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.MiddleEllipsis
                )
                Text(
                    if (fromLabel != null) "${fromLabel.replaceFirstChar { it.uppercase() }} · open the destination, then paste" else "Open the destination, then paste",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onPaste, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Paste")
            }
        }
    }
}

@Composable
private fun ConfirmFolderBar(folderName: String, path: String, label: String, enabled: Boolean, onConfirm: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(folderName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(path, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onConfirm, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SaveBar(name: String, folderName: String, onName: (String) -> Unit, enabled: Boolean, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Save in $folderName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    singleLine = true,
                    placeholder = { Text("File name") },
                    leadingIcon = { Icon(Icons.Outlined.Description, null) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (enabled) onSave() }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = onSave, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SummaryFooter(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

/** The two tabs while the bridge is on another machine: its files, and this phone's. */
@Composable
private fun SideTabs(side: ExplorerSide, bridgeName: String, enabled: Boolean, onSide: (ExplorerSide) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun Tab(s: ExplorerSide, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
            val on = s == side
            Surface(
                onClick = { onSide(s) },
                enabled = enabled || on,
                shape = RoundedCornerShape(12.dp),
                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = modifier.height(38.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Tab(ExplorerSide.BRIDGE, bridgeName, Icons.Outlined.Computer, Modifier.weight(1f))
        Tab(ExplorerSide.PHONE, "This phone", Icons.Outlined.PhoneAndroid, Modifier.weight(1f))
    }
}

private data class FolderTab(val id: Long, val label: String, val side: ExplorerSide)

/**
 * The Files screen's tabs: one folder each (tap to show it, × to close, + for a new one; with this phone and the bridge's
 * machine both there, + asks which).
 */
@Composable
private fun FolderTabs(
    tabs: List<FolderTab>,
    active: Int,
    showSides: Boolean,
    bridgeName: String,
    onOpen: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: (ExplorerSide) -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(active, tabs.size) { if (active in tabs.indices) listState.animateScrollToItem(active) }
    var addMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.lazy.LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(tabs.size, key = { tabs[it].id }) { i ->
                val t = tabs[i]
                val on = i == active
                Surface(
                    onClick = { onOpen(i) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(Modifier.padding(start = 10.dp, end = if (tabs.size > 1) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                showSides && t.side == ExplorerSide.PHONE -> Icons.Outlined.PhoneAndroid
                                showSides -> Icons.Outlined.Computer
                                else -> Icons.Outlined.Folder
                            },
                            null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp)
                        )
                        if (tabs.size > 1) {
                            IconButton(onClick = { onClose(i) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close tab", modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { if (showSides) addMenu = true else onAdd(ExplorerSide.BRIDGE) }) {
                Icon(Icons.Outlined.Add, contentDescription = "New tab")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, shape = RoundedCornerShape(14.dp)) {
                DropdownMenuItem(text = { Text("New tab on $bridgeName") }, leadingIcon = { Icon(Icons.Outlined.Computer, null) },
                    onClick = { addMenu = false; onAdd(ExplorerSide.BRIDGE) })
                DropdownMenuItem(text = { Text("New tab on this phone") }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) },
                    onClick = { addMenu = false; onAdd(ExplorerSide.PHONE) })
            }
        }
    }
}
