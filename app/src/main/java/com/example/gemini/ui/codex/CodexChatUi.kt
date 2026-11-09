package com.example.gemini.ui.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.codex.CodexChatBackend
import com.example.gemini.data.agent.codex.CodexPending
import com.example.gemini.data.agent.codex.CodexPreferences
import com.example.gemini.data.agent.codex.CodexTranscript
import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.o
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.agents.ApprovalChoice
import com.example.gemini.ui.agents.ApprovalItem
import com.example.gemini.ui.claude.ControlChip
import com.example.gemini.ui.components.CodexAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Codex's approval requests as approval-panel items: allow once, allow for the rest of the session (or always allow
 * the command prefix Codex suggests), or deny with an optional note that Codex gets right away. [messages]: the chat,
 * where a file change's diff is (its request does not carry it).
 */
fun codexApprovalItems(pending: List<CodexPending>, backend: CodexChatBackend, messages: List<ChatMessage>): List<ApprovalItem> =
    pending.map { p ->
        val prm = p.params
        val tool = p.itemId?.let { id -> CodexTranscript.safeId(id) }?.let { id -> messages.asReversed().firstNotNullOfOrNull { m -> m.toolCalls.firstOrNull { it.id == id } } }
        val reason = prm.str("reason")
        val deny: (String?) -> Unit = { backend.deny(p.key, it) }
        when (p.method) {
            "item/commandExecution/requestApproval", "execCommandApproval" -> {
                val network = prm.o("networkApprovalContext")
                val command = prm.str("command")?.let { CodexTranscript.unwrapShell(it) }
                    ?: prm.a("command")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")
                    ?: tool?.command.orEmpty()
                val rule = prm.a("proposedExecpolicyAmendment")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                ApprovalItem(
                    key = p.key,
                    agent = AgentKind.CODEX,
                    icon = if (network != null) Icons.Outlined.Language else Icons.Outlined.Terminal,
                    title = if (network != null) "Allow network access?" else "Run a command?",
                    subtitle = network?.let { "${it.str("protocol").orEmpty()} ${it.str("host").orEmpty()}".trim() } ?: prm.str("cwd"),
                    preview = command.takeIf { it.isNotBlank() }?.let { "$ $it" },
                    reason = reason,
                    primary = ApprovalChoice("Allow") { backend.approve(p.key) },
                    always = buildList {
                        add(ApprovalChoice("Allow for this session", "Codex asks again after a restart") { backend.approve(p.key, always = true) })
                        if (rule.isNotEmpty()) add(ApprovalChoice("Always allow `${rule.joinToString(" ")}`", "Codex saves this rule") { backend.approveRule(p.key) })
                    },
                    denyPlaceholder = "Tell Codex what to do instead (optional)",
                    onDeny = deny
                )
            }
            "item/fileChange/requestApproval", "applyPatchApproval" -> ApprovalItem(
                key = p.key,
                agent = AgentKind.CODEX,
                icon = Icons.Outlined.EditNote,
                title = "Change files?",
                subtitle = tool?.command,
                target = prm.str("grantRoot")?.let { "Write access to $it" },
                preview = tool?.output?.takeIf { it.isNotBlank() }?.lines()?.take(80)?.joinToString("\n") { line ->
                    // the panel colors "+ " / "- " lines
                    when {
                        line.startsWith("+") && !line.startsWith("+ ") -> "+ " + line.drop(1)
                        line.startsWith("-") && !line.startsWith("- ") -> "- " + line.drop(1)
                        else -> line
                    }
                },
                reason = reason,
                primary = ApprovalChoice("Allow") { backend.approve(p.key) },
                always = listOf(ApprovalChoice("Allow edits for this session", "Codex asks again after a restart") { backend.approve(p.key, always = true) }),
                denyPlaceholder = "Tell Codex what to do instead (optional)",
                onDeny = deny
            )
            "item/permissions/requestApproval" -> ApprovalItem(
                key = p.key,
                agent = AgentKind.CODEX,
                icon = Icons.Outlined.Lock,
                title = "Grant more access?",
                subtitle = prm.str("cwd"),
                preview = prm.o("permissions")?.toString(),
                reason = reason,
                primary = ApprovalChoice("Allow for this turn") { backend.approve(p.key) },
                always = listOf(ApprovalChoice("Allow for this session") { backend.approve(p.key, always = true) }),
                onDeny = deny
            )
            "mcpServer/elicitation/request" -> ApprovalItem(
                key = p.key,
                agent = AgentKind.CODEX,
                icon = Icons.Outlined.Extension,
                title = prm.str("title") ?: "${prm.str("serverName") ?: "An MCP server"} asks",
                subtitle = prm.str("serverName"),
                target = prm.str("url"),
                reason = prm.str("message") ?: prm.str("description"),
                primary = ApprovalChoice("Continue") { backend.approve(p.key) },
                onDeny = deny
            )
            else -> ApprovalItem(
                key = p.key,
                agent = AgentKind.CODEX,
                icon = Icons.Outlined.Shield,
                title = "Allow this?",
                subtitle = p.method,
                reason = reason,
                primary = ApprovalChoice("Allow") { backend.approve(p.key) },
                onDeny = deny
            )
        }
    }

