package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.example.gemini.theme.ClaudeTerracotta

@Composable
fun ThinkingAccordion(
    thoughtText: String,
    durationMs: Long?,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(isStreaming) }
    var hasUserManuallyToggled by remember { mutableStateOf(false) }

    // Auto-expand while actively streaming thoughts if user hasn't manually toggled
    LaunchedEffect(isStreaming) {
        if (isStreaming && !hasUserManuallyToggled) {
            isExpanded = true
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "thought_chevron_rotation"
    )

    val previewText = remember(thoughtText) {
        thoughtText.trim()
            .lines()
            .map { it.trim().removePrefix("#").removePrefix("*").removePrefix("-").trim() }
            .firstOrNull { it.isNotBlank() } ?: ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // Clean borderless inline header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    hasUserManuallyToggled = true
                    isExpanded = !isExpanded
                }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(15.dp)
                    .rotate(rotation)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = "Thinking",
                tint = ClaudeTerracotta.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Thinking...",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = ClaudeTerracotta
                )
            } else {
                val title = if (durationMs != null && durationMs > 0) "Thought for ${formatDuration(durationMs)}" else "Thinking process"
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )

                if (!isExpanded && previewText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = previewText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }

        // Clean borderless expandable content with subtle left accent line
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
            ) {
                // Subtle vertical left bar
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(ClaudeTerracotta.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = thoughtText,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isStreaming && durationMs != null && durationMs > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⏱️ Thought for ${formatDuration(durationMs)}",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = ClaudeTerracotta.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}
