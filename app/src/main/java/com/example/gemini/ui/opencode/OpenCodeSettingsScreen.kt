package com.example.gemini.ui.opencode

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.data.agent.opencode.OpenCodeCustomModel
import com.example.gemini.data.agent.opencode.OpenCodeCustomProvider
import com.example.gemini.data.agent.opencode.OpenCodePreferences
import com.example.gemini.data.agent.opencode.OpenCodeState
import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.ui.claude.ClaudeCard
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.OpenCodeAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A built-in OpenCode provider (integration) and the saved connections the user made for it. */
private data class Integration(val id: String, val name: String, val keyLogin: Boolean, val envNames: List<String>, val connections: List<Pair<String, String>>)

private fun toast(msg: String, ok: Boolean = true) =
    AppToastHelper.showToast(msg, if (ok) ChatToastType.SUCCESS else ChatToastType.ERROR)

/** The pages of Settings → OpenCode (each opens from the OpenCode row list). */
enum class OpenCodeSettingsPage(val title: String, val subtitle: String) {
    SERVER("Server & install", "Install, update and restart OpenCode"),
    DEFAULTS("Defaults", "Approvals and the agent of new chats"),
    PROVIDERS("Providers", "Built-in providers you connect with an API key"),
    ENDPOINTS("Custom endpoints", "Any OpenAI- or Anthropic-compatible API"),
    MCP("MCP servers", "Tools OpenCode can call: presets and your own servers")
}

/** Settings → OpenCode: one row per page, each with its current state. */
@Composable
fun OpenCodeSettingsGroup(backend: OpenCodeChatBackend, cardBg: Color, onOpen: (OpenCodeSettingsPage) -> Unit) {
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val mode by backend.prefs.permissionMode.collectAsState()
    val connected by backend.connectedProviders.collectAsState()
    val models by backend.availableModels.collectAsState()
    var endpoints by remember { mutableStateOf<Int?>(null) }
    var mcp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        backend.refreshStatus()
        backend.refreshModels()
        backend.client.customProviders().onSuccess { endpoints = it.size }
        backend.client.mcpList().onSuccess { mcp = it.size }
    }
    fun row(page: OpenCodeSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, valueColor: Color? = null) =
        com.example.gemini.ui.settings.SettingsRowItem(icon, OpenCodeAccent, page.title, value, valueColor) { onOpen(page) }
    com.example.gemini.ui.settings.SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(row(OpenCodeSettingsPage.SERVER, Icons.Outlined.Dns,
                if (state == OpenCodeState.READY) status?.version ?: state.label else state.label,
                if (state == OpenCodeState.READY) OpenCodeAccent else MaterialTheme.colorScheme.error)),
            listOf(
                row(OpenCodeSettingsPage.DEFAULTS, Icons.Outlined.Tune, openCodeModeLabel(mode)),
                row(OpenCodeSettingsPage.PROVIDERS, Icons.Outlined.Cloud,
                    if (connected.isNotEmpty()) "${connected.size} connected" else if (models.isNotEmpty()) "Free models" else null),
                row(OpenCodeSettingsPage.ENDPOINTS, Icons.Outlined.Link, endpoints?.takeIf { it > 0 }?.let { "$it added" }),
                row(OpenCodeSettingsPage.MCP, Icons.Outlined.Hub, mcp?.takeIf { it > 0 }?.let { "$it servers" })
            )
        )
    )
}