fun codexModeLabel(mode: String): String = when (mode) {
    CodexPreferences.MODE_READ_ONLY -> "Read only"
    CodexPreferences.MODE_ASK -> "Ask first"
    CodexPreferences.MODE_FULL -> "Full access"
    else -> "Auto"
}

fun codexModeDescription(mode: String): String = when (mode) {
    CodexPreferences.MODE_READ_ONLY -> "Reads the project. Asks before changing files or running anything that needs more."
    CodexPreferences.MODE_ASK -> "Edits files in the project; asks before running commands (except known safe ones)."
    CodexPreferences.MODE_FULL -> "No sandbox and no questions: Codex can change anything and use the network."
    else -> "Codex's default: edits and runs commands inside the project; asks before going outside it or online."
}

@Composable
fun codexModeColor(mode: String): androidx.compose.ui.graphics.Color = when (mode) {
    CodexPreferences.MODE_FULL -> com.example.gemini.theme.AppColors.warning
    CodexPreferences.MODE_READ_ONLY -> com.example.gemini.theme.AppColors.accentTeal
    else -> CodexAccent
}

fun codexModeIcon(mode: String): ImageVector = when (mode) {
    CodexPreferences.MODE_READ_ONLY -> Icons.Outlined.Lock
    CodexPreferences.MODE_ASK -> Icons.Outlined.PanTool
    CodexPreferences.MODE_FULL -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.VerifiedUser
}

