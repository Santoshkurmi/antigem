package ai.antigem.ui.checkpoint

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.checkpoint.CheckpointFileChange
import ai.antigem.data.checkpoint.CheckpointPreview
import ai.antigem.data.checkpoint.CheckpointSummary
import ai.antigem.data.checkpoint.ProjectCheckpoints
import ai.antigem.domain.model.AgentKind
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.accent
import ai.antigem.ui.components.label
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ai.antigem.i18n.L

/** What a restore puts back. */
enum class RestoreMode(private val titleText: () -> String, private val descriptionText: () -> String, val files: Boolean, val chat: Boolean) {
    CHAT_AND_FILES({ L.checkpoint.restore.chatAndFiles }, { L.checkpoint.restore.theChatGoesBackToBefore }, true, true),
    FILES({ L.checkpoint.restore.filesOnly }, { L.checkpoint.restore.onlyTheProjectFilesGoBack }, true, false),
    CHAT({ L.checkpoint.restore.chatOnly }, { "Only the chat goes back (its text returns to the input box); files stay as they are now" }, false, true)
;
    val title: String get() = titleText()
    val description: String get() = descriptionText()
}

private sealed interface SheetState {
    data object Loading : SheetState
    data class Failed(val message: String) : SheetState
    data class Ready(val preview: CheckpointPreview) : SheetState
    data object Working : SheetState
    data class Done(val result: CheckpointPreview, val mode: RestoreMode) : SheetState
    data object Undone : SheetState
}

/**
 * "Go back to before this message": loads what would change (files and warnings), lets the user pick chat / files /
 * both, restores, and offers to undo the file part right after.
 *
 * [onRestoreChat] goes back in the chat (null: no chat part, e.g. from the settings history).
 * [agentBusy]: the agent is still working in this chat (restoring is blocked until it stops).
 * [otherRunningChats]: other chats in the same project still running (warned about).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointRestoreSheet(
    projectPath: String,
    checkpoint: CheckpointSummary,
    onDismiss: () -> Unit,
    onRestoreChat: (() -> Unit)? = null,
    agentBusy: Boolean = false,
    otherRunningChats: Int = 0,
    onDelete: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SheetState>(SheetState.Loading) }
    var mode by remember { mutableStateOf(if (onRestoreChat != null) RestoreMode.CHAT_AND_FILES else RestoreMode.FILES) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(checkpoint.id, reload) {
        state = SheetState.Loading
        state = ProjectCheckpoints.preview(projectPath, checkpoint.id).fold(
            onSuccess = { SheetState.Ready(it) },
            onFailure = { SheetState.Failed(it.message ?: L.checkpoint.restore.couldNotCompareTheFiles) }
        )
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = MaterialTheme.colorScheme.primary
    ModalBottomSheet(
        onDismissRequest = { if (state !is SheetState.Working) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 22.dp)
                .navigationBarsPadding().animateContentSize()
        ) {
            SheetHeader(checkpoint, onRestoreChat != null)
            Spacer(Modifier.height(14.dp))
            when (val s = state) {
                SheetState.Loading -> Busy(L.checkpoint.restore.comparingTheProjectWithThisCheckpoint)
                SheetState.Working -> Busy(L.checkpoint.restore.restoring)
                is SheetState.Failed -> {
                    Notice(Icons.Outlined.ErrorOutline, AppColors.danger, s.message, live = true)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(L.common.close) }
                        Spacer(Modifier.width(6.dp))
                        FilledTonalButton(onClick = { reload++ }) { Text(L.common.tryAgain) }
                    }
                }
                is SheetState.Ready -> {
                    val pv = s.preview
                    PreviewBody(projectPath, checkpoint, pv, files = mode.files)
                    if (agentBusy) {
                        Spacer(Modifier.height(10.dp))
                        Notice(Icons.Outlined.WarningAmber, AppColors.danger, L.checkpoint.restore.theAgentIsStillWorkingIn)
                    } else if (otherRunningChats > 0) {
                        Spacer(Modifier.height(10.dp))
                        Notice(Icons.Outlined.WarningAmber, AppColors.warning,
                            L.checkpoint.restore.otherrunningchatsOtherChatValueStill(otherRunningChats, if (otherRunningChats == 1) " is" else L.checkpoint.restore.sAre))
                    }
                    if (onRestoreChat != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(L.checkpoint.restore.whatGoesBack, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RestoreMode.entries.forEach { m ->
                                ai.antigem.ui.claude.PickerOption(
                                    title = m.title,
                                    description = m.description,
                                    color = accent,
                                    selected = m == mode,
                                    icon = when (m) {
                                        RestoreMode.CHAT_AND_FILES -> Icons.Outlined.History
                                        RestoreMode.FILES -> Icons.Outlined.FolderCopy
                                        RestoreMode.CHAT -> Icons.Outlined.ChatBubbleOutline
                                    },
                                    onClick = { mode = m }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    val nothingToRestore = !mode.chat && pv.files.isEmpty()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) { Text(L.common.delete, color = AppColors.danger) }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(L.common.cancel) }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            enabled = !agentBusy && !nothingToRestore,
                            onClick = {
                                val chosen = mode
                                if (!chosen.files) {
                                    onRestoreChat?.invoke()
                                    onDismiss()
                                    return@Button
                                }
                                state = SheetState.Working
                                scope.launch {
                                    ProjectCheckpoints.restore(projectPath, checkpoint.id).fold(
                                        onSuccess = { res ->
                                            if (chosen.chat) onRestoreChat?.invoke()
                                            state = SheetState.Done(res, chosen)
                                        },
                                        onFailure = { state = SheetState.Failed(it.message ?: L.checkpoint.restore.restoreFailedNothingWasChanged) }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text(when (mode) {
                                RestoreMode.CHAT_AND_FILES -> L.checkpoint.restore.restoreChatFiles
                                RestoreMode.FILES -> L.checkpoint.restore.restoreFiles
                                RestoreMode.CHAT -> L.checkpoint.restore.restoreChat
                            }, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                is SheetState.Done -> DoneBody(s, projectPath, onDismiss, onUndone = { state = SheetState.Undone }, onUndoing = { state = SheetState.Working },
                    onUndoFailed = { state = SheetState.Failed(it) })
                SheetState.Undone -> {
                    Notice(Icons.Outlined.CheckCircle, AppColors.success, L.checkpoint.restore.restoreUndoneTheFilesAreAs, live = true)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDismiss) { Text(L.common.done) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(cp: CheckpointSummary, fromChat: Boolean) {
    val agent = agentOf(cp.agent)
    val color = agent?.accent() ?: MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.History, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (cp.isPreRestore) L.checkpoint.restore.undoThatRestore else if (fromChat) L.checkpoint.restore.goBackToBeforeThisMessage else L.checkpoint.restore.restoreThisCheckpoint,
                fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }
            )
            Text(
                listOfNotNull(agent?.label(), formatCheckpointTime(cp.time)).joinToString(" · "),
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    cp.title?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            "“$it”", fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics { }, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** [live]: TalkBack reads it out when it shows up (a result or an error after waiting). */
@Composable
internal fun Notice(icon: ImageVector, color: Color, text: String, live: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp).padding(top = 1.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
        )
    }
}

