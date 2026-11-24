package ai.antigem.ui.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.agent.agy.AgyCloud
import ai.antigem.theme.GeminiBlue
import exa.language_server_pb.RemoteResource
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private val subtleAlpha = 0.6f

@Composable
private fun subtle() = MaterialTheme.colorScheme.onSurface.copy(alpha = subtleAlpha)

/** Opens the page that connects [pluginId] to the cloud agents, as the Antigravity app does (see [AgyCloud.connectUrl]). */
private fun openConnect(
    scope: kotlinx.coroutines.CoroutineScope,
    uri: androidx.compose.ui.platform.UriHandler,
    pluginId: String,
    // shown in the dialog itself: the app's toasts are drawn under these full-screen dialogs
    onError: (String) -> Unit,
    onOpened: () -> Unit = {}
) {
    scope.launch {
        AgyCloud.connectUrl(pluginId)
            .onSuccess { url ->
                runCatching { uri.openUri(url) }
                    .onSuccess { onOpened() }
                    .onFailure { onError("Couldn't open the browser: ${it.message}") }
            }
            .onFailure { onError(it.message ?: "Couldn't get the link to connect") }
    }
}

/** Runs [onResume] when the app comes back to the front (e.g. from the browser after connecting a service). */
@Composable
private fun OnAppResume(onResume: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onResume)
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) latest.value()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Settings → Antigravity → Cloud agents: the on/off switch (off by default), and while on the cloud projects (repos)
 * and the connections the cloud agent may use (GitHub, Drive, …).
 */
@Composable
fun AgyCloudSettingsCard(modifier: Modifier = Modifier) {
    val enabled by AgyCloud.enabled.collectAsState()
    val projects by AgyCloud.projects.collectAsState()
    val loading by AgyCloud.loading.collectAsState()
    val cloudError by AgyCloud.error.collectAsState()
    var github by remember { mutableStateOf<AgyCloud.CloudPlugin?>(null) }
    var githubChecked by remember { mutableStateOf(false) }
    /** The projects dialog on screen: its tab (0 projects, 1 connections), or null. */
    var dialogTab by remember { mutableStateOf<Int?>(null) }
    // on open, after switching on, and after the dialog closes (something may have changed there)
    LaunchedEffect(enabled, dialogTab) {
        if (enabled && dialogTab == null) {
            AgyCloud.refreshProjects()
            github = AgyCloud.plugins().getOrNull()?.firstOrNull { it.id == "github" }
            githubChecked = true
        }
    }
    dialogTab?.let { CloudProjectsDialog(initialTab = it, onStartChatIn = null, onDismiss = { dialogTab = null }) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(L.cloud.agyCloudUi.cloudAgents, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { AgyCloud.setEnabled(it) })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    L.cloud.agyCloudUi.antigravityChatsCanRunOnA,
                    fontSize = 12.5.sp, color = subtle()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (enabled) L.cloud.agyCloudUi.onCloudProjectsShowInThe
                    else L.cloud.agyCloudUi.offTheAppDoesNotAsk,
                    fontSize = 12.5.sp, color = subtle()
                )
            }
        }
        if (enabled) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    CloudSettingsRow(
                        icon = Icons.Outlined.Cloud,
                        title = L.cloud.agyCloudUi.cloudProjectsRepos,
                        value = when {
                            loading && projects.isEmpty() -> L.common.loading
                            cloudError != null && projects.isEmpty() -> L.cloud.agyCloudUi.notReady
                            projects.size == 1 -> "1 project"
                            else -> "${projects.size} projects"
                        },
                        onClick = { dialogTab = 0 }
                    )
                    HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    CloudSettingsRow(
                        icon = Icons.Outlined.Link,
                        title = L.cloud.agyCloudUi.connectionsGithubDrive,
                        value = when {
                            !githubChecked -> "…"
                            github == null -> ""
                            github!!.connected -> L.cloud.agyCloudUi.githubConnected
                            github!!.expired -> L.cloud.agyCloudUi.githubExpired
                            else -> L.cloud.agyCloudUi.githubNotConnected
                        },
                        onClick = { dialogTab = 1 }
                    )
                }
            }
            cloudError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CloudSettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = GeminiBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(value, fontSize = 12.5.sp, color = subtle(), maxLines = 1)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.padding(start = 2.dp).size(20.dp)
        )
    }
}

