package ai.antigem.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.skills.AgentSkillDto
import ai.antigem.data.skills.AgentSkillsClient
import ai.antigem.data.skills.RepoSkillsInspectResponse
import ai.antigem.domain.model.AgentKind
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaRed
import ai.antigem.ui.claude.ClaudeCard
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private fun toast(msg: String, ok: Boolean = true) =
    AppToastHelper.showToast(msg, if (ok) ChatToastType.SUCCESS else ChatToastType.ERROR)

@Composable
private fun PanelTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun PanelHint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = modifier)
}

/** What the plugin formats a repository holds mean for [agent], when it has no skills to add. */
private fun pluginHint(agent: AgentKind, kinds: List<String>): String? {
    if (kinds.isEmpty()) return null
    return when (agent) {
        AgentKind.CLAUDE -> if ("claude-marketplace" in kinds) L.settings.agentSkills.thisRepositoryIsAClaudeCode else null
        AgentKind.CODEX -> if ("codex-plugin" in kinds || "claude-marketplace" in kinds) L.settings.agentSkills.thisRepositoryIsAPluginMarketplace else null
        AgentKind.AGY -> if ("agy-plugin" in kinds || "claude-plugin" in kinds || "claude-marketplace" in kinds) L.settings.agentSkills.thisRepositoryIsAPluginInstall else null
        AgentKind.OPENCODE -> null
    }
}

/**
 * The skills of one agent's own skills folder: add skills from a GitHub repository (pick which ones), update the
 * ones that came from GitHub, remove any. The same for every agent; only the folder differs (the bridge knows it).
 * Not scrollable itself: put it in a scrolling column.
 */
