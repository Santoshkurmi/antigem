package ai.antigem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.memory.AgentMemory
import ai.antigem.data.memory.MemoryBridgeClient
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.label
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ai.antigem.i18n.L

/** The row on Settings → Agents that opens [AgentMemoryScreen]. */
@Composable
fun AgentMemoryEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled by AgentMemory.enabled.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L.settings.agentMemory.agentMemoryHistory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    L.settings.agentMemory.valueMemoriesAndPastChatsEvery(if (enabled) L.settings.agentMemory.on else L.settings.agentMemory.off),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → Agent memory & history: the switches of [AgentMemory] and the memories the bridge keeps
 * (list, search, add, edit, delete), plus a read-only look at the past chats a search finds. Chat history only
 * exists with memory on; [onOpenHistory] opens the saved chats ([AgentHistoryScreen]).
 */
@Composable
fun AgentMemoryScreen(onOpenHistory: (() -> Unit)? = null) {
    val enabled by AgentMemory.enabled.collectAsState()
    val agents by AgentMemory.agents.collectAsState()
    val listLimit by AgentMemory.listLimit.collectAsState()
    val saveHistory by AgentMemory.saveHistory.collectAsState()
    val changes by AgentMemory.changes.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var root by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryBridgeClient.Memory>>(emptyList()) }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<MemoryBridgeClient.SearchResult?>(null) }

    // A memory with a blank id is a new one.
    var editing by remember { mutableStateOf<MemoryBridgeClient.Memory?>(null) }

    LaunchedEffect(changes, reload) {
        loading = true
        val r = MemoryBridgeClient.list()
        ensureActive()
        r.onSuccess { (folder, list) -> root = folder; memories = list; loadError = null }
            .onFailure { loadError = it.message ?: L.settings.agentMemory.couldNotReachTheBridge }
        loading = false
    }

    LaunchedEffect(query, changes) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null; searchError = null; searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        val r = MemoryBridgeClient.search(q)
        ensureActive()
        r.onSuccess { results = it; searchError = null }
            .onFailure { searchError = it.message ?: L.settings.agentMemory.searchFailed }
        searching = false
    }

    val knownProjects = remember(memories) {
        memories.filter { it.isProject && it.project.isNotBlank() }.map { it.project }.distinct()
            .sortedBy { it.trimEnd('/').substringAfterLast('/').lowercase() }
    }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        // ----- master switch -----
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.toggleRowSemantics(enabled, { AgentMemory.setEnabled(it) }), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.agentMemory.agentMemory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(L.settings.agentMemory.sharedByEveryAgent, fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = enabled, onCheckedChange = { AgentMemory.setEnabled(it) }, modifier = Modifier.mergedIntoRow())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    L.settings.agentMemory.agentsSaveShortMemoriesInTheir,
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    L.settings.agentMemory.storedByTheBridgeNextTo,
                    fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                )
            }
        }

        if (enabled) {
            // ----- per agent -----
            Spacer(Modifier.height(22.dp))
            Text(L.settings.agentMemory.useWith, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                L.settings.agentMemory.aWeakModelMayNotFollow,
                fontSize = 12.sp, lineHeight = 16.sp, color = muted
            )
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    AgentKind.entries.forEach { agent ->
                        val on = agent in agents
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { AgentMemory.setAgent(agent, !on) }.semantics { role = Role.Switch; toggleableState = ToggleableState(on) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(agent.label(), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = { AgentMemory.setAgent(agent, it) }, modifier = Modifier.mergedIntoRow())
                        }
                    }
                }
            }

            // ----- how many are listed -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.agentMemory.memoriesListedPerChat, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            L.settings.agentMemory.memoriesSetToAlwaysAreAlways,
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit - 1) }, enabled = listLimit > 0) {
                        Icon(Icons.Outlined.Remove, L.settings.agentMemory.fewer)
                    }
                    Text(
                        "$listLimit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 26.dp)
                    )
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit + 1) }, enabled = listLimit < AgentMemory.MAX_LIST_LIMIT) {
                        Icon(Icons.Outlined.Add, L.common.more)
                    }
                }
            }

            // ----- chat history -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp).toggleRowSemantics(saveHistory, { AgentMemory.setSaveHistory(it) }), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.agentMemory.saveChatHistory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            L.settings.agentMemory.aCleanedCopyOfEveryChat,
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = saveHistory, onCheckedChange = { AgentMemory.setSaveHistory(it) }, modifier = Modifier.mergedIntoRow())
                }
            }
            if (onOpenHistory != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onOpenHistory,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(L.settings.agentMemory.savedChats, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(L.settings.agentMemory.readEditOrRemoveTheChat, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                    }
                }
            }
        }

        // ----- the memories -----
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(L.settings.agentMemory.memories, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) L.settings.agentMemory.tapOneToEditIt else L.settings.agentMemory.keptWhileMemoryIsOffAgents,
                    fontSize = 12.sp, color = muted
                )
            }
            if (loading || searching) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            FilledTonalButton(onClick = { editing = MemoryBridgeClient.Memory() }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.common.add)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(L.settings.agentMemory.searchMemoriesAndPastChats) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, L.common.clear) } }) else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))

        if (query.isNotBlank()) {
            searchError?.let { ErrorText(it) }
            results?.let { r ->
                // no memory, no history: past chats only show while memory is on
                val chats = if (enabled) r.history else emptyList()
                if (r.memories.isEmpty() && chats.isEmpty() && !searching) {
                    Text(L.settings.agentMemory.nothingFound, fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (r.memories.isNotEmpty()) {
                    GroupHeader(L.settings.agentMemory.memories, null)
                    r.memories.forEach { m ->
                        MemoryCard(m, onClick = { editing = m })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (chats.isNotEmpty()) {
                    GroupHeader(L.settings.agentMemory.pastChats, L.settings.agentMemory.openSavedChatsToEditOr)
                    chats.forEach { h ->
                        HistoryCard(h)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            loadError?.let { err ->
                ErrorText(err)
                TextButton(onClick = { reload++ }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(L.common.tryAgain) }
            }
            if (memories.isEmpty() && !loading && loadError == null) {
                Text(
                    L.settings.agentMemory.noMemoriesYetAgentsSaveThem,
                    fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            val always = memories.filter { it.isAlways }.sortedByDescending { it.updated }
            val global = memories.filter { !it.isAlways && !it.isProject }.sortedByDescending { it.updated }
            val projects = memories.filter { !it.isAlways && it.isProject }
                .groupBy { it.projectDir.ifBlank { it.project } }
                .values
                .map { group -> group.sortedByDescending { it.updated } }
                .sortedBy { it.first().projectName.lowercase() }

            if (always.isNotEmpty()) {
                GroupHeader(L.common.always, L.settings.agentMemory.everyChatGetsTheseInFull)
                always.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (global.isNotEmpty()) {
                GroupHeader(L.common.global, L.settings.agentMemory.listedInAnyProjectWhenRelevant)
                global.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            projects.forEach { group ->
                val first = group.first()
                GroupHeader(L.settings.agentMemory.projectValue(first.projectName.ifBlank { "unknown" }), first.project.ifBlank { null })
                group.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (root.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(L.settings.agentMemory.storedByTheBridgeInRoot(root), fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { m ->
        MemoryEditor(
            memory = m,
            knownProjects = knownProjects,
            onDone = { editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String?) {
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
private fun ErrorText(text: String) {
    Text(
        text, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun MemoryCard(m: MemoryBridgeClient.Memory, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(m.name.ifBlank { L.common.untitled }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.description.isNotBlank()) {
                Text(
                    m.description.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = muted
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryChip(if (m.isAlways) L.common.always else L.settings.agentMemory.passive, highlight = m.isAlways)
                MemoryChip(if (m.isProject) m.projectName.ifBlank { L.settings.agentMemory.oneProject } else L.common.allProjects, highlight = false)
            }
            val meta = listOfNotNull(formatDay(m.updated), savedBy(m.agent)).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HistoryCard(h: MemoryBridgeClient.HistoryHit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(h.title.ifBlank { L.settings.agentMemory.untitledChat }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                h.agent.takeIf { it.isNotBlank() }?.let { agentLabel(it) },
                h.project.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() },
                formatDay(h.updated)
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(h.snippet.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemoryChip(label: String, highlight: Boolean) {
    val c = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditor(
    memory: MemoryBridgeClient.Memory,
    knownProjects: List<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val isNew = memory.id.isBlank()
    var name by remember { mutableStateOf(memory.name) }
    var description by remember { mutableStateOf(memory.description) }
    var text by remember { mutableStateOf(memory.text) }
    var always by remember { mutableStateOf(memory.isAlways) }
    var oneProject by remember { mutableStateOf(memory.isProject) }
    var project by remember { mutableStateOf(memory.project) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        title = { Text(if (isNew) L.settings.agentMemory.newMemory else L.settings.agentMemory.editMemory, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(L.common.name) }, placeholder = { Text(L.settings.agentMemory.shortName) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, singleLine = true,
                    label = { Text(L.settings.agentMemory.description) }, placeholder = { Text(L.settings.agentMemory.oneLineWhatItIsAbout) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(L.settings.agentMemory.whatTheAgentsShouldRemember) },
                    minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text(L.settings.agentMemory.mode, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption(L.common.always, L.settings.agentMemory.everyChatGetsItInFull, always) { always = true }
                MemoryOption(L.settings.agentMemory.passive, L.settings.agentMemory.listedWhenRelevantTheAgentReads, !always) { always = false }
                Spacer(Modifier.height(10.dp))
                Text(L.settings.agentMemory.scope, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption(L.common.allProjects, L.settings.agentMemory.anyChatCanGetIt, !oneProject) { oneProject = false }
                MemoryOption(L.settings.agentMemory.oneProject, L.settings.agentMemory.onlyChatsInThatProjectFolder, oneProject) { oneProject = true }
                if (oneProject) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = project, onValueChange = { project = it }, singleLine = true,
                        label = { Text(L.settings.agentMemory.projectFolder) }, placeholder = { Text("/path/to/project") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (knownProjects.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            knownProjects.forEach { p ->
                                FilterChip(
                                    selected = p == project.trim(),
                                    onClick = { project = p },
                                    label = {
                                        Text(
                                            p.trimEnd('/').substringAfterLast('/').ifBlank { p },
                                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && name.isNotBlank() && text.isNotBlank() && (!oneProject || project.isNotBlank()),
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.save(
                            id = memory.id.ifBlank { null },
                            file = name.trim(),
                            scope = if (oneProject) "project" else "global",
                            project = if (oneProject) project.trim() else null,
                            mode = if (always) "always" else "passive",
                            description = description.trim(),
                            text = text.trim()
                        ).onSuccess {
                            AgentMemory.changed()
                            onDone()
                        }.onFailure {
                            error = it.message ?: L.settings.agentMemory.couldNotSaveTheMemory
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) L.common.saving else L.common.save) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text(L.common.delete, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text(L.common.cancel) }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${memory.name.ifBlank { "Untitled" }}\"?") },
            text = { Text(L.settings.agentMemory.agentsWillNoLongerGetIt) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.delete(memory.id).onSuccess {
                            AgentMemory.changed()
                            onDone()
                        }.onFailure {
                            error = it.message ?: L.settings.agentMemory.couldNotDeleteTheMemory
                            saving = false
                        }
                    }
                }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun MemoryOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Claude Code" for "claude", "Antigravity" for "agy", …; the raw name for anything else. */
private fun agentLabel(agent: String): String =
    AgentKind.entries.firstOrNull { it.name.equals(agent, ignoreCase = true) }?.label() ?: agent

private fun savedBy(agent: String): String? = when {
    agent.isBlank() -> null
    agent.equals("user", ignoreCase = true) -> L.settings.agentMemory.addedByYou
    else -> L.settings.agentMemory.savedByAgentlabel(agentLabel(agent))
}

/** "18 Nov 2026", or null when the bridge sent no time. */
private fun formatDay(ms: Long): String? =
    if (ms <= 0) null else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
