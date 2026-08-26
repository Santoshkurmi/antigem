package com.example.gemini.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*

enum class CategoryType {
    CLAUDE,
    GEMINI,
    OTHER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    quotas: List<ModelQuota>,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Group models by category / family
    val claudeModels = availableModels.filter { it.family == ModelFamily.CLAUDE }
    val geminiModels = availableModels.filter { it.family == ModelFamily.GEMINI }
    val otherModels = availableModels.filter { it.family != ModelFamily.CLAUDE && it.family != ModelFamily.GEMINI }

    val activeModel = availableModels.find { it.id == selectedModelId }

    // Navigation state inside the sheet: null = category list, CategoryType = subcategory sheet
    var activeCategory by remember { mutableStateOf<CategoryType?>(null) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // Intercept back press when inside a subcategory inside the sheet window
        BackHandler(enabled = activeCategory != null) {
            activeCategory = null
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
        ) {
            AnimatedContent(
                targetState = activeCategory,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "category_transition"
            ) { currentCategory ->
                if (currentCategory == null) {
                    // Main Level: Active Model + Category Selection Tiles
                    MainCategoryListView(
                        activeModel = activeModel,
                        claudeModelsCount = claudeModels.size,
                        geminiModelsCount = geminiModels.size,
                        otherModelsCount = otherModels.size,
                        availableModelsCount = availableModels.size,
                        quotas = quotas,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onSelectCategory = { category -> activeCategory = category },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Sub-Sheet: Models inside the chosen category
                    val (categoryTitle, categoryIcon, brandColor, categoryModels) = when (currentCategory) {
                        CategoryType.CLAUDE -> Quadruple("Anthropic Claude Models", Icons.Outlined.AutoAwesome, ClaudeTerracotta, claudeModels)
                        CategoryType.GEMINI -> Quadruple("Google Gemini Models", Icons.Outlined.AutoAwesome, GeminiBlue, geminiModels)
                        CategoryType.OTHER -> Quadruple("Other Antigravity Models", Icons.Outlined.AutoAwesome, MaterialTheme.colorScheme.primary, otherModels)
                    }

                    CategoryModelsSubView(
                        title = categoryTitle,
                        icon = categoryIcon,
                        brandColor = brandColor,
                        models = categoryModels,
                        selectedModelId = selectedModelId,
                        quotas = quotas,
                        onBack = { activeCategory = null },
                        onSelectModel = { modelId ->
                            onSelectModel(modelId)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Main Level Sheet View
 */
@Composable
private fun MainCategoryListView(
    activeModel: AiModel?,
    claudeModelsCount: Int,
    geminiModelsCount: Int,
    otherModelsCount: Int,
    availableModelsCount: Int,
    quotas: List<ModelQuota>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectCategory: (CategoryType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
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
                    text = if (availableModelsCount > 0) "$availableModelsCount models available" else "No models loaded",
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

        Spacer(modifier = Modifier.height(14.dp))

        if (availableModelsCount == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No Models Loaded",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Connect your Google account in Settings or tap refresh to load your active models.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // 1. Featured Active Model at the Top
            if (activeModel != null) {
                Text(
                    text = "CURRENTLY ACTIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                ActiveModelCard(
                    model = activeModel,
                    quota = quotas.find { it.modelId == activeModel.id }
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            Text(
                text = "CHOOSE MODEL CATEGORY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            // Category Navigation Tiles
            if (claudeModelsCount > 0) {
                CategoryNavigationTile(
                    title = "Anthropic Claude",
                    subtitle = "Sonnet, Opus & Claude Reasoning Models",
                    count = claudeModelsCount,
                    icon = Icons.Outlined.AutoAwesome,
                    brandColor = ClaudeTerracotta,
                    onClick = { onSelectCategory(CategoryType.CLAUDE) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (geminiModelsCount > 0) {
                CategoryNavigationTile(
                    title = "Google Gemini",
                    subtitle = "Flash, Pro & Multimodal Models",
                    count = geminiModelsCount,
                    icon = Icons.Outlined.AutoAwesome,
                    brandColor = GeminiBlue,
                    onClick = { onSelectCategory(CategoryType.GEMINI) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (otherModelsCount > 0) {
                CategoryNavigationTile(
                    title = "Other Antigravity Models",
                    subtitle = "Experimental & Specialized AI Models",
                    count = otherModelsCount,
                    icon = Icons.Outlined.AutoAwesome,
                    brandColor = MaterialTheme.colorScheme.primary,
                    onClick = { onSelectCategory(CategoryType.OTHER) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

/**
 * Subcategory Sheet View showing the models inside the chosen family
 */
@Composable
private fun CategoryModelsSubView(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    brandColor: Color,
    models: List<AiModel>,
    selectedModelId: String,
    quotas: List<ModelQuota>,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    Column(
        modifier = modifier
            .padding(bottom = 24.dp)
    ) {
        // Back Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to categories",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = brandColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${models.size} models available",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(models, key = { it.id }) { model ->
                ModelRowItem(
                    model = model,
                    isSelected = model.id == selectedModelId,
                    quota = quotas.find { it.modelId == model.id },
                    onSelect = { onSelectModel(model.id) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/**
 * Clickable Category Tile with forward arrow navigating to the sub-sheet.
 */
@Composable
private fun CategoryNavigationTile(
    title: String,
    subtitle: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    brandColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = brandColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(brandColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = "$count",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Open $title",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * Top Featured Card showing the currently active model.
 */
@Composable
private fun ActiveModelCard(
    model: AiModel,
    quota: ModelQuota?
) {
    val familyColor = if (model.family == ModelFamily.CLAUDE) ClaudeTerracotta else GeminiBlue
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, familyColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(familyColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = familyColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (model.supportsThinking) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ClaudeTerracotta.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
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
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1
                    )
                }

                if (quota?.remainingFraction != null) {
                    val pct = quota.percentage
                    val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                    Text(
                        text = "Remaining Quota: $pct%",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(familyColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Active",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = familyColor
                )
            }
        }
    }
}

/**
 * Individual selectable Model Row inside a chosen category.
 */
@Composable
private fun ModelRowItem(
    model: AiModel,
    isSelected: Boolean,
    quota: ModelQuota?,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect() },
        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) ClaudeTerracotta.copy(alpha = 0.4f) else Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Thinking",
                                fontSize = 9.5.sp,
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

                if (quota?.remainingFraction != null) {
                    val pct = quota.percentage
                    val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp)
                    ) {
                        Text(
                            text = "Quota: $pct%",
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
}
