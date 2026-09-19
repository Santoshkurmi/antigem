package com.example.gemini.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.FsItemNode
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global RAM state holder for the File Manager so user location, history stack,
 * and clipboard persist seamlessly across opens.
 */
object FileManagerStateHolder {
    var currentPath: String = "~"
    var history: MutableList<String> = mutableListOf("~")
    var historyIndex: Int = 0
    var clipboardPath: String? = null
    var clipboardIsCut: Boolean = false

    fun navigateTo(path: String) {
        val clean = if (path.isBlank()) "~" else path
        if (historyIndex >= 0 && historyIndex < history.size && history[historyIndex] == clean) {
            currentPath = clean
            return
        }
        // Truncate forward history if navigating to a new branch
        if (historyIndex < history.size - 1) {
            history = history.subList(0, historyIndex + 1).toMutableList()
        }
        history.add(clean)
        historyIndex = history.size - 1
        currentPath = clean
    }

    fun canGoBack(): Boolean = historyIndex > 0

    fun goBack(): String? {
        if (canGoBack()) {
            historyIndex--
            currentPath = history[historyIndex]
            return currentPath
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerDialog(
    initialPath: String? = null,
    onOpenAsProject: ((ProjectItem) -> Unit)? = null,
    onOpenFileInEditor: ((path: String, name: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember {
        val start = initialPath?.takeIf { it.isNotBlank() } ?: FileManagerStateHolder.currentPath
        if (FileManagerStateHolder.currentPath != start) {
            FileManagerStateHolder.navigateTo(start)
        }
        mutableStateOf(FileManagerStateHolder.currentPath)
    }

    var parentPath by remember { mutableStateOf("") }
    var homePath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<FsItemNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Selected item for action sheet
    var selectedItem by remember { mutableStateOf<FsItemNode?>(null) }

    // Dialog state controllers
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FsItemNode?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<FsItemNode?>(null) }
    var showItemOptionsSheet by remember { mutableStateOf<FsItemNode?>(null) }

    var inputName by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf("python") }
    var isOperating by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    fun refreshDir(target: String = currentPath) {
        scope.launch {
            isLoading = true
            errorMessage = null
            selectedItem = null
            val res = IdeApiClient.browseDirectory(target)
            if (res != null) {
                currentPath = res.currentPath
                parentPath = res.parentPath
                homePath = res.homePath
                items = res.items
                FileManagerStateHolder.currentPath = res.currentPath
            } else {
                // If specific path failed, try to fallback to home "~" if not already at "~"
                if (target != "~" && target != "") {
                    val homeRes = IdeApiClient.browseDirectory("~")
                    if (homeRes != null) {
                        currentPath = homeRes.currentPath
                        parentPath = homeRes.parentPath
                        homePath = homeRes.homePath
                        items = homeRes.items
                        FileManagerStateHolder.currentPath = homeRes.currentPath
                        errorMessage = "Directory \"$target\" not found. Returned to Home folder."
                    } else {
                        errorMessage = "Directory \"$target\" does not exist or cannot be accessed."
                    }
                } else {
                    errorMessage = "Cannot browse directory. Ensure Go daemon / IDE bridge is running."
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshDir(currentPath)
    }

    fun jumpTo(path: String) {
        FileManagerStateHolder.navigateTo(path)
        currentPath = path
        refreshDir(path)
    }

    // Handles hardware back button: navigates up/back inside file manager instead of closing immediately
    fun handleNavigateBack() {
        if (FileManagerStateHolder.canGoBack()) {
            val prev = FileManagerStateHolder.goBack()
            if (prev != null) {
                currentPath = prev
                refreshDir(prev)
                return
            }
        }
        if (parentPath.isNotBlank() && parentPath != currentPath) {
            jumpTo(parentPath)
            return
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = { handleNavigateBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(enabled = true) {
            handleNavigateBack()
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // --- 1. Top Compact Header Bar ---
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back / Up Navigation Button
                        IconButton(
                            onClick = { handleNavigateBack() },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Path Bar / Breadcrumbs with horizontal scroll
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))

                                val segments = remember(currentPath) {
                                    val parts = currentPath.split("/").filter { it.isNotBlank() }
                                    if (currentPath.startsWith("/")) listOf("/") + parts else parts
                                }

                                segments.forEachIndexed { idx, seg ->
                                    val targetSubpath = remember(idx, currentPath) {
                                        if (seg == "/") "/"
                                        else {
                                            val validParts = segments.subList(0, idx + 1).filter { it != "/" }
                                            if (currentPath.startsWith("/")) "/" + validParts.joinToString("/")
                                            else validParts.joinToString("/")
                                        }
                                    }

                                    Text(
                                        text = if (seg == "/") "/" else seg,
                                        fontSize = 13.sp,
                                        fontWeight = if (idx == segments.size - 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (idx == segments.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { jumpTo(targetSubpath) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                    if (idx < segments.size - 1 && seg != "/") {
                                        Text(text = "›", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }

                        // Refresh Button
                        IconButton(
                            onClick = { refreshDir() },
                            enabled = !isLoading,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Close Dialog Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // --- 2. Action Buttons Strip (+ Folder, + File, + Project) ---
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // + New Folder
                        OutlinedButton(
                            onClick = {
                                inputName = ""
                                showNewFolderDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = ClaudeTerracotta
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }

                        // + New File
                        OutlinedButton(
                            onClick = {
                                inputName = ""
                                showNewFileDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }

                        // + New Project (Template)
                        Button(
                            onClick = {
                                inputName = ""
                                selectedTemplate = "python"
                                showNewProjectDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Project", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // --- 3. Main File List Area ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ClaudeTerracotta, modifier = Modifier.size(36.dp))
                        }
                    } else if (errorMessage != null && items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Directory Not Accessible",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = errorMessage ?: "The specified directory does not exist or is not accessible.",
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { jumpTo("~") },
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Go to Home (~)")
                                }
                                OutlinedButton(
                                    onClick = { refreshDir() },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    } else if (items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Folder is empty",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (errorMessage != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = errorMessage ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                            items(items, key = { it.path }) { item ->
                                val isSelected = selectedItem?.path == item.path
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (item.isDir) {
                                                jumpTo(item.path)
                                            } else if (onOpenFileInEditor != null) {
                                                onOpenFileInEditor(item.path, item.name)
                                                onDismiss()
                                            } else {
                                                selectedItem = item
                                                showItemOptionsSheet = item
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = resolveFileIcon(item),
                                            contentDescription = null,
                                            tint = resolveFileColor(item),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                fontSize = 13.5.sp,
                                                fontWeight = if (item.isDir) FontWeight.SemiBold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (!item.isDir) {
                                                    Text(
                                                        text = formatFileSize(item.size),
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }
                                                if (item.modTime > 0) {
                                                    Text(
                                                        text = dateFormat.format(Date(item.modTime)),
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                selectedItem = item
                                                showItemOptionsSheet = item
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

                // --- 4. Bottom Action Bar (Clipboard & Open Project) ---
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Clipboard banner if item is cut/copied
                        if (FileManagerStateHolder.clipboardPath != null) {
                            val clipName = remember(FileManagerStateHolder.clipboardPath) {
                                File(FileManagerStateHolder.clipboardPath ?: "").name
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (FileManagerStateHolder.clipboardIsCut) Icons.Default.ContentCut else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${if (FileManagerStateHolder.clipboardIsCut) "Moving" else "Copied"}: $clipName",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(
                                        onClick = {
                                            val src = FileManagerStateHolder.clipboardPath ?: return@TextButton
                                            val dst = File(currentPath, File(src).name).absolutePath
                                            scope.launch {
                                                isOperating = true
                                                val ok = if (FileManagerStateHolder.clipboardIsCut) {
                                                    IdeApiClient.renameFileOrDir(src, dst)
                                                } else {
                                                    IdeApiClient.copyFileOrDir(src, dst)
                                                }
                                                if (ok) {
                                                    FileManagerStateHolder.clipboardPath = null
                                                    refreshDir()
                                                }
                                                isOperating = false
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("Paste Here", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { FileManagerStateHolder.clipboardPath = null },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        // Open Folder as Project Button
                        if (onOpenAsProject != null) {
                            Button(
                                onClick = {
                                    val folderName = File(currentPath).name.ifBlank { "Project" }
                                    val project = ProjectItem(name = folderName, path = currentPath)
                                    onOpenAsProject(project)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open this folder as Project", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Modal Dialogs for File/Folder Actions ---

    // 1. Create New Folder Dialog
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = inputName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isOperating = true
                                val target = File(currentPath, name).absolutePath
                                val ok = IdeApiClient.createFileOrDir(target, isDir = true)
                                if (ok) {
                                    showNewFolderDialog = false
                                    refreshDir()
                                }
                                isOperating = false
                            }
                        }
                    },
                    enabled = inputName.isNotBlank() && !isOperating,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. Create New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("New File", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("File Name (e.g. main.kt, script.py)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = inputName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isOperating = true
                                val target = File(currentPath, name).absolutePath
                                val ok = IdeApiClient.createFileOrDir(target, isDir = false)
                                if (ok) {
                                    showNewFileDialog = false
                                    refreshDir()
                                }
                                isOperating = false
                            }
                        }
                    },
                    enabled = inputName.isNotBlank() && !isOperating,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Create Project Dialog
    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Create Project", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("Project Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Project Template:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val templates = listOf(
                        "python" to "Python",
                        "node" to "Node.js",
                        "web" to "Web (HTML/JS)",
                        "kotlin" to "Kotlin",
                        "cpp" to "C++",
                        "blank" to "Blank"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        templates.forEach { (tmplKey, tmplLabel) ->
                            val isSel = selectedTemplate == tmplKey
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedTemplate = tmplKey }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = { selectedTemplate = tmplKey },
                                        colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(tmplLabel, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = inputName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isOperating = true
                                val created = IdeApiClient.createProject(name = name, template = selectedTemplate, path = currentPath)
                                if (created != null) {
                                    showNewProjectDialog = false
                                    onOpenAsProject?.invoke(created)
                                    onDismiss()
                                }
                                isOperating = false
                            }
                        }
                    },
                    enabled = inputName.isNotBlank() && !isOperating,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Create & Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Rename Dialog
    showRenameDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename ${if (item.isDir) "Folder" else "File"}", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = inputName.trim()
                        if (newName.isNotBlank() && newName != item.name) {
                            scope.launch {
                                isOperating = true
                                val dst = File(File(item.path).parentFile, newName).absolutePath
                                val ok = IdeApiClient.renameFileOrDir(item.path, dst)
                                if (ok) {
                                    showRenameDialog = null
                                    refreshDir()
                                }
                                isOperating = false
                            }
                        }
                    },
                    enabled = inputName.isNotBlank() && inputName.trim() != item.name && !isOperating
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

    // 5. Delete Confirm Dialog
    showDeleteConfirmDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete ${if (item.isDir) "Folder" else "File"}?") },
            text = {
                Text("Are you sure you want to permanently delete \"${item.name}\"? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isOperating = true
                            val ok = IdeApiClient.deleteFileOrDir(item.path)
                            if (ok) {
                                showDeleteConfirmDialog = null
                                refreshDir()
                            }
                            isOperating = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 6. Item Options Sheet
    showItemOptionsSheet?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { showItemOptionsSheet = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(resolveFileIcon(item), contentDescription = null, tint = resolveFileColor(item), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.path, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                HorizontalDivider()

                if (item.isDir && onOpenAsProject != null) {
                    ListItem(
                        headlineContent = { Text("Open as Project in IDE", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = ClaudeTerracotta) },
                        modifier = Modifier.clickable {
                            showItemOptionsSheet = null
                            onOpenAsProject(ProjectItem(item.name, item.path))
                            onDismiss()
                        }
                    )
                } else if (onOpenFileInEditor != null) {
                    ListItem(
                        headlineContent = { Text("Open in Editor", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            showItemOptionsSheet = null
                            onOpenFileInEditor(item.path, item.name)
                            onDismiss()
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text("Copy") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        FileManagerStateHolder.clipboardPath = item.path
                        FileManagerStateHolder.clipboardIsCut = false
                        showItemOptionsSheet = null
                    }
                )

                ListItem(
                    headlineContent = { Text("Cut / Move") },
                    leadingContent = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                    modifier = Modifier.clickable {
                        FileManagerStateHolder.clipboardPath = item.path
                        FileManagerStateHolder.clipboardIsCut = true
                        showItemOptionsSheet = null
                    }
                )

                ListItem(
                    headlineContent = { Text("Rename") },
                    leadingContent = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    modifier = Modifier.clickable {
                        inputName = item.name
                        showRenameDialog = item
                        showItemOptionsSheet = null
                    }
                )

                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        showDeleteConfirmDialog = item
                        showItemOptionsSheet = null
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun resolveFileIcon(item: FsItemNode): ImageVector {
    if (item.isDir) return Icons.Default.Folder
    val ext = item.ext.lowercase()
    return when (ext) {
        ".kt", ".kts", ".java" -> Icons.Default.Code
        ".js", ".jsx", ".ts", ".tsx" -> Icons.Default.Javascript
        ".py" -> Icons.Default.Terminal
        ".html", ".htm", ".css" -> Icons.Default.Web
        ".json", ".yaml", ".yml", ".toml", ".xml" -> Icons.Default.Settings
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg" -> Icons.Default.Image
        ".mp3", ".wav", ".m4a", ".ogg" -> Icons.Default.AudioFile
        ".md", ".txt" -> Icons.Default.Description
        ".zip", ".tar", ".gz" -> Icons.Default.Archive
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun resolveFileColor(item: FsItemNode): Color {
    if (item.isDir) return ClaudeTerracotta
    val ext = item.ext.lowercase()
    return when (ext) {
        ".kt", ".kts" -> Color(0xFF7F52FF)
        ".java" -> Color(0xFFE76F00)
        ".js", ".jsx" -> Color(0xFFF7DF1E)
        ".ts", ".tsx" -> Color(0xFF3178C6)
        ".py" -> Color(0xFF3776AB)
        ".html", ".htm" -> Color(0xFFE34F26)
        ".css" -> Color(0xFF1572B6)
        ".json", ".yaml", ".yml" -> Color(0xFF4CAF50)
        ".png", ".jpg", ".jpeg", ".webp", ".svg" -> Color(0xFF9C27B0)
        ".mp3", ".wav", ".m4a" -> Color(0xFF00BCD4)
        ".md", ".txt" -> Color(0xFF9E9E9E)
        else -> Color(0xFFB0BEC5)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%.1f %s", value, units[minOf(digitGroups, units.size - 1)])
}
