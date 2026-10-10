package com.example.gemini.ui.claude

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeAccountManager
import com.example.gemini.data.agent.claude.ClaudeStatus
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.ClaudeConfigManager
import com.example.gemini.data.agent.claude.ClaudeMcpAddRequest
import com.example.gemini.data.agent.claude.ClaudePluginAction
import com.example.gemini.data.agent.claude.ClaudePreferences
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.components.ClaudeAccent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pages of the Claude Code settings. */
enum class ClaudeSettingsPage(val title: String, val subtitle: String) {
    ACCOUNT("Account & CLI", "Sign-in, plan usage, install and update Claude Code"),
    DEFAULTS("Chat defaults", "Model, effort, thinking, permission mode, output style"),
    PERMISSIONS("Permission rules", "What Claude may do without asking"),
    MCP("MCP servers", "Tools Claude can call"),
    PLUGINS("Plugins", "Installed plugins, catalog and marketplaces"),
    MEMORY("Memory", "Instructions (CLAUDE.md) and the notes Claude saved"),
    ADVANCED("Advanced", "Edit ~/.claude/settings.json directly")
}

/** Holder passed from the ViewModel into settings. */
class ClaudeSettingsDeps(
    val account: ClaudeAccountManager,
    val config: ClaudeConfigManager,
    val backend: ClaudeChatBackend,
    val prefs: ClaudePreferences
)

private val prettyJson = Json { prettyPrint = true }

/** Claude Code cards for the main settings menu (each opens one page). */
@Composable
fun ClaudeSettingsGroup(deps: ClaudeSettingsDeps, onOpen: (ClaudeSettingsPage) -> Unit) {
    val status by deps.account.status.collectAsState()
    val settings by deps.config.settings.collectAsState()
    val mcp by deps.config.mcpServers.collectAsState()
    LaunchedEffect(Unit) {
        if (status == null) deps.account.refreshStatus()
        if (settings == null) deps.config.loadSettings()
    }
    val state by deps.account.state.collectAsState()
    val auth = status?.auth
    val accountBadge = when (state) {
        ClaudeStatus.READY -> auth?.subscriptionType?.let { com.example.gemini.ui.agents.claudePlanLabel(it) } ?: "Signed in"
        ClaudeStatus.SIGNED_OUT -> "Signed out"
        else -> state.label
    }
    val accountColor = state.dotColor
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClaudeNavCard(
            Icons.Outlined.AccountCircle,
            ClaudeSettingsPage.ACCOUNT.title,
            listOfNotNull(auth?.email, status?.version).joinToString(" · ").ifBlank { ClaudeSettingsPage.ACCOUNT.subtitle },
            badge = accountBadge,
            badgeColor = accountColor
        ) { onOpen(ClaudeSettingsPage.ACCOUNT) }
        ClaudeNavCard(Icons.Outlined.Tune, ClaudeSettingsPage.DEFAULTS.title, ClaudeSettingsPage.DEFAULTS.subtitle) { onOpen(ClaudeSettingsPage.DEFAULTS) }
        val allowCount = settings?.let { deps.config.rules("allow").size } ?: 0
        ClaudeNavCard(Icons.Outlined.Security, ClaudeSettingsPage.PERMISSIONS.title, ClaudeSettingsPage.PERMISSIONS.subtitle,
            badge = allowCount.takeIf { it > 0 }?.let { "$it allowed" }) { onOpen(ClaudeSettingsPage.PERMISSIONS) }
        ClaudeNavCard(Icons.Outlined.Hub, ClaudeSettingsPage.MCP.title, ClaudeSettingsPage.MCP.subtitle,
            badge = mcp.size.takeIf { it > 0 }?.let { "$it servers" }) { onOpen(ClaudeSettingsPage.MCP) }
        ClaudeNavCard(Icons.Outlined.Extension, ClaudeSettingsPage.PLUGINS.title, ClaudeSettingsPage.PLUGINS.subtitle) { onOpen(ClaudeSettingsPage.PLUGINS) }
        ClaudeNavCard(Icons.Outlined.Description, ClaudeSettingsPage.MEMORY.title, ClaudeSettingsPage.MEMORY.subtitle) { onOpen(ClaudeSettingsPage.MEMORY) }
        ClaudeNavCard(Icons.Outlined.Code, ClaudeSettingsPage.ADVANCED.title, ClaudeSettingsPage.ADVANCED.subtitle) { onOpen(ClaudeSettingsPage.ADVANCED) }
    }
}

