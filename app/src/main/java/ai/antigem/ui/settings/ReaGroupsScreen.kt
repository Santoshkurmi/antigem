package ai.antigem.ui.settings

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
import ai.antigem.data.rea.ReaManager
import ai.antigem.theme.QuotaGreen
import ai.antigem.i18n.L

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
        is GroupDialog.Create -> GroupInfoDialog(L.settings.reaGroups.newToolGroup, "", "", onDismiss = { dialog = null }) { name, description ->
            editing = ReaManager.createGroup(name, description)
            dialog = null
        }
        is GroupDialog.Edit -> GroupInfoDialog(L.settings.reaGroups.editGroup, d.group.name, d.group.description, onDismiss = { dialog = null }) { name, description ->
            ReaManager.updateGroup(d.group.id, name, description)
            dialog = null
        }
        is GroupDialog.Delete -> ConfirmDialog(
            "Delete ${d.group.name}?",
            if (d.group.builtin) L.settings.reaGroups.reaServersThatUseThisGroup
            else L.settings.reaGroups.reaServersThatUseThisGroup2,
            L.common.delete, onDismiss = { dialog = null }
        ) { ReaManager.deleteGroup(d.group.id); dialog = null }
        is GroupDialog.Reset -> ConfirmDialog(
            "Reset ${d.group.name}?", L.settings.reaGroups.itsNameDescriptionAndToolsGo, L.settings.reaGroups.reset, onDismiss = { dialog = null }
        ) { ReaManager.resetGroup(d.group.id); dialog = null }
        GroupDialog.ResetAll -> ConfirmDialog(
            L.settings.reaGroups.resetAllGroups, L.settings.reaGroups.everyBuiltInGroupGoesBack, L.settings.reaGroups.resetAll, onDismiss = { dialog = null }
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
                        Text(L.settings.reaGroups.toolGroups, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Hint(L.settings.reaGroups.eachReaMcpServerLetsThe)
                    Text(
                        state.toolsServer?.let { L.settings.reaGroups.alltoolnamescountToolsFromValue(state.allToolNames.size, it) } ?: L.settings.reaGroups.installReaToSeeEveryTool,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = state.groups.isNotEmpty() || state.filesReady, onClick = { onDialog(GroupDialog.Create) }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.reaGroups.newGroup)
                        }
                        OutlinedButton(enabled = !state.busy && state.usable, onClick = ReaManager::refreshTools) { Text(L.settings.reaGroups.refreshTools) }
                    }
                    if (state.task == "tools") LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (state.groups.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(L.settings.reaGroups.noGroupsYet, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        Hint(L.settings.reaGroups.theBuiltInGroupsComeWith)
                        OutlinedButton(onClick = onOpenRea) { Text(L.settings.reaGroups.openReaSetup) }
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
                            TextButton(onClick = { showUngrouped = !showUngrouped }) { Text(if (showUngrouped) L.common.hide else L.common.show, fontSize = 12.5.sp) }
                        }
                        Hint(L.settings.reaGroups.onlyServersThatShowAllTools)
                    }
                }
            }
            if (showUngrouped) items(state.ungrouped, key = { "u:$it" }) { name -> UngroupedRow(name, state) }
        }
        if (state.defaultGroups.isNotEmpty()) {
            item {
                TextButton(onClick = { onDialog(GroupDialog.ResetAll) }) {
                    Text(L.settings.reaGroups.resetAllGroupsToTheDefaults, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
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
                    if (g.builtin) Pill(L.common.builtIn, MaterialTheme.colorScheme.primary) else Pill(L.settings.reaGroups.yours, QuotaGreen)
                }
                Text(
                    "${g.tools.size} tools" + (tokens?.let { " · ≈${formatTokens(it)} tokens" } ?: "") + " · id ${g.id}",
                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (g.description.isNotBlank()) Text(g.description, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                if (unknown > 0) Text(L.settings.reaGroups.unknownToolsThisReaVersionDoesnt(unknown), fontSize = 11.5.sp, color = Amber)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, L.common.more) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(L.settings.reaGroups.editTools) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(L.settings.reaGroups.renameDescribe) }, onClick = { menu = false; onDialog(GroupDialog.Edit(g)) })
                    if (g.builtin && state.defaultGroups.any { it.id == g.id }) {
                        DropdownMenuItem(text = { Text(L.settings.reaGroups.resetToDefault) }, onClick = { menu = false; onDialog(GroupDialog.Reset(g)) })
                    }
                    DropdownMenuItem(text = { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDialog(GroupDialog.Delete(g)) })
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
                TextButton(enabled = state.groups.isNotEmpty(), onClick = { menu = true }) { Text(L.settings.reaGroups.addTo, fontSize = 12.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    state.groups.forEach { g ->
                        DropdownMenuItem(text = { Text(g.name) }, onClick = { menu = false; ReaManager.toggleTool(g.id, name) })
                    }
                }
            }
        }
    }
}