private fun repoSummary(p: AgyCloud.CloudProject): String = when (p.repos.size) {
    0 -> L.cloud.agyCloudUi.noRepos
    1 -> p.repos[0].display_name.ifBlank { p.repos[0].resource_id }
    else -> "${p.repos[0].display_name.ifBlank { p.repos[0].resource_id }} +${p.repos.size - 1}"
}

// ------------------------------------------------------------------------------------------------ project chooser section

/**
 * The cloud part of the project chooser: the account's Antigravity cloud projects (chats run in Google's VM with the
 * project's repos), a new one, and managing them.
 */
fun LazyListScope.cloudProjectsSection(
    projects: List<AgyCloud.CloudProject>,
    selectedId: String?,
    error: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit
) {
    item(key = "cloud_header") {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(L.cloud.agyCloudUi.cloud, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Text(L.cloud.agyCloudUi.runsInGooglesCloudWithYour, fontSize = 11.sp, color = subtle(), lineHeight = 14.sp)
            }
            TextButton(onClick = onManage) { Text(L.cloud.agyCloudUi.manage, fontSize = 12.5.sp) }
        }
    }
    if (error != null && projects.isEmpty()) {
        item(key = "cloud_error") { Text(error, fontSize = 11.5.sp, color = subtle(), lineHeight = 15.sp) }
    }
    items(projects, key = { "cloud_" + it.id }) { p ->
        val on = p.id == selectedId
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (on) GeminiBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            border = BorderStroke(1.5.dp, if (on) GeminiBlue.copy(alpha = 0.7f) else Color.Transparent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(p.id) }
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(GeminiBlue.copy(alpha = if (on) 0.25f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(if (on) Icons.Outlined.Check else Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(17.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(repoSummary(p), fontSize = 11.5.sp, color = subtle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    item(key = "cloud_new") {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onCreate)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, null, tint = GeminiBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(L.cloud.agyCloudUi.newCloudProject, color = GeminiBlue, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
            }
        }
    }
    item(key = "cloud_divider") { HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) }
}

// ------------------------------------------------------------------------------------------------ manage dialog

/**
 * Antigravity cloud agents: the cloud projects (repos, rename, delete, start a chat) and the connections the cloud
 * agent may use (GitHub, Google Drive, …; connected on antigravity.google.com).
 */
@Composable
fun CloudProjectsDialog(
    startWithCreate: Boolean = false,
    /** 0 = Projects, 1 = Connections. */
    initialTab: Int = 0,
    /** Starts a chat in a project; null (from Settings): manage only, no chat buttons. */
    onStartChatIn: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val projects by AgyCloud.projects.collectAsState()
    val loading by AgyCloud.loading.collectAsState()
    val error by AgyCloud.error.collectAsState()
    var tab by remember { mutableIntStateOf(initialTab) }
    var editor by remember { mutableStateOf<EditorTarget?>(if (startWithCreate) EditorTarget.New else null) }
    var renaming by remember { mutableStateOf<AgyCloud.CloudProject?>(null) }
    var deleting by remember { mutableStateOf<AgyCloud.CloudProject?>(null) }
    /** A rename or delete that failed (shown here: the app's toasts are drawn under this dialog). */
    var actionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AgyCloud.refreshProjects() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L.cloud.agyCloudUi.cloudAgents, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(L.cloud.agyCloudUi.antigravityRemoteAgentsGoogleAiPro, fontSize = 11.5.sp, color = subtle())
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, L.common.close) }
                }
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(L.common.projects) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(L.cloud.agyCloudUi.connections) })
                }
                actionError?.let {
                    Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).clickable { actionError = null })
                }
                Spacer(Modifier.height(10.dp))
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { editor = EditorTarget.New },
                            colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.cloud.agyCloudUi.newCloudProject)
                        }
                        Spacer(Modifier.weight(1f))
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { scope.launch { AgyCloud.refreshProjects() } }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
                    }
                    if (error != null) Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                        if (projects.isEmpty() && !loading && error == null) item {
                            Text(
                                L.cloud.agyCloudUi.noCloudProjectsYetACloud,
                                fontSize = 13.sp, color = subtle(), lineHeight = 18.sp
                            )
                        }
                        items(projects, key = { it.id }) { p ->
                            ProjectCard(
                                p,
                                onNewChat = onStartChatIn?.let { start -> { start(p.id) } },
                                onRepos = { editor = EditorTarget.Repos(p) },
                                onRename = { renaming = p },
                                onDelete = { deleting = p }
                            )
                        }
                    }
                } else {
                    ConnectionsTab(Modifier.weight(1f))
                }
            }
        }
    }

    when (val e = editor) {
        EditorTarget.New -> CloudProjectEditor(
            title = L.cloud.agyCloudUi.newCloudProject,
            initialName = "",
            initialRepos = emptyList(),
            askName = true,
            confirm = L.common.create,
            onSave = { name, repos ->
                AgyCloud.createProject(name, repos).map { id -> editor = null; onStartChatIn?.invoke(id) }
            },
            onDismiss = { editor = null }
        )
        is EditorTarget.Repos -> CloudProjectEditor(
            title = L.cloud.agyCloudUi.reposOfName(e.project.name),
            initialName = e.project.name,
            initialRepos = e.project.repos,
            askName = false,
            confirm = L.common.save,
            onSave = { _, repos -> AgyCloud.setRepos(e.project.id, repos).map { editor = null } },
            onDismiss = { editor = null }
        )
        null -> Unit
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(L.cloud.agyCloudUi.renameProject) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(L.common.name) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        actionError = null
                        AgyCloud.rename(p.id, name).onFailure { actionError = L.cloud.agyCloudUi.renameFailedValue(it.message ?: L.cloud.agyCloudUi.unknownError) }
                        renaming = null
                    }
                }) { Text(L.common.rename) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(L.common.cancel) } }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text(L.cloud.agyCloudUi.theCloudProjectAndItsCloud) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        actionError = null
                        AgyCloud.deleteProject(p.id).onFailure { actionError = L.cloud.agyCloudUi.deleteFailedValue(it.message ?: L.cloud.agyCloudUi.unknownError) }
                        deleting = null
                    }
                }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(L.common.cancel) } }
        )
    }
}

