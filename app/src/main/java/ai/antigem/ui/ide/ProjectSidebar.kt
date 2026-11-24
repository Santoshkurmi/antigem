package ai.antigem.ui.ide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import ai.antigem.data.daemon.FileNode
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.antigem.i18n.L

enum class SidebarTab {
    EXPLORER,
    SOURCE_CONTROL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSidebar(
    projects: List<ProjectItem>,
    activeProject: ProjectItem?,
    fileTree: IdeFileTreeState,
    activeFilePath: String?,
    /** The file being opened (its row shows a spinner). */
    openingPath: String? = null,
    onSelectProject: (ProjectItem) -> Unit,
    onOpenFileManager: () -> Unit = {},
    onRemoveProject: (ProjectItem) -> Unit = {},
    onCreateProjectRequested: () -> Unit,
    onOpenFile: (FileNode) -> Unit,
    onOpenFileDiff: (filePath: String, isStaged: Boolean) -> Unit = { _, _ -> },
    onCreateFile: (parentPath: String, name: String, isDir: Boolean) -> Unit,
    onRenameFile: (oldPath: String, newPath: String) -> Unit = { _, _ -> },
    onDeleteFile: (path: String) -> Unit,
    onOpenTerminal: (path: String) -> Unit = {},
    onRefreshTree: () -> Unit,
    modifier: Modifier = Modifier
) {
    var projectsDropdownExpanded by mutableStateOf(false)
    var selectedTab by remember { mutableStateOf(SidebarTab.EXPLORER) }
    var showCreateFileDialog by remember { mutableStateOf<String?>(null) } // parentPath
    var isNewFolderMode by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf<FileNode?>(null) }
    var showDeleteDialog by remember { mutableStateOf<FileNode?>(null) }
    var showDetailsDialog by remember { mutableStateOf<FileNode?>(null) }
    val treeScope = rememberCoroutineScope()
    val daemonStatus by ai.antigem.data.daemon.IdeDaemonManager.status.collectAsState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        // --- 1. Project Selector Header ---
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { projectsDropdownExpanded = true }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeProject?.name ?: L.ide.projectSidebar.selectProject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeProject?.path ?: L.ide.projectSidebar.noProjectOpened,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )

                DropdownMenu(
                    expanded = projectsDropdownExpanded,
                    onDismissRequest = { projectsDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(L.ide.projectSidebar.availableProjects, fontWeight = FontWeight.Bold) },
                        onClick = {},
                        enabled = false
                    )
                    HorizontalDivider()

                    if (projects.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(L.ide.projectSidebar.noProjectsFoundInTermux) },
                            onClick = { projectsDropdownExpanded = false }
                        )
                    } else {
                        projects.forEach { proj ->
                            val isSelected = proj.path == activeProject?.path
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = proj.name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = proj.path,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (proj.isCustom) {
                                            IconButton(
                                                onClick = {
                                                    onRemoveProject(proj)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = L.ide.projectSidebar.removeProject,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    projectsDropdownExpanded = false
                                    onSelectProject(proj)
                                }
                            )
                        }
                    }

                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(L.ide.projectSidebar.browseFileManager, color = MaterialTheme.colorScheme.primary) },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            projectsDropdownExpanded = false
                            onOpenFileManager()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("+ New Project", color = MaterialTheme.colorScheme.primary) },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            projectsDropdownExpanded = false
                            onCreateProjectRequested()
                        }
                    )
                }
            }
        }

        // --- 2. Sidebar Navigation Tabs (Explorer vs Source Control) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (selectedTab == SidebarTab.EXPLORER) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .weight(1f)
                    .clickable { selectedTab = SidebarTab.EXPLORER }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (selectedTab == SidebarTab.EXPLORER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = L.ide.projectSidebar.explorer,
                        fontSize = 11.5.sp,
                        fontWeight = if (selectedTab == SidebarTab.EXPLORER) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == SidebarTab.EXPLORER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (selectedTab == SidebarTab.SOURCE_CONTROL) ClaudeTerracotta.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .weight(1f)
                    .clickable { selectedTab = SidebarTab.SOURCE_CONTROL }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AltRoute,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (selectedTab == SidebarTab.SOURCE_CONTROL) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = L.ide.projectSidebar.git,
                        fontSize = 11.5.sp,
                        fontWeight = if (selectedTab == SidebarTab.SOURCE_CONTROL) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == SidebarTab.SOURCE_CONTROL) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (selectedTab == SidebarTab.SOURCE_CONTROL) {
            GitSourceControlView(
                activeProject = activeProject,
                onOpenFileDiff = onOpenFileDiff,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            // --- 3. Explorer Action Bar (Refresh, New File, New Folder) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = L.ide.projectSidebar.files,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )

                Row {
                    IconButton(
                        onClick = {
                            val rootPath = activeProject?.path ?: ""
                            if (rootPath.isNotBlank()) {
                                isNewFolderMode = false
                                showCreateFileDialog = rootPath
                            }
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoteAdd,
                            contentDescription = L.ide.projectSidebar.newFile,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            val rootPath = activeProject?.path ?: ""
                            if (rootPath.isNotBlank()) {
                                isNewFolderMode = true
                                showCreateFileDialog = rootPath
                            }
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = L.ide.projectSidebar.newFolder,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            val rootPath = activeProject?.path ?: ""
                            onOpenTerminal(rootPath)
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = L.common.terminal,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenFileManager,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = L.ide.projectSidebar.fileManager,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = onRefreshTree,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = L.common.refresh,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // --- 4. File Tree: one flat lazy list (only the rows on screen are drawn), folders read when opened ---
            val rootEntries = fileTree.rootEntries
            if (rootEntries.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val rootError = fileTree.rootError
                    if (daemonStatus != ai.antigem.data.daemon.DaemonStatus.RUNNING) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = ai.antigem.theme.AppColors.danger,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = L.ide.projectSidebar.ideServerOffline,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
                            Text(
                                text = L.ide.projectSidebar.checkingEpAutoRetrying(ep),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onRefreshTree,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(L.ide.projectSidebar.retryNow, fontSize = 13.sp)
                            }
                        }
                    } else if (activeProject != null && rootError != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = rootError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            TextButton(onClick = onRefreshTree) { Text(L.common.retry) }
                        }
                    } else if (activeProject != null && rootEntries == null) {
                        AfterDelay { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else {
                        Text(
                            text = if (activeProject == null) L.ide.projectSidebar.openOrCreateAProjectTo else L.ide.projectSidebar.projectIsEmpty,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val rows = fileTree.rows
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(rows, key = { it.key }, contentType = { it::class }) { row ->
                        when (row) {
                            is TreeRow.Item -> FileTreeNodeItem(
                                node = row.node,
                                depth = row.depth,
                                isExpanded = row.expanded,
                                isOpening = openingPath == row.node.path,
                                activeFilePath = activeFilePath,
                                onToggleExpand = { path -> treeScope.launch { fileTree.toggle(path) } },
                                onOpenFile = onOpenFile,
                                onCreateChildFile = { parentPath, isDir ->
                                    isNewFolderMode = isDir
                                    showCreateFileDialog = parentPath
                                    treeScope.launch { fileTree.expand(parentPath) }
                                },
                                onRenameRequested = { targetNode ->
                                    showRenameDialog = targetNode
                                },
                                onDetailsRequested = { targetNode ->
                                    showDetailsDialog = targetNode
                                },
                                onDeleteRequested = { targetNode ->
                                    showDeleteDialog = targetNode
                                },
                                onOpenTerminal = onOpenTerminal
                            )
                            is TreeRow.Loading -> AfterDelay { FileTreeNoteRow(depth = row.depth, text = L.common.loading, loading = true) }
                            is TreeRow.Note -> FileTreeNoteRow(depth = row.depth, text = row.text, isError = row.isError)
                        }
                    }
                }
            }
        }
    }

    // --- 1. Create File / Folder Dialog ---
    if (showCreateFileDialog != null) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = null },
            icon = {
                Icon(
                    imageVector = if (isNewFolderMode) Icons.Default.CreateNewFolder else Icons.Default.NoteAdd,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text(if (isNewFolderMode) L.ide.projectSidebar.createFolder else L.ide.projectSidebar.createFile, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    label = { Text(if (isNewFolderMode) L.ide.projectSidebar.folderName else L.ide.projectSidebar.fileName) },
                    placeholder = { Text(if (isNewFolderMode) L.ide.projectSidebar.eGComponents else "e.g. utils.py") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parent = showCreateFileDialog ?: ""
                        if (newItemName.isNotBlank() && parent.isNotBlank()) {
                            val fullPath = "$parent/${newItemName.trim()}"
                            treeScope.launch { fileTree.expand(parent) }
                            onCreateFile(fullPath, newItemName.trim(), isNewFolderMode)
                            newItemName = ""
                            showCreateFileDialog = null
                        }
                    },
                    enabled = newItemName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text(L.common.create)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = null }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // --- 2. Rename Dialog ---
    showRenameDialog?.let { node ->
        var renameInput by remember(node) { mutableStateOf(node.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DriveFileRenameOutline,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = L.ide.projectSidebar.renameValue(if (node.isDir) L.ide.projectSidebar.folder else L.ide.projectSidebar.file),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(L.ide.projectSidebar.newName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isNotBlank() && trimmed != node.name) {
                            val parent = java.io.File(node.path).parent ?: ""
                            val newPath = if (parent.isNotBlank()) "$parent/$trimmed" else trimmed
                            showRenameDialog = null
                            onRenameFile(node.path, newPath)
                        }
                    },
                    enabled = renameInput.trim().isNotBlank() && renameInput.trim() != node.name,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text(L.common.rename)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // --- 3. Delete Confirmation Dialog ---
    showDeleteDialog?.let { node ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(30.dp)
                )
            },
            title = {
                Text(
                    text = "Delete ${if (node.isDir) "Folder" else "File"}?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = L.ide.projectSidebar.areYouSureYouWantTo(node.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = node.path
                        showDeleteDialog = null
                        onDeleteFile(path)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(L.common.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(L.common.cancel)
                }
            }
        )
    }

    // --- 4. File / Folder Details Dialog ---
    showDetailsDialog?.let { node ->
        ai.antigem.ui.components.FileDetailsDialog(
            name = node.name,
            path = node.path,
            isDir = node.isDir,
            size = node.size,
            childCount = if (node.isDir) fileTree.childCount(node.path) else null,
            onDismiss = { showDetailsDialog = null }
        )
    }
}

/** How long the sidebar waits before showing a spinner: most files and folders open faster, and a flash of one feels slow. */
const val SPINNER_DELAY_MS = 300L

/** Shows [content] only once [SPINNER_DELAY_MS] has passed (nothing, not even its space, before that). */
@Composable
private fun AfterDelay(content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        show = true
    }
    if (show) content()
}

/** A note under a folder in the tree: still loading, empty, cut short, or could not be read. */
@Composable
private fun FileTreeNoteRow(depth: Int, text: String, loading: Boolean = false, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = (depth * 14 + 24).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontSize = 12.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One file or folder row. Rows are drawn flat by the sidebar's lazy list: a folder's children are rows of their own. */
@Composable
fun FileTreeNodeItem(
    node: FileNode,
    depth: Int,
    isExpanded: Boolean,
    isOpening: Boolean,
    activeFilePath: String?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (FileNode) -> Unit,
    onCreateChildFile: (parentPath: String, isDir: Boolean) -> Unit,
    onRenameRequested: (FileNode) -> Unit,
    onDetailsRequested: (FileNode) -> Unit,
    onDeleteRequested: (FileNode) -> Unit,
    onOpenTerminal: (path: String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    val isActive = activeFilePath == node.path

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(start = (depth * 14).dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                )
                .clickable {
                    if (node.isDir) {
                        onToggleExpand(node.path)
                    } else {
                        onOpenFile(node)
                    }
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDir) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
                if (isOpening) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(
                        imageVector = getFileIcon(node.name),
                        contentDescription = null,
                        tint = getFileIconColor(node.name),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Context Menu Button
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = L.common.options,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.widthIn(min = 175.dp)
                ) {
                    if (node.isDir) {
                        DropdownMenuItem(
                            text = { Text(L.ide.projectSidebar.newFile, fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.NoteAdd,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onCreateChildFile(node.path, false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L.ide.projectSidebar.newFolder, fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onCreateChildFile(node.path, true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L.common.terminal, fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = ai.antigem.theme.AppColors.success,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenTerminal(node.path)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    DropdownMenuItem(
                        text = { Text(L.common.rename, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.DriveFileRenameOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRenameRequested(node)
                        }
                    )
                    if (!node.isDir) {
                        DropdownMenuItem(
                            text = { Text(L.common.share, fontSize = 13.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                AppToastHelper.showToast(L.ide.projectSidebar.preparingFileShare, ChatToastType.INFO)
                                coroutineScope.launch(Dispatchers.IO) {
                                    FileShareHelper.shareFile(
                                        context = context,
                                        filePath = node.path,
                                        fileName = node.name
                                    )
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(L.common.details, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDetailsRequested(node)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(L.common.delete, color = MaterialTheme.colorScheme.error, fontSize = 13.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteRequested(node)
                        }
                    )
                }
            }
        }
    }
}

fun getFileIcon(filename: String): ImageVector {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "py" -> Icons.Default.Code
        "js", "ts", "jsx", "tsx" -> Icons.Default.Javascript
        "html", "htm" -> Icons.Default.Html
        "css", "scss" -> Icons.Default.Style
        "kt", "java" -> Icons.Default.IntegrationInstructions
        "json" -> Icons.Default.DataObject
        "md" -> Icons.Default.Description
        "sh", "bash" -> Icons.Default.Terminal
        "cpp", "c", "h" -> Icons.Default.Memory
        "png", "jpg", "jpeg", "svg" -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
fun getFileIconColor(filename: String): Color {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "py" -> Color(0xFF3572A5)
        "js", "ts" -> Color(0xFFF1E05A)
        "html" -> Color(0xFFE34C26)
        "css" -> Color(0xFF563D7C)
        "kt", "java" -> Color(0xFFA97BFF)
        "json" -> Color(0xFF00B0FF)
        "md" -> Color(0xFF00E676)
        "sh" -> Color(0xFFFF9100)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

object FileShareHelper {
    private const val TAG = "FileShareHelper"

    suspend fun shareFile(
        context: Context,
        filePath: String,
        fileName: String
    ) = withContext(Dispatchers.IO) {
        try {
            val localFile = File(filePath)
            val cacheDir = File(context.cacheDir, "shared_files").apply { mkdirs() }

            // Pre-cleanup previous cached files
            try {
                cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
            } catch (_: Exception) {}

            val shareFile = File(cacheDir, fileName)

            if (localFile.exists()) {
                localFile.copyTo(shareFile, overwrite = true)
            } else {
                // a file only the bridge sees: streamed to the cache byte for byte (never read whole into memory)
                val r = ai.antigem.data.files.FileExplorerApi.download(filePath, shareFile)
                if (!r.ok) throw java.io.IOException(r.error ?: "Download failed")
            }

            val shareUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
            } catch (e: Exception) {
                Log.e(TAG, "FileProvider error: ${e.message}", e)
                Uri.fromFile(shareFile)
            }

            val ext = shareFile.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

            withContext(Dispatchers.Main) {
                launchShareChooser(context, shareUri, fileName, mimeType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                AppToastHelper.showToast(L.ide.projectSidebar.shareErrorMessage(e.message), ChatToastType.ERROR)
            }
        }
    }

    private fun launchShareChooser(context: Context, uri: Uri, fileName: String, mimeType: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                putExtra(Intent.EXTRA_TEXT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, L.ide.projectSidebar.shareFilename(fileName)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch share chooser: ${e.message}")
            AppToastHelper.showToast(L.ide.projectSidebar.couldNotOpenShareChooser, ChatToastType.ERROR)
        }
    }
}
