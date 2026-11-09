package com.example.gemini.ui.codex

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
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
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
import com.example.gemini.data.agent.codex.CodexChatBackend
import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.o
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.GeminiPurple
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.claude.ClaudeCard
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.CodexAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

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
    val bridge = com.example.gemini.data.remote.AndroidLocalBridgeServer
    fun local(name: String, path: String) = McpDraft(name = name, url = "http://127.0.0.1:${bridge.DEFAULT_PORT}$path", headers = lines(deviceHeaders))
    val presets = listOf(
        CodexMcpPreset("Custom Plugin MCP", "Pick an installed plugin", Icons.Outlined.Extension, ClaudeTerracotta, null),
        CodexMcpPreset("Plugin Developer", "Build & test AntiGem plugins", Icons.Outlined.DeveloperMode, GeminiPurple, local(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
        CodexMcpPreset("Browser", "Control the in-app browser", Icons.Outlined.Language, GeminiBlue, local("browser_mcp", "/browser")),
        CodexMcpPreset("Terminal", "Run commands in the terminal", Icons.Outlined.Terminal, QuotaGreen, local("terminal_mcp", "/terminal")),
        CodexMcpPreset("Android", "Tap, type & read this phone", Icons.Outlined.PhoneAndroid, com.example.gemini.theme.AppColors.accentTeal, local(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
        CodexMcpPreset("Notes & To-dos", "Read & edit your notes", Icons.Outlined.EditNote, com.example.gemini.theme.AppColors.warning, local(bridge.NOTES_MCP_NAME, "/notes")),
        CodexMcpPreset("Jarvis", "Voice control of this app", Icons.Outlined.RecordVoiceOver, com.example.gemini.theme.AppColors.accentTeal, local(bridge.JARVIS_MCP_NAME, "/jarvis")),
        CodexMcpPreset("Theme", "Create & switch app themes", Icons.Outlined.Palette, com.example.gemini.theme.AppColors.accentAlt, local(bridge.THEME_MCP_NAME, "/theme")),
        CodexMcpPreset("Stitch", "Google Stitch UI design · API key", Icons.Outlined.Palette, com.example.gemini.theme.AppColors.accentPink,
            McpDraft(name = "stitch", url = "https://stitch.googleapis.com/mcp", headers = "X-Goog-Api-Key: ")),
        // REA runs in the terminal's proot-distro Ubuntu: com.termux builds only; its tile picks the tool groups
        if (com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context))
            CodexMcpPreset("REA", "Reverse engineering · APK & native", Icons.Outlined.BugReport, com.example.gemini.theme.AppColors.accentPink, null)
        else null,
        CodexMcpPreset("Custom server", "Any remote (HTTP) or local (command) server", Icons.Outlined.Add, CodexAccent, McpDraft())
    ).filterNotNull()
    // REA servers: local servers running the rea-mcp launcher (name → env)
    val reaServers = servers.mapNotNull { srv ->
        val command = srv.config.str("command")
        if (!com.example.gemini.data.rea.ReaManager.isReaCommand(command)) return@mapNotNull null
        srv.name to stringsOf(srv.config, "env")
    }.toMap()
    if (showReaDialog) com.example.gemini.ui.settings.ReaServerDialog(
        agent = "Codex",
        existing = reaServers,
        takenNames = servers.map { it.name }.toSet(),
        canReplace = true,
        onDismiss = { showReaDialog = false },
        onSave = { name, env ->
            showReaDialog = false
            scope.launch {
                backend.addLocalMcp(name, listOf(com.example.gemini.data.rea.ReaManager.LAUNCHER), env)
                    .onSuccess { AppToastHelper.showToast("$name saved", ChatToastType.SUCCESS); reload() }
                    .onFailure { AppToastHelper.showToast("Could not save: ${it.message}", ChatToastType.ERROR) }
            }
        },
        onOpenRea = { showReaDialog = false; onOpenRea() },
        onOpenGroups = { showReaDialog = false; onOpenReaGroups() }
    )
    if (choosePlugin) com.example.gemini.ui.settings.WebViewExtensionMcpPicker(onDismiss = { choosePlugin = false }) { extension ->
        val m = com.example.gemini.ui.browser.BrowserExtensionManager.instance
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
                    Text("Tools Codex can call", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Servers added here go into Codex's own config, so every Codex chat (and the Codex CLI on the server) can use them. They connect when a chat starts.",
                        fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CodexAccent)
                else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Quick presets", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
                            com.example.gemini.ui.settings.McpPresetTile(
                                name = p.name, subtitle = p.subtitle, icon = p.icon, accent = p.accent,
                                added = added, addedLabel = "Added",
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
            Text("Servers (${servers.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            error?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            if (servers.isEmpty() && error == null) {
                Text("None yet. Add one from the presets above.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
            }
            servers.forEachIndexed { i, srv ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                val (label, color) = when (srv.status) {
                    "connected" -> (if (srv.toolCount > 0) "Connected · ${srv.toolCount} tools" else "Connected") to CodexAccent
                    "failed" -> "Failed" to MaterialTheme.colorScheme.error
                    "disabled" -> "Disabled" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    "authenticationRequired" -> "Needs sign-in" to com.example.gemini.theme.AppColors.warning
                    "starting", "notStarted" -> "Connecting…" to com.example.gemini.theme.AppColors.warning
                    null -> "Connects with the next chat" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                    }) { Text("Edit") }
                    IconButton(onClick = {
                        scope.launch {
                            backend.removeMcp(srv.name)
                                .onSuccess { AppToastHelper.showToast("${srv.name} removed", ChatToastType.SUCCESS) }
                                .onFailure { AppToastHelper.showToast("Could not remove: ${it.message}", ChatToastType.ERROR) }
                            reload()
                        }
                    }) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
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
                }.onFailure { AppToastHelper.showToast("Could not save: ${it.message}", ChatToastType.ERROR) }
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
        title = { Text(if (initial.isNew) "Add an MCP server" else "Edit ${initial.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = initial.isNew,
                    isError = d.name.isNotBlank() && !nameOk, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = d.remote, onClick = { d = d.copy(remote = true) }, label = { Text("Remote (HTTP)") })
                    FilterChip(selected = !d.remote, onClick = { d = d.copy(remote = false) }, label = { Text("Local (command)") })
                }
                Spacer(Modifier.height(4.dp))
                if (d.remote) {
                    OutlinedTextField(d.url, { d = d.copy(url = it) }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://example.com/mcp") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(d.headers, { d = d.copy(headers = it) }, label = { Text("Headers (one per line, Name: value)") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(d.command, { d = d.copy(command = it) }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem ~/projects") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(d.env, { d = d.copy(env = it) }, label = { Text("Environment (one per line, KEY=value)") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text("It runs on the server, in Codex's environment.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
        },
        confirmButton = {
            Button(onClick = { saving = true; onSave(d) { saving = false } }, enabled = ok && !saving) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