private sealed class EditorTarget {
    data object New : EditorTarget()
    data class Repos(val project: AgyCloud.CloudProject) : EditorTarget()
}

@Composable
private fun ProjectCard(
    p: AgyCloud.CloudProject,
    onNewChat: (() -> Unit)?,
    onRepos: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, L.common.more, modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(L.common.rename) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (p.repos.isEmpty()) Text(L.cloud.agyCloudUi.noReposTheAgentStartsIn, fontSize = 12.sp, color = subtle())
            p.repos.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    if (r.is_private) Icon(Icons.Outlined.Lock, L.common.private, tint = subtle(), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(r.display_name.ifBlank { r.resource_id }, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onNewChat != null) Button(onClick = onNewChat, colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue), shape = RoundedCornerShape(12.dp)) {
                    Text(L.cloud.agyCloudUi.newChatHere, fontSize = 13.sp)
                }
                OutlinedButton(onClick = onRepos, shape = RoundedCornerShape(12.dp)) { Text(L.cloud.agyCloudUi.repos, fontSize = 13.sp) }
            }
        }
    }
}

/** Name and GitHub repos of a cloud project; the repos come from the GitHub connection on antigravity.google.com. */
@Composable
private fun CloudProjectEditor(
    title: String,
    initialName: String,
    initialRepos: List<RemoteResource>,
    askName: Boolean,
    confirm: String,
    onSave: suspend (String, List<RemoteResource>) -> Result<Unit>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var name by remember { mutableStateOf(initialName) }
    var available by remember { mutableStateOf<List<RemoteResource>?>(null) }
    var notConnected by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(initialRepos.map { it.resource_id }.toSet()) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    /** Why saving (or connecting GitHub) failed: shown in the dialog, the app's toasts are drawn under it. */
    var saveError by remember { mutableStateOf<String?>(null) }

    // back from GitHub's sign-in in the browser: load the repos again
    var connecting by remember { mutableStateOf(false) }
    val connectScope = rememberCoroutineScope()
    OnAppResume { if (connecting) { connecting = false; reload++ } }

    LaunchedEffect(reload) {
        available = null; notConnected = false; loadError = null
        AgyCloud.githubRepos()
            .onSuccess { available = it }
            .onFailure { if (it is AgyCloud.GitHubNotConnected) notConnected = true else loadError = it.message }
    }
    // repos already in the project stay listed even if the connection no longer shows them
    val all = remember(available, initialRepos) {
        (available.orEmpty() + initialRepos).distinctBy { it.resource_id }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                if (askName) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(L.cloud.agyCloudUi.projectName) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.cloud.agyCloudUi.githubRepos, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { reload++ }, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.Refresh, L.common.reload, modifier = Modifier.size(18.dp)) }
                }
                when {
                    notConnected -> {
                        Text(
                            L.cloud.agyCloudUi.githubIsNotConnectedToYour,
                            fontSize = 12.5.sp, lineHeight = 17.sp, color = subtle()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { saveError = null; openConnect(connectScope, uri, "github", onError = { saveError = it }) { connecting = true } }) { Text(L.cloud.agyCloudUi.connectGithub) }
                    }
                    loadError != null -> Text(loadError!!, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                    available == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(L.cloud.agyCloudUi.loadingYourRepos, fontSize = 12.5.sp)
                    }
                    all.isEmpty() -> Text(L.cloud.agyCloudUi.noReposAreSharedWithAntigravity, fontSize = 12.5.sp, color = subtle())
                }
                if (all.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(all, key = { it.resource_id }) { r ->
                            val on = r.resource_id in picked
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { picked = if (on) picked - r.resource_id else picked + r.resource_id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = { picked = if (on) picked - r.resource_id else picked + r.resource_id })
                                Column(Modifier.weight(1f)) {
                                    Text(r.display_name.ifBlank { r.resource_id }, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (r.is_private) Text(L.cloud.agyCloudUi.private, fontSize = 10.5.sp, color = subtle())
                                }
                            }
                        }
                    }
                    Text(L.cloud.agyCloudUi.privateReposInOneProjectMust, fontSize = 11.sp, color = subtle(), modifier = Modifier.padding(top = 4.dp))
                }
                saveError?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (!askName || name.isNotBlank()),
                onClick = {
                    saving = true
                    saveError = null
                    scope.launch {
                        val repos = all.filter { it.resource_id in picked }
                        onSave(name, repos).onFailure { saveError = L.cloud.agyCloudUi.couldntSaveValue(it.message ?: L.cloud.agyCloudUi.unknownError) }
                        saving = false
                    }
                }
            ) { Text(if (saving) L.common.saving else confirm) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** The connections the cloud agent may use (GitHub, Google Drive, Docs, …): connected on antigravity.google.com. */
@Composable
private fun ConnectionsTab(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var plugins by remember { mutableStateOf<List<AgyCloud.CloudPlugin>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var disconnecting by remember { mutableStateOf<AgyCloud.CloudPlugin?>(null) }
    // back from a sign-in in the browser: show the new state
    var connecting by remember { mutableStateOf(false) }
    OnAppResume { if (connecting) { connecting = false; reload++ } }
    LaunchedEffect(reload) {
        error = null
        AgyCloud.plugins().onSuccess { plugins = it }.onFailure { error = it.message }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L.cloud.agyCloudUi.connectOpensAntigravitysPageForIt,
                fontSize = 12.sp, color = subtle(), lineHeight = 16.sp, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
        }
        if (error != null) Text(error!!, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
        if (plugins == null && error == null) CircularProgressIndicator(Modifier.padding(16.dp).size(22.dp), strokeWidth = 2.dp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(plugins.orEmpty(), key = { it.id }) { p ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                when {
                                    p.connected -> p.account.takeIf { it.isNotBlank() }?.let { L.cloud.agyCloudUi.connectedAccount(it) } ?: L.cloud.agyCloudUi.connected
                                    p.expired -> L.cloud.agyCloudUi.expiredConnectAgain
                                    else -> L.cloud.agyCloudUi.notConnected
                                },
                                fontSize = 12.sp,
                                color = if (p.connected) ai.antigem.theme.AppColors.success else subtle()
                            )
                        }
                        if (p.connected) TextButton(onClick = { disconnecting = p }) { Text(L.common.disconnect, color = MaterialTheme.colorScheme.error) }
                        else OutlinedButton(onClick = { error = null; openConnect(scope, uri, p.id, onError = { error = it }) { connecting = true } }) { Text(if (p.expired) L.common.reconnect else L.common.connect) }
                    }
                }
            }
        }
    }
    disconnecting?.let { p ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("Disconnect ${p.name}?") },
            text = { Text(L.cloud.agyCloudUi.cloudAgentsCanNoLongerUse(p.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        AgyCloud.disconnectPlugin(p).onFailure { error = L.cloud.agyCloudUi.disconnectFailedValue(it.message ?: L.cloud.agyCloudUi.unknownError) }
                        disconnecting = null
                        reload++
                    }
                }) { Text(L.common.disconnect, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { disconnecting = null }) { Text(L.common.cancel) } }
        )
    }
}

