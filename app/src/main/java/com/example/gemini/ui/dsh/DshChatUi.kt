package com.example.gemini.ui.dsh

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
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.dsh.DshChatBackend
import com.example.gemini.data.agent.dsh.DshPending
import com.example.gemini.data.agent.dsh.DshPreferences
import com.example.gemini.data.agent.dsh.DshTranscript
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.agents.ApprovalChoice
import com.example.gemini.ui.agents.ApprovalItem
import com.example.gemini.ui.claude.ControlChip
import com.example.gemini.ui.components.DshAccent

/**
 * dsh's approval requests as approval-panel items: allow once or deny (dsh's grants never outlive the operation), with
 * an optional note that goes to dsh as the chat's next message. [messages]: the chat, where the tool call's command is.
 */
fun dshApprovalItems(pending: List<DshPending>, backend: DshChatBackend, messages: List<ChatMessage>): List<ApprovalItem> =
    pending.map { p ->
        val prm = p.params
        val tool = p.callId?.let { DshTranscript.safeId(it) }?.let { id -> messages.asReversed().firstNotNullOfOrNull { m -> m.toolCalls.firstOrNull { it.id == id } } }
        val toolName = prm.str("toolName").orEmpty()
        val n = toolName.lowercase()
        val isCommand = n == "bash" || n == "shell"
        val isEdit = n == "write" || n == "edit" || n == "multi_edit" || n == "apply_patch" || n == "str_replace_editor"
        ApprovalItem(
            key = p.key,
            agent = AgentKind.DSH,
            icon = when {
                isCommand -> Icons.Outlined.Terminal
                isEdit -> Icons.Outlined.EditNote
                n.contains("web") || n.contains("fetch") -> Icons.Outlined.Language
                n.startsWith("mcp__") || n.contains("plugin") -> Icons.Outlined.Extension
                else -> Icons.Outlined.Shield
            },
            title = when {
                isCommand -> "Run a command?"
                isEdit -> "Change files?"
                toolName.isBlank() -> "Allow this?"
                else -> "Allow $toolName?"
            },
            // a subagent of the chat on screen can ask too (its chat is read only)
            subtitle = listOfNotNull(
                toolName.ifBlank { null },
                if (p.sessionId != null && p.sessionId != backend.currentConversation.value?.id) "asked by a subagent" else null
            ).joinToString(" · ").ifBlank { null },
            preview = tool?.let { t ->
                when {
                    isCommand -> "$ ${t.command}"
                    isEdit && t.output.isNotBlank() -> t.output.lines().take(80).joinToString("\n") { line ->
                        // the panel colors "+ " / "- " lines
                        when {
                            line.startsWith("+") && !line.startsWith("+ ") -> "+ " + line.drop(1)
                            line.startsWith("-") && !line.startsWith("- ") -> "- " + line.drop(1)
                            else -> line
                        }
                    }
                    else -> t.command
                }
            },
            reason = prm.str("reason"),
            primary = ApprovalChoice("Allow") { backend.approve(p.key) },
            denyPlaceholder = "Tell DeepSeek Harness what to do instead (optional)",
            onDeny = { backend.deny(p.key, it) }
        )
    }