/** One page of Settings → OpenCode. */
@Composable
fun OpenCodeSettingsScreen(backend: OpenCodeChatBackend, page: OpenCodeSettingsPage = OpenCodeSettingsPage.SERVER) {
    val scope = rememberCoroutineScope()
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val mode by backend.prefs.permissionMode.collectAsState()
    val defaultAgent by backend.prefs.agent.collectAsState()
    val agents by backend.agents.collectAsState()
    val models by backend.availableModels.collectAsState()
    var integrations by remember { mutableStateOf<List<Integration>>(emptyList()) }
    var integrationsError by remember { mutableStateOf<String?>(null) }
    var customs by remember { mutableStateOf<List<OpenCodeCustomProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OpenCodeCustomProvider?>(null) }

    suspend fun reload() {
        loading = true
        backend.refreshStatus()
        backend.client.get("/integration")
            .onSuccess { res ->
                integrationsError = null
                integrations = res.obj().a("data").orEmpty().mapNotNull { it.obj() }.map { o ->
                    val methods = o.a("methods").orEmpty().mapNotNull { it.obj() }
                    Integration(
                        id = o.str("id").orEmpty(),
                        name = o.str("name") ?: o.str("id").orEmpty(),
                        keyLogin = methods.any { it.str("type") == "key" },
                        envNames = methods.filter { it.str("type") == "env" }.flatMap { m -> m.a("names").orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } },
                        connections = o.a("connections").orEmpty().mapNotNull { it.obj() }.map { c ->
                            (c.str("id") ?: "") to (c.str("label") ?: c.str("method") ?: c.str("type") ?: "connected")
                        }
                    )
                }.sortedBy { it.name.lowercase() }
            }
            .onFailure { integrationsError = it.message }
        backend.client.customProviders().onSuccess { customs = it }
        loading = false
    }

    LaunchedEffect(Unit) {
        backend.refreshCliJob()
        reload()
    }
    val job by backend.cliJob.collectAsState()
    val jobRunning = job?.running == true
    // a finished install changes everything on this page
    LaunchedEffect(jobRunning) { if (!jobRunning && job != null) reload() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- server
        if (page == OpenCodeSettingsPage.SERVER) ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.gemini.ui.agents.AgentMark(com.example.gemini.domain.model.AgentKind.OPENCODE, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OpenCode", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        listOfNotNull(state.label, status?.version, if (status?.running == true) "server running" else null).joinToString(" · "),
                        fontSize = 12.sp,
                        color = if (state == OpenCodeState.READY) OpenCodeAccent else MaterialTheme.colorScheme.error
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
            status?.binary?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            if (state == OpenCodeState.NOT_INSTALLED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "OpenCode is not installed on the server (on this phone when you use the local server). " +
                        "Install it here: the app downloads the official build (~250 MB) into ~/.opencode/bin.",
                    fontSize = 12.5.sp, lineHeight = 17.sp
                )
            }
            if (state == OpenCodeState.NOT_INSTALLED || state == OpenCodeState.READY) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state == OpenCodeState.NOT_INSTALLED) {
                        Button(
                            onClick = { backend.startCliJob("install") },
                            enabled = !jobRunning,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent, contentColor = Color.White)
                        ) { Text(if (jobRunning) "Installing…" else "Install OpenCode") }
                    } else {
                        OutlinedButton(onClick = { backend.startCliJob("update") }, enabled = !jobRunning, shape = RoundedCornerShape(12.dp)) {
                            Text(if (jobRunning) "Checking…" else "Check for updates")
                        }
                    }
                    if (jobRunning) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OpenCodeAccent)
                    }
                }
                job?.let { j ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (if (j.kind == "update") "Update" else "Install") + ": " + when {
                            j.running -> "running…"
                            j.exit_code == 0 -> "done"
                            else -> "failed"
                        },
                        fontSize = 12.sp,
                        color = if (!j.running && j.exit_code != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        j.log.trim().lines().takeLast(14).joinToString("\n"),
                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(8.dp)
                    )
                }
                if (state == OpenCodeState.NOT_INSTALLED) {
                    Text(
                        "Or in a terminal on the server: curl -fsSL https://opencode.ai/v2/install | bash",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            status?.error?.takeIf { state != OpenCodeState.READY || status?.running == false }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (state == OpenCodeState.READY) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        restarting = true
                        backend.restartServer { err ->
                            restarting = false
                            if (err == null) toast("OpenCode restarted") else toast("Restart failed: $err", ok = false)
                            scope.launch { reload() }
                        }
                    },
                    enabled = !restarting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (restarting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (status?.running == true) "Restart OpenCode" else "Start OpenCode")
                }
                Text(
                    "Running replies stop when OpenCode restarts.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ---- defaults
        if (page == OpenCodeSettingsPage.DEFAULTS) {
        SectionTitle("Approvals")
        ClaudeCard {
            listOf(OpenCodePreferences.MODE_ASK, OpenCodePreferences.MODE_AUTO, OpenCodePreferences.MODE_DEFAULT).forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { backend.setPermissionMode(m) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == m, onClick = { backend.setPermissionMode(m) })
                    Column {
                        Text(openCodeModeLabel(m), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text(openCodeModeDescription(m), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
            Text(
                "Applies to new chats and to the chat on screen.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp)
            )
        }
        SectionTitle("Agent for new chats")
        ClaudeCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.ifEmpty {
                    listOf(com.example.gemini.data.agent.opencode.OpenCodeAgent("build", "build", ""), com.example.gemini.data.agent.opencode.OpenCodeAgent("plan", "plan", ""))
                }.forEach { a ->
                    FilterChip(selected = defaultAgent == a.id, onClick = { backend.prefs.setAgent(a.id) }, label = { Text(a.name.replaceFirstChar { it.uppercase() }) })
                }
            }
            Text(
                "Build makes changes; Plan only reads and plans. Switch any time from the chip above the message box.",
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp)
            )
        }

        }

        // ---- built-in providers
        if (page == OpenCodeSettingsPage.PROVIDERS) ClaudeCard {
            Text(
                "OpenCode has ${integrations.size.takeIf { it > 0 } ?: "200+"} built-in providers (OpenAI, Anthropic, Google, OpenRouter, Groq, DeepSeek, Mistral, Ollama Cloud…). " +
                    "Connect one with its API key; its models then show in the model picker. ${models.size} models are available now.",
                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            integrationsError?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            val connected = integrations.filter { it.connections.isNotEmpty() }
            if (connected.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                connected.forEach { integ ->
                    integ.connections.forEach { (credId, label) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Link, null, tint = OpenCodeAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(integ.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                            if (credId.startsWith("cred")) {
                                TextButton(onClick = {
                                    scope.launch {
                                        backend.client.api("DELETE", "/credential/$credId")
                                            .onSuccess { toast("${integ.name} disconnected"); backend.refreshModels() }
                                            .onFailure { toast("Could not disconnect: ${it.message}", ok = false) }
                                        reload()
                                    }
                                }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showConnect = true },
                enabled = integrations.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent, contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Connect a provider")
            }
        }

        // ---- custom endpoints
        if (page == OpenCodeSettingsPage.ENDPOINTS) ClaudeCard {
            Text(
                "Any OpenAI-compatible API (Ollama, LM Studio, vLLM, LiteLLM, a company gateway…) or an Anthropic-compatible one. " +
                    "Its address must be reachable from the server.",
                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            customs.forEach { p ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cloud, null, tint = OpenCodeAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text(
                            "${if (p.kind == "anthropic") "Anthropic" else "OpenAI"}-compatible · ${p.models.size} model${if (p.models.size == 1) "" else "s"}" +
                                (p.api_key_hint?.let { " · key $it" } ?: if (p.has_api_key) " · key set" else ""),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        Text(p.base_url, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editing = p }) { Icon(Icons.Outlined.Edit, "Edit") }
                    IconButton(onClick = {
                        scope.launch {
                            backend.client.deleteCustomProvider(p.id)
                                .onSuccess { toast("${p.name} removed"); backend.refreshModels() }
                                .onFailure { toast("Could not remove: ${it.message}", ok = false) }
                            reload()
                        }
                    }) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { editing = OpenCodeCustomProvider() }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add an endpoint")
            }
        }
        if (page == OpenCodeSettingsPage.MCP) OpenCodeMcpPage(backend)
        Spacer(Modifier.height(24.dp))
    }

    if (showConnect) {
        ConnectProviderDialog(
            integrations = integrations.filter { it.keyLogin },
            onDismiss = { showConnect = false },
            onConnect = { integ, key, done ->
                scope.launch {
                    backend.client.post("/integration/${integ.id}/connect/key", buildJsonObject { put("key", key); put("label", "Added from AntiGem") })
                        .onSuccess {
                            toast("${integ.name} connected")
                            showConnect = false
                            backend.refreshModels()
                            reload()
                        }
                        .onFailure { toast("Could not connect: ${it.message}", ok = false) }
                    done()
                }
            }
        )
    }
    editing?.let { p ->
        CustomProviderDialog(
            backend = backend,
            initial = p,
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                backend.refreshModels()
                scope.launch { reload() }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ConnectProviderDialog(integrations: List<Integration>, onDismiss: () -> Unit, onConnect: (Integration, String, () -> Unit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Integration?>(null) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.let { "Connect ${it.name}" } ?: "Connect a provider", fontWeight = FontWeight.Bold) },
        text = {
            val p = picked
            if (p == null) {
                Column {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search providers") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val shown = integrations.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.id }) { integ ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picked = integ }.padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(integ.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                if (integ.connections.isNotEmpty()) Text("connected", fontSize = 11.sp, color = OpenCodeAccent)
                            }
                        }
                    }
                }
            } else {
                Column {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true, label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Saved by OpenCode on the server" + (p.envNames.firstOrNull()?.let { ". Setting $it in the server's environment works too." } ?: "."),
                        fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        },
        confirmButton = {
            if (picked != null) {
                Button(onClick = {
                    busy = true
                    onConnect(picked!!, key.trim()) { busy = false }
                }, enabled = key.isNotBlank() && !busy) { Text(if (busy) "Connecting…" else "Connect") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (picked != null) { picked = null; key = "" } else onDismiss() }) { Text(if (picked != null) "Back" else "Close") }
        }
    )
}

@Composable
private fun CustomProviderDialog(backend: OpenCodeChatBackend, initial: OpenCodeCustomProvider, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind.ifBlank { "openai" }) }
    var baseUrl by remember { mutableStateOf(initial.base_url) }
    var apiKey by remember { mutableStateOf("") }
    val models = remember { mutableStateListOf<OpenCodeCustomModel>().apply { addAll(initial.models) } }
    var newModel by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<List<OpenCodeCustomModel>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add an endpoint" else "Edit ${initial.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "openai", onClick = { kind = "openai" }, label = { Text("OpenAI-compatible") })
                    FilterChip(selected = kind == "anthropic", onClick = { kind = "anthropic" }, label = { Text("Anthropic") })
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (kind == "anthropic") "https://api.example.com/v1" else "http://localhost:11434/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    apiKey, { apiKey = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (!isNew && initial.has_api_key) "API key (blank keeps ${initial.api_key_hint ?: "the saved one"})" else "API key (optional for local servers)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Models", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            fetching = true
                            error = null
                            scope.launch {
                                backend.client.fetchModels(kind, baseUrl.trim(), apiKey.trim(), initial.id)
                                    .onSuccess { fetched = it; if (it.isEmpty()) error = "The endpoint listed no models." }
                                    .onFailure { error = it.message }
                                fetching = false
                            }
                        },
                        enabled = baseUrl.isNotBlank() && !fetching
                    ) { Text(if (fetching) "Fetching…" else "Fetch from endpoint") }
                }
                models.toList().forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.id, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { models.remove(m) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Delete, "Remove", Modifier.size(16.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newModel, { newModel = it }, singleLine = true, placeholder = { Text("model id, e.g. llama3.1") }, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val id = newModel.trim()
                        if (id.isNotBlank() && models.none { it.id == id }) models.add(OpenCodeCustomModel(id))
                        newModel = ""
                    }) { Icon(Icons.Outlined.Add, "Add model") }
                }
                fetched?.takeIf { it.isNotEmpty() }?.let { list ->
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to add (${list.size} found):", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { m ->
                            val on = models.any { it.id == m.id }
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) models.removeAll { it.id == m.id } else models.add(m) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = on, onCheckedChange = { if (on) models.removeAll { it.id == m.id } else models.add(m) })
                                Text(m.name?.takeIf { it.isNotBlank() && it != m.id }?.let { "$it  (${m.id})" } ?: m.id, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        backend.client.saveCustomProvider(
                            initial.copy(name = name.trim(), kind = kind, base_url = baseUrl.trim(), api_key = apiKey.trim().ifBlank { null }, models = models.toList())
                        ).onSuccess { toast("${it.name} saved"); onSaved() }
                            .onFailure { error = it.message }
                        saving = false
                    }
                },
                enabled = !saving && name.isNotBlank() && baseUrl.isNotBlank() && models.isNotEmpty()
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
