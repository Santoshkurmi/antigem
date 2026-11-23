package ai.antigem.ui.opencode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.agent.opencode.OpenCodeChatBackend
import ai.antigem.data.agent.opencode.OpenCodeCustomModel
import ai.antigem.data.agent.opencode.OpenCodeCustomProvider
import ai.antigem.data.agent.opencode.OpenCodePreferences
import ai.antigem.data.agent.opencode.OpenCodeState
import ai.antigem.data.agent.opencode.a
import ai.antigem.data.agent.opencode.obj
import ai.antigem.data.agent.opencode.str
import ai.antigem.ui.claude.ClaudeCard
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.OpenCodeAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A built-inn OpenCode provider (integration) and the saved connections the user made for it. */
private data class Integration(val id: String, val name: String, val keyLogin: Boolean, val envNames: List<String>, val connections: List<Pair<String, String>>)

private fun toast(msg: String, ok: Boolean = true) =
    AppToastHelper.showToast(msg, if (ok) ChatToastType.SUCCESS else ChatToastType.ERROR)

/** The pages of Settings → OpenCode (each opens from the OpenCode row list). */
enum class OpenCodeSettingsPage(val title: String, val subtitle: String) {
    SERVER("OpenCode CLI", "Install, update and restart OpenCode"),
    DEFAULTS("Permissions", "What OpenCode asks before acting, and the agent of new chats"),
    PROVIDERS("Custom providers", "Sign in or add an API key, or add any OpenAI- or Anthropic-compatible API manually"),
    MCP("MCP servers", "Tools OpenCode can call: presets and your own servers"),
    SKILLS("Skills", "Add skills from GitHub, update or remove them")
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
        ai.antigem.ui.settings.SettingsRowItem(icon, OpenCodeAccent, page.title, value, valueColor) { onOpen(page) }
    ai.antigem.ui.settings.SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(row(OpenCodeSettingsPage.SERVER, Icons.Outlined.Dns,
                if (state == OpenCodeState.READY) status?.version ?: state.label else state.label,
                if (state == OpenCodeState.READY) OpenCodeAccent else MaterialTheme.colorScheme.error)),
            listOf(
                row(OpenCodeSettingsPage.DEFAULTS, Icons.Outlined.Tune, openCodeModeLabel(mode)),
                row(OpenCodeSettingsPage.PROVIDERS, Icons.Outlined.Cloud,
                    (connected.size + (endpoints ?: 0)).takeIf { it > 0 }?.let { "$it connected" } ?: if (models.isNotEmpty()) "Free models" else null),
                row(OpenCodeSettingsPage.MCP, Icons.Outlined.Hub, mcp?.takeIf { it > 0 }?.let { "$it servers" }),
                row(OpenCodeSettingsPage.SKILLS, Icons.Outlined.Psychology)
            )
        )
    )
}

/** One page of Settings → OpenCode. */
@Composable
fun OpenCodeSettingsScreen(
    backend: OpenCodeChatBackend,
    page: OpenCodeSettingsPage = OpenCodeSettingsPage.SERVER,
    onOpenRea: () -> Unit = {},
    onOpenReaGroups: () -> Unit = {}
) {
    if (page == OpenCodeSettingsPage.SKILLS) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ai.antigem.ui.settings.AgentSkillsPanel(ai.antigem.domain.model.AgentKind.OPENCODE, OpenCodeAccent)
        }
        return
    }
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
                ai.antigem.ui.agents.AgentMark(ai.antigem.domain.model.AgentKind.OPENCODE, size = 40.dp)
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
                    listOf(ai.antigem.data.agent.opencode.OpenCodeAgent("build", "build", ""), ai.antigem.data.agent.opencode.OpenCodeAgent("plan", "plan", ""))
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

        // ---- built-in providers: accounts and signing in
        if (page == OpenCodeSettingsPage.PROVIDERS) OpenCodeProvidersPage(backend)

        // ---- custom endpoints
        // ---- manual: any OpenAI- / Anthropic-compatible API by its address (on the same page as the providers)
        if (page == OpenCodeSettingsPage.PROVIDERS) ClaudeCard {
            Text("Manual", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Any OpenAI-compatible API (Ollama, LM Studio, vLLM, LiteLLM, a company gateway…) or an Anthropic-compatible one, " +
                    "added by its address. It must be reachable from the server.",
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
                                p.models.count { it.images }.let { if (it > 0) " · $it with images" else "" } +
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
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Add manually")
            }
        }
        if (page == OpenCodeSettingsPage.MCP) OpenCodeMcpPage(backend, onOpenRea, onOpenReaGroups)
        Spacer(Modifier.height(24.dp))
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

