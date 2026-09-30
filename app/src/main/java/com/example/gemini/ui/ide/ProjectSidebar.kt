package com.example.gemini.ui.ide

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
import com.example.gemini.data.daemon.FileNode
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.theme.ClaudeTerracotta

enum class SidebarTab {
    EXPLORER,
    SOURCE_CONTROL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSidebar(
    projects: List<ProjectItem>,
    activeProject: ProjectItem?,
    fileTree: List<FileNode>,
    activeFilePath: String?,
    onSelectProject: (ProjectItem) -> Unit,
    onOpenFileManager: () -> Unit = {},
    onRemoveProject: (ProjectItem) -> Unit = {},
    onCreateProjectRequested: () -> Unit,
    onOpenFile: (FileNode) -> Unit,
    onOpenFileDiff: (filePath: String, isStaged: Boolean) -> Unit = { _, _ -> },
    onCreateFile: (parentPath: String, name: String, isDir: Boolean) -> Unit,
    onRenameFile: (oldPath: String, newPath: String) -> Unit = { _, _ -> },
    onDeleteFile: (path: String) -> Unit,
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
    var expandedPaths by remember { mutableStateOf(setOf<String>()) }
    val daemonStatus by com.example.gemini.data.daemon.TermuxDaemonManager.status.collectAsState()

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
                        text = activeProject?.name ?: "Select Project",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeProject?.path ?: "No project opened",
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
                        text = { Text("Available Projects", fontWeight = FontWeight.Bold) },
                        onClick = {},
                        enabled = false
                    )
                    HorizontalDivider()

                    if (projects.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No projects found in Termux") },
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
                                                    contentDescription = "Remove project",
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
                        text = { Text("Browse / File Manager...", color = MaterialTheme.colorScheme.primary) },
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
                        text = "Explorer",
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
                        text = "Git",
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
                    text = "FILES",
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
                            contentDescription = "New File",
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
                            contentDescription = "New Folder",
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
                            contentDescription = "File Manager",
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
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // --- 4. Recursive File Tree ---
            if (fileTree.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (daemonStatus != com.example.gemini.data.daemon.DaemonStatus.RUNNING) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "IDE Server Offline",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val ep = AuthPreferences.currentBridgeHttpUrl.removePrefix("http://").removePrefix("https://")
                            Text(
                                text = "Checking $ep (auto-retrying)",
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
                                Text("Retry Now", fontSize = 13.sp)
                            }
                        }
                    } else {
                        Text(
                            text = if (activeProject == null) "Open or create a project to view files" else "Project is empty",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(fileTree) { node ->
                        FileTreeNodeItem(
                            node = node,
                            depth = 0,
                            activeFilePath = activeFilePath,
                            expandedPaths = expandedPaths,
                            onToggleExpand = { path ->
                                expandedPaths = if (expandedPaths.contains(path)) expandedPaths - path else expandedPaths + path
                            },
                            onOpenFile = onOpenFile,
                            onCreateChildFile = { parentPath, isDir ->
                                isNewFolderMode = isDir
                                showCreateFileDialog = parentPath
                                expandedPaths = expandedPaths + parentPath
                            },
                            onRenameRequested = { targetNode ->
                                showRenameDialog = targetNode
                            },
                            onDetailsRequested = { targetNode ->
                                showDetailsDialog = targetNode
                            },
                            onDeleteRequested = { targetNode ->
                                showDeleteDialog = targetNode
                            }
                        )
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
            title = { Text(if (isNewFolderMode) "Create Folder" else "Create File", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    label = { Text(if (isNewFolderMode) "Folder Name" else "File Name") },
                    placeholder = { Text(if (isNewFolderMode) "e.g. components" else "e.g. utils.py") },
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
                            expandedPaths = expandedPaths + parent
                            onCreateFile(fullPath, newItemName.trim(), isNewFolderMode)
                            newItemName = ""
                            showCreateFileDialog = null
                        }
                    },
                    enabled = newItemName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = null }) {
                    Text("Cancel")
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
                    text = "Rename ${if (node.isDir) "Folder" else "File"}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New Name") },
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
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
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
                    text = "Are you sure you want to permanently delete \"${node.name}\"? This action cannot be undone.",
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
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- 4. File / Folder Details Dialog ---
    showDetailsDialog?.let { node ->
        com.example.gemini.ui.components.FileDetailsDialog(
            name = node.name,
            path = node.path,
            isDir = node.isDir,
            size = node.size,
            childCount = if (node.isDir) node.children.size else null,
            onDismiss = { showDetailsDialog = null }
        )
    }
}

@Composable
fun FileTreeNodeItem(
    node: FileNode,
    depth: Int,
    activeFilePath: String?,
    expandedPaths: Set<String>,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (FileNode) -> Unit,
    onCreateChildFile: (parentPath: String, isDir: Boolean) -> Unit,
    onRenameRequested: (FileNode) -> Unit,
    onDetailsRequested: (FileNode) -> Unit,
    onDeleteRequested: (FileNode) -> Unit
) {
    val isExpanded = expandedPaths.contains(node.path)
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
                Icon(
                    imageVector = getFileIcon(node.name),
                    contentDescription = null,
                    tint = getFileIconColor(node.name),
                    modifier = Modifier.size(16.dp)
                )
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
                        contentDescription = "Options",
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
                            text = { Text("New File", fontSize = 13.5.sp) },
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
                            text = { Text("New Folder", fontSize = 13.5.sp) },
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    DropdownMenuItem(
                        text = { Text("Rename", fontSize = 13.5.sp) },
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
                    DropdownMenuItem(
                        text = { Text("Details", fontSize = 13.5.sp) },
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
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 13.5.sp) },
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

        if (node.isDir && isExpanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                FileTreeNodeItem(
                    node = child,
                    depth = depth + 1,
                    activeFilePath = activeFilePath,
                    expandedPaths = expandedPaths,
                    onToggleExpand = onToggleExpand,
                    onOpenFile = onOpenFile,
                    onCreateChildFile = onCreateChildFile,
                    onRenameRequested = onRenameRequested,
                    onDetailsRequested = onDetailsRequested,
                    onDeleteRequested = onDeleteRequested
                )
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
