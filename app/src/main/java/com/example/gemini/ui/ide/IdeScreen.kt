package com.example.gemini.ui.ide

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.Icons
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
import com.example.gemini.data.daemon.DaemonStatus
import com.example.gemini.data.daemon.FileNode
import com.example.gemini.data.daemon.GitApiClient
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.OpenTab
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.ui.components.FileManagerDialog
import java.io.File

import com.example.gemini.data.daemon.mergeProjects
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.ui.chat.ChatViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.gemini.data.daemon.FileSaveResult

import kotlinx.coroutines.delay

import com.example.gemini.data.daemon.TabDiskUpdateResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(
    viewModel: ChatViewModel? = null,
    isVisible: Boolean = true,
    onNavigateToChat: () -> Unit,
    onExecuteRunCommand: (command: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val conversations by (viewModel?.conversations ?: MutableStateFlow(emptyList())).collectAsState()
    val allProjects by TermuxDaemonManager.projects.collectAsState()
    val activeProject by TermuxDaemonManager.activeProject.collectAsState()
    val openTabs by TermuxDaemonManager.openTabs.collectAsState()
    val activeTabPath by TermuxDaemonManager.activeTabPath.collectAsState()

    var fileTree by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isWordWrap by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
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

    val findFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val composeView = LocalView.current

    androidx.activity.compose.BackHandler(enabled = isVisible && showFindBar) {
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

    fun refreshProjectsAndTree() {
        coroutineScope.launch {
            TermuxDaemonManager.checkHealthAndReconnect(isSilent = true)
            val projs = TermuxDaemonManager.loadProjects(conversations)
            val current = TermuxDaemonManager.activeProject.value
            if (current != null) {
                fileTree = IdeApiClient.getFileTree(current.path)
            } else if (projs.isNotEmpty()) {
                TermuxDaemonManager.setActiveProject(projs.first())
                fileTree = IdeApiClient.getFileTree(projs.first().path)
            }
        }
    }

    // Auto-refresh file tree and projects whenever user switches to IDE screen
    LaunchedEffect(isVisible) {
        if (isVisible) {
            TermuxDaemonManager.ensureDaemonStarted()
            refreshProjectsAndTree()
        }
    }

    // React immediately whenever daemon reconnects in the background
    LaunchedEffect(Unit) {
        TermuxDaemonManager.serverReconnectedEvent.collect {
            refreshProjectsAndTree()
        }
    }

    // Periodically poll active tab disk content & file tree in the background while user is in IDE
    LaunchedEffect(isVisible, activeProject?.path, activeTabPath) {
        if (isVisible) {
            while (true) {
                if (activeTabPath != null) {
                    val currentTab = TermuxDaemonManager.openTabs.value.find { it.path == activeTabPath }
                    if (currentTab != null && !currentTab.isDiff) {
                        val diskContent = IdeApiClient.readFile(currentTab.path)
                        if (diskContent != null) {
                            val res = TermuxDaemonManager.updateTabFromDisk(currentTab.path, diskContent)
                            if (res == TabDiskUpdateResult.AUTO_UPDATED) {
                                autoUpdateNotification = "${currentTab.name} reloaded from disk"
                            }
                        }
                    }
                }
                val projPath = activeProject?.path
                if (!projPath.isNullOrBlank()) {
                    val freshTree = IdeApiClient.getFileTree(projPath)
                    if (freshTree.isNotEmpty() && freshTree != fileTree) {
                        fileTree = freshTree
                    }
                }
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

    LaunchedEffect(activeProject) {
        if (activeProject != null) {
            coroutineScope.launch {
                fileTree = IdeApiClient.getFileTree(activeProject?.path)
            }
        }
    }

    val activeTab = openTabs.find { it.path == activeTabPath }
    val activeExt = remember(activeTab?.name) { activeTab?.name?.substringAfterLast('.', "")?.lowercase() ?: "" }
    val isImageFile = activeExt in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico")
    val isSvgFile = activeExt == "svg"
    var showSvgSource by remember(activeTabPath) { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF252526),
                modifier = Modifier.width(300.dp)
            ) {
                ProjectSidebar(
                    projects = allProjects,
                    activeProject = activeProject,
                    fileTree = fileTree,
                    activeFilePath = activeTabPath,
                    onSelectProject = { proj ->
                        TermuxDaemonManager.setActiveProject(proj)
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
                    onOpenFile = { node ->
                        coroutineScope.launch {
                            val content = IdeApiClient.readFile(node.path) ?: ""
                            TermuxDaemonManager.openOrSelectTab(node.path, node.name, content)
                            drawerState.close()
                        }
                    },
                    onOpenFileDiff = { filePath, isStaged ->
                        coroutineScope.launch {
                            val projPath = activeProject?.path ?: return@launch
                            val diffRes = GitApiClient.getDiff(projPath, filePath, isStaged)
                            val diffContent = diffRes?.diff ?: ""
                            TermuxDaemonManager.openDiffTab(filePath, diffContent, isStaged)
                            drawerState.close()
                        }
                    },
                    onCreateFile = { fullPath, _, isDir ->
                        coroutineScope.launch {
                            IdeApiClient.createFileOrDir(fullPath, isDir)
                            fileTree = IdeApiClient.getFileTree(activeProject?.path)
                        }
                    },
                    onDeleteFile = { path ->
                        coroutineScope.launch {
                            IdeApiClient.deleteFileOrDir(path)
                            TermuxDaemonManager.closeTab(path)
                            fileTree = IdeApiClient.getFileTree(activeProject?.path)
                        }
                    },
                    onRefreshTree = { refreshProjectsAndTree() }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Toggle Sidebar"
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeProject?.name ?: "antiGem IDE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        val isTextFile = activeTab != null && (!isImageFile || (isSvgFile && showSvgSource))
                        val isTextEditable = isTextFile && !activeTab.isReadOnly

                        // SVG Toggle Button (Graphic Preview <-> XML Source Code)
                        if (isSvgFile) {
                            IconButton(
                                onClick = { showSvgSource = !showSvgSource },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (showSvgSource) Icons.Default.Image else Icons.Default.Code,
                                    contentDescription = if (showSvgSource) "Preview SVG Graphic" else "Edit SVG XML Source",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Undo Button
                        if (isTextFile) {
                            IconButton(
                                onClick = { currentEditorView?.undo() },
                                enabled = canUndo && isTextEditable,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Undo,
                                    contentDescription = "Undo",
                                    tint = if (canUndo && isTextEditable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Redo Button
                            IconButton(
                                onClick = { currentEditorView?.redo() },
                                enabled = canRedo && isTextEditable,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Redo,
                                    contentDescription = "Redo",
                                    tint = if (canRedo && isTextEditable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Find / Search in File Button
                            IconButton(
                                onClick = {
                                    showFindBar = !showFindBar
                                    if (!showFindBar) {
                                        currentEditorView?.stopSearch()
                                        findQuery = ""
                                        searchMatchCount = 0
                                        currentMatchIndex = -1
                                        keyboardController?.hide()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Find in file",
                                    tint = if (showFindBar) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Save Button
                        if (activeTab != null && !activeTab.isReadOnly && (!isImageFile || (isSvgFile && showSvgSource))) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val result = IdeApiClient.saveFileDetailed(
                                            path = activeTab.path,
                                            content = activeTab.content,
                                            expectedHash = activeTab.originalHash,
                                            force = false
                                        )
                                        when (result) {
                                            is FileSaveResult.Success -> {
                                                TermuxDaemonManager.markTabSaved(activeTab.path, result.hash)
                                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                            }
                                            is FileSaveResult.Conflict -> {
                                                conflictDialogTab = activeTab.copy(
                                                    diskConflict = true,
                                                    diskContentOnConflict = result.diskContent
                                                )
                                            }
                                            is FileSaveResult.Error -> {
                                                Toast.makeText(context, "Save Error: ${result.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                enabled = activeTab.isModified,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save File",
                                    tint = if (activeTab.isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Word Wrap Toggle Button
                        if (!isImageFile && !(isSvgFile && !showSvgSource)) {
                            IconButton(
                                onClick = { isWordWrap = !isWordWrap },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.WrapText,
                                    contentDescription = "Toggle Word Wrap",
                                    tint = if (isWordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Run Project Button
                        IconButton(
                            onClick = {
                                val projPath = activeProject?.path ?: ""
                                if (projPath.isNotBlank()) {
                                    onExecuteRunCommand("cd \"$projPath\" && (python3 main.py || node index.js || bash run.sh || ./gradlew run)")
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // AI Chat Drawer Toggle Button
                        IconButton(
                            onClick = onNavigateToChat,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Chat",
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
            contentWindowInsets = WindowInsets.statusBars
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {

            // Code Editor & Tabs Area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E))
            ) {
                // Open File Tabs Bar
                if (openTabs.isNotEmpty()) {
                    LazyRow(
                        state = tabListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(Color(0xFF252526))
                    ) {
                        items(openTabs) { tab ->
                            val isSelected = tab.path == activeTabPath
                            Row(
                                modifier = Modifier
                                    .height(38.dp)
                                    .background(if (isSelected) Color(0xFF1E1E1E) else Color(0xFF2D2D2D))
                                    .clickable { TermuxDaemonManager.setActiveTabPath(tab.path) }
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
                                        tint = Color(0xFFFBBF24).copy(alpha = 0.85f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                }
                                Text(
                                    text = if (tab.isModified) "${tab.name} *" else tab.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) (if (tab.isDiff) ClaudeTerracotta else if (tab.isReadOnly) Color(0xFFFDE68A) else Color.White) else (if (tab.isReadOnly) Color(0xFF9CA3AF) else Color.LightGray),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            if (tab.isModified) {
                                                tabToClose = tab
                                            } else {
                                                TermuxDaemonManager.closeTab(tab.path)
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
                        color = Color(0xFF252526),
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
                                    .background(Color(0xFF3C3C3C), shape = RoundedCornerShape(4.dp))
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
                                        cursorBrush = SolidColor(Color(0xFF007ACC)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(findFocusRequester),
                                        decorationBox = { innerTextField ->
                                            if (findQuery.isEmpty()) {
                                                Text(
                                                    text = "Find in file...",
                                                    color = Color(0xFF888888),
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
                                                contentDescription = "Clear",
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
                                    color = if (searchMatchCount > 0) Color.LightGray else Color(0xFFEF4444),
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
                                    contentDescription = "Previous Match",
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
                                    contentDescription = "Next Match",
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
                                    text = "Aa",
                                    color = if (isCaseSensitive) Color(0xFF007ACC) else Color.Gray,
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
                                    contentDescription = "Close Find",
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
                        color = Color(0xFF1E3A8A),
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
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = autoUpdateNotification!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFDBEAFE),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Read-Only Warning Banner
                if (activeTab != null && activeTab.isReadOnly && !activeTab.isDiff) {
                    Surface(
                        color = Color(0xFF1E293B),
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
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Historical Revision • Read-Only",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.5.sp,
                                color = Color(0xFFCBD5E1),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Disk Conflict Warning Banner
                if (activeTab != null && activeTab.diskConflict) {
                    Surface(
                        color = Color(0xFF451A03),
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
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "File modified on disk externally",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFDE68A),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { conflictDialogTab = activeTab },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Resolve", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                    val content = IdeApiClient.readFile(fullFilePath) ?: ""
                                    TermuxDaemonManager.openOrSelectTab(
                                        path = fullFilePath,
                                        name = File(fullFilePath).name,
                                        content = content,
                                        isReadOnly = false
                                    )
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
                                        TermuxDaemonManager.openOrSelectTab(
                                            path = tabPath,
                                            name = tabName,
                                            content = content,
                                            isReadOnly = true
                                        )
                                    } else {
                                        val content = GitApiClient.getCommitFileContent(projPath, "HEAD", relPath) ?: (IdeApiClient.readFile(fullFilePath) ?: "")
                                        val tabPath = "revision:HEAD:$fullFilePath"
                                        val tabName = "${File(fullFilePath).name} (HEAD)"
                                        TermuxDaemonManager.openOrSelectTab(
                                            path = tabPath,
                                            name = tabName,
                                            content = content,
                                            isReadOnly = true
                                        )
                                    }
                                }
                            },
                            onClose = {
                                TermuxDaemonManager.closeTab(activeTab.path)
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
                        key(activeTab.path) {
                            AndroidView<CodeEditorView>(
                                factory = { ctx ->
                                    CodeEditorView(ctx).apply {
                                        isWordWrapEnabled = isWordWrap
                                        isReadOnly = activeTab.isReadOnly
                                        setFile(activeTab.name, activeTab.content)
                                        onContentChangeListener = { newText ->
                                            if (!activeTab.isReadOnly) {
                                                TermuxDaemonManager.updateTabContent(activeTab.path, newText)
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
                                    view.isWordWrapEnabled = isWordWrap
                                    view.isReadOnly = activeTab.isReadOnly
                                    view.setFile(activeTab.name, activeTab.content)
                                    canUndo = view.canUndo()
                                    canRedo = view.canRedo()
                                    view.onContentChangeListener = { newText ->
                                        if (!activeTab.isReadOnly) {
                                            TermuxDaemonManager.updateTabContent(activeTab.path, newText)
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
                                text = "Select a file from the sidebar to start editing",
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
                    title = { Text("Unsaved Changes", fontWeight = FontWeight.Bold) },
                    text = { Text("Do you want to save the changes made to '${closingTarget.name}' before closing?") },
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
                                        TermuxDaemonManager.closeTab(target.path)
                                        Toast.makeText(context, "Saved and closed", Toast.LENGTH_SHORT).show()
                                    } else if (res is FileSaveResult.Conflict) {
                                        conflictDialogTab = target.copy(diskConflict = true, diskContentOnConflict = res.diskContent)
                                    } else {
                                        Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text("Save & Close")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val target = tabToClose ?: return@TextButton
                                tabToClose = null
                                TermuxDaemonManager.closeTab(target.path)
                            }
                        ) {
                            Text("Don't Save", color = Color(0xFFE57373))
                        }
                    }
                )
            }

            // External Conflict Resolution Dialog
            if (conflictDialogTab != null) {
                val conflictingTarget = conflictDialogTab!!
                AlertDialog(
                    onDismissRequest = { conflictDialogTab = null },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA726)) },
                    title = { Text("File Conflict Detected", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text("The file '${conflictingTarget.name}' has been modified on disk or by AI.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Choose whether to overwrite the disk version with your editor changes or reload the version from disk.",
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
                                        TermuxDaemonManager.markTabSaved(target.path, res.hash)
                                        Toast.makeText(context, "Saved (Overwritten)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to overwrite disk", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Text("Overwrite Disk (Keep Mine)")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                val target = conflictDialogTab ?: return@TextButton
                                conflictDialogTab = null
                                TermuxDaemonManager.resolveTabConflict(target.path, keepMine = false)
                                Toast.makeText(context, "Reverted to disk version", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Revert to Disk", color = Color(0xFFE57373))
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
                FileManagerDialog(
                    initialPath = initialBrowsePath,
                    onOpenAsProject = { proj ->
                        showFileManager = false
                        TermuxDaemonManager.setActiveProject(proj)
                        viewModel?.onProjectChanged(proj.path)
                        refreshProjectsAndTree()
                    },
                    onOpenFileInEditor = { path, name ->
                        coroutineScope.launch {
                            val content = IdeApiClient.readFile(path) ?: ""
                            TermuxDaemonManager.openOrSelectTab(path, name, content)
                            showFileManager = false
                        }
                    },
                    onDismiss = { showFileManager = false }
                )
            }
        }
    }
}
}

