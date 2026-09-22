package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.*

private val IMAGE_FILE_REGEX = Regex(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)

/**
 * Clean, professional inline tool call component matching Cursor & Claude developer UI.
 * Rendered without heavy bordered boxes — features an animated frontend chevron (->), status icon,
 * concise action description, and smoothly expandable terminal stdout output.
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
    var isResolvingImage by remember(toolCall.output) {
        mutableStateOf(isImageOutput && toolCall.output.isNotBlank() && !HubMediaResolver.isLocalOrCached(context, toolCall.output))
    }
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
                isResolvingImage = false
            }
        }
    }

    val isPendingApproval = toolCall.status == "PENDING_APPROVAL"
    val isRunning = toolCall.status == "RUNNING"
    val isTerminated = toolCall.status == "TERMINATED"
    val isRejected = toolCall.status == "REJECTED"
    val isSuccess = toolCall.status == "SUCCESS" || (toolCall.status != "FAILED" && !isTerminated && !isRejected && !isPendingApproval && toolCall.exitCode == 0)
    val isFailed = toolCall.status == "FAILED" || (toolCall.exitCode != null && toolCall.exitCode != 0 && !isTerminated && !isRejected)

    val actionPrefix = when {
        isPendingApproval && isMcp -> "Approve MCP:"
        isPendingApproval -> "Approve Command:"
        isRunning && isSearch -> "Searching web..."
        isRunning && isReader -> "Fetching page..."
        isRunning && isMath -> "Evaluating math..."
        isRunning && isViewFile -> "Reading file..."
        isRunning && isEditFile -> "Editing file..."
        isRunning && isListDir -> "Listing directory..."
        isRunning && (isFind || isGrep) -> "Searching code..."
        isRunning && isGenImg -> "Generating image..."
        isRunning && isMcp -> "Executing MCP tool..."
        isRunning -> "Executing in Termux..."

        isSuccess && isSearch -> "Web search"
        isSuccess && isReader -> "Read page"
        isSuccess && isMath -> "Math evaluated"
        isSuccess && isViewFile -> "Read file"
        isSuccess && isEditFile -> "Edited file"
        isSuccess && isListDir -> "Listed directory"
        isSuccess && (isFind || isGrep) -> "Searched code"
        isSuccess && isGenImg -> "Generated image"
        isSuccess && isMcp -> "MCP tool"
        isSuccess -> "Ran bash"

        isTerminated -> "Stopped:"
        isRejected -> "Rejected:"
        isFailed -> "Failed:"
        else -> "Tool:"
    }

    val actionColor = when {
        isPendingApproval -> Color(0xFFF59E0B)
        isRunning -> ClaudeTerracotta
        isSuccess -> MaterialTheme.colorScheme.onSurface
        isTerminated || isFailed -> Color(0xFFEF4444)
        isRejected -> Color.Gray
        else -> MaterialTheme.colorScheme.onSurface
    }

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "tool_chevron_rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // Flat, borderless single-line Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { ToolCallExpansionCache.setExpanded(toolCall, !isExpanded) }
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frontend Expand Chevron (-> / rotating arrow)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )

            Spacer(modifier = Modifier.width(4.dp))

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

            // Tool / State Icon
            if (isPendingApproval) {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Pending Approval",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(14.dp)
                )
            } else if (isRunning) {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = "Running",
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isSuccess) {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    tint = QuotaGreen,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isTerminated) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Terminated",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(14.dp)
                )
            } else if (isRejected) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Rejected",
                    tint = Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isFailed) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Failed",
                    tint = Color.Red,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Action Verb
            Text(
                text = actionPrefix,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = actionColor
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Command / Target argument
            Text(
                text = toolCall.command,
                fontFamily = if (isBash || isMcp || isViewFile || isEditFile) FontFamily.Monospace else FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Pristine Circular Terminate (Stop) Button while Running
            if (isRunning && onTerminate != null && isBash) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.18f))
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = { onTerminate(toolCall) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop command",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Duration in ms
            if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                Text(
                    text = "${toolCall.durationMs}ms",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Exit Code Badge
            if (isTerminated) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Red.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "stopped",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
            } else if (toolCall.exitCode != null && !isRunning && !isPendingApproval && !isRejected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSuccess) QuotaGreen.copy(alpha = 0.12f) else Color.Red.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "exit ${toolCall.exitCode}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSuccess) QuotaGreen else Color.Red
                    )
                }
            }
        }

        // Always-visible Image Preview for Generate Image tool
        if (isImageOutput) {
            if (isRunning) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF090A10),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generating image with Gemini...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (toolCall.output.isNotBlank() || isSuccess) {
                val displayUri = resolvedImageUri.ifBlank { toolCall.output }
                val coilData = remember(displayUri) {
                    val memKey = HubMediaResolver.normalizeKey(toolCall.output)
                    val cachedBytes = HubMediaResolver.getImageBytes(memKey)
                    when {
                        cachedBytes != null -> cachedBytes
                        displayUri.startsWith("data:image/") -> {
                            try {
                                val b64 = displayUri.substringAfter("base64,")
                                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            } catch (_: Exception) { displayUri }
                        }
                        else -> displayUri
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF090A10),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
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
                                    Text(
                                        text = "Loading image...",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(coilData)
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

                            val snackbarHostState = LocalSnackbarHostState.current
                            val coroutineScope = rememberCoroutineScope()
                            IconButton(
                                onClick = {
                                    ImageDownloadHelper.downloadImage(
                                        context = context,
                                        imageSource = displayUri,
                                        title = toolCall.command,
                                        coroutineScope = coroutineScope,
                                        snackbarHostState = snackbarHostState
                                    )
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download Image",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
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
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0D0E15),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
