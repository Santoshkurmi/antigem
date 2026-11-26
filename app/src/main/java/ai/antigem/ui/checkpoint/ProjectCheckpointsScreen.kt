package ai.antigem.ui.checkpoint

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.checkpoint.CheckpointProjectInfo
import ai.antigem.data.checkpoint.CheckpointProjectState
import ai.antigem.data.checkpoint.CheckpointSettings
import ai.antigem.data.checkpoint.CheckpointSummary
import ai.antigem.data.checkpoint.ProjectCheckpoints
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.accent
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.label
import kotlinx.coroutines.launch
import ai.antigem.i18n.L
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

/**
 * Settings → Project checkpoints: on / off / per project, how long checkpoints are kept, and every project's
 * checkpoints (space used, history with restore, clean up, delete).
 */
@Composable
fun ProjectCheckpointsScreen() {
    val scope = rememberCoroutineScope()
    val settings by ProjectCheckpoints.settings.collectAsState()
    val changes by ProjectCheckpoints.changes.collectAsState()
    var projects by remember { mutableStateOf<List<CheckpointProjectInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var historyFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var cleanFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var deleteAllFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(changes, refresh) {
        ProjectCheckpoints.projects().fold({ projects = it; loadError = null }, { loadError = it.message ?: L.checkpoint.projectCheckpoints.theBridgeDidNotAnswer })
    }

    val openHistory = historyFor
    if (openHistory != null) {
        BackHandler { historyFor = null }
        CheckpointHistory(project = openHistory, onBack = { historyFor = null }, onClean = { cleanFor = openHistory }, onDeleteAll = { deleteAllFor = openHistory })
    } else {
        val s = settings
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                L.checkpoint.projectCheckpoints.beforeEveryMessageYouSendClaude,
                fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
            )
            loadError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(Icons.Outlined.WarningAmber, AppColors.danger, it)
                TextButton(onClick = { refresh++ }) { Text(L.common.tryAgain) }
            }

            SectionLabel(L.checkpoint.projectCheckpoints.checkpoints)
            val mode = s?.mode ?: CheckpointSettings.MODE_OFF
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(CheckpointSettings.MODE_OFF, L.common.off, L.checkpoint.projectCheckpoints.noCheckpointsAreMadeTheDefault),
                    Triple(CheckpointSettings.MODE_SELECTED, L.checkpoint.projectCheckpoints.projectsIChoose, L.checkpoint.projectCheckpoints.aNewChatInAProject),
                    Triple(CheckpointSettings.MODE_ALL, L.checkpoint.projectCheckpoints.allGitProjects, L.checkpoint.projectCheckpoints.everyGitProjectGetsCheckpointsTurn)
                ).forEach { (value, title, desc) ->
                    ai.antigem.ui.claude.PickerOption(
                        title = title,
                        description = desc,
                        color = if (value == CheckpointSettings.MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        selected = mode == value,
                        icon = when (value) {
                            CheckpointSettings.MODE_OFF -> Icons.Outlined.PowerSettingsNew
                            CheckpointSettings.MODE_SELECTED -> Icons.AutoMirrored.Outlined.PlaylistAddCheck
                            else -> Icons.Outlined.AllInclusive
                        },
                        onClick = {
                            if (s != null && mode != value) scope.launch {
                                ProjectCheckpoints.updateSettings(mode = value).onFailure { toastError(it) }
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(L.checkpoint.projectCheckpoints.checkpointsWorkInGitProjectsOnly,
                fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)

            if (s != null) {
                SectionLabel(L.checkpoint.projectCheckpoints.keep)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChoiceRow(L.checkpoint.projectCheckpoints.deleteCheckpointsOlderThan, s.retentionDays, listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to L.common.never)) {
                            scope.launch { ProjectCheckpoints.updateSettings(retentionDays = it).onFailure(::toastError) }
                        }
                        ChoiceRow(L.checkpoint.projectCheckpoints.atMostPerChat, s.maxPerChat, listOf(50 to "50", 100 to "100", 200 to "200", 0 to L.checkpoint.projectCheckpoints.noLimit)) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxPerChat = it).onFailure(::toastError) }
                        }
                        ChoiceRow(L.checkpoint.projectCheckpoints.spacePerProject, s.maxProjectMb, listOf(200 to "200 MB", 500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB", 0 to L.checkpoint.projectCheckpoints.noLimit)) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxProjectMb = it).onFailure(::toastError) }
                        }
                        ChoiceRow(L.checkpoint.projectCheckpoints.skipFilesLargerThan, s.maxFileMb, listOf(5 to "5 MB", 20 to "20 MB", 50 to "50 MB", 100 to "100 MB")) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxFileMb = it).onFailure(::toastError) }
                        }
                        Text(
                            L.checkpoint.projectCheckpoints.oldCheckpointsAreRemovedAutomaticallyThe,
                            fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                        )
                    }
                }
            }

            SectionLabel(L.common.projects)
            val list = projects
            when {
                list == null && loadError == null -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(L.common.loading, fontSize = 13.sp, color = muted)
                }
                list.isNullOrEmpty() -> Text(
                    if (mode == CheckpointSettings.MODE_OFF) L.checkpoint.projectCheckpoints.turnCheckpointsOnAboveThenStart
                    else L.checkpoint.projectCheckpoints.noProjectHasCheckpointsYetThey,
                    fontSize = 12.5.sp, color = muted
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { p ->
                        ProjectCard(
                            p = p,
                            mode = mode,
                            onToggle = { on -> scope.launch { ProjectCheckpoints.setDecision(p.path, if (on) "on" else "off").onFailure(::toastError) } },
                            onHistory = { historyFor = p },
                            onClean = { cleanFor = p },
                            onDeleteAll = { deleteAllFor = p }
                        )
                    }
                }
            }

            SectionLabel(L.checkpoint.projectCheckpoints.goodToKnow)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        L.checkpoint.projectCheckpoints.onlyFilesInsideTheProjectGo,
                        L.checkpoint.projectCheckpoints.filesYourGitignoreIgnoresAndNode,
                        L.checkpoint.projectCheckpoints.aRestoreFirstSavesTheFiles,
                        L.checkpoint.projectCheckpoints.goingBackInAChatEdit,
                        L.checkpoint.projectCheckpoints.theCopiesLiveWithTheBridge
                    ).forEach { t ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Info, null, tint = muted, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    cleanFor?.let { p ->
        CleanOldDialog(p, onDismiss = { cleanFor = null }) { days ->
            cleanFor = null
            scope.launch {
                ProjectCheckpoints.delete(p.path, olderThanDays = days).fold(
                    { r -> AppToastHelper.showToast(L.checkpoint.projectCheckpoints.deletedDeletedCheckpointValueFormatbytes(r.deleted, if (r.deleted == 1) "" else "s", formatBytes((r.sizeBefore - r.sizeAfter).coerceAtLeast(0))), ChatToastType.SUCCESS) },
                    ::toastError
                )
            }
        }
    }
    deleteAllFor?.let { p ->
        var forget by remember(p.path) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteAllFor = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(L.checkpoint.projectCheckpoints.deleteAllCheckpoints, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text(L.checkpoint.projectCheckpoints.everyCheckpointOfNameIsDeleted(p.name, formatBytes(p.sizeBytes)), fontSize = 14.sp, lineHeight = 20.sp)
                    Row(Modifier.padding(top = 10.dp).clickable { forget = !forget }.semantics { role = Role.Checkbox; toggleableState = ToggleableState(forget) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = forget, onCheckedChange = { forget = it }, modifier = Modifier.mergedIntoRow())
                        Text(L.checkpoint.projectCheckpoints.alsoForgetMyAnswerForThis, fontSize = 13.5.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllFor = null
                    if (historyFor?.path == p.path) historyFor = null
                    scope.launch {
                        ProjectCheckpoints.delete(p.path, all = true, forget = forget).fold(
                            { AppToastHelper.showToast(L.checkpoint.projectCheckpoints.allCheckpointsOfNameDeleted(p.name), ChatToastType.SUCCESS) }, ::toastError
                        )
                    }
                }) { Text(L.checkpoint.projectCheckpoints.deleteAll, color = AppColors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleteAllFor = null }) { Text(L.common.cancel) } }
        )
    }
}

