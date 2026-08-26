package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.theme.*
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ChatTelemetryDialog(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    onOpenSystemPrompt: () -> Unit,
    onDismiss: () -> Unit
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    // Aggregate token statistics across all turns
    val turnsWithUsage = messages.mapNotNull { it.tokenUsage }
    val totalPromptTokens = turnsWithUsage.sumOf { it.promptTokens }
    val totalOutputTokens = turnsWithUsage.sumOf { it.outputTokens }
    val totalCachedTokens = turnsWithUsage.sumOf { it.cachedTokens }
    val totalCreationTokens = turnsWithUsage.sumOf { it.cacheCreationTokens }
    val totalCombinedTokens = totalPromptTokens + totalOutputTokens

    val userMessagesCount = messages.count { it.role == MessageRole.USER }
    val assistantMessagesCount = messages.count { it.role == MessageRole.ASSISTANT }
    val toolsExecutedCount = messages.sumOf { it.toolCalls.size }

    val cacheEfficiencyPct = if (totalPromptTokens > 0 && totalCachedTokens > 0) {
        ((totalCachedTokens.toDouble() / (totalPromptTokens + totalCachedTokens)) * 100).toInt().coerceIn(0, 100)
    } else 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.78f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Chat Telemetry & Tokens",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Aggregated usage telemetry for \"${conversation?.title ?: "Current Chat"}\":",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Total Token Metric Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMetricCard(
                            title = "Input Tokens",
                            value = numberFormat.format(totalPromptTokens),
                            icon = Icons.Outlined.ArrowDownward,
                            iconColor = Color(0xFF64B5F6),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricCard(
                            title = "Output Tokens",
                            value = numberFormat.format(totalOutputTokens),
                            icon = Icons.Outlined.ArrowUpward,
                            iconColor = Color(0xFF81C784),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMetricCard(
                            title = "Cache Read Hits",
                            value = if (totalCachedTokens > 0) "${numberFormat.format(totalCachedTokens)} ($cacheEfficiencyPct%)" else "0",
                            icon = Icons.Outlined.FlashOn,
                            iconColor = Color(0xFFFFD54F),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricCard(
                            title = "Total Consumption",
                            value = numberFormat.format(totalCombinedTokens),
                            icon = Icons.Outlined.Functions,
                            iconColor = ClaudeTerracotta,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Conversation Metadata
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Conversation Stats",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            TelemetryRow(label = "Active Model", value = conversation?.modelId ?: "Default")
                            TelemetryRow(label = "User Turns", value = "$userMessagesCount turns")
                            TelemetryRow(label = "Model Responses", value = "$assistantMessagesCount responses")
                            TelemetryRow(label = "Tools Executed", value = "$toolsExecutedCount tools")
                            TelemetryRow(label = "Active Context Summary", value = if (!conversation?.summary.isNullOrBlank()) "Active" else "None")
                            TelemetryRow(label = "Custom System Prompt", value = if (!conversation?.customSystemPrompt.isNullOrBlank()) "Configured" else "Default")
                        }
                    }

                    // Custom System Prompt Action Button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Chat System Prompt",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (!conversation?.customSystemPrompt.isNullOrBlank()) "Custom instructions active" else "Using default system instruction",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    onDismiss()
                                    onOpenSystemPrompt()
                                }
                            ) {
                                Text("Edit", fontSize = 12.5.sp, color = ClaudeTerracotta, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
