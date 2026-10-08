package com.example.gemini.ui.agents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import com.example.gemini.data.remote.dto.PermissionScopes
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.ui.chat.PendingToolApproval

/**
 * Antigravity's pending tool approvals as approval-panel items: allow once, always allow (conversation /
 * workspace / everywhere), or deny with a reason.
 */
fun agyApprovalItems(
    pending: List<PendingToolApproval>,
    onApprove: (toolCall: ToolCall, messageId: String, scope: String) -> Unit,
    onReject: (toolCall: ToolCall, messageId: String, reason: String?) -> Unit
): List<ApprovalItem> = pending.map { approval ->
    val call = approval.toolCall
    val (icon, title) = when (call.toolType) {
        ToolType.BASH -> Icons.Outlined.Terminal to "Run a command?"
        ToolType.EDIT_FILE -> Icons.Outlined.EditNote to "Edit a file?"
        ToolType.VIEW_FILE, ToolType.LIST_DIR, ToolType.FIND, ToolType.GREP_SEARCH -> Icons.Outlined.FolderOpen to "Read files?"
        ToolType.SEARCH_WEB -> Icons.Outlined.TravelExplore to "Search the web?"
        ToolType.READ_URL -> Icons.Outlined.Language to "Open a web page?"
        ToolType.GENERATE_IMAGE -> Icons.Outlined.Image to "Generate an image?"
        ToolType.MCP -> Icons.Outlined.Extension to "Use ${call.name.substringAfterLast("__")}?"
        else -> Icons.Outlined.Shield to "Allow ${call.name}?"
    }
    val body = call.command.ifBlank { call.output }
    fun scope(label: String, description: String, value: String) =
        ApprovalChoice(label, description) { onApprove(call, approval.messageId, value) }
    ApprovalItem(
        key = call.id,
        agent = AgentKind.AGY,
        icon = icon,
        title = title,
        subtitle = call.name,
        preview = if (call.toolType == ToolType.BASH) "$ $body" else body,
        primary = ApprovalChoice("Allow") { onApprove(call, approval.messageId, PermissionScopes.ONCE) },
        always = listOf(
            scope("For this conversation", "Don't ask again in this chat", PermissionScopes.CONVERSATION),
            scope("In this workspace", "Don't ask again in this project", PermissionScopes.PROJECT),
            scope("Everywhere", "Don't ask again anywhere", PermissionScopes.GLOBAL)
        ),
        denyPlaceholder = "Tell Antigravity what to do instead (optional)",
        onDeny = { onReject(call, approval.messageId, it) }
    )
}
