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
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.HubMediaResolver
import java.io.File
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.*

private val IMAGE_FILE_REGEX = Regex(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)

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
    val isChoice = toolCall.toolType == ToolType.ASK_CHOICE
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
    val isExpanded = ToolCallExpansionCache.isExpanded(toolCall, default = false)

    val isSearch = toolCall.toolType == ToolType.SEARCH_WEB
    val isReader = toolCall.toolType == ToolType.READ_URL
    val isMath = toolCall.toolType == ToolType.MATH
    val isViewFile = toolCall.toolType == ToolType.VIEW_FILE
    val isEditFile = toolCall.toolType == ToolType.EDIT_FILE
    val isListDir = toolCall.toolType == ToolType.LIST_DIR
    val isFind = toolCall.toolType == ToolType.FIND
    val isGrep = toolCall.toolType == ToolType.GREP_SEARCH
    val isGenImg = toolCall.toolType == ToolType.GENERATE_IMAGE
    val isMcp = toolCall.toolType == ToolType.MCP
    val isBash = toolCall.toolType == ToolType.BASH

    val isImageOutput = remember(toolCall.output, isGenImg) {
        isGenImg || toolCall.output.startsWith("data:image/") ||
        (toolCall.output.startsWith("file://") && toolCall.output.matches(IMAGE_FILE_REGEX))
    }

    var resolvedImageUri by remember(toolCall.output) {
        mutableStateOf(if (isImageOutput && toolCall.output.isNotBlank()) HubMediaResolver.getResolvedUriSync(context, toolCall.output) else "")
    }
    var isResolvingImage by remember(toolCall.output) { mutableStateOf(false) }
    var showFullScreenViewer by remember { mutableStateOf(false) }

    LaunchedEffect(toolCall.output, isImageOutput) {
        if (isImageOutput && toolCall.output.isNotBlank()) {
            if (!HubMediaResolver.isLocalOrCached(context, toolCall.output)) {
                isResolvingImage = true
                val res = HubMediaResolver.resolveMediaUri(context, toolCall.output)
                if (res.isNotBlank()) resolvedImageUri = res
                isResolvingImage = false
            } else {
                resolvedImageUri = HubMediaResolver.getResolvedUriSync(context, toolCall.output)
            }
        }
    }

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
                    .clickable { ToolCallExpansionCache.setExpanded(toolCall, !isExpanded) }
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
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta)
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
                    val toolIcon = when {
                        isSearch -> Icons.Outlined.Search
                        isReader -> Icons.Outlined.Language
                        isMath -> Icons.Outlined.Functions
                        isViewFile -> Icons.Default.Description
                        isEditFile -> Icons.Default.Edit
                        isListDir -> Icons.Default.Folder
                        isFind || isGrep -> Icons.Default.Search
                        isGenImg -> Icons.Default.Image
                        isMcp -> Icons.Default.Build
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
                    isPendingApproval && isMcp -> "Approve MCP Tool:"
                    isPendingApproval -> "Approval Needed:"
                    isRunning && isSearch -> "Searching Web:"
                    isRunning && isReader -> "Fetching Page:"
                    isRunning && isMath -> "Evaluating CAS Math:"
                    isRunning && isViewFile -> "Viewing File:"
                    isRunning && isEditFile -> "Editing File:"
                    isRunning && isListDir -> "Listing Directory:"
                    isRunning && (isFind || isGrep) -> "Searching Code:"
                    isRunning && isGenImg -> "Generating Image:"
                    isRunning && isMcp -> "Executing MCP Tool:"
                    isRunning -> "Executing in Termux:"

                    isSuccess && isSearch -> "Web Search:"
                    isSuccess && isReader -> "Read Webpage:"
                    isSuccess && isMath -> "Symja CAS Math Engine:"
                    isSuccess && isViewFile -> "Viewed File:"
                    isSuccess && isEditFile -> "Edited File:"
                    isSuccess && isListDir -> "Listed Directory:"
                    isSuccess && (isFind || isGrep) -> "Searched Code:"
                    isSuccess && isGenImg -> "Generated Image:"
                    isSuccess && isMcp -> "MCP Tool Output:"
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
                    fontFamily = if (isBash || isMcp) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                // Terminate (Stop) Button while Running
                if (isRunning && onTerminate != null && isBash) {
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




            // Always-visible Image Preview for Generate Image tool
            if (isGenImg) {
                if (isRunning) {
                    Surface(
                        shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                        color = Color(0xFF090A10),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeTerracotta
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Generating image with Gemini...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (toolCall.output.isNotBlank() || isSuccess) {
                    val displayUri = resolvedImageUri.ifBlank { toolCall.output }
                    Surface(
                        shape = if (isExpanded) RoundedCornerShape(0.dp) else RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                        color = Color(0xFF090A10),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF13151F))
                                    .clickable {
                                        if (displayUri.isNotBlank()) showFullScreenViewer = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isResolvingImage) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                            color = ClaudeTerracotta
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(displayUri)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = toolCall.command,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 140.dp, max = 340.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Tap image to enlarge • Double-tap zoom",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = { showFullScreenViewer = true },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Full view",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        shareMediaFile(context, displayUri, toolCall.command)
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Expanded Terminal / Tool Output
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
                            isMath -> "CAS Expr: ${toolCall.command}"
                            isViewFile -> "File: ${toolCall.command}"
                            isEditFile -> "File: ${toolCall.command}"
                            isListDir -> "Directory: ${toolCall.command}"
                            isGrep -> "Grep: ${toolCall.command}"
                            isFind -> "Find: ${toolCall.command}"
                            isGenImg -> "Prompt: ${toolCall.command}"
                            isMcp -> "MCP: ${toolCall.command}"
                            else -> if (isBash) "$ ${toolCall.command}" else toolCall.command
                        }
                        Text(
                            text = commandPrefix,
                            fontFamily = if (isBash || isMcp) FontFamily.Monospace else FontFamily.Default,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = ClaudeTerracotta,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Tool Output", toolCall.output)
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
                        val emptyMessage = when {
                            isRunning && isViewFile -> "Reading file contents..."
                            isRunning && isEditFile -> "Applying file modifications..."
                            isRunning && isListDir -> "Scanning directory..."
                            isRunning && (isFind || isGrep) -> "Searching..."
                            isRunning && isMcp -> "Executing MCP tool..."
                            isRunning && isBash -> "Running in Termux..."
                            isRunning -> "Processing..."
                            isPendingApproval -> "(Waiting for approval)"
                            isViewFile -> "(File inspected by model)"
                            isEditFile -> "(File modification completed)"
                            isListDir -> "(Directory contents scanned)"
                            isFind || isGrep -> "(Search completed - no output)"
                            isMcp -> "(MCP tool executed - no output)"
                            else -> "(No output returned)"
                        }
                        Text(
                            text = emptyMessage,
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

    if (showFullScreenViewer) {
        val displayUri = resolvedImageUri.ifBlank { toolCall.output }
        FullScreenImageDialog(
            imageUrl = displayUri,
            title = toolCall.command,
            onDismiss = { showFullScreenViewer = false }
        )
    }
}

private fun shareMediaFile(context: Context, mediaUriOrPath: String, title: String) {
    try {
        val clean = mediaUriOrPath.removePrefix("file://")
        val file = File(clean)
        if (file.exists() && file.length() > 0) {
            val uri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_TEXT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Image"))
        } else {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, if (title.isNotBlank()) "$title\n$mediaUriOrPath" else mediaUriOrPath)
            }
            context.startActivity(Intent.createChooser(intent, "Share Image"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