/** One Claude settings page (the settings top bar shows its title); [onBack] returns to the main settings menu. */
@Composable
fun ClaudeSettingsScreen(deps: ClaudeSettingsDeps, page: ClaudeSettingsPage, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        deps.config.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        deps.account.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // the plugin catalog is a lazy list of its own (thousands of entries); every other page scrolls as a whole
    if (page == ClaudeSettingsPage.PLUGINS) {
        PluginsPage(deps)
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (page) {
            ClaudeSettingsPage.ACCOUNT -> AccountPage(deps)
            ClaudeSettingsPage.DEFAULTS -> DefaultsPage(deps)
            ClaudeSettingsPage.PERMISSIONS -> PermissionsPage(deps)
            ClaudeSettingsPage.MCP -> McpPage(deps)
            ClaudeSettingsPage.PLUGINS -> Unit
            ClaudeSettingsPage.MEMORY -> MemoryPage(deps)
            ClaudeSettingsPage.ADVANCED -> AdvancedPage(deps)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

private fun activeProjectPath(): String? =
    com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path?.removePrefix("file://")?.takeIf { it.isNotBlank() }

// ======================================================================== Account & CLI

@Composable
private fun AccountPage(deps: ClaudeSettingsDeps) {
    val status by deps.account.status.collectAsState()
    val statusError by deps.account.statusError.collectAsState()
    val usage by deps.account.usage.collectAsState()
    val busy by deps.account.isBusy.collectAsState()
    val job by deps.config.cliJob.collectAsState()
    val state by deps.account.state.collectAsState()
    var showLogin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        deps.account.refreshStatus()
        deps.account.refreshUsage(force = true)
    }

    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Claude Code CLI", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { deps.account.refreshStatus() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
        when (state) {
            ClaudeStatus.CHECKING -> Text("Checking…", fontSize = 12.5.sp)
            ClaudeStatus.BRIDGE_OFFLINE, ClaudeStatus.BRIDGE_OUTDATED, ClaudeStatus.ERROR -> {
                Text(state.label, color = state.dotColor, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                statusError?.let { Hint(it) }
            }
            ClaudeStatus.NOT_INSTALLED -> Text("Claude Code not found on this device.", fontSize = 12.5.sp, color = state.dotColor)
            else -> {
                Text(status?.version ?: "Installed", fontSize = 13.sp)
                status?.bin_path?.let { Hint(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = job?.running == true
            if (state == ClaudeStatus.NOT_INSTALLED) {
                Button(onClick = { deps.config.startCliJob("install") }, enabled = !running,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Install Claude Code") }
            } else if (status?.installed == true) {
                OutlinedButton(onClick = { deps.config.startCliJob("update") }, enabled = !running) { Text("Check for updates") }
            }
            if (running) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
        job?.let { j ->
            Spacer(Modifier.height(8.dp))
            Text(
                "${j.kind}: " + if (j.running) "running…" else "finished (exit ${j.exit_code})",
                fontSize = 12.sp,
                color = if (!j.running && j.exit_code != 0) QuotaRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    j.log.lines().takeLast(30).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                )
            }
        }
    }

    ClaudeCard {
        Text("Account", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val auth = status?.auth
        if (auth?.loggedIn == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(ClaudeAccent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text((auth.email?.firstOrNull()?.uppercaseChar() ?: '✻').toString(), color = ClaudeAccent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(auth.email ?: "Signed in", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Hint(listOfNotNull(auth.subscriptionType?.let { "Plan: ${it.replaceFirstChar { c -> c.uppercase() }}" },
                        auth.authMethod?.let { "via $it" }).joinToString(" · "))
                }
                OutlinedButton(onClick = { deps.account.logout() }, enabled = !busy) { Text("Sign out", color = QuotaRed) }
            }
        } else {
            Hint("Sign in with your Claude subscription or an Anthropic Console account.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showLogin = true }, enabled = !state.isBlocking && state != ClaudeStatus.CHECKING && (!busy || state == ClaudeStatus.SIGNING_IN),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) {
                Text(if (state == ClaudeStatus.SIGNING_IN) "Signing in…" else "Sign in")
            }
        }
    }

    val usageRefreshing by deps.account.isRefreshingUsage.collectAsState()
    val usageError by deps.account.usageError.collectAsState()
    val usageUpdatedAt by deps.account.usageUpdatedAt.collectAsState()
    if (usage != null || usageRefreshing || usageError != null) {
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Plan usage", fontWeight = FontWeight.Bold)
                    Hint(
                        when {
                            usageRefreshing -> "Updating…"
                            usageUpdatedAt > 0 -> "Updated " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(usageUpdatedAt))
                            else -> ""
                        }
                    )
                }
                if (usageRefreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                else IconButton(onClick = { deps.account.refreshUsage(force = true) }) { Icon(Icons.Outlined.Refresh, "Refresh usage") }
            }
            usageError?.let { Text("Could not update: $it", fontSize = 12.sp, color = QuotaRed) }
            val u = usage ?: return@ClaudeCard
            val rl = u.rate_limits
            if (rl == null || !u.rate_limits_available) {
                Hint("Plan limits are not available for this account (API billing has no 5-hour / weekly limits).")
            } else {
                rl.five_hour?.let { UsageBar("5-hour limit", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at)) }
                Spacer(Modifier.height(8.dp))
                rl.seven_day?.let { UsageBar("Weekly limit (all models)", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at)) }
                rl.seven_day_opus?.let {
                    Spacer(Modifier.height(8.dp))
                    UsageBar("Weekly limit (Opus)", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at))
                }
                rl.seven_day_sonnet?.let {
                    Spacer(Modifier.height(8.dp))
                    UsageBar("Weekly limit (Sonnet)", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at))
                }
            }
        }
    }

    if (showLogin) ClaudeLoginDialog(deps.account) { showLogin = false }
}

// ======================================================================== Chat defaults

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    val models by deps.backend.modelInfos.collectAsState()
    val styles by deps.backend.outputStyles.collectAsState()
    val voiceLang by deps.prefs.voiceLanguage.collectAsState()
    LaunchedEffect(Unit) {
        deps.config.loadSettings()
        if (models.isEmpty()) deps.backend.refreshInfo(force = false)
    }
    val s = settings ?: JsonObject(emptyMap())
    fun str(key: String) = (s[key] as? JsonPrimitive)?.content
    val model = str("model") ?: "default"
    val effort = str("effortLevel")
    val thinking = (s["alwaysThinkingEnabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
    val style = str("outputStyle") ?: "default"
    // no default in settings.json: new chats start in Auto
    val mode = deps.config.defaultMode()?.takeIf { it != "bypassPermissions" } ?: "auto"

    Hint("These apply to new Claude chats (saved in ~/.claude/settings.json, shared with the terminal CLI).")

    ClaudeCard {
        SectionTitle("Default model")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = models.map { it.value to it.displayName.ifBlank { it.value } }.ifEmpty { listOf("default" to "Default") }
            options.forEach { (value, label) ->
                ControlChip(label, highlighted = value == model, color = if (value == model) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    onClick = { deps.config.setValue("model", if (value == "default") null else JsonPrimitive(value)) })
            }
        }
        val levels = models.find { it.value == model }?.supportedEffortLevels ?: models.firstOrNull()?.supportedEffortLevels.orEmpty()
        if (levels.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Default effort")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<String?>(null) + levels).forEach { level ->
                    ControlChip(effortLabel(level), highlighted = level == effort,
                        color = if (level == effort) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        onClick = { deps.config.setValue("effortLevel", level?.let { JsonPrimitive(it) }) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Thinking")
                Hint("Reason before answering")
            }
            Switch(checked = thinking, onCheckedChange = { deps.config.setValue("alwaysThinkingEnabled", JsonPrimitive(it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
        }
    }

    ClaudeCard {
        SectionTitle("Default permission mode")
        Spacer(Modifier.height(6.dp))
        ClaudeChatBackend.PERMISSION_MODES.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { deps.config.setDefaultMode(value) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.RadioButton(selected = value == mode, onClick = { deps.config.setDefaultMode(value) })
                Column {
                    Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Hint(permissionModeDescription(value))
                }
            }
        }
    }

    if (styles.isNotEmpty()) {
        ClaudeCard {
            SectionTitle("Output style")
            Hint("How Claude writes its replies")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                styles.forEach { st ->
                    ControlChip(st, highlighted = st.equals(style, true),
                        color = if (st.equals(style, true)) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        onClick = { deps.config.setValue("outputStyle", if (st == "default") null else JsonPrimitive(st)) })
                }
            }
        }
    }

    ClaudeCard {
        SectionTitle("Dictation language")
        Hint("Language code for voice input in Claude chats (e.g. en, es, de, ja)")
        Spacer(Modifier.height(6.dp))
        var lang by remember(voiceLang) { mutableStateOf(voiceLang) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(lang, { lang = it.trim() }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { deps.prefs.setVoiceLanguage(lang.ifBlank { "en" }) }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Save") }
        }
    }
}

// ======================================================================== Permission rules

@Composable
private fun PermissionsPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    LaunchedEffect(Unit) { deps.config.loadSettings() }
    var newRule by remember { mutableStateOf("") }
    var behavior by remember { mutableStateOf("allow") }
    if (settings == null) Hint("Loading settings…")

    // Claude Code's command sandbox: the safe way to have fewer prompts (Bypass is never used by this app)
    val sandbox by deps.config.sandbox.collectAsState()
    LaunchedEffect(Unit) { deps.config.loadSandbox() }
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Sandbox commands")
                Hint(
                    when {
                        sandbox == null -> "Checking whether the sandbox can run on this device…"
                        sandbox?.available == true -> "Run Claude's shell commands in an isolated container (no network, limited files). Commands that stay inside it run without asking."
                        else -> "Not available here: ${sandbox?.reason ?: "unknown reason"}. Auto mode already asks rarely."
                    }
                )
            }
            Switch(
                checked = sandbox?.enabled == true,
                enabled = sandbox?.available == true,
                onCheckedChange = { deps.config.setSandboxEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent)
            )
        }
    }

    Hint("Rules use the CLI syntax: Bash(npm test), Bash(git log *), Read(./src/**), Edit(docs/**), WebFetch(domain:github.com), mcp__server__tool. Deny wins over ask, ask wins over allow.")
    ClaudeCard {
        SectionTitle("Add a rule")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(newRule, { newRule = it }, singleLine = true, placeholder = { Text("Bash(npm run test *)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("allow" to QuotaGreen, "ask" to QuotaAmber, "deny" to QuotaRed).forEach { (b, c) ->
                ControlChip(b.replaceFirstChar { it.uppercase() }, highlighted = behavior == b, color = if (behavior == b) c else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { behavior = b })
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                deps.config.addRule(behavior, newRule)
                newRule = ""
            }, enabled = newRule.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Add") }
        }
    }
    listOf("allow" to "Allowed without asking", "ask" to "Always ask", "deny" to "Never allowed").forEach { (b, title) ->
        val rules = deps.config.rules(b)
        ClaudeCard {
            SectionTitle("$title (${rules.size})")
            if (rules.isEmpty()) Hint("No rules")
            rules.forEach { rule ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rule, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { deps.config.removeRule(b, rule) }) { Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f)) }
                }
            }
        }
    }
}

