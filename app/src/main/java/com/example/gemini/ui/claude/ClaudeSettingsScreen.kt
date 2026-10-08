package com.example.gemini.ui.claude

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
    MEMORY("Memory", "CLAUDE.md instructions Claude always reads"),
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

/** One Claude settings page with its own header; [onBack] returns to the main settings menu. */
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text("Claude Code · ${page.title}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ClaudeAccent)
                Text(page.subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (page) {
                ClaudeSettingsPage.ACCOUNT -> AccountPage(deps)
                ClaudeSettingsPage.DEFAULTS -> DefaultsPage(deps)
                ClaudeSettingsPage.PERMISSIONS -> PermissionsPage(deps)
                ClaudeSettingsPage.MCP -> McpPage(deps)
                ClaudeSettingsPage.PLUGINS -> PluginsPage(deps)
                ClaudeSettingsPage.MEMORY -> MemoryPage(deps)
                ClaudeSettingsPage.ADVANCED -> AdvancedPage(deps)
            }
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

    usage?.let { u ->
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan usage", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { deps.account.refreshUsage(force = true) }) { Icon(Icons.Outlined.Refresh, "Refresh usage") }
            }
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
    val allowBypass by deps.prefs.allowBypass.collectAsState()
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
    val mode = deps.config.defaultMode() ?: "default"

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
            if (value == "bypassPermissions" && !allowBypass) return@forEach
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { deps.config.setDefaultMode(if (value == "default") null else value) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.RadioButton(selected = value == mode, onClick = { deps.config.setDefaultMode(if (value == "default") null else value) })
                Column {
                    Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Hint(permissionModeDescription(value))
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow Bypass mode", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                Hint("Lets chats skip every permission check. Only for sandboxes without sensitive data.")
            }
            Switch(checked = allowBypass, onCheckedChange = { deps.prefs.setAllowBypass(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = QuotaRed))
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

@Composable
private fun McpPage(deps: ClaudeSettingsDeps) {
    val servers by deps.config.mcpServers.collectAsState()
    val loading by deps.config.mcpLoading.collectAsState()
    val cwd = activeProjectPath()
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { deps.config.loadMcp(cwd) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint(if (cwd != null) "Includes servers for the open project ($cwd)." else "Open a project to also see its project-scoped servers.")
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        IconButton(onClick = { deps.config.loadMcp(cwd) }) { Icon(Icons.Outlined.Refresh, "Refresh") }
    }
    Button(onClick = { showAdd = true }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Add MCP server") }
    if (servers.isEmpty() && !loading) Hint("No MCP servers configured.")
    servers.forEach { srv ->
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (srv.status) {
                    "connected" -> QuotaGreen
                    "pending" -> QuotaAmber
                    "failed" -> QuotaRed
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(srv.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Hint(listOfNotNull(srv.status, srv.scope ?: srv.source, "${srv.tools.size} tools".takeIf { srv.tools.isNotEmpty() }).joinToString(" · "))
                    srv.error?.let { Text(it, fontSize = 11.sp, color = QuotaRed) }
                }
                val removable = srv.scope in setOf("user", "project", "local")
                if (removable) IconButton(onClick = { deps.config.removeMcp(srv.name, srv.scope, cwd) }) {
                    Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f))
                }
            }
            if (srv.tools.isNotEmpty()) {
                Text(srv.tools.joinToString(", ") { it.name }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (showAdd) AddMcpDialog(cwd, onDismiss = { showAdd = false }) { req ->
        deps.config.addMcp(req, cwd)
        showAdd = false
    }
}

@Composable
private fun AddMcpDialog(cwd: String?, onDismiss: () -> Unit, onAdd: (ClaudeMcpAddRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var scope by remember { mutableStateOf("user") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pairs by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("stdio", "http", "sse").forEach { t ->
                        ControlChip(t, highlighted = transport == t, color = if (transport == t) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { transport = t })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    val busy by deps.config.pluginBusy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var marketSource by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { deps.config.loadPlugins() }

    TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
        listOf("Installed", "Discover", "Marketplaces").forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
        }
    }
    if (data == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Hint("Loading plugins… (the catalog can take a few seconds)")
        }
        return
    }
    val d = data!!
    busy?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Hint("Working on $it…")
        }
    }
    when (tab) {
        0 -> {
            if (d.plugins.installed.isEmpty()) Hint("No plugins installed. Find some in Discover.")
            d.plugins.installed.forEach { p ->
                ClaudeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { p.key }, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            Hint(listOfNotNull(p.version?.let { "v$it" }, p.scope, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                        }
                        if (p.enabled != null) Switch(
                            checked = p.enabled, enabled = busy == null,
                            onCheckedChange = { deps.config.pluginAction(if (it) "enable" else "disable", ClaudePluginAction(plugin = p.key, scope = p.scope)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent)
                        )
                    }
                    if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { deps.config.pluginAction("update", ClaudePluginAction(plugin = p.key)) }, enabled = busy == null) { Text("Update", fontSize = 12.sp) }
                        OutlinedButton(onClick = { deps.config.pluginAction("uninstall", ClaudePluginAction(plugin = p.key, scope = p.scope)) }, enabled = busy == null) {
                            Text("Uninstall", fontSize = 12.sp, color = QuotaRed)
                        }
                    }
                }
            }
        }
        1 -> {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search ${d.plugins.available.size} plugins") }, modifier = Modifier.fillMaxWidth())
            val installedIds = d.plugins.installed.map { it.key }.toSet()
            val results = d.plugins.available.filter {
                query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) || (it.category?.contains(query, true) == true)
            }
            Hint("${results.size} result(s)" + if (results.size > 60) " · showing the first 60" else "")
            results.take(60).forEach { p ->
                ClaudeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            Hint(listOfNotNull(p.category, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                        }
                        if (p.key in installedIds) Text("Installed", fontSize = 11.sp, color = QuotaGreen)
                        else Button(onClick = { deps.config.pluginAction("install", ClaudePluginAction(plugin = p.key, scope = "user")) }, enabled = busy == null,
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Install", fontSize = 12.sp) }
                    }
                    if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
        }
        else -> {
            ClaudeCard {
                SectionTitle("Add a marketplace")
                Hint("GitHub owner/repo, a git URL, or a URL to marketplace.json")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(marketSource, { marketSource = it.trim() }, singleLine = true, placeholder = { Text("owner/repo") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        deps.config.pluginAction("marketplace-add", ClaudePluginAction(source = marketSource))
                        marketSource = ""
                    }, enabled = marketSource.isNotBlank() && busy == null, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Add") }
                }
            }
            d.marketplaces.forEach { m ->
                ClaudeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            Hint(listOfNotNull(m.repo, m.url, m.source).firstOrNull().orEmpty())
                        }
                        IconButton(onClick = { deps.config.pluginAction("marketplace-update", ClaudePluginAction(name = m.name)) }, enabled = busy == null) {
                            Icon(Icons.Outlined.Refresh, "Update")
                        }
                        IconButton(onClick = { deps.config.pluginAction("marketplace-remove", ClaudePluginAction(name = m.name)) }, enabled = busy == null) {
                            Icon(Icons.Outlined.Delete, "Remove", tint = QuotaRed.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================== Memory

@Composable
private fun MemoryPage(deps: ClaudeSettingsDeps) {
    val memory by deps.config.memory.collectAsState()
    val cwd = activeProjectPath()
    var scope by remember { mutableStateOf("user") }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(scope) { deps.config.loadMemory(scope, if (scope == "project") cwd else null) }
    LaunchedEffect(memory) { memory?.let { text = it.content } }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ControlChip("Personal (all projects)", highlighted = scope == "user", color = if (scope == "user") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { scope = "user" })
        ControlChip("This project", highlighted = scope == "project",
            color = if (scope == "project") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (cwd != null) 0.7f else 0.3f),
            onClick = { if (cwd != null) scope = "project" })
    }
    if (scope == "project" && cwd == null) {
        Hint("Open a project first.")
        return
    }
    val m = memory
    if (m == null) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        return
    }
    Hint(m.path + if (!m.exists) " (will be created)" else "")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        placeholder = { Text("# Instructions Claude follows in every chat\n- Prefer Kotlin coroutines\n- Run ./gradlew test before committing") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp)
    )
    Button(onClick = { deps.config.saveMemory(scope, if (scope == "project") cwd else null, text) },
        colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text("Save CLAUDE.md") }
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
