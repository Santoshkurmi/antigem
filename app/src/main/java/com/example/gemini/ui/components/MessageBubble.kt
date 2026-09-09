package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import android.net.Uri
import android.content.Intent
import java.io.File
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modelId: String = "gemini",
    isDevModeEnabled: Boolean = false,
    onEdit: (ChatMessage) -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    onApproveTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onRejectTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onTerminateTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onSubmitChoices: ((com.example.gemini.domain.model.ToolCall, String, String) -> Unit)? = null,
    onSkipChoices: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onUpdateSummary: ((String) -> Unit)? = null,
    onDeleteSummary: (() -> Unit)? = null,
    onViewRawPayload: ((String) -> Unit)? = null,
    summarizingModelName: String = "AI",
    pendingQueuedUserMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (message.role == MessageRole.SUMMARY) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (message.isStreaming) {
                LiveSummarizingCard(
                    modelName = summarizingModelName,
                    pendingQueuedMessage = pendingQueuedUserMessage
                )
            } else {
                ActiveContextSummaryCard(
                    summaryText = message.content,
                    onEditSummary = { onUpdateSummary?.invoke(it) },
                    onDeleteSummary = { onDeleteSummary?.invoke() }
                )
            }
        }
        return
    }

    val isUser = message.role == MessageRole.USER
    var showUserActions by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (isUser) 20.dp else 4.dp,
                bottom = if (isUser) 6.dp else 10.dp
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        val hasActiveRunningTool = message.toolCalls.any { it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE" }

        // Thinking block (for assistant responses)
        if (!isUser && !message.thoughtText.isNullOrEmpty()) {
            ThinkingAccordion(
                thoughtText = message.thoughtText,
                durationMs = message.thoughtDurationMs,
                isStreaming = message.isStreaming && !hasActiveRunningTool
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Context Compaction / Summary Banner (if this turn contains a summary)
        if (!message.contextSummary.isNullOrBlank() && message.contextSummary != "null") {
            ContextSummaryCheckpointBanner(summaryText = message.contextSummary)
        }

        // Message Content Bubble
        if (isUser) {
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showUserActions = !showUserActions }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Action icons shown on tap/press below sent user message
                AnimatedVisibility(
                    visible = showUserActions,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp, end = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Copy Prompt
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Copied Prompt", message.content)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied prompt", Toast.LENGTH_SHORT).show()
                                showUserActions = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Edit Prompt
                        IconButton(
                            onClick = {
                                onEdit(message)
                                showUserActions = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit prompt",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Retry Prompt
                        IconButton(
                            onClick = {
                                onRetry(message)
                                showUserActions = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Retry prompt",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        val payloadToShow = message.rawContent ?: message.rawPayload
                        if (!payloadToShow.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    onViewRawPayload?.invoke(payloadToShow)
                                    showUserActions = false
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DataObject,
                                    contentDescription = "View Raw Request Payload",
                                    tint = Color(0xFF8BE9FD),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Assistant response
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.content.isNotEmpty() || message.toolCalls.isNotEmpty()) {
                    SelectionContainer {
                        MarkdownContent(
                            content = message.content,
                            toolCalls = message.toolCalls,
                            onApproveTool = if (onApproveTool != null) { toolCall -> onApproveTool(toolCall, message.id) } else null,
                            onRejectTool = if (onRejectTool != null) { toolCall -> onRejectTool(toolCall, message.id) } else null,
                            onTerminateTool = if (onTerminateTool != null) { toolCall -> onTerminateTool(toolCall, message.id) } else null,
                            onSubmitChoices = if (onSubmitChoices != null) { toolCall, summary -> onSubmitChoices(toolCall, message.id, summary) } else null,
                            onSkipChoices = if (onSkipChoices != null) { toolCall -> onSkipChoices(toolCall, message.id) } else null
                        )
                    }

                } else if (message.isStreaming && !hasActiveRunningTool && message.content.isEmpty() && message.thoughtText.isNullOrEmpty() && message.toolCalls.isEmpty()) {
                    ModelTypingIndicator(modelId = modelId)
                }

                // Copy, Retry, Raw Payload and Token Telemetry for assistant responses
                if (!message.isStreaming && message.content.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Copied Response", message.content)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy message",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = { onRetry(message) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Regenerate response",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            val payloadToShow = message.rawContent ?: message.rawPayload
                            if (!payloadToShow.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onViewRawPayload?.invoke(payloadToShow) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DataObject,
                                        contentDescription = "View Raw Request Payload",
                                        tint = Color(0xFF8BE9FD),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Token & Cache Telemetry Badge (Always shown when metrics exist)
                        if (message.tokenUsage != null) {
                            val payloadToShow = message.rawContent ?: message.rawPayload
                            Spacer(modifier = Modifier.height(4.dp))
                            TokenUsageTelemetryPill(
                                usage = message.tokenUsage,
                                onViewPayload = if (!payloadToShow.isNullOrBlank()) {
                                    { onViewRawPayload?.invoke(payloadToShow) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserMessageBubble(
    message: ChatMessage,
    isLastUserMessage: Boolean = false,
    isDevModeEnabled: Boolean = false,
    onEdit: (ChatMessage) -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    onViewRawPayload: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUserActions by remember { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    if (previewImageUrl != null) {
        FullScreenImageDialog(
            imageUrl = previewImageUrl!!,
            title = "Image Preview",
            onDismiss = { previewImageUrl = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (!message.contextSummary.isNullOrBlank() && message.contextSummary != "null") {
            ContextSummaryCheckpointBanner(summaryText = message.contextSummary)
        }

        Box(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { showUserActions = !showUserActions }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Audio attachments player
                val audioAtts = message.attachments.filter { it.isAudio }
                for (att in audioAtts) {
                    ChatAudioPlayer(attachment = att)
                }

                // 2. Image attachments preview
                val imageAtts = message.attachments.filter {
                    it.isImage || it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) ||
                            it.name.endsWith(".jpeg", true) || it.name.endsWith(".webp", true) ||
                            it.name.endsWith(".gif", true) || it.mimeType?.startsWith("image/") == true
                }
                if (imageAtts.isNotEmpty()) {
                    UserMessageImagesGrid(
                        attachments = imageAtts,
                        onImageClick = { previewImageUrl = it }
                    )
                }

                // 3. Document / other attachments
                val docAtts = message.attachments.filter { !it.isAudio && !imageAtts.contains(it) }
                if (docAtts.isNotEmpty()) {
                    for (doc in docAtts) {
                        UserMessageDocumentItem(attachment = doc)
                    }
                }

                // 4. Text content (if not just placeholder "Voice note")
                val displayContent = formatUserDisplayContent(message.content)
                val lineCount = remember(displayContent) { displayContent.lines().size }
                val isLongText = remember(displayContent, lineCount) { lineCount > 6 || displayContent.length > 350 }
                var isTextExpanded by remember { mutableStateOf(false) }

                if (displayContent.isNotBlank() && !(audioAtts.isNotEmpty() && (displayContent == "Voice note" || displayContent == "Voice message"))) {
                    Column {
                        SelectionContainer {
                            Text(
                                text = displayContent,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (isLongText && !isTextExpanded) 6 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isLongText) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { isTextExpanded = !isTextExpanded }
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isTextExpanded) "Show less ▲" else "Show more (${lineCount} lines) ▼",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ClaudeTerracotta
                                )
                            }
                        }
                    }
                }
            }
        }

        // Action icons shown on tap/press below sent user message
        AnimatedVisibility(
            visible = showUserActions,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy Prompt
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Copied Prompt", message.content)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied prompt", Toast.LENGTH_SHORT).show()
                        showUserActions = false
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Edit Prompt (ONLY shown for the last user message)
                if (isLastUserMessage) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            onEdit(message)
                            showUserActions = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Undo and edit prompt",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                val payloadToShow = message.rawContent ?: message.rawPayload
                if (isDevModeEnabled && !payloadToShow.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            onViewRawPayload?.invoke(payloadToShow)
                            showUserActions = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DataObject,
                            contentDescription = "View Raw Request Payload",
                            tint = Color(0xFF8BE9FD),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantMessageFooter(
    message: ChatMessage,
    isDevModeEnabled: Boolean = false,
    onRetry: (ChatMessage) -> Unit = {},
    onViewRawPayload: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Response", message.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = { onRetry(message) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Regenerate response",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }

            val payloadToShow = message.rawContent ?: message.rawPayload
            if (!payloadToShow.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onViewRawPayload?.invoke(payloadToShow) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DataObject,
                        contentDescription = "View Raw Request Payload",
                        tint = Color(0xFF8BE9FD),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Token & Cache Telemetry Badge (Always shown when metrics exist)
        if (message.tokenUsage != null) {
            val payloadToShow = message.rawContent ?: message.rawPayload
            Spacer(modifier = Modifier.height(4.dp))
            TokenUsageTelemetryPill(
                usage = message.tokenUsage,
                onViewPayload = if (!payloadToShow.isNullOrBlank()) {
                    { onViewRawPayload?.invoke(payloadToShow) }
                } else null
            )
        }
    }
}

private val WAITING_PHRASES = listOf(
    "Consulting AGY",
    "Cooking response",
    "Crafting answer",
    "Gathering context",
    "Working on it",
    "Synthesizing",
    "Connecting dots",
    "Analyzing request",
    "Formulating response"
)

@Composable
fun ModelTypingIndicator(
    modelId: String,
    modifier: Modifier = Modifier
) {
    val phrase = remember(modelId) { WAITING_PHRASES.random() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = ClaudeTerracotta.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.22f))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            WaitingDotsText(phrase = phrase)
        }
    }
}

@Composable
private fun WaitingDotsText(phrase: String) {
    val dots by produceState(initialValue = ".") {
        var count = 1
        while (true) {
            delay(1000L)
            count = (count % 3) + 1
            value = ".".repeat(count)
        }
    }

    Text(
        text = "$phrase$dots",
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = ClaudeTerracotta,
        letterSpacing = 0.2.sp
    )
}

@Composable
fun TokenUsageTelemetryPill(
    usage: com.example.gemini.domain.model.TokenUsage,
    onViewPayload: (() -> Unit)? = null
) {
    val nf = NumberFormat.getNumberInstance(Locale.US)
    val cachePct = if (usage.promptTokens > 0 && usage.cachedTokens > 0) {
        ((usage.cachedTokens.toDouble() / (usage.promptTokens + usage.cachedTokens)) * 100).toInt().coerceIn(0, 100)
    } else 0

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onViewPayload != null) Modifier.clickable { onViewPayload() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "📥 ${nf.format(usage.promptTokens)}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF64B5F6)
            )
            Text(
                text = "📤 ${nf.format(usage.outputTokens)}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF81C784)
            )
            if (usage.cachedTokens > 0) {
                Text(
                    text = "⚡ ${nf.format(usage.cachedTokens)} ($cachePct%)",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFFD54F)
                )
            }
            if (usage.durationMs > 0) {
                val secStr = String.format(Locale.US, "%.1fs", usage.durationMs / 1000f)
                Text(
                    text = "⏱️ $secStr",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (usage.isEstimated) {
                Text(
                    text = "(est.)",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ContextSummaryCheckpointBanner(
    summaryText: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🧠 Context Compacted (History Summarized)",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeTerracotta,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "Hide ▲" else "Show Details ▼",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = summaryText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

private fun formatUserDisplayContent(content: String): String {
    val imageRegex = Regex("""\[Attached Image:\s*([^\]]+)\]\([^\)]+\)""", RegexOption.IGNORE_CASE)
    val fileRegex = Regex("""\[Attached File:\s*([^\]]+)\]\([^\)]+\)""", RegexOption.IGNORE_CASE)
    return content
        .replace(imageRegex) { "[${it.groupValues[1].trim()}]" }
        .replace(fileRegex) { "[${it.groupValues[1].trim()}]" }
        .trim()
}

@Composable
private fun UserMessageImageItem(
    attachment: com.example.gemini.domain.model.ChatAttachment,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit
) {
    val context = LocalContext.current
    val rawUri = remember(attachment) {
        attachment.localUri ?: attachment.url ?: (if (attachment.path.startsWith("file://") || attachment.path.startsWith("http")) attachment.path else "file://${attachment.path}")
    }
    var resolvedUri by remember(rawUri) {
        mutableStateOf(HubMediaResolver.getResolvedUriSync(context, rawUri))
    }

    LaunchedEffect(rawUri) {
        if (!HubMediaResolver.isLocalOrCached(context, rawUri)) {
            val res = HubMediaResolver.resolveMediaUri(context, rawUri)
            if (res.isNotBlank()) {
                resolvedUri = res
            }
        }
    }

    val finalUri = resolvedUri.ifBlank { rawUri }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onImageClick(finalUri) }
    ) {
        AsyncImage(
            model = finalUri,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun UserMessageImagesGrid(
    attachments: List<com.example.gemini.domain.model.ChatAttachment>,
    onImageClick: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    if (attachments.size == 1) {
        UserMessageImageItem(
            attachment = attachments.first(),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            onImageClick = onImageClick
        )
    } else if (attachments.size == 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (att in attachments) {
                UserMessageImageItem(
                    attachment = att,
                    modifier = Modifier
                        .weight(1f)
                        .height(130.dp),
                    onImageClick = onImageClick
                )
            }
        }
    } else {
        // 3 or more images: chunked in rows of 2
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val chunks = attachments.chunked(2)
            for (row in chunks) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (att in row) {
                        UserMessageImageItem(
                            attachment = att,
                            modifier = Modifier
                                .weight(1f)
                                .height(110.dp),
                            onImageClick = onImageClick
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserMessageDocumentItem(
    attachment: com.example.gemini.domain.model.ChatAttachment
) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                try {
                    val uri = when {
                        !attachment.localUri.isNullOrBlank() -> Uri.parse(attachment.localUri)
                        attachment.path.isNotBlank() && File(attachment.path).exists() -> {
                            val file = File(attachment.path)
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                        }
                        attachment.path.isNotBlank() -> {
                            val cached = HubMediaResolver.getLocalCacheFile(context, attachment.path)
                            if (cached.exists() && cached.length() > 0) {
                                androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    cached
                                )
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, attachment.mimeType ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open ${attachment.name}"))
                    } else {
                        Toast.makeText(context, attachment.name, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = ClaudeTerracotta,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (attachment.size > 0L) {
                    val sizeKb = attachment.size / 1024.0
                    val sizeStr = if (sizeKb >= 1024) String.format("%.1f MB", sizeKb / 1024.0) else String.format("%.0f KB", sizeKb)
                    Text(
                        text = sizeStr,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

