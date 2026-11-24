package ai.antigem.ui.components

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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
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
import ai.antigem.data.remote.HubMediaResolver
import ai.antigem.data.remote.dto.AskQuestionResponseItemDto
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
import ai.antigem.theme.*
import ai.antigem.i18n.L

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
    onSubmitChoices: ((ToolCall, List<AskQuestionResponseItemDto>, String) -> Unit)? = null,
    onSkipChoices: ((ToolCall, List<AskQuestionResponseItemDto>) -> Unit)? = null
) {
    val isChoice = toolCall.toolType == ToolType.ASK_CHOICE
    if (isChoice) {
        ChoiceQuestionnaireCard(
            toolCall = toolCall,
            onSubmit = { responses, summary -> onSubmitChoices?.invoke(toolCall, responses, summary) },
            onSkip = { responses -> onSkipChoices?.invoke(toolCall, responses) },
            onCancel = { onReject?.invoke(toolCall) },
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
    val isSystemNotice = toolCall.toolType == ToolType.SYSTEM_NOTIFICATION || toolCall.name == "system_notice"

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
        isPendingApproval && isMcp -> L.components.agentToolCallCard.approveMcp
        isPendingApproval -> L.components.agentToolCallCard.approveCommand
        isRunning && isSearch -> L.components.agentToolCallCard.searchingWeb
        isRunning && isReader -> L.components.agentToolCallCard.fetchingPage
        isRunning && isMath -> L.components.agentToolCallCard.evaluatingMath
        isRunning && isViewFile -> L.components.agentToolCallCard.readingFile
        isRunning && isEditFile -> L.components.agentToolCallCard.editingFile
        isRunning && isListDir -> L.components.agentToolCallCard.listingDirectory
        isRunning && (isFind || isGrep) -> L.components.agentToolCallCard.searchingCode
        isRunning && isGenImg -> L.components.agentToolCallCard.generatingImage
        isRunning && isMcp -> L.components.agentToolCallCard.executingMcpTool
        isRunning && isSystemNotice -> L.components.agentToolCallCard.systemEvent
        isRunning -> L.components.agentToolCallCard.executingInTermux

        isSuccess && isSystemNotice -> L.components.agentToolCallCard.system
        isSuccess && isSearch -> L.components.agentToolCallCard.webSearch
        isSuccess && isReader -> L.components.agentToolCallCard.readPage
        isSuccess && isMath -> L.components.agentToolCallCard.mathEvaluated
        isSuccess && isViewFile -> L.components.agentToolCallCard.readFile
        isSuccess && isEditFile -> L.components.agentToolCallCard.editedFile
        isSuccess && isListDir -> L.components.agentToolCallCard.listedDirectory
        isSuccess && (isFind || isGrep) -> L.components.agentToolCallCard.searchedCode
        isSuccess && isGenImg -> L.components.agentToolCallCard.generatedImage
        isSuccess && isMcp -> L.components.agentToolCallCard.mcpTool
        isSuccess -> L.components.agentToolCallCard.ranBash

        isTerminated -> L.components.agentToolCallCard.stopped
        isRejected -> L.components.agentToolCallCard.rejected
        isFailed -> L.components.agentToolCallCard.failed
        else -> L.components.agentToolCallCard.tool
    }

    val actionColor = when {
        isPendingApproval -> ai.antigem.theme.AppColors.warning
        isRunning -> ClaudeTerracotta
        isSuccess -> MaterialTheme.colorScheme.onSurface
        isTerminated || isFailed -> ai.antigem.theme.AppColors.danger
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
                contentDescription = if (isExpanded) L.common.collapse else L.common.expand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )

            Spacer(modifier = Modifier.width(4.dp))

            val isTimer = toolCall.command.contains("timer", ignoreCase = true) || toolCall.command.contains("schedule", ignoreCase = true)
            val toolIcon = when {
                isSystemNotice -> if (isTimer) Icons.Outlined.Schedule else Icons.Outlined.Notifications
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
                    contentDescription = L.components.agentToolCallCard.pendingApproval,
                    tint = ai.antigem.theme.AppColors.warning,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isRunning) {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = L.common.running,
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
                    contentDescription = L.components.agentToolCallCard.terminated,
                    tint = ai.antigem.theme.AppColors.danger,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isRejected) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = L.components.agentToolCallCard.rejected2,
                    tint = Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
            } else if (isFailed) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = L.common.failed,
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
                        .background(ai.antigem.theme.AppColors.danger.copy(alpha = 0.18f))
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = { onTerminate(toolCall) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = L.components.agentToolCallCard.stopCommand,
                        tint = ai.antigem.theme.AppColors.danger,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // Duration
            if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                Text(
                    text = formatDuration(toolCall.durationMs),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
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
                        text = L.components.agentToolCallCard.stopped2,
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

        // Always-visible Image Preview for Generate Image tool (once generated)
        if (isImageOutput && !isRunning && (toolCall.output.isNotBlank() || isSuccess)) {
            val displayUri = resolvedImageUri.ifBlank { toolCall.output }
            val coilData: Any? = remember(displayUri, resolvedImageUri) {
                    val memKey = HubMediaResolver.normalizeKey(toolCall.output)
                    val cachedBytes = HubMediaResolver.getImageBytes(memKey)
                    when {
                        cachedBytes != null -> cachedBytes
                        displayUri.startsWith("data:image/") -> {
                            try {
                                val b64 = displayUri.substringAfter("base64,")
                                if (b64.isNotBlank()) {
                                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                } else null
                            } catch (_: Exception) { displayUri }
                        }
                        displayUri.startsWith("file://") -> {
                            val f = java.io.File(displayUri.removePrefix("file://"))
                            if (f.exists() && f.canRead()) f else displayUri
                        }
                        displayUri.isNotBlank() -> {
                            val f = java.io.File(displayUri)
                            if (f.exists() && f.canRead()) f else displayUri
                        }
                        else -> null
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ai.antigem.theme.AppColors.terminalBg,
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
                                .background(ai.antigem.theme.AppColors.editorBg)
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
                                        text = L.components.agentToolCallCard.loadingImage,
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
                                text = L.components.agentToolCallCard.tapImageToEnlargeDoubleTap,
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
                                    contentDescription = L.components.agentToolCallCard.fullView,
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
                                    contentDescription = L.components.agentToolCallCard.downloadImage,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
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
                color = ai.antigem.theme.AppColors.terminalBg,
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
                            isSystemNotice -> L.components.agentToolCallCard.notificationCommand(toolCall.command)
                            isSearch -> L.components.agentToolCallCard.searchQueryCommand(toolCall.command)
                            isReader -> "URL: ${toolCall.command}"
                            isMath -> L.components.agentToolCallCard.casExprCommand(toolCall.command)
                            isViewFile -> L.components.agentToolCallCard.fileCommand(toolCall.command)
                            isEditFile -> L.components.agentToolCallCard.fileCommand(toolCall.command)
                            isListDir -> L.components.agentToolCallCard.directoryCommand(toolCall.command)
                            isGrep -> L.components.agentToolCallCard.grepCommand(toolCall.command)
                            isFind -> L.components.agentToolCallCard.findCommand(toolCall.command)
                            isGenImg -> L.components.agentToolCallCard.promptCommand(toolCall.command)
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

                        if (toolCall.durationMs != null && toolCall.durationMs > 0) {
                            Text(
                                text = "⏱️ ${formatDuration(toolCall.durationMs)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Tool Output", toolCall.output)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, L.components.agentToolCallCard.outputCopied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = L.components.agentToolCallCard.copyOutput,
                                tint = Color.Gray,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (toolCall.output.isEmpty()) {
                        val emptyMessage = when {
                            isRunning && isViewFile -> L.components.agentToolCallCard.readingFileContents
                            isRunning && isEditFile -> L.components.agentToolCallCard.applyingFileModifications
                            isRunning && isListDir -> L.components.agentToolCallCard.scanningDirectory
                            isRunning && (isFind || isGrep) -> L.components.agentToolCallCard.searching
                            isRunning && isGenImg -> L.components.agentToolCallCard.generatingImage
                            isRunning && isMcp -> L.components.agentToolCallCard.executingMcpTool
                            isRunning && isBash -> L.components.agentToolCallCard.runningInTermux
                            isRunning -> L.components.agentToolCallCard.processing
                            isPendingApproval -> L.components.agentToolCallCard.waitingForApproval
                            isViewFile -> L.components.agentToolCallCard.fileInspectedByModel
                            isEditFile -> L.components.agentToolCallCard.fileModificationCompleted
                            isListDir -> L.components.agentToolCallCard.directoryContentsScanned
                            isFind || isGrep -> L.components.agentToolCallCard.searchCompletedNoOutput
                            isGenImg -> L.components.agentToolCallCard.imageGenerationCompleted
                            isMcp -> L.components.agentToolCallCard.mcpToolExecutedNoOutput
                            else -> L.components.agentToolCallCard.noOutputReturned
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
                            color = ai.antigem.theme.AppColors.editorText,
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