private fun toastError(e: Throwable) {
    AppToastHelper.showToast(e.message ?: L.checkpoint.projectCheckpoints.somethingWentWrong, ChatToastType.ERROR)
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, current: Int, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == current, onClick = { if (value != current) onPick(value) }, label = { Text(label, fontSize = 12.5.sp) })
            }
        }
    }
}

@Composable
private fun ProjectCard(
    p: CheckpointProjectInfo, mode: String,
    onToggle: (Boolean) -> Unit, onHistory: () -> Unit, onClean: () -> Unit, onDeleteAll: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (status, color) = when {
        p.tracked -> L.checkpoint.projectCheckpoints.savingCheckpoints to AppColors.success
        !p.exists -> L.checkpoint.projectCheckpoints.folderNotFound to AppColors.danger
        mode == CheckpointSettings.MODE_OFF -> L.checkpoint.projectCheckpoints.checkpointsAreOff to muted
        !p.isGit -> L.checkpoint.projectCheckpoints.notAGitRepository to AppColors.warning
        else -> L.checkpoint.projectCheckpoints.offForThisProject to muted
    }
    val switchOn = when (mode) {
        CheckpointSettings.MODE_ALL -> p.decision != "off"
        else -> p.decision == "on"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(switchOn, onToggle, enabled = mode != CheckpointSettings.MODE_OFF && p.exists)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Folder, null, tint = color, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.path, fontSize = 11.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = switchOn, enabled = mode != CheckpointSettings.MODE_OFF && p.exists, onCheckedChange = onToggle, modifier = Modifier.mergedIntoRow())
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
                if (p.hasData) {
                    Text(
                        "  ·  ${p.count} checkpoint${if (p.count == 1) "" else "s"} · ${formatBytes(p.sizeBytes)}" +
                            if (p.lastTime > 0) " · ${formatCheckpointTime(p.lastTime)}" else "",
                        fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!p.isGit && p.exists && mode != CheckpointSettings.MODE_OFF) {
                Spacer(Modifier.height(4.dp))
                Text(L.checkpoint.projectCheckpoints.runGitInitInThisFolder, fontSize = 11.5.sp, color = muted)
            }
            if (p.hasData) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onHistory) {
                        Icon(Icons.Outlined.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(L.checkpoint.projectCheckpoints.history)
                    }
                    TextButton(onClick = onClean) {
                        Icon(Icons.Outlined.CleaningServices, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(L.checkpoint.projectCheckpoints.cleanOld)
                    }
                    TextButton(onClick = onDeleteAll) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp), tint = AppColors.danger); Spacer(Modifier.width(5.dp))
                        Text(L.checkpoint.projectCheckpoints.deleteAll, color = AppColors.danger)
                    }
                }
            } else Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CleanOldDialog(p: CheckpointProjectInfo, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var days by remember { mutableIntStateOf(7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(L.checkpoint.projectCheckpoints.cleanOldCheckpoints, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text(L.checkpoint.projectCheckpoints.deleteTheCheckpointsOfNameOlder(p.name), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { days = d }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(label, fontSize = 14.sp)
                    }
                }
                Text(L.checkpoint.projectCheckpoints.newerCheckpointsStayAndKeepWorking, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(days) }) { Text(L.common.delete, color = AppColors.danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** One project's checkpoints, newest first: tap one to see what it would change and restore it (files only). */
@Composable
private fun CheckpointHistory(project: CheckpointProjectInfo, onBack: () -> Unit, onClean: () -> Unit, onDeleteAll: () -> Unit) {
    val scope = rememberCoroutineScope()
    val changes by ProjectCheckpoints.changes.collectAsState()
    var items by remember { mutableStateOf<List<CheckpointSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<CheckpointSummary?>(null) }
    LaunchedEffect(project.path, changes) {
        ProjectCheckpoints.history(project.path).fold({ items = it; error = null }, { error = it.message })
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, L.common.back) }
            Column(Modifier.weight(1f)) {
                Text(project.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${items?.size ?: project.count} checkpoints · ${formatBytes(project.sizeBytes)}", fontSize = 12.sp, color = muted)
            }
            IconButton(onClick = onClean) { Icon(Icons.Outlined.CleaningServices, L.checkpoint.projectCheckpoints.cleanOld) }
            IconButton(onClick = onDeleteAll) { Icon(Icons.Outlined.DeleteOutline, L.checkpoint.projectCheckpoints.deleteAll, tint = AppColors.danger) }
        }
        error?.let { Box(Modifier.padding(16.dp)) { Notice(Icons.Outlined.WarningAmber, AppColors.danger, it) } }
        val list = items
        if (list == null && error == null) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        } else if (list != null && list.isEmpty()) {
            Text(L.checkpoint.projectCheckpoints.noCheckpointsLeft, fontSize = 13.sp, color = muted, modifier = Modifier.padding(20.dp))
        } else if (list != null) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { cp -> HistoryRow(cp) { open = cp } }
            }
        }
    }
    open?.let { cp ->
        CheckpointRestoreSheet(
            projectPath = project.path,
            checkpoint = cp,
            onDismiss = { open = null },
            onDelete = {
                open = null
                scope.launch { ProjectCheckpoints.delete(project.path, ids = listOf(cp.id)).onFailure(::toastError) }
            }
        )
    }
}

