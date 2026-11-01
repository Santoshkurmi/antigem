package com.example.gemini.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gemini.ui.browser.BrowserExtension
import com.example.gemini.ui.browser.BrowserExtensionManager
import com.example.gemini.ui.browser.PluginRepositories
import com.example.gemini.ui.browser.PluginVersions
import kotlinx.coroutines.launch

@Composable
fun WebViewExtensionsScreen(onChanged: () -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<BrowserExtension?>(null) }
    var zip by remember { mutableStateOf<Uri?>(null) }
    // Set when a new ZIP matches an installed plugin (same folder or ID): ask to update it or install a separate copy
    var conflict by remember { mutableStateOf<BrowserExtension?>(null) }
    var asNew by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("") }
    var zipVersion by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    fun folderTaken(name: String) = manager.extensions.any { it.folderName.equals(name.trim(), ignoreCase = true) }
    fun freeFolder(base: String) = generateSequence(2) { it + 1 }.map { "${base.take(60)}-$it" }.first { !folderTaken(it) }
    var tab by remember { mutableIntStateOf(0) }
    // ID of the plugin being zipped for download, and the plugin waiting for remove confirmation
    var exporting by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<BrowserExtension?>(null) }
    var deleteData by remember { mutableStateOf(false) }
    /** Runs a plugin change; the text it returns is shown as a notice. */
    fun runOperation(action: suspend () -> String?) {
        scope.launch {
            busy = true; error = null; notice = null
            runCatching { action() }.onSuccess { notice = it; onChanged() }.onFailure { error = it.message ?: "Plugin operation failed" }
            busy = false
        }
    }
    fun install(uri: Uri, name: String, updateId: String?, copy: Boolean, previousVersion: String?) = runOperation {
        val installed = manager.installArchive(uri, name, updateId, asNew = copy)
        if (previousVersion != null) "Updated ${installed.folderName}: ${PluginVersions.label(previousVersion)} → ${PluginVersions.label(installed.version)}"
            else "Installed ${installed.folderName} ${PluginVersions.label(installed.version)}"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        asNew = false
        val filename = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        folder = update?.folderName ?: filename.orEmpty().substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "my-extension" }
        scope.launch {
            busy = true
            // An unreadable ZIP is reported by the install itself
            val manifest = runCatching { manager.archiveManifest(uri) }.getOrNull()
            busy = false
            zipVersion = manifest?.optString("version").orEmpty()
            val id = manifest?.optString("id").orEmpty()
            if (update == null) conflict = manager.extensions.firstOrNull { it.folderName.equals(folder, ignoreCase = true) || (id.isNotBlank() && it.id == id) }
            zip = uri
        }
    }
    LaunchedEffect(Unit) { runCatching { manager.refreshExtensions() }.onFailure { error = it.message } }
    // Repository listings also drive the "update available" line on installed plugins
    LaunchedEffect(Unit) { PluginRepositories.refreshAll() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Repositories") })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (tab == 1) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PluginRepositoriesTab(busy, ::runOperation, ::folderTaken, ::freeFolder)
            return@Column
        }
        Text("Install custom AntiGem plugins from ZIP files or from a repository. A plugin can have MCP tools, skills (skills/*.md), or both. Changes apply to the running browser immediately.", style = MaterialTheme.typography.bodyMedium)
        Text("To create a plugin with your model, add Custom AntiGem Plugin Developer in MCP settings. It supplies the SDK, draft editing, installation, updates, tests and worker logs.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { update = null; picker.launch(arrayOf("*/*")) }) { Text("Install ZIP") }
            OutlinedButton(enabled = !busy, onClick = { runOperation { manager.refreshExtensions(); null } }) { Text("Refresh") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
        manager.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (manager.extensions.isEmpty()) Text("No plugins installed. Choose a ZIP containing manifest.json plus a background worker, a skills/ folder, or both.")
        manager.extensions.forEach { extension ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PluginIcon(extension.iconPath)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(extension.folderName, style = MaterialTheme.typography.titleMedium)
                            Text("${extension.name} · ${PluginVersions.label(extension.version)} · ${extension.toolCount} MCP tools" + (if (extension.skillCount > 0) " · ${extension.skillCount} skills" else ""), style = MaterialTheme.typography.bodySmall)
                            extension.source?.let { Text("From ${it.repo}" + (if (it.ref.isNotBlank()) "@${it.ref}" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (exporting == extension.id) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                        else IconButton(enabled = exporting == null, onClick = {
                            scope.launch {
                                exporting = extension.id; error = null
                                runCatching {
                                    val zip = manager.exportZip(extension.id)
                                    try { saveToDownloads(context, zip, "application/zip") } finally { zip.parentFile?.deleteRecursively() }
                                }.onFailure { error = "Could not zip ${extension.folderName}: ${it.message}" }
                                exporting = null
                            }
                        }) { Icon(Icons.Outlined.Download, "Download as ZIP") }
                        IconButton(enabled = !busy, onClick = { removing = extension; deleteData = false }) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        Switch(checked = extension.isEnabled, enabled = !busy, onCheckedChange = { enabled -> runOperation { manager.setEnabled(extension.id, enabled); null } })
                    }
                    if (extension.description.isNotBlank()) Text(extension.description, style = MaterialTheme.typography.bodySmall)
                    val source = extension.source
                    val repo = source?.let { s -> PluginRepositories.repos.firstOrNull { it.slug.equals(s.repo, ignoreCase = true) && it.ref == s.ref } }
                    val offered = PluginRepositories.updateFor(extension)
                    offered?.let { Text("Update available: ${PluginVersions.label(extension.version)} → ${PluginVersions.label(it.version)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (repo != null && offered != null) TextButton(enabled = !busy, onClick = {
                            runOperation {
                                val updated = PluginRepositories.install(repo, offered, extension.folderName, extension.id)
                                "Updated ${updated.folderName}: ${PluginVersions.label(extension.version)} → ${PluginVersions.label(updated.version)}"
                            }
                        }) { Text("Update") }
                        TextButton(enabled = !busy, onClick = { update = extension; picker.launch(arrayOf("*/*")) }) { Text("Update ZIP") }
                        TextButton(enabled = !busy && extension.isEnabled, onClick = { runOperation { manager.reload(extension.id); null } }) { Text("Reload") }
                    }
                }
            }
        }
        Text("Add an enabled plugin to your model in MCP settings → Custom AntiGem Plugin MCP; its skills come with it as get_skills / get_skill tools. In chat, type /plu: to point the model at a plugin or one of its skills. Updating or reloading restarts that plugin's worker; its MCP connection and stored data are kept.", style = MaterialTheme.typography.bodySmall)
    }
    conflict?.takeIf { zip != null }?.let { existing -> AlertDialog(
        onDismissRequest = { zip = null; conflict = null },
        title = { Text("Plugin already installed") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${existing.folderName} (${existing.name}) is already installed. Update it with this ZIP, or install the ZIP as a separate new plugin?")
            VersionChange(existing.version, zipVersion)
        } },
        confirmButton = { TextButton(onClick = {
            val uri = zip!!; zip = null; conflict = null
            install(uri, existing.folderName, existing.id, copy = false, previousVersion = existing.version)
        }) { Text("Update") } },
        dismissButton = { Row {
            TextButton(onClick = { zip = null; conflict = null }) { Text("Cancel") }
            TextButton(onClick = { conflict = null; asNew = true; folder = freeFolder(folder) }) { Text("Install as new") }
        } }
    ) }
    removing?.let { extension -> AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text("Remove ${extension.folderName}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (extension.location) {
                BrowserExtension.Location.BUNDLED -> "This is built into the app: it is removed now and comes back when the app restarts. To keep it off, disable it instead."
                BrowserExtension.Location.USER_FOLDER -> "This deletes the folder ${extension.dirPath} from your files. Make sure you have another copy if it is your source."
                BrowserExtension.Location.INSTALLED -> "Deletes the plugin from ${extension.dirPath}."
            })
            Text("If you added it in MCP settings, remove that server there too.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                Text("Also delete its stored data (otherwise kept for a reinstall)", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            val wipe = deleteData; removing = null
            runOperation { manager.uninstall(extension.id, wipe); "Removed ${extension.folderName}" }
        }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
    ) }
    if (zip != null && conflict == null) {
        val taken = update == null && folderTaken(folder)
        AlertDialog(
            onDismissRequest = { zip = null }, title = { Text(if (update != null) "Update plugin" else if (asNew) "Install as new plugin" else "Install plugin") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = folder, onValueChange = { folder = it }, enabled = update == null, label = { Text("Folder name") }, singleLine = true,
                    isError = taken, supportingText = if (taken) {{ Text("A plugin already uses this folder name") }} else null)
                update?.let { VersionChange(it.version, zipVersion) }
                if (update == null) Text("Version: ${PluginVersions.label(zipVersion)}", style = MaterialTheme.typography.bodySmall)
                Text(when {
                    update != null -> "The plugin's ID and stored data are preserved. The previous package is restored if loading fails."
                    asNew -> "Installed as a separate plugin next to the existing one, with its own ID taken from this folder name. Add it to MCP settings separately."
                    else -> "The ZIP may contain manifest.json directly or inside one enclosing folder."
                })
            } },
            confirmButton = { TextButton(enabled = folder.isNotBlank() && !taken, onClick = {
                val uri = zip!!; val id = update?.id; val name = folder.trim(); val copy = asNew; val previous = update?.version; zip = null
                install(uri, name, id, copy, previous)
            }) { Text(if (update == null) "Install" else "Update") } },
            dismissButton = { TextButton(onClick = { zip = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun WebViewExtensionMcpPicker(onDismiss: () -> Unit, onSelect: (BrowserExtension) -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    LaunchedEffect(Unit) { manager.loadExtensions() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Custom AntiGem Plugin MCP") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val available = manager.extensions.filter { it.isEnabled && (it.toolCount > 0 || it.skillCount > 0) }
            if (available.isEmpty()) Text("Enable or install a plugin in Settings → Custom AntiGem Plugins first.")
            available.forEach { extension ->
                OutlinedButton(onClick = { onSelect(extension) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PluginIcon(extension.iconPath, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                        Text(extension.folderName)
                        Text("${extension.name} · ${extension.toolCount} tools" + (if (extension.skillCount > 0) " · ${extension.skillCount} skills" else ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