// ------------------------------------------------------------------------------------------------ in a cloud chat

/** Above the message box of a cloud chat: its project, web preview, run a command. */
@Composable
fun CloudChatStrip(projectName: String?, onProject: () -> Unit, onPreview: () -> Unit, onRunCommand: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(50)).clickable(onClick = onProject).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Cloud, null, tint = GeminiBlue, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(projectName ?: L.cloud.agyCloudUi.cloud, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = GeminiBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StripChip(Icons.Outlined.Visibility, L.common.preview, onPreview)
        StripChip(Icons.Outlined.Terminal, L.common.run, onRunCommand)
    }
}

@Composable
private fun StripChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

/**
 * Runs a command in the cloud VM. The VM has no terminal for apps (Google refuses it), so the agent runs the command
 * and shows its output in the chat.
 */
@Composable
fun CloudRunCommandDialog(onRun: (String) -> Unit, onDismiss: () -> Unit) {
    var cmd by remember { mutableStateOf("") }
    val examples = listOf("git status", "npm run build", "npm test", "ls -la")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Terminal, null) },
        title = { Text(L.cloud.agyCloudUi.runACommandInTheCloud) },
        text = {
            Column {
                OutlinedTextField(
                    cmd, { cmd = it }, label = { Text(L.common.command) }, singleLine = false, maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    examples.take(3).forEach { e ->
                        Surface(onClick = { cmd = e }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                            Text(e, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(L.cloud.agyCloudUi.theAgentRunsItAndThe, fontSize = 11.5.sp, color = subtle())
            }
        },
        confirmButton = {
            TextButton(enabled = cmd.isNotBlank(), onClick = { onRun(cmd.trim()); onDismiss() }) {
                Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(L.common.run)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** The message that asks the cloud agent to run [command] as is. */
fun cloudRunCommandPrompt(command: String): String =
    "Run this command in the terminal exactly as written (in the project's folder) and show me its full output; " +
        "if it starts a server, keep it running in the background and tell me its port.\n\n```bash\n$command\n```"
