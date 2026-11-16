package com.example.gemini.ui.dsh

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.dsh.DshChatBackend
import com.example.gemini.data.agent.dsh.DshConfigSaved
import com.example.gemini.data.agent.dsh.DshPreferences
import com.example.gemini.data.agent.dsh.DshState
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.claude.ClaudeCard
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.DshAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The pages of Settings → DeepSeek Harness (each opens from the row list). */
enum class DshSettingsPage(val title: String, val subtitle: String) {
    PROVIDER("Provider & model", "API key, default model, output limit and reasoning"),
    PERMISSIONS("Permissions", "What DeepSeek Harness asks before acting"),
    MCP("MCP servers", "Tools DeepSeek Harness can call: presets and your own servers"),
    SKILLS("Skills", "Add skills from GitHub, update or remove them"),
    SERVER("Server & install", "Install, update, remove, restart and stop DeepSeek Harness")
}

fun dshModeLabel(mode: String): String = when (mode) {
    "full" -> "Full access"
    "ask" -> "Ask first"
    else -> mode.replaceFirstChar { it.uppercase() }
}

/** Settings → DeepSeek Harness: one row per page, each with its current state. */
@Composable
fun DshSettingsGroup(backend: DshChatBackend, cardBg: Color, onOpen: (DshSettingsPage) -> Unit) {
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val config by backend.config.collectAsState()
    LaunchedEffect(Unit) {
        backend.refreshStatus()
        backend.refreshConfig()
    }
    fun row(page: DshSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, valueColor: Color? = null) =
        com.example.gemini.ui.settings.SettingsRowItem(icon, DshAccent, page.title, value, valueColor) { onOpen(page) }
    val cfg = config
    val provider = cfg?.providers?.firstOrNull { it.id == cfg.provider }
    val presets = status?.presets.orEmpty()
    com.example.gemini.ui.settings.SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(
                row(DshSettingsPage.PROVIDER, Icons.Outlined.Key,
                    provider?.name ?: cfg?.provider?.takeIf { it.isNotBlank() } ?: state.label,
                    if (state == DshState.NO_KEY) MaterialTheme.colorScheme.error else DshAccent)
            ),
            listOf(
                row(DshSettingsPage.PERMISSIONS, Icons.Outlined.Tune,
                    if (presets.size == 1) dshModeLabel(presets.keys.first()) else cfg?.permission?.takeIf { it.isNotBlank() }?.let { dshModeLabel(it) }),
                row(DshSettingsPage.MCP, Icons.Outlined.Hub, cfg?.mcp?.takeIf { it.isNotEmpty() }?.let { "${it.size} servers" }),
                row(DshSettingsPage.SKILLS, Icons.Outlined.Psychology)
            ),
            listOf(
                row(DshSettingsPage.SERVER, Icons.Outlined.Dns,
                    if (state == DshState.NOT_INSTALLED) state.label else status?.version ?: state.label,
                    if (state == DshState.NOT_INSTALLED || state == DshState.ERROR || state == DshState.OFFLINE) MaterialTheme.colorScheme.error else DshAccent)
            )
        )
    )
}

/**
 * After a settings change: the running dsh restarts to use it (right away when no dsh chat is working, else after
 * asking, since a restart ends running replies).
 */