@Composable
fun AgentSkillsPanel(agent: AgentKind, accent: Color, onChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<AgentSkillDto>?>(null) }
    var dir by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val busy = remember { mutableStateMapOf<String, String>() } // skill id → update | remove
    var confirmRemove by remember { mutableStateOf<AgentSkillDto?>(null) }

    var source by remember { mutableStateOf("") }
    var inspecting by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<RepoSkillsInspectResponse?>(null) }
    var findError by remember { mutableStateOf<String?>(null) }
    // a repository AntiGem can't add by itself: (source, why) for the "Set up with AI" dialog
    var aiSetup by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val picked = remember { mutableStateMapOf<String, Boolean>() } // repository path → add it
    var installing by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        AgentSkillsClient.list(agent)
            .onSuccess { skills = it.skills; dir = it.dir; loadError = null }
            .onFailure { loadError = it.message ?: L.settings.agentSkills.couldNotListTheSkills }
        loading = false
    }
    LaunchedEffect(agent) { reload() }

    fun find() {
        val text = source.trim()
        if (text.isBlank() || inspecting) return
        inspecting = true
        found = null
        findError = null
        scope.launch {
            AgentSkillsClient.inspect(agent, text)
                .onSuccess { res ->
                    found = res
                    picked.clear()
                    res.skills.forEach { picked[it.path] = true }
                    // no skills, and no plugin this agent's Plugins page can take: an AI agent can set it up
                    if (res.skills.isEmpty() && pluginHint(agent, res.pluginKinds) == null) aiSetup = text to L.settings.agentSkills.itHasNoSkillsNoFolder
                }
                .onFailure {
                    val why = it.message ?: L.settings.agentSkills.couldNotReadTheRepository
                    findError = why
                    // a typo in the link is fixed by hand; anything else (private repository, GitHub limits…) an agent may manage
                    if (!why.startsWith("Use owner/repo") && !why.startsWith("Invalid")) aiSetup = text to why
                }
            inspecting = false
        }
    }

    // ---- add from GitHub
    ClaudeCard {
        PanelTitle(L.settings.agentSkills.addSkillsFromGithub)
        PanelHint(L.settings.agentSkills.aGithubRepositoryOwnerRepoOr)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                source, { source = it; findError = null }, singleLine = true,
                placeholder = { Text(L.settings.agentSkills.ownerRepoOrGithubComLink) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find() }),
                modifier = Modifier.weight(1f), enabled = !installing
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { find() }, enabled = source.isNotBlank() && !inspecting && !installing,
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                if (inspecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text(L.settings.agentSkills.find)
            }
        }
        if (inspecting) PanelHint(L.settings.agentSkills.lookingForSkillsInTheRepository, Modifier.padding(top = 6.dp))
        findError?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            SetupWithAiLink(accent) { aiSetup = source.trim() to it }
        }

        found?.let { res ->
            Spacer(Modifier.height(10.dp))
            if (res.skills.isEmpty()) {
                Text(L.settings.agentSkills.noSkillsFoundInRepoValue(res.repo, (if (res.subpath.isNotBlank()) "/${res.subpath}" else "")),
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                pluginHint(agent, res.pluginKinds)?.let { PanelHint(it, Modifier.padding(top = 4.dp)) }
                SetupWithAiLink(accent) { aiSetup = source.trim() to L.settings.agentSkills.itHasNoSkillsNoFolder }
            } else {
                val count = picked.values.count { it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.settings.agentSkills.skillscountSkillValueInRepo(res.skills.size, if (res.skills.size == 1) "" else "s", res.repo), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (res.skills.size > 1) TextButton(onClick = {
                        val all = count < res.skills.size
                        res.skills.forEach { picked[it.path] = all }
                    }, enabled = !installing) { Text(if (count < res.skills.size) L.common.selectAll else L.settings.agentSkills.selectNone, fontSize = 12.sp) }
                }
                res.skills.forEach { s ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = picked[s.path] == true, onCheckedChange = { picked[s.path] = it }, enabled = !installing,
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (s.description.isNotBlank()) Text(s.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            Text(s.path.ifBlank { L.settings.agentSkills.theWholeRepository } + if (s.id != s.path.substringAfterLast('/')) "  →  ${s.id}" else "",
                                fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                            when {
                                s.sameSource -> Text(L.settings.agentSkills.alreadyAddedAddingItAgainUpdates, fontSize = 11.sp, color = accent)
                                s.exists -> Text(L.settings.agentSkills.aDifferentSkillNamedIdIs(s.id), fontSize = 11.sp, color = QuotaAmber)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val paths = res.skills.filter { picked[it.path] == true }.map { it.path }
                            installing = true
                            scope.launch {
                                AgentSkillsClient.install(agent, source.trim(), paths, replace = true)
                                    .onSuccess { r ->
                                        val updated = r.replaced.size
                                        toast(buildString {
                                            append(L.settings.agentSkills.addedInstalledcountSkillValue(r.installed.size, if (r.installed.size == 1) "" else "s"))
                                            if (updated > 0) append(" ($updated replaced)")
                                            append(". Start a new chat to use them.")
                                        })
                                        found = null
                                        source = ""
                                        reload()
                                        onChanged()
                                    }
                                    .onFailure { e ->
                                    toast(e.message ?: L.settings.agentSkills.couldNotAddTheSkills, ok = false)
                                    aiSetup = source.trim() to (e.message ?: L.settings.agentSkills.addingTheSkillsFailed)
                                }
                                installing = false
                            }
                        },
                        enabled = count > 0 && !installing, colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        if (installing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(L.settings.agentSkills.adding)
                        } else Text(if (count == 1) L.settings.agentSkills.add1Skill else L.settings.agentSkills.addCountSkills(count))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { found = null }, enabled = !installing) { Text(L.common.cancel) }
                }
            }
        }
    }

    // ---- the skills folder
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PanelTitle(L.settings.agentSkills.yourSkillsValue((skills?.let { " (${it.size})" } ?: "")))
                if (dir.isNotBlank()) Text(dir, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { scope.launch { reload() } }, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                else Icon(Icons.Outlined.Refresh, L.common.refresh)
            }
        }
        loadError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        val list = skills
        when {
            list == null && loadError == null -> PanelHint(L.common.loading, Modifier.padding(top = 6.dp))
            list != null && list.isEmpty() -> PanelHint(L.settings.agentSkills.noSkillsHereYetAddSome, Modifier.padding(top = 6.dp))
        }
        list.orEmpty().forEach { s ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            val op = busy[s.id]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.kind == "plugin") {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.15f)) {
                                Text(L.settings.agentSkills.plugin, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = accent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                    if (s.description.isNotBlank()) Text(s.description, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    Text(
                        s.source?.let { src -> "github.com/${src.repo}" + (if (src.path.isNotBlank()) " · ${src.path}" else "") + (if (src.ref.isNotBlank()) " @${src.ref}" else "") }
                            ?: L.settings.agentSkills.addedByHand,
                        fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (op != null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    Spacer(Modifier.width(6.dp))
                    Text(if (op == "update") L.common.updating else L.common.removing, fontSize = 11.5.sp, color = accent)
                } else {
                    if (s.source != null) IconButton(onClick = {
                        busy[s.id] = "update"
                        scope.launch {
                            AgentSkillsClient.update(agent, s.id)
                                .onSuccess { toast(L.settings.agentSkills.nameUpdatedStartANewChat(s.name)); reload(); onChanged() }
                                .onFailure { toast(it.message ?: L.settings.agentSkills.couldNotUpdateName(s.name), ok = false) }
                            busy.remove(s.id)
                        }
                    }) { Icon(Icons.Outlined.Refresh, L.settings.agentSkills.updateFromGithub, tint = accent) }
                    IconButton(onClick = { confirmRemove = s }) { Icon(Icons.Outlined.Delete, L.common.remove, tint = QuotaRed.copy(alpha = 0.85f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PanelHint(L.settings.agentSkills.theAgentReadsItsSkillsWhen)
    }

    aiSetup?.let { (src, why) -> SetupWithAiDialog(src, agent, why) { aiSetup = null } }

    confirmRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(L.settings.agentSkills.removeName(s.name)) },
            text = { Text(if (s.source == null) L.settings.agentSkills.thisDeletesTheFolderPathIt(s.path) else L.settings.agentSkills.thisDeletesTheFolderPath(s.path)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    busy[s.id] = "remove"
                    scope.launch {
                        AgentSkillsClient.remove(agent, s.id)
                            .onSuccess { toast("${s.name} removed"); reload(); onChanged() }
                            .onFailure { toast(it.message ?: L.settings.agentSkills.couldNotRemoveName(s.name), ok = false) }
                        busy.remove(s.id)
                    }
                }) { Text(L.common.remove, color = QuotaRed) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(L.common.cancel) } }
        )
    }
}

/** [AgentSkillsPanel] in a full-height dialog, for screens whose own list can't hold it (Antigravity's Skills tab). */
@Composable
fun AgentSkillsDialog(agent: AgentKind, accent: Color, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f)
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(L.common.done) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AgentSkillsPanel(agent, accent)
                }
            }
        }
    }
}