fun codexEffortLabel(effort: String): String = when (effort) {
    "" -> "Default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** Codex chat controls above the input: reasoning effort, approval mode, and the retry notice. */
@Composable
fun CodexControlsStrip(backend: CodexChatBackend) {
    val efforts by backend.efforts.collectAsState()
    val effort by backend.effort.collectAsState()
    val mode by backend.permissionMode.collectAsState()
    val notice by backend.notice.collectAsState()
    val plan by backend.planMode.collectAsState()
    val lastPlan by backend.lastPlan.collectAsState()
    val running by backend.isStreaming.collectAsState()
    var effortSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = CodexAccent)
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (plan && lastPlan != null && !running) ControlChip(
                text = "Implement plan",
                icon = Icons.Outlined.PlayArrow,
                color = com.example.gemini.theme.AppColors.success,
                highlighted = true,
                onClick = { backend.implementPlan() }
            )
            ControlChip(
                text = if (plan) "Plan mode" else "Plan",
                icon = Icons.Outlined.Lightbulb,
                color = com.example.gemini.theme.AppColors.accentTeal,
                highlighted = plan,
                onClick = { backend.setPlanMode(!plan) }
            )
            if (efforts.isNotEmpty()) ControlChip(
                text = "Reasoning: " + codexEffortLabel(effort),
                icon = Icons.Outlined.Psychology,
                color = CodexAccent,
                highlighted = effort == "high" || effort == "xhigh" || effort == "max" || effort == "ultra",
                onClick = { effortSheet = true }
            )
            ControlChip(
                text = codexModeLabel(mode),
                icon = codexModeIcon(mode),
                highlighted = mode == CodexPreferences.MODE_FULL,
                color = if (mode == CodexPreferences.MODE_FULL) codexModeColor(mode) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                onClick = { modeSheet = true }
            )
        }
    }
    if (effortSheet) {
        com.example.gemini.ui.claude.ClaudePickerSheet("Reasoning", "how long Codex thinks before answering", { effortSheet = false }, agent = AgentKind.CODEX) {
            efforts.forEach { e ->
                com.example.gemini.ui.claude.PickerOption(
                    title = codexEffortLabel(e),
                    description = when (e) {
                        "low", "minimal", "none" -> "Fast answers for simple tasks."
                        "medium" -> "Balanced: the usual choice."
                        "high" -> "Thinks longer on hard problems (uses more of your limits)."
                        else -> "Longest thinking (uses the most of your limits)."
                    },
                    color = CodexAccent,
                    selected = e == effort,
                    icon = Icons.Outlined.Psychology,
                    onClick = { backend.selectEffort(e); effortSheet = false }
                )
            }
            SheetNote("Applies to the next message and to new chats.")
        }
    }
    if (modeSheet) {
        com.example.gemini.ui.claude.ClaudePickerSheet("Approvals", "what Codex asks before doing", { modeSheet = false }, agent = AgentKind.CODEX) {
            CodexPreferences.MODES.forEach { m ->
                com.example.gemini.ui.claude.PickerOption(
                    title = codexModeLabel(m),
                    description = codexModeDescription(m),
                    color = codexModeColor(m),
                    selected = m == mode,
                    icon = codexModeIcon(m),
                    onClick = { backend.setPermissionMode(m); modeSheet = false }
                )
            }
            SheetNote("Applies to the next message of this chat and to new chats. Requests show above the message box.")
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Row(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}

/** Codex's plan limits as quota meters ("5h ▬▬▭ 72% · 2h 4m"): the primary window, then the secondary one. */
fun codexQuotaWindows(limits: com.example.gemini.data.agent.codex.CodexRateLimits?): List<com.example.gemini.domain.model.QuotaWindowUi> =
    listOfNotNull(limits?.primary, limits?.secondary).map { w ->
        val label = w.windowMinutes?.let { m ->
            when {
                m >= 60 * 24 -> "${m / (60 * 24)}d"
                m >= 60 -> "${m / 60}h"
                else -> "${m}m"
            }
        } ?: "Limit"
        com.example.gemini.domain.model.QuotaWindowUi(
            label = label,
            remaining = (1f - (w.usedPercent / 100.0).toFloat()).coerceIn(0f, 1f),
            resetIso = w.resetsAt?.let { secs ->
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(secs * 1000))
            }
        )
    }

/** Codex's model picker: the models the signed-in account can use, under the plan's usage limits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexModelSheet(backend: CodexChatBackend, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val models by backend.availableModels.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    val refreshing by backend.isRefreshingModels.collectAsState()
    val error by backend.modelsError.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        backend.refreshModels()
        backend.refreshRateLimits()
    }
    // the plan's limits count every Codex model together: one group, its meters above the list
    val windows = remember(limits) { codexQuotaWindows(limits) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            AgentSheetHeader(
                AgentKind.CODEX,
                title = "Select model",
                detail = if (models.isNotEmpty()) "${models.size} models" else null,
                refreshing = refreshing,
                onRefresh = {
                    // the models and the plan limits shown above them
                    backend.refreshModels()
                    scope.launch { backend.refreshRateLimits() }
                }
            )
            if (windows.isNotEmpty() && models.isNotEmpty()) {
                com.example.gemini.ui.models.QuotaGroupHeader(title = "All models", accent = CodexAccent, count = models.size, windows = windows)
            } else Spacer(Modifier.height(10.dp))
            error?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(models, key = { it.id }) { m ->
                    val isOn = m.id == selected
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isOn) CodexAccent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (isOn) CodexAccent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            backend.selectModel(m.id)
                            onDismiss()
                        }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName + if (m.isDefault) " · default" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (m.description.isNotBlank()) Text(
                                    m.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isOn) Icon(Icons.Outlined.Check, null, tint = CodexAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onDismiss(); onOpenSettings() }) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Codex account and settings")
            }
        }
    }
}
