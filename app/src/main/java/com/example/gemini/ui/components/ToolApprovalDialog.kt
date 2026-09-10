package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.chat.PendingToolApproval

/**
 * Docked inline tool approval panel positioned directly above the chat input box.
 * Never dismissed accidentally by clicks outside; renders clean rows of approval requests.
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
    if (pendingApprovals.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
        color = Color(0xFF13141F),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Header Row: Status badge & Batch buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "Permission Required",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF59E0B).copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${pendingApprovals.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B)
                        )
                    }
                }

                if (pendingApprovals.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextButton(
                            onClick = onRejectAll,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = "Reject All",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFEF4444)
                            )
                        }

                        Button(
                            onClick = onApproveAll,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = "Accept All",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Rows list: max height ensures chat isn't obscured
            if (pendingApprovals.size == 1) {
                val item = pendingApprovals.first()
                ToolApprovalDockedItem(
                    approval = item,
                    onApprove = { scope -> onApprove(item.toolCall, item.messageId, scope) },
                    onReject = { onReject(item.toolCall, item.messageId) }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pendingApprovals, key = { it.toolCall.id }) { item ->
                        ToolApprovalDockedItem(
                            approval = item,
                            onApprove = { scope -> onApprove(item.toolCall, item.messageId, scope) },
                            onReject = { onReject(item.toolCall, item.messageId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolApprovalDockedItem(
    approval: PendingToolApproval,
    onApprove: (String) -> Unit,
    onReject: () -> Unit
) {
    val context = LocalContext.current
    val toolCall = approval.toolCall
    var isExpanded by remember { mutableStateOf(false) }
    var showScopeMenu by remember { mutableStateOf(false) }

    val isBash = toolCall.name == "bash" || toolCall.name == "run_command" || toolCall.name == "terminal"
    val isEditFile = toolCall.name == "edit_file" || toolCall.name == "modifyFile" || toolCall.name == "write_to_file" || toolCall.name == "replace_file_content"
    val isViewFile = toolCall.name == "view_file" || toolCall.name == "viewFile"
    val isListDir = toolCall.name == "list_dir" || toolCall.name == "listDirectory"

    val toolTypeLabel = when {
        isBash -> "SHELL COMMAND"
        isEditFile -> "FILE EDIT"
        isViewFile -> "FILE READ"
        isListDir -> "LIST DIR"
        else -> toolCall.name.uppercase()
    }

    val toolIcon = when {
        isBash -> Icons.Default.Terminal
        isEditFile -> Icons.Default.Edit
        isViewFile -> Icons.Default.Description
        isListDir -> Icons.Default.Folder
        else -> Icons.Default.Build
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0C0D15),
        border = BorderStroke(1.dp, Color(0xFF25273D))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Header: Tool badge + expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = toolIcon,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = toolTypeLabel,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isBash) "Execution in Termux" else "Tool Action",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (toolCall.command.length > 50 || toolCall.command.contains("\n")) {
                    Text(
                        text = if (isExpanded) "Collapse" else "Expand",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ClaudeTerracotta,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { isExpanded = !isExpanded }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Command / Action Preview
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF05060A),
                border = BorderStroke(1.dp, Color(0xFF1B1C2A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBash) "$ ${toolCall.command}" else toolCall.command,
                        fontFamily = if (isBash) FontFamily.Monospace else FontFamily.Default,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ClaudeTerracotta,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("command", toolCall.command))
                            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy command",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Actions Row: Reject, Run Once / Accept, Scope Dropdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    border = BorderStroke(
                        1.dp,
                        Color(0xFFEF4444).copy(alpha = 0.4f)
                    )
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Reject", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onApprove("PERMISSION_SCOPE_ONCE") },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBash) "Run Once" else "Accept",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isBash) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        IconButton(
                            onClick = { showScopeMenu = true },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Approval options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showScopeMenu,
                            onDismissRequest = { showScopeMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Always in This Chat", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                        Text("Allow this command for this conversation", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(15.dp))
                                },
                                onClick = {
                                    showScopeMenu = false
                                    onApprove("PERMISSION_SCOPE_CONVERSATION")
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Always in Workspace", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                        Text("Allow across entire workspace", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Security, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(15.dp))
                                },
                                onClick = {
                                    showScopeMenu = false
                                    onApprove("PERMISSION_SCOPE_WORKSPACE")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Floating Dialog variant retained for compatibility.
 * Configured so clicking outside DOES NOT dismiss.
 */
@Composable
fun ToolApprovalDialog(
    pendingApprovals: List<PendingToolApproval>,
    onApprove: (ToolCall, String, String) -> Unit,
    onReject: (ToolCall, String) -> Unit,
    onApproveAll: () -> Unit,
    onRejectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    if (pendingApprovals.isEmpty()) return

    Dialog(
        onDismissRequest = { /* Do not dismiss on outside click */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        ToolApprovalDockedPanel(
            pendingApprovals = pendingApprovals,
            onApprove = onApprove,
            onReject = onReject,
            onApproveAll = onApproveAll,
            onRejectAll = onRejectAll,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp)
        )
    }
}

