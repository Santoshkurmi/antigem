package com.example.gemini.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.rea.ReaManager
import com.example.gemini.theme.QuotaGreen

/**
 * Settings → REA → Tool groups: the groups of REA tools that REA MCP servers show. Built-in groups come with the
 * setup files; every group can be changed, and the user can create their own. New REA tools land in "no group".
 */
@Composable
fun ReaGroupsScreen(cardBg: Color, cardBorder: BorderStroke, onOpenRea: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ReaManager.init(context)
        ReaManager.refresh()
    }
    val state by ReaManager.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }
    BackHandler(enabled = editing != null) { editing = null }

    val group = state.groups.firstOrNull { it.id == editing }
    // The open group was deleted (or reset away): back to the list
    LaunchedEffect(group == null, state.ready) { if (editing != null && group == null && state.ready) editing = null }

    if (group != null) {
        GroupEditor(group, state, cardBg, cardBorder, onBack = { editing = null }, onEditInfo = { dialog = GroupDialog.Edit(group) })
    } else {
        GroupList(state, cardBg, cardBorder, onOpen = { editing = it }, onDialog = { dialog = it }, onOpenRea = onOpenRea)
    }

    when (val d = dialog) {
        null -> Unit
        is GroupDialog.Create -> GroupInfoDialog("New tool group", "", "", onDismiss = { dialog = null }) { name, description ->
            editing = ReaManager.createGroup(name, description)
            dialog = null
        }
        is GroupDialog.Edit -> GroupInfoDialog("Edit group", d.group.name, d.group.description, onDismiss = { dialog = null }) { name, description ->
            ReaManager.updateGroup(d.group.id, name, description)
            dialog = null
        }
        is GroupDialog.Delete -> ConfirmDialog(
            "Delete ${d.group.name}?",
            if (d.group.builtin) "REA servers that use this group stop showing its tools. You can bring it back with Reset all groups."
            else "REA servers that use this group stop showing its tools.",
            "Delete", onDismiss = { dialog = null }
        ) { ReaManager.deleteGroup(d.group.id); dialog = null }
        is GroupDialog.Reset -> ConfirmDialog(
            "Reset ${d.group.name}?", "Its name, description and tools go back to the defaults.", "Reset", onDismiss = { dialog = null }
        ) { ReaManager.resetGroup(d.group.id); dialog = null }
        GroupDialog.ResetAll -> ConfirmDialog(
            "Reset all groups?", "Every built-in group goes back to its defaults and your own groups are deleted.", "Reset all", onDismiss = { dialog = null }
        ) { ReaManager.resetAllGroups(); dialog = null }
    }
}

private sealed interface GroupDialog {
    data object Create : GroupDialog
    data object ResetAll : GroupDialog
    data class Edit(val group: ReaManager.Group) : GroupDialog
    data class Delete(val group: ReaManager.Group) : GroupDialog
    data class Reset(val group: ReaManager.Group) : GroupDialog
}

@Composable
private fun GroupList(
    state: ReaManager.State,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpen: (String) -> Unit,
    onDialog: (GroupDialog) -> Unit,
    onOpenRea: () -> Unit
) {
    var showUngrouped by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Category, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Tool groups", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Hint("Each REA MCP server lets the agent use the tools of the groups chosen for it (the REA preset in each agent's MCP settings). Open a group to add or remove tools; a tool can be in several groups. Sizes are what a group costs per request on a server that shows its tools directly; on demand, groups cost nothing up front. Tools marked ✎ change things (apps, files, sessions). Changes apply when the server restarts.")
                    Text(
                        state.toolsServer?.let { "${state.allToolNames.size} tools, from $it" } ?: "Install REA to see every tool with its description.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = state.groups.isNotEmpty() || state.filesReady, onClick = { onDialog(GroupDialog.Create) }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New group")
                        }
                        OutlinedButton(enabled = !state.busy && state.usable, onClick = ReaManager::refreshTools) { Text("Refresh tools") }
                    }
                    if (state.task == "tools") LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (state.groups.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No groups yet", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        Hint("The built-in groups come with REA's setup files. Get them on the REA page (step 1), or create your own group here.")
                        OutlinedButton(onClick = onOpenRea) { Text("Open REA setup") }
                    }
                }
            }
        }
        items(state.groups, key = { it.id }) { g -> GroupCard(g, state, cardBg, cardBorder, onOpen = { onOpen(g.id) }, onDialog = onDialog) }
        if (state.ungrouped.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("In no group (${state.ungrouped.size})", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showUngrouped = !showUngrouped }) { Text(if (showUngrouped) "Hide" else "Show", fontSize = 12.5.sp) }
                        }
                        Hint("Only servers that show all tools offer these. New tools from REA updates appear here: add them to a group.")
                    }
                }
            }
            if (showUngrouped) items(state.ungrouped, key = { "u:$it" }) { name -> UngroupedRow(name, state) }
        }
        if (state.defaultGroups.isNotEmpty()) {
            item {
                TextButton(onClick = { onDialog(GroupDialog.ResetAll) }) {
                    Text("Reset all groups to the defaults", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    g: ReaManager.Group,
    state: ReaManager.State,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpen: () -> Unit,
    onDialog: (GroupDialog) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val tokens = state.tokensFor(listOf(g.id))
    val unknown = if (state.tools.isNotEmpty()) g.tools.count { it !in state.toolsByName } else 0
    Card(
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (g.builtin) Pill("Built-in", MaterialTheme.colorScheme.primary) else Pill("Yours", QuotaGreen)
                }
                Text(
                    "${g.tools.size} tools" + (tokens?.let { " · ≈${formatTokens(it)} tokens" } ?: "") + " · id ${g.id}",
                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (g.description.isNotBlank()) Text(g.description, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                if (unknown > 0) Text("$unknown tools this REA version doesn't have", fontSize = 11.5.sp, color = Amber)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit tools") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Rename / describe") }, onClick = { menu = false; onDialog(GroupDialog.Edit(g)) })
                    if (g.builtin && state.defaultGroups.any { it.id == g.id }) {
                        DropdownMenuItem(text = { Text("Reset to default") }, onClick = { menu = false; onDialog(GroupDialog.Reset(g)) })
                    }
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDialog(GroupDialog.Delete(g)) })
                }
            }
        }
    }
}

