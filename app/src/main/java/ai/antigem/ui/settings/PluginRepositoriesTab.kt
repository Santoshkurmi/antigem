package ai.antigem.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.antigem.ui.browser.PluginRepo
import ai.antigem.ui.browser.PluginRepositories
import ai.antigem.ui.browser.PluginVersions
import ai.antigem.ui.browser.RepoPlugin
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * Settings → Custom AntiGem Plugins → Repositories: GitHub repositories of plugin folders, their plugins with
 * install/update status, and the guide for hosting one. [runOperation]'s result is shown as a notice.
 */
@Composable
fun PluginRepositoriesTab(busy: Boolean, runOperation: (suspend () -> String?) -> Unit, folderTaken: (String) -> Boolean, freeFolder: (String) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf<String?>(null) }
    // A new install whose default folder name is taken waits here for another name
    var pending by remember { mutableStateOf<Pair<PluginRepo, RepoPlugin>?>(null) }
    var folder by remember { mutableStateOf("") }

    fun install(repo: PluginRepo, plugin: RepoPlugin, name: String) = runOperation {
        val installed = PluginRepositories.install(repo, plugin, name, updateId = null)
        L.settings.pluginRepositoriesTab.installedFoldernameLabelFromLabel2(installed.folderName, PluginVersions.label(installed.version), repo.label)
    }
    fun installOrUpdate(repo: PluginRepo, plugin: RepoPlugin) {
        val installed = PluginRepositories.installedFor(repo, plugin)
        if (installed != null) runOperation {
            val updated = PluginRepositories.install(repo, plugin, installed.folderName, installed.id)
            "Updated ${updated.folderName}: ${PluginVersions.label(installed.version)} → ${PluginVersions.label(updated.version)}"
        } else {
            val name = plugin.path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "-").trimStart('.', '-', '_').take(64).ifBlank { "plugin" }
            if (folderTaken(name)) { pending = repo to plugin; folder = freeFolder(name) } else install(repo, plugin, name)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.settings.pluginRepositoriesTab.hostYourOwnRepository, style = MaterialTheme.typography.titleSmall)
                    Text(L.settings.pluginRepositoriesTab.howToLayOutAGithub(PluginRepositories.INDEX), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { guide = runCatching { readBundledGuide(context) }.getOrElse { L.settings.pluginRepositoriesTab.guideNotFoundMessage(it.message) } }) { Icon(Icons.Outlined.Visibility, L.settings.pluginRepositoriesTab.viewGuide) }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "antigem-plugin-repository.md", readBundledGuide(context)) } }) { Icon(Icons.Outlined.Download, L.settings.pluginRepositoriesTab.downloadGuide) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.settings.pluginRepositoriesTab.pluginSdkForPc, style = MaterialTheme.typography.titleSmall)
                    Text(L.settings.pluginRepositoriesTab.allPluginDocsAndExamplesAs, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "antigem-plugin-sdk.zip", bundledSdkZip(context), "application/zip") } }) { Icon(Icons.Outlined.FolderZip, L.settings.pluginRepositoriesTab.downloadPluginSdkZip) }
            }
        }
        OutlinedTextField(value = input, onValueChange = { input = it; addError = null }, label = { Text(L.settings.pluginRepositoriesTab.githubRepository) },
            placeholder = { Text(L.settings.pluginRepositoriesTab.ownerRepoOwnerRepoBranchOr) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = addError != null, supportingText = { Text(addError ?: L.settings.pluginRepositoriesTab.onlyAddRepositoriesYouTrustPlugins) })
        Button(enabled = input.isNotBlank(), onClick = {
            runCatching { PluginRepositories.parse(input).also { PluginRepositories.add(it) } }
                .onSuccess { repo -> input = ""; scope.launch { PluginRepositories.refresh(repo) } }
                .onFailure { addError = it.message }
        }) { Text(L.settings.pluginRepositoriesTab.addRepository) }

        if (PluginRepositories.repos.isEmpty()) Text(L.settings.pluginRepositoriesTab.noRepositoriesAdded, style = MaterialTheme.typography.bodySmall)
        PluginRepositories.repos.forEach { repo ->
            val listing = PluginRepositories.listings[repo.label]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listing?.title ?: repo.label, style = MaterialTheme.typography.titleMedium)
                            if (listing?.title != null && listing.title != repo.label) Text(repo.label, style = MaterialTheme.typography.bodySmall)
                        }
                        if (repo.label in PluginRepositories.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { scope.launch { PluginRepositories.refresh(repo) } }) { Icon(Icons.Outlined.Refresh, L.common.checkForUpdates) }
                        IconButton(onClick = { PluginRepositories.remove(repo) }) { Icon(Icons.Outlined.Delete, L.settings.pluginRepositoriesTab.removeRepository) }
                    }
                    PluginRepositories.errors[repo.label]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (listing != null && listing.plugins.isEmpty()) Text(L.settings.pluginRepositoriesTab.thisRepositoryListsNoPlugins, style = MaterialTheme.typography.bodySmall)
                    listing?.plugins?.forEach { plugin -> RepoPluginRow(repo, plugin, busy) { installOrUpdate(repo, plugin) } }
                }
            }
        }
    }

    guide?.let { MarkdownViewerDialog(L.settings.pluginRepositoriesTab.pluginRepositoryGuide, it, "antigem-plugin-repository.md") { guide = null } }
    pending?.let { (repo, plugin) ->
        val taken = folderTaken(folder)
        AlertDialog(
            onDismissRequest = { pending = null }, title = { Text(L.settings.pluginRepositoriesTab.installName(plugin.name)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.settings.pluginRepositoriesTab.aPluginAlreadyUsesTheFolder(plugin.path.substringAfterLast('/')))
                OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text(L.settings.pluginRepositoriesTab.folderName) }, singleLine = true,
                    isError = taken, supportingText = if (taken) {{ Text(L.settings.pluginRepositoriesTab.aPluginAlreadyUsesThisFolder) }} else null)
            } },
            confirmButton = { TextButton(enabled = folder.isNotBlank() && !taken, onClick = { val name = folder.trim(); pending = null; install(repo, plugin, name) }) { Text(L.common.install) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun RepoPluginRow(repo: PluginRepo, plugin: RepoPlugin, busy: Boolean, onInstall: () -> Unit) {
    val installed = PluginRepositories.installedFor(repo, plugin)
    val comparison = installed?.let { PluginVersions.compare(plugin.version, it.version) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PluginIcon(plugin.iconUrl, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${plugin.name} · ${PluginVersions.label(plugin.version)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (installed != null) Text(when {
                comparison == null -> L.settings.pluginRepositoriesTab.installedLabelAsFoldername(PluginVersions.label(installed.version), installed.folderName)
                comparison > 0 -> L.settings.pluginRepositoriesTab.updateAvailableLabelLabel2(PluginVersions.label(installed.version), PluginVersions.label(plugin.version))
                comparison == 0 -> L.settings.pluginRepositoriesTab.installedUpToDate
                else -> L.settings.pluginRepositoriesTab.installedLabelIsNewerThanThis(PluginVersions.label(installed.version))
            }, style = MaterialTheme.typography.labelSmall, color = if (comparison != null && comparison > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        when {
            installed == null -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(L.common.install) }
            comparison != null && comparison > 0 -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(L.common.update) }
            else -> TextButton(enabled = !busy, onClick = onInstall) { Text(L.common.reinstall) }
        }
    }
}

/** Real progress of a repository install or update; the bar only moves without a percentage while the size is unknown. */
@Composable
internal fun PluginInstallProgress(progress: ai.antigem.ui.browser.RepoInstallProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val fraction = progress.fraction
    val files = if (progress.filesTotal > 0) "${progress.filesDone}/${progress.filesTotal} files" else null
    val label = when (progress.phase) {
        "listing" -> L.settings.pluginRepositoriesTab.readingTheRepository
        "downloading" -> listOfNotNull(
            L.settings.pluginRepositoriesTab.downloading, files,
            if (progress.bytesTotal > 0) "${size(progress.bytesDone)} / ${size(progress.bytesTotal)}" else size(progress.bytesDone)
        ).joinToString(" · ")
        "extracting" -> listOfNotNull(L.settings.pluginRepositoriesTab.extracting, files).joinToString(" · ")
        "installing" -> L.common.installing
        else -> L.settings.pluginRepositoriesTab.startingPlugin
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (progress.note.isNotBlank()) Text(progress.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
