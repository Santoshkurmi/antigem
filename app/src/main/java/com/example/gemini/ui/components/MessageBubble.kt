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
    isDevModeEnabled: Boolean = false,
    onEdit: (ChatMessage) -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    onViewRawPayload: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showUserActions by remember { mutableStateOf(false) }

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