fun dshEffortLabel(effort: String): String = when (effort) {
    "" -> "Default"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** "128k" style token counts. */
fun dshTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

/** dsh chat controls above the input: plan mode, reasoning effort, context use, and the retry notice. */
@Composable
fun DshControlsStrip(backend: DshChatBackend) {
    val effort by backend.effort.collectAsState()
    val notice by backend.notice.collectAsState()
    val plan by backend.planMode.collectAsState()
    val context by backend.contextUsage.collectAsState()
    var effortSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = DshAccent)
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlChip(
                text = if (plan) "Plan mode" else "Plan",
                icon = Icons.Outlined.Lightbulb,
                color = com.example.gemini.theme.AppColors.accentTeal,
                highlighted = plan,
                onClick = { backend.setPlanMode(!plan) }
            )
            ControlChip(
                text = "Reasoning: " + dshEffortLabel(effort),
                icon = Icons.Outlined.Psychology,
                color = DshAccent,
                highlighted = effort == "high",
                onClick = { effortSheet = true }
            )
            context?.let { c ->
                val window = c.contextWindow?.takeIf { it > 0 }
                val share = window?.let { c.usedTokens.toDouble() / it }
                ControlChip(
                    text = "Context " + (if (window != null) "${(share!! * 100).toInt()}% · ${dshTokens(c.usedTokens)}/${dshTokens(window)}" else dshTokens(c.usedTokens)),
                    icon = Icons.Outlined.DataUsage,
                    color = if ((share ?: 0.0) >= 0.85) com.example.gemini.theme.AppColors.warning else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    highlighted = (share ?: 0.0) >= 0.85
                )
            }
        }
    }
    if (effortSheet) {
        com.example.gemini.ui.claude.ClaudePickerSheet("Reasoning", "how long the model thinks before answering", { effortSheet = false }, agent = AgentKind.DSH) {
            DshPreferences.EFFORTS.forEach { e ->
                com.example.gemini.ui.claude.PickerOption(
                    title = dshEffortLabel(e),
                    description = when (e) {
                        "" -> "The model's own default (or the one set in Settings → DeepSeek Harness)."
                        "low" -> "Fast answers for simple tasks."
                        "medium" -> "Balanced: the usual choice."
                        else -> "Thinks longer on hard problems (uses more tokens)."
                    },
                    color = DshAccent,
                    selected = e == effort,
                    icon = Icons.Outlined.Psychology,
                    onClick = { backend.selectEffort(e); effortSheet = false }
                )
            }
            SheetNote("Applies to this chat's next message and to new chats. Models without reasoning ignore it.")
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

/**
 * dsh's model picker: the models of every provider with a key (grouped by provider, searchable; there can be
 * hundreds), or a typed "provider/model" when dsh is not running to list them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshModelSheet(backend: DshChatBackend, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val models by backend.availableModels.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    val refreshing by backend.isRefreshingModels.collectAsState()
    val error by backend.modelsError.collectAsState()
    val status by backend.status.collectAsState()
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { backend.refreshModels() }
    val shown = remember(models, query) {
        val q = query.trim()
        models.filter { q.isBlank() || it.id.contains(q, ignoreCase = true) || it.displayName.contains(q, ignoreCase = true) || it.baseName.contains(q, ignoreCase = true) }
            .groupBy { it.baseName }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            AgentSheetHeader(
                AgentKind.DSH,
                title = "Select model",
                detail = if (models.isNotEmpty()) "${models.size} models" else selected.takeIf { it.isNotBlank() },
                refreshing = refreshing,
                onRefresh = { backend.refreshModels(start = true) }
            )
            Spacer(Modifier.height(8.dp))
            if (models.isNotEmpty()) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search models") },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
            }
            error?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
            if (models.isEmpty()) {
                if (status?.running != true) {
                    Text(
                        "The model list comes from DeepSeek Harness itself. Starting it takes up to half a minute on a phone.",
                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { backend.refreshModels(start = true) },
                        enabled = !refreshing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DshAccent, contentColor = Color.White)
                    ) { Text(if (refreshing) "Starting…" else "Start and load models") }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Or type a model as provider/model:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        manual, { manual = it }, singleLine = true, placeholder = { Text("openrouter/deepseek/deepseek-v4-flash") },
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { backend.selectModel(manual.trim()); onDismiss() },
                        enabled = DshPreferences.split(manual.trim()) != null
                    ) { Text("Use") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                shown.forEach { (provider, list) ->
                    item(key = "h_$provider") {
                        Text(
                            provider.ifBlank { "Models" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DshAccent,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(list, key = { it.id }) { m ->
                        val isOn = m.id == selected
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isOn) DshAccent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isOn) DshAccent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).clickable {
                                backend.selectModel(m.id)
                                onDismiss()
                            }
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m.displayName + (m.tier?.let { " · $it" } ?: "") + if (m.isDefault) " · default" else "",
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        m.description.ifBlank { m.id }, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isOn) Icon(Icons.Outlined.Check, null, tint = DshAccent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onDismiss(); onOpenSettings() }) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Providers, API keys and settings")
            }
        }
    }
}
