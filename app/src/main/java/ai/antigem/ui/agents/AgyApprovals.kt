package ai.antigem.ui.agents

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import ai.antigem.data.remote.dto.PermissionScopes
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
import ai.antigem.ui.chat.PendingToolApproval
import ai.antigem.i18n.L

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
        ToolType.BASH -> Icons.Outlined.Terminal to L.common.runACommand
        ToolType.EDIT_FILE -> Icons.Outlined.EditNote to L.agents.agyApprovals.editAFile
        ToolType.VIEW_FILE, ToolType.LIST_DIR, ToolType.FIND, ToolType.GREP_SEARCH -> Icons.Outlined.FolderOpen to L.agents.agyApprovals.readFiles
        ToolType.SEARCH_WEB -> Icons.Outlined.TravelExplore to L.common.searchTheWeb
        ToolType.READ_URL -> Icons.Outlined.Language to L.agents.agyApprovals.openAWebPage
        ToolType.GENERATE_IMAGE -> Icons.Outlined.Image to L.agents.agyApprovals.generateAnImage
        ToolType.MCP -> Icons.Outlined.Extension to L.agents.agyApprovals.useName(call.name.substringAfterLast("__"))
        else -> Icons.Outlined.Shield to L.agents.agyApprovals.allowName(call.name)
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
        primary = ApprovalChoice(L.common.allow) { onApprove(call, approval.messageId, PermissionScopes.ONCE) },
        always = listOf(
            scope(L.agents.agyApprovals.forThisConversation, L.agents.agyApprovals.dontAskAgainInThisChat, PermissionScopes.CONVERSATION),
            scope(L.agents.agyApprovals.inThisWorkspace, L.agents.agyApprovals.dontAskAgainInThisProject, PermissionScopes.PROJECT),
            scope(L.agents.agyApprovals.everywhere, L.agents.agyApprovals.dontAskAgainAnywhere, PermissionScopes.GLOBAL)
        ),
        denyPlaceholder = L.agents.agyApprovals.tellAntigravityWhatToDoInstead,
        onDeny = { onReject(call, approval.messageId, it) }
    )
}