/**
 * Install an Antigravity plugin from GitHub with agy's own installer (`agy plugin install`): the bridge downloads
 * the repository, agy reads its Antigravity or Claude Code plugin manifest (or Claude Code marketplace) and copies it
 * into ~/.gemini/config/plugins.
 */
@Composable
fun AgyGitHubPluginDialog(accent: Color, onInstalled: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var aiSetup by remember { mutableStateOf<Pair<String, String?>?>(null) }
    fun install() {
        if (source.isBlank() || installing) return
        installing = true
        output = null
        scope.launch {
            AgentSkillsClient.installAgyPlugin(source.trim())
                .onSuccess {
                    failed = false
                    output = it.ifBlank { L.settings.agentSkills.installed }
                    toast(L.settings.agentSkills.pluginInstalledStartANewChat)
                    onInstalled()
                }
                .onFailure {
                    failed = true
                    output = it.message ?: L.settings.agentSkills.agyCouldNotInstallIt
                    val why = if (output!!.contains("could not detect plugin structure")) L.settings.agentSkills.agyFoundNoPluginInIt else output!!.lines().first()
                    if (!why.startsWith("Use owner/repo") && !why.startsWith("Invalid")) aiSetup = source.trim() to why
                }
            installing = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        title = { Text(L.settings.agentSkills.installAPluginFromGithub) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PanelHint("An Antigravity or Claude Code plugin (or a Claude Code marketplace, which installs all its plugins): owner/repo or a github.com link. agy's own installer adds it, the same as `agy plugin install`.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    source, { source = it }, singleLine = true, enabled = !installing,
                    placeholder = { Text(L.settings.agentSkills.ownerRepoOrGithubComLink) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { install() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (installing) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    Spacer(Modifier.width(8.dp))
                    PanelHint(L.settings.agentSkills.downloadingAndInstalling)
                }
                if (failed) SetupWithAiLink(accent) { aiSetup = source.trim() to output?.lines()?.firstOrNull() }
                output?.let {
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = (if (failed) MaterialTheme.colorScheme.error else accent).copy(alpha = 0.08f)) {
                        Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(8.dp).fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (output != null && !failed) TextButton(onClick = onDismiss) { Text(L.common.done) }
            else Button(onClick = { install() }, enabled = source.isNotBlank() && !installing, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(L.common.install) }
        },
        dismissButton = { if (output == null || failed) TextButton(onClick = onDismiss, enabled = !installing) { Text(L.common.cancel) } }
    )
    aiSetup?.let { (src, why) -> SetupWithAiDialog(src, AgentKind.AGY, why) { aiSetup = null } }
}

/** A slim row at the top of a list: what it adds, and a button that opens the GitHub dialog. */
@Composable
fun GitHubActionRow(title: String, subtitle: String, button: String, accent: Color, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) { Text(button, fontSize = 12.sp, color = accent) }
        }
    }
}
