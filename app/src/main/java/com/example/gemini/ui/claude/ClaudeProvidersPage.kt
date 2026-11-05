package com.example.gemini.ui.claude

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.ClaudeFetchRaw
import com.example.gemini.data.agent.claude.ClaudeModelsMapping
import com.example.gemini.data.agent.claude.ClaudeRawKey
import com.example.gemini.data.agent.claude.ClaudeProvider
import com.example.gemini.data.agent.claude.ClaudeProviderModel
import com.example.gemini.data.agent.claude.ClaudeProviderTestResult
import com.example.gemini.data.agent.claude.contextLabel
import com.example.gemini.data.agent.claude.limitsOf
import com.example.gemini.data.agent.claude.thinkingLabel
import com.example.gemini.data.agent.claude.tokenLabel
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.components.ClaudeAccent

private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api"

/** A starting point for a popular Anthropic-compatible provider; everything stays editable (and testable) in the editor. */
private class Preset(val label: String, val note: String, val provider: ClaudeProvider)

private val PRESETS = listOf(
    Preset(
        "OpenRouter", "Model list is fetched automatically (free tool-capable models by default).",
        ClaudeProvider(name = "OpenRouter", base_url = OPENROUTER_BASE_URL)
    ),
    Preset(
        "Kimi", "Moonshot AI's Anthropic-compatible endpoint.",
        ClaudeProvider(
            name = "Kimi", base_url = "https://api.moonshot.ai/anthropic", models_url = "https://api.moonshot.ai/v1/models",
            models = listOf(ClaudeProviderModel("kimi-k2-0905-preview", "Kimi K2", context_length = 262_144))
        )
    ),
    Preset(
        "DeepSeek", "DeepSeek's Anthropic-compatible endpoint.",
        ClaudeProvider(
            name = "DeepSeek", base_url = "https://api.deepseek.com/anthropic", models_url = "https://api.deepseek.com/models",
            models = listOf(
                ClaudeProviderModel("deepseek-chat", "DeepSeek Chat", context_length = 128_000),
                ClaudeProviderModel("deepseek-reasoner", "DeepSeek Reasoner", context_length = 128_000)
            )
        )
    ),
    Preset(
        "Z.ai", "Z.ai (GLM) Anthropic-compatible endpoint.",
        ClaudeProvider(
            name = "Z.ai", base_url = "https://api.z.ai/api/anthropic", models_url = "https://api.z.ai/api/paas/v4/models",
            models = listOf(ClaudeProviderModel("glm-4.6", "GLM 4.6", context_length = 200_000))
        )
    ),
    Preset("Custom", "Any endpoint that speaks the Anthropic Messages API.", ClaudeProvider())
)