@Composable
private fun PreviewBody(projectPath: String, cp: CheckpointSummary, pv: CheckpointPreview, files: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!files) {
        Notice(Icons.Outlined.Info, AppColors.info,
            if (pv.files.isNotEmpty()) L.checkpoint.restore.filesStayAsTheyAreNow(pv.files.size) else L.checkpoint.restore.filesStayAsTheyAreNow2)
        return
    }
    if (pv.files.isEmpty()) {
        Notice(Icons.Outlined.CheckCircle, AppColors.success, L.checkpoint.restore.noFileChangesTheProjectsFiles)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pv.modified > 0) CountChip(Icons.Outlined.Edit, AppColors.warning, L.checkpoint.restore.countChanged(pv.modified))
            if (pv.recreated > 0) CountChip(Icons.Outlined.AddCircleOutline, AppColors.success, L.checkpoint.restore.recreatedComeBack(pv.recreated))
            if (pv.deleted > 0) CountChip(Icons.Outlined.RemoveCircleOutline, AppColors.danger, L.checkpoint.restore.countRemoved(pv.deleted))
        }
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                pv.files.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    FileRow(projectPath, cp.id, f)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(L.checkpoint.restore.tapAFileToSeeExactly, fontSize = 11.5.sp, color = muted)
    }
    val shown = pv.warnings.filter { it.kind != "outside" }
    if (shown.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { w ->
                Notice(Icons.Outlined.WarningAmber, if (w.kind == "other_chats" || w.kind == "mid_turn" || w.kind == "late") AppColors.warning else AppColors.info, w.text)
            }
            if (pv.laterChats.isNotEmpty()) {
                Column(Modifier.padding(start = 6.dp)) {
                    pv.laterChats.take(5).forEach { lc ->
                        val a = agentOf(lc.agent)
                        Text(
                            "• ${a?.label() ?: "Chat"}: “${lc.title?.takeIf { it.isNotBlank() } ?: "untitled"}” (${lc.turns} message${if (lc.turns == 1) "" else "s"}, ${formatCheckpointTime(lc.time)})",
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
    pv.warnings.firstOrNull { it.kind == "outside" }?.let {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, tint = muted, modifier = Modifier.size(14.dp).padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text(it.text, fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
        }
    }
}

@Composable
private fun CountChip(icon: ImageVector, color: Color, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun FileRow(projectPath: String, checkpointId: String, f: CheckpointFileChange) {
    var open by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<String?>(null) }
    var diffError by remember { mutableStateOf<String?>(null) }
    val (icon, color, label) = when (f.change) {
        "deleted" -> Triple(Icons.Outlined.RemoveCircleOutline, AppColors.danger, "removed")
        "recreated" -> Triple(Icons.Outlined.AddCircleOutline, AppColors.success, L.checkpoint.restore.comesBack)
        else -> Triple(Icons.Outlined.Edit, AppColors.warning, "changed")
    }
    LaunchedEffect(open) {
        if (open && diff == null && !f.binary) {
            ProjectCheckpoints.diff(projectPath, checkpointId, f.path).fold({ diff = it }, { diffError = it.message })
        }
    }
    val name = f.path.substringAfterLast('/')
    val dir = f.path.substringBeforeLast('/', "")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .semantics { stateDescription = if (open) L.components.a11y.expanded else L.components.a11y.collapsed }
                .clickable(role = Role.Button) { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dir.isNotEmpty()) Text(dir, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (f.binary) {
                Text(L.checkpoint.restore.binary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (f.added > 0) Text("+${f.added}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.success, fontFamily = FontFamily.Monospace)
                if (f.added > 0 && f.removed > 0) Spacer(Modifier.width(5.dp))
                if (f.removed > 0) Text("−${f.removed}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.danger, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(6.dp))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                when {
                    f.binary -> Text(L.checkpoint.restore.aBinaryFileItIsPut, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    diffError != null -> Text(diffError!!, fontSize = 12.sp, color = AppColors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    diff == null -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = L.common.loading })
                    else -> DiffBlock(diff!!)
                }
            }
        }
    }
}

/** A unified diff (now → checkpoint): green lines come back, red ones go away. */
@Composable
private fun DiffBlock(diff: String) {
    val lines = remember(diff) {
        diff.lines().dropWhile { !it.startsWith("@@") }.let { if (it.isEmpty()) diff.lines() else it }
    }
    val maxLines = 500
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)
            .horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)
    ) {
        lines.take(maxLines).forEach { line ->
            val (bg, fg) = when {
                line.startsWith("+") -> AppColors.success.copy(alpha = 0.14f) to AppColors.success
                line.startsWith("-") -> AppColors.danger.copy(alpha = 0.14f) to AppColors.danger
                line.startsWith("@@") -> Color.Transparent to AppColors.info
                line.startsWith("\\") -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                else -> Color.Transparent to AppColors.codeText
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = fg, softWrap = false,
                modifier = Modifier.background(bg).padding(horizontal = 8.dp)
            )
        }
        if (lines.size > maxLines) {
            Text(L.checkpoint.restore.valueMoreLines(lines.size - maxLines), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun DoneBody(
    s: SheetState.Done, projectPath: String, onDismiss: () -> Unit,
    onUndone: () -> Unit, onUndoing: () -> Unit, onUndoFailed: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val r = s.result
    val parts = listOfNotNull(
        r.modified.takeIf { it > 0 }?.let { L.checkpoint.restore.valueChangedBack(it) },
        r.recreated.takeIf { it > 0 }?.let { L.checkpoint.restore.valueBroughtBack(it) },
        r.deleted.takeIf { it > 0 }?.let { "$it removed" }
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(AppColors.success.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CheckCircle, null, tint = AppColors.success, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (r.errors.isEmpty()) L.checkpoint.restore.filesRestored else L.checkpoint.restore.filesRestoredWithProblems, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Text(
            if (parts.isEmpty()) L.checkpoint.restore.nothingHadToChange else parts.joinToString(" · "),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (s.mode.chat) {
            Spacer(Modifier.height(4.dp))
            Text(L.checkpoint.restore.theChatWentBackTooThe, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (r.errors.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Notice(Icons.Outlined.ErrorOutline, AppColors.danger, r.errors.take(8).joinToString("\n") + if (r.errors.size > 8) "\n… ${r.errors.size - 8} more" else "")
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val undo = r.undoId
        if (undo != null) {
            OutlinedButton(onClick = {
                onUndoing()
                scope.launch {
                    ProjectCheckpoints.restore(projectPath, undo).fold({ onUndone() }, { onUndoFailed(it.message ?: L.checkpoint.restore.couldNotUndo) })
                }
            }) {
                Icon(Icons.AutoMirrored.Outlined.Undo, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (s.mode.chat) L.checkpoint.restore.undoFiles else L.checkpoint.restore.undoRestore)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onDismiss) { Text(L.common.done) }
    }
    if (s.mode.chat && r.undoId != null) {
        Spacer(Modifier.height(6.dp))
        Text(L.checkpoint.restore.undoBringsTheFilesBackThe, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun agentOf(name: String?): AgentKind? =
    name?.let { n -> AgentKind.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }

/** "just now", "5 min ago", "3 h ago", else the date. */
fun formatCheckpointTime(ms: Long): String {
    if (ms <= 0) return ""
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000 -> L.common.justNow
        d < 3_600_000 -> L.checkpoint.restore.valueMinAgo(d / 60_000)
        d < 24 * 3_600_000 -> L.checkpoint.restore.valueHAgo(d / 3_600_000)
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%d KB", b / (1L shl 10))
    else -> "$b B"
}
