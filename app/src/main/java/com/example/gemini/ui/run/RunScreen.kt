package com.example.gemini.ui.run

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.data.ide.RunConfigManager
import com.example.gemini.data.ide.RunConfiguration
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.run.RunEntry
import com.example.gemini.data.run.RunManager
import com.example.gemini.data.run.RunPanel
import com.example.gemini.data.run.RunStatus
import com.example.gemini.data.run.RunTemplate
import com.example.gemini.data.run.RunTemplateGroup
import com.example.gemini.data.run.RunTemplates
import com.example.gemini.theme.AppColors
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.ui.components.WorkspaceFolderBrowserDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.roundToInt

/** The last folder the Run panel worked in, and the recent ones, kept across app starts. */
private class RunPanelPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("antigem_run_panel", Context.MODE_PRIVATE)
    var folder: String?
        get() = prefs.getString("folder", null)
        set(value) { prefs.edit().putString("folder", value).apply() }
    val recent: List<String>
        get() = runCatching { JSONArray(prefs.getString("recent", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())

    fun remember(folder: String) {
        val list = (listOf(folder) + recent.filter { it != folder }).take(8)
        prefs.edit().putString("folder", folder).putString("recent", JSONArray(list).toString()).apply()
    }
}

private enum class CommandFilter(val label: String) { PROJECT("Project"), GLOBAL("Global"), SUGGESTED("Suggested") }

/**
 * The Run screen, one of the app's screens like Chat, IDE and Terminal (floating switcher → Run): a folder's commands,
 * and the live terminal of each run. Runs keep going when another screen is in front. [onClose] goes back to the
 * previous screen; [onSendToChat] switches to the chat after a run's output was put in its message box.
 */
@Composable
fun RunScreen(isVisible: Boolean, onClose: () -> Unit, onSendToChat: () -> Unit) {
    val runs by RunManager.runs.collectAsState()
    BackHandler(enabled = isVisible) { onClose() }
    // results that finish while the screen is in front are seen already (no bubble for them later)
    LaunchedEffect(isVisible, runs) { if (isVisible) RunPanel.markSeen() }
    RunScreenContent(runs = runs, onClose = onClose, onSendToChat = onSendToChat)
}

/**
 * Above every screen: while a command runs the screen stays on, and while another screen is in front a bubble shows
 * the runs (spinner, then ✓ / ✗ until looked at); tapping it opens the Run screen.
 */
@Composable
fun RunBubbleHost(isRunScreen: Boolean) {
    val runs by RunManager.runs.collectAsState()
    val seen by RunPanel.seen.collectAsState()
    val anyRunning = runs.any { it.status == RunStatus.RUNNING }
    // keepScreenOn on any view of the window keeps it on, without touching the flag other screens set
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { android.view.View(it) },
        update = { it.keepScreenOn = anyRunning },
        modifier = Modifier.size(1.dp)
    )
    val showBubble = !isRunScreen && runs.any { it.status == RunStatus.RUNNING || RunPanel.key(it) !in seen }
    if (showBubble) RunBubble(runs, seen)
}