private enum class ToolFilter(private val labelText: () -> String) { ALL({ L.settings.reaGroups.all }), IN({ L.settings.reaGroups.inGroup }), OUT({ L.settings.reaGroups.notInGroup }), NONE({ L.settings.reaGroups.inNoGroup }) ;
    val label: String get() = labelText()
}

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
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, L.settings.reaGroups.backToGroups) }
                        Column(Modifier.weight(1f)) {
                            Text(group.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${group.tools.size} tools" + (tokens?.let { " · ≈${formatTokens(it)} tokens" } ?: "") + " · id ${group.id}",
                                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onEditInfo) { Icon(Icons.Outlined.Edit, L.common.rename) }
                    }
                    if (group.description.isNotBlank()) Hint(group.description)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(L.settings.reaGroups.searchTools) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = {
                                Text(
                                    when (f) {
                                        ToolFilter.ALL -> L.settings.reaGroups.allAlltoolnamescount(state.allToolNames.size)
                                        ToolFilter.IN -> L.settings.reaGroups.inGroupIngroupcount(inGroup.size)
                                        ToolFilter.OUT -> L.settings.reaGroups.notInGroup
                                        ToolFilter.NONE -> L.settings.reaGroups.inNoGroupUngroupedcount(ungrouped.size)
                                    },
                                    fontSize = 12.sp
                                )
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = shown.any { it !in inGroup }, onClick = { ReaManager.setGroupTools(group.id, group.tools + shown) }) {
                            Text(L.settings.reaGroups.addValueShown(shown.count { it !in inGroup }), fontSize = 12.5.sp)
                        }
                        OutlinedButton(enabled = shown.any { it in inGroup }, onClick = { ReaManager.setGroupTools(group.id, group.tools - shown.toSet()) }) {
                            Text(L.settings.reaGroups.removeValueShown(shown.count { it in inGroup }), fontSize = 12.5.sp)
                        }
                    }
                    if (state.tools.isEmpty()) Hint(L.settings.reaGroups.installReaAndRefreshToolsTo)
                }
            }
        }
        if (shown.isEmpty()) item { Hint(L.settings.reaGroups.noToolsMatch) }
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
                    Text(L.settings.reaGroups.notInTheInstalledReasTool, fontSize = 11.sp, color = Amber)
                } else if (tool.description.isNotBlank()) {
                    Text(tool.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                val meta = listOfNotNull(
                    tool?.takeIf { it.changes }?.let { L.settings.reaGroups.changesThings },
                    tool?.required?.takeIf { it.isNotEmpty() }?.let { "needs ${it.joinToString(", ")}" },
                    tool?.bytes?.takeIf { it > 0 }?.let { "≈${formatTokens(it / 4)} tokens" },
                    alsoIn.takeIf { it.isNotEmpty() }?.let { L.settings.reaGroups.alsoInValue(it.joinToString(", ")) }
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
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text(L.common.name) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it.take(300) }, label = { Text(L.settings.reaGroups.whatItIsForOptional) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, description) }) { Text(L.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}