/** Which of a provider's models the list shows. */
private enum class ModelFilter(val label: String) { ALL("All"), ADDED("Added"), IMAGES("Images"), REASONING("Reasoning") }

/**
 * Adding or editing a custom provider. Nearly full screen: an endpoint can list hundreds of models (OpenRouter has over
 * 400), so they sit in a lazy list with search, filters and select all / none, and each added model has its chips.
 */
@Composable
private fun CustomProviderDialog(backend: OpenCodeChatBackend, initial: OpenCodeCustomProvider, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = initial.id.isBlank()
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind.ifBlank { "openai" }) }
    var baseUrl by remember { mutableStateOf(initial.base_url) }
    var apiKey by remember { mutableStateOf("") }
    // the models that will be saved, by id
    val models = remember { mutableStateMapOf<String, OpenCodeCustomModel>().apply { initial.models.forEach { put(it.id, it) } } }
    var fetched by remember { mutableStateOf<List<OpenCodeCustomModel>>(emptyList()) }
    var newModel by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ModelFilter.ALL) }
    var fetching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // every model the list can show: the endpoint's, then the saved or hand-added ones it does not list
    val rows by remember {
        derivedStateOf {
            val fetchedIds = fetched.mapTo(HashSet()) { it.id }
            fetched + models.values.filter { it.id !in fetchedIds }.sortedBy { it.id.lowercase() }
        }
    }
    val shown by remember {
        derivedStateOf {
            val q = query.trim()
            rows.filter { m ->
                val cur = models[m.id] ?: m
                (q.isEmpty() || m.id.contains(q, true) || m.name?.contains(q, true) == true) && when (filter) {
                    ModelFilter.ALL -> true
                    ModelFilter.ADDED -> models.containsKey(m.id)
                    ModelFilter.IMAGES -> cur.images
                    ModelFilter.REASONING -> cur.reasoning
                }
            }
        }
    }

    fun fetch() {
        fetching = true
        error = null
        scope.launch {
            backend.client.fetchModels(kind, baseUrl.trim(), apiKey.trim(), initial.id)
                .onSuccess { list ->
                    fetched = list.distinctBy { it.id }
                    when {
                        list.isEmpty() -> error = "The endpoint listed no models."
                        // nothing chosen yet (a new provider): every model the endpoint lists is on; untick the unwanted ones
                        models.isEmpty() -> fetched.forEach { models[it.id] = it }
                        // models already added keep the user's choices: what was found only turns things on and fills in limits
                        else -> fetched.forEach { f ->
                            models[f.id]?.let { o ->
                                models[f.id] = o.copy(
                                    images = o.images || f.images,
                                    reasoning = o.reasoning || f.reasoning,
                                    context_length = o.context_length?.takeIf { it > 0 } ?: f.context_length,
                                    max_output_tokens = o.max_output_tokens?.takeIf { it > 0 } ?: f.max_output_tokens,
                                    detected = f.detected ?: o.detected
                                )
                            }
                        }
                    }
                }
                .onFailure { error = it.message }
            fetching = false
        }
    }

    fun save() {
        saving = true
        error = null
        scope.launch {
            backend.client.saveCustomProvider(
                initial.copy(
                    name = name.trim(), kind = kind, base_url = baseUrl.trim(), api_key = apiKey.trim().ifBlank { null },
                    models = models.values.sortedBy { it.id.lowercase() }
                )
            ).onSuccess { toast("${it.name} saved"); onSaved() }
                .onFailure { error = it.message }
            saving = false
        }
    }

    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f)
        ) {
            Column(Modifier.fillMaxSize()) {
                // title and Save stay put while the list scrolls
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                    Text(
                        if (isNew) "Add a custom provider" else "Edit ${initial.name}",
                        fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { save() },
                        enabled = !saving && name.isNotBlank() && baseUrl.isNotBlank() && models.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeAccent)
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) }

                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    item(key = "fields") {
                        Column {
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
                        }
                    }
                    item(key = "models-header") {
                        Column(Modifier.padding(top = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Models", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (fetching) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 2.dp), strokeWidth = 2.dp, color = OpenCodeAccent)
                                TextButton(onClick = { fetch() }, enabled = baseUrl.isNotBlank() && !fetching) {
                                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (fetching) "Fetching…" else "Fetch from endpoint")
                                }
                            }
                            Text(
                                "Tick the models to add. For each added one, set what it can do: Images lets you attach pictures, Reasoning shows " +
                                    "its thinking, Tools lets it edit files and run commands. Fetching fills these in where the endpoint or models.dev knows the model.",
                                fontSize = 11.sp, lineHeight = 14.sp, color = dim
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                OutlinedTextField(
                                    newModel, { newModel = it }, singleLine = true, modifier = Modifier.weight(1f),
                                    placeholder = { Text("Add a model id by hand, e.g. llama3.1", fontSize = 13.sp) }
                                )
                                IconButton(onClick = {
                                    val id = newModel.trim()
                                    if (id.isNotBlank() && !models.containsKey(id)) models[id] = OpenCodeCustomModel(id)
                                    newModel = ""
                                }) { Icon(Icons.Outlined.Add, "Add model") }
                            }
                        }
                    }
                    if (rows.isNotEmpty()) item(key = "model-tools") {
                        Column(Modifier.padding(top = 8.dp)) {
                            if (rows.size > 8) {
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it }, singleLine = true, shape = RoundedCornerShape(12.dp),
                                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                                    placeholder = { Text("Search ${rows.size} models", fontSize = 13.sp) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ModelFilter.entries.forEach { f ->
                                    val n = when (f) {
                                        ModelFilter.ALL -> rows.size
                                        ModelFilter.ADDED -> models.size
                                        ModelFilter.IMAGES -> rows.count { (models[it.id] ?: it).images }
                                        ModelFilter.REASONING -> rows.count { (models[it.id] ?: it).reasoning }
                                    }
                                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} · $n", fontSize = 12.sp) })
                                }
                            }
                            val allShownAdded = shown.isNotEmpty() && shown.all { models.containsKey(it.id) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${models.size} added" + if (shown.size != rows.size) " · ${shown.size} shown" else "",
                                    fontSize = 11.5.sp, color = dim, modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    if (allShownAdded) shown.forEach { models.remove(it.id) }
                                    else shown.forEach { if (!models.containsKey(it.id)) models[it.id] = it }
                                }, enabled = shown.isNotEmpty()) {
                                    Text(if (allShownAdded) "Select none" else "Select all", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    items(shown, key = { it.id }) { m ->
                        ProviderModelRow(
                            model = m,
                            added = models[m.id],
                            onToggle = { if (models.containsKey(m.id)) models.remove(m.id) else models[m.id] = m },
                            onChange = { models[it.id] = it }
                        )
                    }
                    if (rows.isNotEmpty() && shown.isEmpty()) item(key = "none") {
                        Text("No models match.", fontSize = 12.5.sp, color = dim, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}

/** One model of a custom provider: ticked = added; an added one shows its capability chips. */
@Composable
private fun ProviderModelRow(model: OpenCodeCustomModel, added: OpenCodeCustomModel?, onToggle: () -> Unit, onChange: (OpenCodeCustomModel) -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val cur = added ?: model
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = added != null, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                val title = model.name?.takeIf { it.isNotBlank() && it != model.id }
                Text(title ?: model.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // not added yet: what it can do, so the list can be read before ticking
                val facts = listOfNotNull(
                    model.id.takeIf { title != null },
                    cur.context_length?.takeIf { it > 0 }?.let { "${it / 1000}K" },
                    if (added == null) "images".takeIf { cur.images } else null,
                    if (added == null) "reasoning".takeIf { cur.reasoning } else null,
                    if (added == null) "no tools".takeIf { cur.no_tools } else null
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 10.5.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (added != null) ModelCapabilityChips(added, onChange, Modifier.padding(start = 48.dp, bottom = 6.dp))
    }
}

/** What a custom model can do, as chips the user turns on and off; [onChange] gets the changed model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCapabilityChips(model: OpenCodeCustomModel, onChange: (OpenCodeCustomModel) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CapabilityChip("Images", Icons.Outlined.Image, model.images) { onChange(model.copy(images = it)) }
        CapabilityChip("Reasoning", Icons.Outlined.Psychology, model.reasoning) { onChange(model.copy(reasoning = it)) }
        CapabilityChip("Tools", Icons.Outlined.Build, !model.no_tools) { onChange(model.copy(no_tools = !it)) }
        model.detected?.takeIf { it.isNotBlank() }?.let {
            Text(
                "auto: $it", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun CapabilityChip(label: String, icon: ImageVector, on: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onToggle(!on) },
        label = { Text(label, fontSize = 11.5.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(14.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OpenCodeAccent.copy(alpha = 0.16f),
            selectedLeadingIconColor = OpenCodeAccent
        )
    )
}
