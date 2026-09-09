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
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
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
                        availableModels = availableModels,
                        claudeModelsCount = claudeModels.size,
                        geminiModelsCount = geminiModels.size,
                        otherModelsCount = otherModels.size,
                        availableModelsCount = availableModels.size,
                        quotas = quotas,
                        quotaSummary = quotaSummary,
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
    availableModels: List<AiModel>,
    claudeModelsCount: Int,
    geminiModelsCount: Int,
    otherModelsCount: Int,
    availableModelsCount: Int,
    quotas: List<ModelQuota>,
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
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
            val geminiGroup = quotaSummary?.groups?.find {
                it.groupId == "gemini" || it.groupName.contains("gemini", ignoreCase = true)
            }
            val claudeGroup = quotaSummary?.groups?.find {
                it.groupId == "claude_gpt" || it.groupName.contains("claude", ignoreCase = true) || it.groupName.contains("gpt", ignoreCase = true)
            }

            if (claudeModelsCount > 0) {
                val fiveHour = claudeGroup?.fiveHour
                val weekly = claudeGroup?.weekly
                val line1 = if (fiveHour != null) {
                    "5-Hour: ${fiveHour.remainingPct} left (${fiveHour.usedPct} used)" +
                            (if (fiveHour.countdown.isNotBlank()) " • ${fiveHour.countdown}" else "")
                } else {
                    val q = quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }
                    if (q?.percentage != null) "5-Hour: ${q.percentage}% left • Resets in ${q.resetCountdown ?: "soon"}" else "5-Hour: 100% available"
                }
                val line2 = if (weekly != null) {
                    "Weekly: ${weekly.remainingPct} left (${weekly.usedPct} used)" +
                            (if (weekly.countdown.isNotBlank()) " • ${weekly.countdown}" else "")
                } else null

                CategoryNavigationTile(
                    title = "Anthropic Claude & GPT",
                    line1 = line1,
                    line2 = line2,
                    count = claudeModelsCount,
                    icon = Icons.Outlined.AutoAwesome,
                    brandColor = ClaudeTerracotta,
                    onClick = { onSelectCategory(CategoryType.CLAUDE) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (geminiModelsCount > 0) {
                val fiveHour = geminiGroup?.fiveHour
                val weekly = geminiGroup?.weekly
                val line1 = if (fiveHour != null) {
                    "5-Hour: ${fiveHour.remainingPct} left (${fiveHour.usedPct} used)" +
                            (if (fiveHour.countdown.isNotBlank()) " • ${fiveHour.countdown}" else "")
                } else {
                    val q = quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }
                    if (q?.percentage != null) "5-Hour: ${q.percentage}% left • Resets in ${q.resetCountdown ?: "soon"}" else "5-Hour: 100% available"
                }
                val line2 = if (weekly != null) {
                    "Weekly: ${weekly.remainingPct} left (${weekly.usedPct} used)" +
                            (if (weekly.countdown.isNotBlank()) " • ${weekly.countdown}" else "")
                } else null

                CategoryNavigationTile(
                    title = "Google Gemini",
                    line1 = line1,
                    line2 = line2,
                    count = geminiModelsCount,
                    icon = Icons.Outlined.AutoAwesome,
                    brandColor = GeminiBlue,
                    onClick = { onSelectCategory(CategoryType.GEMINI) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (otherModelsCount > 0) {
                val fiveHour = geminiGroup?.fiveHour
                val weekly = geminiGroup?.weekly
                val line1 = if (fiveHour != null) {
                    "5-Hour: ${fiveHour.remainingPct} left (${fiveHour.usedPct} used)" +
                            (if (fiveHour.countdown.isNotBlank()) " • ${fiveHour.countdown}" else "")
                } else {
                    val q = quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.OTHER } }
                    if (q?.percentage != null) "5-Hour: ${q.percentage}% left • Resets in ${q.resetCountdown ?: "soon"}" else "5-Hour: 100% available"
                }
                val line2 = if (weekly != null) {
                    "Weekly: ${weekly.remainingPct} left (${weekly.usedPct} used)" +
                            (if (weekly.countdown.isNotBlank()) " • ${weekly.countdown}" else "")
                } else null

                CategoryNavigationTile(
                    title = "Other Antigravity Models",
                    line1 = line1,
                    line2 = line2,
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
 * Subcategory Sheet View showing the models inside the chosen family,
 * grouping models that share base names with different thinking tiers (High, Medium, Low).
 */
data class ModelVariant(
    val model: AiModel,
    val tierLabel: String
)

data class SubGroupedModel(
    val baseId: String,
    val baseName: String,
    val description: String,
    val family: ModelFamily,
    val variants: List<ModelVariant>
)

private fun groupModelsByBaseName(models: List<AiModel>): List<SubGroupedModel> {
    val map = LinkedHashMap<String, MutableList<AiModel>>()

    for (model in models) {
        val baseId = if (model.baseName.isNotBlank()) {
            model.baseName
        } else {
            val id = model.key.ifBlank { model.id }
            when {
                id.endsWith("-high", ignoreCase = true) -> id.substringBeforeLast("-high")
                id.endsWith("-medium", ignoreCase = true) -> id.substringBeforeLast("-medium")
                id.endsWith("-med", ignoreCase = true) -> id.substringBeforeLast("-med")
                id.endsWith("-low", ignoreCase = true) -> id.substringBeforeLast("-low")
                id.endsWith("-extra-low", ignoreCase = true) -> id.substringBeforeLast("-extra-low")
                else -> id
            }
        }
        map.getOrPut(baseId) { mutableListOf() }.add(model)
    }

    return map.map { (baseId, modelList) ->
        val first = modelList.first()
        val cleanBaseName = first.baseName.ifBlank {
            first.displayName
                .replace(Regex("\\s*\\((High|Medium|Low|Med|Thinking)\\)", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        val variants = modelList.map { m ->
            val label = m.tier?.ifBlank { null } ?: when {
                m.key.endsWith("-high", ignoreCase = true) || m.id.endsWith("-high", ignoreCase = true) || m.displayName.contains("High", ignoreCase = true) -> "High"
                m.key.endsWith("-medium", ignoreCase = true) || m.key.endsWith("-med", ignoreCase = true) || m.id.endsWith("-medium", ignoreCase = true) || m.displayName.contains("Medium", ignoreCase = true) || m.displayName.contains("Med", ignoreCase = true) -> "Medium"
                m.key.endsWith("-low", ignoreCase = true) || m.id.endsWith("-low", ignoreCase = true) || m.displayName.contains("Low", ignoreCase = true) -> "Low"
                else -> ""
            }
            ModelVariant(m, label)
        }

        SubGroupedModel(
            baseId = baseId,
            baseName = cleanBaseName,
            description = first.description,
            family = first.family,
            variants = variants
        )
    }
}

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

    val groupedModels = remember(models) { groupModelsByBaseName(models) }

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
                    text = "${groupedModels.size} base model${if (groupedModels.size != 1) "s" else ""} • ${models.size} variants",
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
            items(groupedModels, key = { it.baseId }) { group ->
                if (group.variants.size > 1) {
                    TieredModelGroupCard(
                        group = group,
                        selectedModelId = selectedModelId,
                        quota = quotas.find { it.modelId == selectedModelId || group.variants.any { v -> v.model.id == it.modelId } },
                        onSelectModel = onSelectModel
                    )
                } else {
                    val model = group.variants.first().model
                    ModelRowItem(
                        model = model,
                        isSelected = model.id == selectedModelId,
                        quota = quotas.find { it.modelId == model.id },
                        onSelect = { onSelectModel(model.id) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Multi-tier model card allowing direct selection of thinking effort (High, Medium, Low).
 */
@Composable
private fun TieredModelGroupCard(
    group: SubGroupedModel,
    selectedModelId: String,
    quota: ModelQuota?,
    onSelectModel: (String) -> Unit
) {
    val isAnyVariantSelected = group.variants.any { it.model.id == selectedModelId }
    val brandColor = when (group.family) {
        ModelFamily.CLAUDE -> ClaudeTerracotta
        ModelFamily.GEMINI -> GeminiBlue
        ModelFamily.OTHER -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = if (isAnyVariantSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (isAnyVariantSelected) brandColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(brandColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = brandColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.baseName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (quota?.remainingFraction != null) {
                        val pct = quota.percentage
                        val badgeColor = if (pct > 50) QuotaGreen else if (pct > 20) QuotaAmber else QuotaRed
                        val countdownStr = quota.resetCountdown?.let { " • $it" } ?: ""
                        Text(
                            text = "5h: $pct% left$countdownStr",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = badgeColor,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }

                if (isAnyVariantSelected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = "Active",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Thinking Effort Selector Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Thinking Effort:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(end = 8.dp)
                )

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    group.variants.forEach { variant ->
                        val isSelected = variant.model.id == selectedModelId
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectModel(variant.model.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) brandColor else brandColor.copy(alpha = 0.1f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) brandColor else brandColor.copy(alpha = 0.25f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                }
                                Text(
                                    text = variant.tierLabel.ifBlank { variant.model.displayName },
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
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
    line1: String,
    line2: String? = null,
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
                    .size(40.dp)
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

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = line1,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 1
                )

                if (!line2.isNullOrBlank()) {
                    Text(
                        text = line2,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
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
                    val usedPctStr = quota.usedPercentage ?: "${100 - pct}%"
                    val countdownStr = quota.resetCountdown?.let { " • $it" } ?: ""
                    Text(
                        text = "5h Limit: $pct% left ($usedPctStr used)$countdownStr",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    if (!quota.weeklyResetCountdown.isNullOrBlank() || !quota.weeklyUsedPercentage.isNullOrBlank()) {
                        val weeklyPct = quota.weeklyRemainingFraction?.let { (it * 100).toInt() } ?: 100
                        val weeklyUsedStr = quota.weeklyUsedPercentage ?: "${100 - weeklyPct}%"
                        val weeklyCountdownStr = quota.weeklyResetCountdown?.let { " • $it" } ?: ""
                        Text(
                            text = "Weekly: $weeklyPct% left ($weeklyUsedStr used)$weeklyCountdownStr",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
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
