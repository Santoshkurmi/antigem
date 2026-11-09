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

    fun set(list: List<ExplorerEntry>, move: Boolean) {
        entries.clear()
        entries.addAll(list)
        this.move = move
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

private data class DestRequest(val op: DestOp, val entries: List<ExplorerEntry>)

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
    showClipboard: Boolean = true
) {
    val context = LocalContext.current
    remember { ExplorerPrefs.init(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val settings by ExplorerPrefs.settings.collectAsState()
    val projects by TermuxDaemonManager.projects.collectAsState()
    val ideAvailable = onOpenedInIde != null

    // ----- listing state -----
    var path by remember { mutableStateOf(initialPath?.takeIf { it.isNotBlank() } ?: ExplorerPrefs.lastPath(stateKey) ?: "~") }
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
    var confirmOverwrite by remember { mutableStateOf<String?>(null) }

    fun snack(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Short)
        }
    }

    suspend fun load(target: String, pushFrom: String?): Boolean {
        loading = true
        val res = FileExplorerApi.list(target)
        loading = false
        res.onSuccess { l ->
            if (pushFrom != null && pushFrom != l.path) history.add(pushFrom)
            path = l.path
            home = l.home
            entries = l.entries
            loadError = null
            loaded = true
            selected.clear()
            ExplorerPrefs.setLastPath(stateKey, l.path)
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
            FileExplorerApi.list(path).onSuccess { l ->
                entries = l.entries
                home = l.home
                loadError = null
                loaded = true
                selected.keys.retainAll(keep.intersect(l.entries.map { it.path }.toSet()))
            }.onFailure { if (!silent) snack(it.message ?: "Refresh failed") }
            refreshing = false
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
        if (!loaded) path = target
        if (!load(target, pushFrom = if (loaded) path else null)) snack("Cannot open $target")
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
            val uri = FileOpener.uriFor(context, e.path, e.size) { busyProgress = it }
            busy = null
            uri.onSuccess { FileOpener.view(context, it, e.name, chooser)?.let(::snack) }
                .onFailure { snack(it.message ?: "Could not open ${e.name}") }
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
        } else if (e.kind == FileKind.MARKDOWN) {
            scope.launch {
                busy = "Opening ${e.name}…"
                val content = IdeApiClient.readFile(e.path)
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

    fun transfer(items: List<ExplorerEntry>, dest: String, move: Boolean, policy: ConflictPolicy?) {
        if (items.isEmpty()) return
        scope.launch {
            if (policy == null) {
                val destNames = if (dest == path) entries.map { it.name }.toSet()
                else FileExplorerApi.list(dest).getOrNull()?.entries?.map { it.name }?.toSet().orEmpty()
                val clashes = items.filter { it.name in destNames && parentOf(it.path) != dest.trimEnd('/') }
                if (clashes.isNotEmpty()) {
                    conflict = PendingConflict(clashes.map { it.name }, move) { p -> conflict = null; transfer(items, dest, move, p) }
                    return@launch
                }
            }
            val n = items.size
            val noun = if (n == 1) "\"${items.first().name}\"" else "$n items"
            runOp(if (move) "Moving $noun…" else "Copying $noun…", {
                FileExplorerApi.transfer(items.map { it.path }, dest, move, policy ?: ConflictPolicy.RENAME)
            }) { r ->
                if (r.ok) {
                    if (ExplorerClipboard.entries.map { it.path }.toSet() == items.map { it.path }.toSet()) ExplorerClipboard.clear()
                    val where = if (dest == path) "here" else "to ${dest.trimEnd('/').substringAfterLast('/')}"
                    snack("${if (move) "Moved" else "Copied"} $noun $where")
                }
            }
        }
    }

    fun shareEntries(items: List<ExplorerEntry>) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) { snack("Folders cannot be shared. Compress them to ZIP first."); return }
        scope.launch {
            busy = "Preparing to share…"
            val uris = files.mapNotNull { f -> FileOpener.uriFor(context, f.path, f.size).getOrNull()?.let { it to f.name } }
            busy = null
            if (uris.isEmpty()) snack("Could not prepare files to share") else FileOpener.share(context, uris)?.let(::snack)
        }
    }

    fun extract(e: ExplorerEntry, targetDir: String?) {
        runOp("Extracting ${e.name}…", { FileExplorerApi.extract(e.path, targetDir) }) { r ->
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
                            onCopy = { ExplorerClipboard.set(selected.values.toList(), move = false); selected.clear(); snack("Copied. Open a folder and tap Paste.") },
                            onCut = { ExplorerClipboard.set(selected.values.toList(), move = true); selected.clear(); snack("Cut. Open a folder and tap Paste.") },
                            onDelete = { deleteTargets = selected.values.toList() },
                            menuOpen = selMenuOpen,
                            onMenu = { selMenuOpen = it },
                            menu = {
                                val sel = selected.values.toList()
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { selMenuOpen = false; shareEntries(sel) })
                                DropdownMenuItem(text = { Text("Compress to ZIP") }, leadingIcon = { Icon(Icons.Outlined.FolderZip, null) }, onClick = { selMenuOpen = false; compressTargets = sel })
                                DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }, onClick = { selMenuOpen = false; destRequest = DestRequest(DestOp.COPY, sel) })
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) }, onClick = { selMenuOpen = false; destRequest = DestRequest(DestOp.MOVE, sel) })
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
                                        FileExplorerApi.search(path, query.trim(), settings.showHidden)
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
                                        FileExplorerApi.search(path, query.trim(), settings.showHidden)
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
                                    if (onOpenFolderInIde != null) {
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
                    if (!searching) {
                        BreadcrumbBar(
                            crumbs = crumbs,
                            onPlaces = {
                                placesOpen = true
                                scope.launch { roots = FileExplorerApi.roots().second }
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
                    ClipboardBar(
                        count = ExplorerClipboard.entries.size,
                        firstName = ExplorerClipboard.entries.first().name,
                        move = ExplorerClipboard.move,
                        enabled = busy == null && loaded,
                        onPaste = { transfer(ExplorerClipboard.entries.toList(), path, ExplorerClipboard.move, null) },
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
                                val names = FileExplorerApi.list(folder).getOrNull()?.entries?.map { it.name }?.toSet() ?: existingNames
                                if (n in names) confirmOverwrite = n else onSave(folder, n)
                            }
                        }
                    )
                } else if (onConfirmFolder != null && !selecting) {
                    ConfirmFolderBar(folderName = folderName, path = path, label = confirmLabel, enabled = loaded && busy == null) { onConfirmFolder(path) }
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
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
            allowProject = onProjectCreated != null,
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
                val r = FileExplorerApi.create(path, name, isDir)
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
            caps = ActionCaps(ide = ideAvailable, openFolderInIde = onOpenFolderInIde != null, pickFolder = onConfirmFolder != null),
            onDismiss = close,
            onOpen = { close(); openEntry(e) },
            onOpenWith = { close(); openWithFor = e },
            onOpenInIde = { close(); openInIde(e) },
            onOpenFolderInIde = { close(); onOpenFolderInIde?.invoke(e.path) },
            onShare = { close(); shareEntries(listOf(e)) },
            onCopy = { close(); ExplorerClipboard.set(listOf(e), move = false); snack("Copied. Open a folder and tap Paste.") },
            onCut = { close(); ExplorerClipboard.set(listOf(e), move = true); snack("Cut. Open a folder and tap Paste.") },
            onCopyTo = { close(); destRequest = DestRequest(DestOp.COPY, listOf(e)) },
            onMoveTo = { close(); destRequest = DestRequest(DestOp.MOVE, listOf(e)) },
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
                runOp("Deleting $noun…", { FileExplorerApi.delete(targets.map { it.path }) }) { r ->
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
                    val r = FileExplorerApi.rename(e.path, name)
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
                runOp("Compressing…", { FileExplorerApi.compress(targets.map { it.path }, "$path/$name") }) { r ->
                    if (r.ok) snack("Created ${r.path?.substringAfterLast('/') ?: name} (${r.count} files)")
                }
                null
            },
            onDismiss = { compressTargets = null }
        )
    }

    detailsFor?.let { e ->
        DetailsSheet(entry = e, onCopyPath = { copyText(context, it); snack("Path copied") }, onDismiss = { detailsFor = null })
    }

    archiveFor?.let { e ->
        ArchiveSheet(
            entry = e,
            onExtractHere = { archiveFor = null; extract(e, null) },
            onExtractTo = { archiveFor = null; destRequest = DestRequest(DestOp.EXTRACT, listOf(e)) },
            onDismiss = { archiveFor = null }
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
            onConfirmFolder = { dest ->
                destRequest = null
                when (req.op) {
                    DestOp.COPY -> transfer(req.entries, dest, move = false, policy = null)
                    DestOp.MOVE -> transfer(req.entries, dest, move = true, policy = null)
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
    showClipboard: Boolean = true
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
                showClipboard = showClipboard
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
private fun ClipboardBar(count: Int, firstName: String, move: Boolean, enabled: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
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
                Text("Open the destination, then paste", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
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
