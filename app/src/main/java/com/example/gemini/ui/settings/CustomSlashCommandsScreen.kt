package com.example.gemini.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gemini.data.remote.CustomSlashCommand
import com.example.gemini.data.remote.CustomSlashCommands
import com.example.gemini.ui.browser.BrowserExtensionManager

@Composable
fun CustomSlashCommandsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CustomSlashCommands.init(context) }
    var editing by remember { mutableStateOf<CustomSlashCommand?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomSlashCommand?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create your own / commands for chat. When you send one, the model gets the text it expands to, while the chat keeps showing the short command.", style = MaterialTheme.typography.bodyMedium)
        Text("Use \$ARGUMENTS in the expansion to insert whatever you type after the command. A command can also attach a custom AntiGem plugin and one of its skills, like /plu: does.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("Add command") }
            OutlinedButton(onClick = { CustomSlashCommands.restoreDefaults() }) { Text("Restore defaults") }
        }
        if (CustomSlashCommands.commands.isEmpty()) Text("No custom commands yet.", style = MaterialTheme.typography.bodySmall)
        CustomSlashCommands.commands.forEach { command ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("/${command.name}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        if (CustomSlashCommands.isDefault(command.name)) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("Built-in", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (command.description.isNotBlank()) Text(command.description, style = MaterialTheme.typography.bodySmall)
                    if (command.pluginId.isNotBlank()) Text("Plugin: ${command.pluginId}${if (command.skill.isNotBlank()) " · skill: ${command.skill}" else ""}", style = MaterialTheme.typography.bodySmall)
                    if (command.expansion.isNotBlank()) Text(command.expansion, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = command }) { Text("Edit") }
                        TextButton(onClick = { deleting = command }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (creating || editing != null) CustomSlashCommandDialog(
        initial = editing,
        onDismiss = { creating = false; editing = null }
    )
    deleting?.let { command -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("Delete /${command.name}?") },
        confirmButton = { TextButton(onClick = { CustomSlashCommands.delete(command.name); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
    ) }
}

@Composable
private fun CustomSlashCommandDialog(initial: CustomSlashCommand?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var expansion by remember { mutableStateOf(initial?.expansion.orEmpty()) }
    var pluginId by remember { mutableStateOf(initial?.pluginId.orEmpty()) }
    var skill by remember { mutableStateOf(initial?.skill.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val plugins = BrowserExtensionManager.instance.extensions.filter { it.isEnabled || it.id == pluginId }
    val plugin = plugins.firstOrNull { it.id == pluginId }
    val skills = plugin?.skills.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New slash command" else "Edit /${initial.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.removePrefix("/").trim() }, label = { Text("Name") }, prefix = { Text("/") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (shown in the / list)") }, singleLine = true)
                OutlinedTextField(value = expansion, onValueChange = { expansion = it }, label = { Text("Expands to") },
                    placeholder = { Text("e.g. Review \$ARGUMENTS for bugs and explain each one") }, minLines = 4, maxLines = 10)
                Choice("Plugin", plugin?.let { "${it.folderName} (${it.name})" } ?: "None",
                    listOf("" to "None") + plugins.map { it.id to "${it.folderName} (${it.name})" }) { pluginId = it; skill = "" }
                if (plugin != null && skills.isNotEmpty()) Choice("Skill", skill.ifBlank { "None (whole plugin)" },
                    listOf("" to "None (whole plugin)") + skills.map { it.name to it.name }) { skill = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { CustomSlashCommands.save(CustomSlashCommand(name, description.trim(), expansion.trim(), pluginId, skill), initial?.name) }
                .onSuccess { onDismiss() }.onFailure { error = it.message }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
            }
        }
    }
}