// ======================================================================== MCP servers

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpPage(deps: ClaudeSettingsDeps) {
    val servers by deps.config.mcpServers.collectAsState()
    val loading by deps.config.mcpLoading.collectAsState()
    val ops by deps.config.mcpOps.collectAsState()
    val addError by deps.config.mcpAddError.collectAsState()
    val cwd = activeProjectPath()
    var addDraft by remember { mutableStateOf<ClaudeMcpAddRequest?>(null) }
    var toDelete by remember { mutableStateOf<com.example.gemini.data.agent.claude.ClaudeMcpServer?>(null) }
    var expandedTools by remember { mutableStateOf(setOf<String>()) }
    var expandedErrors by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { deps.config.loadMcp(cwd) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tools Claude can call", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Hint(if (cwd != null) "Includes servers of the open project ($cwd)." else "Open a project to also see its project-scoped servers.")
        }
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        else IconButton(onClick = { deps.config.loadMcp(cwd) }) { Icon(Icons.Outlined.Refresh, "Refresh") }
    }

    // quick presets, like the Antigravity MCP screen
    val context = LocalContext.current
    val deviceId = remember(context) {
        runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
    }
    val presets = listOf(
        "Browser/Terminal Automation" to ClaudeMcpAddRequest(
            name = "browser_terminal_automation",
            transport = "http",
            url = "http://127.0.0.1:${com.example.gemini.data.remote.AndroidLocalBridgeServer.DEFAULT_PORT}/mcp",
            headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
        )
    )
    Text("Quick presets", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (label, req) ->
            val added = servers.any { it.name == req.name }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !added && req.name !in ops) { addDraft = req }
            ) {
                Row(Modifier.padding(vertical = 8.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (added) "✓ $label" else "+ $label", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    TransportBadge(req.transport)
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ClaudeAccent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, ClaudeAccent.copy(alpha = 0.4f)),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { addDraft = ClaudeMcpAddRequest(name = "") }
        ) {
            Text("+ Custom server", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent, modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp))
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Configured servers (${servers.size})", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (servers.isNotEmpty()) Hint("${servers.sumOf { it.tools.size }} tools")
    }

    // servers being added show up right away with their progress
    ops.filterKeys { name -> servers.none { it.name == name } }.forEach { (name, op) ->
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Hint(if (op.startsWith("Connecting")) "Added · checking that it starts and listing its tools…" else op)
                }
            }
        }
    }

    when {
        servers.isEmpty() && loading && ops.isEmpty() -> ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Checking MCP servers…", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Hint("Claude Code starts each server to list its tools; this can take a few seconds.")
                }
            }
        }
        servers.isEmpty() && ops.isEmpty() -> ClaudeCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Hub, null, tint = ClaudeAccent.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text("No MCP servers yet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Hint("Add one from the presets above or a custom server to give Claude more tools.")
            }
        }
    }

    servers.forEach { srv ->
        val op = ops[srv.name]
        val (statusColor, statusLabel) = when {
            op != null -> ClaudeAccent to op
            srv.status == "connected" -> QuotaGreen to "Connected"
            srv.status == "pending" -> QuotaAmber to "Starting"
            srv.status == "failed" -> QuotaRed to "Failed"
            srv.status == "needs-auth" -> QuotaAmber to "Needs sign-in"
            srv.status == "disabled" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) to "Disabled"
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) to srv.status.ifBlank { "Unknown" }.replaceFirstChar { it.uppercase() }
        }
        val cfg = srv.config
        fun cfgStr(key: String) = (cfg?.get(key) as? JsonPrimitive)?.content
        val transport = cfgStr("type") ?: if (cfgStr("url") != null) "http" else "stdio"
        val target = cfgStr("url") ?: listOfNotNull(cfgStr("command"),
            (cfg?.get("args") as? kotlinx.serialization.json.JsonArray)?.joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }).joinToString(" ").trim()
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(transportColor(transport).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, null, tint = transportColor(transport), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TransportBadge(transport)
                        Badge(statusLabel, statusColor)
                        (srv.scope ?: srv.source)?.let { Hint(scopeLabel(it)) }
                    }
                }
                when {
                    op != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                    srv.scope in setOf("user", "project", "local") -> IconButton(onClick = { toDelete = srv }) {
                        Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f))
                    }
                }
            }
            if (target.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                    Text(if (transport == "stdio") "> $target" else "URL: $target", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            srv.error?.takeIf { it.isNotBlank() }?.let { err ->
                val open = srv.name in expandedErrors
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expandedErrors = if (open) expandedErrors - srv.name else expandedErrors + srv.name },
                    shape = RoundedCornerShape(8.dp), color = QuotaRed.copy(alpha = 0.08f)
                ) {
                    Text(err, fontSize = 11.5.sp, color = QuotaRed, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (srv.tools.isNotEmpty()) {
                val open = srv.name in expandedTools
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { expandedTools = if (open) expandedTools - srv.name else expandedTools + srv.name }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${srv.tools.size} tools", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent, modifier = Modifier.weight(1f))
                    Text(if (open) "Hide" else "Show", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                if (open) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        srv.tools.forEach { tool ->
                            Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Text(tool.name, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    addDraft?.let { draft ->
        AddMcpDialog(
            initial = draft,
            cwd = cwd,
            error = addError?.takeIf { it.first == draft.name }?.second,
            onDismiss = { addDraft = null }
        ) { req ->
            deps.config.addMcp(req, cwd)
            addDraft = null
        }
    }
    toDelete?.let { srv ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${srv.name}?", fontWeight = FontWeight.Bold) },
            text = { Text("Claude will no longer be able to use its tools.", fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deps.config.removeMcp(srv.name, srv.scope, cwd)
                    toDelete = null
                }) { Text("Remove", color = QuotaRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun transportColor(transport: String) = if (transport == "stdio") Color(0xFF7B1FA2) else Color(0xFF1976D2)

@Composable
private fun TransportBadge(transport: String) = Badge(if (transport == "stdio") "Stdio" else transport.uppercase(), transportColor(transport))

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

private fun scopeLabel(scope: String) = when (scope) {
    "user" -> "all projects"
    "local" -> "this project (you)"
    "project" -> "this project (.mcp.json)"
    else -> scope
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMcpDialog(
    initial: ClaudeMcpAddRequest,
    cwd: String?,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (ClaudeMcpAddRequest) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var transport by remember { mutableStateOf(initial.transport) }
    var scope by remember { mutableStateOf(initial.scope) }
    var command by remember { mutableStateOf((listOf(initial.command) + initial.args).filter { it.isNotBlank() }.joinToString(" ")) }
    var url by remember { mutableStateOf(initial.url) }
    var pairs by remember {
        mutableStateOf(
            if (initial.transport == "stdio") initial.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
            else initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "Add MCP server" else "Add ${initial.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let {
                    Surface(shape = RoundedCornerShape(8.dp), color = QuotaRed.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                        Text(it, fontSize = 12.sp, color = QuotaRed, modifier = Modifier.padding(8.dp))
                    }
                }
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Transport", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("stdio", "http", "sse").forEach { t ->
                        ControlChip(t, highlighted = transport == t, color = if (transport == t) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { transport = t })
                    }
                }
                Text("Available in", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("user" to "All projects", "local" to "This project (me)", "project" to "This project (.mcp.json)").forEach { (sc, label) ->
                        val enabled = sc == "user" || cwd != null
                        ControlChip(label, highlighted = scope == sc, color = if (scope == sc) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
                            onClick = { if (enabled) scope = sc })
                    }
                }
                if (transport == "stdio") {
                    OutlinedTextField(command, { command = it }, label = { Text("Command and arguments") }, placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem /sdcard") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pairs, { pairs = it }, label = { Text("Environment (KEY=value per line)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("URL") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pairs, { pairs = it }, label = { Text("Headers (Name: value per line)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val sep = if (transport == "stdio") "=" else ":"
                val map = pairs.lines().mapNotNull { line ->
                    val i = line.indexOf(sep)
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }.toMap()
                onAdd(
                    ClaudeMcpAddRequest(
                        name = name, scope = scope, transport = transport,
                        command = parts.firstOrNull().orEmpty(), args = parts.drop(1),
                        env = if (transport == "stdio") map else emptyMap(),
                        url = url, headers = if (transport != "stdio") map else emptyMap(),
                        cwd = cwd.orEmpty()
                    )
                )
            }, enabled = name.isNotBlank() && (if (transport == "stdio") command.isNotBlank() else url.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ======================================================================== Plugins

@Composable
private fun PluginsPage(deps: ClaudeSettingsDeps) {
    val data by deps.config.plugins.collectAsState()
    val ops by deps.config.pluginOps.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var marketSource by remember { mutableStateOf("") }
    var addingMarket by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { deps.config.loadPlugins() }
    // the field empties once the marketplace add has finished
    LaunchedEffect(ops) {
        addingMarket?.let { if (it !in ops) { addingMarket = null; marketSource = "" } }
    }

    // the catalog has thousands of plugins: search once typing pauses, off the main thread
    val available = data?.plugins?.available.orEmpty()
    val searchIndex = remember(available) { available.map { p -> "${p.name}\n${p.description}\n${p.category.orEmpty()}".lowercase() } }
    var results by remember { mutableStateOf(available) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, available) {
        searching = true
        if (query.isNotBlank()) kotlinx.coroutines.delay(300)
        val q = query.trim().lowercase()
        results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (q.isEmpty()) available else available.filterIndexed { i, _ -> searchIndex[i].contains(q) }
        }
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf("Installed", "Discover", "Marketplaces").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        val d = data
        if (d == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Hint("Loading plugins… (the catalog can take a few seconds)")
            }
            return@Column
        }
        val installedIds = remember(d) { d.plugins.installed.map { it.key }.toSet() }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> {
                    if (d.plugins.installed.isEmpty()) item { Hint("No plugins installed. Find some in Discover.") }
                    items(d.plugins.installed.size, key = { "i_" + d.plugins.installed[it].key }) { i ->
                        val p = d.plugins.installed[i]
                        val op = ops[p.key]
                        ClaudeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name.ifBlank { p.key }, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    Hint(listOfNotNull(p.version?.let { "v$it" }, p.scope, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                                }
                                if (op != null) PluginProgress(op)
                                else if (p.enabled != null) Switch(
                                    checked = p.enabled,
                                    onCheckedChange = { deps.config.pluginAction(if (it) "enable" else "disable", ClaudePluginAction(plugin = p.key, scope = p.scope)) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent)
                                )
                            }
                            if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                OutlinedButton(onClick = { deps.config.pluginAction("update", ClaudePluginAction(plugin = p.key)) }, enabled = op == null) { Text("Update", fontSize = 12.sp) }
                                OutlinedButton(onClick = { deps.config.pluginAction("uninstall", ClaudePluginAction(plugin = p.key, scope = p.scope)) }, enabled = op == null) {
                                    Text("Uninstall", fontSize = 12.sp, color = QuotaRed)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item(key = "search") {
                        OutlinedTextField(
                            query, { query = it }, singleLine = true,
                            placeholder = { Text("Search ${available.size} plugins") },
                            trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item(key = "count") { Hint(if (searching) "Searching…" else "${results.size} plugin(s)") }
                    items(results.size, key = { "a_" + results[it].key }) { i ->
                        val p = results[i]
                        val op = ops[p.key]
                        ClaudeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    Hint(listOfNotNull(p.category, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                                }
                                when {
                                    op != null -> PluginProgress(op)
                                    p.key in installedIds -> Text("Installed", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = QuotaGreen)
                                    else -> Button(onClick = { deps.config.pluginAction("install", ClaudePluginAction(plugin = p.key, scope = "user")) },
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Install", fontSize = 12.sp) }
                                }
                            }
                            if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        }
                    }
                }
                else -> {
                    item(key = "about") {
                        Hint("A marketplace is a catalog of plugins (a git repo with a marketplace.json). Discover lists the plugins of every marketplace added here; refresh one to get its newest plugins.")
                    }
                    item(key = "add") {
                        ClaudeCard {
                            SectionTitle("Add a marketplace")
                            Hint("GitHub owner/repo, a git URL, or a URL to marketplace.json")
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(marketSource, { marketSource = it.trim() }, singleLine = true, placeholder = { Text("owner/repo") }, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                val adding = addingMarket != null
                                Button(onClick = {
                                    addingMarket = marketSource
                                    deps.config.pluginAction("marketplace-add", ClaudePluginAction(source = marketSource))
                                }, enabled = marketSource.isNotBlank() && !adding, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) {
                                    if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text("Add")
                                }
                            }
                        }
                    }
                    items(d.marketplaces.size, key = { "m_" + d.marketplaces[it].name }) { i ->
                        val m = d.marketplaces[i]
                        val op = ops[m.name]
                        ClaudeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    Hint(listOfNotNull(m.repo, m.url, m.source).firstOrNull().orEmpty())
                                }
                                if (op != null) PluginProgress(op)
                                else {
                                    IconButton(onClick = { deps.config.pluginAction("marketplace-update", ClaudePluginAction(name = m.name)) }) {
                                        Icon(Icons.Outlined.Refresh, "Refresh catalog")
                                    }
                                    IconButton(onClick = { deps.config.pluginAction("marketplace-remove", ClaudePluginAction(name = m.name)) }) {
                                        Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The running (or queued) action of one plugin / marketplace. */
@Composable
private fun PluginProgress(action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        Spacer(Modifier.width(6.dp))
        Text(
            when (action) {
                "install" -> "Installing…"
                "uninstall" -> "Removing…"
                "enable" -> "Enabling…"
                "disable" -> "Disabling…"
                "update", "marketplace-update" -> "Updating…"
                "marketplace-remove" -> "Removing…"
                else -> "Working…"
            },
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent
        )
    }
}

// ======================================================================== Memory

@Composable
private fun MemoryPage(deps: ClaudeSettingsDeps) {
    val memory by deps.config.memory.collectAsState()
    val auto by deps.config.autoMemory.collectAsState()
    // the project Claude chats run in: the open project, otherwise the bridge's default workspace
    val cwd = activeProjectPath()
    val projectName = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "Default workspace"
    var scope by remember { mutableStateOf("user") }
    var text by remember { mutableStateOf("") }
    var openNote by remember { mutableStateOf<String?>(null) }
    var noteToDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope) { deps.config.loadMemory(scope, if (scope == "project") cwd else null) }
    LaunchedEffect(memory) { memory?.let { text = it.content } }
    LaunchedEffect(cwd) { deps.config.loadAutoMemory(cwd) }

    // ---- CLAUDE.md
    ClaudeCard {
        SectionTitle("Instructions (CLAUDE.md)")
        Hint("Rules Claude follows in every chat. New chats read them right away; a chat that is already running reads them the next time its Claude process starts.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ControlChip("All projects", highlighted = scope == "user", color = if (scope == "user") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { scope = "user" })
            ControlChip(projectName, highlighted = scope == "project", color = if (scope == "project") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { scope = "project" })
        }
        Spacer(Modifier.height(8.dp))
        val m = memory
        if (m == null) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        } else {
            Hint(
                (if (scope == "user") "Used in every project · " else "Used only in $projectName · ") +
                    m.path + if (!m.exists) " (created when you save)" else ""
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("# Instructions Claude follows in every chat\n- Prefer Kotlin coroutines\n- Run ./gradlew test before committing") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { deps.config.saveMemory(scope, if (scope == "project") cwd else null, text) },
                enabled = text != m.content,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) { Text("Save") }
        }
    }

    // ---- auto memory
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Claude's memory · $projectName")
                Hint("Notes Claude saved by itself while working in this project. This is what Claude checks when you ask what it remembers.")
            }
            IconButton(onClick = { deps.config.loadAutoMemory(cwd) }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
        val a = auto
        when {
            a == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
            a.error != null -> Text(a.error, fontSize = 12.sp, color = QuotaRed)
            a.files.isEmpty() -> Hint("No notes yet. Ask Claude to remember something and it saves it here.")
            else -> {
                Hint(a.dir)
                a.files.forEach { f ->
                    val open = openNote == f.name
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { openNote = if (open) null else f.name },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Hint(java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.modified)))
                        }
                        IconButton(onClick = { noteToDelete = f.name }) { Icon(Icons.Outlined.Delete, "Delete", tint = QuotaRed.copy(alpha = 0.8f)) }
                    }
                    if (open) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                            Text(f.content, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    noteToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete $name?", fontWeight = FontWeight.Bold) },
            text = { Text("Claude forgets what this note says.", fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deps.config.deleteAutoMemory(cwd, name)
                    noteToDelete = null
                }) { Text("Delete", color = QuotaRed) }
            },
            dismissButton = { TextButton(onClick = { noteToDelete = null }) { Text("Cancel") } }
        )
    }
}

// ======================================================================== Advanced

@Composable
private fun AdvancedPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    val path by deps.config.settingsPath.collectAsState()
    val error by deps.config.settingsError.collectAsState()
    var text by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { deps.config.loadSettings() }
    LaunchedEffect(settings) { settings?.let { text = prettyJson.encodeToString(JsonObject.serializer(), it) } }

    Hint("Every Claude Code setting lives here: permissions, hooks, env, model, statusLine… Changes apply to new Claude chats.")
    if (path.isNotBlank()) Hint(path)
    error?.let { Text(it, color = QuotaRed, fontSize = 12.sp) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parseError = null
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
        isError = parseError != null
    )
    parseError?.let { Text(it, color = QuotaRed, fontSize = 12.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { deps.config.loadSettings() }) { Text("Reload") }
        Button(onClick = {
            val parsed: JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrElse {
                parseError = "Invalid JSON: ${it.message}"
                null
            }
            when (parsed) {
                null -> Unit
                is JsonObject -> deps.config.saveSettings(parsed, "settings.json saved")
                else -> parseError = "settings.json must be a JSON object"
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Validate & save") }
    }
}
