package ai.antigem.ui.components

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.theme.*
import java.text.NumberFormat
import java.util.Locale
import ai.antigem.i18n.L

@Composable
fun ChatTelemetryDialog(
    conversation: Conversation?,
    messages: List<ChatMessage>,
    onOpenSystemPrompt: (() -> Unit)? = null,
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
                        text = L.components.chatTelemetry.chatTelemetryTokens,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = L.components.chatTelemetry.aggregatedUsageTelemetryForValue(conversation?.title ?: L.components.chatTelemetry.currentChat),
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
                    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    val inputColor = if (isDark) ai.antigem.theme.AppColors.info else ai.antigem.theme.AppColors.info
                    val outputColor = if (isDark) ai.antigem.theme.AppColors.success else ai.antigem.theme.AppColors.success
                    val cacheColor = ai.antigem.theme.AppColors.warning

                    // Total Token Metric Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMetricCard(
                            title = L.components.chatTelemetry.inputTokens,
                            value = numberFormat.format(totalPromptTokens),
                            icon = Icons.Outlined.ArrowDownward,
                            iconColor = inputColor,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricCard(
                            title = L.components.chatTelemetry.outputTokens,
                            value = numberFormat.format(totalOutputTokens),
                            icon = Icons.Outlined.ArrowUpward,
                            iconColor = outputColor,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryMetricCard(
                            title = L.components.chatTelemetry.cacheReadHits,
                            value = if (totalCachedTokens > 0) "${numberFormat.format(totalCachedTokens)} ($cacheEfficiencyPct%)" else "0",
                            icon = Icons.Outlined.FlashOn,
                            iconColor = cacheColor,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryMetricCard(
                            title = L.components.chatTelemetry.totalConsumption,
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
                                text = L.components.chatTelemetry.conversationStats,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            TelemetryRow(label = L.components.chatTelemetry.activeModel, value = conversation?.modelId ?: L.common.default)
                            TelemetryRow(label = L.components.chatTelemetry.userTurns, value = L.components.chatTelemetry.countTurns(userMessagesCount))
                            TelemetryRow(label = L.components.chatTelemetry.modelResponses, value = L.components.chatTelemetry.countResponses(assistantMessagesCount))
                            TelemetryRow(label = L.components.chatTelemetry.toolsExecuted, value = L.components.chatTelemetry.countTools(toolsExecutedCount))
                            TelemetryRow(label = L.components.chatTelemetry.activeContextSummary, value = if (!conversation?.summary.isNullOrBlank()) L.common.active else L.components.chatTelemetry.none)
                            TelemetryRow(label = L.components.chatTelemetry.customSystemPrompt, value = if (!conversation?.customSystemPrompt.isNullOrBlank()) L.components.chatTelemetry.configured else L.common.default)
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
                                    text = L.components.chatTelemetry.chatSystemPrompt,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (!conversation?.customSystemPrompt.isNullOrBlank()) L.components.chatTelemetry.customInstructionsActive else L.components.chatTelemetry.usingDefaultSystemInstruction,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (onOpenSystemPrompt != null) {
                                TextButton(
                                    onClick = {
                                        onDismiss()
                                        onOpenSystemPrompt()
                                    }
                                ) {
                                    Text(L.common.edit, fontSize = 12.5.sp, color = ClaudeTerracotta, fontWeight = FontWeight.SemiBold)
                                }
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
                    Text(L.common.close, color = Color.White, fontSize = 13.sp)
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