@Composable
private fun UngroupedRow(name: String, state: ReaManager.State) {
    var menu by remember { mutableStateOf(false) }
    val tool = state.toolsByName[name]
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(name, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                tool?.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
            }
            Box {
                TextButton(enabled = state.groups.isNotEmpty(), onClick = { menu = true }) { Text("Add to…", fontSize = 12.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.groups.forEach { g ->
                        DropdownMenuItem(text = { Text(g.name) }, onClick = { menu = false; ReaManager.toggleTool(g.id, name) })
                    }
                }
            }
        }
    }
}

private enum class ToolFilter(val label: String) { ALL("All"), IN("In group"), OUT("Not in group"), NONE("In no group") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditor(
    group: ReaManager.Group,
    state: ReaManager.State,
    cardBg: Color,
    cardBorder: BorderStroke,
    onBack: () -> Unit,
    onEditInfo: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ToolFilter.ALL) }
    val inGroup = group.tools.toSet()
    val ungrouped = state.ungrouped.toSet()
    val byName = state.toolsByName
    val shown = state.allToolNames.filter { name ->
        val matches = query.isBlank() || name.contains(query.trim(), ignoreCase = true) ||
            byName[name]?.let { it.title.contains(query.trim(), true) || it.description.contains(query.trim(), true) } == true
        matches && when (filter) {
            ToolFilter.ALL -> true
            ToolFilter.IN -> name in inGroup
            ToolFilter.OUT -> name !in inGroup
            ToolFilter.NONE -> name in ungrouped
        }
    }
    val tokens = state.tokensFor(listOf(group.id))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to groups") }
                        Column(Modifier.weight(1f)) {
                            Text(group.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${group.tools.size} tools" + (tokens?.let { " · ≈${formatTokens(it)} tokens" } ?: "") + " · id ${group.id}",
                                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onEditInfo) { Icon(Icons.Outlined.Edit, "Rename") }
                    }
                    if (group.description.isNotBlank()) Hint(group.description)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search tools") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = {
                                Text(
                                    when (f) {
                                        ToolFilter.ALL -> "All ${state.allToolNames.size}"
                                        ToolFilter.IN -> "In group ${inGroup.size}"
                                        ToolFilter.OUT -> "Not in group"
                                        ToolFilter.NONE -> "In no group ${ungrouped.size}"
                                    },
                                    fontSize = 12.sp
                                )
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = shown.any { it !in inGroup }, onClick = { ReaManager.setGroupTools(group.id, group.tools + shown) }) {
                            Text("Add ${shown.count { it !in inGroup }} shown", fontSize = 12.5.sp)
                        }
                        OutlinedButton(enabled = shown.any { it in inGroup }, onClick = { ReaManager.setGroupTools(group.id, group.tools - shown.toSet()) }) {
                            Text("Remove ${shown.count { it in inGroup }} shown", fontSize = 12.5.sp)
                        }
                    }
                    if (state.tools.isEmpty()) Hint("Install REA (and Refresh tools) to see every tool with its description. Until then only tools already in groups are listed.")
                }
            }
        }
        if (shown.isEmpty()) item { Hint("No tools match.") }
        items(shown, key = { it }) { name ->
            ToolRow(name, name in inGroup, byName[name], state.groups.filter { it.id != group.id && name in it.tools }.map { it.name }) {
                ReaManager.toggleTool(group.id, name)
            }
        }
    }
}

@Composable
private fun ToolRow(name: String, checked: Boolean, tool: ReaManager.Tool?, alsoIn: List<String>, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
    ) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                if (tool == null) {
                    Text("Not in the installed REA's tool list", fontSize = 11.sp, color = Amber)
                } else if (tool.description.isNotBlank()) {
                    Text(tool.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                val meta = listOfNotNull(
                    tool?.takeIf { it.changes }?.let { "✎ changes things" },
                    tool?.required?.takeIf { it.isNotEmpty() }?.let { "needs ${it.joinToString(", ")}" },
                    tool?.bytes?.takeIf { it > 0 }?.let { "≈${formatTokens(it / 4)} tokens" },
                    alsoIn.takeIf { it.isNotEmpty() }?.let { "also in ${it.joinToString(", ")}" }
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun GroupInfoDialog(title: String, name0: String, description0: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var description by remember { mutableStateOf(description0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it.take(300) }, label = { Text("What it is for (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, description) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