@Composable
private fun RunScreenContent(runs: List<RunEntry>, onClose: () -> Unit, onSendToChat: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { RunPanelPrefs(context) }
    val activeProject by TermuxDaemonManager.activeProject.collectAsState()
    val ideProjects by TermuxDaemonManager.projects.collectAsState()

    var folder by remember { mutableStateOf(prefs.folder ?: activeProject?.path ?: "") }
    var filter by remember { mutableStateOf(CommandFilter.PROJECT) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var projectConfigs by remember { mutableStateOf<List<RunConfiguration>>(emptyList()) }
    var projectFileBroken by remember { mutableStateOf(false) }
    var globalConfigs by remember { mutableStateOf<List<RunConfiguration>>(emptyList()) }
    var suggested by remember { mutableStateOf<List<RunTemplateGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CommandEdit?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    val selectedRunId by RunPanel.selectedRunId.collectAsState()
    val selectedRun = runs.find { it.id == selectedRunId }
    var showRuns by remember { mutableStateOf(selectedRun != null) }
    LaunchedEffect(selectedRunId) { if (selectedRunId != null) showRuns = true }

    // A PC that doesn't give the phone a shell: its folders can't be run in from here
    val cannotRun = AuthPreferences.currentRemoteMode && !AuthPreferences.remoteShell

    LaunchedEffect(folder, refreshKey) {
        if (folder.isBlank()) {
            projectConfigs = emptyList(); suggested = emptyList()
        } else {
            loading = true
            val loaded = RunConfigManager.loadProject(folder)
            projectFileBroken = loaded == null
            projectConfigs = loaded.orEmpty()
            suggested = RunTemplates.detect(context, folder)
            loading = false
        }
        globalConfigs = RunConfigManager.loadGlobal(context)
    }
    // Opening a folder with nothing saved yet: show what can be run in it
    LaunchedEffect(folder, loading) {
        if (!loading && projectConfigs.isEmpty() && suggested.isNotEmpty() && filter == CommandFilter.PROJECT) filter = CommandFilter.SUGGESTED
    }

    fun pickFolder(path: String) {
        folder = path.trimEnd('/').ifBlank { path }
        prefs.remember(folder)
    }
    val requestedFolder by RunPanel.requestedFolder.collectAsState()
    LaunchedEffect(requestedFolder) {
        requestedFolder?.let { pickFolder(it); RunPanel.requestedFolder.value = null }
    }

    fun start(name: String, command: String, cwd: String?, env: Map<String, String>? = null, args: List<String>? = null) {
        if (cannotRun) {
            com.example.gemini.ui.components.AppToastHelper.showToast(
                "This PC doesn't let the phone run commands", com.example.gemini.ui.components.ChatToastType.ERROR
            )
            return
        }
        val resolved = RunConfigManager.resolveVariables(command, null, folder)
        val withArgs = if (args.isNullOrEmpty()) resolved else resolved + " " + args.joinToString(" ") { shellQuote(it) }
        val exports = env.orEmpty().entries.joinToString("") { (k, v) -> "export $k=${shellQuote(v)}\n" }
        val run = RunManager.start(context, name, exports + withArgs, resolveCwd(cwd, folder))
        if (folder.isNotBlank()) prefs.remember(folder)
        RunPanel.selectedRunId.value = run.id
        showRuns = true
    }

    fun saveCommand(edit: CommandEdit, config: RunConfiguration, global: Boolean) {
        scope.launch {
            val ok = if (global) {
                val list = globalConfigs.filterNot { it.name.equals(edit.originalName ?: config.name, ignoreCase = true) || it.name.equals(config.name, ignoreCase = true) }
                RunConfigManager.saveGlobal(context, list + config)
            } else {
                val list = projectConfigs.filterNot { it.name.equals(edit.originalName ?: config.name, ignoreCase = true) || it.name.equals(config.name, ignoreCase = true) }
                RunConfigManager.saveProject(folder, list + config)
            }
            // moved between Project and Global: drop it from where it was
            if (ok && edit.originalName != null && edit.wasGlobal != global) {
                if (edit.wasGlobal) RunConfigManager.saveGlobal(context, globalConfigs.filterNot { it.name == edit.originalName })
                else RunConfigManager.saveProject(folder, projectConfigs.filterNot { it.name == edit.originalName })
            }
            if (!ok) com.example.gemini.ui.components.AppToastHelper.showToast("Couldn't save the command", com.example.gemini.ui.components.ChatToastType.ERROR)
            filter = if (global) CommandFilter.GLOBAL else CommandFilter.PROJECT
            refreshKey++
        }
    }

    fun deleteCommand(config: RunConfiguration, global: Boolean) {
        scope.launch {
            if (global) RunConfigManager.saveGlobal(context, globalConfigs.filterNot { it.name == config.name })
            else RunConfigManager.saveProject(folder, projectConfigs.filterNot { it.name == config.name })
            refreshKey++
        }
    }

    Surface(color = AppColors.editorBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(
                    onClose = onClose,
                    showRuns = showRuns,
                    runCount = runs.size,
                    runningCount = runs.count { it.status == RunStatus.RUNNING },
                    onShowCommands = { showRuns = false },
                    onShowRuns = {
                        showRuns = true
                        if (selectedRun == null) RunPanel.selectedRunId.value = runs.lastOrNull()?.id
                    }
                )
                if (showRuns) {
                    RunsView(
                        runs = runs,
                        selected = selectedRun ?: runs.lastOrNull(),
                        onSelect = { RunPanel.selectedRunId.value = it },
                        onSendToChat = { run ->
                            RunPanel.chatHandoff.value = chatText(run, RunManager.recentOutput(run.id))
                            onSendToChat()
                        },
                        onBackToCommands = { showRuns = false }
                    )
                } else {
                    CommandsView(
                        folder = folder,
                        activeProjectPath = activeProject?.path,
                        ideProjects = ideProjects.map { it.path },
                        recent = prefs.recent,
                        filter = filter,
                        onFilter = { filter = it },
                        loading = loading,
                        cannotRun = cannotRun,
                        projectConfigs = projectConfigs,
                        projectFileBroken = projectFileBroken,
                        globalConfigs = globalConfigs,
                        suggested = suggested,
                        onPickFolder = ::pickFolder,
                        onBrowse = { showBrowser = true },
                        onRunConfig = { start(it.name, it.command.orEmpty(), it.cwd, it.env, it.args) },
                        onRunTemplate = { start(it.name, it.command, it.cwd) },
                        onAddTemplate = { t ->
                            scope.launch {
                                val list = projectConfigs.filterNot { it.name.equals(t.name, ignoreCase = true) }
                                if (RunConfigManager.saveProject(folder, list + t.toConfig())) {
                                    com.example.gemini.ui.components.AppToastHelper.showToast("Added \"${t.name}\" to the project")
                                }
                                refreshKey++
                            }
                        },
                        onNew = { editing = CommandEdit(global = filter == CommandFilter.GLOBAL || folder.isBlank()) },
                        onEdit = { cfg, global -> editing = CommandEdit(cfg, cfg.name, global, global) },
                        onDuplicate = { cfg, global -> editing = CommandEdit(cfg.copy(name = cfg.name + " copy"), null, global, global) },
                        onDelete = ::deleteCommand
                    )
                }
            }
    }

    editing?.let { edit ->
        CommandEditorDialog(
            edit = edit,
            canSaveToProject = folder.isNotBlank(),
            onDismiss = { editing = null },
            onSave = { cfg, global -> editing = null; saveCommand(edit, cfg, global) }
        )
    }
    if (showBrowser) {
        WorkspaceFolderBrowserDialog(
            initialPath = folder.ifBlank { null },
            onSelectFolder = { pickFolder(it.path); showBrowser = false },
            onDismiss = { showBrowser = false }
        )
    }
}

@Composable
private fun PanelHeader(
    onClose: () -> Unit,
    showRuns: Boolean,
    runCount: Int,
    runningCount: Int,
    onShowCommands: () -> Unit,
    onShowRuns: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.editorPanel)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppColors.editorText)
        }
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(ClaudeTerracotta.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.PlayArrow, null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(8.dp))
        Text("Run", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.editorText)
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.editorBg).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            SegmentButton("Commands", selected = !showRuns, onClick = onShowCommands)
            SegmentButton(
                if (runningCount > 0) "Runs · $runningCount running" else "Runs ($runCount)",
                selected = showRuns,
                enabled = runCount > 0,
                onClick = onShowRuns
            )
        }
    }
    HorizontalDivider(color = AppColors.editorBorder, thickness = 0.5.dp)
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.18f) else Color.Transparent
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> ClaudeTerracotta
                enabled -> AppColors.editorText
                else -> AppColors.editorMuted
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// ---------------------------------------------------------------- commands

