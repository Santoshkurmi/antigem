package ai.antigem.ui.opencode

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
import androidx.compose.material.icons.outlined.Speed
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
import ai.antigem.data.agent.opencode.OpenCodeChatBackend
import ai.antigem.data.agent.opencode.OpenCodePreferences
import ai.antigem.data.agent.opencode.OpenCodeTranscript
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.agents.ApprovalChoice
import ai.antigem.ui.agents.ApprovalItem
import ai.antigem.ui.claude.ControlChip
import ai.antigem.ui.components.OpenCodeAccent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ai.antigem.i18n.L

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
            primary = ApprovalChoice(L.common.allow) { backend.replyPermission(p.id, "once") },
            always = if (p.save.isEmpty()) listOf(ApprovalChoice(L.opencode.chatUi.alwaysAllowAction(p.action)) { backend.replyPermission(p.id, "always") })
            else listOf(ApprovalChoice(L.opencode.chatUi.alwaysAllowValue(p.save.joinToString(", ")), L.opencode.chatUi.opencodeRemembersThisForTheProject) { backend.replyPermission(p.id, "always") }),
            denyPlaceholder = L.opencode.chatUi.tellOpencodeWhatToDoInstead,
            onDeny = { backend.replyPermission(p.id, "reject", it) }
        )
    }

private fun permissionTitle(action: String): Pair<ImageVector, String> = when (action) {
    "shell", "bash" -> Icons.Outlined.Terminal to L.common.runACommand
    "edit" -> Icons.Outlined.EditNote to L.opencode.chatUi.changeAFile
    "write" -> Icons.AutoMirrored.Outlined.NoteAdd to L.opencode.chatUi.createAFile
    "webfetch" -> Icons.Outlined.Language to L.opencode.chatUi.fetchAWebPage
    "websearch" -> Icons.Outlined.TravelExplore to L.common.searchTheWeb
    "read", "list", "glob", "grep" -> Icons.Outlined.FolderOpen to L.opencode.chatUi.readThis
    "external_directory" -> Icons.Outlined.FolderOpen to L.opencode.chatUi.workOutsideTheProject
    "skill" -> Icons.Outlined.Extension to L.opencode.chatUi.useASkill
    else -> Icons.Outlined.Shield to L.opencode.chatUi.allowAction(action)
}

fun openCodeModeLabel(mode: String): String = when (mode) {
    OpenCodePreferences.MODE_AUTO -> L.opencode.chatUi.allowAll
    OpenCodePreferences.MODE_DEFAULT -> L.opencode.chatUi.opencodeRules
    else -> L.common.askFirst
}

fun openCodeModeDescription(mode: String): String = when (mode) {
    OpenCodePreferences.MODE_AUTO -> L.opencode.chatUi.commandsEditsAndWebAccessRun
    OpenCodePreferences.MODE_DEFAULT -> L.opencode.chatUi.opencodesOwnRulesFromItsConfig
    else -> L.opencode.chatUi.askBeforeCommandsFileChangesAnd
}

