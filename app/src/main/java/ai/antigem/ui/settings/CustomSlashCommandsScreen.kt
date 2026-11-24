package ai.antigem.ui.settings

import ai.antigem.ui.components.bouncyVerticalScroll
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
import ai.antigem.data.remote.CustomSlashCommand
import ai.antigem.data.remote.CustomSlashCommands
import ai.antigem.ui.browser.BrowserExtensionManager
import ai.antigem.i18n.L

@Composable
fun CustomSlashCommandsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CustomSlashCommands.init(context) }
    var editing by remember { mutableStateOf<CustomSlashCommand?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomSlashCommand?>(null) }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(L.settings.customSlashCommands.createYourOwnCommandsForChat, style = MaterialTheme.typography.bodyMedium)
        Text(L.settings.customSlashCommands.useArgumentsInTheExpansionTo, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text(L.settings.customSlashCommands.addCommand) }
            OutlinedButton(onClick = { CustomSlashCommands.restoreDefaults() }) { Text(L.settings.customSlashCommands.restoreDefaults) }
        }
        if (CustomSlashCommands.commands.isEmpty()) Text(L.settings.customSlashCommands.noCustomCommandsYet, style = MaterialTheme.typography.bodySmall)
        CustomSlashCommands.commands.forEach { command ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("/${command.name}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        if (CustomSlashCommands.isDefault(command.name)) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(L.common.builtIn, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (command.description.isNotBlank()) Text(command.description, style = MaterialTheme.typography.bodySmall)
                    if (command.pluginId.isNotBlank()) Text(L.settings.customSlashCommands.pluginPluginidValue(command.pluginId, if (command.skill.isNotBlank()) " · skill: ${command.skill}" else ""), style = MaterialTheme.typography.bodySmall)
                    if (command.expansion.isNotBlank()) Text(command.expansion, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = command }) { Text(L.common.edit) }
                        TextButton(onClick = { deleting = command }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
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
        confirmButton = { TextButton(onClick = { CustomSlashCommands.delete(command.name); deleting = null }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(L.common.cancel) } }
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
        title = { Text(if (initial == null) L.settings.customSlashCommands.newSlashCommand else L.settings.customSlashCommands.editName(initial.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.removePrefix("/").trim() }, label = { Text(L.common.name) }, prefix = { Text("/") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(L.settings.customSlashCommands.descriptionShownInTheList) }, singleLine = true)
                OutlinedTextField(value = expansion, onValueChange = { expansion = it }, label = { Text(L.settings.customSlashCommands.expandsTo) },
                    placeholder = { Text(L.settings.customSlashCommands.eGReviewArgumentsForBugs) }, minLines = 4, maxLines = 10)
                Choice(L.settings.customSlashCommands.plugin, plugin?.let { "${it.folderName} (${it.name})" } ?: L.settings.customSlashCommands.none,
                    listOf("" to L.settings.customSlashCommands.none) + plugins.map { it.id to "${it.folderName} (${it.name})" }) { pluginId = it; skill = "" }
                if (plugin != null && skills.isNotEmpty()) Choice(L.settings.customSlashCommands.skill, skill.ifBlank { L.settings.customSlashCommands.noneWholePlugin },
                    listOf("" to L.settings.customSlashCommands.noneWholePlugin) + skills.map { it.name to it.name }) { skill = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { CustomSlashCommands.save(CustomSlashCommand(name, description.trim(), expansion.trim(), pluginId, skill), initial?.name) }
                .onSuccess { onDismiss() }.onFailure { error = it.message }
        }) { Text(L.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
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
