package com.example.gemini.ui.models

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.domain.model.ThinkingPreference
import com.example.gemini.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    quotas: List<ModelQuota>,
    thinkingPreference: ThinkingPreference? = null,
    onOpenThinkingConfig: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Group models by category / family
    val claudeModels = availableModels.filter { it.family == ModelFamily.CLAUDE }
    val geminiModels = availableModels.filter { it.family == ModelFamily.GEMINI }
    val otherModels = availableModels.filter { it.family != ModelFamily.CLAUDE && it.family != ModelFamily.GEMINI }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Select Model",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${availableModels.size} models active",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }

                val rotation by if (isRefreshing) {
                    val infiniteTransition = rememberInfiniteTransition(label = "spin")
                    infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "spin_angle"
                    )
                } else {
                    remember { mutableFloatStateOf(0f) }
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh models & quotas",
                        tint = ClaudeTerracotta,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(rotation)
                    )
                }
            }

            // Thinking Quick Config Bar if callback available
            if (thinkingPreference != null && onOpenThinkingConfig != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onDismiss()
                            onOpenThinkingConfig()
                        },
                    color = if (thinkingPreference.isEnabled) ClaudeTerracotta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = if (thinkingPreference.isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reasoning Depth: ${thinkingPreference.level.label} (${if (thinkingPreference.isEnabled) "${thinkingPreference.activeTokens} tokens" else "Off"})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (thinkingPreference.isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tap to configure thinking token budget",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Configure Thinking",
                            tint = if (thinkingPreference.isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                if (claudeModels.isNotEmpty()) {
                    item {
                        CategoryHeader(title = "Anthropic Claude Models", icon = Icons.Outlined.AutoAwesome, color = ClaudeTerracotta)
                    }
                    items(claudeModels, key = { it.id }) { model ->
                        ModelRowItem(
                            model = model,
                            isSelected = model.id == selectedModelId,
                            quota = quotas.find { it.modelId == model.id },
                            onSelect = {
                                onSelectModel(model.id)
                                onDismiss()
                            }
                        )
                    }
                }

                if (geminiModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        CategoryHeader(title = "Google Gemini Models", icon = Icons.Outlined.AutoAwesome, color = GeminiBlue)
                    }
                    items(geminiModels, key = { it.id }) { model ->
                        ModelRowItem(
                            model = model,
                            isSelected = model.id == selectedModelId,
                            quota = quotas.find { it.modelId == model.id },
                            onSelect = {
                                onSelectModel(model.id)
                                onDismiss()
                            }
                        )
                    }
                }

                if (otherModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        CategoryHeader(title = "Other Antigravity Models", icon = Icons.Outlined.AutoAwesome, color = MaterialTheme.colorScheme.primary)
                    }
                    items(otherModels, key = { it.id }) { model ->
                        ModelRowItem(
                            model = model,
                            isSelected = model.id == selectedModelId,
                            quota = quotas.find { it.modelId == model.id },
                            onSelect = {
                                onSelectModel(model.id)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CategoryHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun ModelRowItem(
    model: AiModel,
    isSelected: Boolean,
    quota: ModelQuota?,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName,
                    fontSize = 14.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (model.supportsThinking) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Thinking",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta
                        )
                    }
                }
            }

            if (model.description.isNotBlank()) {
                Text(
                    text = model.description,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2
                )
            }

            // Live Quota badge
            if (quota?.remainingFraction != null) {
                val pct = quota.percentage
                val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    Text(
                        text = "Remaining: $pct%",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor
                    )
                    if (quota.resetTime != null) {
                        Text(
                            text = " • Resets: ${quota.resetTime.take(16).replace("T", " ")}",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = ClaudeTerracotta,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
