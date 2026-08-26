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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.theme.ClaudeTerracotta

@Composable
fun MessageBubble(
    message: ChatMessage,
    modelId: String = "gemini",
    onEdit: (ChatMessage) -> Unit = {},
    onRetry: (ChatMessage) -> Unit = {},
    onApproveTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onRejectTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onTerminateTool: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    onSubmitChoices: ((com.example.gemini.domain.model.ToolCall, String, String) -> Unit)? = null,
    onSkipChoices: ((com.example.gemini.domain.model.ToolCall, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    var showUserActions by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Thinking block (for assistant responses)
        if (!isUser && !message.thoughtText.isNullOrEmpty()) {
            ThinkingAccordion(
                thoughtText = message.thoughtText,
                durationMs = message.thoughtDurationMs,
                isStreaming = message.isStreaming && message.content.isEmpty()
            )
            Spacer(modifier = Modifier.height(4.dp))
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
                } else if (message.isStreaming && message.thoughtText.isNullOrEmpty()) {
                    ModelTypingIndicator(modelId = modelId)
                }

                // Copy and Retry action buttons for assistant responses
                if (!message.isStreaming && message.content.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
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
                    }
                }
            }
        }
    }
}

@Composable
fun ModelTypingIndicator(
    modelId: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    @Composable
    fun animateDot(delay: Int): Float {
        val anim by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -6f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    0f at 0
                    -6f at 300
                    0f at 600
                    0f at 1000
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delay)
            ),
            label = "dot_bounce"
        )
        return anim
    }

    val offset1 = animateDot(0)
    val offset2 = animateDot(200)
    val offset3 = animateDot(400)

    val label = when {
        modelId.contains("claude", ignoreCase = true) -> "Claude is thinking..."
        modelId.contains("gemini", ignoreCase = true) -> "Gemini is thinking..."
        modelId.contains("gpt", ignoreCase = true) -> "GPT is thinking..."
        else -> "Thinking..."
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer { translationY = offset1 }
                .clip(CircleShape)
                .background(ClaudeTerracotta)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer { translationY = offset2 }
                .clip(CircleShape)
                .background(ClaudeTerracotta.copy(alpha = 0.8f))
        )
        Spacer(modifier = Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer { translationY = offset3 }
                .clip(CircleShape)
                .background(ClaudeTerracotta.copy(alpha = 0.6f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}
