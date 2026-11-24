package ai.antigem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.memory.AgentMemory
import ai.antigem.data.memory.MemoryBridgeClient
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.label
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ai.antigem.i18n.L

/** Longer than this, a saved chat is shown read-only (a text field that big is too slow to edit). */
private const val HISTORY_EDIT_LIMIT = 300_000

/**
 * Settings → Agents → Agent memory & history → saved chats: every chat the bridge keeps for the agents to look up
 * (the user's messages and the replies, no tool output), grouped by project. Open one to read or edit it, or remove it.
 */
@Composable
fun AgentHistoryScreen() {
    val saveHistory by AgentMemory.saveHistory.collectAsState()
    val changes by AgentMemory.changes.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()

    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var chats by remember { mutableStateOf<List<MemoryBridgeClient.HistoryChat>>(emptyList()) }

    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<MemoryBridgeClient.HistoryChat?>(null) }

    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(changes, reload) {
        loading = true
        val r = MemoryBridgeClient.historyList()
        ensureActive()
        r.onSuccess { chats = it; loadError = null }
            .onFailure { loadError = it.message ?: L.settings.agentHistory.couldNotReachTheBridge }
        loading = false
    }

    val q = filter.trim()
    val shown = remember(chats, q) {
        if (q.isEmpty()) chats
        else chats.filter { c ->
            c.title.contains(q, ignoreCase = true) ||
                c.projectName.contains(q, ignoreCase = true) ||
                historyAgentLabel(c.agent).contains(q, ignoreCase = true)
        }
    }
    // One group per project folder, the group with the newest chat first; newest chat first inside a group.
    val groups = remember(shown) {
        shown.groupBy { it.project.trim() }
            .values
            .map { group -> group.sortedByDescending { it.updated } }
            .sortedByDescending { it.first().updated }
    }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            L.settings.agentHistory.chatsSavedForTheAgentsTo,
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )

        if (!saveHistory) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Text(
                    L.settings.agentHistory.chatHistoryIsOffNewMessages,
                    fontSize = 12.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text(L.settings.agentHistory.filterByTitleProjectOrAgent) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (filter.isNotEmpty()) ({ IconButton(onClick = { filter = "" }) { Icon(Icons.Outlined.Close, L.common.clear) } }) else null,
            modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = when {
                chats.isEmpty() -> ""
                q.isEmpty() -> L.settings.agentHistory.chatscountSavedChatValue(chats.size, if (chats.size == 1) "" else "s")
                else -> L.settings.agentHistory.showncountOfChatscountSavedChatValue(shown.size, chats.size, if (chats.size == 1) "" else "s")
            }
            Text(count, fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        loadError?.let { err ->
            HistoryErrorText(err)
            TextButton(onClick = { reload++ }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(L.common.tryAgain) }
        }
        if (chats.isEmpty() && !loading && loadError == null) {
            Text(L.settings.agentHistory.noSavedChatsYet, fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (chats.isNotEmpty() && shown.isEmpty()) {
            Text(L.settings.agentHistory.noSavedChatMatchesQ(q), fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
        }

        groups.forEach { group ->
            val first = group.first()
            val project = first.project.trim()
            HistoryGroupHeader(
                title = if (project.isBlank()) L.settings.agentHistory.noProjectFolder else first.projectName.ifBlank { project },
                subtitle = project.ifBlank { null }
            )
            group.forEach { c ->
                HistoryRow(c, onClick = { open = c })
                Spacer(Modifier.height(8.dp))
            }
        }

        if (chats.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { clearError = null; confirmClear = true },
                enabled = !clearing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(if (clearing) L.common.removing else L.settings.agentHistory.removeAllSavedChats) }
        }
        clearError?.let { HistoryErrorText(it) }
        Spacer(Modifier.height(24.dp))
    }

    open?.let { c ->
        HistoryViewer(chat = c, onClose = { open = null })
    }

    if (confirmClear) {
        val n = chats.size
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(if (n == 1) L.settings.agentHistory.remove1SavedChat else L.settings.agentHistory.removeAllNSavedChats(n)) },
            text = { Text(L.settings.agentHistory.theyAreNotSavedAgainNew) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    clearError = null
                    scope.launch {
                        MemoryBridgeClient.historyClear()
                            .onSuccess { AgentMemory.changed() }
                            .onFailure { clearError = it.message ?: L.settings.agentHistory.couldNotRemoveTheSavedChats }
                        clearing = false
                    }
                }) { Text(L.common.remove, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun HistoryGroupHeader(title: String, subtitle: String?) {
    Spacer(Modifier.height(14.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (!subtitle.isNullOrBlank()) {
        Text(
            subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun HistoryErrorText(text: String) {
    Text(
        text, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun HistoryRow(c: MemoryBridgeClient.HistoryChat, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                c.title.ifBlank { L.settings.agentHistory.untitledChat }, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOfNotNull(
                    historyAgentLabel(c.agent).takeIf { it.isNotBlank() },
                    historyDay(c.updated),
                    historySize(c.size)
                ).joinToString("  ·  ")
                Text(
                    meta, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (c.edited) {
                    Spacer(Modifier.width(8.dp))
                    HistoryEditedChip()
                }
            }
        }
    }
}

@Composable
private fun HistoryEditedChip() {
    val c = MaterialTheme.colorScheme.tertiary
    Text(
        L.settings.agentHistory.edited, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Reads a saved chat and lets the user edit it (or only read it when it is very long) or remove it. */
@Composable
private fun HistoryViewer(chat: MemoryBridgeClient.HistoryChat, onClose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var info by remember { mutableStateOf(chat) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = saving || removing

    LaunchedEffect(chat.chatId) {
        loading = true
        val r = MemoryBridgeClient.historyGet(chat.chatId)
        ensureActive()
        r.onSuccess { (c, t) ->
            // The bridge sends a bare chat (only the id) when it has no details: keep the list's.
            if (c.title.isNotBlank() || c.updated > 0) info = c
            original = t
            text = t
            loadError = null
        }.onFailure { loadError = it.message ?: L.settings.agentHistory.couldNotLoadTheChat }
        loading = false
    }

    val tooLarge = original.length > HISTORY_EDIT_LIMIT
    val failed = loadError

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        title = {
            Text(
                info.title.ifBlank { L.settings.agentHistory.untitledChat }, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val meta = listOfNotNull(
                    historyAgentLabel(info.agent).takeIf { it.isNotBlank() },
                    info.project.trim().ifBlank { L.settings.agentHistory.noProjectFolder },
                    historyDay(info.started)?.let { "started $it" },
                    historyDay(info.updated)?.let { "updated $it" }
                ).joinToString("  ·  ")
                Text(meta, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                    failed != null -> HistoryErrorText(failed)
                    tooLarge -> {
                        Text(L.settings.agentHistory.tooLargeToEditHere, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                                Text(original, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp),
                        minLines = 12, maxLines = 22,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && !loading && failed == null && !tooLarge && text != original,
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.historySave(chat.chatId, text).onSuccess {
                            AgentMemory.changed()
                            onClose()
                        }.onFailure {
                            error = it.message ?: L.settings.agentHistory.couldNotSaveTheChat
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) L.common.saving else L.common.save) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmRemove = true }, enabled = !busy) {
                    Text(if (removing) L.common.removing else L.common.remove, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onClose, enabled = !busy) { Text(L.common.close) }
            }
        }
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(L.settings.agentHistory.removeThisSavedChat) },
            text = { Text(L.settings.agentHistory.theAgentsCanNoLongerLook) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    removing = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.deleteHistory(chat.chatId).onSuccess {
                            AgentMemory.changed()
                            onClose()
                        }.onFailure {
                            error = it.message ?: L.settings.agentHistory.couldNotRemoveTheChat
                            removing = false
                        }
                    }
                }) { Text(L.common.remove, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(L.common.cancel) } }
        )
    }
}

/** "Claude Code" for "claude", "Antigravity" for "agy", …; the raw name for anything else. */
private fun historyAgentLabel(agent: String): String =
    AgentKind.entries.firstOrNull { it.name.equals(agent, ignoreCase = true) }?.label() ?: agent

/** "18 Nov 2026", or null when the bridge sent no time. */
private fun historyDay(ms: Long): String? =
    if (ms <= 0) null else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

/** "840 B", "12 KB", "1.4 MB"; null when the bridge sent no size. */
private fun historySize(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}