/** Icon and color of an approval mode (chip and sheet). */
@Composable
fun openCodeModeColor(mode: String): androidx.compose.ui.graphics.Color = when (mode) {
    OpenCodePreferences.MODE_AUTO -> ai.antigem.theme.AppColors.warning
    OpenCodePreferences.MODE_DEFAULT -> ai.antigem.theme.AppColors.accentAlt
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

private fun openCodeAgentDescription(a: ai.antigem.data.agent.opencode.OpenCodeAgent): String = when (a.id) {
    "build" -> L.opencode.chatUi.makesChangesEditsFilesAndRuns
    "plan" -> L.opencode.chatUi.readOnlyExploresTheCodeAnd
    else -> a.description.ifBlank { L.opencode.chatUi.yourOwnAgentFromTheOpencode }
}

/** OpenCode chat controls above the input: agent (Build / Plan), approval mode, effort and thinking, and the retry / compaction notice. */
@Composable
fun OpenCodeControlsStrip(backend: OpenCodeChatBackend) {
    val agents by backend.agents.collectAsState()
    val agent by backend.agent.collectAsState()
    val mode by backend.permissionMode.collectAsState()
    val notice by backend.notice.collectAsState()
    val selectedModel by backend.selectedModelId.collectAsState()
    val models by backend.allModels.collectAsState()
    val variantsByModel by backend.modelVariants.collectAsState()
    val variant by backend.variant.collectAsState()
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    var agentSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var effortSheet by remember { mutableStateOf(false) }
    // thinking levels of the chat's model: "none" (where the model has it) is the Thinking switch, the rest are Effort
    val model = models.firstOrNull { it.id == selectedModel }
    val variants = variantsByModel[selectedModel].orEmpty()
    val canTurnOff = "none" in variants
    val levels = variants.filter { it != "none" }
    val thinkingOff = variant == "none"
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
            if (levels.isNotEmpty()) {
                val level = variant?.takeIf { it != "none" }
                ControlChip(
                    text = "Effort · ${ai.antigem.ui.claude.effortLabel(level)}",
                    icon = Icons.Outlined.Speed,
                    color = if (level != null) OpenCodeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    highlighted = level != null,
                    onClick = { effortSheet = true }
                )
            }
            if (variants.isNotEmpty() || model?.supportsThinking == true) {
                ControlChip(
                    text = if (thinkingOff) L.opencode.chatUi.noThinking else L.common.thinking,
                    icon = Icons.Outlined.Lightbulb,
                    color = if (thinkingOff) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else OpenCodeAccent,
                    highlighted = !thinkingOff,
                    onClick = {
                        when {
                            // back on: the model's default level
                            thinkingOff -> backend.setVariant(null)
                            canTurnOff -> backend.setVariant("none")
                            else -> android.widget.Toast.makeText(
                                androidContext,
                                L.opencode.chatUi.valueAlwaysThinksItCantBe(model?.displayName ?: L.opencode.chatUi.thisModel),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }
    if (effortSheet) {
        ai.antigem.ui.claude.ClaudeEffortSheet(
            levels = levels,
            current = variant?.takeIf { it != "none" },
            onSelect = { backend.setVariant(it) },
            onDismiss = { effortSheet = false },
            agent = AgentKind.OPENCODE,
            color = OpenCodeAccent,
            detail = L.opencode.chatUi.howHardValueThinksOnEach(model?.displayName ?: L.opencode.chatUi.theModel)
        )
    }
    if (agentSheet) {
        val list = agents.ifEmpty {
            listOf(
                ai.antigem.data.agent.opencode.OpenCodeAgent("build", "build", ""),
                ai.antigem.data.agent.opencode.OpenCodeAgent("plan", "plan", "")
            )
        }
        ai.antigem.ui.claude.ClaudePickerSheet(L.opencode.chatUi.agent, L.opencode.chatUi.whoWorksOnThisChat, { agentSheet = false }, agent = AgentKind.OPENCODE) {
            list.forEach { a ->
                ai.antigem.ui.claude.PickerOption(
                    title = a.name.replaceFirstChar { it.uppercase() },
                    description = openCodeAgentDescription(a),
                    color = if (a.id == "plan") ai.antigem.theme.AppColors.accentTeal else OpenCodeAccent,
                    selected = a.id == agent,
                    icon = openCodeAgentIcon(a.id),
                    onClick = { backend.selectAgent(a.id); agentSheet = false }
                )
            }
            SheetNote(L.opencode.chatUi.switchingAppliesToTheNextMessage)
        }
    }
    if (modeSheet) {
        ai.antigem.ui.claude.ClaudePickerSheet(L.common.approvals, L.opencode.chatUi.whatOpencodeAsksBeforeDoing, { modeSheet = false }, agent = AgentKind.OPENCODE) {
            listOf(OpenCodePreferences.MODE_ASK, OpenCodePreferences.MODE_AUTO, OpenCodePreferences.MODE_DEFAULT).forEach { m ->
                ai.antigem.ui.claude.PickerOption(
                    title = openCodeModeLabel(m),
                    description = openCodeModeDescription(m),
                    color = openCodeModeColor(m),
                    selected = m == mode,
                    icon = openCodeModeIcon(m),
                    onClick = { backend.setPermissionMode(m); modeSheet = false }
                )
            }
            SheetNote(L.opencode.chatUi.appliesToThisChatRightAway)
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
