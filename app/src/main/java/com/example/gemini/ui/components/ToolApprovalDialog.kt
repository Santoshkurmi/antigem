package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.chat.PendingToolApproval

/**
 * Docked tool approval panel positioned directly above the chat input box.
 * Renders ONE approval request at a time for maximum clarity and focus.
 */
@Composable
fun ToolApprovalDockedPanel(
    pendingApprovals: List<PendingToolApproval>,
    onApprove: (ToolCall, String, String) -> Unit,
    onReject: (ToolCall, String) -> Unit,
    onApproveAll: () -> Unit,
    onRejectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentApproval = pendingApprovals.firstOrNull() ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF141522),
        border = BorderStroke(1.dp, Color(0xFF2E3048)),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (pendingApprovals.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Pending Tool Approvals (1 of ${pendingApprovals.size})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onRejectAll,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Reject All", fontSize = 11.sp, color = Color(0xFFEF4444))
                        }

                        Button(
                            onClick = onApproveAll,
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Accept All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            ToolApprovalDockedItem(
                approval = currentApproval,
                onApprove = { scope -> onApprove(currentApproval.toolCall, currentApproval.messageId, scope) },
                onReject = { onReject(currentApproval.toolCall, currentApproval.messageId) }
            )
        }
    }
}

@Composable
fun ToolApprovalDockedItem(
    approval: PendingToolApproval,
    onApprove: (String) -> Unit,
    onReject: () -> Unit
) {
    val toolCall = approval.toolCall

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D0E17),
        border = BorderStroke(1.dp, Color(0xFF222436))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = toolCall.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerracotta
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = toolCall.command.ifBlank { toolCall.output },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
                ) {
                    Text("Reject", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { onApprove("PERMISSION_SCOPE_ONCE") },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Accept", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun ToolApprovalDialog(
    pendingApprovals: List<PendingToolApproval>,
    onApprove: (ToolCall, String, String) -> Unit,
    onReject: (ToolCall, String) -> Unit,
    onApproveAll: () -> Unit,
    onRejectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    ToolApprovalDockedPanel(
        pendingApprovals = pendingApprovals,
        onApprove = onApprove,
        onReject = onReject,
        onApproveAll = onApproveAll,
        onRejectAll = onRejectAll
    )
}
