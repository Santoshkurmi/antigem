package ai.antigem.ui.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.codex.CodexChatBackend
import ai.antigem.data.agent.opencode.a
import ai.antigem.data.agent.opencode.o
import ai.antigem.data.agent.opencode.str
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.GeminiBlue
import ai.antigem.theme.GeminiPurple
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.claude.ClaudeCard
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.CodexAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import ai.antigem.i18n.L

/** A server being added or edited: remote (URL + headers) or local (a command Codex starts). */
private data class McpDraft(
    val name: String = "",
    val remote: Boolean = true,
    val url: String = "",
    val headers: String = "",
    val command: String = "",
    val env: String = "",
    val isNew: Boolean = true
)

private class CodexMcpPreset(val name: String, val subtitle: String, val icon: ImageVector, val accent: Color, val draft: McpDraft?)

private fun lines(map: Map<String, String>) = map.entries.joinToString("\n") { "${it.key}: ${it.value}" }

/** "Key: value" / "KEY=value" lines → map (blank lines skipped). */
private fun parsePairs(text: String, sep: Char): Map<String, String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(sep) }
        .associate { it.substringBefore(sep).trim() to it.substringAfter(sep).trim() }

/** A command line → its words (quotes keep spaces together). */
private fun splitCommand(text: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    for (c in text.trim()) {
        when {
            quote != null && c == quote -> quote = null
            quote == null && (c == '"' || c == '\'') -> quote = c
            quote == null && c.isWhitespace() -> if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
}

private fun stringsOf(o: kotlinx.serialization.json.JsonObject?, key: String): Map<String, String> =
    o.o(key)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()

/**
 * Settings → Codex → MCP servers: the app's presets (its own MCP servers: browser, terminal, Android, notes, Jarvis,
 * REA…), every server in Codex's config with its state, and adding a remote or local server. Servers are kept in
 * Codex's own config (~/.codex/config.toml on the server), so the Codex CLI and the VSCode extension see them too.
 */
@Composable
fun CodexMcpPage(backend: CodexChatBackend, onOpenRea: () -> Unit = {}, onOpenReaGroups: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var servers by remember { mutableStateOf<List<CodexChatBackend.McpServer>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<McpDraft?>(null) }
    var choosePlugin by remember { mutableStateOf(false) }
    var showReaDialog by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        backend.mcpList().onSuccess { servers = it; error = null }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    val deviceId = remember(context) {
        runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
    }
    val deviceHeaders = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
    val bridge = ai.antigem.data.remote.AndroidLocalBridgeServer
    fun local(name: String, path: String) = McpDraft(name = name, url = "http://127.0.0.1:${bridge.DEFAULT_PORT}$path", headers = lines(deviceHeaders))
    val presets = listOf(
        CodexMcpPreset(L.common.customPluginMcp, L.common.pickAnInstalledPlugin, Icons.Outlined.Extension, ClaudeTerracotta, null),
        CodexMcpPreset(L.common.pluginDeveloper, L.codex.mcp.buildTestAntigemPlugins, Icons.Outlined.DeveloperMode, GeminiPurple, local(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
        CodexMcpPreset(L.common.miniAppDeveloper, L.codex.mcp.buildTestSignMiniApps, Icons.Outlined.Apps, GeminiBlue, local(bridge.MINI_APP_DEVELOPER_MCP_NAME, "/mini_app_developer")),
        CodexMcpPreset(L.common.browser, L.common.controlTheInAppBrowser, Icons.Outlined.Language, GeminiBlue, local("browser_mcp", "/browser")),
        CodexMcpPreset(L.common.terminal, L.codex.mcp.runCommandsInTheTerminal, Icons.Outlined.Terminal, QuotaGreen, local("terminal_mcp", "/terminal")),
        CodexMcpPreset(L.codex.mcp.android, L.codex.mcp.tapTypeReadThisPhone, Icons.Outlined.PhoneAndroid, ai.antigem.theme.AppColors.accentTeal, local(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
        CodexMcpPreset(L.common.notesToDos, L.codex.mcp.readEditYourNotes, Icons.Outlined.EditNote, ai.antigem.theme.AppColors.warning, local(bridge.NOTES_MCP_NAME, "/notes")),
        CodexMcpPreset(L.common.jarvis, L.codex.mcp.voiceControlOfThisApp, Icons.Outlined.RecordVoiceOver, ai.antigem.theme.AppColors.accentTeal, local(bridge.JARVIS_MCP_NAME, "/jarvis")),
        CodexMcpPreset(L.common.theme, L.codex.mcp.createSwitchAppThemes, Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentAlt, local(bridge.THEME_MCP_NAME, "/theme")),
        CodexMcpPreset(L.common.language, L.codex.mcp.translateTheAppAddLanguages, Icons.Outlined.Translate, ai.antigem.theme.AppColors.accentTeal, local(bridge.LANGUAGE_MCP_NAME, "/language")),
        CodexMcpPreset(L.common.stitch, L.codex.mcp.googleStitchUiDesignApiKey, Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentPink,
            McpDraft(name = "stitch", url = "https://stitch.googleapis.com/mcp", headers = "X-Goog-Api-Key: ")),
        // REA runs in the terminal's proot-distro Ubuntu; its tile picks the tool groups
        CodexMcpPreset("REA", L.codex.mcp.reverseEngineeringApkNative, Icons.Outlined.BugReport, ai.antigem.theme.AppColors.accentPink, null),
        CodexMcpPreset(L.codex.mcp.customServer, L.codex.mcp.anyRemoteHttpOrLocalCommand, Icons.Outlined.Add, CodexAccent, McpDraft())
    ).filterNotNull()
    // REA servers: local servers running the rea-mcp launcher (name → env)
    val reaServers = servers.mapNotNull { srv ->
        val command = srv.config.str("command")
        if (!ai.antigem.data.rea.ReaManager.isReaCommand(command)) return@mapNotNull null
        srv.name to stringsOf(srv.config, "env")
    }.toMap()
    if (showReaDialog) ai.antigem.ui.settings.ReaServerDialog(
        agent = "Codex",
        existing = reaServers,
        takenNames = servers.map { it.name }.toSet(),
        canReplace = true,
        onDismiss = { showReaDialog = false },
        onSave = { name, env ->
            showReaDialog = false
            scope.launch {
                backend.addLocalMcp(name, listOf(ai.antigem.data.rea.ReaManager.LAUNCHER), env)
                    .onSuccess { AppToastHelper.showToast("$name saved", ChatToastType.SUCCESS); reload() }
                    .onFailure { AppToastHelper.showToast(L.codex.mcp.couldNotSaveMessage(it.message), ChatToastType.ERROR) }
            }
        },
        onOpenRea = { showReaDialog = false; onOpenRea() },
        onOpenGroups = { showReaDialog = false; onOpenReaGroups() }
    )
    if (choosePlugin) ai.antigem.ui.settings.WebViewExtensionMcpPicker(onDismiss = { choosePlugin = false }) { extension ->
        val m = ai.antigem.ui.browser.BrowserExtensionManager.instance
        draft = McpDraft(name = m.mcpServerName(extension.id), url = m.mcpUrl(extension.id), headers = lines(deviceHeaders))
        choosePlugin = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.codex.mcp.toolsCodexCanCall, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        L.codex.mcp.serversAddedHereGoIntoCodexs,
                        fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CodexAccent)
                else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
            }
            Spacer(Modifier.height(10.dp))
            Text(L.common.quickPresets, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            val d = p.draft
                            val name = d?.name.orEmpty()
                            val isRea = p.name == "REA" && d == null
                            val added = if (isRea) reaServers.isNotEmpty()
                                else name.isNotBlank() && servers.any { it.name == name || (bridge.isDeveloperMcpName(name) && bridge.isDeveloperMcpName(it.name)) }
                            ai.antigem.ui.settings.McpPresetTile(
                                name = p.name, subtitle = p.subtitle, icon = p.icon, accent = p.accent,
                                added = added, addedLabel = L.common.added,
                                // REA can be added more than once (different tool groups)
                                enabled = isRea || !added,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) {
                                when {
                                    isRea -> showReaDialog = true
                                    d == null -> choosePlugin = true
                                    else -> draft = d
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        ClaudeCard {
            Text(L.codex.mcp.serversCount(servers.size), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            error?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            if (servers.isEmpty() && error == null) {
                Text(L.codex.mcp.noneYetAddOneFromThe, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
            }
            servers.forEachIndexed { i, srv ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                val (label, color) = when (srv.status) {
                    "connected" -> (if (srv.toolCount > 0) L.codex.mcp.connectedToolcountTools(srv.toolCount) else L.codex.mcp.connected) to CodexAccent
                    "failed" -> L.common.failed to MaterialTheme.colorScheme.error
                    "disabled" -> L.common.disabled to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    "authenticationRequired" -> L.common.needsSignIn to ai.antigem.theme.AppColors.warning
                    "starting", "notStarted" -> L.common.connecting to ai.antigem.theme.AppColors.warning
                    null -> L.codex.mcp.connectsWithTheNextChat to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    else -> srv.status to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
                val c = srv.config
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Hub, null, tint = CodexAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(srv.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val where = c.str("url") ?: listOfNotNull(c.str("command"))
                            .plus(c.a("args").orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() }).joinToString(" ").ifBlank { null }
                        where?.let { Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(label, fontSize = 11.sp, color = color)
                        srv.error?.let { Text(it, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    TextButton(onClick = {
                        draft = if (c.str("url") == null) McpDraft(
                            name = srv.name, remote = false,
                            command = (listOfNotNull(c.str("command")) + c.a("args").orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() })
                                .joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it },
                            env = stringsOf(c, "env").entries.joinToString("\n") { "${it.key}=${it.value}" },
                            isNew = false
                        ) else McpDraft(
                            name = srv.name, url = c.str("url").orEmpty(),
                            headers = stringsOf(c, "http_headers").entries.joinToString("\n") { "${it.key}: ${it.value}" },
                            isNew = false
                        )
                    }) { Text(L.common.edit) }
                    IconButton(onClick = {
                        scope.launch {
                            backend.removeMcp(srv.name)
                                .onSuccess { AppToastHelper.showToast("${srv.name} removed", ChatToastType.SUCCESS) }
                                .onFailure { AppToastHelper.showToast(L.codex.mcp.couldNotRemoveMessage(it.message), ChatToastType.ERROR) }
                            reload()
                        }
                    }) { Icon(Icons.Outlined.Delete, L.common.remove, tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    draft?.let { d ->
        McpServerDialog(d, onDismiss = { draft = null }) { saved, done ->
            scope.launch {
                val r = if (saved.remote) backend.addRemoteMcp(saved.name.trim(), saved.url.trim(), parsePairs(saved.headers, ':'))
                else backend.addLocalMcp(saved.name.trim(), splitCommand(saved.command), parsePairs(saved.env, '='))
                r.onSuccess {
                    AppToastHelper.showToast("${saved.name.trim()} saved", ChatToastType.SUCCESS)
                    draft = null
                    reload()
                }.onFailure { AppToastHelper.showToast(L.codex.mcp.couldNotSaveMessage(it.message), ChatToastType.ERROR) }
                done()
            }
        }
    }
}

@Composable
private fun McpServerDialog(initial: McpDraft, onDismiss: () -> Unit, onSave: (McpDraft, () -> Unit) -> Unit) {
    var d by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    val nameOk = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(d.name.trim())
    val ok = nameOk && if (d.remote) d.url.trim().let { it.startsWith("http://") || it.startsWith("https://") } else splitCommand(d.command).isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isNew) L.codex.mcp.addAnMcpServer else L.codex.mcp.editName(initial.name), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    d.name, { d = d.copy(name = it) }, label = { Text(L.common.name) }, singleLine = true, enabled = initial.isNew,
                    isError = d.name.isNotBlank() && !nameOk, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = d.remote, onClick = { d = d.copy(remote = true) }, label = { Text(L.codex.mcp.remoteHttp) })
                    FilterChip(selected = !d.remote, onClick = { d = d.copy(remote = false) }, label = { Text(L.codex.mcp.localCommand) })
                }
                Spacer(Modifier.height(4.dp))
                if (d.remote) {
                    OutlinedTextField(d.url, { d = d.copy(url = it) }, label = { Text(L.common.url) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://example.com/mcp") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(d.headers, { d = d.copy(headers = it) }, label = { Text(L.codex.mcp.headersOnePerLineNameValue) },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(d.command, { d = d.copy(command = it) }, label = { Text(L.common.command) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem ~/projects") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(d.env, { d = d.copy(env = it) }, label = { Text("Environment (one per line, KEY=value)") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text(L.codex.mcp.itRunsOnTheServerIn, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
        },
        confirmButton = {
            Button(onClick = { saving = true; onSave(d) { saving = false } }, enabled = ok && !saving) { Text(if (saving) L.common.saving else L.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}