/**
 * Custom providers: Anthropic-compatible APIs whose models show up in the Claude model picker next to Claude's own.
 * A chat on one runs with that provider's endpoint, key and limits; settings.json and other chats are not changed.
 * Changes reach a chat the next time its process starts (switch the model or reopen the chat).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvidersPage(deps: ClaudeSettingsDeps) {
    val backend = deps.backend
    val context = LocalContext.current
    val providers by backend.providers.collectAsState()
    val settingsEndpoint by backend.settingsEndpoint.collectAsState()
    val conflicts by backend.settingsConflicts.collectAsState()
    var editing by remember { mutableStateOf<ClaudeProvider?>(null) }
    var toDelete by remember { mutableStateOf<ClaudeProvider?>(null) }
    var importing by remember { mutableStateOf(false) }
    var savedProvider by remember { mutableStateOf<ClaudeProvider?>(null) }
    var restarting by remember { mutableStateOf(false) }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val results = remember { mutableStateMapOf<String, ClaudeProviderTestResult>() }
    LaunchedEffect(Unit) { backend.refreshProviders() }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Text("Other Anthropic-compatible APIs", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Text(
        "OpenRouter, Kimi, DeepSeek, Z.ai… Their models are listed in the chat's model picker under Claude's own. " +
            "A chat on one of them uses its key, headers and limits; your Claude login and other chats stay as they are.",
        fontSize = 11.5.sp, color = muted
    )

    settingsEndpoint?.let { endpoint ->
        Surface(shape = RoundedCornerShape(12.dp), color = QuotaAmber.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaAmber.copy(alpha = 0.4f))) {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("settings.json sends every chat to $endpoint", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Then even chats on Claude's models go there. Move it into a provider so only the chats you choose use it.",
                    fontSize = 11.5.sp, color = muted
                )
                TextButton(enabled = !importing, onClick = {
                    importing = true
                    backend.importSettingsProvider { err ->
                        importing = false
                        Toast.makeText(context, err ?: "Moved into a provider", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (importing) "Moving…" else "Move into a provider", color = ClaudeAccent) }
            }
        }
    }
    if (conflicts.isNotEmpty() && providers.isNotEmpty()) {
        Text(
            "settings.json sets ${conflicts.joinToString(", ")}. Provider chats override these, so they cannot change a provider's model or key.",
            fontSize = 11.sp, color = muted
        )
    }

    // quick start
    Text("Add a provider", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESETS.filter { it.label != "OpenRouter" || providers.none { p -> p.openrouter } }.forEach { preset ->
            OutlinedButton(onClick = { editing = preset.provider }) { Text(if (preset.label == "Custom") "+ Custom" else "+ ${preset.label}") }
        }
    }

    if (providers.isEmpty()) {
        ClaudeCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No custom providers yet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Pick a preset or add your own with its base URL and API key, test the connection, then add or fetch its models.", fontSize = 11.5.sp, color = muted)
            }
        }
    }

    providers.forEach { p ->
        ProviderCard(
            p = p,
            testing = testing[p.id] == true,
            result = results[p.id],
            onEdit = { editing = p },
            onDelete = { toDelete = p },
            onTest = {
                testing[p.id] = true
                results.remove(p.id)
                backend.testProvider(p, p.models.firstOrNull()?.id.orEmpty()) { res ->
                    testing[p.id] = false
                    results[p.id] = res
                }
            }
        )
    }

    editing?.let { p ->
        val preset = PRESETS.firstOrNull { it.provider === p }
        ProviderEditor(p, backend, note = preset?.note, onDismiss = { editing = null }) { saved, done ->
            backend.saveProvider(saved) { err ->
                done(err)
                if (err == null) {
                    editing = null
                    // chats already running keep the settings they started with
                    if (saved.id.isNotBlank()) savedProvider = saved
                }
            }
        }
    }

    savedProvider?.let { p ->
        AlertDialog(
            onDismissRequest = { savedProvider = null },
            title = { Text("Saved ${p.name}", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "New chats and chats you reopen use these settings right away. Chats that are already running on ${p.name} keep the " +
                        "settings they started with until they restart (the conversation is kept). You can also use \"Restart chat\" in a chat's menu.",
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                TextButton(enabled = !restarting, onClick = {
                    restarting = true
                    backend.restartProviderChats(p.id) { restarted, busy, err ->
                        restarting = false
                        savedProvider = null
                        val msg = err?.let { "Could not restart: $it" } ?: buildString {
                            append(if (restarted == 0) "No running chat needed a restart" else "Restarted $restarted chat${if (restarted == 1) "" else "s"}")
                            if (busy > 0) append("; $busy still replying: restart ${if (busy == 1) "it" else "them"} when done")
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (restarting) "Restarting…" else "Restart idle chats now", color = ClaudeAccent) }
            },
            dismissButton = { TextButton(onClick = { savedProvider = null }) { Text("Later") } }
        )
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${p.name}?", fontWeight = FontWeight.Bold) },
            text = { Text("Its key, headers and models are removed. Chats on it have to pick another model.", fontSize = 13.5.sp) },
            confirmButton = { TextButton(onClick = { backend.deleteProvider(p.id); toDelete = null }) { Text("Remove", color = QuotaRed) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    p: ClaudeProvider,
    testing: Boolean,
    result: ClaudeProviderTestResult?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var expanded by remember(p.id) { mutableStateOf(false) }
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                Text(p.base_url, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            p.api_key_hint?.let { Badge("Key $it") }
            Badge(if (p.auth_mode == "x-api-key") "x-api-key" else "Bearer token")
            Badge(if (p.thinking_budget > 0) "Thinking ${tokenLabel(p.thinking_budget.toLong())}" else "No thinking budget")
            if (p.headers.isNotEmpty()) Badge("${p.headers.size} header${if (p.headers.size == 1) "" else "s"}")
            if (p.extra_env.isNotEmpty()) Badge("${p.extra_env.size} env")
            if (p.timeout_sec > 0) Badge("${p.timeout_sec}s timeout")
            if (p.disable_betas) Badge("no betas")
        }
        Spacer(Modifier.size(8.dp))
        Text("Models (${p.models.size})", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
        if (p.models.isEmpty()) {
            Text("None yet: open Edit to add model ids or fetch the provider's list.", fontSize = 11.5.sp, color = muted)
        }
        (if (expanded) p.models else p.models.take(5)).forEach { m ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp)
                Text(
                    listOfNotNull(m.id.takeIf { !m.name.isNullOrBlank() }, m.contextLabel(p) ?: "context unknown", m.thinkingLabel(p)).joinToString(" · "),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = muted
                )
            }
        }
        if (p.models.size > 5) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show all ${p.models.size}", color = ClaudeAccent, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !testing, onClick = onTest) {
                Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(16.dp), tint = ClaudeAccent)
                Spacer(Modifier.width(6.dp))
                Text("Test connection", color = ClaudeAccent)
            }
            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        }
        result?.let { TestResultView(it) }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun TestResultView(r: ClaudeProviderTestResult) {
    val color = if (r.ok) QuotaGreen else QuotaRed
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.1f), border = BorderStroke(1.dp, color.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (r.ok) {
                    Text("Connected · ${r.latency_ms} ms", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text("${r.model} answered${if (r.reply.isNotBlank()) ": \"${r.reply.take(60)}\"" else ""}", fontSize = 11.5.sp)
                } else {
                    Text("Connection failed", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text(r.error, fontSize = 11.5.sp)
                    if (r.hint.isNotBlank()) Text(r.hint, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                if (r.url.isNotBlank()) Text(r.url, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

// ---------------------------------------------------------------- editor

/** "128k" / "1.5m" / "200000" → tokens; blank → 0; null when it is not a number. */
private fun parseTokens(text: String): Long? {
    val t = text.trim().lowercase().replace(",", "").replace("_", "")
    if (t.isEmpty()) return 0
    val mult = when {
        t.endsWith("m") -> 1_000_000.0
        t.endsWith("k") -> 1000.0
        else -> 1.0
    }
    val n = t.trimEnd('k', 'm').toDoubleOrNull() ?: return null
    return (n * mult).toLong().takeIf { it >= 0 }
}

