package com.example.gemini.ui.opencode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.opencode.OpenCodeChatBackend
import com.example.gemini.data.agent.opencode.OpenCodePreferences
import com.example.gemini.data.agent.opencode.OpenCodeTranscript
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.ApprovalChoice
import com.example.gemini.ui.agents.ApprovalItem
import com.example.gemini.ui.claude.ControlChip
import com.example.gemini.ui.components.OpenCodeAccent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenCode's permission requests as approval-panel items: allow once, always allow (OpenCode remembers the pattern
 * it suggests, e.g. `git *`), or deny with an optional note for the agent.
 */
fun openCodeApprovalItems(pending: List<OpenCodeTranscript.Permission>, backend: OpenCodeChatBackend): List<ApprovalItem> =
    pending.map { p ->
        val (icon, title) = permissionTitle(p.action)
        val res = p.resources.joinToString("\n")
        val diff = listOf("diff", "patch").firstNotNullOfOrNull { (p.metadata[it] as? JsonPrimitive)?.contentOrNull }
        ApprovalItem(
            key = p.id,
            agent = AgentKind.OPENCODE,
            icon = icon,
            title = title,
            subtitle = p.action,
            target = if (p.action == "shell") null else res.ifBlank { null },
            preview = when {
                p.action == "shell" -> p.resources.joinToString("\n") { "$ $it" }
                !diff.isNullOrBlank() -> diff.lines().take(60).joinToString("\n")
                else -> null
            },
            primary = ApprovalChoice("Allow") { backend.replyPermission(p.id, "once") },
            always = if (p.save.isEmpty()) listOf(ApprovalChoice("Always allow ${p.action}") { backend.replyPermission(p.id, "always") })
            else listOf(ApprovalChoice("Always allow " + p.save.joinToString(", "), "OpenCode remembers this for the project") { backend.replyPermission(p.id, "always") }),
            denyPlaceholder = "Tell OpenCode what to do instead (optional)",
            onDeny = { backend.replyPermission(p.id, "reject", it) }
        )
    }

private fun permissionTitle(action: String): Pair<ImageVector, String> = when (action) {
    "shell", "bash" -> Icons.Outlined.Terminal to "Run a command?"
    "edit" -> Icons.Outlined.EditNote to "Change a file?"
    "write" -> Icons.AutoMirrored.Outlined.NoteAdd to "Create a file?"
    "webfetch" -> Icons.Outlined.Language to "Fetch a web page?"
    "websearch" -> Icons.Outlined.TravelExplore to "Search the web?"
    "read", "list", "glob", "grep" -> Icons.Outlined.FolderOpen to "Read this?"
    "external_directory" -> Icons.Outlined.FolderOpen to "Work outside the project?"
    "skill" -> Icons.Outlined.Extension to "Use a skill?"
    else -> Icons.Outlined.Shield to "Allow $action?"
}

fun openCodeModeLabel(mode: String): String = when (mode) {
    OpenCodePreferences.MODE_AUTO -> "Allow all"
    OpenCodePreferences.MODE_DEFAULT -> "OpenCode rules"
    else -> "Ask first"
}

fun openCodeModeDescription(mode: String): String = when (mode) {
    OpenCodePreferences.MODE_AUTO -> "Commands, edits and web access run without asking."
    OpenCodePreferences.MODE_DEFAULT -> "OpenCode's own rules from its config (by default most tools run without asking)."
    else -> "Ask before commands, file changes and web access. Reading the project is allowed."
}

/** Icon and color of an approval mode (chip and sheet). */
@Composable
fun openCodeModeColor(mode: String): androidx.compose.ui.graphics.Color = when (mode) {
    OpenCodePreferences.MODE_AUTO -> com.example.gemini.theme.AppColors.warning
    OpenCodePreferences.MODE_DEFAULT -> com.example.gemini.theme.AppColors.accentAlt
    else -> OpenCodeAccent
}

