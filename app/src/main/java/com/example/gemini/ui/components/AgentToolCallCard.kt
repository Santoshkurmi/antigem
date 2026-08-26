package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.theme.*

/**
 * Interactive inline tool call card rendered inside assistant messages.
 * Shows status (Executing, Success, Failed), command preview, duration, and expandable terminal stdout.
 */
@Composable
fun AgentToolCallCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
    onApprove: ((ToolCall) -> Unit)? = null,
    onReject: ((ToolCall) -> Unit)? = null,
    onTerminate: ((ToolCall) -> Unit)? = null,
    onSubmitChoices: ((ToolCall, String) -> Unit)? = null,
    onSkipChoices: ((ToolCall) -> Unit)? = null
) {
    val isChoice = toolCall.name == "ask_choices" || toolCall.name == "user_choice"
    if (isChoice) {
        ChoiceQuestionnaireCard(
            toolCall = toolCall,
            onSubmit = { summary -> onSubmitChoices?.invoke(toolCall, summary) },
            onSkip = { onSkipChoices?.invoke(toolCall) },
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    val isSearch = toolCall.name == "web_search" || toolCall.name == "search"
    val isReader = toolCall.name == "read_url" || toolCall.name == "web_reader"

    val isPendingApproval = toolCall.status == "PENDING_APPROVAL"
    val isRunning = toolCall.status == "RUNNING"
    val isTerminated = toolCall.status == "TERMINATED"
    val isRejected = toolCall.status == "REJECTED"
    val isSuccess = toolCall.status == "SUCCESS" || (toolCall.status != "FAILED" && !isTerminated && !isRejected && !isPendingApproval && toolCall.exitCode == 0)
    val isFailed = toolCall.status == "FAILED" || (toolCall.exitCode != null && toolCall.exitCode != 0 && !isTerminated && !isRejected)

    val borderColor = when {
        isPendingApproval -> Color(0xFFF59E0B).copy(alpha = 0.6f)
        isRunning -> ClaudeTerracotta.copy(alpha = 0.5f)
        isSuccess -> QuotaGreen.copy(alpha = 0.35f)
        isTerminated -> Color(0xFFEF4444).copy(alpha = 0.5f)
        isRejected -> Color.Gray.copy(alpha = 0.35f)
        isFailed -> Color.Red.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }

    val headerBg = when {
        isPendingApproval -> Color(0xFFF59E0B).copy(alpha = 0.12f)
        isRunning -> ClaudeTerracotta.copy(alpha = 0.1f)
        isSuccess -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        isTerminated -> Color(0xFFEF4444).copy(alpha = 0.1f)
        isRejected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        isFailed -> Color.Red.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { isExpanded = !isExpanded }
                    .background(headerBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Icon
                if (isPendingApproval) {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = "Pending Approval",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(15.dp)
                    )
                } else if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ClaudeTerracotta
                    )
                } else if (isSuccess) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Success",
                        tint = QuotaGreen,
                        modifier = Modifier.size(15.dp)
                    )
                } else if (isTerminated) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Terminated",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(15.dp)
                    )
                } else if (isRejected) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Rejected",
                        tint = Color.Gray,
                        modifier = Modifier.size(15.dp)
                    )
                } else if (isFailed) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Failed",
                        tint = Color.Red,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    val toolIcon = when (toolCall.name) {
                        "web_search", "search" -> Icons.Outlined.Search
                        "read_url", "web_reader" -> Icons.Outlined.Language
                        else -> Icons.Default.Terminal
                    }
                    Icon(
                        imageVector = toolIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action Label & Command
                val actionPrefix = when {
                    isPendingApproval -> "Approval Needed:"
                    isRunning && isSearch -> "Searching Web:"
                    isRunning && isReader -> "Fetching Page:"
                    isRunning -> "Executing in Termux:"
                    isSuccess && isSearch -> "Web Search:"
                    isSuccess && isReader -> "Read Webpage:"
                    isSuccess -> "Executed:"
                    isTerminated -> "Terminated:"
                    isRejected -> "Rejected:"
                    isFailed -> "Failed:"
                    else -> "Tool:"
                }

                val actionColor = when {
                    isPendingApproval -> Color(0xFFF59E0B)
                    isRunning -> ClaudeTerracotta
                    isSuccess -> QuotaGreen
                    isTerminated || isFailed -> Color(0xFFEF4444)
                    isRejected -> Color.Gray
                    else -> MaterialTheme.colorScheme.onSurface
                }

                Text(
                    text = actionPrefix,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = actionColor
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = toolCall.command,
                    fontFamily = if (isSearch) FontFamily.Default else FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                // Terminate (Stop) Button while Running
                if (isRunning && onTerminate != null && !isSearch && !isReader) {
                    IconButton(
                        onClick = { onTerminate(toolCall) },
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.18f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop command",
                            tint = Color.Red,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Duration & Exit Code Badges
                if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                    Text(
                        text = "${toolCall.durationMs}ms",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                if (isTerminated) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Red.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "stopped",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Red
                        )
                    }
                } else if (toolCall.exitCode != null && !isRunning && !isPendingApproval && !isRejected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSuccess) QuotaGreen.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "exit ${toolCall.exitCode}",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) QuotaGreen else Color.Red
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle output",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Pending Approval Action Bar
            if (isPendingApproval) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0E15).copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "AI wants to execute this shell command in Termux:",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0D0E15),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$ ${toolCall.command}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = ClaudeTerracotta,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onReject?.invoke(toolCall) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Reject", fontSize = 11.5.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onApprove?.invoke(toolCall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Run Command", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Expanded Terminal Output
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0E15))
                        .padding(10.dp)
                ) {
                    // Command Bar with copy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val commandPrefix = when {
                            isSearch -> "Search Query: \"${toolCall.command}\""
                            isReader -> "URL: ${toolCall.command}"
                            else -> "$ ${toolCall.command}"
                        }
                        Text(
                            text = commandPrefix,
                            fontFamily = if (isSearch) FontFamily.Default else FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = ClaudeTerracotta,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Command Output", toolCall.output)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy Output",
                                tint = Color.Gray,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (toolCall.output.isEmpty()) {
                        Text(
                            text = if (isRunning) "Running in Termux..." else if (isPendingApproval) "(Waiting for approval)" else "(No output returned)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    } else {
                        Text(
                            text = toolCall.output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE2E8F0),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}