private fun headersText(h: Map<String, String>) = h.entries.joinToString("\n") { "${it.key}: ${it.value}" }
private fun envText(e: Map<String, String>) = e.entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun parsePairs(text: String, separator: Char, what: String): Pair<Map<String, String>, String?> {
    val out = linkedMapOf<String, String>()
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val i = line.indexOf(separator)
        if (i <= 0) return emptyMap<String, String>() to "$what: \"$line\" must look like NAME$separator${if (separator == ':') " value" else "value"}"
        out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
    }
    return out to null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditor(
    initial: ClaudeProvider,
    backend: ClaudeChatBackend,
    note: String?,
    onDismiss: () -> Unit,
    onSave: (ClaudeProvider, (String?) -> Unit) -> Unit
) {
    val isNew = initial.id.isBlank()
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.base_url) }
    var key by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(initial.auth_mode.ifBlank { "bearer" }) }
    var modelsUrl by remember { mutableStateOf(initial.models_url) }
    var smallModel by remember { mutableStateOf(initial.small_model) }
    var models by remember { mutableStateOf(initial.models) }
    var sendThinking by remember { mutableStateOf(initial.thinking_budget > 0) }
    var budgetText by remember { mutableStateOf(if (initial.thinking_budget > 0) initial.thinking_budget.toString() else "16000") }
    var timeoutText by remember { mutableStateOf(if (initial.timeout_sec > 0) initial.timeout_sec.toString() else "") }
    var disableBetas by remember { mutableStateOf(initial.disable_betas) }
    // blank = the default (3), 0 = never retry, 1..10
    var retriesText by remember { mutableStateOf(if (initial.max_retries > 0) initial.max_retries.toString() else if (initial.max_retries < 0) "0" else "") }
    var defaultCtxText by remember { mutableStateOf(if (initial.default_context > 0) initial.default_context.toString() else "") }
    var defaultOutText by remember { mutableStateOf(if (initial.default_max_output > 0) initial.default_max_output.toString() else "") }
    var headers by remember { mutableStateOf(headersText(initial.headers)) }
    var env by remember { mutableStateOf(envText(initial.extra_env)) }
    var newModel by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ClaudeProviderTestResult?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<ClaudeProviderModel>?>(null) }
    var fetchRaw by remember { mutableStateOf<ClaudeFetchRaw?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchNote by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf(initial.models_map) }
    var showMapping by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ClaudeProviderModel?>(null) }
    val openRouter = baseUrl.contains("openrouter.ai")
    // the provider-level defaults as typed so far: model rows and the model editor resolve their labels against it
    val draft = initial.copy(
        default_context = parseTokens(defaultCtxText) ?: 0,
        default_max_output = parseTokens(defaultOutText) ?: 0,
        thinking_budget = if (sendThinking) budgetText.trim().toIntOrNull() ?: 0 else 0
    )

    /** The provider as the form shows it, or the first problem with it. */
    fun build(map: ClaudeModelsMapping? = mapping): Pair<ClaudeProvider?, String?> {
        val (h, hErr) = parsePairs(headers, ':', "Headers")
        if (hErr != null) return null to hErr
        val (e, eErr) = parsePairs(env, '=', "Environment")
        if (eErr != null) return null to eErr
        val budget = if (sendThinking) budgetText.trim().toIntOrNull() else 0
        if (budget == null || (budget != 0 && budget < 1024)) return null to "The thinking budget must be a number of at least 1024"
        val timeout = if (timeoutText.isBlank()) 0 else timeoutText.trim().toIntOrNull() ?: return null to "The timeout must be a number of seconds"
        val retriesRaw = retriesText.trim().toIntOrNull()
        if (retriesText.isNotBlank() && (retriesRaw == null || retriesRaw !in 0..10)) return null to "Retries must be a number from 0 to 10"
        // 0 (blank) = the default, -1 = never retry
        val retries: Int = when {
            retriesText.isBlank() -> 0
            retriesRaw == 0 -> -1
            else -> retriesRaw ?: 0
        }
        val defCtx = parseTokens(defaultCtxText) ?: return null to "The default context window must be a number like 128k"
        val defOut = parseTokens(defaultOutText) ?: return null to "The default max output must be a number like 8k"
        return initial.copy(
            name = name, base_url = baseUrl, api_key = key.ifBlank { null }, auth_mode = authMode,
            models_url = if (openRouter) "" else modelsUrl, small_model = smallModel, models = models,
            headers = h, extra_env = e, timeout_sec = timeout, thinking_budget = budget, disable_betas = disableBetas,
            default_context = defCtx, default_max_output = defOut, models_map = map, max_retries = retries
        ) to null
    }

    /** Fetches the provider's model list with the form's values; [map] overrides the saved field mapping (Apply & fetch). */
    fun runFetch(map: ClaudeModelsMapping? = mapping) {
        val (p, err) = build(map)
        if (p == null) {
            error = err
            return
        }
        error = null
        fetchError = null
        fetchNote = null
        fetching = true
        backend.fetchFormModels(p, freeOnly = openRouter && freeOnly) { out ->
            fetching = false
            fetchRaw = out.raw
            fetchError = out.error
            when {
                out.error != null -> Unit
                out.models.isNotEmpty() -> {
                    fetched = out.models
                    out.raw?.paging_error?.takeIf { it.isNotBlank() }?.let {
                        Toast.makeText(context, "Only part of the list was read ($it)", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    val raw = out.raw
                    fetchNote = if (raw != null && raw.entries > 0) {
                        "The server listed ${raw.entries} entries but none could be used (no usable id" +
                            (if (openRouter && freeOnly) ", or filtered out by \"only free models\"" else "") +
                            "). Open Field mapping and pick the id field."
                    } else {
                        "The server answered${raw?.status?.takeIf { it > 0 }?.let { " (HTTP $it)" }.orEmpty()} but no models could be read. " +
                            "If the raw response does contain models, open Field mapping and point at them; otherwise it comes from the provider " +
                            "(empty account, permissions or headers)."
                    }
                }
            }
        }
    }

    fun runTest(model: String) {
        val (p, err) = build()
        if (p == null) {
            testResult = ClaudeProviderTestResult(error = err.orEmpty())
            return
        }
        testing = true
        testResult = null
        backend.testProvider(p, model) { res ->
            testing = false
            testResult = res
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                    Text(if (isNew) "Add provider" else "Edit ${initial.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !saving && name.isNotBlank() && baseUrl.isNotBlank() && (!isNew || key.isNotBlank()),
                        onClick = {
                            val (p, err) = build()
                            if (p == null) {
                                error = err
                                return@TextButton
                            }
                            saving = true
                            error = null
                            onSave(p) { e ->
                                saving = false
                                error = e
                            }
                        }
                    ) { Text(if (saving) "Saving…" else "Save", color = ClaudeAccent, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    note?.let { Text(it, fontSize = 12.sp, color = muted) }
                    error?.let { Text(it, fontSize = 12.5.sp, color = QuotaRed, fontWeight = FontWeight.SemiBold) }

                    // ---- connection
                    EditorSection("Connection", "Where the chat sends its requests and how it signs them.") {
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true,
                            placeholder = { Text("https://api.example.com/anthropic") }, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Claude Code calls <base URL>/v1/messages") }
                        )
                        OutlinedTextField(
                            key, { key = it }, label = { Text("API key") }, singleLine = true,
                            placeholder = { Text(if (isNew) "sk-…" else "unchanged (${initial.api_key_hint ?: "saved"})") },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                        )
                        Text("How the key is sent", fontSize = 12.sp, color = muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("bearer" to "Bearer token", "x-api-key" to "x-api-key header").forEach { (mode, label) ->
                                FilterChip(
                                    selected = authMode == mode, onClick = { authMode = mode }, label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                                )
                            }
                        }
                        Text(
                            if (authMode == "bearer") "Authorization: Bearer <key> (ANTHROPIC_AUTH_TOKEN). Used by most providers."
                            else "x-api-key: <key> (ANTHROPIC_API_KEY). Used by providers that copy Anthropic's own scheme.",
                            fontSize = 11.sp, color = muted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = !testing && baseUrl.isNotBlank(), onClick = { runTest(models.firstOrNull()?.id ?: newModel.trim()) }) {
                                Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Test connection")
                            }
                            Spacer(Modifier.width(10.dp))
                            if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                        }
                        Text(
                            "Sends one short message to ${models.firstOrNull()?.id ?: "the model id typed below"} with these exact settings.",
                            fontSize = 11.sp, color = muted
                        )
                        testResult?.let { TestResultView(it) }
                    }

                    // ---- models
                    EditorSection("Models (${models.size})", "These appear in the chat's model picker. Set the context window so the Context chip and auto-compact are right.") {
                        if (!openRouter) {
                            OutlinedTextField(
                                modelsUrl, { modelsUrl = it }, label = { Text("Models URL (optional)") }, singleLine = true,
                                placeholder = { Text("https://api.example.com/v1/models") }, modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = freeOnly, onCheckedChange = { freeOnly = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                                Spacer(Modifier.width(10.dp))
                                Text("Only free models that can call tools", fontSize = 12.5.sp)
                            }
                        }
                        val canFetch = openRouter || modelsUrl.isNotBlank()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = canFetch && !fetching && baseUrl.isNotBlank(), onClick = { runFetch() }) {
                                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fetch the provider's list")
                            }
                            Spacer(Modifier.width(4.dp))
                            if (fetching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                            fetchRaw?.let { raw ->
                                IconButton(onClick = { showRaw = true }) {
                                    Icon(Icons.Outlined.Code, "Show the raw response", tint = if (raw.status in 200..299) ClaudeAccent else QuotaAmber)
                                }
                            }
                        }
                        TextButton(enabled = canFetch, onClick = { showMapping = true }) {
                            Text(
                                if (mapping == null) "Field mapping & pagination…" else "Field mapping & pagination (customised)…",
                                color = ClaudeAccent, fontSize = 12.sp
                            )
                        }
                        fetchError?.let { msg ->
                            Surface(shape = RoundedCornerShape(10.dp), color = QuotaRed.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaRed.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Could not fetch the models", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(msg, fontSize = 11.5.sp)
                                }
                            }
                        }
                        fetchNote?.let { msg ->
                            Surface(shape = RoundedCornerShape(10.dp), color = QuotaAmber.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaAmber.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("No models to add", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(msg, fontSize = 11.5.sp)
                                }
                            }
                        }
                        if (!canFetch) Text("Set a models URL to fetch the list; otherwise type model ids below.", fontSize = 11.sp, color = muted)
                        if (isNew && key.isBlank() && canFetch) Text("Enter the API key first.", fontSize = 11.sp, color = muted)

                        models.forEach { m ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { editingModel = m }.padding(vertical = 4.dp)) {
                                    Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            m.id.takeIf { !m.name.isNullOrBlank() },
                                            m.contextLabel(draft) ?: "context unknown (tap to set)",
                                            m.thinkingLabel(draft),
                                            "can reason".takeIf { m.can_think }
                                        ).joinToString(" · "),
                                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                        color = if (m.contextLabel(draft) != null) muted else QuotaAmber, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(enabled = !testing, onClick = { runTest(m.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    Text("Test", color = ClaudeAccent, fontSize = 12.sp)
                                }
                                IconButton(onClick = { editingModel = m }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Edit, "Edit model", Modifier.size(16.dp)) }
                                IconButton(onClick = { models = models - m }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Remove model", Modifier.size(16.dp)) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newModel, onValueChange = { newModel = it }, singleLine = true, modifier = Modifier.weight(1f),
                                placeholder = { Text("model id, e.g. kimi-k2-0905-preview", fontSize = 12.sp) },
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            IconButton(
                                enabled = newModel.isNotBlank() && newModel.none { it.isWhitespace() } && models.none { it.id == newModel.trim() },
                                onClick = {
                                    models = models + ClaudeProviderModel(newModel.trim())
                                    newModel = ""
                                }
                            ) { Icon(Icons.Outlined.Add, "Add model", tint = ClaudeAccent) }
                        }
                    }

                    // ---- behaviour
                    EditorSection("Behavior", "How chats on this provider run.") {
                        ModelIdField(
                            value = smallModel, onValueChange = { smallModel = it }, models = models,
                            label = "Background model (optional)", placeholder = "titles & summaries; default: the chat's model"
                        )
                        Text(
                            "Sonnet, Opus and subagent requests are always mapped to the chat's own model, so the provider never receives a Claude model name.",
                            fontSize = 11.sp, color = muted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Send a thinking budget", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "Off: Thinking in the chat never sends a token budget (safest; many providers reject it). On: it sends the budget below.",
                                    fontSize = 11.sp, color = muted
                                )
                            }
                            Switch(checked = sendThinking, onCheckedChange = { sendThinking = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                        }
                        if (sendThinking) {
                            OutlinedTextField(
                                budgetText, { budgetText = it.filter(Char::isDigit) }, label = { Text("Thinking budget (tokens)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            "This is the provider's default. Any model can override it: tap it in the list above.",
                            fontSize = 11.sp, color = muted
                        )
                        Text("Defaults for models without their own size", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        OutlinedTextField(
                            defaultCtxText, { defaultCtxText = it }, label = { Text("Default context window") }, singleLine = true,
                            isError = parseTokens(defaultCtxText) == null, placeholder = { Text("e.g. 128k (blank = unknown)") }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            defaultOutText, { defaultOutText = it }, label = { Text("Default max output") }, singleLine = true,
                            isError = parseTokens(defaultOutText) == null, placeholder = { Text("e.g. 8k (blank = unknown)") }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            timeoutText, { timeoutText = it.filter(Char::isDigit) }, label = { Text("Request timeout, seconds (optional)") }, singleLine = true,
                            placeholder = { Text("default") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            retriesText, { retriesText = it.filter(Char::isDigit) }, label = { Text("Retries when a request fails (0-10)") }, singleLine = true,
                            placeholder = { Text("default: 3") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Rate limits, rejected keys and network errors are retried with growing waits. 0 = fail at once.") }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Disable experimental betas", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Turn on if the provider answers 400 about unknown fields or beta headers.", fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = disableBetas, onCheckedChange = { disableBetas = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                        }
                    }

                    // ---- advanced
                    EditorSection("Advanced", "Extra request headers and environment variables for this provider's chats.") {
                        OutlinedTextField(
                            headers, { headers = it }, label = { Text("Headers (one per line)") }, minLines = 2, maxLines = 6,
                            placeholder = { Text("X-Title: AntiGem\nHTTP-Referer: https://example.com") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            env, { env = it }, label = { Text("Environment (NAME=value, one per line)") }, minLines = 2, maxLines = 6,
                            placeholder = { Text("CLAUDE_CODE_MAX_OUTPUT_TOKENS=8192") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth()
                        )
                        Text("These win over everything above, so they can fix a provider's quirks.", fontSize = 11.sp, color = muted)
                    }
                    Text("Changes apply the next time a chat's process starts (switch its model or reopen the chat).", fontSize = 11.sp, color = muted)
                }
            }
        }
    }

    editingModel?.let { m ->
        ModelEditDialog(m, draft, onDismiss = { editingModel = null }) { updated ->
            models = models.map { if (it.id == m.id) updated else it }
            editingModel = null
        }
    }

    if (showRaw) fetchRaw?.let { raw -> RawResponseDialog(raw, onDismiss = { showRaw = false }) }

    if (showMapping) {
        ModelsMappingDialog(
            raw = fetchRaw, initial = mapping ?: ClaudeModelsMapping(),
            onDismiss = { showMapping = false }
        ) { chosen ->
            val saved = chosen.takeIf { it != ClaudeModelsMapping() }
            mapping = saved
            showMapping = false
            runFetch(saved)
        }
    }

    fetched?.let { list ->
        FetchedModelsDialog(initial.name.ifBlank { name }, list, models, onDismiss = { fetched = null }) { picked ->
            // fill in sizes the saved models lack, then add the new ones
            val bySize = list.associateBy { it.id }
            models = models.map { m ->
                val f = bySize[m.id]
                if (f != null && (m.context_length == 0L || (f.can_think && !m.can_think))) {
                    m.copy(
                        context_length = m.context_length.takeIf { it > 0 } ?: f.context_length,
                        max_output_tokens = m.max_output_tokens.takeIf { it > 0 } ?: f.max_output_tokens,
                        can_think = m.can_think || f.can_think
                    )
                } else m
            } + picked.filter { p -> models.none { it.id == p.id } }
            fetched = null
        }
    }
}

@Composable
private fun EditorSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    ClaudeCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelEditDialog(model: ClaudeProviderModel, provider: ClaudeProvider, onDismiss: () -> Unit, onSave: (ClaudeProviderModel) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var name by remember { mutableStateOf(model.name.orEmpty()) }
    var ctx by remember { mutableStateOf(if (model.context_length > 0) model.context_length.toString() else "") }
    var out by remember { mutableStateOf(if (model.max_output_tokens > 0) model.max_output_tokens.toString() else "") }
    var thinkMode by remember { mutableStateOf(model.thinking_mode) }
    var budgetText by remember { mutableStateOf(if (model.thinking_budget > 0) model.thinking_budget.toString() else "16000") }
    val ctxValue = parseTokens(ctx)
    val outValue = parseTokens(out)
    val budget = budgetText.trim().toIntOrNull()
    val budgetOk = thinkMode != "budget" || (budget != null && budget >= 1024)
    val inherited = if (provider.thinking_budget > 0) "thinking ${tokenLabel(provider.thinking_budget.toLong())}" else "no thinking budget"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.id, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    ctx, { ctx = it }, label = { Text("Context window (tokens)") }, singleLine = true, isError = ctxValue == null,
                    placeholder = { Text(if (provider.default_context > 0) "default: ${tokenLabel(provider.default_context)}" else "e.g. 128k, 200000, 1m") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Used by the Context chip and for auto-compact. Blank = the provider's default.") }
                )
                OutlinedTextField(
                    out, { out = it }, label = { Text("Max output (tokens, optional)") }, singleLine = true, isError = outValue == null,
                    placeholder = { Text(if (provider.default_max_output > 0) "default: ${tokenLabel(provider.default_max_output)}" else "e.g. 8k") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Thinking", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (model.can_think) Text("The provider's model list says this model can reason.", fontSize = 11.sp, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Inherit", "off" to "Never send", "budget" to "Custom budget").forEach { (mode, label) ->
                        FilterChip(
                            selected = thinkMode == mode, onClick = { thinkMode = mode }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                        )
                    }
                }
                if (thinkMode == "") Text("Inherits the provider: $inherited.", fontSize = 11.sp, color = muted)
                if (thinkMode == "off") Text("Thinking in the chat never sends a token budget to this model.", fontSize = 11.sp, color = muted)
                if (thinkMode == "budget") {
                    OutlinedTextField(
                        budgetText, { budgetText = it.filter(Char::isDigit) }, label = { Text("Thinking budget (tokens)") }, singleLine = true,
                        isError = !budgetOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("At least 1024. Sent when Thinking is on in the chat.") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ctxValue != null && outValue != null && budgetOk, onClick = {
                onSave(
                    model.copy(
                        name = name.trim().ifBlank { null }, context_length = ctxValue ?: 0, max_output_tokens = outValue ?: 0,
                        thinking_mode = thinkMode, thinking_budget = if (thinkMode == "budget") budget ?: 0 else 0
                    )
                )
            }) { Text("OK", color = ClaudeAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FetchedModelsDialog(
    providerName: String,
    models: List<ClaudeProviderModel>,
    current: List<ClaudeProviderModel>,
    onDismiss: () -> Unit,
    onAdd: (List<ClaudeProviderModel>) -> Unit
) {
    val existing = remember(current) { current.map { it.id }.toSet() }
    var picked by remember { mutableStateOf(models.map { it.id }.filter { it !in existing }.toSet()) }
    var query by remember { mutableStateOf("") }
    val shown = models.filter { query.isBlank() || it.id.contains(query, true) || it.name.orEmpty().contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName: ${models.size} models", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (models.size > 8) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row {
                    TextButton(onClick = { picked = picked + shown.map { it.id }.filter { it !in existing } }) { Text("Select all") }
                    TextButton(onClick = { picked = picked - shown.map { it.id }.toSet() }) { Text("None") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { m ->
                        val added = m.id in existing
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !added) { picked = if (m.id in picked) picked - m.id else picked + m.id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = added || m.id in picked, onCheckedChange = null, enabled = !added,
                                colors = CheckboxDefaults.colors(checkedColor = ClaudeAccent)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp)
                                Text(
                                    listOfNotNull(m.id, m.contextLabel(), "can reason".takeIf { m.can_think }, "added".takeIf { added }).joinToString(" · "),
                                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(models.filter { it.id in picked }) }) {
                Text(if (picked.isEmpty()) "Update sizes" else "Add ${picked.size}", color = ClaudeAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A text field for a model id with a dropdown of the provider's models (typing any id still works). */
@Composable
private fun ModelIdField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<ClaudeProviderModel>,
    label: String,
    placeholder: String
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value, onValueChange, label = { Text(label) }, singleLine = true, placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (models.isNotEmpty()) {
                    IconButton(onClick = { open = !open }) { Icon(Icons.Filled.ArrowDropDown, "Choose from the provider's models") }
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(
                text = { Text("Same as the chat's model", fontSize = 13.sp) },
                onClick = { onValueChange(""); open = false }
            )
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!m.name.isNullOrBlank()) Text(m.id, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    },
                    onClick = { onValueChange(m.id); open = false }
                )
            }
        }
    }
}

/** The request and the raw answer of a model fetch, so a server problem can be told from an app problem. */
@Composable
private fun RawResponseDialog(raw: ClaudeFetchRaw, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val verdict = when {
        raw.status == 0 -> "No answer from the server (network or URL problem)."
        raw.status !in 200..299 -> "The server refused the request (HTTP ${raw.status}): not an app problem."
        raw.entries == 0 -> "The server answered HTTP ${raw.status} and listed no models: the app has nothing to show."
        raw.kept < raw.entries -> "The server listed ${raw.entries} entries; the app kept ${raw.kept} (the others have no \"id\" or were filtered out)."
        else -> "The server listed ${raw.entries} models and the app kept all of them."
    }
    val text = buildString {
        appendLine("GET ${raw.url}")
        raw.request_headers.forEach { appendLine(it) }
        appendLine()
        appendLine("HTTP ${raw.status}")
        append(raw.body)
        if (raw.truncated) append("\n… (cut)")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw response", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(verdict, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text("GET ${raw.url}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                raw.request_headers.forEach { Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = muted) }
                HorizontalDivider()
                Text("HTTP ${raw.status}  ·  ${raw.entries} listed  ·  ${raw.kept} kept", fontSize = 11.sp, color = muted)
                if (raw.pages > 0) {
                    Text(
                        "${raw.pages} page${if (raw.pages == 1) "" else "s"} read · pagination: ${raw.pagination.ifBlank { "none" }}",
                        fontSize = 11.sp, color = muted
                    )
                }
                if (raw.paging_error.isNotBlank()) Text("Stopped early: ${raw.paging_error}", fontSize = 11.sp, color = QuotaAmber)
                Text("The body below is the first page only.", fontSize = 10.5.sp, color = muted)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        Text(raw.body.ifBlank { "(empty body)" } + if (raw.truncated) "\n… (cut)" else "", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp), tint = ClaudeAccent); Spacer(Modifier.width(6.dp)); Text("Copy", color = ClaudeAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A text field for a path / name with a dropdown of suggestions (typing anything still works). */
@Composable
private fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<ClaudeRawKey>,
    placeholder: String = "detect automatically",
    autoLabel: String = "Detect automatically"
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value, onValueChange, label = { Text(label) }, singleLine = true, placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton(onClick = { open = !open }) { Icon(Icons.Filled.ArrowDropDown, "Choose") } }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(autoLabel, fontSize = 13.sp) }, onClick = { onValueChange(""); open = false })
            options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(o.path.ifBlank { "(top level)" }, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            if (o.sample.isNotBlank()) Text(o.sample, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { onValueChange(o.path); open = false }
                )
            }
        }
    }
}

/**
 * Tells the fetch how to read a provider whose model list the app does not understand by itself: where the array is,
 * which field is the id / name / size, and how the pages are chained. Suggestions come from the last fetch's answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsMappingDialog(
    raw: ClaudeFetchRaw?,
    initial: ClaudeModelsMapping,
    onDismiss: () -> Unit,
    onApply: (ClaudeModelsMapping) -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var m by remember { mutableStateOf(initial) }
    val lists = raw?.lists.orEmpty()
    val topKeys = raw?.top_keys.orEmpty()
    // the keys of the list being mapped (the chosen path, else the first list found)
    val current = lists.firstOrNull { it.path == m.list_path } ?: lists.firstOrNull()
    val itemKeys = current?.keys.orEmpty()
    var sizeText by remember { mutableStateOf(if (initial.page_size > 0) initial.page_size.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Field mapping & pagination", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (raw == null || lists.isEmpty()) "Fetch once to get suggestions from the provider's answer, or type the paths by hand. Leave a field empty to detect it."
                    else "Suggestions come from the last answer. Leave a field empty to detect it. Paths use dots, e.g. top_provider.context_length.",
                    fontSize = 11.5.sp, color = muted
                )
                Text("Where the models are", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SuggestField(
                    m.list_path, { m = m.copy(list_path = it) }, "List path",
                    lists.map { ClaudeRawKey(it.path, "${it.count} items" + if (it.strings) " (ids as plain text)" else "") }
                )
                Text("Fields of one model", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SuggestField(m.id_field, { m = m.copy(id_field = it) }, "Id", itemKeys)
                SuggestField(m.name_field, { m = m.copy(name_field = it) }, "Display name", itemKeys, "none")
                SuggestField(m.context_field, { m = m.copy(context_field = it) }, "Context window", itemKeys, "none")
                SuggestField(m.max_output_field, { m = m.copy(max_output_field = it) }, "Max output", itemKeys, "none")
                OutlinedTextField(
                    m.strip_prefix, { m = m.copy(strip_prefix = it) }, label = { Text("Remove from the start of every id") },
                    singleLine = true, placeholder = { Text("e.g. models/") }, modifier = Modifier.fillMaxWidth()
                )

                Text("Pagination", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Detect", "none" to "None", "cursor" to "Cursor", "token" to "Page token", "next_url" to "Next URL").forEach { (mode, label) ->
                        FilterChip(
                            selected = m.pagination == mode, onClick = { m = m.copy(pagination = mode) }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                        )
                    }
                }
                Text(
                    when (m.pagination) {
                        "" -> "Detected from the first answer: has_more + last_id (Anthropic), nextPageToken / next_page_token (Google), or a next URL." +
                            (raw?.takeIf { it.pages > 0 }?.let { " Last fetch: ${it.pages} page(s), ${it.pagination.ifBlank { "none" }}." } ?: "")
                        "none" -> "Only the first page is read."
                        "cursor" -> "While the flag is true, the next request sends the cursor (default: has_more / last_id → after_id)."
                        "token" -> "The next request sends the token from the answer (default: next_page_token / nextPageToken → page_token / pageToken)."
                        else -> "The next request goes to the URL the answer carries (default field: next)."
                    },
                    fontSize = 11.sp, color = muted
                )
                if (m.pagination == "cursor") {
                    SuggestField(m.more_field, { m = m.copy(more_field = it) }, "Field that says more pages exist", topKeys, "has_more")
                }
                if (m.pagination == "cursor" || m.pagination == "token" || m.pagination == "next_url") {
                    SuggestField(
                        m.next_field, { m = m.copy(next_field = it) },
                        when (m.pagination) { "cursor" -> "Field with the last id"; "token" -> "Field with the next token"; else -> "Field with the next URL" },
                        topKeys, "default"
                    )
                }
                if (m.pagination == "cursor" || m.pagination == "token") {
                    OutlinedTextField(
                        m.page_param, { m = m.copy(page_param = it) }, label = { Text("Query parameter to send it in") },
                        singleLine = true, placeholder = { Text(if (m.pagination == "cursor") "after_id" else "page_token") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (m.pagination != "none") {
                    OutlinedTextField(
                        sizeText, { sizeText = it.filter(Char::isDigit); m = m.copy(page_size = sizeText.toIntOrNull() ?: 0) },
                        label = { Text("Page size (optional)") }, singleLine = true, placeholder = { Text("provider's default") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                    if (m.page_size > 0) {
                        OutlinedTextField(
                            m.limit_param, { m = m.copy(limit_param = it) }, label = { Text("Parameter name for the page size") },
                            singleLine = true, placeholder = { Text("limit") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text("At most 100 pages / 20,000 models are read; reading stops early if a page adds nothing new.", fontSize = 10.5.sp, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(m) }) { Text("Apply & fetch", color = ClaudeAccent) } },
        dismissButton = {
            Row {
                TextButton(onClick = { m = ClaudeModelsMapping(); sizeText = "" }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
