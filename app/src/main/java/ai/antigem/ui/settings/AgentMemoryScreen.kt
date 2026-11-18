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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                Text("Agent memory & history", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${if (enabled) "On" else "Off"}  ·  Memories and past chats every agent can use",
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
            .onFailure { loadError = it.message ?: "Could not reach the bridge." }
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
            .onFailure { searchError = it.message ?: "Search failed." }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent memory", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Shared by every agent", fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = enabled, onCheckedChange = { AgentMemory.setEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Agents save short memories in their replies; the chat shows a chip you can undo. A new chat is told the " +
                        "Always memories and the most relevant others, and the agents can look up past chats.",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Stored by the bridge next to the agents, so it works in local and server mode.",
                    fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                )
            }
        }

        if (enabled) {
            // ----- per agent -----
            Spacer(Modifier.height(22.dp))
            Text("Use with", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "A weak model may not follow the memory rules. Turn it off for that agent.",
                fontSize = 12.sp, lineHeight = 16.sp, color = muted
            )
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    AgentKind.entries.forEach { agent ->
                        val on = agent in agents
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { AgentMemory.setAgent(agent, !on) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(agent.label(), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = { AgentMemory.setAgent(agent, it) })
                        }
                    }
                }
            }

            // ----- how many are listed -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Memories listed per chat", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Memories set to Always are always included in full and do not count.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit - 1) }, enabled = listLimit > 0) {
                        Icon(Icons.Outlined.Remove, "Fewer")
                    }
                    Text(
                        "$listLimit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 26.dp)
                    )
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit + 1) }, enabled = listLimit < AgentMemory.MAX_LIST_LIMIT) {
                        Icon(Icons.Outlined.Add, "More")
                    }
                }
            }

            // ----- chat history -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save chat history", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "A cleaned copy of every chat (your messages and the replies, no tool output) the agents can read " +
                                "when you refer to earlier work.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = saveHistory, onCheckedChange = { AgentMemory.setSaveHistory(it) })
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
                            Text("Saved chats", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Read, edit or remove the chat history the agents can look up", fontSize = 12.sp, lineHeight = 16.sp, color = muted)
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
                Text("Memories", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) "Tap one to edit it" else "Kept while memory is off; agents do not get them",
                    fontSize = 12.sp, color = muted
                )
            }
            if (loading || searching) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            FilledTonalButton(onClick = { editing = MemoryBridgeClient.Memory() }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search memories and past chats") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") } }) else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))

        if (query.isNotBlank()) {
            searchError?.let { ErrorText(it) }
            results?.let { r ->
                // no memory, no history: past chats only show while memory is on
                val chats = if (enabled) r.history else emptyList()
                if (r.memories.isEmpty() && chats.isEmpty() && !searching) {
                    Text("Nothing found.", fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (r.memories.isNotEmpty()) {
                    GroupHeader("Memories", null)
                    r.memories.forEach { m ->
                        MemoryCard(m, onClick = { editing = m })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (chats.isNotEmpty()) {
                    GroupHeader("Past chats", "Open Saved chats to edit or remove them")
                    chats.forEach { h ->
                        HistoryCard(h)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            loadError?.let { err ->
                ErrorText(err)
                TextButton(onClick = { reload++ }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Try again") }
            }
            if (memories.isEmpty() && !loading && loadError == null) {
                Text(
                    "No memories yet. Agents save them as you chat, or add one.",
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
                GroupHeader("Always", "Every chat gets these in full")
                always.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (global.isNotEmpty()) {
                GroupHeader("Global", "Listed in any project when relevant")
                global.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            projects.forEach { group ->
                val first = group.first()
                GroupHeader("Project: ${first.projectName.ifBlank { "unknown" }}", first.project.ifBlank { null })
                group.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (root.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Stored by the bridge in $root", fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
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
            Text(m.name.ifBlank { "Untitled" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.description.isNotBlank()) {
                Text(
                    m.description.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = muted
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryChip(if (m.isAlways) "Always" else "Passive", highlight = m.isAlways)
                MemoryChip(if (m.isProject) m.projectName.ifBlank { "One project" } else "All projects", highlight = false)
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
            Text(h.title.ifBlank { "Untitled chat" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        title = { Text(if (isNew) "New memory" else "Edit memory", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") }, placeholder = { Text("short-name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, singleLine = true,
                    label = { Text("Description") }, placeholder = { Text("One line: what it is about") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("What the agents should remember") },
                    minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption("Always", "Every chat gets it in full.", always) { always = true }
                MemoryOption("Passive", "Listed when relevant; the agent reads it when it needs it.", !always) { always = false }
                Spacer(Modifier.height(10.dp))
                Text("Scope", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption("All projects", "Any chat can get it.", !oneProject) { oneProject = false }
                MemoryOption("One project", "Only chats in that project folder.", oneProject) { oneProject = true }
                if (oneProject) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = project, onValueChange = { project = it }, singleLine = true,
                        label = { Text("Project folder") }, placeholder = { Text("/path/to/project") },
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
                            error = it.message ?: "Could not save the memory."
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${memory.name.ifBlank { "Untitled" }}\"?") },
            text = { Text("Agents will no longer get it. Chats that already got it keep what they were told.") },
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
                            error = it.message ?: "Could not delete the memory."
                            saving = false
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
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
    agent.equals("user", ignoreCase = true) -> "Added by you"
    else -> "Saved by ${agentLabel(agent)}"
}

/** "18 Nov 2026", or null when the bridge sent no time. */
private fun formatDay(ms: Long): String? =
    if (ms <= 0) null else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