@Composable
private fun HistoryRow(cp: CheckpointSummary, onClick: () -> Unit) {
    val agent = agentOf(cp.agent)
    val color = if (cp.isPreRestore) AppColors.info else agent?.accent() ?: MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(if (cp.isPreRestore) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.History, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cp.title?.takeIf { it.isNotBlank() } ?: if (cp.isPreRestore) L.checkpoint.projectCheckpoints.beforeARestore else L.checkpoint.projectCheckpoints.message,
                    fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val flags = listOfNotNull(
                    if (cp.isPreRestore) L.checkpoint.projectCheckpoints.undoPoint else agent?.label(),
                    formatCheckpointTime(cp.time),
                    if (cp.midTurn) L.checkpoint.projectCheckpoints.sentWhileWorking else null,
                    if (cp.late) "late" else null,
                    if (cp.repos > 1) "${cp.repos} repos" else null
                )
                Text(flags.joinToString(" · "), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Asked once per project when checkpoints are on for chosen projects: keep checkpoints for [state]'s project?
 * Dismissing without an answer asks again later.
 */
@Composable
fun TrackProjectDialog(state: CheckpointProjectState, onAnswer: (String?) -> Unit) {
    val name = state.path.trimEnd('/').substringAfterLast('/').ifBlank { state.path }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(L.checkpoint.projectCheckpoints.keepCheckpointsForName(name), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    L.checkpoint.projectCheckpoints.beforeEachMessageAntigemSavesA,
                    fontSize = 14.sp, lineHeight = 20.sp
                )
                if (!state.isGit) {
                    Notice(Icons.Outlined.WarningAmber, AppColors.warning,
                        L.checkpoint.projectCheckpoints.thisFolderIsNotAGit)
                }
                Text(L.checkpoint.projectCheckpoints.youCanChangeThisAnyTime, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer("on") }) { Text(L.checkpoint.projectCheckpoints.keepCheckpoints, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = { onAnswer("off") }) { Text(L.checkpoint.projectCheckpoints.notForThisProject) } }
    )
}
