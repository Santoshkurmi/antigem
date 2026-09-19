package com.example.gemini.ui.ide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    onDeleteFile: (path: String) -> Unit,
    onRefreshTree: () -> Unit,
    modifier: Modifier = Modifier
) {
    var projectsDropdownExpanded by mutableStateOf(false)
    var selectedTab by remember { mutableStateOf(SidebarTab.EXPLORER) }
    var showCreateFileDialog by remember { mutableStateOf<String?>(null) } // parentPath
    var isNewFolderMode by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    val daemonStatus by com.example.gemini.data.daemon.TermuxDaemonManager.status.collectAsState()
    val daemonMessage by com.example.gemini.data.daemon.TermuxDaemonManager.statusMessage.collectAsState()

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
                            onOpenFile = onOpenFile,
                            onCreateChildFile = { parentPath, isDir ->
                                isNewFolderMode = isDir
                                showCreateFileDialog = parentPath
                            },
                            onDeleteFile = onDeleteFile
                        )
                    }
                }
            }
        }

        // --- 4. Daemon Connection Status & Logs Footer ---
        var showDaemonLogsDialog by remember { mutableStateOf(false) }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when (daemonStatus) {
                com.example.gemini.data.daemon.DaemonStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                com.example.gemini.data.daemon.DaemonStatus.STARTING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDaemonLogsDialog = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when (daemonStatus) {
                                com.example.gemini.data.daemon.DaemonStatus.RUNNING -> Color(0xFF4CAF50)
                                com.example.gemini.data.daemon.DaemonStatus.STARTING -> Color(0xFFFF9100)
                                else -> Color(0xFFFF5252)
                            },
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Daemon Status: ${daemonStatus.name}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = daemonMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (daemonStatus != com.example.gemini.data.daemon.DaemonStatus.RUNNING) {
                    IconButton(
                        onClick = onRefreshTree,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "View Logs",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (showDaemonLogsDialog) {
            DaemonLogsDialog(onDismiss = { showDaemonLogsDialog = false })
        }
    }

    // --- Create File / Folder Dialog ---
    if (showCreateFileDialog != null) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = null },
            title = { Text(if (isNewFolderMode) "Create Folder" else "Create File") },
            text = {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ClaudeTerracotta),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (newItemName.isEmpty()) {
                                    Text(
                                        text = if (isNewFolderMode) "e.g. components" else "e.g. utils.py",
                                        fontSize = 13.5.sp,
                                        lineHeight = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parent = showCreateFileDialog ?: ""
                        if (newItemName.isNotBlank() && parent.isNotBlank()) {
                            val fullPath = "$parent/${newItemName.trim()}"
                            onCreateFile(fullPath, newItemName.trim(), isNewFolderMode)
                            newItemName = ""
                            showCreateFileDialog = null
                        }
                    },
                    enabled = newItemName.isNotBlank()
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
}

@Composable
fun FileTreeNodeItem(
    node: FileNode,
    depth: Int,
    activeFilePath: String?,
    onOpenFile: (FileNode) -> Unit,
    onCreateChildFile: (parentPath: String, isDir: Boolean) -> Unit,
    onDeleteFile: (path: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
                        expanded = !expanded
                    } else {
                        onOpenFile(node)
                    }
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDir) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
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
                onDismissRequest = { menuExpanded = false }
            ) {
                if (node.isDir) {
                    DropdownMenuItem(
                        text = { Text("+ New File") },
                        onClick = {
                            menuExpanded = false
                            onCreateChildFile(node.path, false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("+ New Folder") },
                        onClick = {
                            menuExpanded = false
                            onCreateChildFile(node.path, true)
                        }
                    )
                    Divider()
                }
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDeleteFile(node.path)
                    }
                )
            }
        }

        if (node.isDir && expanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                FileTreeNodeItem(
                    node = child,
                    depth = depth + 1,
                    activeFilePath = activeFilePath,
                    onOpenFile = onOpenFile,
                    onCreateChildFile = onCreateChildFile,
                    onDeleteFile = onDeleteFile
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
