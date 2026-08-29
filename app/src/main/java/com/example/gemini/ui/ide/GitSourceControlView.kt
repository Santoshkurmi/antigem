package com.example.gemini.ui.ide

import android.widget.Toast
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.daemon.*
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSourceControlView(
    activeProject: ProjectItem?,
    onOpenFileDiff: (filePath: String, isStaged: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<GitStatusResponse?>(null) }
    var branches by remember { mutableStateOf<List<GitBranchInfo>>(emptyList()) }
    var commitLogs by remember { mutableStateOf<List<GitCommitLog>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }

    var commitMessage by remember { mutableStateOf("") }
    var stagedExpanded by remember { mutableStateOf(true) }
    var changesExpanded by remember { mutableStateOf(true) }
    var untrackedExpanded by remember { mutableStateOf(true) }
    var historyExpanded by remember { mutableStateOf(false) }

    var showBranchMenu by remember { mutableStateOf(false) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showStashMenu by remember { mutableStateOf(false) }
    var showStashDialog by remember { mutableStateOf(false) }
    var stashMessage by remember { mutableStateOf("") }

    fun refreshGitData() {
        val projPath = activeProject?.path ?: return
        scope.launch {
            val s = GitApiClient.getStatus(projPath)
            status = s
            branches = GitApiClient.getBranches(projPath)
            if (historyExpanded || commitLogs.isEmpty()) {
                commitLogs = GitApiClient.getLog(projPath, 20)
            }
        }
    }

    // Initial load & Project switch
    LaunchedEffect(activeProject?.path) {
        if (activeProject != null) {
            isLoading = true
            refreshGitData()
            isLoading = false
        }
    }

    // Auto-detection: smart debounced background observer while Git tab is open (every 3 seconds)
    LaunchedEffect(activeProject?.path) {
        while (true) {
            delay(3000)
            val projPath = activeProject?.path ?: continue
            val freshStatus = GitApiClient.getStatus(projPath)
            if (freshStatus != null && freshStatus != status) {
                status = freshStatus
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // --- 1. Branch & Sync Header ---
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.15f),
                        modifier = Modifier.clickable { showBranchMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AltRoute,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = status?.branch ?: "HEAD",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = ClaudeTerracotta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showBranchMenu,
                        onDismissRequest = { showBranchMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("+ Create New Branch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showBranchMenu = false
                                newBranchName = ""
                                showNewBranchDialog = true
                            }
                        )
                        HorizontalDivider()
                        branches.forEach { b ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (b.isCurrent) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = b.name,
                                            fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (b.isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                onClick = {
                                    showBranchMenu = false
                                    val projPath = activeProject?.path ?: return@DropdownMenuItem
                                    scope.launch {
                                        isOperating = true
                                        val ok = GitApiClient.checkoutBranch(projPath, b.name, create = false)
                                        if (ok) {
                                            refreshGitData()
                                            Toast.makeText(context, "Switched to ${b.name}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to switch branch", Toast.LENGTH_SHORT).show()
                                        }
                                        isOperating = false
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Pull Button
                IconButton(
                    onClick = {
                        val projPath = activeProject?.path ?: return@IconButton
                        scope.launch {
                            isOperating = true
                            val ok = GitApiClient.pull(projPath)
                            refreshGitData()
                            Toast.makeText(context, if (ok) "Git Pull Successful" else "Git Pull Failed", Toast.LENGTH_SHORT).show()
                            isOperating = false
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = "Pull", modifier = Modifier.size(16.dp))
                }

                // Push Button
                IconButton(
                    onClick = {
                        val projPath = activeProject?.path ?: return@IconButton
                        scope.launch {
                            isOperating = true
                            val ok = GitApiClient.push(projPath)
                            refreshGitData()
                            Toast.makeText(context, if (ok) "Git Push Successful" else "Git Push Failed", Toast.LENGTH_SHORT).show()
                            isOperating = false
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Push", modifier = Modifier.size(16.dp))
                }

                // Stash Menu
                Box {
                    IconButton(
                        onClick = { showStashMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Archive, contentDescription = "Stash", modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = showStashMenu,
                        onDismissRequest = { showStashMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📦 Stash Changes...") },
                            onClick = {
                                showStashMenu = false
                                stashMessage = ""
                                showStashDialog = true
                            }
                        )
                        if (status?.hasStash == true) {
                            DropdownMenuItem(
                                text = { Text("📤 Pop Stash (Apply & Drop)") },
                                onClick = {
                                    showStashMenu = false
                                    val projPath = activeProject?.path ?: return@DropdownMenuItem
                                    scope.launch {
                                        isOperating = true
                                        val ok = GitApiClient.stashPop(projPath)
                                        refreshGitData()
                                        Toast.makeText(context, if (ok) "Stash applied!" else "Stash pop failed", Toast.LENGTH_SHORT).show()
                                        isOperating = false
                                    }
                                }
                            )
                        }
                    }
                }

                // Refresh Button
                IconButton(
                    onClick = { refreshGitData() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. Commit Message Box ---
        OutlinedTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            placeholder = { Text("Commit message (Cmd+Enter to commit)", fontSize = 12.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 90.dp),
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ClaudeTerracotta,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Commit Button
        Button(
            onClick = {
                val projPath = activeProject?.path ?: return@Button
                if (commitMessage.isBlank()) {
                    Toast.makeText(context, "Please enter a commit message", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                scope.launch {
                    isOperating = true
                    // Auto-stage all if nothing staged
                    if (status?.stagedFiles.isNullOrEmpty()) {
                        GitApiClient.stage(projPath, emptyList())
                    }
                    val ok = GitApiClient.commit(projPath, commitMessage.trim())
                    if (ok) {
                        commitMessage = ""
                        refreshGitData()
                        Toast.makeText(context, "Committed successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Commit failed", Toast.LENGTH_SHORT).show()
                    }
                    isOperating = false
                }
            },
            enabled = !isOperating && (status?.hasChanges == true || commitMessage.isNotBlank()),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ClaudeTerracotta,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        ) {
            if (isOperating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Commit", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 3. Changes / Staged Lists & History (Scrollable) ---
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // STAGED CHANGES SECTION
            val staged = status?.stagedFiles ?: emptyList()
            if (staged.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "STAGED CHANGES",
                        count = staged.size,
                        isExpanded = stagedExpanded,
                        onToggle = { stagedExpanded = !stagedExpanded },
                        actions = {
                            IconButton(
                                onClick = {
                                    val projPath = activeProject?.path ?: return@IconButton
                                    scope.launch {
                                        GitApiClient.unstage(projPath, emptyList())
                                        refreshGitData()
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Remove, contentDescription = "Unstage All", modifier = Modifier.size(14.dp))
                            }
                        }
                    )
                }

                if (stagedExpanded) {
                    items(staged) { file ->
                        GitFileRow(
                            file = file,
                            onClick = { onOpenFileDiff(file.path, true) },
                            onAction = {
                                val projPath = activeProject?.path ?: return@GitFileRow
                                scope.launch {
                                    GitApiClient.unstage(projPath, listOf(file.path))
                                    refreshGitData()
                                }
                            },
                            actionIcon = Icons.Default.Remove,
                            actionDesc = "Unstage"
                        )
                    }
                }
            }

            // UNSTAGED CHANGES SECTION
            val unstaged = status?.unstagedFiles ?: emptyList()
            if (unstaged.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "CHANGES",
                        count = unstaged.size,
                        isExpanded = changesExpanded,
                        onToggle = { changesExpanded = !changesExpanded },
                        actions = {
                            Row {
                                IconButton(
                                    onClick = {
                                        val projPath = activeProject?.path ?: return@IconButton
                                        scope.launch {
                                            GitApiClient.discard(projPath, emptyList())
                                            refreshGitData()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Undo, contentDescription = "Discard All", modifier = Modifier.size(13.dp), tint = Color(0xFFE57373))
                                }
                                IconButton(
                                    onClick = {
                                        val projPath = activeProject?.path ?: return@IconButton
                                        scope.launch {
                                            GitApiClient.stage(projPath, emptyList())
                                            refreshGitData()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = "Stage All", modifier = Modifier.size(14.dp), tint = Color(0xFF81C784))
                                }
                            }
                        }
                    )
                }

                if (changesExpanded) {
                    items(unstaged) { file ->
                        GitFileRow(
                            file = file,
                            onClick = { onOpenFileDiff(file.path, false) },
                            onAction = {
                                val projPath = activeProject?.path ?: return@GitFileRow
                                scope.launch {
                                    GitApiClient.stage(projPath, listOf(file.path))
                                    refreshGitData()
                                }
                            },
                            actionIcon = Icons.Default.Add,
                            actionDesc = "Stage",
                            onDiscard = {
                                val projPath = activeProject?.path ?: return@GitFileRow
                                scope.launch {
                                    GitApiClient.discard(projPath, listOf(file.path))
                                    refreshGitData()
                                }
                            }
                        )
                    }
                }
            }

            // UNTRACKED FILES SECTION
            val untracked = status?.untrackedFiles ?: emptyList()
            if (untracked.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "UNTRACKED",
                        count = untracked.size,
                        isExpanded = untrackedExpanded,
                        onToggle = { untrackedExpanded = !untrackedExpanded },
                        actions = {
                            IconButton(
                                onClick = {
                                    val projPath = activeProject?.path ?: return@IconButton
                                    scope.launch {
                                        GitApiClient.stage(projPath, untracked.map { it.path })
                                        refreshGitData()
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Stage All Untracked", modifier = Modifier.size(14.dp), tint = Color(0xFF81C784))
                            }
                        }
                    )
                }

                if (untrackedExpanded) {
                    items(untracked) { file ->
                        GitFileRow(
                            file = file,
                            onClick = { onOpenFileDiff(file.path, false) },
                            onAction = {
                                val projPath = activeProject?.path ?: return@GitFileRow
                                scope.launch {
                                    GitApiClient.stage(projPath, listOf(file.path))
                                    refreshGitData()
                                }
                            },
                            actionIcon = Icons.Default.Add,
                            actionDesc = "Stage",
                            onDiscard = {
                                val projPath = activeProject?.path ?: return@GitFileRow
                                scope.launch {
                                    GitApiClient.discard(projPath, listOf(file.path))
                                    refreshGitData()
                                }
                            }
                        )
                    }
                }
            }

            // Empty state if no changes
            if (status != null && status?.hasChanges == false) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Working tree is clean", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // COMMIT HISTORY SECTION
            item {
                Spacer(modifier = Modifier.height(12.dp))
                SectionHeader(
                    title = "COMMIT HISTORY",
                    count = commitLogs.size,
                    isExpanded = historyExpanded,
                    onToggle = {
                        historyExpanded = !historyExpanded
                        if (historyExpanded && commitLogs.isEmpty()) {
                            val projPath = activeProject?.path ?: return@SectionHeader
                            scope.launch { commitLogs = GitApiClient.getLog(projPath, 20) }
                        }
                    }
                )
            }

            if (historyExpanded) {
                items(commitLogs) { commit ->
                    CommitLogRow(commit)
                }
            }
        }
    }

    // Create New Branch Dialog
    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("Create & Checkout Branch", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Branch Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val projPath = activeProject?.path ?: return@TextButton
                        val name = newBranchName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                isOperating = true
                                val ok = GitApiClient.checkoutBranch(projPath, name, create = true)
                                if (ok) {
                                    refreshGitData()
                                    Toast.makeText(context, "Branch $name created & active", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to create branch", Toast.LENGTH_SHORT).show()
                                }
                                isOperating = false
                            }
                        }
                        showNewBranchDialog = false
                    }
                ) {
                    Text("Create", fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Stash Changes Dialog
    if (showStashDialog) {
        AlertDialog(
            onDismissRequest = { showStashDialog = false },
            title = { Text("Stash Changes", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = stashMessage,
                    onValueChange = { stashMessage = it },
                    label = { Text("Stash Message (Optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val projPath = activeProject?.path ?: return@TextButton
                        scope.launch {
                            isOperating = true
                            val ok = GitApiClient.stash(projPath, stashMessage.trim())
                            if (ok) {
                                refreshGitData()
                                Toast.makeText(context, "Changes stashed!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Stash failed", Toast.LENGTH_SHORT).show()
                            }
                            isOperating = false
                        }
                        showStashDialog = false
                    }
                ) {
                    Text("Stash", fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStashDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        actions?.invoke()
    }
}

@Composable
private fun GitFileRow(
    file: GitFileStatus,
    onClick: () -> Unit,
    onAction: () -> Unit,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionDesc: String,
    onDiscard: (() -> Unit)? = null
) {
    val fileName = remember(file.path) { File(file.path).name }
    val parentDir = remember(file.path) { File(file.path).parent ?: "" }

    val (statusBg, statusFg) = when (file.status) {
        "M" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
        "A", "U" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "D" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
        "R" -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0))
        else -> Pair(Color(0xFFF5F5F5), Color(0xFF424242))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status badge (M, A, D, U)
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = statusBg
        ) {
            Text(
                text = file.status,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = statusFg,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (parentDir.isNotBlank()) {
                Text(
                    text = parentDir,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (onDiscard != null) {
            IconButton(
                onClick = onDiscard,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Undo,
                    contentDescription = "Discard",
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        IconButton(
            onClick = onAction,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = actionIcon,
                contentDescription = actionDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun CommitLogRow(commit: GitCommitLog) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = commit.shortHash,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerracotta,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = commit.author,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = commit.date,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = commit.message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}