fun openCodeModeIcon(mode: String): ImageVector = when (mode) {
    OpenCodePreferences.MODE_AUTO -> Icons.Outlined.WarningAmber
    OpenCodePreferences.MODE_DEFAULT -> Icons.Outlined.Rule
    else -> Icons.Outlined.PanTool
}

/** Icon of an OpenCode agent (Build / Plan / the user's own). */
fun openCodeAgentIcon(id: String): ImageVector = when (id) {
    "plan" -> Icons.Outlined.Lightbulb
    "build" -> Icons.Outlined.SmartToy
    else -> Icons.Outlined.Psychology
}

private fun openCodeAgentDescription(a: com.example.gemini.data.agent.opencode.OpenCodeAgent): String = when (a.id) {
    "build" -> "Makes changes: edits files and runs commands to get the job done."
    "plan" -> "Read-only: explores the code and plans the work, without changing files."
    else -> a.description.ifBlank { "Your own agent from the OpenCode config." }
}

/** OpenCode chat controls above the input: agent (Build / Plan), approval mode, and the retry / compaction notice. */
@Composable
fun OpenCodeControlsStrip(backend: OpenCodeChatBackend) {
    val agents by backend.agents.collectAsState()
    val agent by backend.agent.collectAsState()
    val mode by backend.permissionMode.collectAsState()
    val notice by backend.notice.collectAsState()
    var agentSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        notice?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = OpenCodeAccent)
                Spacer(Modifier.width(6.dp))
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = agents.firstOrNull { it.id == agent }?.name ?: agent
            ControlChip(
                text = name.replaceFirstChar { it.uppercase() },
                icon = openCodeAgentIcon(agent),
                color = OpenCodeAccent,
                highlighted = agent == "plan",
                onClick = { agentSheet = true }
            )
            ControlChip(
                text = openCodeModeLabel(mode),
                icon = openCodeModeIcon(mode),
                highlighted = mode == OpenCodePreferences.MODE_AUTO,
                color = if (mode == OpenCodePreferences.MODE_AUTO) openCodeModeColor(mode) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                onClick = { modeSheet = true }
            )
        }
    }
    if (agentSheet) {
        val list = agents.ifEmpty {
            listOf(
                com.example.gemini.data.agent.opencode.OpenCodeAgent("build", "build", ""),
                com.example.gemini.data.agent.opencode.OpenCodeAgent("plan", "plan", "")
            )
        }
        com.example.gemini.ui.claude.ClaudePickerSheet("Agent", "who works on this chat", { agentSheet = false }, agent = AgentKind.OPENCODE) {
            list.forEach { a ->
                com.example.gemini.ui.claude.PickerOption(
                    title = a.name.replaceFirstChar { it.uppercase() },
                    description = openCodeAgentDescription(a),
                    color = if (a.id == "plan") com.example.gemini.theme.AppColors.accentTeal else OpenCodeAccent,
                    selected = a.id == agent,
                    icon = openCodeAgentIcon(a.id),
                    onClick = { backend.selectAgent(a.id); agentSheet = false }
                )
            }
            SheetNote("Switching applies to the next message of this chat. New chats use the agent from Settings → OpenCode → Defaults.")
        }
    }
    if (modeSheet) {
        com.example.gemini.ui.claude.ClaudePickerSheet("Approvals", "what OpenCode asks before doing", { modeSheet = false }, agent = AgentKind.OPENCODE) {
            listOf(OpenCodePreferences.MODE_ASK, OpenCodePreferences.MODE_AUTO, OpenCodePreferences.MODE_DEFAULT).forEach { m ->
                com.example.gemini.ui.claude.PickerOption(
                    title = openCodeModeLabel(m),
                    description = openCodeModeDescription(m),
                    color = openCodeModeColor(m),
                    selected = m == mode,
                    icon = openCodeModeIcon(m),
                    onClick = { backend.setPermissionMode(m); modeSheet = false }
                )
            }
            SheetNote("Applies to this chat right away and to new chats. Requests show above the message box.")
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Row(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
        androidx.compose.material3.Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
}
