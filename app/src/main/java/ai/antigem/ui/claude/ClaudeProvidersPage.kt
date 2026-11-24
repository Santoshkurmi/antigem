package ai.antigem.ui.claude

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
import ai.antigem.data.agent.claude.ClaudeChatBackend
import ai.antigem.data.agent.claude.ClaudeFetchRaw
import ai.antigem.data.agent.claude.ClaudeModelsMapping
import ai.antigem.data.agent.claude.ClaudeRawKey
import ai.antigem.data.agent.claude.ClaudeProvider
import ai.antigem.data.agent.claude.ClaudeProviderModel
import ai.antigem.data.agent.claude.ClaudeProviderTestResult
import ai.antigem.data.agent.claude.contextLabel
import ai.antigem.data.agent.claude.limitsOf
import ai.antigem.data.agent.claude.thinkingLabel
import ai.antigem.data.agent.claude.tokenLabel
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.QuotaRed
import ai.antigem.ui.components.ClaudeAccent
import ai.antigem.i18n.L

private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api"

/** A starting point for a popular Anthropic-compatible provider; everything stays editable (and testable) in the editor. */
private class Preset(val label: String, val note: String, val provider: ClaudeProvider)

private val PRESETS get() = listOf(
    Preset(
        "OpenRouter", L.claude.providers.modelListIsFetchedAutomaticallyFree,
        ClaudeProvider(name = "OpenRouter", base_url = OPENROUTER_BASE_URL)
    ),
    Preset(
        L.claude.providers.kimi, L.claude.providers.moonshotAisAnthropicCompatibleEndpoint,
        ClaudeProvider(
            name = L.claude.providers.kimi, base_url = "https://api.moonshot.ai/anthropic", models_url = "https://api.moonshot.ai/v1/models",
            models = listOf(ClaudeProviderModel("kimi-k2-0905-preview", L.claude.providers.kimiK2, context_length = 262_144))
        )
    ),
    Preset(
        "DeepSeek", L.claude.providers.deepseeksAnthropicCompatibleEndpoint,
        ClaudeProvider(
            name = "DeepSeek", base_url = "https://api.deepseek.com/anthropic", models_url = "https://api.deepseek.com/models",
            models = listOf(
                ClaudeProviderModel("deepseek-chat", L.claude.providers.deepseekChat, context_length = 128_000),
                ClaudeProviderModel("deepseek-reasoner", L.claude.providers.deepseekReasoner, context_length = 128_000)
            )
        )
    ),
    Preset(
        "Z.ai", L.claude.providers.zAiGlmAnthropicCompatibleEndpoint,
        ClaudeProvider(
            name = "Z.ai", base_url = "https://api.z.ai/api/anthropic", models_url = "https://api.z.ai/api/paas/v4/models",
            models = listOf(ClaudeProviderModel("glm-4.6", "GLM 4.6", context_length = 200_000))
        )
    ),
    Preset(L.claude.providers.custom, L.claude.providers.anyEndpointThatSpeaksTheAnthropic, ClaudeProvider())
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

    Text(L.claude.providers.otherAnthropicCompatibleApis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Text(
        L.claude.providers.openrouterKimiDeepseekZAiTheir,
        fontSize = 11.5.sp, color = muted
    )
    // Claude Code speaks only Anthropic's API: other providers work better in OpenCode
    val oc = ai.antigem.ui.components.OpenCodeAccent
    Surface(shape = RoundedCornerShape(12.dp), color = oc.copy(alpha = 0.08f), border = BorderStroke(1.dp, oc.copy(alpha = 0.35f))) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L.claude.providers.thirdPartyModelsWorkBetterIn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = oc)
            Text(
                L.claude.providers.claudeCodeOnlySpeaksAnthropicsApi,
                fontSize = 11.5.sp, color = muted
            )
        }
    }

    settingsEndpoint?.let { endpoint ->
        Surface(shape = RoundedCornerShape(12.dp), color = QuotaAmber.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaAmber.copy(alpha = 0.4f))) {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L.claude.providers.settingsJsonSendsEveryChatTo(endpoint), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    L.claude.providers.thenEvenChatsOnClaudesModels,
                    fontSize = 11.5.sp, color = muted
                )
                TextButton(enabled = !importing, onClick = {
                    importing = true
                    backend.importSettingsProvider { err ->
                        importing = false
                        Toast.makeText(context, err ?: L.claude.providers.movedIntoAProvider, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (importing) L.claude.providers.moving else L.claude.providers.moveIntoAProvider, color = ClaudeAccent) }
            }
        }
    }
    if (conflicts.isNotEmpty() && providers.isNotEmpty()) {
        Text(
            L.claude.providers.settingsJsonSetsValueProviderChats(conflicts.joinToString(", ")),
            fontSize = 11.sp, color = muted
        )
    }

    // quick start
    Text(L.claude.providers.addAProvider, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESETS.filter { it.label != "OpenRouter" || providers.none { p -> p.openrouter } }.forEach { preset ->
            OutlinedButton(onClick = { editing = preset.provider }) { Text("+ ${preset.label}") }
        }
    }

    if (providers.isEmpty()) {
        ClaudeCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(L.claude.providers.noCustomProvidersYet, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(L.claude.providers.pickAPresetOrAddYour, fontSize = 11.5.sp, color = muted)
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
            title = { Text(L.claude.providers.savedName(p.name), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    L.claude.providers.newChatsAndChatsYouReopen(p.name),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                TextButton(enabled = !restarting, onClick = {
                    restarting = true
                    backend.restartProviderChats(p.id) { restarted, busy, err ->
                        restarting = false
                        savedProvider = null
                        val msg = err?.let { L.claude.providers.couldNotRestartValue(it) } ?: buildString {
                            append(if (restarted == 0) L.claude.providers.noRunningChatNeededARestart else L.claude.providers.restartedRestartedChatValue(restarted, if (restarted == 1) "" else "s"))
                            if (busy > 0) append(L.claude.providers.busyStillReplyingRestartValueWhen(busy, if (busy == 1) "it" else "them"))
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (restarting) L.common.restarting else L.claude.providers.restartIdleChatsNow, color = ClaudeAccent) }
            },
            dismissButton = { TextButton(onClick = { savedProvider = null }) { Text(L.common.later) } }
        )
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${p.name}?", fontWeight = FontWeight.Bold) },
            text = { Text(L.claude.providers.itsKeyHeadersAndModelsAre, fontSize = 13.5.sp) },
            confirmButton = { TextButton(onClick = { backend.deleteProvider(p.id); toDelete = null }) { Text(L.common.remove, color = QuotaRed) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(L.common.cancel) } }
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
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, L.common.edit) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, L.common.remove, tint = QuotaRed.copy(alpha = 0.8f)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            p.api_key_hint?.let { Badge(L.claude.providers.keyValue(it)) }
            Badge(if (p.auth_mode == "x-api-key") "x-api-key" else "Bearer token")
            Badge(if (p.thinking_budget > 0) L.claude.providers.thinkingTokenlabel(tokenLabel(p.thinking_budget.toLong())) else L.claude.providers.noThinkingBudget)
            if (p.headers.isNotEmpty()) Badge("${p.headers.size} header${if (p.headers.size == 1) "" else "s"}")
            if (p.extra_env.isNotEmpty()) Badge("${p.extra_env.size} env")
            if (p.timeout_sec > 0) Badge(L.claude.providers.timeoutSecSTimeout(p.timeout_sec))
            if (p.disable_betas) Badge(L.claude.providers.noBetas)
        }
        Spacer(Modifier.size(8.dp))
        Text("Models (${p.models.size})", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
        if (p.models.isEmpty()) {
            Text(L.claude.providers.noneYetOpenEditToAdd, fontSize = 11.5.sp, color = muted)
        }
        (if (expanded) p.models else p.models.take(5)).forEach { m ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp)
                Text(
                    listOfNotNull(m.id.takeIf { !m.name.isNullOrBlank() }, m.contextLabel(p) ?: L.claude.providers.contextUnknown, m.thinkingLabel(p)).joinToString(" · "),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = muted
                )
            }
        }
        if (p.models.size > 5) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) L.claude.providers.showFewer else L.claude.providers.showAllModelscount(p.models.size), color = ClaudeAccent, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !testing, onClick = onTest) {
                Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(16.dp), tint = ClaudeAccent)
                Spacer(Modifier.width(6.dp))
                Text(L.claude.providers.testConnection, color = ClaudeAccent)
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
                    Text(L.claude.providers.connectedLatencyMsMs(r.latency_ms), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text(L.claude.providers.modelAnsweredValue(r.model, if (r.reply.isNotBlank()) ": \"${r.reply.take(60)}\"" else ""), fontSize = 11.5.sp)
                } else {
                    Text(L.claude.providers.connectionFailed, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
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
        if (i <= 0) return emptyMap<String, String>() to L.claude.providers.whatLineMustLookLikeName(what, line, separator, if (separator == ':') " value" else "value")
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
        val (h, hErr) = parsePairs(headers, ':', L.claude.providers.headers)
        if (hErr != null) return null to hErr
        val (e, eErr) = parsePairs(env, '=', L.claude.providers.environment)
        if (eErr != null) return null to eErr
        val budget = if (sendThinking) budgetText.trim().toIntOrNull() else 0
        if (budget == null || (budget != 0 && budget < 1024)) return null to L.claude.providers.theThinkingBudgetMustBeA
        val timeout = if (timeoutText.isBlank()) 0 else timeoutText.trim().toIntOrNull() ?: return null to L.claude.providers.theTimeoutMustBeANumber
        val retriesRaw = retriesText.trim().toIntOrNull()
        if (retriesText.isNotBlank() && (retriesRaw == null || retriesRaw !in 0..10)) return null to L.claude.providers.retriesMustBeANumberFrom
        // 0 (blank) = the default, -1 = never retry
        val retries: Int = when {
            retriesText.isBlank() -> 0
            retriesRaw == 0 -> -1
            else -> retriesRaw ?: 0
        }
        val defCtx = parseTokens(defaultCtxText) ?: return null to L.claude.providers.theDefaultContextWindowMustBe
        val defOut = parseTokens(defaultOutText) ?: return null to L.claude.providers.theDefaultMaxOutputMustBe
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
                        L.claude.providers.theServerListedEntriesEntriesBut(raw.entries, (if (openRouter && freeOnly) L.claude.providers.orFilteredOutByOnlyFree else ""))
                    } else {
                        L.claude.providers.theServerAnsweredValueButNo(raw?.status?.takeIf { it > 0 }?.let { " (HTTP $it)" }.orEmpty())
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
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, L.common.close) }
                    Text(if (isNew) L.claude.providers.addProvider else L.claude.providers.editName(initial.name), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
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
                    ) { Text(if (saving) L.common.saving else L.common.save, color = ClaudeAccent, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    note?.let { Text(it, fontSize = 12.sp, color = muted) }
                    error?.let { Text(it, fontSize = 12.5.sp, color = QuotaRed, fontWeight = FontWeight.SemiBold) }

                    // ---- connection
                    EditorSection(L.claude.providers.connection, L.claude.providers.whereTheChatSendsItsRequests) {
                        OutlinedTextField(name, { name = it }, label = { Text(L.common.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            baseUrl, { baseUrl = it }, label = { Text(L.claude.providers.baseUrl) }, singleLine = true,
                            placeholder = { Text("https://api.example.com/anthropic") }, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(L.claude.providers.claudeCodeCallsBaseUrlV1) }
                        )
                        OutlinedTextField(
                            key, { key = it }, label = { Text(L.common.apiKey) }, singleLine = true,
                            placeholder = { Text(if (isNew) L.claude.providers.sk else "unchanged (${initial.api_key_hint ?: "saved"})") },
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                        )
                        Text(L.claude.providers.howTheKeyIsSent, fontSize = 12.sp, color = muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("bearer" to "Bearer token", "x-api-key" to L.claude.providers.xApiKeyHeader).forEach { (mode, label) ->
                                FilterChip(
                                    selected = authMode == mode, onClick = { authMode = mode }, label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                                )
                            }
                        }
                        Text(
                            if (authMode == "bearer") "Authorization: Bearer <key> (ANTHROPIC_AUTH_TOKEN). Used by most providers."
                            else L.claude.providers.xApiKeyKeyAnthropicApi,
                            fontSize = 11.sp, color = muted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = !testing && baseUrl.isNotBlank(), onClick = { runTest(models.firstOrNull()?.id ?: newModel.trim()) }) {
                                Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(L.claude.providers.testConnection)
                            }
                            Spacer(Modifier.width(10.dp))
                            if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                        }
                        Text(
                            L.claude.providers.sendsOneShortMessageToValue(models.firstOrNull()?.id ?: L.claude.providers.theModelIdTypedBelow),
                            fontSize = 11.sp, color = muted
                        )
                        testResult?.let { TestResultView(it) }
                    }

                    // ---- models
                    EditorSection("Models (${models.size})", L.claude.providers.theseAppearInTheChatsModel) {
                        if (!openRouter) {
                            OutlinedTextField(
                                modelsUrl, { modelsUrl = it }, label = { Text(L.claude.providers.modelsUrlOptional) }, singleLine = true,
                                placeholder = { Text("https://api.example.com/v1/models") }, modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = freeOnly, onCheckedChange = { freeOnly = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                                Spacer(Modifier.width(10.dp))
                                Text(L.claude.providers.onlyFreeModelsThatCanCall, fontSize = 12.5.sp)
                            }
                        }
                        val canFetch = openRouter || modelsUrl.isNotBlank()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(enabled = canFetch && !fetching && baseUrl.isNotBlank(), onClick = { runFetch() }) {
                                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(L.claude.providers.fetchTheProvidersList)
                            }
                            Spacer(Modifier.width(4.dp))
                            if (fetching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                            fetchRaw?.let { raw ->
                                IconButton(onClick = { showRaw = true }) {
                                    Icon(Icons.Outlined.Code, L.claude.providers.showTheRawResponse, tint = if (raw.status in 200..299) ClaudeAccent else QuotaAmber)
                                }
                            }
                        }
                        TextButton(enabled = canFetch, onClick = { showMapping = true }) {
                            Text(
                                if (mapping == null) L.claude.providers.fieldMappingPagination else L.claude.providers.fieldMappingPaginationCustomised,
                                color = ClaudeAccent, fontSize = 12.sp
                            )
                        }
                        fetchError?.let { msg ->
                            Surface(shape = RoundedCornerShape(10.dp), color = QuotaRed.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaRed.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(L.claude.providers.couldNotFetchTheModels, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(msg, fontSize = 11.5.sp)
                                }
                            }
                        }
                        fetchNote?.let { msg ->
                            Surface(shape = RoundedCornerShape(10.dp), color = QuotaAmber.copy(alpha = 0.1f), border = BorderStroke(1.dp, QuotaAmber.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(L.claude.providers.noModelsToAdd, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(msg, fontSize = 11.5.sp)
                                }
                            }
                        }
                        if (!canFetch) Text(L.claude.providers.setAModelsUrlToFetch, fontSize = 11.sp, color = muted)
                        if (isNew && key.isBlank() && canFetch) Text(L.claude.providers.enterTheApiKeyFirst, fontSize = 11.sp, color = muted)

                        models.forEach { m ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { editingModel = m }.padding(vertical = 4.dp)) {
                                    Text(m.name?.takeIf { it.isNotBlank() } ?: m.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            m.id.takeIf { !m.name.isNullOrBlank() },
                                            m.contextLabel(draft) ?: L.claude.providers.contextUnknownTapToSet,
                                            m.thinkingLabel(draft),
                                            L.claude.providers.canReason.takeIf { m.can_think }
                                        ).joinToString(" · "),
                                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                        color = if (m.contextLabel(draft) != null) muted else QuotaAmber, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(enabled = !testing, onClick = { runTest(m.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    Text(L.claude.providers.test, color = ClaudeAccent, fontSize = 12.sp)
                                }
                                IconButton(onClick = { editingModel = m }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Edit, L.claude.providers.editModel, Modifier.size(16.dp)) }
                                IconButton(onClick = { models = models - m }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, L.claude.providers.removeModel, Modifier.size(16.dp)) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newModel, onValueChange = { newModel = it }, singleLine = true, modifier = Modifier.weight(1f),
                                placeholder = { Text(L.claude.providers.modelIdEGKimiK2, fontSize = 12.sp) },
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            IconButton(
                                enabled = newModel.isNotBlank() && newModel.none { it.isWhitespace() } && models.none { it.id == newModel.trim() },
                                onClick = {
                                    models = models + ClaudeProviderModel(newModel.trim())
                                    newModel = ""
                                }
                            ) { Icon(Icons.Outlined.Add, L.claude.providers.addModel, tint = ClaudeAccent) }
                        }
                    }

                    // ---- behaviour
                    EditorSection(L.claude.providers.behavior, L.claude.providers.howChatsOnThisProviderRun) {
                        ModelIdField(
                            value = smallModel, onValueChange = { smallModel = it }, models = models,
                            label = L.claude.providers.backgroundModelOptional, placeholder = L.claude.providers.titlesSummariesDefaultTheChatsModel
                        )
                        Text(
                            L.claude.providers.sonnetOpusAndSubagentRequestsAre,
                            fontSize = 11.sp, color = muted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(L.claude.providers.sendAThinkingBudget, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    L.claude.providers.offThinkingInTheChatNever,
                                    fontSize = 11.sp, color = muted
                                )
                            }
                            Switch(checked = sendThinking, onCheckedChange = { sendThinking = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                        }
                        if (sendThinking) {
                            OutlinedTextField(
                                budgetText, { budgetText = it.filter(Char::isDigit) }, label = { Text(L.claude.providers.thinkingBudgetTokens) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            L.claude.providers.thisIsTheProvidersDefaultAny,
                            fontSize = 11.sp, color = muted
                        )
                        Text(L.claude.providers.defaultsForModelsWithoutTheirOwn, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        OutlinedTextField(
                            defaultCtxText, { defaultCtxText = it }, label = { Text(L.claude.providers.defaultContextWindow) }, singleLine = true,
                            isError = parseTokens(defaultCtxText) == null, placeholder = { Text(L.claude.providers.eG128kBlankUnknown) }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            defaultOutText, { defaultOutText = it }, label = { Text(L.claude.providers.defaultMaxOutput) }, singleLine = true,
                            isError = parseTokens(defaultOutText) == null, placeholder = { Text(L.claude.providers.eG8kBlankUnknown) }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            timeoutText, { timeoutText = it.filter(Char::isDigit) }, label = { Text(L.claude.providers.requestTimeoutSecondsOptional) }, singleLine = true,
                            placeholder = { Text(L.claude.providers.default) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            retriesText, { retriesText = it.filter(Char::isDigit) }, label = { Text(L.claude.providers.retriesWhenARequestFails0) }, singleLine = true,
                            placeholder = { Text("default: 3") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(L.claude.providers.rateLimitsRejectedKeysAndNetwork) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(L.claude.providers.disableExperimentalBetas, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(L.claude.providers.turnOnIfTheProviderAnswers, fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = disableBetas, onCheckedChange = { disableBetas = it }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                        }
                    }

                    // ---- advanced
                    EditorSection(L.claude.providers.advanced, L.claude.providers.extraRequestHeadersAndEnvironment) {
                        OutlinedTextField(
                            headers, { headers = it }, label = { Text(L.claude.providers.headersOnePerLine) }, minLines = 2, maxLines = 6,
                            placeholder = { Text("X-Title: AntiGem\nHTTP-Referer: https://example.com") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            env, { env = it }, label = { Text("Environment (NAME=value, one per line)") }, minLines = 2, maxLines = 6,
                            placeholder = { Text("CLAUDE_CODE_MAX_OUTPUT_TOKENS=8192") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth()
                        )
                        Text(L.claude.providers.theseWinOverEverythingAboveSo, fontSize = 11.sp, color = muted)
                    }
                    Text(L.claude.providers.changesApplyTheNextTimeA, fontSize = 11.sp, color = muted)
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
    val inherited = if (provider.thinking_budget > 0) "thinking ${tokenLabel(provider.thinking_budget.toLong())}" else L.claude.providers.noThinkingBudget2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.id, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(L.claude.providers.displayNameOptional) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    ctx, { ctx = it }, label = { Text(L.claude.providers.contextWindowTokens) }, singleLine = true, isError = ctxValue == null,
                    placeholder = { Text(if (provider.default_context > 0) "default: ${tokenLabel(provider.default_context)}" else L.claude.providers.eG128k2000001m) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(L.claude.providers.usedByTheContextChipAnd) }
                )
                OutlinedTextField(
                    out, { out = it }, label = { Text(L.claude.providers.maxOutputTokensOptional) }, singleLine = true, isError = outValue == null,
                    placeholder = { Text(if (provider.default_max_output > 0) "default: ${tokenLabel(provider.default_max_output)}" else L.claude.providers.eG8k) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(L.common.thinking, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (model.can_think) Text(L.claude.providers.theProvidersModelListSaysThis, fontSize = 11.sp, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to L.claude.providers.inherit, "off" to L.claude.providers.neverSend, "budget" to L.claude.providers.customBudget).forEach { (mode, label) ->
                        FilterChip(
                            selected = thinkMode == mode, onClick = { thinkMode = mode }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                        )
                    }
                }
                if (thinkMode == "") Text(L.claude.providers.inheritsTheProviderInherited(inherited), fontSize = 11.sp, color = muted)
                if (thinkMode == "off") Text(L.claude.providers.thinkingInTheChatNeverSends, fontSize = 11.sp, color = muted)
                if (thinkMode == "budget") {
                    OutlinedTextField(
                        budgetText, { budgetText = it.filter(Char::isDigit) }, label = { Text(L.claude.providers.thinkingBudgetTokens) }, singleLine = true,
                        isError = !budgetOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(L.claude.providers.atLeast1024SentWhenThinking) }
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
            }) { Text(L.common.ok, color = ClaudeAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
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
                    OutlinedTextField(query, { query = it }, placeholder = { Text(L.common.search) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row {
                    TextButton(onClick = { picked = picked + shown.map { it.id }.filter { it !in existing } }) { Text(L.common.selectAll) }
                    TextButton(onClick = { picked = picked - shown.map { it.id }.toSet() }) { Text(L.claude.providers.none) }
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
                                    listOfNotNull(m.id, m.contextLabel(), L.claude.providers.canReason.takeIf { m.can_think }, "added".takeIf { added }).joinToString(" · "),
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
                Text(if (picked.isEmpty()) L.claude.providers.updateSizes else L.claude.providers.addPickedcount(picked.size), color = ClaudeAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
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
                    IconButton(onClick = { open = !open }) { Icon(Icons.Filled.ArrowDropDown, L.claude.providers.chooseFromTheProvidersModels) }
                }
            }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(
                text = { Text(L.claude.providers.sameAsTheChatsModel, fontSize = 13.sp) },
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
        raw.status == 0 -> L.claude.providers.noAnswerFromTheServerNetwork
        raw.status !in 200..299 -> L.claude.providers.theServerRefusedTheRequestHttp(raw.status)
        raw.entries == 0 -> L.claude.providers.theServerAnsweredHttpStatusAnd(raw.status)
        raw.kept < raw.entries -> L.claude.providers.theServerListedEntriesEntriesThe(raw.entries, raw.kept)
        else -> L.claude.providers.theServerListedEntriesModelsAnd(raw.entries)
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
        title = { Text(L.claude.providers.rawResponse, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(verdict, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(L.claude.providers.getUrl(raw.url), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                raw.request_headers.forEach { Text(it, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = muted) }
                HorizontalDivider()
                Text(L.claude.providers.httpStatusEntriesListedKeptKept(raw.status, raw.entries, raw.kept), fontSize = 11.sp, color = muted)
                if (raw.pages > 0) {
                    Text(
                        L.claude.providers.pagesPageValueReadPaginationValue2(raw.pages, if (raw.pages == 1) "" else "s", raw.pagination.ifBlank { "none" }),
                        fontSize = 11.sp, color = muted
                    )
                }
                if (raw.paging_error.isNotBlank()) Text(L.claude.providers.stoppedEarlyPagingError(raw.paging_error), fontSize = 11.sp, color = QuotaAmber)
                Text(L.claude.providers.theBodyBelowIsTheFirst, fontSize = 10.5.sp, color = muted)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        Text(raw.body.ifBlank { L.claude.providers.emptyBody } + if (raw.truncated) "\n… (cut)" else "", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, L.common.copied, Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp), tint = ClaudeAccent); Spacer(Modifier.width(6.dp)); Text(L.common.copy, color = ClaudeAccent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
    )
}

/** A text field for a path / name with a dropdown of suggestions (typing anything still works). */
@Composable
private fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<ClaudeRawKey>,
    placeholder: String = L.claude.providers.detectAutomatically,
    autoLabel: String = L.claude.providers.detectAutomatically2
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value, onValueChange, label = { Text(label) }, singleLine = true, placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton(onClick = { open = !open }) { Icon(Icons.Filled.ArrowDropDown, L.claude.providers.choose) } }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(autoLabel, fontSize = 13.sp) }, onClick = { onValueChange(""); open = false })
            options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(o.path.ifBlank { L.claude.providers.topLevel }, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
        title = { Text(L.claude.providers.fieldMappingPagination2, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (raw == null || lists.isEmpty()) L.claude.providers.fetchOnceToGetSuggestionsFrom
                    else L.claude.providers.suggestionsComeFromTheLastAnswer,
                    fontSize = 11.5.sp, color = muted
                )
                Text(L.claude.providers.whereTheModelsAre, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SuggestField(
                    m.list_path, { m = m.copy(list_path = it) }, L.claude.providers.listPath,
                    lists.map { ClaudeRawKey(it.path, L.claude.providers.countItems(it.count) + if (it.strings) L.claude.providers.idsAsPlainText else "") }
                )
                Text(L.claude.providers.fieldsOfOneModel, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                SuggestField(m.id_field, { m = m.copy(id_field = it) }, L.claude.providers.id, itemKeys)
                SuggestField(m.name_field, { m = m.copy(name_field = it) }, L.claude.providers.displayName, itemKeys, "none")
                SuggestField(m.context_field, { m = m.copy(context_field = it) }, L.claude.providers.contextWindow, itemKeys, "none")
                SuggestField(m.max_output_field, { m = m.copy(max_output_field = it) }, L.claude.providers.maxOutput, itemKeys, "none")
                OutlinedTextField(
                    m.strip_prefix, { m = m.copy(strip_prefix = it) }, label = { Text(L.claude.providers.removeFromTheStartOfEvery) },
                    singleLine = true, placeholder = { Text(L.claude.providers.eGModels) }, modifier = Modifier.fillMaxWidth()
                )

                Text(L.claude.providers.pagination, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to L.claude.providers.detect, "none" to L.claude.providers.none, "cursor" to L.claude.providers.cursor, "token" to L.claude.providers.pageToken, "next_url" to L.claude.providers.nextUrl).forEach { (mode, label) ->
                        FilterChip(
                            selected = m.pagination == mode, onClick = { m = m.copy(pagination = mode) }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ClaudeAccent.copy(alpha = 0.2f))
                        )
                    }
                }
                Text(
                    when (m.pagination) {
                        "" -> L.claude.providers.detectedFromTheFirstAnswerHas((raw?.takeIf { it.pages > 0 }?.let { " Last fetch: ${it.pages} page(s), ${it.pagination.ifBlank { "none" }}." } ?: ""))
                        "none" -> L.claude.providers.onlyTheFirstPageIsRead
                        "cursor" -> L.claude.providers.whileTheFlagIsTrueThe
                        "token" -> L.claude.providers.theNextRequestSendsTheToken
                        else -> L.claude.providers.theNextRequestGoesToThe
                    },
                    fontSize = 11.sp, color = muted
                )
                if (m.pagination == "cursor") {
                    SuggestField(m.more_field, { m = m.copy(more_field = it) }, L.claude.providers.fieldThatSaysMorePagesExist, topKeys, "has_more")
                }
                if (m.pagination == "cursor" || m.pagination == "token" || m.pagination == "next_url") {
                    SuggestField(
                        m.next_field, { m = m.copy(next_field = it) },
                        when (m.pagination) { "cursor" -> L.claude.providers.fieldWithTheLastId; "token" -> L.claude.providers.fieldWithTheNextToken; else -> L.claude.providers.fieldWithTheNextUrl },
                        topKeys, "default"
                    )
                }
                if (m.pagination == "cursor" || m.pagination == "token") {
                    OutlinedTextField(
                        m.page_param, { m = m.copy(page_param = it) }, label = { Text(L.claude.providers.queryParameterToSendItIn) },
                        singleLine = true, placeholder = { Text(if (m.pagination == "cursor") "after_id" else "page_token") }, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (m.pagination != "none") {
                    OutlinedTextField(
                        sizeText, { sizeText = it.filter(Char::isDigit); m = m.copy(page_size = sizeText.toIntOrNull() ?: 0) },
                        label = { Text(L.claude.providers.pageSizeOptional) }, singleLine = true, placeholder = { Text(L.claude.providers.providersDefault) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                    )
                    if (m.page_size > 0) {
                        OutlinedTextField(
                            m.limit_param, { m = m.copy(limit_param = it) }, label = { Text(L.claude.providers.parameterNameForThePageSize) },
                            singleLine = true, placeholder = { Text(L.claude.providers.limit) }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(L.claude.providers.atMost100Pages20000, fontSize = 10.5.sp, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(m) }) { Text(L.claude.providers.applyFetch, color = ClaudeAccent) } },
        dismissButton = {
            Row {
                TextButton(onClick = { m = ClaudeModelsMapping(); sizeText = "" }) { Text(L.claude.providers.reset) }
                TextButton(onClick = onDismiss) { Text(L.common.cancel) }
            }
        }
    )
}
