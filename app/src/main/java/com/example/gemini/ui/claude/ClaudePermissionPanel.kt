package com.example.gemini.ui.claude

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.ClaudeTranscript
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.ApprovalChoice
import com.example.gemini.ui.agents.ApprovalItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Claude's permission prompts as approval-panel items: tool approvals (allow once / always allow from Claude's
 * suggestions / deny with feedback) and plan approval (ExitPlanMode). Questions (AskUserQuestion) go to the
 * question panel instead.
 */
fun claudeApprovalItems(pending: List<ClaudeTranscript.PendingPermission>, backend: ClaudeChatBackend): List<ApprovalItem> =
    pending.map { p ->
        if (p.toolName == "ExitPlanMode") {
            ApprovalItem(
                key = p.toolUseId,
                agent = AgentKind.CLAUDE,
                icon = Icons.Outlined.Checklist,
                title = "Review the plan",
                subtitle = "Plan mode",
                markdown = (p.input["plan"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "_(empty plan)_" },
                primary = ApprovalChoice("Approve · auto-accept edits") { backend.approvePlan(p.toolUseId, "acceptEdits") },
                secondary = listOf(ApprovalChoice("Approve · review each edit") { backend.approvePlan(p.toolUseId, "default") }),
                denyLabel = "Keep planning",
                denyPlaceholder = "What should change in the plan?",
                onDeny = { backend.keepPlanning(p.toolUseId, it.orEmpty()) }
            )
        } else {
            val (icon, title) = toolTitle(p.toolName)
            ApprovalItem(
                key = p.toolUseId,
                agent = AgentKind.CLAUDE,
                icon = icon,
                title = title,
                subtitle = if (p.toolName.startsWith("mcp__")) "MCP · " + p.toolName.removePrefix("mcp__").substringBefore("__") else p.toolName,
                target = toolTarget(p.toolName, p.input, p.blockedPath),
                preview = toolPreview(p.toolName, p.input),
                reason = p.reason,
                primary = ApprovalChoice("Allow") { backend.approveToolAlways(p.toolUseId, -1) },
                always = p.suggestions.orEmpty().mapIndexedNotNull { i, s ->
                    (s as? JsonObject)?.let { ApprovalChoice(describeSuggestion(it)) { backend.approveToolAlways(p.toolUseId, i) } }
                },
                denyPlaceholder = "Tell Claude what to do instead (optional)",
                onDeny = { backend.rejectToolById(p.toolUseId, it) }
            )
        }
    }

private fun toolTitle(name: String): Pair<ImageVector, String> = when {
    name == "Bash" -> Icons.Outlined.Terminal to "Run a command?"
    name == "Write" -> Icons.AutoMirrored.Outlined.NoteAdd to "Create a file?"
    name == "Edit" || name == "MultiEdit" -> Icons.Outlined.EditNote to "Edit a file?"
    name == "NotebookEdit" -> Icons.Outlined.EditNote to "Edit a notebook?"
    name == "WebFetch" -> Icons.Outlined.Language to "Fetch a web page?"
    name == "WebSearch" -> Icons.Outlined.TravelExplore to "Search the web?"
    name == "Read" || name == "Glob" || name == "Grep" || name == "LS" -> Icons.Outlined.FolderOpen to "Read outside the project?"
    name.startsWith("mcp__") -> Icons.Outlined.Extension to "Use ${name.substringAfterLast("__")}?"
    else -> Icons.Outlined.Shield to "Allow $name?"
}

private fun toolTarget(name: String, input: JsonObject, blockedPath: String?): String? = when (name) {
    "Write", "Edit", "MultiEdit", "NotebookEdit" -> str(input, "file_path").ifBlank { str(input, "notebook_path") }.ifBlank { null }
    "WebFetch" -> str(input, "url").ifBlank { null }
    else -> blockedPath
}

private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun toolPreview(name: String, input: JsonObject): String = when (name) {
    "Bash" -> "$ " + str(input, "command") + str(input, "description").takeIf { it.isNotBlank() }?.let { "\n# $it" }.orEmpty()
    "Write" -> str(input, "content").lines().take(40).joinToString("\n") { "+ $it" }
    "Edit" -> str(input, "old_string").lines().take(20).joinToString("\n") { "- $it" } + "\n" +
        str(input, "new_string").lines().take(20).joinToString("\n") { "+ $it" }
    "MultiEdit" -> "${(input["edits"] as? JsonArray)?.size ?: 0} edits"
    "WebFetch" -> str(input, "prompt")
    "WebSearch" -> str(input, "query")
    else -> input.entries.joinToString("\n") { (k, v) -> "$k: ${shortValue(v)}" }
}

private fun shortValue(v: JsonElement): String = when (v) {
    is JsonPrimitive -> v.contentOrNull.orEmpty().take(200)
    else -> v.toString().take(200)
}

/** Text for one `permission_suggestions` entry. */
private fun describeSuggestion(s: JsonObject): String {
    val where = when (str(s, "destination")) {
        "session" -> "for this session"
        "localSettings" -> "in this project (only me)"
        "projectSettings" -> "in this project (shared)"
        "userSettings" -> "everywhere"
        else -> ""
    }
    return when (str(s, "type")) {
        "addRules", "replaceRules" -> {
            val rules = (s["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString(", ") { r ->
                val content = str(r, "ruleContent")
                if (content.isBlank()) str(r, "toolName") else "${str(r, "toolName")}($content)"
            }
            "Always allow $rules $where".trim()
        }
        "addDirectories" -> "Allow access to ${(s["directories"] as? JsonArray).orEmpty().joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }} $where".trim()
        "setMode" -> "Switch to ${permissionModeLabel(str(s, "mode"))} $where".trim()
        else -> str(s, "type")
    }
}
