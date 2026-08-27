package com.example.gemini.ui.ide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
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
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import kotlinx.coroutines.launch

data class OpenTab(
    val path: String,
    val name: String,
    var content: String,
    var originalContent: String,
    val isModified: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeScreen(
    onNavigateToChat: () -> Unit,
    onExecuteRunCommand: (command: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // --- Persistent State from TermuxDaemonManager ---
    var isSidebarOpen by remember { mutableStateOf(true) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var isWordWrap by remember { mutableStateOf(false) }

    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    val activeProject by TermuxDaemonManager.activeProject.collectAsState()
    var fileTree by remember { mutableStateOf<List<FileNode>>(emptyList()) }

    val openTabs by TermuxDaemonManager.openTabs.collectAsState()
    val activeTabPath by TermuxDaemonManager.activeTabPath.collectAsState()

    val daemonStatus by TermuxDaemonManager.status.collectAsState()

    // Refresh projects & tree
    fun refreshProjectsAndTree() {
        coroutineScope.launch {
            val list = IdeApiClient.getProjects()
            projects = list
            if (activeProject == null && list.isNotEmpty()) {
                TermuxDaemonManager.setActiveProject(list.first())
            }
            if (activeProject != null) {
                fileTree = IdeApiClient.getFileTree(activeProject?.path)
            }
        }
    }

    // Initialize Go daemon & load projects
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            TermuxDaemonManager.ensureDaemonStarted()
            refreshProjectsAndTree()
        }
    }

    val activeTab = openTabs.find { it.path == activeTabPath }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF252526),
                modifier = Modifier.width(300.dp)
            ) {
                ProjectSidebar(
                    projects = projects,
                    activeProject = activeProject,
                    fileTree = fileTree,
                    activeFilePath = activeTabPath,
                    onSelectProject = { proj ->
                        TermuxDaemonManager.setActiveProject(proj)
                        coroutineScope.launch {
                            fileTree = IdeApiClient.getFileTree(proj.path)
                            drawerState.close()
                        }
                    },
                    onCreateProjectRequested = {
                        showNewProjectDialog = true
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenFile = { node ->
                        coroutineScope.launch {
                            val content = IdeApiClient.readFile(node.path) ?: ""
                            TermuxDaemonManager.openOrSelectTab(node.path, node.name, content)
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
                        // Save Button
                        if (activeTab != null) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val success = IdeApiClient.saveFile(activeTab.path, activeTab.content)
                                        if (success) {
                                            TermuxDaemonManager.markTabSaved(activeTab.path)
                                        }
                                    }
                                },
                                enabled = activeTab.isModified
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save File",
                                    tint = if (activeTab.isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Word Wrap Toggle Button
                        IconButton(onClick = { isWordWrap = !isWordWrap }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = "Toggle Word Wrap",
                                tint = if (isWordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        // Run Project Button
                        IconButton(
                            onClick = {
                                val projPath = activeProject?.path ?: ""
                                if (projPath.isNotBlank()) {
                                    onExecuteRunCommand("cd \"$projPath\" && (python3 main.py || node index.js || bash run.sh || ./gradlew run)")
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run",
                                tint = Color(0xFF4CAF50)
                            )
                        }

                        // AI Chat Drawer Toggle Button
                        IconButton(onClick = onNavigateToChat) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Chat",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
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
                                Text(
                                    text = if (tab.isModified) "${tab.name} *" else tab.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { TermuxDaemonManager.closeTab(tab.path) }
                                )
                            }
                            Spacer(modifier = Modifier.width(1.dp))
                        }
                    }
                }

                // Editor Content View
                if (activeTab != null) {
                    key(activeTab.path) {
                        AndroidView<CodeEditorView>(
                            factory = { ctx ->
                                CodeEditorView(ctx).apply {
                                    isWordWrapEnabled = isWordWrap
                                    setFile(activeTab.name, activeTab.content)
                                    onContentChangeListener = { newText ->
                                        TermuxDaemonManager.updateTabContent(activeTab.path, newText)
                                    }
                                }
                            },
                            update = { view ->
                                view.isWordWrapEnabled = isWordWrap
                                view.setFile(activeTab.name, activeTab.content)
                                view.onContentChangeListener = { newText ->
                                    TermuxDaemonManager.updateTabContent(activeTab.path, newText)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
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
        }
    }
}

    // New Project Dialog
    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreateProject = { name, template ->
                showNewProjectDialog = false
                coroutineScope.launch {
                    val newProj = IdeApiClient.createProject(name, template)
                    if (newProj != null) {
                        TermuxDaemonManager.setActiveProject(newProj)
                        refreshProjectsAndTree()
                    }
                }
            }
        )
    }
}