@Composable
internal fun rememberRestartFlow(backend: DshChatBackend): (DshConfigSaved) -> Unit {
    var ask by remember { mutableStateOf(false) }
    val restart = {
        backend.controlServer("restart") { err ->
            AppToastHelper.showToast(err?.let { "Restart failed: $it" } ?: "DeepSeek Harness restarted with the new settings",
                if (err == null) ChatToastType.SUCCESS else ChatToastType.ERROR)
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Restart DeepSeek Harness?", fontWeight = FontWeight.Bold) },
        text = { Text("The new settings apply after a restart. A chat is replying right now: restarting stops it.") },
        confirmButton = { TextButton(onClick = { ask = false; restart() }) { Text("Restart") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Later") } }
    )
    return { saved ->
        if (saved.restartRequired) {
            if (backend.conversations.value.any { it.isRunning }) ask = true else restart()
        }
    }
}

/** One page of Settings → DeepSeek Harness. */
@Composable
fun DshSettingsScreen(
    backend: DshChatBackend,
    page: DshSettingsPage = DshSettingsPage.SERVER,
    onOpenRea: () -> Unit = {},
    onOpenReaGroups: () -> Unit = {}
) {
    when (page) {
        DshSettingsPage.MCP -> DshMcpPage(backend, onOpenRea, onOpenReaGroups)
        DshSettingsPage.SKILLS -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            com.example.gemini.ui.settings.AgentSkillsPanel(AgentKind.DSH, DshAccent)
            Text(
                "Skills go to ~/.dsh/skills on the server. Use one in a chat by typing / and its name.",
                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        DshSettingsPage.PROVIDER -> DshProviderPage(backend)
        DshSettingsPage.PERMISSIONS -> DshPermissionsPage(backend)
        DshSettingsPage.SERVER -> DshServerPage(backend)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DshServerPage(backend: DshChatBackend) {
    val scope = rememberCoroutineScope()
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val job by backend.cliJob.collectAsState()
    val jobRunning = job?.running == true
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val st = status
    val customBin = !st?.custom_bin.isNullOrBlank()

    suspend fun reload() {
        loading = true
        backend.refreshStatus()
        loading = false
    }
    LaunchedEffect(Unit) {
        backend.refreshCliJob()
        reload()
    }
    LaunchedEffect(jobRunning) { if (!jobRunning && job != null) reload() }
    fun control(action: String, done: String) {
        busy = action
        backend.controlServer(action) { err ->
            busy = null
            AppToastHelper.showToast(err?.let { "Could not $action: $it" } ?: done, if (err == null) ChatToastType.SUCCESS else ChatToastType.ERROR)
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove DeepSeek Harness?", fontWeight = FontWeight.Bold) },
        text = { Text("This deletes dsh and its Node.js from the server. Your chats in ~/.dsh are kept, and so are your settings and API keys.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; backend.startCliJob("remove") }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.gemini.ui.agents.AgentMark(AgentKind.DSH, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("DeepSeek Harness", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        listOfNotNull(
                            st?.version?.let { "dsh $it" },
                            st?.node_version?.let { "Node $it" },
                            when {
                                st?.running == true -> st.uptime_seconds?.let { "running for ${uptime(it)}" } ?: "running"
                                st?.starting == true -> "starting…"
                                st?.installed == true -> "starts with the first message"
                                else -> null
                            }
                        ).joinToString(" · ").ifBlank { state.label },
                        fontSize = 12.sp,
                        color = if (state == DshState.READY) DshAccent else MaterialTheme.colorScheme.error
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
            st?.route?.takeIf { it.isNotBlank() && st.running }?.let {
                Text("Running on $it", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
            }
            if (customBin) Text(
                "The server uses the dsh given with --dsh-bin (${st?.custom_bin}): install and update it yourself.",
                fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp)
            )
            if (st?.glibc_ok == false) Text(
                "The app's glibc loader is missing: reinstall the terminal environment before installing.",
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)
            )
            if (state == DshState.NOT_INSTALLED && !customBin) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "DeepSeek Harness is not installed on the server. Install it here: the app downloads Node.js and dsh (about 1 GB in all).",
                    fontSize = 12.5.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st?.installed != true && !customBin) {
                    Button(
                        onClick = { backend.startCliJob("install") },
                        enabled = !jobRunning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DshAccent, contentColor = Color.White)
                    ) { Text(if (jobRunning) "Installing…" else "Install DeepSeek Harness") }
                } else {
                    if (!customBin) OutlinedButton(onClick = { backend.startCliJob("update") }, enabled = !jobRunning, shape = RoundedCornerShape(12.dp)) {
                        Text(if (jobRunning && job?.kind == "update") "Updating…" else "Update")
                    }
                    if (st?.running == true) {
                        OutlinedButton(onClick = { control("restart", "DeepSeek Harness restarted") }, enabled = busy == null && !jobRunning, shape = RoundedCornerShape(12.dp)) {
                            Text(if (busy == "restart") "Restarting…" else "Restart")
                        }
                        OutlinedButton(onClick = { control("stop", "DeepSeek Harness stopped") }, enabled = busy == null && !jobRunning, shape = RoundedCornerShape(12.dp)) {
                            Text(if (busy == "stop") "Stopping…" else "Stop")
                        }
                    } else {
                        OutlinedButton(onClick = { control("start", "DeepSeek Harness started") }, enabled = busy == null && !jobRunning && st?.starting != true, shape = RoundedCornerShape(12.dp)) {
                            Text(if (busy == "start" || st?.starting == true) "Starting…" else "Start")
                        }
                    }
                    if (!customBin) TextButton(onClick = { confirmRemove = true }, enabled = !jobRunning && busy == null) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (jobRunning || busy != null) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp, color = DshAccent)
            }
            job?.let { j ->
                Spacer(Modifier.height(8.dp))
                Text(
                    (when (j.kind) { "update" -> "Update"; "remove" -> "Remove"; else -> "Install" }) + ": " + when {
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
            Text(
                "Restarting or stopping ends replies that are running. DeepSeek Harness keeps its chats in ~/.dsh on the server; a chat starts it again when needed (5–30 s on a phone).",
                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp)
            )
            st?.error?.takeIf { state != DshState.READY || st.running != true }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            st?.logs?.takeIf { it.isNotEmpty() && (state == DshState.ERROR || st.error != null) }?.let { logs ->
                Text(
                    logs.takeLast(8).joinToString("\n"),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(8.dp)
                )
            }
        }
    }
}

private fun uptime(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "$seconds s"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DshProviderPage(backend: DshChatBackend) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by backend.config.collectAsState()
    val models by backend.availableModels.collectAsState()
    val status by backend.status.collectAsState()
    val onSaved = rememberRestartFlow(backend)
    LaunchedEffect(Unit) {
        backend.refreshConfig()
        if (status?.running == true && models.isEmpty()) backend.refreshModels()
    }
    val cfg = config
    if (cfg == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = DshAccent)
        }
        return
    }
    var keyText by remember(cfg.provider) { mutableStateOf("") }
    var modelText by remember(cfg.provider, cfg.model) { mutableStateOf(cfg.model) }
    var maxTokensText by remember(cfg.max_tokens) { mutableStateOf(if (cfg.max_tokens > 0) cfg.max_tokens.toString() else "32768") }
    var saving by remember { mutableStateOf(false) }
    val provider = cfg.providers.firstOrNull { it.id == cfg.provider }
    val keySaved = provider != null && provider.env in cfg.keys_set

    fun save(body: JsonObject, done: String) {
        saving = true
        scope.launch {
            backend.saveConfig(body)
                .onSuccess { AppToastHelper.showToast(done, ChatToastType.SUCCESS); onSaved(it) }
                .onFailure { AppToastHelper.showToast("Could not save: ${it.message}", ChatToastType.ERROR) }
            saving = false
        }
    }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ClaudeCard {
            Text("Provider", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Where the models run. Each provider uses its own API key.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cfg.providers.forEach { p ->
                    FilterChip(
                        selected = p.id == cfg.provider,
                        enabled = !saving,
                        onClick = {
                            if (p.id != cfg.provider) save(buildJsonObject { put("provider", p.id); put("model", p.default_model.orEmpty()) }, "Provider: ${p.name}")
                        },
                        label = { Text(p.name + if (p.env in cfg.keys_set) " ✓" else "") }
                    )
                }
            }
        }

        if (provider != null) ClaudeCard {
            Text("API key", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (keySaved) "Key saved for ${provider.name} (${provider.env}). Enter a new one to replace it."
                else "${provider.name} needs an API key (${provider.env}). It is kept on the server only.",
                fontSize = 11.5.sp, color = if (keySaved) DshAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                keyText, { keyText = it }, singleLine = true, label = { Text(provider.env) },
                placeholder = { Text(if (keySaved) "••••••••" else "Paste the key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        save(buildJsonObject { put("keys", buildJsonObject { put(provider.env, keyText.trim()) }) }, "Key saved")
                        keyText = ""
                    },
                    enabled = keyText.isNotBlank() && !saving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DshAccent, contentColor = Color.White)
                ) { Text("Save key") }
                if (keySaved) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { save(buildJsonObject { put("keys", buildJsonObject { put(provider.env, "") }) }, "Key removed") }, enabled = !saving) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                provider.key_url?.takeIf { it.isNotBlank() }?.let { url ->
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { open(url) }) { Text("Get a key") }
                }
            }
        }

        ClaudeCard {
            Text("Default model", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "New chats use it unless you pick another one in the chat. Each chat can switch from the model button.",
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            val mine = models.filter { it.id.startsWith(cfg.provider + "/") }
            if (mine.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mine.take(40).forEach { m ->
                        val id = m.id.removePrefix(cfg.provider + "/")
                        FilterChip(selected = id == cfg.model, enabled = !saving, onClick = { modelText = id; save(buildJsonObject { put("model", id) }, "Default model: ${m.displayName}") },
                            label = { Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                if (mine.size > 40) Text("${mine.size - 40} more: type the id below.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            } else if (status?.running != true) {
                Text(
                    "The model list needs DeepSeek Harness running. Type the model id instead${provider?.default_model?.let { " (e.g. $it)" }.orEmpty()}, or load the list.",
                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { backend.refreshModels(start = true) }) { Text("Start and load models") }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    modelText, { modelText = it }, singleLine = true, label = { Text("Model id") },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { save(buildJsonObject { put("model", modelText.trim()) }, "Default model saved") },
                    enabled = modelText.isNotBlank() && modelText.trim() != cfg.model && !saving
                ) { Text("Save") }
            }
        }

        ClaudeCard {
            Text("Reasoning effort", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("How long models that reason think by default. Each chat can change it above the message box.",
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DshPreferences.EFFORTS.forEach { e ->
                    FilterChip(selected = e == cfg.reasoning_effort, enabled = !saving,
                        onClick = { if (e != cfg.reasoning_effort) save(buildJsonObject { put("reasoning_effort", e) }, "Reasoning: ${dshEffortLabel(e)}") },
                        label = { Text(dshEffortLabel(e)) })
                }
            }
        }

        ClaudeCard {
            Text("Max output tokens", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("The most one model reply may write (32768 by default).", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    maxTokensText, { maxTokensText = it.filter { c -> c.isDigit() }.take(7) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { save(buildJsonObject { put("max_tokens", maxTokensText.toInt()) }, "Output limit saved") },
                    enabled = (maxTokensText.toIntOrNull() ?: 0) > 0 && maxTokensText.toIntOrNull() != cfg.max_tokens && !saving
                ) { Text("Save") }
            }
        }
        Text(
            "These settings are kept on the server for every app using it. A change restarts DeepSeek Harness when it is running.",
            fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun DshPermissionsPage(backend: DshChatBackend) {
    val scope = rememberCoroutineScope()
    val status by backend.status.collectAsState()
    val config by backend.config.collectAsState()
    val onSaved = rememberRestartFlow(backend)
    LaunchedEffect(Unit) {
        backend.refreshStatus()
        backend.refreshConfig()
    }
    val presets = status?.presets.orEmpty()
    // Android has one mode (no sandbox there)
    val onlyFull = presets.keys == setOf("full")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ClaudeCard {
            Text("Approvals", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            if (onlyFull) {
                com.example.gemini.ui.claude.PickerOption(
                    title = "Full access",
                    description = "Android has no sandbox; DeepSeek Harness runs commands directly inside Termux. Actions dsh itself flags (like installing plugins) still ask you.",
                    color = com.example.gemini.theme.AppColors.warning,
                    selected = true,
                    icon = Icons.Outlined.WarningAmber,
                    onClick = {}
                )
            } else {
                val current = config?.permission ?: status?.permission ?: "ask"
                (presets.keys.ifEmpty { setOf("ask", "full") }).sortedBy { if (it == "ask") 0 else 1 }.forEach { mode ->
                    com.example.gemini.ui.claude.PickerOption(
                        title = dshModeLabel(mode),
                        description = when (mode) {
                            "ask" -> "Works in the project's sandbox and asks before stepping outside it (dsh preset ${presets[mode] ?: "workspace-write"})."
                            "full" -> "No sandbox and no questions: dsh can change anything (dsh preset ${presets[mode] ?: "danger-full-access"})."
                            else -> "dsh preset ${presets[mode].orEmpty()}"
                        },
                        color = if (mode == "full") com.example.gemini.theme.AppColors.warning else DshAccent,
                        selected = mode == current,
                        icon = if (mode == "full") Icons.Outlined.WarningAmber else Icons.Outlined.PanTool,
                        onClick = {
                            if (mode != current) scope.launch {
                                backend.saveConfig(buildJsonObject { put("permission", mode) })
                                    .onSuccess { AppToastHelper.showToast("New chats: ${dshModeLabel(mode)}", ChatToastType.SUCCESS); onSaved(it) }
                                    .onFailure { AppToastHelper.showToast("Could not save: ${it.message}", ChatToastType.ERROR) }
                            }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "Applies to new chats (DeepSeek Harness restarts to use it). One chat can switch with /permission <preset>.",
                    fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}
