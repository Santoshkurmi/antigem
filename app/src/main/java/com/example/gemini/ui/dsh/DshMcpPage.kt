package com.example.gemini.ui.dsh

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
import androidx.compose.material.icons.outlined.Apps
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.gemini.data.agent.dsh.DshChatBackend
import com.example.gemini.data.agent.dsh.DshJson
import com.example.gemini.data.agent.dsh.DshMcpServer
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.GeminiPurple
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.claude.ClaudeCard
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.DshAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A server being added or edited: remote (URL + headers) or local (a command dsh starts). */
private data class McpDraft(
    val name: String = "",
    val remote: Boolean = true,
    val url: String = "",
    val headers: String = "",
    val command: String = "",
    val env: String = "",
    val isNew: Boolean = true
)

private class DshMcpPreset(val name: String, val subtitle: String, val icon: ImageVector, val accent: Color, val draft: McpDraft?)

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

/**
 * Settings → DeepSeek Harness → MCP servers: the app's presets (its own MCP servers: browser, terminal, Android,
 * notes, Jarvis, REA…), every server mounted into dsh's sessions, and adding a remote or local server. The bridge
 * keeps them in its dsh config and writes them into dsh's profile; a change restarts a running dsh.
 */
@Composable
fun DshMcpPage(backend: DshChatBackend, onOpenRea: () -> Unit = {}, onOpenReaGroups: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by backend.config.collectAsState()
    val servers = config?.mcp.orEmpty()
    var loading by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<McpDraft?>(null) }
    var choosePlugin by remember { mutableStateOf(false) }
    var showReaDialog by remember { mutableStateOf(false) }
    val onSaved = rememberRestartFlow(backend)

    suspend fun reload() {
        loading = true
        backend.refreshConfig()
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    /** Saves the whole list (the bridge replaces it); [done] runs after either outcome with success. */
    fun saveList(list: List<DshMcpServer>, message: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val body = buildJsonObject { put("mcp", DshJson.encodeToJsonElement(ListSerializer(DshMcpServer.serializer()), list)) }
            backend.saveConfig(body)
                .onSuccess { AppToastHelper.showToast(message, ChatToastType.SUCCESS); onSaved(it); done(true) }
                .onFailure { AppToastHelper.showToast("Could not save: ${it.message}", ChatToastType.ERROR); done(false) }
        }
    }
    fun upsert(server: DshMcpServer, message: String, done: (Boolean) -> Unit = {}) =
        saveList(servers.filter { it.name != server.name } + server, message, done)

    val deviceId = remember(context) {
        runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
    }
    val deviceHeaders = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
    val bridge = com.example.gemini.data.remote.AndroidLocalBridgeServer
    fun local(name: String, path: String) = McpDraft(name = name, url = "http://127.0.0.1:${bridge.DEFAULT_PORT}$path", headers = lines(deviceHeaders))
    val presets = listOf(
        DshMcpPreset("Custom Plugin MCP", "Pick an installed plugin", Icons.Outlined.Extension, ClaudeTerracotta, null),
        DshMcpPreset("Plugin Developer", "Build & test AntiGem plugins", Icons.Outlined.DeveloperMode, GeminiPurple, local(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
        DshMcpPreset("Mini App Developer", "Build, test & sign mini apps", Icons.Outlined.Apps, GeminiBlue, local(bridge.MINI_APP_DEVELOPER_MCP_NAME, "/mini_app_developer")),
        DshMcpPreset("Browser", "Control the in-app browser", Icons.Outlined.Language, GeminiBlue, local("browser_mcp", "/browser")),
        DshMcpPreset("Terminal", "Run commands in the terminal", Icons.Outlined.Terminal, QuotaGreen, local("terminal_mcp", "/terminal")),
        DshMcpPreset("Android", "Tap, type & read this phone", Icons.Outlined.PhoneAndroid, com.example.gemini.theme.AppColors.accentTeal, local(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
        DshMcpPreset("Notes & To-dos", "Read & edit your notes", Icons.Outlined.EditNote, com.example.gemini.theme.AppColors.warning, local(bridge.NOTES_MCP_NAME, "/notes")),
        DshMcpPreset("Jarvis", "Voice control of this app", Icons.Outlined.RecordVoiceOver, com.example.gemini.theme.AppColors.accentTeal, local(bridge.JARVIS_MCP_NAME, "/jarvis")),
        DshMcpPreset("Theme", "Create & switch app themes", Icons.Outlined.Palette, com.example.gemini.theme.AppColors.accentAlt, local(bridge.THEME_MCP_NAME, "/theme")),
        DshMcpPreset("Stitch", "Google Stitch UI design · API key", Icons.Outlined.Palette, com.example.gemini.theme.AppColors.accentPink,
            McpDraft(name = "stitch", url = "https://stitch.googleapis.com/mcp", headers = "X-Goog-Api-Key: ")),
        // REA runs in the terminal's proot-distro Ubuntu: com.termux builds only; its tile picks the tool groups
        if (com.example.gemini.data.local.LocalEnvironmentManager.isTermuxPackage(context))
            DshMcpPreset("REA", "Reverse engineering · APK & native", Icons.Outlined.BugReport, com.example.gemini.theme.AppColors.accentPink, null)
        else null,
        DshMcpPreset("Custom server", "Any remote (HTTP) or local (command) server", Icons.Outlined.Add, DshAccent, McpDraft())
    ).filterNotNull()
    // REA servers: local servers running the rea-mcp launcher (name → env)
    val reaServers = servers.filter { com.example.gemini.data.rea.ReaManager.isReaCommand(it.command) }.associate { it.name to it.env.orEmpty() }
    if (showReaDialog) com.example.gemini.ui.settings.ReaServerDialog(
        agent = "DeepSeek Harness",
        existing = reaServers,
        takenNames = servers.map { it.name }.toSet(),
        canReplace = true,
        onDismiss = { showReaDialog = false },
        onSave = { name, env ->
            showReaDialog = false
            upsert(DshMcpServer(name = name, transport = "stdio", command = com.example.gemini.data.rea.ReaManager.LAUNCHER, env = env.ifEmpty { null }), "$name saved")
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
                    Text("Tools DeepSeek Harness can call", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Servers added here are mounted into every DeepSeek Harness chat. A change restarts DeepSeek Harness when it is running.",
                        fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DshAccent)
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
            if (servers.isEmpty()) {
                Text("None yet. Add one from the presets above.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
            }
            servers.forEachIndexed { i, srv ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Hub, null, tint = if (srv.disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else DshAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(srv.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val where = srv.url ?: (listOfNotNull(srv.command) + srv.args.orEmpty()).joinToString(" ").ifBlank { null }
                        where?.let { Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Text(
                            if (srv.disabled) "Off" else if (srv.transport == "stdio") "Local command" else "Remote (HTTP)",
                            fontSize = 11.sp, color = if (srv.disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else DshAccent
                        )
                    }
                    Switch(checked = !srv.disabled, onCheckedChange = { on ->
                        saveList(servers.map { if (it.name == srv.name) it.copy(disabled = !on) else it }, "${srv.name} ${if (on) "on" else "off"}")
                    })
                    TextButton(onClick = {
                        draft = if (srv.transport == "stdio") McpDraft(
                            name = srv.name, remote = false,
                            command = (listOfNotNull(srv.command) + srv.args.orEmpty()).joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it },
                            env = srv.env.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" },
                            isNew = false
                        ) else McpDraft(name = srv.name, url = srv.url.orEmpty(), headers = lines(srv.headers.orEmpty()), isNew = false)
                    }) { Text("Edit") }
                    IconButton(onClick = { saveList(servers.filter { it.name != srv.name }, "${srv.name} removed") }) {
                        Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    draft?.let { d ->
        McpServerDialog(d, onDismiss = { draft = null }) { saved, done ->
            val name = saved.name.trim()
            val old = servers.firstOrNull { it.name == name }
            val server = if (saved.remote) {
                DshMcpServer(name = name, transport = "streamable-http", url = saved.url.trim(),
                    headers = parsePairs(saved.headers, ':').ifEmpty { null }, disabled = old?.disabled ?: false)
            } else {
                val words = splitCommand(saved.command)
                DshMcpServer(name = name, transport = "stdio", command = words.first(), args = words.drop(1).ifEmpty { null },
                    env = parsePairs(saved.env, '=').ifEmpty { null }, disabled = old?.disabled ?: false)
            }
            upsert(server, "$name saved") { ok ->
                if (ok) draft = null
                done()
            }
        }
    }
}

@Composable
private fun McpServerDialog(initial: McpDraft, onDismiss: () -> Unit, onSave: (McpDraft, () -> Unit) -> Unit) {
    var d by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    // the bridge's rule for dsh server names
    val nameOk = Regex("^[A-Za-z0-9_-]{1,32}$").matches(d.name.trim())
    val ok = nameOk && if (d.remote) d.url.trim().let { it.startsWith("http://") || it.startsWith("https://") } else splitCommand(d.command).isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isNew) "Add an MCP server" else "Edit ${initial.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = initial.isNew,
                    isError = d.name.isNotBlank() && !nameOk, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (d.name.isNotBlank() && !nameOk) ({ Text("Letters, digits, - and _ (up to 32)") }) else null
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
                    Text("It runs on the server, in DeepSeek Harness' environment.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
        },
        confirmButton = {
            Button(onClick = { saving = true; onSave(d) { saving = false } }, enabled = ok && !saving) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
