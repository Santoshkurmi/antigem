package ai.antigem.ui.ide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.daemon.DaemonStatus
import ai.antigem.data.daemon.FileNode
import ai.antigem.data.daemon.GitApiClient
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.data.daemon.OpenTab
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.daemon.IdeDaemonManager
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.ui.components.LocalTerminalContent
import ai.antigem.data.local.LocalPtySession
import androidx.compose.ui.zIndex
import java.io.File
import java.util.UUID

import ai.antigem.data.daemon.mergeProjects
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.ui.chat.ChatViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import ai.antigem.data.daemon.FileSaveResult
import ai.antigem.data.daemon.FileReadOutcome
import ai.antigem.data.files.FileExplorerApi
import ai.antigem.data.files.FileKinds
import ai.antigem.data.files.IdeFileLauncher

import kotlinx.coroutines.delay

import ai.antigem.data.daemon.TabDiskUpdateResult
import androidx.compose.ui.draw.clip
import ai.antigem.theme.GeminiBlue
import ai.antigem.data.ide.RunConfigManager
import ai.antigem.data.ide.RunConfiguration
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.ui.browser.BrowserSessionManager
import ai.antigem.i18n.L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(
    viewModel: ChatViewModel? = null,
    isVisible: Boolean = true,
    onNavigateToChat: () -> Unit,
    onExecuteRunCommand: (command: String) -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val conversations by (viewModel?.conversations ?: MutableStateFlow(emptyList())).collectAsState()
    val allProjects by IdeDaemonManager.projects.collectAsState()
    val activeProject by IdeDaemonManager.activeProject.collectAsState()
    val openTabs by IdeDaemonManager.openTabs.collectAsState()
    val activeTabPath by IdeDaemonManager.activeTabPath.collectAsState()

    val fileTree = remember { IdeFileTreeState() }
    var openingPath by remember { mutableStateOf<String?>(null) }
    val openJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var isWordWrap by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
    var saveAsDialogTab by remember { mutableStateOf<OpenTab?>(null) }
    var tabToClose by remember { mutableStateOf<OpenTab?>(null) }
    var conflictDialogTab by remember { mutableStateOf<OpenTab?>(null) }
    var autoUpdateNotification by remember { mutableStateOf<String?>(null) }
    var currentEditorView by remember { mutableStateOf<CodeEditorView?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var isCaseSensitive by remember { mutableStateOf(false) }
    var searchMatchCount by remember { mutableIntStateOf(0) }
    var currentMatchIndex by remember { mutableIntStateOf(-1) }
    val tabListState = rememberLazyListState()

    var showRunMenu by remember { mutableStateOf(false) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var selectedProfileName by remember { mutableStateOf<String?>(null) }
    var runConfigurations by remember { mutableStateOf<List<RunConfiguration>>(emptyList()) }
    // The IDE's run goes through RunManager (so the Run panel shows it too); closing the runner here still stops it
    var ideRunId by remember { mutableStateOf<String?>(null) }
    val allRuns by ai.antigem.data.run.RunManager.runs.collectAsState()
    val runnerSession: LocalPtySession? = allRuns.find { it.id == ideRunId }?.session
    fun closeIdeRun() {
        ideRunId?.let { ai.antigem.data.run.RunManager.remove(it) }
        ideRunId = null
    }
    var runnerHeightFraction by remember { mutableFloatStateOf(0.5f) }
    var isRunnerMaximized by remember { mutableStateOf(true) }
    var lastExecutedProfile by remember { mutableStateOf<RunConfiguration?>(null) }

    fun refreshRunConfigurations() {
        val projPath = activeProject?.path
        if (!projPath.isNullOrBlank()) {
            runConfigurations = RunConfigManager.loadConfigurations(projPath)
        } else {
            runConfigurations = emptyList()
        }
    }

    LaunchedEffect(activeProject?.path, openTabs.size) {
        refreshRunConfigurations()
    }

    // Ready-made commands for the project's kind (Flutter, React Native, Gradle, npm…), as in the Run panel's Suggested
    var suggestedRuns by remember { mutableStateOf<List<Pair<String, ai.antigem.data.run.RunTemplate>>>(emptyList()) }
    LaunchedEffect(activeProject?.path) {
        val path = activeProject?.path
        suggestedRuns = if (path.isNullOrBlank()) emptyList() else
            ai.antigem.data.run.RunTemplates.detect(context, path).flatMap { g -> g.templates.map { g.kind to it } }
    }

    fun executeRun(profile: RunConfiguration) {
        lastExecutedProfile = profile
        val projectPath = activeProject?.path ?: ""
        val rawCmd = profile.command ?: ""
        val resolvedCmd = if (rawCmd.isNotBlank()) {
            RunConfigManager.resolveVariables(rawCmd, activeTabPath, projectPath)
        } else {
            val fallback = activeTabPath?.let { RunConfigManager.getDefaultRunnerForFile(it, projectPath) }
            RunConfigManager.resolveVariables(fallback?.command ?: "bash", activeTabPath, projectPath)
        }
        val resolvedCwd = RunConfigManager.resolveWorkingDir(profile.cwd, activeTabPath, projectPath)

        closeIdeRun()
        ideRunId = ai.antigem.data.run.RunManager.start(
            context, profile.name, resolvedCmd, resolvedCwd.orEmpty(), origin = "ide"
        ).id
    }

    val findFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val composeView = LocalView.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val focusCoordinator = ai.antigem.ui.components.LocalAppFocusCoordinator.current

    DisposableEffect(focusCoordinator, showFindBar, currentEditorView) {
        focusCoordinator.registerScreen(
            mode = ai.antigem.AppViewMode.IDE,
            onRequestFocus = {
                if (showFindBar) {
                    try { findFocusRequester.requestFocus() } catch (_: Exception) {}
                } else {
                    val editor = currentEditorView?.editor ?: currentEditorView
                    if (editor != null) {
                        editor.requestFocus()
                        val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            },
            onClearFocus = {
                currentEditorView?.editor?.clearFocus()
                currentEditorView?.clearFocus()
                focusManager.clearFocus(force = true)
            }
        )
        onDispose {
            focusCoordinator.unregisterScreen(ai.antigem.AppViewMode.IDE)
        }
    }

    androidx.activity.compose.BackHandler(enabled = isVisible && runnerSession != null) {
        closeIdeRun()
    }

    androidx.activity.compose.BackHandler(enabled = isVisible && showFindBar && runnerSession == null) {
        showFindBar = false
        findQuery = ""
        currentEditorView?.stopSearch()
        searchMatchCount = 0
        currentMatchIndex = -1
        keyboardController?.hide()
    }

    // Auto-scroll active tab into view when activeTabPath or tabs change
    LaunchedEffect(activeTabPath, openTabs.size) {
        if (activeTabPath != null) {
            val idx = openTabs.indexOfFirst { it.path == activeTabPath }
            if (idx >= 0) {
                tabListState.animateScrollToItem(idx)
            }
        }
    }

    /** [force]: read the shown folders again even when the bridge says they did not change (the Refresh button). */
    fun refreshProjectsAndTree(force: Boolean = false) {
        coroutineScope.launch {
            val projs = IdeDaemonManager.loadProjects(conversations)
            val current = IdeDaemonManager.activeProject.value
            if (current != null) {
                fileTree.refreshVisible(force)
            } else if (projs.isNotEmpty()) {
                // the tree follows the active project (LaunchedEffect below)
                IdeDaemonManager.setActiveProject(projs.first())
            }
        }
    }

    // Auto-refresh file tree and projects whenever user switches to IDE screen
    LaunchedEffect(isVisible) {
        if (isVisible) {
            refreshProjectsAndTree()
        }
    }

    // React immediately whenever daemon reconnects in the background
    LaunchedEffect(Unit) {
        IdeDaemonManager.serverReconnectedEvent.collect {
            refreshProjectsAndTree(force = true)
        }
    }

    // While the IDE is shown, the open file is checked every 3 s with a stat (size and change time, no download) and
    // read again only when one of them moved. Switching tabs does not restart the loop.
    LaunchedEffect(isVisible) {
        if (isVisible) {
            while (true) {
                checkActiveTabOnDisk()?.let { autoUpdateNotification = it }
                delay(3000)
            }
        }
    }

    // Auto-dismiss reload notification banner after 3 seconds
    LaunchedEffect(autoUpdateNotification) {
        if (autoUpdateNotification != null) {
            delay(3000)
            autoUpdateNotification = null
        }
    }

    // Recheck connection and reload file tree when app is brought to foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshProjectsAndTree()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // the project folder is read as soon as a project is active, so the sidebar opens with it ready
    LaunchedEffect(activeProject?.path) {
        fileTree.setRoot(activeProject?.path)
    }

    val activeTab = openTabs.find { it.path == activeTabPath }
    val activeExt = remember(activeTab?.name) { activeTab?.name?.substringAfterLast('.', "")?.lowercase() ?: "" }
    val isImageFile = activeExt in IdeFileLauncher.VIEWER_IMAGE_EXTS
    val isSvgFile = activeExt == "svg"
    var showSvgSource by remember(activeTabPath) { mutableStateOf(false) }
    var binaryWarningDialog by remember { mutableStateOf<BinaryWarningTab?>(null) }
    var tooLargeDialog by remember { mutableStateOf<TooLargeFile?>(null) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // The tree's shown folders are checked every 3 s only while the sidebar is open (an unchanged folder costs the
    // bridge one stat), and right away when it opens.
    val sidebarOpen = drawerState.targetValue == DrawerValue.Open
    LaunchedEffect(isVisible, sidebarOpen) {
        if (isVisible && sidebarOpen) {
            while (true) {
                fileTree.refreshVisible()
                delay(3000)
            }
        }
    }

    /** Opens a file through the same size and type checks as everywhere else; a new tap cancels the last open. */
    fun openInIde(path: String, name: String, size: Long, force: Boolean = false) {
        openJob[0]?.cancel()
        openingPath = null
        openJob[0] = coroutineScope.launch {
            // most files open in a few ms: the row's spinner only shows for a slow one
            val spinner = launch {
                delay(SPINNER_DELAY_MS)
                openingPath = path
            }
            try {
                when (val r = IdeFileLauncher.open(path, name, size, force)) {
                    is IdeFileLauncher.Outcome.Opened -> drawerState.close()
                    is IdeFileLauncher.Outcome.NeedsConfirm -> binaryWarningDialog = BinaryWarningTab(path, name, size, r.mimeType)
                    is IdeFileLauncher.Outcome.Failed ->
                        if (r.tooLarge) tooLargeDialog = TooLargeFile(path, name, r.message)
                        else Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                spinner.cancel()
                // a newer tap owns the spinner now
                if (openJob[0] === coroutineContext[kotlinx.coroutines.Job]) openingPath = null
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isVisible && (drawerState.isOpen || runnerSession == null),
        drawerContent = {
            ModalDrawerSheet(
                // the sidebar's own color, so the sheet's strip right of it and behind the status bar match it
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp)
            ) {
                ProjectSidebar(
                    projects = allProjects,
                    activeProject = activeProject,
                    fileTree = fileTree,
                    activeFilePath = activeTabPath,
                    openingPath = openingPath,
                    onSelectProject = { proj ->
                        IdeDaemonManager.setActiveProject(proj)
                        viewModel?.onProjectChanged(proj.path)
                    },
                    onOpenFileManager = {
                        showFileManager = true
                        coroutineScope.launch { drawerState.close() }
                    },
                    onRemoveProject = { proj ->
                        coroutineScope.launch {
                            IdeApiClient.removeSavedProject(proj.path)
                            refreshProjectsAndTree()
                        }
                    },
                    onCreateProjectRequested = {
                        showFileManager = true
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenFile = { node -> openInIde(node.path, node.name, node.size) },
                    onOpenFileDiff = { filePath, isStaged ->
                        coroutineScope.launch {
                            val projPath = activeProject?.path ?: return@launch
                            val diffRes = GitApiClient.getDiff(projPath, filePath, isStaged)
                            val diffContent = diffRes?.diff ?: ""
                            IdeDaemonManager.openDiffTab(filePath, diffContent, isStaged)
                            drawerState.close()
                        }
                    },
                    onCreateFile = { fullPath, name, isDir ->
                        coroutineScope.launch {
                            val ok = IdeApiClient.createFileOrDir(fullPath, isDir)
                            if (ok) {
                                fileTree.reload(parentPathOf(fullPath))
                                Toast.makeText(context, "Created ${if (isDir) "folder" else "file"} '$name'", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, L.ide.screen.failedToCreateValue(if (isDir) "folder" else "file"), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRenameFile = { oldPath, newPath ->
                        coroutineScope.launch {
                            val ok = IdeApiClient.renameFileOrDir(oldPath, newPath)
                            if (ok) {
                                val newName = File(newPath).name
                                IdeDaemonManager.renameTab(oldPath, newPath, newName)
                                fileTree.forget(oldPath)
                                fileTree.reload(parentPathOf(oldPath), parentPathOf(newPath))
                                Toast.makeText(context, L.ide.screen.renamedToNewname(newName), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, L.ide.screen.failedToRename, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDeleteFile = { path ->
                        coroutineScope.launch {
                            val ok = IdeApiClient.deleteFileOrDir(path)
                            if (ok) {
                                IdeDaemonManager.closeTab(path)
                                fileTree.forget(path)
                                fileTree.reload(parentPathOf(path))
                                Toast.makeText(context, L.ide.screen.deleted, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, L.ide.screen.failedToDelete, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenTerminal = { targetPath ->
                        coroutineScope.launch { drawerState.close() }
                        val resolvedPath = targetPath.ifBlank { activeProject?.path }
                        LocalTerminalManager.createNewSession(
                            context = context,
                            workingDir = resolvedPath
                        )
                        onNavigateToTerminal()
                    },
                    onRefreshTree = { refreshProjectsAndTree(force = true) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            coroutineScope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = L.ide.screen.toggleSidebar
                            )
                        }
                    },
                    title = {
                        Text(
                            text = activeProject?.name ?: L.ide.screen.antigemIde,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    },
                    actions = {
                        val isTextFile = activeTab != null && (!isImageFile || (isSvgFile && showSvgSource))
                        val isTextEditable = isTextFile && !activeTab.isReadOnly
                        var showMoreOptionsMenu by remember { mutableStateOf(false) }

                        // Three-Dot More Options Menu (Word Wrap, Find in file, SVG toggle)
                        if (isTextFile) {
                            Box {
                                IconButton(
                                    onClick = { showMoreOptionsMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = L.ide.screen.moreEditorOptions,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMoreOptionsMenu,
                                    onDismissRequest = { showMoreOptionsMenu = false }
                                ) {
                                    // Word Wrap with Checkbox
                                    DropdownMenuItem(
                                        text = { Text(L.ide.screen.wordWrap, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.WrapText,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = isWordWrap,
                                                onCheckedChange = { isWordWrap = it },
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            isWordWrap = !isWordWrap
                                        }
                                    )

                                    // Find / Search in File
                                    DropdownMenuItem(
                                        text = { Text(L.ide.screen.findInFile, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            showMoreOptionsMenu = false
                                            showFindBar = true
                                        }
                                    )

                                    // SVG Source / Graphic Preview Toggle
                                    if (isSvgFile) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (showSvgSource) L.ide.screen.previewSvgGraphic else L.ide.screen.editSvgXmlSource,
                                                    fontSize = 13.sp
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (showSvgSource) Icons.Default.Image else Icons.Default.Code,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                showMoreOptionsMenu = false
                                                showSvgSource = !showSvgSource
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Undo & Redo Buttons
                        if (isTextFile) {
                            IconButton(
                                onClick = { currentEditorView?.undo() },
                                enabled = canUndo && isTextEditable,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = L.common.undo,
                                    tint = if (canUndo && isTextEditable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = { currentEditorView?.redo() },
                                enabled = canRedo && isTextEditable,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = L.ide.screen.redo,
                                    tint = if (canRedo && isTextEditable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Save Button
                        if (activeTab != null && !activeTab.isReadOnly && (!isImageFile || (isSvgFile && showSvgSource))) {
                            val isExternal = activeTab.isExternal || activeTab.path.startsWith("content://") || activeTab.path.startsWith("android.resource://")
                            IconButton(
                                onClick = {
                                    if (isExternal) {
                                        // Externally opened file has no location on the daemon server filesystem yet -> prompt where to save
                                        saveAsDialogTab = activeTab
                                    } else {
                                        // Normal project/workspace file -> direct save to its server path
                                        coroutineScope.launch {
                                            val currentContent = currentEditorView?.getText() ?: activeTab.content
                                            val result = IdeApiClient.saveFileDetailed(
                                                path = activeTab.path,
                                                content = currentContent,
                                                expectedHash = activeTab.originalHash,
                                                force = false
                                            )
                                            when (result) {
                                                is FileSaveResult.Success -> {
                                                    IdeDaemonManager.markTabSaved(activeTab.path, result.hash)
                                                    Toast.makeText(context, L.ide.screen.saved, Toast.LENGTH_SHORT).show()
                                                }
                                                is FileSaveResult.Conflict -> {
                                                    conflictDialogTab = activeTab.copy(
                                                        diskConflict = true,
                                                        diskContentOnConflict = result.diskContent
                                                    )
                                                }
                                                is FileSaveResult.Error -> {
                                                    Toast.makeText(context, L.ide.screen.saveErrorMessage(result.message), Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = activeTab.isModified || isExternal,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = if (isExternal) L.ide.screen.saveAs else L.ide.screen.saveFile,
                                    tint = if (activeTab.isModified || isExternal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Run Action Split Button & Dropdown Menu
                        Box {
                            val isCustomMode = selectedProfileName != null
                            val customProfile = if (isCustomMode) runConfigurations.find { it.name == selectedProfileName } else null

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCustomMode) GeminiBlue.copy(alpha = 0.18f) else ai.antigem.theme.AppColors.success.copy(alpha = 0.14f))
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isCustomMode && customProfile != null) {
                                            executeRun(customProfile)
                                        } else {
                                            val activePath = activeTabPath
                                            val projectPath = activeProject?.path ?: ""
                                            if (!activePath.isNullOrBlank()) {
                                                if (RunConfigManager.hasDefaultRunnerForFile(activePath)) {
                                                    val runner = RunConfigManager.getDefaultRunnerForFile(activePath, projectPath)
                                                    if (runner != null) {
                                                        executeRun(runner)
                                                    } else {
                                                        showAddProfileDialog = true
                                                    }
                                                } else {
                                                    val ext = File(activePath).extension
                                                    Toast.makeText(context, L.ide.screen.noDefaultRunnerForExtConfigure(ext), Toast.LENGTH_SHORT).show()
                                                    showAddProfileDialog = true
                                                }
                                            } else {
                                                Toast.makeText(context, L.ide.screen.openAFileOrSelectA, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = if (isCustomMode) L.ide.screen.runProfileSelectedprofilename(selectedProfileName) else L.ide.screen.runActiveFile,
                                            tint = if (isCustomMode) GeminiBlue else ai.antigem.theme.AppColors.success,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (isCustomMode) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(GeminiBlue)
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        refreshRunConfigurations()
                                        showRunMenu = true
                                    },
                                    modifier = Modifier
                                        .size(22.dp)
                                        .padding(end = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = L.ide.screen.runConfigurations,
                                        tint = if (isCustomMode) GeminiBlue else ai.antigem.theme.AppColors.success,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showRunMenu,
                                onDismissRequest = { showRunMenu = false },
                                modifier = Modifier.widthIn(min = 250.dp)
                            ) {
                                val activePath = activeTabPath
                                val activeFile = activePath?.let { File(it) }
                                val isAutoMode = selectedProfileName == null

                                // Mode 1: Auto (Active Tab File)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = L.ide.screen.autoRunActiveFile,
                                                fontWeight = if (isAutoMode) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = if (activeFile != null) activeFile.name else L.ide.screen.noFileOpen,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = if (isAutoMode) ai.antigem.theme.AppColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (isAutoMode) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = L.common.selected,
                                                tint = ai.antigem.theme.AppColors.success,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        showRunMenu = false
                                        selectedProfileName = null
                                        if (activePath != null) {
                                            if (RunConfigManager.hasDefaultRunnerForFile(activePath)) {
                                                val runner = RunConfigManager.getDefaultRunnerForFile(activePath, activeProject?.path)
                                                if (runner != null) executeRun(runner)
                                            } else {
                                                val ext = File(activePath).extension
                                                Toast.makeText(context, L.ide.screen.noDefaultRunnerForExtConfigure(ext), Toast.LENGTH_SHORT).show()
                                                showAddProfileDialog = true
                                            }
                                        } else {
                                            Toast.makeText(context, L.ide.screen.openAFileToRun, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Mode 2: Project Configurations (Custom Mode)
                                Text(
                                    text = "PROJECT PROFILES (CUSTOM MODE)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )

                                if (runConfigurations.isNotEmpty()) {
                                    runConfigurations.forEach { profile ->
                                        val isSelected = selectedProfileName == profile.name
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = profile.name,
                                                            fontSize = 13.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        val desc = profile.command ?: ""
                                                        if (desc.isNotBlank()) {
                                                            Text(
                                                                text = desc,
                                                                fontSize = 10.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Tune,
                                                    contentDescription = null,
                                                    tint = if (isSelected) GeminiBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            trailingIcon = {
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = L.common.selected,
                                                        tint = GeminiBlue,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showRunMenu = false
                                                selectedProfileName = profile.name
                                                executeRun(profile)
                                            }
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                L.ide.screen.noCustomProfilesConfigured,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        },
                                        enabled = false,
                                        onClick = {}
                                    )
                                }

                                if (suggestedRuns.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        text = "SUGGESTED FOR THIS PROJECT",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                    suggestedRuns.take(8).forEach { (kind, template) ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(template.name, fontSize = 13.sp)
                                                    Text(
                                                        text = "$kind · ${template.command}",
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            },
                                            onClick = {
                                                showRunMenu = false
                                                // a sub-folder of the project (React Native's android/) is relative to the project
                                                executeRun(template.toConfig().copy(cwd = template.cwd?.let { "\${workspaceFolder}/$it" } ?: "\${workspaceFolder}"))
                                            }
                                        )
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = { Text(L.ide.screen.openRunScreen, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        showRunMenu = false
                                        ai.antigem.data.run.RunPanel.show(folder = activeProject?.path)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(L.ide.screen.addRunConfiguration, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        showRunMenu = false
                                        val projectPath = activeProject?.path ?: ""
                                        if (projectPath.isBlank()) {
                                            Toast.makeText(context, L.ide.screen.openAProjectFolderFirst, Toast.LENGTH_SHORT).show()
                                        } else {
                                            showAddProfileDialog = true
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(L.ide.screen.editRunJsonInEditor, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        showRunMenu = false
                                        val projectPath = activeProject?.path ?: ""
                                        if (projectPath.isBlank()) {
                                            Toast.makeText(context, L.ide.screen.openAProjectFolderFirst, Toast.LENGTH_SHORT).show()
                                        } else {
                                            val file = RunConfigManager.getOrCreateLaunchJson(projectPath, activeTabPath)
                                            val content = try { file.readText() } catch (_: Exception) { "{}" }
                                            IdeDaemonManager.openOrSelectTab(file.absolutePath, "run.json", content)
                                        }
                                    }
                                )
                            }
                        }

                        // AI Chat Drawer Toggle Button
                        IconButton(
                            onClick = onNavigateToChat,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = L.ide.screen.aiChat,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            // Navigation bars too, so the editor, viewers and run terminal never sit under 3-button navigation
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars)
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    // The run terminal pads navigation bars/IME itself; don't let it add the bar twice
                    .consumeWindowInsets(paddingValues)
            ) {

            // Code Editor & Tabs Area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ai.antigem.theme.AppColors.editorBg)
            ) {
                // Open File Tabs Bar
                if (openTabs.isNotEmpty()) {
                    LazyRow(
                        state = tabListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(ai.antigem.theme.AppColors.editorPanel)
                    ) {
                        items(openTabs) { tab ->
                            val isSelected = tab.path == activeTabPath
                            Row(
                                modifier = Modifier
                                    .height(38.dp)
                                    .background(if (isSelected) ai.antigem.theme.AppColors.editorBg else ai.antigem.theme.AppColors.editorLine)
                                    .clickable { IdeDaemonManager.setActiveTabPath(tab.path) }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (tab.isDiff) {
                                    Icon(
                                        imageVector = Icons.Default.Difference,
                                        contentDescription = null,
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                } else if (tab.isReadOnly) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Read-Only",
                                        tint = ai.antigem.theme.AppColors.warning.copy(alpha = 0.85f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                }
                                Text(
                                    text = if (tab.isModified) "${tab.name} *" else tab.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) (if (tab.isDiff) ClaudeTerracotta else if (tab.isReadOnly) ai.antigem.theme.AppColors.warning else Color.White) else (if (tab.isReadOnly) ai.antigem.theme.AppColors.editorMuted else Color.LightGray),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = L.common.closeTab,
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            if (tab.isModified) {
                                                tabToClose = tab
                                            } else {
                                                IdeDaemonManager.closeTab(tab.path)
                                            }
                                        }
                                )
                            }
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                    }
                }

                // Interactive Find in File Bar
                if (showFindBar && activeTab != null && !isImageFile && !(isSvgFile && !showSvgSource)) {
                    LaunchedEffect(Unit) {
                        currentEditorView?.editor?.clearFocus()
                        currentEditorView?.clearFocus()
                        composeView.requestFocus()
                        for (i in 0 until 5) {
                            delay(50)
                            try {
                                findFocusRequester.requestFocus()
                                keyboardController?.show()
                                break
                            } catch (_: Exception) {}
                        }
                        try {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(composeView, InputMethodManager.SHOW_IMPLICIT)
                        } catch (_: Exception) {}
                    }
                    Surface(
                        color = ai.antigem.theme.AppColors.editorPanel,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Search Input Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .background(ai.antigem.theme.AppColors.editorLine, shape = RoundedCornerShape(4.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        findFocusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    BasicTextField(
                                        value = findQuery,
                                        onValueChange = { newQuery ->
                                            findQuery = newQuery
                                            currentEditorView?.search(newQuery, isCaseSensitive)
                                        },
                                        textStyle = TextStyle(
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        singleLine = true,
                                        cursorBrush = SolidColor(ai.antigem.theme.AppColors.info),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(findFocusRequester),
                                        decorationBox = { innerTextField ->
                                            if (findQuery.isEmpty()) {
                                                Text(
                                                    text = L.ide.screen.findInFile2,
                                                    color = ai.antigem.theme.AppColors.editorMuted,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                    if (findQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                findQuery = ""
                                                currentEditorView?.stopSearch()
                                                searchMatchCount = 0
                                                currentMatchIndex = -1
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = L.common.clear,
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Match Count Indicator
                            if (findQuery.isNotEmpty()) {
                                val matchText = if (searchMatchCount > 0) {
                                    "${if (currentMatchIndex >= 0) currentMatchIndex + 1 else 0}/$searchMatchCount"
                                } else {
                                    "0 matches"
                                }
                                Text(
                                    text = matchText,
                                    color = if (searchMatchCount > 0) Color.LightGray else ai.antigem.theme.AppColors.danger,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }

                            // Previous Match
                            IconButton(
                                onClick = { currentEditorView?.findPrevious() },
                                enabled = searchMatchCount > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = L.ide.screen.previousMatch,
                                    tint = if (searchMatchCount > 0) Color.White else Color.DarkGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Next Match
                            IconButton(
                                onClick = { currentEditorView?.findNext() },
                                enabled = searchMatchCount > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = L.ide.screen.nextMatch,
                                    tint = if (searchMatchCount > 0) Color.White else Color.DarkGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Case Sensitive Toggle
                            IconButton(
                                onClick = {
                                    val newCase = !isCaseSensitive
                                    isCaseSensitive = newCase
                                    currentEditorView?.search(findQuery, newCase)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text(
                                    text = L.ide.screen.aa,
                                    color = if (isCaseSensitive) ai.antigem.theme.AppColors.info else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            // Close Find Bar
                            IconButton(
                                onClick = {
                                    showFindBar = false
                                    findQuery = ""
                                    currentEditorView?.stopSearch()
                                    searchMatchCount = 0
                                    currentMatchIndex = -1
                                    keyboardController?.hide()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = L.ide.screen.closeFind,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Auto-Reload Info Banner
                if (autoUpdateNotification != null) {
                    Surface(
                        color = ai.antigem.theme.AppColors.editorInfoBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.info,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = autoUpdateNotification!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = ai.antigem.theme.AppColors.editorInfoText,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Read-Only Warning Banner
                if (activeTab != null && activeTab.isReadOnly && !activeTab.isDiff) {
                    Surface(
                        color = ai.antigem.theme.AppColors.editorBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.warning,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = L.ide.screen.historicalRevisionReadOnly,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.5.sp,
                                color = ai.antigem.theme.AppColors.editorText,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Disk Conflict Warning Banner
                if (activeTab != null && activeTab.diskConflict) {
                    Surface(
                        color = ai.antigem.theme.AppColors.editorLine,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.warning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = L.ide.screen.fileModifiedOnDiskExternally,
                                style = MaterialTheme.typography.bodySmall,
                                color = ai.antigem.theme.AppColors.warning,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { conflictDialogTab = activeTab },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(L.ide.screen.resolve, color = ai.antigem.theme.AppColors.warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Editor / Asset Viewer / Diff Content Area
                if (activeTab != null) {
                    if (activeTab.isDiff) {
                        UnifiedDiffViewer(
                            filePath = activeTab.diffFile ?: activeTab.path,
                            rawDiff = activeTab.content,
                            isStaged = activeTab.isStagedDiff,
                            commitHash = activeTab.commitHash,
                            onOpenCurrentFile = {
                                coroutineScope.launch {
                                    val projPath = activeProject?.path ?: ""
                                    val rawFilePath = activeTab.diffFile ?: activeTab.path
                                    val fullFilePath = if (File(rawFilePath).isAbsolute) {
                                        rawFilePath
                                    } else if (projPath.isNotBlank()) {
                                        File(projPath, rawFilePath).absolutePath
                                    } else {
                                        rawFilePath
                                    }
                                    openInIde(fullFilePath, File(fullFilePath).name, -1)
                                }
                            },
                            onOpenHistoricalFile = {
                                coroutineScope.launch {
                                    val projPath = activeProject?.path ?: return@launch
                                    val rawFilePath = activeTab.diffFile ?: activeTab.path
                                    val fullFilePath = if (File(rawFilePath).isAbsolute) {
                                        rawFilePath
                                    } else {
                                        File(projPath, rawFilePath).absolutePath
                                    }
                                    val relPath = if (File(rawFilePath).isAbsolute && projPath.isNotBlank()) {
                                        File(rawFilePath).relativeToOrSelf(File(projPath)).path
                                    } else {
                                        rawFilePath
                                    }
                                    val commitHash = activeTab.commitHash
                                    if (commitHash != null) {
                                        val content = GitApiClient.getCommitFileContent(projPath, commitHash, relPath) ?: ""
                                        val tabPath = "commit:$commitHash:$fullFilePath"
                                        val tabName = "${File(fullFilePath).name} (${commitHash.take(7)})"
                                        IdeDaemonManager.openOrSelectTab(
                                            path = tabPath,
                                            name = tabName,
                                            content = content,
                                            isReadOnly = true
                                        )
                                    } else {
                                        val content = GitApiClient.getCommitFileContent(projPath, "HEAD", relPath) ?: (IdeApiClient.readFile(fullFilePath) ?: "")
                                        val tabPath = "revision:HEAD:$fullFilePath"
                                        val tabName = "${File(fullFilePath).name} (HEAD)"
                                        IdeDaemonManager.openOrSelectTab(
                                            path = tabPath,
                                            name = tabName,
                                            content = content,
                                            isReadOnly = true
                                        )
                                    }
                                }
                            },
                            onClose = {
                                IdeDaemonManager.closeTab(activeTab.path)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                    } else if (isImageFile || (isSvgFile && !showSvgSource)) {
                        ImageAssetViewer(
                            filePath = activeTab.path,
                            fileName = activeTab.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            onToggleXmlSource = if (isSvgFile) { { showSvgSource = true } } else null
                        )
                    } else {
                        // the editor follows the app theme's IDE colors (re-applied when the theme changes)
                        val editorThemeColors = ai.antigem.ui.ide.EditorThemeColors.fromAppTheme()
                        key(activeTab.path) {
                            AndroidView<CodeEditorView>(
                                factory = { ctx ->
                                    CodeEditorView(ctx).apply {
                                        applyThemeColors(editorThemeColors)
                                        isWordWrapEnabled = isWordWrap
                                        isReadOnly = activeTab.isReadOnly
                                        setFile(activeTab.name, activeTab.content)
                                        onContentChangeListener = { newText ->
                                            if (!activeTab.isReadOnly) {
                                                IdeDaemonManager.updateTabContent(activeTab.path, newText)
                                            }
                                        }
                                        onUndoRedoStateListener = { u, r ->
                                            canUndo = u
                                            canRedo = r
                                        }
                                        onSearchResultListener = { count, idx ->
                                            searchMatchCount = count
                                            currentMatchIndex = idx
                                        }
                                        currentEditorView = this
                                    }
                                },
                                update = { view ->
                                    currentEditorView = view
                                    view.applyThemeColors(editorThemeColors)
                                    view.editor.isFocusable = isVisible
                                    view.editor.isFocusableInTouchMode = isVisible
                                    view.isFocusable = isVisible
                                    view.isFocusableInTouchMode = isVisible
                                    if (!isVisible && view.hasFocus()) {
                                        view.clearFocus()
                                    }
                                    view.isWordWrapEnabled = isWordWrap
                                    view.isReadOnly = activeTab.isReadOnly
                                    view.setFile(activeTab.name, activeTab.content)
                                    canUndo = view.canUndo()
                                    canRedo = view.canRedo()
                                    view.onContentChangeListener = { newText ->
                                        if (!activeTab.isReadOnly) {
                                            IdeDaemonManager.updateTabContent(activeTab.path, newText)
                                        }
                                    }
                                    view.onUndoRedoStateListener = { u, r ->
                                        canUndo = u
                                        canRedo = r
                                    }
                                    view.onSearchResultListener = { count, idx ->
                                        searchMatchCount = count
                                        currentMatchIndex = idx
                                    }
                                    if (showFindBar && findQuery.isNotEmpty()) {
                                        view.search(findQuery, isCaseSensitive)
                                    }
                                },
                                onRelease = { view ->
                                    if (currentEditorView == view) {
                                        currentEditorView = null
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                            )
                        }
                    }
                } else {

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = Color.DarkGray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = L.ide.screen.selectAFileFromTheSidebar,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            // Unsaved Tab Close Confirmation Dialog
            if (tabToClose != null) {
                val closingTarget = tabToClose!!
                AlertDialog(
                    onDismissRequest = { tabToClose = null },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = ClaudeTerracotta) },
                    title = { Text(L.ide.screen.unsavedChanges, fontWeight = FontWeight.Bold) },
                    text = { Text(L.ide.screen.doYouWantToSaveThe(closingTarget.name)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = tabToClose ?: return@Button
                                tabToClose = null
                                coroutineScope.launch {
                                    val res = IdeApiClient.saveFileDetailed(
                                        path = target.path,
                                        content = target.content,
                                        expectedHash = target.originalHash,
                                        force = false
                                    )
                                    if (res is FileSaveResult.Success) {
                                        IdeDaemonManager.closeTab(target.path)
                                        Toast.makeText(context, L.ide.screen.savedAndClosed, Toast.LENGTH_SHORT).show()
                                    } else if (res is FileSaveResult.Conflict) {
                                        conflictDialogTab = target.copy(diskConflict = true, diskContentOnConflict = res.diskContent)
                                    } else {
                                        Toast.makeText(context, L.ide.screen.failedToSaveFile, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text(L.ide.screen.saveClose)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val target = tabToClose ?: return@TextButton
                                tabToClose = null
                                IdeDaemonManager.closeTab(target.path)
                            }
                        ) {
                            Text(L.ide.screen.dontSave, color = ai.antigem.theme.AppColors.danger)
                        }
                    }
                )
            }

            // External Conflict Resolution Dialog
            if (conflictDialogTab != null) {
                val conflictingTarget = conflictDialogTab!!
                AlertDialog(
                    onDismissRequest = { conflictDialogTab = null },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = ai.antigem.theme.AppColors.warning) },
                    title = { Text(L.ide.screen.fileConflictDetected, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(L.ide.screen.theFileNameHasBeenModified(conflictingTarget.name))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                L.ide.screen.chooseWhetherToOverwriteTheDisk,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = conflictDialogTab ?: return@Button
                                conflictDialogTab = null
                                coroutineScope.launch {
                                    val res = IdeApiClient.saveFileDetailed(
                                        path = target.path,
                                        content = target.content,
                                        force = true
                                    )
                                    if (res is FileSaveResult.Success) {
                                        IdeDaemonManager.markTabSaved(target.path, res.hash)
                                        Toast.makeText(context, L.ide.screen.savedOverwritten, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, L.ide.screen.failedToOverwriteDisk, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text(L.ide.screen.overwriteDiskKeepMine)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val target = conflictDialogTab ?: return@TextButton
                                conflictDialogTab = null
                                IdeDaemonManager.resolveTabConflict(target.path, keepMine = false)
                                Toast.makeText(context, L.ide.screen.revertedToDiskVersion, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(L.ide.screen.revertToDisk, color = ai.antigem.theme.AppColors.danger)
                        }
                    }
                )
            }

            // Full-Screen File Manager & Project Selector Dialog
            if (showFileManager) {
                val initialBrowsePath = remember(activeProject?.path) {
                    val p = activeProject?.path
                    if (!p.isNullOrBlank()) {
                        val parent = java.io.File(p).parent
                        if (!parent.isNullOrBlank() && parent != "/") parent else p
                    } else "~"
                }
                ai.antigem.ui.files.FileExplorerDialog(
                    stateKey = "ide_projects",
                    initialPath = initialBrowsePath,
                    title = L.ide.screen.openAProjectOrFile,
                    confirmLabel = L.ide.screen.openAsProject,
                    onConfirmFolder = { folder ->
                        showFileManager = false
                        val proj = ProjectItem(java.io.File(folder).name.ifBlank { folder }, folder)
                        IdeDaemonManager.setActiveProject(proj)
                        viewModel?.onProjectChanged(proj.path)
                        refreshProjectsAndTree()
                    },
                    onOpenFolderInIde = { folder ->
                        showFileManager = false
                        val proj = ProjectItem(java.io.File(folder).name.ifBlank { folder }, folder)
                        IdeDaemonManager.setActiveProject(proj)
                        viewModel?.onProjectChanged(proj.path)
                        refreshProjectsAndTree()
                    },
                    onProjectCreated = { proj ->
                        showFileManager = false
                        IdeDaemonManager.setActiveProject(proj)
                        viewModel?.onProjectChanged(proj.path)
                        refreshProjectsAndTree()
                    },
                    onOpenedInIde = { showFileManager = false },
                    onDismiss = { showFileManager = false }
                )
            }
            // Save As File Manager Dialog (e.g. for externally opened files or save-as)
            saveAsDialogTab?.let { tabToSave ->
                val initialSavePath = remember(activeProject?.path) {
                    activeProject?.path?.takeIf { it.isNotBlank() } ?: "~"
                }
                ai.antigem.ui.files.FileExplorerDialog(
                    stateKey = "ide_save_as",
                    initialPath = initialSavePath,
                    title = L.ide.screen.saveNameAs(tabToSave.name),
                    saveFileName = tabToSave.name,
                    onSave = { folderPath, fileName ->
                        val cleanFolder = folderPath.trim().trimEnd('/')
                        val cleanFileName = fileName.trim().trimStart('/').trimEnd('/').ifBlank { "file.txt" }
                        val fullPath = if (cleanFolder.isBlank() || cleanFolder == "~") {
                            cleanFileName
                        } else {
                            "$cleanFolder/$cleanFileName"
                        }
                        val contentToSave = currentEditorView?.getText()
                            ?: IdeDaemonManager.openTabs.value.find { it.path == tabToSave.path }?.content
                            ?: tabToSave.content

                        android.util.Log.d("IdeScreen", "[SaveAs] Saving external file: targetFullPath='$fullPath', folder='$cleanFolder', file='$cleanFileName', contentLength=${contentToSave.length}")

                        coroutineScope.launch {
                            val res = IdeApiClient.saveFileDetailed(
                                path = fullPath,
                                content = contentToSave,
                                expectedHash = "",
                                force = true
                            )
                            if (res is FileSaveResult.Success) {
                                IdeDaemonManager.closeTab(tabToSave.path)
                                IdeDaemonManager.openOrSelectTab(fullPath, cleanFileName, contentToSave, res.hash)
                                IdeDaemonManager.markTabSaved(fullPath, res.hash)
                                saveAsDialogTab = null
                                refreshProjectsAndTree()
                                Toast.makeText(context, L.ide.screen.savedToFullpath(fullPath), Toast.LENGTH_SHORT).show()
                            } else if (res is FileSaveResult.Error) {
                                Toast.makeText(context, L.ide.screen.saveErrorMessage(res.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { saveAsDialogTab = null }
                )
            }

            // Binary / Non-Text File Warning Confirmation Dialog
            binaryWarningDialog?.let { tabInfo ->
                AlertDialog(
                    onDismissRequest = { binaryWarningDialog = null },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = ai.antigem.theme.AppColors.warning,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = {
                        Text(L.ide.screen.nonTextFileWarning, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "\"${tabInfo.name}\" appears to be a non-text / binary file (${tabInfo.mimeType}).",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = L.ide.screen.openingItInTheTextEditor,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                binaryWarningDialog = null
                                openInIde(tabInfo.path, tabInfo.name, tabInfo.size, force = true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.warning)
                        ) {
                            Text(L.ide.screen.openAnyway, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { binaryWarningDialog = null }) {
                            Text(L.common.cancel)
                        }
                    }
                )
            }
            // Refused for its size: say why, and offer to share it (streamed, never read into memory)
            tooLargeDialog?.let { info ->
                AlertDialog(
                    onDismissRequest = { tooLargeDialog = null },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = ai.antigem.theme.AppColors.warning,
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = { Text(L.ide.screen.fileTooLarge, fontWeight = FontWeight.Bold) },
                    text = { Text(info.message, style = MaterialTheme.typography.bodyMedium) },
                    confirmButton = {
                        TextButton(onClick = { tooLargeDialog = null }) { Text(L.common.ok) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            tooLargeDialog = null
                            ai.antigem.ui.components.AppToastHelper.showToast(L.ide.screen.preparingFileShare, ai.antigem.ui.components.ChatToastType.INFO)
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                FileShareHelper.shareFile(context = context, filePath = info.path, fileName = info.name)
                            }
                        }) { Text(L.common.share) }
                    }
                )
            }
            // Add Run Configuration Dialog
            if (showAddProfileDialog) {
                val projPath = activeProject?.path ?: ""
                AddRunProfileDialog(
                    projectPath = projPath,
                    activeFilePath = activeTabPath,
                    onDismiss = { showAddProfileDialog = false },
                    onSaveProfile = { newProfile ->
                        showAddProfileDialog = false
                        RunConfigManager.addConfiguration(projPath, newProfile)
                        refreshRunConfigurations()
                        selectedProfileName = newProfile.name
                        Toast.makeText(context, L.ide.screen.addedRunConfigurationName(newProfile.name), Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // In-IDE Full Working Isolated Terminal Overlay (Bottom Sheet / Maximize)
            runnerSession?.let { session ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val density = LocalDensity.current
                    val maxHeightPx = constraints.maxHeight.toFloat()
                    val targetHeightFraction = if (isRunnerMaximized) 1.0f else runnerHeightFraction.coerceIn(0.2f, 1.0f)
                    val panelHeightDp = with(density) { (maxHeightPx * targetHeightFraction).toDp() }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelHeightDp),
                        shape = if (isRunnerMaximized) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                        color = ai.antigem.theme.AppColors.editorBg,
                        border = if (isRunnerMaximized) null else BorderStroke(1.dp, ai.antigem.theme.AppColors.editorText.copy(alpha = 0.2f)),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        LocalTerminalContent(
                            customSession = session,
                            showFloatingPill = false,
                            backgroundColor = ai.antigem.theme.AppColors.terminalBg,
                            isMaximized = isRunnerMaximized,
                            onToggleMaximize = {
                                if (isRunnerMaximized) {
                                    isRunnerMaximized = false
                                    if (runnerHeightFraction >= 0.95f || runnerHeightFraction <= 0.2f) {
                                        runnerHeightFraction = 0.5f
                                    }
                                } else {
                                    isRunnerMaximized = true
                                }
                            },
                            onDragDelta = { dragAmount ->
                                val currentPx = if (isRunnerMaximized) maxHeightPx else maxHeightPx * runnerHeightFraction
                                val newPx = (currentPx - dragAmount).coerceIn(maxHeightPx * 0.2f, maxHeightPx)
                                val fraction = (newPx / maxHeightPx).coerceIn(0.2f, 1.0f)
                                if (dragAmount < 0 && fraction >= 0.98f) {
                                    isRunnerMaximized = true
                                    runnerHeightFraction = 1.0f
                                } else {
                                    isRunnerMaximized = false
                                    runnerHeightFraction = fraction
                                }
                            },
                            onRetry = {
                                closeIdeRun()
                                lastExecutedProfile?.let { executeRun(it) }
                            },
                            onClose = {
                                closeIdeRun()
                            }
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun AddRunProfileDialog(
    projectPath: String,
    activeFilePath: String?,
    onDismiss: () -> Unit,
    onSaveProfile: (RunConfiguration) -> Unit
) {
    val defaultRunner = activeFilePath?.let { RunConfigManager.getDefaultRunnerForFile(it, projectPath) }

    var profileName by remember { mutableStateOf(defaultRunner?.name ?: L.ide.screen.customRun) }
    var commandInput by remember { mutableStateOf(defaultRunner?.command ?: "python3 \"\${file}\"") }
    var cwdInput by remember { mutableStateOf(defaultRunner?.cwd ?: "\${workspaceFolder}") }
    var isDefault by remember { mutableStateOf(false) }

    val presets = listOf(
        L.ide.screen.python to "python3 \"\${file}\"",
        "Node.js" to "node \"\${file}\"",
        L.ide.screen.cProgram to "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && gcc -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
        "C++" to "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && g++ -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
        "TypeScript" to "npx tsx \"\${file}\"",
        "PHP" to "php \"\${file}\"",
        L.ide.screen.bashShell to "bash \"\${file}\"",
        L.ide.screen.rust to "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && rustc \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
        L.ide.screen.go to "go run \"\${file}\"",
        L.ide.screen.devServer to "npm run dev"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Tune, contentDescription = null, tint = ai.antigem.theme.AppColors.success, modifier = Modifier.size(28.dp))
        },
        title = {
            Text(L.ide.screen.addRunConfiguration2, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = L.ide.screen.savedIntoAntigemRunJsonIn,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Presets row
                Text(L.ide.screen.quickPresets, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presets) { (presetName, presetCmd) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                profileName = presetName
                                commandInput = presetCmd
                            },
                            label = { Text(presetName, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text(L.ide.screen.profileName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    label = { Text(L.ide.screen.commandToExecute) },
                    placeholder = { Text("python3 \"\${file}\"") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = cwdInput,
                    onValueChange = { cwdInput = it },
                    label = { Text(L.ide.screen.workingDirectoryCwd) },
                    placeholder = { Text("\${workspaceFolder}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isDefault = !isDefault }
                ) {
                    Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(L.ide.screen.setAsDefaultRunProfile, fontSize = 12.5.sp)
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(L.ide.screen.supportedVariables, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        Text("\${file} • \${fileBasename} • \${fileBasenameNoExtension} • \${fileDirname} • \${workspaceFolder}", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val config = RunConfiguration(
                        name = profileName.trim().ifEmpty { L.ide.screen.runProfile },
                        type = "terminal",
                        command = commandInput.trim(),
                        cwd = cwdInput.trim().ifEmpty { "\${workspaceFolder}" },
                        isDefault = isDefault
                    )
                    onSaveProfile(config)
                },
                colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.success)
            ) {
                Text(L.ide.screen.saveProfile, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L.common.cancel)
            }
        }
    )
}

data class BinaryWarningTab(
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String
)

/** A file the IDE refused for its size, with the reason shown to the user. */
data class TooLargeFile(val path: String, val name: String, val message: String)

/** The folder holding [path] (either slash), or null when there is none. */
private fun parentPathOf(path: String): String? {
    val p = path.trimEnd('/', '\\')
    val i = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
    return if (i > 0) p.substring(0, i) else null
}

/**
 * Checks the active tab's file on the bridge: read again only when its size or change time moved since the last read,
 * and never once it grew past what the editor opens. Returns a note for the user, or null.
 */
private suspend fun checkActiveTabOnDisk(): String? {
    val path = IdeDaemonManager.activeTabPath.value ?: return null
    val tab = IdeDaemonManager.openTabs.value.find { it.path == path } ?: return null
    if (tab.isDiff || tab.isReadOnly || tab.isExternal || IdeFileLauncher.isViewerImage(tab.name)) return null
    val stat = FileExplorerApi.stat(path).getOrNull()?.takeIf { !it.isDir } ?: return null
    val last = IdeDaemonManager.diskStamp(path)
    if (last == null) {
        // a tab opened without a stat: this look is the baseline
        IdeDaemonManager.setDiskStamp(path, stat.size, stat.modTime)
        return null
    }
    if (last.first == stat.size && last.second == stat.modTime) return null
    if (stat.size > IdeFileLauncher.MAX_TEXT_BYTES) {
        IdeDaemonManager.setDiskStamp(path, stat.size, stat.modTime)
        return "${tab.name} changed on disk and is now too large to reload (${FileKinds.formatSize(stat.size)})"
    }
    val read = IdeApiClient.readFileLimited(path, IdeFileLauncher.MAX_TEXT_BYTES) as? FileReadOutcome.Ok ?: return null
    IdeDaemonManager.setDiskStamp(path, stat.size, stat.modTime)
    return if (IdeDaemonManager.updateTabFromDisk(path, read.result.content) == TabDiskUpdateResult.AUTO_UPDATED) {
        L.ide.screen.nameReloadedFromDisk(tab.name)
    } else null
}