@Composable
private fun CommandsView(
    folder: String,
    activeProjectPath: String?,
    ideProjects: List<String>,
    recent: List<String>,
    filter: CommandFilter,
    onFilter: (CommandFilter) -> Unit,
    loading: Boolean,
    cannotRun: Boolean,
    projectConfigs: List<RunConfiguration>,
    projectFileBroken: Boolean,
    globalConfigs: List<RunConfiguration>,
    suggested: List<RunTemplateGroup>,
    onPickFolder: (String) -> Unit,
    onBrowse: () -> Unit,
    onRunConfig: (RunConfiguration) -> Unit,
    onRunTemplate: (RunTemplate) -> Unit,
    onAddTemplate: (RunTemplate) -> Unit,
    onNew: () -> Unit,
    onEdit: (RunConfiguration, Boolean) -> Unit,
    onDuplicate: (RunConfiguration, Boolean) -> Unit,
    onDelete: (RunConfiguration, Boolean) -> Unit
) {
    // Commands written for the IDE's open file (${file}…) mean nothing here
    val usableProject = projectConfigs.filterNot { isIdeOnly(it) }
    val hiddenIdeOnly = projectConfigs.size - usableProject.size
    val suggestedCount = suggested.sumOf { it.templates.size }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        FolderRow(folder, activeProjectPath, ideProjects, recent, onPickFolder, onBrowse)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CommandFilter.entries.forEach { f ->
                val count = when (f) {
                    CommandFilter.PROJECT -> usableProject.size
                    CommandFilter.GLOBAL -> globalConfigs.size
                    CommandFilter.SUGGESTED -> suggestedCount
                }
                FilterPill("${f.label} ($count)", selected = filter == f) { onFilter(f) }
            }
        }

        if (cannotRun) {
            Banner("This PC doesn't let the phone run commands. Allow it on the PC (shell access), or switch to the phone.", AppColors.warning)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = ClaudeTerracotta) } }
            }
            when (filter) {
                CommandFilter.PROJECT -> {
                    if (folder.isBlank()) {
                        item { EmptyHint("Pick a folder above: its commands are saved in .antigem/run.json inside it.") }
                    } else {
                        if (projectFileBroken) item { Banner(".antigem/run.json in this folder isn't valid JSON. Fix it (or delete it) to see its commands.", AppColors.danger) }
                        if (usableProject.isEmpty() && !loading && !projectFileBroken) {
                            item {
                                EmptyHint(
                                    if (suggestedCount > 0) "No commands saved for this folder yet. Add your own, or pick one from Suggested ($suggestedCount)."
                                    else "No commands saved for this folder yet. Add one below."
                                )
                            }
                        }
                        itemsIndexed(usableProject, key = { i, c -> "p:$i:" + c.name }) { _, cfg ->
                            CommandRow(cfg.name, cfg.command.orEmpty(), cfg.cwd, onRun = { onRunConfig(cfg) },
                                onEdit = { onEdit(cfg, false) }, onDuplicate = { onDuplicate(cfg, false) }, onDelete = { onDelete(cfg, false) })
                        }
                        if (hiddenIdeOnly > 0) {
                            item { Text("$hiddenIdeOnly IDE-only command(s) hidden (they use the IDE's open file).", fontSize = 11.5.sp, color = AppColors.editorMuted, modifier = Modifier.padding(4.dp)) }
                        }
                    }
                }
                CommandFilter.GLOBAL -> {
                    if (globalConfigs.isEmpty()) {
                        item { EmptyHint("Global commands work in any folder (they run in the folder picked above). For example adb devices, adb logcat or a backup script.") }
                    }
                    itemsIndexed(globalConfigs, key = { i, c -> "g:$i:" + c.name }) { _, cfg ->
                        CommandRow(cfg.name, cfg.command.orEmpty(), cfg.cwd, onRun = { onRunConfig(cfg) },
                            onEdit = { onEdit(cfg, true) }, onDuplicate = { onDuplicate(cfg, true) }, onDelete = { onDelete(cfg, true) })
                    }
                }
                CommandFilter.SUGGESTED -> {
                    if (suggested.isEmpty() && !loading) {
                        item { EmptyHint(if (folder.isBlank()) "Pick a folder to see what can be run in it." else "Nothing recognised in this folder (Flutter, React Native, Android Gradle, Node.js, Python, Go, Rust, make).") }
                    }
                    suggested.forEach { group ->
                        item(key = "h:" + group.kind) {
                            Text(group.kind, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
                        }
                        if (group.needsAndroidSetup) {
                            item(key = "w:" + group.kind) {
                                Banner("These need the Android build tools: set them up in Settings → Android Development first.", AppColors.warning)
                            }
                        }
                        itemsIndexed(group.templates, key = { i, t -> "s:" + group.kind + ":$i" }) { _, t ->
                            TemplateRow(t, onRun = { onRunTemplate(t) }, onAdd = if (folder.isNotBlank()) ({ onAddTemplate(t) }) else null)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = AppColors.editorBorder, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New command")
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: String,
    activeProjectPath: String?,
    ideProjects: List<String>,
    recent: List<String>,
    onPick: (String) -> Unit,
    onBrowse: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(12.dp),
            color = AppColors.editorPanel,
            border = BorderStroke(1.dp, AppColors.editorBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (folder.isBlank()) "Choose a folder" else folder.substringAfterLast('/').ifBlank { folder },
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.editorText, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (folder.isNotBlank()) {
                        Text(shortPath(folder), fontSize = 11.sp, color = AppColors.editorMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                    }
                }
                Icon(Icons.Filled.UnfoldMore, "Change folder", tint = AppColors.editorMuted, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val seen = mutableSetOf<String>()
            val choices = buildList {
                activeProjectPath?.let { add(it to "IDE project") }
                recent.forEach { add(it to "Recent") }
                ideProjects.forEach { add(it to "Project") }
            }.filter { seen.add(it.first.trimEnd('/')) }.take(14)
            choices.forEach { (path, label) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(path.substringAfterLast('/').ifBlank { path }, fontSize = 13.5.sp, fontWeight = if (path == folder) FontWeight.Bold else FontWeight.Normal)
                            Text("$label · ${shortPath(path)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { menu = false; onPick(path) }
                )
            }
            if (choices.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                text = { Text("Browse…") },
                onClick = { menu = false; onBrowse() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(
    name: String,
    command: String,
    cwd: String?,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppColors.editorPanel,
        border = BorderStroke(0.5.dp, AppColors.editorBorder),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onRun, onLongClick = { menu = true })
    ) {
        Row(Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRun) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(ClaudeTerracotta.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, "Run $name", tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.editorText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(command, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.editorMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!cwd.isNullOrBlank() && cwd != "\${workspaceFolder}") {
                    Text("in $cwd", fontSize = 10.5.sp, color = AppColors.editorMuted.copy(alpha = 0.8f), maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = AppColors.editorMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Outlined.Edit, null) }, text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Outlined.Delete, null) }, text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"$name\"?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TemplateRow(t: RunTemplate, onRun: () -> Unit, onAdd: (() -> Unit)?) {
    Surface(
        onClick = onRun,
        shape = RoundedCornerShape(12.dp),
        color = AppColors.editorPanel,
        border = BorderStroke(0.5.dp, AppColors.editorBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRun) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(ClaudeTerracotta.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, "Run ${t.name}", tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.editorText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (t.cwd?.let { "cd $it && " } ?: "") + t.command,
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.editorMuted, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                t.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 10.5.sp, color = AppColors.editorMuted.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onAdd != null) {
                IconButton(onClick = onAdd) { Icon(Icons.Outlined.BookmarkAdd, "Save to this project", tint = AppColors.editorMuted) }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.18f) else AppColors.editorPanel,
        border = BorderStroke(1.dp, if (selected) ClaudeTerracotta.copy(alpha = 0.6f) else AppColors.editorBorder)
    ) {
        Text(
            label, fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) ClaudeTerracotta else AppColors.editorText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.12f), border = BorderStroke(1.dp, color.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text, fontSize = 12.sp, color = color, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, fontSize = 12.5.sp, color = AppColors.editorMuted, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp))
}

// ---------------------------------------------------------------- editor

private data class CommandEdit(
    val config: RunConfiguration? = null,
    /** Name before editing (null = a new command). */
    val originalName: String? = null,
    val global: Boolean = false,
    val wasGlobal: Boolean = false
)

@Composable
private fun CommandEditorDialog(
    edit: CommandEdit,
    canSaveToProject: Boolean,
    onDismiss: () -> Unit,
    onSave: (RunConfiguration, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(edit.config?.name.orEmpty()) }
    var command by remember { mutableStateOf(edit.config?.command.orEmpty()) }
    var cwd by remember { mutableStateOf(edit.config?.cwd?.takeIf { it != "\${workspaceFolder}" }.orEmpty()) }
    var global by remember { mutableStateOf(edit.global || !canSaveToProject) }
    val canSave = name.isNotBlank() && command.isNotBlank()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = AppColors.editorBg,
            border = BorderStroke(1.dp, AppColors.editorBorder),
            modifier = Modifier.fillMaxWidth(0.94f).imePadding()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (edit.originalName == null) "New command" else "Edit command", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.editorText)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text("Build debug APK") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("Command") }, placeholder = { Text("adev ./gradlew assembleDebug") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cwd, onValueChange = { cwd = it }, singleLine = true,
                    label = { Text("Folder (optional)") }, placeholder = { Text("android  ·  empty = the project folder") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Save to", fontSize = 12.sp, color = AppColors.editorMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("This project", selected = !global) { if (canSaveToProject) global = false }
                    FilterPill("Global (any folder)", selected = global) { global = true }
                }
                Text(
                    if (global) "Kept in the app's home (~/.antigem/global-run.json); runs in the folder picked in the panel."
                    else "Saved in the project's .antigem/run.json, which the IDE and the agents use too.",
                    fontSize = 11.5.sp, color = AppColors.editorMuted
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        onClick = {
                            val base = edit.config ?: RunConfiguration(name = name.trim())
                            onSave(base.copy(name = name.trim(), type = "terminal", command = command.trim(), cwd = cwd.trim().ifBlank { null }), global)
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- runs

@Composable
private fun RunsView(
    runs: List<RunEntry>,
    selected: RunEntry?,
    onSelect: (String) -> Unit,
    onSendToChat: (RunEntry) -> Unit,
    onBackToCommands: () -> Unit
) {
    val context = LocalContext.current
    if (selected == null) {
        Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing running.", color = AppColors.editorMuted)
                TextButton(onClick = onBackToCommands) { Text("Show commands") }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // one tab per run (Metro and a build side by side)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            runs.forEach { run ->
                val isSel = run.id == selected.id
                Surface(
                    onClick = { onSelect(run.id) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSel) AppColors.editorPanel else Color.Transparent,
                    border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta.copy(alpha = 0.55f) else AppColors.editorBorder)
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(run.status, Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(run.name, fontSize = 12.5.sp, color = AppColors.editorText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        IconButton(onClick = { RunManager.remove(run.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, if (run.status == RunStatus.RUNNING) "Stop and close" else "Close", tint = AppColors.editorMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }

        // the selected run: what, where, how it went
        Row(
            Modifier.fillMaxWidth().background(AppColors.editorPanel).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(selected.command.lineSequence().lastOrNull { it.isNotBlank() && !it.startsWith("export ") } ?: selected.command,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.editorText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(selected), fontSize = 11.sp, color = statusColor(selected.status), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected.status == RunStatus.RUNNING) {
                TextButton(onClick = { RunManager.stop(selected.id) }) {
                    Icon(Icons.Filled.Stop, null, tint = AppColors.danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop", color = AppColors.danger)
                }
            } else {
                IconButton(onClick = { onSendToChat(selected) }) {
                    Icon(Icons.Outlined.ForwardToInbox, "Send output to chat", tint = if (selected.status == RunStatus.FAILED) ClaudeTerracotta else AppColors.editorMuted)
                }
            }
            IconButton(onClick = { RunManager.restart(context, selected.id) }) {
                Icon(Icons.Filled.Refresh, "Run again", tint = AppColors.editorText)
            }
        }
        if (selected.status == RunStatus.FAILED) {
            Surface(
                onClick = { onSendToChat(selected) },
                color = AppColors.danger.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ForwardToInbox, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Failed. Send the output to the chat so the agent can fix it", fontSize = 12.sp, color = AppColors.danger)
                }
            }
        }

        // the Terminal screen's own terminal (font size, cursor, colors, the two key rows); Enter after the end closes the tab
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(selected.id, selected.startedAt) {
                com.example.gemini.ui.components.LocalTerminalContent(
                    onClose = { RunManager.remove(selected.id) },
                    customSession = selected.session,
                    showFloatingPill = false,
                    showSessionHeader = false,
                    focusScreenMode = null,
                    showKeyboardOnOpen = false
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: RunStatus, modifier: Modifier = Modifier) {
    when (status) {
        RunStatus.RUNNING -> CircularProgressIndicator(modifier, strokeWidth = 2.dp, color = ClaudeTerracotta)
        RunStatus.SUCCEEDED -> Icon(Icons.Filled.CheckCircle, "Done", tint = AppColors.success, modifier = modifier)
        RunStatus.FAILED -> Icon(Icons.Filled.Error, "Failed", tint = AppColors.danger, modifier = modifier)
        RunStatus.STOPPED -> Icon(Icons.Filled.StopCircle, "Stopped", tint = AppColors.editorMuted, modifier = modifier)
    }
}

@Composable
private fun statusLine(run: RunEntry): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (run.status == RunStatus.RUNNING) {
        LaunchedEffect(run.id, run.startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    }
    val took = duration(((run.finishedAt ?: now) - run.startedAt).coerceAtLeast(0))
    val where = shortPath(run.folder)
    return when (run.status) {
        RunStatus.RUNNING -> "Running · $took · $where"
        RunStatus.SUCCEEDED -> "Done in $took · exit 0 · $where"
        RunStatus.FAILED -> "Failed after $took" + (run.exitCode?.let { " · exit $it" } ?: "") + " · $where"
        RunStatus.STOPPED -> "Stopped after $took · $where"
    }
}

private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.RUNNING -> ClaudeTerracotta
    RunStatus.SUCCEEDED -> AppColors.success
    RunStatus.FAILED -> AppColors.danger
    RunStatus.STOPPED -> AppColors.editorMuted
}

// ---------------------------------------------------------------- bubble

/** Shown while the panel is hidden: a spinner while something runs, then ✓ / ✗ until the result is looked at. */
@Composable
private fun RunBubble(runs: List<RunEntry>, seen: Set<String>) {
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val running = runs.count { it.status == RunStatus.RUNNING }
    val latestUnseen = runs.lastOrNull { it.status != RunStatus.RUNNING && RunPanel.key(it) !in seen }
    val target = runs.lastOrNull { it.status == RunStatus.RUNNING } ?: latestUnseen ?: runs.lastOrNull()
    val failed = running == 0 && runs.any { it.status == RunStatus.FAILED && RunPanel.key(it) !in seen }
    val ring = when {
        running > 0 -> ClaudeTerracotta
        failed -> AppColors.danger
        else -> AppColors.success
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizePx = with(density) { 56.dp.toPx() }
        val maxX = constraints.maxWidth - sizePx
        val maxY = constraints.maxHeight - sizePx
        val startX = with(density) { 12.dp.toPx() }
        val startY = constraints.maxHeight * 0.62f
        Box(
            Modifier
                .offset { IntOffset((startX + offset.x).coerceIn(0f, maxX).roundToInt(), (startY + offset.y).coerceIn(0f, maxY).roundToInt()) }
                .size(56.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); offset += drag }
                }
        ) {
            Surface(
                onClick = { RunPanel.show(target?.id) },
                shape = CircleShape,
                color = AppColors.editorPanel,
                border = BorderStroke(2.dp, ring.copy(alpha = 0.85f)),
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        running > 0 -> {
                            CircularProgressIndicator(Modifier.size(44.dp), strokeWidth = 2.5.dp, color = ClaudeTerracotta)
                            Icon(Icons.Filled.PlayArrow, "Runs", tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        }
                        failed -> Icon(Icons.Filled.Close, "A run failed", tint = AppColors.danger, modifier = Modifier.size(26.dp))
                        else -> Icon(Icons.Filled.Check, "Runs finished", tint = AppColors.success, modifier = Modifier.size(26.dp))
                    }
                }
            }
            if (running > 1) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(ClaudeTerracotta),
                    contentAlignment = Alignment.Center
                ) { Text("$running", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun isIdeOnly(cfg: RunConfiguration): Boolean {
    val text = (cfg.command.orEmpty() + " " + cfg.cwd.orEmpty())
    return IDE_FILE_VARIABLE.containsMatchIn(text)
}

// Android's regex engine needs the closing brace escaped
private val IDE_FILE_VARIABLE = Regex("""\$\{file(Basename|BasenameNoExtension|Dirname|Ext)?\}""")

/** A command's folder: the project folder, a sub-folder of it, or an absolute / ~ path. */
private fun resolveCwd(cwd: String?, folder: String): String {
    val c = cwd?.trim().orEmpty()
    if (c.isBlank()) return folder
    val resolved = RunConfigManager.resolveVariables(c, null, folder)
    return when {
        resolved.startsWith("/") || resolved.startsWith("~") -> resolved
        folder.isBlank() -> resolved
        else -> folder.trimEnd('/') + "/" + resolved.removePrefix("./")
    }
}

private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

private fun shortPath(path: String): String =
    path.replace(Regex("^/data/(data|user/0)/[^/]+/files/home"), "~")

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/** What "Send to chat" puts in the message box: what ran, where, how it ended, and the end of its output. */
private fun chatText(run: RunEntry, output: String): String {
    val how = when (run.status) {
        RunStatus.FAILED -> "failed" + (run.exitCode?.let { " (exit code $it)" } ?: "")
        RunStatus.SUCCEEDED -> "finished (exit code 0)"
        RunStatus.STOPPED -> "was stopped"
        RunStatus.RUNNING -> "is still running"
    }
    val fence = if (output.contains("```")) "````" else "```"
    return buildString {
        append("The command \"${run.name}\" $how in ${shortPath(run.folder).ifBlank { "the home folder" }}.\n\n")
        append(fence).append("text\n")
        append("$ ").append(run.command.trim()).append("\n")
        // the run's own "▶ command" header line repeats the line above
        append(output.trim().lines().dropWhile { it.trimStart().startsWith("▶ ") }.joinToString("\n").trim())
        append("\n").append(fence).append("\n")
    }
}
